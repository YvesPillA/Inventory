package com.fieldstock.app.importdata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.i18n.*
import com.fieldstock.app.model.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ExportImportCompatibilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repository: InventoryRepository
    private lateinit var input: RecordInput

    @Before fun seed() {
        AppLanguage.current = AppLanguage.CHINESE
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        repository = InventoryRepository(context)
        val warehouse = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        input = RecordInput(repository.snapshot().tasks.single().id, warehouse.id, ItemKind.PART,
            "000aB", "", 3, StockCondition.NORMAL, "Line 1: A+B / 2\r\n中文, <tag> & _x0041_ \"OK\"")
    }
    @After fun close() { repository.close(); context.deleteDatabase(InventoryRepository.DATABASE_NAME); AppLanguage.current = AppLanguage.CHINESE }
    private fun output(write: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(write).toByteArray()
    private fun csv(rows: List<ImportRow>) = rows.groupingBy { it }.eachCount()
    private fun StockRecord.importRow() = ImportRow(kind, bom, sn, quantity, condition, note, pn)
    private fun unzip(bytes: ByteArray) = linkedMapOf<String, ByteArray>().also { files ->
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
    }
    private fun zip(files: Map<String, ByteArray>) = output { out ->
        ZipOutputStream(out).use { z -> files.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }

    @Test fun exportedWorkbooksRoundTripAcrossLanguagesWithoutImportingSummariesOrVoidedRecords() {
        repository.save(input); repository.save(input.copy(quantity = 4))
        repository.save(input.copy(kind = ItemKind.MACHINE, bom = "", sn = "000a-01", quantity = 1, pn = "00-A-b2-C"))
        repository.save(input.copy(bom = "", condition = StockCondition.PENDING))
        repository.save(input.copy(kind = ItemKind.MACHINE, bom = "", sn = "", quantity = 1, condition = StockCondition.PENDING))
        repository.voidRecord(repository.save(input.copy(bom = "VOID01")).id)
        val snapshot = repository.snapshot()
        for (language in AppLanguage.entries) {
            val bytes = output { InventoryFiles.writeXlsx(snapshot, it, null, null, language) }
            AppLanguage.current = if (language == AppLanguage.CHINESE) AppLanguage.ENGLISH else AppLanguage.CHINESE
            val content = InventoryImport.readFile(bytes.inputStream(), "renamed.csv") as InventoryImportContent.Rows
            assertEquals(5, content.rows.size)
            assertEquals(csv(snapshot.records.filterNot { it.voided }.map { it.importRow() }), csv(content.rows))
            assertEquals(7, content.rows.filter { it.kind == ItemKind.PART && it.condition == StockCondition.NORMAL }.sumOf { it.quantity })
        }
    }

    @Test fun legacyReportsWithoutPnAndExcelSharedStringsWithSparseCellsAreAccepted() {
        val strings = listOf("国家", "仓库", "BOM", "SN", "数量", "单位", "状态", "备注", "记录ID", "Norway", "Main", "000a-01", "损坏", "first\nsecond & + / #", "legacy-id")
        fun cell(column: Char, row: Int, index: Int) = "<c r=\"$column$row\" t=\"s\"><v>$index</v></c>"
        val head = (0..8).joinToString("") { cell('A' + it, 1, it) }
        val data = cell('A',2,9) + cell('B',2,10) + cell('D',2,11) + "<c r=\"E2\"><v>1.0</v></c>" + cell('G',2,12) + cell('H',2,13) + cell('I',2,14)
        val files = linkedMapOf(
            "xl/workbook.xml" to "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"整机明细\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"rId1\" Target=\"/xl/worksheets/renamed.xml\"/></Relationships>",
            "xl/worksheets/renamed.xml" to "<worksheet><sheetData><row r=\"1\">$head</row><row r=\"2\">$data</row></sheetData></worksheet>",
            "xl/sharedStrings.xml" to "<sst>" + strings.joinToString("") { "<si><t>${it.replace("&", "&amp;")}</t></si>" } + "</sst>"
        )
        val row = InventoryImport.read(zip(files.mapValues { it.value.toByteArray() }).inputStream(), "old.xlsx").single()
        assertEquals("", row.bom); assertEquals("", row.pn); assertEquals("000a-01", row.sn)
        assertEquals(1, row.quantity); assertEquals(StockCondition.DAMAGED, row.condition)
        assertEquals("first\nsecond & + / #", row.note)
    }

    @Test fun csvZipAndIndividualExportedCsvCanBeReadInEitherLanguage() {
        repository.save(input.copy(note = "plain, + / #\nline 2"))
        repository.save(input.copy(kind = ItemKind.MACHINE, sn = "00a-01", pn = "00-A-B-C", quantity = 1))
        repository.save(input.copy(bom = "", condition = StockCondition.PENDING))
        val snapshot = repository.snapshot()
        for (language in AppLanguage.entries) {
            val bytes = output { InventoryFiles.writeCsvZip(snapshot, it, null, null, language) }
            AppLanguage.current = if (language == AppLanguage.CHINESE) AppLanguage.ENGLISH else AppLanguage.CHINESE
            val rows = InventoryImport.read(bytes.inputStream(), "report.zip")
            assertEquals(csv(snapshot.records.map { it.importRow() }), csv(rows))
            val individual = unzip(bytes).filterKeys { it.endsWith(".csv") }.flatMap { (name, data) -> InventoryImport.read(data.inputStream(), name) }
            assertEquals(csv(rows), csv(individual))
        }
    }

    @Test fun allDetailSheetsAreValidatedBeforeAnyRowsAreReturned() {
        repository.save(input)
        repository.save(input.copy(kind = ItemKind.MACHINE, sn = "00a-01", quantity = 1))
        val files = unzip(output { InventoryFiles.writeXlsx(repository.snapshot(), it, null, null) })
        val machine = files.getValue("xl/worksheets/sheet4.xml").toString(Charsets.UTF_8)
        files["xl/worksheets/sheet4.xml"] = machine.replace("00a-01", "bad--sn").toByteArray()
        val message = assertThrows(IllegalArgumentException::class.java) { InventoryImport.read(zip(files).inputStream(), "report.xlsx") }.message.orEmpty()
        assertTrue(message, message.contains("整机明细")); assertTrue(message, message.contains("第 2 行"))
        assertEquals(2, repository.snapshot().records.size)
    }

    @Test fun formulaCellsAndUnsafeXmlAreRejectedInsteadOfUsingCachedValues() {
        repository.save(input)
        val original = unzip(output { InventoryFiles.writeXlsx(repository.snapshot(), it, null, null) })
        val sheet = original.getValue("xl/worksheets/sheet2.xml").toString(Charsets.UTF_8)
        val formula = original.toMutableMap().apply { this["xl/worksheets/sheet2.xml"] = sheet.replace("<v>3</v>", "<f>1+2</f><v>3</v>").toByteArray() }
        assertTrue(assertThrows(IllegalArgumentException::class.java) { InventoryImport.read(zip(formula).inputStream(), null) }.message.orEmpty().contains("公式"))
        val doctype = original.toMutableMap().apply { this["xl/worksheets/sheet2.xml"] = sheet.replace("<worksheet", "<!DOCTYPE worksheet [<!ENTITY probe SYSTEM 'file:///private-data'>]><worksheet").toByteArray() }
        assertThrows(IllegalArgumentException::class.java) { InventoryImport.read(zip(doctype).inputStream(), null) }
    }

    @Test fun fullBackupSelectionPreservesHistoryAndVoidedRecordsAndChecksIntegrity() {
        val saved = repository.save(input)
        repository.voidRecord(saved.id)
        val snapshot = repository.snapshot()
        val bytes = output { InventoryFiles.writeBackup(snapshot, it) }
        for (version in 1..2) {
            val data = if (version == 1) legacyBackup(bytes) else bytes
            val content = InventoryImport.readFile(("\uFEFF \n" + data.toString(Charsets.UTF_8)).byteInputStream(), "renamed.xlsx") as InventoryImportContent.Backup
            assertEquals(snapshot, content.snapshot)
        }
        val bad = JSONObject(bytes.toString(Charsets.UTF_8)).put("checksum", "0".repeat(64)).toString()
        assertThrows(IllegalArgumentException::class.java) { InventoryImport.readFile(bad.byteInputStream(), "backup.json") }
        assertEquals(snapshot, repository.snapshot())
    }

    @Test fun importingTheSameRowsFromExcelAndTemplateKeepsTheExistingContentReminder() {
        repository.save(input.copy(note = "same"))
        val workbook = output { InventoryFiles.writeXlsx(repository.snapshot(), it, null, null) }
        val rows = InventoryImport.read(workbook.inputStream(), "report.xlsx")
        val csv = "Type,BOM,SN,Quantity,Condition,Note,PN\nPart,000aB,,3,Normal,same,\n"
        assertEquals(InventoryImport.fingerprint(rows), InventoryImport.fingerprint(InventoryImport.read(csv.byteInputStream(), "template.csv")))
    }

    @Test fun suppliedFilesCanBeImportedWithoutConvertingOrPublishingTheirContents() {
        val path = System.getenv("FIELDSTOCK_COMPAT_SAMPLES_DIR")
        assumeTrue("Optional local customer samples", !path.isNullOrEmpty())
        val files = File(path!!).listFiles()!!.toList()
        val xlsx = files.single { it.extension == "xlsx" }
        val backup = files.single { it.extension == "json" }
        val template = files.single { it.extension == "csv" }
        val rows = xlsx.inputStream().use { (InventoryImport.readFile(it, xlsx.name) as InventoryImportContent.Rows).rows }
        val snapshot = backup.inputStream().use { (InventoryImport.readFile(it, backup.name) as InventoryImportContent.Backup).snapshot }
        assertEquals(400, rows.size); assertEquals(380, rows.count { it.kind == ItemKind.PART }); assertEquals(20, rows.count { it.kind == ItemKind.MACHINE })
        assertEquals(csv(snapshot.records.filterNot { it.voided }.map { it.importRow() }), csv(rows))
        assertTrue(template.inputStream().use { (InventoryImport.readFile(it, template.name) as InventoryImportContent.Rows).rows }.isEmpty())
        assertEquals(400, repository.restore(snapshot))
        assertEquals(0, repository.restore(snapshot))
        assertEquals(400, repository.snapshot().records.size)
        assertEquals(snapshot.changes.size, repository.snapshot().changes.size)
    }

    private fun legacyBackup(bytes: ByteArray): ByteArray {
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        val payload = JSONObject(envelope.getString("payload"))
        val rows = payload.getJSONArray("records")
        for (index in 0 until rows.length()) for (key in listOf("pn", "sourcePn", "rawPn")) rows.getJSONObject(index).remove(key)
        val text = payload.toString()
        val sha = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return envelope.put("version",1).put("payload",text).put("checksum",sha).toString().toByteArray()
    }
}
