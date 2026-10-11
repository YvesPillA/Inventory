package com.fieldstock.app.export

import com.fieldstock.app.model.ChangeLog
import com.fieldstock.app.model.Country
import com.fieldstock.app.model.InventorySnapshot
import com.fieldstock.app.model.InventoryTask
import com.fieldstock.app.model.ItemKind
import com.fieldstock.app.model.StockRecord
import com.fieldstock.app.model.Warehouse
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
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
    private const val VERSION = 1
    private const val MAX_ITEMS = 200_000
    private const val MAX_STRING_LENGTH = 100_000
    private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX")

    @Suppress("UNUSED_PARAMETER") // Kept for callers of older app versions; warehouse alone defines scope.
    fun writeXlsx(snapshot: InventorySnapshot, output: OutputStream, taskId: String?, warehouseId: String?) {
        val data = ExportData(snapshot, warehouseId)
        val names = listOf("盘点说明", "配件明细", "配件汇总", "整机明细", "待核查")
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
            textRow("项目", "说明"),
            textRow("导出时间", timestamp(System.currentTimeMillis())),
            textRow("仓库范围", warehouseId?.let { data.warehouseLabel(it) } ?: "全部国家、仓库"),
            textRow("统计规则", "已确认的有效记录进入正式配件、整机明细与配件汇总；待核查记录单列“待核查”表，不计入已确认数量。已撤销记录只保留在完整备份中。"),
            textRow("单位", "配件以件统计，整机以台统计。按国家、仓库、BOM 和状态汇总全部历史有效记录，正常、损坏等状态分别汇总。"),
            textRow("编号格式", "BOM、SN 均为文本，保留前导零；整机一台一条记录。"),
            textRow("时间格式", "ISO 8601，包含导出手机当前时区偏移。原始时间戳保存在完整备份中。"),
            textRow("CSV 导入", "CSV 使用 UTF-8 编码。用 Excel 的“从文本/CSV”导入，并将 BOM 和 SN 列设为文本，保留前导零。"),
            textRow("CSV 安全", "可能被表格软件当作公式的自由文本会加前置单引号；XLSX 保留文本原值。"),
            textRow("恢复", "Excel、CSV 用于分析；恢复到手机请使用完整 JSON 备份。")
        ), false)
        zip.writeSheet(2, sequence {
            yield(textRow(*PART_HEADERS))
            data.confirmedRecords.filter { it.kind == ItemKind.PART }.forEach { yield(data.detailRow(it)) }
        })
        zip.writeSheet(3, sequence {
            yield(textRow("国家", "仓库", "BOM", "数量", "单位", "状态", "录入条数"))
            data.confirmedRecords.filter { it.kind == ItemKind.PART }
                .groupBy { SummaryKey(it.warehouseId, it.bom, it.condition) }
                .forEach { (key, records) ->
                    val quantity = records.sumOf { it.quantity.toLong() }
                    val warehouse = data.warehouses.getValue(key.warehouseId)
                    yield(listOf(Text(data.countries.getValue(warehouse.countryId).name), Text(warehouse.name), Text(key.bom), Numeric(quantity), Text("件"), Text(key.condition), Numeric(records.size.toLong())))
                }
        })
        zip.writeSheet(4, sequence {
            yield(textRow(*MACHINE_HEADERS))
            data.confirmedRecords.filter { it.kind == ItemKind.MACHINE }.forEach { yield(data.detailRow(it)) }
        })
        zip.writeSheet(5, sequence {
            yield(textRow(*PENDING_HEADERS))
            data.pendingRecords.forEach { yield(data.pendingRow(it)) }
        })
        zip.finish()
        zip.flush()
        }
    }

    /** Confirmed parts/machines and a separate pending CSV; all entries use UTF-8 with a BOM. */
    @Suppress("UNUSED_PARAMETER") // Retained for API compatibility; historical task IDs never filter exports.
    fun writeCsvZip(snapshot: InventorySnapshot, output: OutputStream, taskId: String?, warehouseId: String?) {
        val data = ExportData(snapshot, warehouseId)
        ZipOutputStream(NonClosingOutputStream(output)).use { zip ->
        listOf(ItemKind.PART to "配件明细.csv", ItemKind.MACHINE to "整机明细.csv").forEach { (kind, name) ->
            zip.textEntry(name) { writer ->
                writer.write("\uFEFF")
                writer.write(csvRow(if (kind == ItemKind.PART) PART_HEADERS.toList() else MACHINE_HEADERS.toList()))
                data.confirmedRecords.filter { it.kind == kind }.forEach { record ->
                    writer.write(csvRow(data.detailRow(record).map { cell ->
                        when (cell) { is Text -> cell.value; is Numeric -> cell.value.toString() }
                    }))
                }
            }
        }
        zip.textEntry("待核查.csv") { writer ->
            writer.write("\uFEFF")
            writer.write(csvRow(PENDING_HEADERS.toList()))
            data.pendingRecords.forEach { record -> writer.write(csvRow(data.pendingRow(record).map { cell ->
                when (cell) { is Text -> cell.value; is Numeric -> cell.value.toString() }
            })) }
        }
        zip.textEntry("CSV导入说明.txt") { writer ->
            writer.write("\uFEFFCSV 使用 UTF-8 编码。\r\n导出仓库：${warehouseId?.let { data.warehouseLabel(it) } ?: "全部国家、仓库"}。范围包含全部历史有效记录。\r\nExcel：数据 → 从文本/CSV → 编码 UTF-8 → 将 BOM、SN 列的数据类型设为文本，再加载。\r\n直接双击 CSV 可能让 Excel 丢失编号前导零；建议优先使用同时提供的 XLSX 导出。\r\n可能被当成公式的自由文本前置了单引号。\r\n配件明细.csv、整机明细.csv 只包含范围内已确认的有效记录；待核查.csv 单独保留待核查记录，不计入已确认数量；已撤销记录仅在完整备份中保留。\r\n时间包含时区偏移。CSV 不能用作恢复备份。\r\n")
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
        require(bytes.size <= MAX_BACKUP_BYTES) { "备份超过 32 MB 上限，请减少历史记录后重试。" }
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
            require(envelope.string("format") == FORMAT) { "不是本应用的备份文件。" }
            require(envelope.integer("version") == VERSION.toLong()) { "不支持此备份版本。" }
            val payload = envelope.string("payload", MAX_BACKUP_BYTES)
            val checksum = envelope.string("checksum")
            require(checksum.matches(Regex("[a-f0-9]{64}"))) { "备份校验码格式错误。" }
            require(MessageDigest.isEqual(checksum.toByteArray(Charsets.US_ASCII), sha256(payload.toByteArray(Charsets.UTF_8)).toByteArray(Charsets.US_ASCII))) { "备份校验失败，文件可能已损坏或被修改。" }
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
                    row.exactKeys("id", "taskId", "warehouseId", "kind", "bom", "sn", "quantity", "condition", "location", "note", "sourceBom", "sourceSn", "rawBom", "rawSn", "createdAt", "updatedAt", "voided")
                    val quantity = row.integer("quantity")
                    require(quantity in 1..Int.MAX_VALUE.toLong()) { "记录数量必须为正整数。" }
                    StockRecord(
                        id = row.id(), taskId = row.string("taskId"), warehouseId = row.string("warehouseId"),
                        kind = ItemKind.valueOf(row.string("kind")), bom = row.string("bom"), sn = row.string("sn"),
                        quantity = quantity.toInt(), condition = row.string("condition"), note = row.string("note"), location = row.string("location"),
                        sourceBom = row.string("sourceBom"), sourceSn = row.string("sourceSn"), rawBom = row.string("rawBom"), rawSn = row.string("rawSn"),
                        createdAt = row.nonNegativeLong("createdAt"), updatedAt = row.nonNegativeLong("updatedAt"), voided = row.boolean("voided")
                    )
                },
                changes = data.objects("changes") { row ->
                    row.exactKeys("id", "recordId", "action", "before", "after", "at")
                    ChangeLog(row.id(), row.string("recordId"), row.string("action"), row.string("before"), row.string("after"), row.nonNegativeLong("at"))
                }
            )
        } catch (failure: Exception) {
            if (failure is IllegalArgumentException) throw failure
            throw IllegalArgumentException("备份文件无法读取：格式或字段不正确。", failure)
        }
    }

    private val PART_HEADERS = arrayOf("国家", "仓库", "BOM", "数量", "单位", "状态", "备注", "BOM来源", "BOM识别原文", "记录时间", "修改时间", "记录ID")
    private val MACHINE_HEADERS = arrayOf("国家", "仓库", "BOM", "SN", "数量", "单位", "状态", "备注", "BOM来源", "SN来源", "BOM识别原文", "SN识别原文", "记录时间", "修改时间", "记录ID")
    private val PENDING_HEADERS = MACHINE_HEADERS.toMutableList().apply { add(2, "类型") }.toTypedArray()
    private data class SummaryKey(val warehouseId: String, val bom: String, val condition: String)
    private sealed interface Cell
    private data class Text(val value: String) : Cell
    private data class Numeric(val value: Long) : Cell
    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
    }
    private fun textRow(vararg values: String): List<Cell> = values.map(::Text)

    private class ExportData(snapshot: InventorySnapshot, warehouseId: String?) {
        val countries = snapshot.countries.associateBy { it.id }
        val warehouses = snapshot.warehouses.associateBy { it.id }
        val records: List<StockRecord>
        val confirmedRecords: List<StockRecord>
        val pendingRecords: List<StockRecord>
        init {
            require(warehouseId == null || warehouses.containsKey(warehouseId)) { "导出仓库不存在。" }
            records = snapshot.records.filter { !it.voided && (warehouseId == null || it.warehouseId == warehouseId) }
                .sortedWith(compareBy<StockRecord> { warehouses[it.warehouseId]?.countryId }.thenBy { it.warehouseId }.thenBy { it.bom }.thenBy { it.createdAt }.thenBy { it.id })
            confirmedRecords = records.filter { it.condition != "待核查" }
            pendingRecords = records.filter { it.condition == "待核查" }
            records.forEach { record ->
                require(warehouses.containsKey(record.warehouseId)) { "记录关联的仓库不存在。" }
                require(countries.containsKey(warehouses.getValue(record.warehouseId).countryId)) { "仓库关联的国家不存在。" }
            }
        }
        fun warehouseLabel(id: String): String {
            val warehouse = warehouses.getValue(id)
            return "${countries.getValue(warehouse.countryId).name} / ${warehouse.name}"
        }
        fun pendingRow(record: StockRecord): List<Cell> = detailRow(record, includeSn = true).toMutableList().apply {
            add(2, Text(if (record.kind == ItemKind.PART) "配件" else "整机"))
        }
        fun detailRow(record: StockRecord, includeSn: Boolean = record.kind == ItemKind.MACHINE): List<Cell> {
            val warehouse = warehouses.getValue(record.warehouseId)
            return buildList {
                add(Text(countries.getValue(warehouse.countryId).name)); add(Text(warehouse.name)); add(Text(record.bom))
                if (includeSn) add(Text(record.sn))
                add(Numeric(record.quantity.toLong())); add(Text(if (record.kind == ItemKind.PART) "件" else "台")); add(Text(record.condition)); add(Text(record.note)); add(Text(record.sourceBom))
                if (includeSn) add(Text(record.sourceSn))
                add(Text(record.rawBom))
                if (includeSn) add(Text(record.rawSn))
                add(Text(timestamp(record.createdAt))); add(Text(timestamp(record.updatedAt))); add(Text(record.id))
            }
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
                require(rowCount <= 1_048_576) { "记录超过 Excel 单页行数上限。" }
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
    private fun csvRow(values: List<String>): String = values.joinToString(",") { original ->
        val first = original.trimStart { it <= '\u0020' || it.isWhitespace() || it == '\uFEFF' }.firstOrNull()
        val protected = if (first in listOf('=', '+', '-', '@') || original.startsWith('\t') || original.startsWith('\r') || original.startsWith('\n')) "'$original" else original
        "\"${protected.replace("\"", "\"\"")}\""
    } + "\r\n"

    private fun InventorySnapshot.toJson(): JSONObject = JSONObject()
        .put("tasks", jsonArray(tasks) { JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt) })
        .put("countries", jsonArray(countries) { JSONObject().put("id", it.id).put("name", it.name) })
        .put("warehouses", jsonArray(warehouses) { JSONObject().put("id", it.id).put("countryId", it.countryId).put("name", it.name) })
        .put("records", jsonArray(records) { JSONObject().put("id", it.id).put("taskId", it.taskId).put("warehouseId", it.warehouseId).put("kind", it.kind.name).put("bom", it.bom).put("sn", it.sn).put("quantity", it.quantity).put("condition", it.condition).put("note", it.note).put("location", it.location).put("sourceBom", it.sourceBom).put("sourceSn", it.sourceSn).put("rawBom", it.rawBom).put("rawSn", it.rawSn).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt).put("voided", it.voided) })
        .put("changes", jsonArray(changes) { JSONObject().put("id", it.id).put("recordId", it.recordId).put("action", it.action).put("before", it.before).put("after", it.after).put("at", it.at) })
    private fun <T> jsonArray(items: List<T>, convert: (T) -> JSONObject): JSONArray {
        require(items.size <= MAX_ITEMS) { "备份中的记录数超过上限。" }
        return JSONArray().also { array -> items.forEach { array.put(convert(it)) } }
    }
    private fun parseObject(text: String): JSONObject {
        val tokenizer = JSONTokener(text)
        val value = tokenizer.nextValue()
        require(value is JSONObject && tokenizer.nextClean() == '\u0000') { "JSON 必须为单个对象。" }
        return value
    }
    private fun JSONObject.exactKeys(vararg expected: String) {
        val actual = keys().asSequence().toSet()
        require(actual == expected.toSet()) { "备份字段缺失或含未知字段。" }
    }
    private fun JSONObject.string(key: String, limit: Int = MAX_STRING_LENGTH): String {
        val value = get(key)
        require(value is String && value.length <= limit) { "字段 $key 必须为长度有效的文本。" }
        return value
    }
    private fun JSONObject.id(): String = string("id").also { require(it.isNotBlank()) { "ID 不得为空。" } }
    private fun JSONObject.integer(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "字段 $key 必须为整数。" }
        return (value as Number).toLong()
    }
    private fun JSONObject.nonNegativeLong(key: String): Long = integer(key).also { require(it >= 0) { "字段 $key 不能为负数。" } }
    private fun JSONObject.boolean(key: String): Boolean {
        val value = get(key)
        require(value is Boolean) { "字段 $key 必须为布尔值。" }
        return value
    }
    private fun <T> JSONObject.objects(key: String, convert: (JSONObject) -> T): List<T> {
        val array = get(key)
        require(array is JSONArray && array.length() <= MAX_ITEMS) { "字段 $key 必须为数量有效的数组。" }
        return (0 until array.length()).map { index ->
            val row = array.get(index)
            require(row is JSONObject) { "数组 $key 中包含非对象项目。" }
            convert(row)
        }
    }
    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BACKUP_BYTES) { "备份超过 32 MB 上限。" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
