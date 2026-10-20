package com.fieldstock.app.importdata

import com.fieldstock.app.model.IdentifierRules
import com.fieldstock.app.model.ItemKind
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
    val note: String
)

/** Standard CSV only. Parsing never evaluates spreadsheet formula text or changes identifier case. */
object InventoryImport {
    /** Hash parsed values, so a rename, UTF-8 BOM or line-ending change does not evade the reminder. */
    fun fingerprint(rows: List<ImportRow>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("fieldstock-csv-v1".toByteArray(Charsets.UTF_8))
        rows.forEach { row ->
            listOf(row.kind.name, row.bom, row.sn, row.quantity.toString(), row.condition, row.note).forEach { value ->
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
    private enum class State { START, UNQUOTED, QUOTED, AFTER_QUOTE }

    /** Header only: example inventory must never accidentally become a real imported record. */
    fun writeTemplate(output: OutputStream) {
        output.write(("\uFEFF" + header.joinToString(",") + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    fun read(input: InputStream, fileName: String?): List<ImportRow> {
        val bytes = readLimited(input)
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            invalid(1, "仅支持标准 CSV，不支持 XLSX 或 ZIP；请另存为 CSV UTF-8 后导入。")
        }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (failure: CharacterCodingException) {
            val hint = fileName?.take(80)?.filterNot(Char::isISOControl)?.let { "（$it）" }.orEmpty()
            throw IllegalArgumentException("第 1 行：文件${hint}不是有效 UTF-8 CSV，请另存为 CSV UTF-8。", failure)
        }
        val rows = mutableListOf<ImportRow>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var state = State.START
        var line = 1
        var rowStartLine = 1
        var foundHeader = false
        var index = 0

        fun finishField() {
            if (fields.size >= header.size) invalid(rowStartLine, "需要 6 列，列数过多；备注中的逗号或换行必须使用双引号包围。")
            fields.add(field.toString())
            field.setLength(0)
            state = State.START
        }
        fun finishRow() {
            finishField()
            if (fields.any { it.isNotBlank() }) {
                if (!foundHeader) {
                    if (fields != header) invalid(rowStartLine, "表头必须按顺序为：${header.joinToString(",")}。")
                    foundHeader = true
                } else {
                    if (fields == header) invalid(rowStartLine, "重复表头，请只保留第一行表头。")
                    if (fields.size != header.size) invalid(rowStartLine, "需要 6 列，实际为 ${fields.size} 列；备注中的逗号或换行必须使用双引号包围。")
                    if (rows.size >= MAX_ROWS) invalid(rowStartLine, "数据不能超过 10000 行，请分批导入。")
                    rows.add(validate(fields, rowStartLine))
                }
            }
            fields.clear()
        }
        fun append(character: Char) {
            if (field.length >= MAX_FIELD_CHARS) invalid(rowStartLine, "单个字段不能超过 4000 字。")
            field.append(character)
        }

        while (index < text.length) {
            val character = text[index]
            if (character.isISOControl() && character !in listOf('\t', '\r', '\n')) invalid(line, "文件含无效控制字符。")
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
                    State.UNQUOTED -> invalid(line, "未加引号的字段中出现双引号。")
                    State.AFTER_QUOTE -> invalid(line, "结束引号后只能出现逗号、换行或文件结束。")
                    State.QUOTED -> {
                        if (index + 1 < text.length && text[index + 1] == '"') { append('"'); index++ }
                        else state = State.AFTER_QUOTE
                    }
                }
                state == State.AFTER_QUOTE -> invalid(line, "结束引号后只能出现逗号、换行或文件结束。")
                else -> { append(character); if (state == State.START) state = State.UNQUOTED }
            }
            index++
        }
        if (state == State.QUOTED) invalid(rowStartLine, "双引号未闭合。")
        if (state != State.START || field.isNotEmpty() || fields.isNotEmpty()) finishRow()
        if (!foundHeader) invalid(1, "缺少表头，请使用下载的标准 CSV 模板。")
        return rows
    }

    private fun validate(fields: List<String>, line: Int): ImportRow {
        val kind = when (fields[0]) {
            "配件" -> ItemKind.PART
            "整机" -> ItemKind.MACHINE
            else -> invalid(line, "类型只能填写“配件”或“整机”。")
        }
        val bom = fields[1]
        val sn = fields[2]
        val condition = fields[4]
        val note = fields[5]
        if (condition !in setOf("正常", "损坏", "待核查")) invalid(line, "状态只能填写“正常”“损坏”或“待核查”。")
        val pending = condition == "待核查"
        if (!pending || bom.isNotEmpty()) IdentifierRules.validateBom(bom)?.let { invalid(line, it) }
        if (kind == ItemKind.PART && sn.isNotEmpty()) invalid(line, "配件的 SN 必须留空。")
        if (kind == ItemKind.MACHINE && (!pending || sn.isNotEmpty())) IdentifierRules.validateSn(sn)?.let { invalid(line, it) }
        if (pending && bom.isEmpty() && sn.isEmpty() && note.isBlank()) invalid(line, "待核查记录至少填写一个编号或备注。")
        val quantityText = fields[3]
        val quantity = if (quantityText.isNotEmpty() && quantityText.all { it in '0'..'9' }) quantityText.toIntOrNull() else null
        if (quantity == null || quantity !in 1..MAX_QUANTITY) invalid(line, "数量须为 1 到 1000000000 的整数。")
        if (kind == ItemKind.MACHINE && quantity != 1) invalid(line, "整机数量必须为 1。")
        return ImportRow(kind, bom, sn, quantity, condition, note)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > MAX_FILE_BYTES) invalid(1, "文件不能超过 8 MiB，请分批导入。")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun invalid(line: Int, message: String): Nothing = throw IllegalArgumentException("第 $line 行：$message")
}
