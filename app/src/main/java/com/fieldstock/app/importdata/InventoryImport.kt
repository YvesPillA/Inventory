package com.fieldstock.app.importdata

import com.fieldstock.app.model.IdentifierRules
import com.fieldstock.app.model.ItemKind
import com.fieldstock.app.model.InventorySnapshot
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.diagnostics.AppDiagnostics
import com.fieldstock.app.i18n.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

data class ImportRow(
    val kind: ItemKind,
    val bom: String,
    val sn: String,
    val quantity: Int,
    val condition: String,
    val note: String,
    val pn: String = ""
)

sealed interface InventoryImportContent {
    data class Rows(val rows: List<ImportRow>) : InventoryImportContent
    data class Backup(val snapshot: InventorySnapshot) : InventoryImportContent
}

/** Content determines format. Parsing never evaluates formulas or changes identifier case. */
object InventoryImport {
    fun readFile(input: InputStream, fileName: String?): InventoryImportContent {
        val source = input.buffered()
        source.mark(4096)
        val prefix = ByteArray(4096)
        val count = source.read(prefix)
        source.reset()
        val json = count > 0 && prefix.copyOf(count).toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart().startsWith("{")
        AppDiagnostics.event("import.format",mapOf("format" to if(json)"JSON" else if(count>=2&&prefix[0]==80.toByte()&&prefix[1]==75.toByte())"XLSX_OR_ZIP" else "CSV"))
        return if (json) InventoryImportContent.Backup(InventoryFiles.readBackup(source))
        else InventoryImportContent.Rows(read(source, fileName))
    }
    /** Hash parsed values, so a rename, UTF-8 BOM or line-ending change does not evade the reminder. */
    fun fingerprint(rows: List<ImportRow>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        // Preserve receipts for legacy CSVs, including a new template with an empty PN column.
        val hasPn = rows.any { it.pn.isNotEmpty() }
        digest.update((if (hasPn) "fieldstock-csv-v2" else "fieldstock-csv-v1").toByteArray(Charsets.UTF_8))
        rows.forEach { row ->
            (listOf(row.kind.name, row.bom, row.sn, row.quantity.toString(), row.condition, row.note) +
                if (hasPn) listOf(row.pn) else emptyList()).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_ROWS = 10_000
    private const val MAX_QUANTITY = 1_000_000_000
    private const val MAX_FIELD_CHARS = 4000
    private val header = listOf("类型", "BOM", "SN", "数量", "状态", "备注")
    private val englishHeader = listOf("Type", "BOM", "SN", "Quantity", "Condition", "Note")
    internal fun isHeader(fields: List<String>): Boolean = fields.size in 6..7 &&
        header.indices.all { fields[it] == header[it] || fields[it].equals(englishHeader[it], ignoreCase = true) } &&
        (fields.size == 6 || fields[6].equals("PN", ignoreCase = true))
    private enum class State { START, UNQUOTED, QUOTED, AFTER_QUOTE }

    /** Header only: example inventory must never accidentally become a real imported record. */
    fun writeTemplate(output: OutputStream, language: AppLanguage = AppLanguage.current) {
        val columns = if (language == AppLanguage.ENGLISH) englishHeader else header
        output.write(("\uFEFF" + (columns + "PN").joinToString(",") + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    fun read(input: InputStream, fileName: String?): List<ImportRow> {
        val bytes = readLimited(input)
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return InventoryReportImport.read(bytes)
        }
        return readCsv(bytes, fileName)
    }

    internal fun readCsv(bytes: ByteArray, fileName: String?): List<ImportRow> {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (failure: CharacterCodingException) {
            val hint = fileName?.take(80)?.filterNot(Char::isISOControl)?.let { "（$it）" }.orEmpty()
            throw IllegalArgumentException(tr(TextKey.LINE_1_FILE_IS_NOT_VALID_UTF_8_CSV, hint), failure)
        }
        val rows = mutableListOf<ImportRow>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var state = State.START
        var line = 1
        var rowStartLine = 1
        var foundHeader = false
        var expectedColumns = 0
        var reportColumns: ReportColumns? = null
        var headers = emptyList<String>()
        var index = 0

        fun finishField() {
            if (fields.size >= 64) invalid(rowStartLine, tr(TextKey.IMPORT_TOO_MANY_COLUMNS))
            fields.add(field.toString())
            field.setLength(0)
            state = State.START
        }
        fun finishRow() {
            finishField()
            if (fields.any { it.isNotBlank() }) {
                if (!foundHeader) {
                    if (!isHeader(fields)) {
                        reportColumns = InventoryReportImport.columns(fields)
                            ?: invalid(rowStartLine, tr(TextKey.HEADERS_MUST_BE_IN_THIS_ORDER_TYPE_BOM_SN))
                    }
                    foundHeader = true
                    expectedColumns = fields.size
                    headers = fields.toList()
                } else {
                    if (isHeader(fields) || fields == headers) invalid(rowStartLine, tr(TextKey.REPEATED_HEADER_KEEP_ONLY_THE_FIRST_HEADER_ROW))
                    if (fields.size != expectedColumns) invalid(rowStartLine, tr(TextKey.CSV_COLUMN_COUNT, expectedColumns, fields.size))
                    if (rows.size >= MAX_ROWS) invalid(rowStartLine, tr(TextKey.DATA_MUST_NOT_EXCEED_10000_ROWS_IMPORT_IN_SMALLER))
                    rows.add(validate(reportColumns?.standardFields(fields) ?: fields, rowStartLine))
                }
            }
            fields.clear()
        }
        fun append(character: Char) {
            if (field.length >= MAX_FIELD_CHARS) invalid(rowStartLine, tr(TextKey.EACH_FIELD_MUST_NOT_EXCEED_4000_CHARACTERS))
            field.append(character)
        }

        while (index < text.length) {
            val character = text[index]
            if (character.isISOControl() && character !in listOf('\t', '\r', '\n')) invalid(line, tr(TextKey.THE_FILE_CONTAINS_AN_INVALID_CONTROL_CHARACTER))
            when {
                character == '\r' || character == '\n' -> {
                    val crlf = character == '\r' && index + 1 < text.length && text[index + 1] == '\n'
                    if (state == State.QUOTED) {
                        append(character)
                        if (crlf) append('\n')
                    } else finishRow()
                    if (crlf) index++
                    line++
                    if (state != State.QUOTED) rowStartLine = line
                }
                character == ',' && state != State.QUOTED -> finishField()
                character == '"' -> when (state) {
                    State.START -> state = State.QUOTED
                    State.UNQUOTED -> invalid(line, tr(TextKey.A_DOUBLE_QUOTE_APPEARS_INSIDE_AN_UNQUOTED_FIELD))
                    State.AFTER_QUOTE -> invalid(line, tr(TextKey.ONLY_A_COMMA_LINE_BREAK_OR_END_OF_FILE))
                    State.QUOTED -> {
                        if (index + 1 < text.length && text[index + 1] == '"') { append('"'); index++ }
                        else state = State.AFTER_QUOTE
                    }
                }
                state == State.AFTER_QUOTE -> invalid(line, tr(TextKey.ONLY_A_COMMA_LINE_BREAK_OR_END_OF_FILE))
                else -> { append(character); if (state == State.START) state = State.UNQUOTED }
            }
            index++
        }
        if (state == State.QUOTED) invalid(rowStartLine, tr(TextKey.UNCLOSED_DOUBLE_QUOTE))
        if (state != State.START || field.isNotEmpty() || fields.isNotEmpty()) finishRow()
        if (!foundHeader) invalid(1, tr(TextKey.MISSING_HEADER_USE_THE_STANDARD_CSV_TEMPLATE))
        return rows
    }

    internal fun validate(fields: List<String>, line: Int): ImportRow {
        val kind = when (fields[0].lowercase(java.util.Locale.ROOT)) {
            "配件", "part", "parts" -> ItemKind.PART
            "整机", "machine", "machines" -> ItemKind.MACHINE
            else -> invalid(line, tr(TextKey.TYPE_MUST_BE_PART_OR_MACHINE_CHINESE_VALUES_ARE))
        }
        val bom = fields[1]
        val sn = fields[2]
        val pn = fields.getOrElse(6) { "" }
        val condition = StockCondition.parse(fields[4]) ?: invalid(line, tr(TextKey.CONDITION_MUST_BE_NORMAL_DAMAGED_OR_PENDING_VERIFICATION_CHINESE))
        val note = fields[5]
        val pending = condition == StockCondition.PENDING
        if ((kind == ItemKind.PART && !pending) || bom.isNotEmpty()) IdentifierRules.validateBom(bom)?.let { invalid(line, it) }
        if (kind == ItemKind.PART && sn.isNotEmpty()) invalid(line, tr(TextKey.SN_MUST_BE_EMPTY_FOR_PARTS))
        if (kind == ItemKind.PART && pn.isNotEmpty()) invalid(line, tr(TextKey.PARTS_MUST_NOT_HAVE_A_PN))
        if (pn.isNotEmpty()) IdentifierRules.validatePn(pn)?.let { invalid(line, it) }
        if (kind == ItemKind.MACHINE && (!pending || sn.isNotEmpty())) IdentifierRules.validateSn(sn)?.let { invalid(line, it) }
        if (pending && bom.isEmpty() && sn.isEmpty() && pn.isEmpty() && note.isBlank()) invalid(line, tr(TextKey.PENDING_RECORDS_REQUIRE_AT_LEAST_ONE_IDENTIFIER_OR_A_2))
        val quantityText = fields[3]
        val quantity = if (quantityText.isNotEmpty() && quantityText.all { it in '0'..'9' }) quantityText.toIntOrNull() else null
        if (quantity == null || quantity !in 1..MAX_QUANTITY) invalid(line, tr(TextKey.QUANTITY_MUST_BE_AN_INTEGER_BETWEEN_1_AND_1000000000))
        if (kind == ItemKind.MACHINE && quantity != 1) invalid(line, tr(TextKey.MACHINE_QUANTITY_MUST_BE_1_2))
        return ImportRow(kind, bom, sn, quantity, condition, note, pn)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > MAX_FILE_BYTES) invalid(1, tr(TextKey.FILE_MUST_NOT_EXCEED_8_MIB_IMPORT_IN_SMALLER))
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun invalid(line: Int, message: String): Nothing = throw IllegalArgumentException(tr(TextKey.LINE, line, message))
}
