package com.fieldstock.app.importdata

import com.fieldstock.app.i18n.*
import com.fieldstock.app.diagnostics.AppDiagnostics
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class ReportColumns(val indexes: Map<String, Int>) {
    fun standardFields(values: List<String>): List<String> {
        fun get(key: String) = indexes[key]?.let { values.getOrElse(it) { "" } }.orEmpty()
        val kind = if ("type" in indexes) get("type") else if ("sn" in indexes) "Machine" else "Part"
        return listOf(kind, get("bom"), get("sn"), get("quantity"), get("condition"), get("note"), get("pn"))
    }
}

/** Reads the application's detail sheets, never its summary or guide. No extraction to disk. */
internal object InventoryReportImport {
    private const val MAX_EXPANDED_BYTES = 64 * 1024 * 1024
    private const val MAX_ENTRIES = 256
    private const val MAX_COLUMNS = 64
    private const val MAX_TEXT = 4000
    private val aliases = mapOf(
        "国家" to "country", "country" to "country", "仓库" to "warehouse", "warehouse" to "warehouse",
        "类型" to "type", "type" to "type", "bom" to "bom", "sn" to "sn", "pn" to "pn",
        "数量" to "quantity", "quantity" to "quantity", "状态" to "condition", "condition" to "condition",
        "备注" to "note", "note" to "note", "记录id" to "id", "record id" to "id"
    )
    private val sheetNames = listOf(TextKey.PARTS_DETAIL, TextKey.MACHINE_DETAIL).flatMap { key ->
        AppLanguage.entries.map { it.text(key).lowercase(Locale.ROOT) }
    }.toSet() + setOf("待核查", "pending verification")
    private val csvNames = listOf(TextKey.PARTS_DETAIL_CSV, TextKey.MACHINE_DETAIL_CSV, TextKey.PENDING_VERIFICATION_CSV)
        .flatMap { key -> AppLanguage.entries.map { it.text(key).lowercase(Locale.ROOT) } }.toSet()

    fun columns(headers: List<String>): ReportColumns? {
        val indexes = linkedMapOf<String, Int>()
        headers.forEachIndexed { index, text ->
            val key = aliases[text.lowercase(Locale.ROOT)]
            if (key != null && indexes.put(key, index) != null) invalid(tr(TextKey.IMPORT_REPORT_HEADERS))
        }
        return if (indexes.keys.containsAll(listOf("country", "warehouse", "bom", "quantity", "condition", "note", "id"))) ReportColumns(indexes) else null
    }

