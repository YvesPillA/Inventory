package com.fieldstock.app.export

import com.fieldstock.app.model.ChangeLog
import com.fieldstock.app.model.Country
import com.fieldstock.app.model.InventorySnapshot
import com.fieldstock.app.model.InventoryTask
import com.fieldstock.app.model.ItemKind
import com.fieldstock.app.model.StockRecord
import com.fieldstock.app.model.Warehouse
import com.fieldstock.app.model.BoundedJson
import com.fieldstock.app.i18n.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Stream-only exports: callers own the supplied stream and decide where to save it. */
object InventoryFiles {
    const val MAX_BACKUP_BYTES: Int = 32 * 1024 * 1024
    private const val FORMAT = "fieldstock-backup"
    private const val VERSION = 2
    private const val MAX_ITEMS = 200_000
    private const val MAX_STRING_LENGTH = 100_000
    private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX")

    @Suppress("UNUSED_PARAMETER") // Kept for callers of older app versions; warehouse alone defines scope.
    fun writeXlsx(snapshot: InventorySnapshot, output: OutputStream, taskId: String?, warehouseId: String?, language: AppLanguage = AppLanguage.current) {
        val data = ExportData(snapshot, warehouseId, language)
        val names = listOf(language.text(TextKey.COUNTING_GUIDE), language.text(TextKey.PARTS_DETAIL), language.text(TextKey.PARTS_SUMMARY), language.text(TextKey.MACHINE_DETAIL), language.condition(StockCondition.PENDING))
        // Finish the ZIP without closing the caller's stream (e.g. SAF's file descriptor).
        ZipOutputStream(NonClosingOutputStream(output)).use { zip ->
        zip.textEntry("[Content_Types].xml") { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            names.indices.forEach { writer.write("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
            writer.write("</Types>")
        }
        zip.textEntry("_rels/.rels") { it.write("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""") }
        zip.textEntry("xl/workbook.xml") { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
            names.forEachIndexed { index, name -> writer.write("""<sheet name="$name" sheetId="${index + 1}" r:id="rId${index + 1}"/>""") }
            writer.write("</sheets></workbook>")
        }
        zip.textEntry("xl/_rels/workbook.xml.rels") { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            names.indices.forEach { writer.write("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
            writer.write("</Relationships>")
        }
        zip.writeSheet(1, sequenceOf(
            textRow(language.text(TextKey.ITEM), language.text(TextKey.DESCRIPTION)),
            textRow(language.text(TextKey.EXPORTED_AT), timestamp(System.currentTimeMillis())),
            textRow(language.text(TextKey.WAREHOUSE_SCOPE), warehouseId?.let { data.warehouseLabel(it) } ?: language.text(TextKey.ALL_COUNTRIES_AND_WAREHOUSES)),
            textRow(language.text(TextKey.COUNTING_RULES), language.text(TextKey.CONFIRMED_ACTIVE_RECORDS_APPEAR_IN_PARTS_DETAIL_MACHINE_DETAIL)),
            textRow(language.text(TextKey.UNIT), language.text(TextKey.PARTS_ARE_COUNTED_IN_PIECES_AND_MACHINES_IN_UNITS)),
            textRow(language.text(TextKey.IDENTIFIER_FORMAT), language.text(TextKey.BOM_AND_SN_ARE_TEXT_VALUES_WITH_LEADING_ZEROS)),
            textRow(language.text(TextKey.TIME_FORMAT), language.text(TextKey.ISO_8601_WITH_THE_EXPORTING_PHONE_S_CURRENT_TIME)),
            textRow(language.text(TextKey.CSV_IN_EXCEL), language.text(TextKey.CSV_USES_UTF_8_IN_EXCEL_USE_FROM_TEXT)),
            textRow(language.text(TextKey.CSV_SAFETY), language.text(TextKey.FREE_TEXT_THAT_SPREADSHEET_SOFTWARE_MIGHT_TREAT_AS_A)),
            textRow(language.text(TextKey.RESTORE), language.text(TextKey.EXCEL_AND_CSV_ARE_FOR_ANALYSIS_TO_RESTORE_INVENTORY))
        ), false)
        zip.writeSheet(2, sequence {
            yield(textRow(*partHeaders(language)))
            data.confirmedRecords.filter { it.kind == ItemKind.PART }.forEach { yield(data.detailRow(it)) }
        })
        zip.writeSheet(3, sequence {
            yield(textRow(language.text(TextKey.COUNTRY), language.text(TextKey.WAREHOUSE), "BOM", language.text(TextKey.QUANTITY), language.text(TextKey.UNIT), language.text(TextKey.CONDITION), language.text(TextKey.RECORD_COUNT)))
            data.confirmedRecords.filter { it.kind == ItemKind.PART }
                .groupBy { SummaryKey(it.warehouseId, it.bom, it.condition) }
                .forEach { (key, records) ->
                    val quantity = records.sumOf { it.quantity.toLong() }
                    val warehouse = data.warehouses.getValue(key.warehouseId)
                    yield(listOf(Text(data.countries.getValue(warehouse.countryId).name), Text(warehouse.name), Text(key.bom), Numeric(quantity), Text(language.text(TextKey.PCS)), Text(language.condition(key.condition)), Numeric(records.size.toLong())))
                }
        })
        zip.writeSheet(4, sequence {
            yield(textRow(*machineHeaders(language)))
            data.confirmedRecords.filter { it.kind == ItemKind.MACHINE }.forEach { yield(data.detailRow(it)) }
        })
        zip.writeSheet(5, sequence {
            yield(textRow(*pendingHeaders(language)))
            data.pendingRecords.forEach { yield(data.pendingRow(it)) }
        })
        zip.finish()
        zip.flush()
        }
    }

    /** Confirmed parts/machines and a separate pending CSV; all entries use UTF-8 with a BOM. */
    @Suppress("UNUSED_PARAMETER") // Retained for API compatibility; historical task IDs never filter exports.
    fun writeCsvZip(snapshot: InventorySnapshot, output: OutputStream, taskId: String?, warehouseId: String?, language: AppLanguage = AppLanguage.current) {
        val data = ExportData(snapshot, warehouseId, language)
        ZipOutputStream(NonClosingOutputStream(output)).use { zip ->
        listOf(ItemKind.PART to language.text(TextKey.PARTS_DETAIL_CSV), ItemKind.MACHINE to language.text(TextKey.MACHINE_DETAIL_CSV)).forEach { (kind, name) ->
            zip.textEntry(name) { writer ->
                writer.write("\uFEFF")
                val headers = if (kind == ItemKind.PART) partHeaders(language).toList() else machineHeaders(language).toList()
                writer.write(csvRow(headers + language.text(TextKey.CSV_NOTE_ESCAPED)))
                data.confirmedRecords.filter { it.kind == kind }.forEach { record ->
                    writer.write(reportCsvRow(data.detailRow(record), headers, language))
                }
            }
        }
        zip.textEntry(language.text(TextKey.PENDING_VERIFICATION_CSV)) { writer ->
            writer.write("\uFEFF")
            val headers = pendingHeaders(language).toList()
            writer.write(csvRow(headers + language.text(TextKey.CSV_NOTE_ESCAPED)))
            data.pendingRecords.forEach { record -> writer.write(reportCsvRow(data.pendingRow(record), headers, language)) }
        }
        zip.textEntry(language.text(TextKey.CSV_INSTRUCTIONS_TXT)) { writer ->
            writer.write(language.text(TextKey.CSV_USES_UTF_8_WAREHOUSE_INCLUDES_ALL_HISTORICAL_ACTIVE, warehouseId?.let { data.warehouseLabel(it) } ?: language.text(TextKey.ALL_COUNTRIES_AND_WAREHOUSES)))
        }
        zip.finish()
        zip.flush()
        }
    }

    /** The payload is a JSON string so its checksum is independent of object key ordering. */
    fun writeBackup(snapshot: InventorySnapshot, output: OutputStream) {
        val payload = snapshot.toJson().toString()
        val envelope = JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("payload", payload).put("checksum", sha256(payload.toByteArray(Charsets.UTF_8)))
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { tr(TextKey.BACKUP_EXCEEDS_THE_32_MIB_LIMIT_REDUCE_OLDER_RECORDS) }
        output.write(bytes)
        output.flush()
    }

    /** Validates structure and integrity; Repository.restore validates references before replacing data. */
    fun readBackup(input: InputStream): InventorySnapshot {
        try {
            val bytes = readLimited(input)
            val jsonText = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val envelope = parseObject(jsonText.removePrefix("\uFEFF"))
            envelope.exactKeys("format", "version", "payload", "checksum")
            require(envelope.string("format") == FORMAT) { tr(TextKey.THIS_IS_NOT_A_FIELDSTOCK_BACKUP_FILE) }
            val version = envelope.integer("version")
            require(version in 1..VERSION.toLong()) { tr(TextKey.THIS_BACKUP_VERSION_IS_NOT_SUPPORTED) }
            val payload = envelope.string("payload", MAX_BACKUP_BYTES)
            val checksum = envelope.string("checksum")
            require(checksum.matches(Regex("[a-f0-9]{64}"))) { tr(TextKey.INVALID_BACKUP_CHECKSUM_FORMAT) }
            require(MessageDigest.isEqual(checksum.toByteArray(Charsets.US_ASCII), sha256(payload.toByteArray(Charsets.UTF_8)).toByteArray(Charsets.US_ASCII))) { tr(TextKey.BACKUP_VERIFICATION_FAILED_THE_FILE_MAY_BE_DAMAGED_OR) }
            val data = parseObject(payload)
            data.exactKeys("tasks", "countries", "warehouses", "records", "changes")
            return InventorySnapshot(
                tasks = data.objects("tasks") { row ->
                    row.exactKeys("id", "name", "createdAt")
                    InventoryTask(row.id(), row.string("name"), row.nonNegativeLong("createdAt"))
                },
                countries = data.objects("countries") { row ->
                    row.exactKeys("id", "name")
                    Country(row.id(), row.string("name"))
                },
                warehouses = data.objects("warehouses") { row ->
                    row.exactKeys("id", "countryId", "name")
                    Warehouse(row.id(), row.string("countryId"), row.string("name"))
                },
                records = data.objects("records") { row ->
                    val keys = arrayOf("id", "taskId", "warehouseId", "kind", "bom", "sn", "quantity", "condition", "location", "note", "sourceBom", "sourceSn", "rawBom", "rawSn", "createdAt", "updatedAt", "voided")
                    row.exactKeys(*(if (version >= 2) keys + arrayOf("pn", "sourcePn", "rawPn") else keys))
                    val quantity = row.integer("quantity")
                    require(quantity in 1..Int.MAX_VALUE.toLong()) { tr(TextKey.RECORD_QUANTITY_MUST_BE_A_POSITIVE_INTEGER) }
                    StockRecord(
                        id = row.id(), taskId = row.string("taskId"), warehouseId = row.string("warehouseId"),
                        kind = ItemKind.valueOf(row.string("kind")), bom = row.string("bom"), sn = row.string("sn"),
                        quantity = quantity.toInt(), condition = row.string("condition"), note = row.string("note"), location = row.string("location"),
                        sourceBom = row.string("sourceBom"), sourceSn = row.string("sourceSn"), rawBom = row.string("rawBom"), rawSn = row.string("rawSn"),
                        createdAt = row.nonNegativeLong("createdAt"), updatedAt = row.nonNegativeLong("updatedAt"), voided = row.boolean("voided"),
                        pn = if (version >= 2) row.string("pn") else "",
                        sourcePn = if (version >= 2) row.string("sourcePn") else "manual",
                        rawPn = if (version >= 2) row.string("rawPn") else ""
                    )
                },
                changes = data.objects("changes") { row ->
                    row.exactKeys("id", "recordId", "action", "before", "after", "at")
                    ChangeLog(row.id(), row.string("recordId"), row.string("action"), row.string("before"), row.string("after"), row.nonNegativeLong("at"))
                }
            )
        } catch (failure: Exception) {
            if (failure is IllegalArgumentException) throw failure
            throw IllegalArgumentException(tr(TextKey.COULD_NOT_READ_THE_BACKUP_INVALID_FORMAT_OR_FIELDS), failure)
        }
    }

    private fun partHeaders(language: AppLanguage) = arrayOf(language.text(TextKey.COUNTRY), language.text(TextKey.WAREHOUSE), "BOM", language.text(TextKey.QUANTITY), language.text(TextKey.UNIT), language.text(TextKey.CONDITION), language.text(TextKey.NOTE), language.text(TextKey.BOM_SOURCE), language.text(TextKey.RAW_BOM_TEXT), language.text(TextKey.CREATED_AT), language.text(TextKey.UPDATED_AT), language.text(TextKey.RECORD_ID))
    private fun machineHeaders(language: AppLanguage) = arrayOf(language.text(TextKey.COUNTRY), language.text(TextKey.WAREHOUSE), "BOM", "PN", "SN", language.text(TextKey.QUANTITY), language.text(TextKey.UNIT), language.text(TextKey.CONDITION), language.text(TextKey.NOTE), language.text(TextKey.BOM_SOURCE), language.text(TextKey.PN_SOURCE), language.text(TextKey.SN_SOURCE), language.text(TextKey.RAW_BOM_TEXT), language.text(TextKey.RAW_PN_TEXT), language.text(TextKey.RAW_SN_TEXT), language.text(TextKey.CREATED_AT), language.text(TextKey.UPDATED_AT), language.text(TextKey.RECORD_ID))
    private fun pendingHeaders(language: AppLanguage) = machineHeaders(language).toMutableList().apply { add(2, language.text(TextKey.TYPE)) }.toTypedArray()
    private data class SummaryKey(val warehouseId: String, val bom: String, val condition: String)
    private sealed interface Cell
    private data class Text(val value: String) : Cell
    private data class Numeric(val value: Long) : Cell
    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
    }
    private fun textRow(vararg values: String): List<Cell> = values.map(::Text)

    private class ExportData(snapshot: InventorySnapshot, warehouseId: String?, private val language: AppLanguage) {
        val countries = snapshot.countries.associateBy { it.id }
        val warehouses = snapshot.warehouses.associateBy { it.id }
        val records: List<StockRecord>
        val confirmedRecords: List<StockRecord>
        val pendingRecords: List<StockRecord>
        init {
            require(warehouseId == null || warehouses.containsKey(warehouseId)) { language.text(TextKey.THE_EXPORT_WAREHOUSE_DOES_NOT_EXIST) }
            records = snapshot.records.filter { !it.voided && (warehouseId == null || it.warehouseId == warehouseId) }
                .sortedWith(compareBy<StockRecord> { warehouses[it.warehouseId]?.countryId }.thenBy { it.warehouseId }.thenBy { it.bom }.thenBy { it.createdAt }.thenBy { it.id })
            confirmedRecords = records.filter { it.condition != StockCondition.PENDING }
            pendingRecords = records.filter { it.condition == StockCondition.PENDING }
            records.forEach { record ->
                require(warehouses.containsKey(record.warehouseId)) { language.text(TextKey.A_RECORD_REFERS_TO_A_WAREHOUSE_THAT_DOES_NOT) }
                require(countries.containsKey(warehouses.getValue(record.warehouseId).countryId)) { language.text(TextKey.A_WAREHOUSE_REFERS_TO_A_COUNTRY_THAT_DOES_NOT) }
            }
        }
        fun warehouseLabel(id: String): String {
            val warehouse = warehouses.getValue(id)
            return "${countries.getValue(warehouse.countryId).name} / ${warehouse.name}"
        }
        fun pendingRow(record: StockRecord): List<Cell> = detailRow(record, includeSn = true).toMutableList().apply {
            add(2, Text(if (record.kind == ItemKind.PART) language.text(TextKey.PARTS) else language.text(TextKey.MACHINE)))
        }
        fun detailRow(record: StockRecord, includeSn: Boolean = record.kind == ItemKind.MACHINE): List<Cell> {
            val warehouse = warehouses.getValue(record.warehouseId)
            return buildList {
                add(Text(countries.getValue(warehouse.countryId).name)); add(Text(warehouse.name)); add(Text(record.bom))
                if (includeSn) { add(Text(record.pn)); add(Text(record.sn)) }
                add(Numeric(record.quantity.toLong())); add(Text(if (record.kind == ItemKind.PART) language.text(TextKey.PCS) else language.text(TextKey.UNIT_2))); add(Text(language.condition(record.condition))); add(Text(record.note)); add(Text(sourceLabel(record.sourceBom)))
                if (includeSn) { add(Text(sourceLabel(record.sourcePn))); add(Text(sourceLabel(record.sourceSn))) }
                add(Text(record.rawBom))
                if (includeSn) { add(Text(record.rawPn)); add(Text(record.rawSn)) }
                add(Text(timestamp(record.createdAt))); add(Text(timestamp(record.updatedAt))); add(Text(record.id))
            }
        }
        private fun sourceLabel(value: String): String = if (language == AppLanguage.CHINESE) value else when (value) {
            "manual" -> "Manual"
            "ocr" -> "OCR"
            "import" -> "Import"
            else -> value
        }
    }

    private fun timestamp(value: Long): String = dateFormat.format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
    private fun ZipOutputStream.textEntry(name: String, write: (OutputStreamWriter) -> Unit) {
        putNextEntry(ZipEntry(name))
        val writer = OutputStreamWriter(this, Charsets.UTF_8)
        write(writer)
        writer.flush()
        closeEntry()
    }
    private fun ZipOutputStream.writeSheet(number: Int, rows: Sequence<List<Cell>>, filter: Boolean = true) {
        textEntry("xl/worksheets/sheet$number.xml") { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="20"/><cols><col min="1" max="16" width="22" customWidth="1"/></cols><sheetData>""")
            var rowCount = 0
            var columnCount = 0
            rows.forEach { cells ->
                rowCount++
                require(rowCount <= 1_048_576) { tr(TextKey.TOO_MANY_ROWS_FOR_ONE_EXCEL_WORKSHEET) }
                columnCount = maxOf(columnCount, cells.size)
                writer.write("<row r=\"$rowCount\">")
                cells.forEachIndexed { index, cell ->
                    val reference = "${columnName(index)}$rowCount"
                    when (cell) {
                        is Text -> writer.write("<c r=\"$reference\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlText(cell.value)}</t></is></c>")
                        is Numeric -> writer.write("<c r=\"$reference\"><v>${cell.value}</v></c>")
                    }
                }
                writer.write("</row>")
            }
            writer.write("</sheetData>")
            if (filter && rowCount > 0 && columnCount > 0) writer.write("<autoFilter ref=\"A1:${columnName(columnCount - 1)}$rowCount\"/>")
            writer.write("</worksheet>")
        }
    }
    private fun columnName(zeroBased: Int): String {
        var value = zeroBased + 1
        var result = ""
        while (value > 0) { value--; result = ('A' + value % 26) + result; value /= 26 }
        return result
    }
    /** Excel escapes _xNNNN_ itself; escape the underscore to preserve literal OCR/free text. */
    private fun xmlText(value: String): String {
        val safe = StringBuilder()
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            index += Character.charCount(codePoint)
            when {
                codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD || codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint in 0x10000..0x10FFFF -> safe.appendCodePoint(codePoint)
                else -> safe.append('\uFFFD')
            }
        }
        return safe.toString().replace(Regex("_x[0-9A-Fa-f]{4}_")) { "_x005F_${it.value.substring(1)}" }
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;").replace("\r", "&#13;")
    }
    private fun reportCsvRow(cells: List<Cell>, headers: List<String>, language: AppLanguage): String {
        val values = cells.map { cell -> when (cell) { is Text -> cell.value; is Numeric -> cell.value.toString() } }
        val escaped = requiresCsvProtection(values[headers.indexOf(language.text(TextKey.NOTE))])
        return csvRow(values + if (escaped) "1" else "0")
    }
    internal fun requiresCsvProtection(value: String): Boolean {
        val first = value.trimStart { it <= '\u0020' || it.isWhitespace() || it == '\uFEFF' }.firstOrNull()
        return first in listOf('=', '+', '-', '@') || value.startsWith('\t') || value.startsWith('\r') || value.startsWith('\n')
    }
    private fun csvRow(values: List<String>): String = values.joinToString(",") { original ->
        val protected = if (requiresCsvProtection(original)) "'$original" else original
        "\"${protected.replace("\"", "\"\"")}\""
    } + "\r\n"

    private fun InventorySnapshot.toJson(): JSONObject = JSONObject()
        .put("tasks", jsonArray(tasks) { JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt) })
        .put("countries", jsonArray(countries) { JSONObject().put("id", it.id).put("name", it.name) })
        .put("warehouses", jsonArray(warehouses) { JSONObject().put("id", it.id).put("countryId", it.countryId).put("name", it.name) })
        .put("records", jsonArray(records) { JSONObject().put("id", it.id).put("taskId", it.taskId).put("warehouseId", it.warehouseId).put("kind", it.kind.name).put("bom", it.bom).put("sn", it.sn).put("pn", it.pn).put("sourcePn", it.sourcePn).put("rawPn", it.rawPn).put("quantity", it.quantity).put("condition", it.condition).put("note", it.note).put("location", it.location).put("sourceBom", it.sourceBom).put("sourceSn", it.sourceSn).put("rawBom", it.rawBom).put("rawSn", it.rawSn).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt).put("voided", it.voided) })
        .put("changes", jsonArray(changes) { JSONObject().put("id", it.id).put("recordId", it.recordId).put("action", it.action).put("before", it.before).put("after", it.after).put("at", it.at) })
    private fun <T> jsonArray(items: List<T>, convert: (T) -> JSONObject): JSONArray {
        require(items.size <= MAX_ITEMS) { tr(TextKey.THE_BACKUP_CONTAINS_TOO_MANY_RECORDS) }
        return JSONArray().also { array -> items.forEach { array.put(convert(it)) } }
    }
    private fun parseObject(text: String): JSONObject = BoundedJson.objectValue(text)
    private fun JSONObject.exactKeys(vararg expected: String) {
        val actual = keys().asSequence().toSet()
        require(actual == expected.toSet()) { tr(TextKey.THE_BACKUP_HAS_MISSING_OR_UNKNOWN_FIELDS) }
    }
    private fun JSONObject.string(key: String, limit: Int = MAX_STRING_LENGTH): String {
        val value = get(key)
        require(value is String && value.length <= limit) { tr(TextKey.FIELD_MUST_BE_TEXT_OF_A_VALID_LENGTH, key) }
        return value
    }
    private fun JSONObject.id(): String = string("id").also { require(it.isNotBlank()) { tr(TextKey.ID_MUST_NOT_BE_EMPTY) } }
    private fun JSONObject.integer(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { tr(TextKey.FIELD_MUST_BE_AN_INTEGER, key) }
        return (value as Number).toLong()
    }
    private fun JSONObject.nonNegativeLong(key: String): Long = integer(key).also { require(it >= 0) { tr(TextKey.FIELD_MUST_NOT_BE_NEGATIVE, key) } }
    private fun JSONObject.boolean(key: String): Boolean {
        val value = get(key)
        require(value is Boolean) { tr(TextKey.FIELD_MUST_BE_A_BOOLEAN, key) }
        return value
    }
    private fun <T> JSONObject.objects(key: String, convert: (JSONObject) -> T): List<T> {
        val array = get(key)
        require(array is JSONArray && array.length() <= MAX_ITEMS) { tr(TextKey.FIELD_MUST_BE_AN_ARRAY_WITHIN_THE_ALLOWED_SIZE, key) }
        return (0 until array.length()).map { index ->
            val row = array.get(index)
            require(row is JSONObject) { tr(TextKey.ARRAY_CONTAINS_AN_ITEM_THAT_IS_NOT_AN_OBJECT, key) }
            convert(row)
        }
    }
    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BACKUP_BYTES) { tr(TextKey.BACKUP_EXCEEDS_THE_32_MIB_LIMIT) }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
