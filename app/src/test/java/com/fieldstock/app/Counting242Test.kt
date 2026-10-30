package com.fieldstock.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.i18n.*
import com.fieldstock.app.importdata.InventoryImport
import com.fieldstock.app.model.*
import com.fieldstock.app.scan.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class Counting242Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repository: InventoryRepository
    private lateinit var input: RecordInput

    @Before fun seed() {
        AppLanguage.current = AppLanguage.CHINESE
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        repository = InventoryRepository(context)
        val warehouse = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        input = RecordInput(repository.snapshot().tasks.single().id, warehouse.id, ItemKind.MACHINE,
            "", "000a-01", 1, StockCondition.NORMAL, "Line 1: A+B / 2\nLine 2 (OK), #3")
    }
    @After fun close() { repository.close(); context.deleteDatabase(InventoryRepository.DATABASE_NAME); AppLanguage.current = AppLanguage.CHINESE }

    @Test fun snOnlyMachinesSaveEditReopenAndKeepGlobalSerialProtection() {
        val saved = repository.save(input)
        assertEquals("", saved.bom); assertEquals("", saved.pn)
        val updated = repository.save(input.copy(pn = "001-Ab-02-C", sourcePn = "ocr", rawPn = "PN: 001-Ab-02-C"), saved.id)
        repository.close(); repository = InventoryRepository(context)
        assertEquals(updated, repository.snapshot().records.single())
        assertThrows(DuplicateSnException::class.java) { repository.save(input.copy(pn = "different")) }
        assertThrows(IllegalArgumentException::class.java) { repository.save(input.copy(sn = "")) }
        assertThrows(IllegalArgumentException::class.java) { repository.save(input.copy(kind = ItemKind.PART, sn = "")) }
    }

    @Test fun pnValidationKeepsZerosCaseAndMultipleHyphensWithoutWeakeningSn() {
        listOf("001a", "A-B-C-002-xY", "1".repeat(100)).forEachIndexed { index, pn ->
            assertNull(IdentifierRules.validatePn(pn))
            assertEquals(pn, repository.save(input.copy(sn = "T" + index + "-01", pn = pn)).pn)
        }
        for (pn in listOf("-AB", "AB-", "A--B", "A B", "A/B", "A_B", "1".repeat(101))) {
            assertNotNull(pn, IdentifierRules.validatePn(pn))
            assertThrows(IllegalArgumentException::class.java) { repository.save(input.copy(pn = pn)) }
        }
        assertNotNull(IdentifierRules.validateSn("A-B-C"))
    }

    @Test fun bothLanguagesAcceptNewMixedPnCsvAndLegacySnOnlyCsvAndProtectReceipts() {
        val old = "Type,BOM,SN,Quantity,Condition,Note\nMachine,,000a-01,1,Normal,note\n"
        val emptyPn = old.replace("Condition,Note", "Condition,Note,PN").replace("Normal,note", "Normal,note,")
        for (language in AppLanguage.entries) {
            AppLanguage.current = language
            val legacyRows = InventoryImport.read(old.byteInputStream(), null)
            assertEquals("", legacyRows.single().bom)
            assertEquals(InventoryImport.fingerprint(legacyRows), InventoryImport.fingerprint(InventoryImport.read(emptyPn.byteInputStream(), null)))
            val zh = "类型,BOM,SN,数量,状态,备注,PN\n整机,,000a-01,1,正常,\"first, + / #\nsecond\",000-aB-02-C\n"
            val en = zh.replace("类型", "Type").replace("数量", "Quantity").replace("状态", "Condition")
                .replace("整机", "Machine").replace("正常", "Normal")
            val rows = InventoryImport.read(zh.byteInputStream(), null)
            assertEquals(rows, InventoryImport.read(en.byteInputStream(), null))
            assertEquals("000-aB-02-C", rows.single().pn)
            assertEquals("first, + / #\nsecond", rows.single().note)
            assertNotEquals(InventoryImport.fingerprint(rows), InventoryImport.fingerprint(rows.map { it.copy(pn = "other") }))
            assertThrows(IllegalArgumentException::class.java) {
                InventoryImport.read("Type,BOM,SN,Quantity,Condition,Note,PN\nPart,ABC,,1,Normal,,A-B\n".byteInputStream(), null)
            }
        }
    }

    @Test fun pnAndNoteOcrSeparateLabelFieldsAndKeepWholeMultilineNotes() {
        val label = "BOM: 001AB PN: 000-A-b2-C SN: 000a-01"
        assertEquals("000-A-b2-C", OcrCandidates.extract(label, IdentifierField.PN).single().value)
        assertEquals("001AB", OcrCandidates.extract(label, IdentifierField.BOM).single().value)
        assertEquals("000a-01", OcrCandidates.extract(label, IdentifierField.SN).single().value)
        assertEquals("000-A-b2-C", OcrCandidates.extract("P/N: 000 - A - b2 - C", IdentifierField.PN).single().value)
        assertTrue(OcrCandidates.extract("PN: A_B", IdentifierField.PN).isEmpty())
        val note = "SN: 000a-01 / Part #2 + 3 = 5\r\nOK (A,B); @customer\nBOM: 001AB"
        assertEquals(note.replace("\r\n", "\n"), OcrCandidates.extract(note, IdentifierField.NOTE).single().value)
        assertTrue(OcrCandidates.extract("x".repeat(4001), IdentifierField.NOTE).isEmpty())
        val lines = listOf(LiveTextLine("Row 1 #A", CodeBox(.1f, .1f, .8f, .2f)),
            LiveTextLine("Row 2: +5", CodeBox(.1f, .3f, .8f, .4f)))
        assertEquals("Row 1 #A\nRow 2: +5", LiveOcr.locate(lines, IdentifierField.NOTE).single().candidate.value)
    }

    @Test fun backupV2PreservesPnAuditAndUndoWhileVersionOneStillReads() {
        val saved = repository.save(input.copy(pn = "001-a-B", sourcePn = "ocr", rawPn = "PN: 001-a-B"))
        val snapshot = repository.snapshot()
        val bytes = ByteArrayOutputStream().also { InventoryFiles.writeBackup(snapshot, it) }.toByteArray()
        assertEquals(snapshot, InventoryFiles.readBackup(bytes.inputStream()))
        repository.recoverable("Edit PN") { it.save(input.copy(pn = "NEW-1"), saved.id) }
        repository.undoRecovery(repository.recoveryPoints().single().id)
        assertEquals(saved, repository.snapshot().records.single())
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        val payload = JSONObject(envelope.getString("payload"))
        val records = payload.getJSONArray("records")
        for (i in 0 until records.length()) for (key in listOf("pn", "sourcePn", "rawPn")) records.getJSONObject(i).remove(key)
        val text = payload.toString()
        envelope.put("version", 1).put("payload", text).put("checksum", sha256(text))
        val old = InventoryFiles.readBackup(envelope.toString().byteInputStream())
        assertEquals("", old.records.single().pn)
        assertEquals(snapshot.records.single().note, old.records.single().note)
    }

    @Test fun bilingualReportsPreservePnAsTextAndMultilineNotesInCorrectColumns() {
        repository.save(input.copy(pn = "000-aB-02-C", sourcePn = "ocr", rawPn = "PN: 000-aB-02-C"))
        for (language in AppLanguage.entries) {
            val xlsx = ByteArrayOutputStream().also { InventoryFiles.writeXlsx(repository.snapshot(), it, null, null, language) }
            val files = unzip(xlsx.toByteArray())
            val sheet = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(files.getValue("xl/worksheets/sheet4.xml").byteInputStream())
            val cells = sheet.getElementsByTagName("c")
            val pn = (0 until cells.length).map { cells.item(it) as org.w3c.dom.Element }.single { it.getAttribute("r") == "D2" }
            assertEquals("inlineStr", pn.getAttribute("t")); assertEquals("000-aB-02-C", pn.textContent)
            assertTrue(sheet.documentElement.textContent.contains(input.note))
            assertTrue(sheet.documentElement.textContent.contains(language.text(TextKey.PN_SOURCE)))
            val csv = ByteArrayOutputStream().also { InventoryFiles.writeCsvZip(repository.snapshot(), it, null, null, language) }
            val detail = unzip(csv.toByteArray()).getValue(language.text(TextKey.MACHINE_DETAIL_CSV))
            assertTrue(detail.contains("\"000-aB-02-C\"")); assertTrue(detail.contains(input.note))
        }
    }

    @Test fun genuineSchemaFourTableUpgradesWithoutLosingInventoryHistoryOrReceipts() {
        val saved = repository.save(input.copy(bom = "OLD001"))
        repository.importRecords(listOf(input.copy(sn = "000b-02", bom = "OLD002")), DuplicateMode.NEW_ENTRY,
            ImportMetadata("a".repeat(64), "legacy.csv"))
        val before = repository.snapshot(); val receipts = repository.importReceipts()
        repository.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(InventoryRepository.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val oldSql = db.rawQuery("SELECT sql FROM sqlite_master WHERE name='records'", null).use { c -> c.moveToFirst(); c.getString(0) }
                .replace("CREATE TABLE records", "CREATE TABLE legacy_records")
                .replace("pn TEXT NOT NULL DEFAULT '', source_pn TEXT NOT NULL DEFAULT 'manual', raw_pn TEXT NOT NULL DEFAULT '',", "")
            val indexes = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='records' AND sql IS NOT NULL", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            val columns = "id,task_id,warehouse_id,kind,bom,sn,quantity,condition,note,location,source_bom,source_sn,raw_bom,raw_sn,created_at,updated_at,voided"
            db.execSQL(oldSql); db.execSQL("INSERT INTO legacy_records (" + columns + ") SELECT " + columns + " FROM records")
            db.execSQL("DROP TABLE records"); db.execSQL("ALTER TABLE legacy_records RENAME TO records")
            indexes.forEach(db::execSQL); db.version = 4
            db.rawQuery("SELECT * FROM records", null).use { assertEquals(-1, it.getColumnIndex("pn")) }
        }
        repository = InventoryRepository(context)
        assertEquals(before, repository.snapshot()); assertEquals(receipts, repository.importReceipts())
        assertEquals("NEW-A-02", repository.save(input.copy(bom = saved.bom, pn = "NEW-A-02"), saved.id).pn)
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes().toString(Charsets.UTF_8)) } }
    }
}
