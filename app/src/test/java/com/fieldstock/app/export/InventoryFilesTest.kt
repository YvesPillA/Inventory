package com.fieldstock.app.export

import com.fieldstock.app.model.ChangeLog
import com.fieldstock.app.model.Country
import com.fieldstock.app.model.InventorySnapshot
import com.fieldstock.app.model.InventoryTask
import com.fieldstock.app.model.ItemKind
import com.fieldstock.app.model.StockRecord
import com.fieldstock.app.model.Warehouse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class InventoryFilesTest {
    private fun record(
        id: String = "p1", kind: ItemKind = ItemKind.PART, quantity: Int = 12,
        warehouseId: String = "w1", condition: String = "正常", voided: Boolean = false,
        note: String = "", taskId: String = "t1"
    ) = StockRecord(
        id = id, taskId = taskId, warehouseId = warehouseId, kind = kind,
        bom = "001ABC", sn = if (kind == ItemKind.MACHINE) "0001-02AB" else "",
        quantity = quantity, condition = condition, note = note, location = "A-01",
        sourceBom = "ocr", sourceSn = "manual", rawBom = "BOM: 001ABC", rawSn = "",
        createdAt = 1_790_000_000_000L, updatedAt = 1_790_000_001_000L, voided = voided
    )
    private fun snapshot(records: List<StockRecord> = listOf(record())) = InventorySnapshot(
        tasks = listOf(InventoryTask(id = "t1", name = "北欧盘点", createdAt = 1_790_000_000_000L), InventoryTask(id = "t2", name = "复盘", createdAt = 1_790_000_000_000L)),
        countries = listOf(Country(id = "c1", name = "挪威 & 瑞典"), Country(id = "c2", name = "中国")),
        warehouses = listOf(Warehouse(id = "w1", countryId = "c1", name = "奥斯陆 <A>"), Warehouse(id = "w2", countryId = "c2", name = "上海")),
        records = records,
        changes = listOf(ChangeLog(id = "a1", recordId = "p1", action = "UPDATE", before = "{\"note\":\"before\"}", after = "{\"note\":\"中文\\n\"}", at = 1_790_000_001_000L))
    )

    @Test fun xlsxPreservesIdentifiersAsTextAndHasNumericTotals() {
        val data = snapshot(listOf(record(quantity = Int.MAX_VALUE), record(id = "p2", quantity = 3), record(id = "damage", quantity = 2, condition = "损坏"), record(id = "old", quantity = 99, voided = true), record(id = "m1", kind = ItemKind.MACHINE, quantity = 1)))
        val output = ByteArrayOutputStream()
        InventoryFiles.writeXlsx(data, output, null, null)
        val files = unzip(output.toByteArray())
        assertTrue(files.containsKey("[Content_Types].xml"))
        assertEquals(5, files.keys.count { it.startsWith("xl/worksheets/") })
        val detail = parseXml(files.getValue("xl/worksheets/sheet2.xml"))
        assertEquals("inlineStr", cell(detail, "C2").getAttribute("t"))
        assertEquals("001ABC", cell(detail, "C2").textContent)
        assertFalse(detail.documentElement.textContent.contains("A-01"))
        assertEquals("", cell(detail, "D2").getAttribute("t"))
        val summary = parseXml(files.getValue("xl/worksheets/sheet3.xml"))
        assertTrue((0 until summary.getElementsByTagName("v").length).any { summary.getElementsByTagName("v").item(it).textContent == "2147483650" })
        assertFalse(detail.documentElement.textContent.contains("99"))
        val machine = parseXml(files.getValue("xl/worksheets/sheet4.xml"))
        assertEquals("inlineStr", cell(machine, "E2").getAttribute("t"))
        assertEquals("0001-02AB", cell(machine, "E2").textContent)
        assertTrue(cell(detail, "J2").textContent.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+-]\\d{2}:\\d{2})")))
    }

    @Test fun xlsxEscapesFreeTextAndRemovesIllegalXmlCharactersWithoutCreatingFormula() {
        val note = "=SUM(1,2) & <tag> \"quote\"\ncontrol:\u0001 literal:_x0041_ emoji:😀"
        val output = ByteArrayOutputStream()
        InventoryFiles.writeXlsx(snapshot(listOf(record(note = note))), output, "t1", "w1")
        val xml = unzip(output.toByteArray()).getValue("xl/worksheets/sheet2.xml")
        val document = parseXml(xml) // Fails if an illegal XML 1.0 control survives.
        assertEquals(0, document.getElementsByTagName("f").length)
        assertEquals("inlineStr", cell(document, "G2").getAttribute("t"))
        assertEquals(note.replace("\u0001", "\uFFFD").replace("_x0041_", "_x005F_x0041_"), cell(document, "G2").textContent)
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&lt;tag&gt;"))
    }

    @Test fun csvProtectsFormulaFreeTextFiltersWarehouseAndIncludesHistoricalRecords() {
        val records = listOf(record(note = " \t=HYPERLINK(\"x\")\nnext,line"), record(id = "other-task", taskId = "t2"), record(id = "other-warehouse", warehouseId = "w2"), record(id = "voided", voided = true), record(id = "m1", kind = ItemKind.MACHINE, quantity = 1))
        val output = ByteArrayOutputStream()
        InventoryFiles.writeCsvZip(snapshot(records), output, "t1", "w1")
        val files = unzip(output.toByteArray())
        assertEquals(3, files.keys.count { it.endsWith(".csv") })
        val parts = files.getValue("配件明细.csv")
        assertTrue(parts.startsWith("\uFEFF"))
        assertTrue(parts.contains("\"001ABC\""))
        assertTrue(parts.contains("\"' \t=HYPERLINK(\"\"x\"\")\nnext,line\""))
        assertTrue(parts.contains("other-task"))
        assertFalse(parts.contains("other-warehouse"))
        assertFalse(parts.contains("voided"))
        assertTrue(files.getValue("整机明细.csv").contains("\"0001-02AB\""))
        assertTrue(files.getValue("CSV导入说明.txt").contains("文本"))
    }

    @Test fun exportsMergeLegacyTasksWithinWarehouseAndOmitTaskAndLocationFromEveryVisibleSheet() {
        val data = snapshot(listOf(
            record(id = "legacy-one", taskId = "t1", quantity = 4),
            record(id = "legacy-two", taskId = "t2", quantity = 7),
            record(id = "legacy-damaged", taskId = "t2", quantity = 2, condition = "损坏"),
            record(id = "other-warehouse", taskId = "t2", warehouseId = "w2", quantity = 100),
            record(id = "voided-legacy", taskId = "t2", quantity = 70, voided = true),
            record(id = "pending-legacy", taskId = "t2", quantity = 50, condition = "待核查"),
            record(id = "legacy-machine", taskId = "t2", kind = ItemKind.MACHINE, quantity = 1)
        ))
        val xlsx = ByteArrayOutputStream()
        // Even a nonexistent old filter must be ignored; only the warehouse determines scope.
        InventoryFiles.writeXlsx(data, xlsx, "retired-task-filter", "w1")
        val sheets = unzip(xlsx.toByteArray())
        val summaryRows = rows(parseXml(sheets.getValue("xl/worksheets/sheet3.xml")))
        assertEquals(listOf("国家", "仓库", "BOM", "数量", "单位", "状态", "录入条数"), summaryRows.first())
        assertEquals(3, summaryRows.size)
        val normal = summaryRows.drop(1).single { it[5] == "正常" }
        assertEquals("001ABC", normal[2])
        assertEquals("11", normal[3])
        assertEquals("2", normal[6])
        val parts = parseXml(sheets.getValue("xl/worksheets/sheet2.xml"))
        assertEquals(4, parts.getElementsByTagName("row").length)
        assertTrue(parts.documentElement.textContent.contains("legacy-one"))
        assertTrue(parts.documentElement.textContent.contains("legacy-two"))
        assertFalse(parts.documentElement.textContent.contains("other-warehouse"))
        assertFalse(parts.documentElement.textContent.contains("voided-legacy"))
        assertEquals("inlineStr", cell(parts, "C2").getAttribute("t"))
        assertEquals("001ABC", cell(parts, "C2").textContent)
        sheets.filterKeys { it.startsWith("xl/worksheets/") }.values.forEach { xml ->
            val visibleText = parseXml(xml).documentElement.textContent
            listOf("任务", "库位", "北欧盘点", "复盘", "A-01").forEach { forbidden ->
                assertFalse("XLSX should omit $forbidden", visibleText.contains(forbidden))
            }
        }
        val csv = ByteArrayOutputStream()
        InventoryFiles.writeCsvZip(data, csv, "retired-task-filter", "w1")
        val files = unzip(csv.toByteArray())
        assertTrue(files.getValue("配件明细.csv").contains("legacy-one"))
        assertTrue(files.getValue("配件明细.csv").contains("legacy-two"))
        assertFalse(files.getValue("配件明细.csv").contains("other-warehouse"))
        files.values.forEach { text ->
            listOf("任务", "库位", "北欧盘点", "复盘", "A-01").forEach { forbidden ->
                assertFalse("CSV should omit $forbidden", text.contains(forbidden))
            }
        }
    }

    @Test fun pendingRecordsArePreservedSeparatelyAndExcludedFromConfirmedDetailsAndTotals() {
        val data = snapshot(listOf(
            record(id = "confirmed-part", quantity = 12),
            record(id = "pending-part", quantity = 50, condition = "待核查", note = "编号需复核"),
            record(id = "confirmed-machine", kind = ItemKind.MACHINE, quantity = 1),
            record(id = "pending-machine", kind = ItemKind.MACHINE, quantity = 1, condition = "待核查", note = "标签模糊"),
            record(id = "voided-pending", condition = "待核查", voided = true),
            record(id = "other-warehouse-pending", condition = "待核查", warehouseId = "w2")
        ))
        val xlsx = ByteArrayOutputStream()
        InventoryFiles.writeXlsx(data, xlsx, "t1", "w1")
        val sheets = unzip(xlsx.toByteArray())
        val parts = parseXml(sheets.getValue("xl/worksheets/sheet2.xml"))
        val summary = parseXml(sheets.getValue("xl/worksheets/sheet3.xml"))
        val machines = parseXml(sheets.getValue("xl/worksheets/sheet4.xml"))
        assertEquals(2, parts.getElementsByTagName("row").length)
        assertEquals(2, summary.getElementsByTagName("row").length)
        assertEquals("12", cell(summary, "D2").textContent)
        assertEquals(2, machines.getElementsByTagName("row").length)
        assertFalse(parts.documentElement.textContent.contains("pending-part"))
        assertFalse(machines.documentElement.textContent.contains("pending-machine"))
        val pending = parseXml(sheets.getValue("xl/worksheets/sheet5.xml"))
        assertEquals(3, pending.getElementsByTagName("row").length)
        assertTrue(pending.documentElement.textContent.contains("pending-part"))
        assertTrue(pending.documentElement.textContent.contains("pending-machine"))
        assertTrue(pending.documentElement.textContent.contains("编号需复核"))
        assertTrue(pending.documentElement.textContent.contains("0001-02AB"))
        assertFalse(pending.documentElement.textContent.contains("A-01"))
        assertFalse(pending.documentElement.textContent.contains("voided-pending"))
        assertFalse(pending.documentElement.textContent.contains("other-warehouse-pending"))
        val csv = ByteArrayOutputStream()
        InventoryFiles.writeCsvZip(data, csv, "t1", "w1")
        val files = unzip(csv.toByteArray())
        assertFalse(files.getValue("配件明细.csv").contains("pending-part"))
        assertFalse(files.getValue("整机明细.csv").contains("pending-machine"))
        assertTrue(files.getValue("待核查.csv").contains("pending-part"))
        assertTrue(files.getValue("待核查.csv").contains("pending-machine"))
        assertFalse(files.getValue("待核查.csv").contains("voided-pending"))
        assertFalse(files.getValue("待核查.csv").contains("other-warehouse-pending"))
        assertTrue(files.getValue("CSV导入说明.txt").contains("不计入已确认数量"))
    }

    @Test fun backupRoundTripPreservesLegacyTasksLocationVoidedAuditOcrAndNonAscii() {
        val data = snapshot(listOf(record(note = "测试\n\"文本\"😀"), record(id = "voided", voided = true), record(id = "m1", kind = ItemKind.MACHINE, quantity = 1)))
        val output = ByteArrayOutputStream()
        InventoryFiles.writeBackup(data, output)
        val envelope = JSONObject(output.toString(Charsets.UTF_8.name()))
        assertEquals("fieldstock-backup", envelope.getString("format"))
        assertEquals(2, envelope.getInt("version"))
        val payload = JSONObject(envelope.getString("payload"))
        assertEquals(2, payload.getJSONArray("tasks").length())
        assertEquals("t1", payload.getJSONArray("records").getJSONObject(0).getString("taskId"))
        assertEquals("A-01", payload.getJSONArray("records").getJSONObject(0).getString("location"))
        assertEquals(data, InventoryFiles.readBackup(ByteArrayInputStream(output.toByteArray())))
    }

    @Test fun tamperedBackupIsRejectedBeforeItCanBeRestored() {
        val envelope = backupEnvelope()
        envelope.put("payload", envelope.getString("payload").replace("001ABC", "999ABC"))
        assertThrows(IllegalArgumentException::class.java) { InventoryFiles.readBackup(envelope.toString().byteInputStream()) }
    }

    @Test fun backupRejectsUnknownKindsMissingFieldsAndCoercedTypesEvenWithValidChecksum() {
        listOf<(JSONObject) -> Unit>(
            { it.getJSONArray("records").getJSONObject(0).put("kind", "UNKNOWN") },
            { it.getJSONArray("records").getJSONObject(0).remove("quantity"); Unit },
            { it.getJSONArray("records").getJSONObject(0).put("quantity", "12") },
            { it.getJSONArray("records").getJSONObject(0).put("quantity", 1.5) },
            { it.getJSONArray("records").getJSONObject(0).put("voided", "false") },
            { it.getJSONArray("records").getJSONObject(0).put("sn", JSONObject.NULL) },
            { it.getJSONArray("records").getJSONObject(0).remove("location"); Unit }
        ).forEach { mutate ->
            val envelope = backupEnvelope()
            val payload = JSONObject(envelope.getString("payload"))
            mutate(payload)
            val serialized = payload.toString()
            envelope.put("payload", serialized).put("checksum", sha256(serialized))
            assertThrows(IllegalArgumentException::class.java) { InventoryFiles.readBackup(envelope.toString().byteInputStream()) }
        }
    }

    @Test fun backupRejectsUnsupportedVersionsAndTrailingData() {
        val envelope = backupEnvelope().put("version", 999)
        assertThrows(IllegalArgumentException::class.java) { InventoryFiles.readBackup(envelope.toString().byteInputStream()) }
        assertThrows(IllegalArgumentException::class.java) { InventoryFiles.readBackup((backupEnvelope().toString() + "{}").byteInputStream()) }
    }

    private fun backupEnvelope(): JSONObject {
        val output = ByteArrayOutputStream()
        InventoryFiles.writeBackup(snapshot(), output)
        return JSONObject(output.toString(Charsets.UTF_8.name()))
    }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
    private fun parseXml(xml: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    private fun rows(document: Document): List<List<String>> = (0 until document.getElementsByTagName("row").length).map { index ->
        val row = document.getElementsByTagName("row").item(index) as Element
        (0 until row.getElementsByTagName("c").length).map { row.getElementsByTagName("c").item(it).textContent }
    }
    private fun cell(document: Document, reference: String): Element = (0 until document.getElementsByTagName("c").length)
        .map { document.getElementsByTagName("c").item(it) as Element }.first { it.getAttribute("r") == reference }
}