    fun read(bytes: ByteArray): List<ImportRow> {
        try {
            val entries = readZip(bytes)
            return if ("xl/workbook.xml" in entries) readWorkbook(entries) else {
                val files = entries.filterKeys { it.lowercase(Locale.ROOT) in csvNames }
                require(files.isNotEmpty()) { tr(TextKey.IMPORT_SUPPORTED_FILES) }
                buildList {
                    files.forEach { (name, data) ->
                        val rows = inSheet(name) { InventoryImport.readCsv(data, name) }
                        checkRows(size + rows.size)
                        addAll(rows)
                    }
                }
            }
        } catch (failure: IllegalArgumentException) { throw failure }
        catch (failure: Exception) { throw IllegalArgumentException(tr(TextKey.IMPORT_ARCHIVE_INVALID), failure) }
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        var count = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= MAX_ENTRIES) { tr(TextKey.IMPORT_ARCHIVE_LIMIT) }
                val name = entry.name
                require(name.isNotEmpty() && name.length <= 300 && !name.startsWith('/') && '\\' !in name && name.split('/').none { it == ".." }) { tr(TextKey.IMPORT_ARCHIVE_INVALID) }
                val out = ByteArrayOutputStream()
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_EXPANDED_BYTES) { tr(TextKey.IMPORT_ARCHIVE_LIMIT) }
                    out.write(buffer, 0, n)
                }
                if (!entry.isDirectory) require(entries.put(name, out.toByteArray()) == null) { tr(TextKey.IMPORT_ARCHIVE_INVALID) }
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun readWorkbook(entries: Map<String, ByteArray>): List<ImportRow> {
        val sheets = mutableListOf<Pair<String, String>>()
        parse(entries.getValue("xl/workbook.xml")) { p, event ->
            if (event == XmlPullParser.START_TAG && p.name == "sheet") {
                val name = p.getAttributeValue(null, "name") ?: invalid(tr(TextKey.IMPORT_ARCHIVE_INVALID))
                val id = p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                    ?: invalid(tr(TextKey.IMPORT_ARCHIVE_INVALID))
                sheets += name to id
            }
        }
        val targets = mutableMapOf<String, String>()
        parse(entries["xl/_rels/workbook.xml.rels"] ?: invalid(tr(TextKey.IMPORT_ARCHIVE_INVALID))) { p, event ->
            if (event == XmlPullParser.START_TAG && p.name == "Relationship" && p.getAttributeValue(null, "TargetMode") != "External") {
                val id = p.getAttributeValue(null, "Id") ?: invalid(tr(TextKey.IMPORT_ARCHIVE_INVALID))
                val target = p.getAttributeValue(null, "Target") ?: invalid(tr(TextKey.IMPORT_ARCHIVE_INVALID))
                val path = if (target.startsWith('/')) target.removePrefix("/") else "xl/$target"
                require(!path.contains('\\') && path.split('/').none { it == ".." } && path.startsWith("xl/")) { tr(TextKey.IMPORT_ARCHIVE_INVALID) }
                require(targets.put(id, path.replace("/./", "/")) == null) { tr(TextKey.IMPORT_ARCHIVE_INVALID) }
            }
        }
        val strings = sharedStrings(entries["xl/sharedStrings.xml"])
        val selected = sheets.filter { it.first.lowercase(Locale.ROOT) in sheetNames }
        AppDiagnostics.event("import.xlsx.sheets",mapOf("total" to sheets.size,"detail_sheets" to selected.joinToString { it.first }))
        require(selected.isNotEmpty()) { tr(TextKey.IMPORT_REPORT_SHEETS) }
        require(selected.map { it.first.lowercase(Locale.ROOT) }.distinct().size == selected.size) { tr(TextKey.IMPORT_REPORT_SHEETS) }
        return buildList {
            selected.forEach { (name, id) ->
                val data = targets[id]?.let(entries::get) ?: invalid(tr(TextKey.IMPORT_ARCHIVE_INVALID))
                inSheet(name) { readSheet(data, strings) { row -> checkRows(size + 1); add(row) } }
            }
        }
    }

    private fun sharedStrings(data: ByteArray?): List<String> {
        if (data == null) return emptyList()
        val result = mutableListOf<String>()
        var value: StringBuilder? = null
        var textDepth = -1
        var phoneticDepth = -1
        parse(data) { p, event ->
            when {
                event == XmlPullParser.START_TAG && p.name == "si" -> value = StringBuilder()
                event == XmlPullParser.START_TAG && p.name == "rPh" -> phoneticDepth = p.depth
                event == XmlPullParser.START_TAG && p.name == "t" && phoneticDepth < 0 -> textDepth = p.depth
                event in listOf(XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF) && textDepth >= 0 -> append(value, p.text.orEmpty())
                event == XmlPullParser.END_TAG && p.depth == textDepth -> textDepth = -1
                event == XmlPullParser.END_TAG && p.depth == phoneticDepth -> phoneticDepth = -1
                event == XmlPullParser.END_TAG && p.name == "si" -> {
                    require(result.size < 200_000) { tr(TextKey.IMPORT_ARCHIVE_LIMIT) }
                    result += unescape(value.toString()); value = null
                }
            }
        }
        return result
    }

    private fun readSheet(data: ByteArray, strings: List<String>, consume: (ImportRow) -> Unit) {
        var headers: List<String>? = null
        var mapping: ReportColumns? = null
        var values = mutableListOf<String>()
        var row = 0
        var lastRow = 0
        var column = -1
        var type: String? = null
        var value = StringBuilder()
        var textDepth = -1
        var formula = false
        parse(data) { p, event ->
            when {
                event == XmlPullParser.START_TAG && p.name == "row" -> {
                    row = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (lastRow + 1)
                    require(row > lastRow && row <= 1_048_576) { tr(TextKey.IMPORT_ARCHIVE_INVALID) }
                    lastRow = row; values = mutableListOf(); column = -1
                }
                event == XmlPullParser.START_TAG && p.name == "c" -> {
                    val reference = p.getAttributeValue(null, "r")
                    val next = reference?.let(::columnIndex) ?: (column + 1)
                    require(next > column && next < MAX_COLUMNS) { tr(TextKey.IMPORT_TOO_MANY_COLUMNS) }
                    column = next; type = p.getAttributeValue(null, "t"); value = StringBuilder(); formula = false
                }
                event == XmlPullParser.START_TAG && p.name == "f" -> formula = true
                event == XmlPullParser.START_TAG && (p.name == "v" || (p.name == "t" && type == "inlineStr")) -> textDepth = p.depth
                event in listOf(XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF) && textDepth >= 0 -> append(value, p.text.orEmpty())
                event == XmlPullParser.END_TAG && p.depth == textDepth -> textDepth = -1
                event == XmlPullParser.END_TAG && p.name == "c" -> {
                    require(!formula) { tr(TextKey.IMPORT_FORMULA_ROW, row) }
                    val raw = value.toString()
                    val cell = when (type) {
                        "s" -> strings.getOrNull(raw.toIntOrNull() ?: -1) ?: invalid(tr(TextKey.IMPORT_ARCHIVE_INVALID))
                        "inlineStr", "str" -> unescape(raw)
                        null, "n" -> {
                            if (column == mapping?.indexes?.get("quantity") && raw.isNotEmpty()) {
                                try { BigDecimal(raw).toBigIntegerExact().toString() }
                                catch (_: ArithmeticException) { raw }
                            } else raw
                        }
                        else -> invalid(tr(TextKey.IMPORT_CELL_INVALID, row))
                    }
                    while (values.size <= column) values += ""
                    values[column] = cell
                }
                event == XmlPullParser.END_TAG && p.name == "row" && values.any { it.isNotBlank() } -> {
                    if (headers == null) {
                        headers = values.toList()
                        mapping = columns(values) ?: invalid(tr(TextKey.IMPORT_REPORT_HEADERS))
                    } else {
                        require(values != headers) { tr(TextKey.REPEATED_HEADER_KEEP_ONLY_THE_FIRST_HEADER_ROW) }
                        consume(InventoryImport.validate(mapping!!.standardFields(values), row))
                    }
                }
            }
        }
        require(headers != null) { tr(TextKey.IMPORT_REPORT_HEADERS) }
    }

    private fun columnIndex(reference: String): Int {
        require(reference.matches(Regex("[A-Z]+[1-9][0-9]*"))) { tr(TextKey.IMPORT_ARCHIVE_INVALID) }
        var index = 0
        for (c in reference.takeWhile { it in 'A'..'Z' }) {
            index = index * 26 + (c - 'A' + 1)
            require(index <= MAX_COLUMNS) { tr(TextKey.IMPORT_TOO_MANY_COLUMNS) }
        }
        return index - 1
    }

    private fun append(value: StringBuilder?, text: String) {
        if (value == null) return
        require(value.length + text.length <= MAX_TEXT) { tr(TextKey.EACH_FIELD_MUST_NOT_EXCEED_4000_CHARACTERS) }
        value.append(text)
    }
    private fun unescape(value: String) = Regex("_x([0-9a-fA-F]{4})_").replace(value) { it.groupValues[1].toInt(16).toChar().toString() }
    private fun checkRows(count: Int) { require(count <= InventoryImport.MAX_ROWS) { tr(TextKey.DATA_MUST_NOT_EXCEED_10000_ROWS_IMPORT_IN_SMALLER) } }
    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
    private inline fun <T> inSheet(name: String, operation: () -> T): T = try { operation() }
    catch (failure: IllegalArgumentException) { throw IllegalArgumentException(tr(TextKey.IMPORT_SHEET_ERROR, name, failure.message), failure) }

    private inline fun parse(data: ByteArray, handle: (XmlPullParser, Int) -> Unit) {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(data.inputStream(), null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            require(event != XmlPullParser.DOCDECL && parser.depth <= 64) { tr(TextKey.IMPORT_ARCHIVE_INVALID) }
            handle(parser, event)
            event = parser.nextToken()
        }
    }
}
