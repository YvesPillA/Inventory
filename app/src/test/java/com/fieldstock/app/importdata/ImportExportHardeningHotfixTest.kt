package com.fieldstock.app.importdata

import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.i18n.*
import com.fieldstock.app.model.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ImportExportHardeningHotfixTest {
    @After fun resetLanguage() { AppLanguage.current = AppLanguage.CHINESE }
    private fun stock(note: String = "note") = StockRecord("record-1", "task", "warehouse", ItemKind.PART,
        "000Ab", "", 3, StockCondition.NORMAL, note, "", "manual", "manual", "", "", 1, 1, false)
    private fun snapshot(note: String = "note") = InventorySnapshot(listOf(InventoryTask("task", "First", 1)),
        listOf(Country("country", "Norway")), listOf(Warehouse("warehouse", "country", "Main")), listOf(stock(note)))
    private fun output(write: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(write).toByteArray()
    private fun workbook(note: String = "note", language: AppLanguage = AppLanguage.CHINESE) = output { InventoryFiles.writeXlsx(snapshot(note), it, null, null, language) }
    private fun unzip(bytes: ByteArray) = linkedMapOf<String, ByteArray>().also { files ->
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() } }
    }
    private fun zip(files: Map<String, ByteArray>) = output { out -> ZipOutputStream(out).use { zip ->
        files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
    } }
    private fun rows(bytes: ByteArray) = InventoryImport.read(bytes.inputStream(), "report.xlsx")

    @Test fun maximumNotesContainingLiteralSpreadsheetEscapesRoundTripInBothLanguages() {
        val note = "_x0041_".repeat(571) + "abc"
        assertEquals(4000, note.length)
        for (language in AppLanguage.entries) {
            AppLanguage.current = if (language == AppLanguage.ENGLISH) AppLanguage.CHINESE else AppLanguage.ENGLISH
            assertEquals(note, rows(workbook(note, language)).single().note)
        }
    }

    @Test fun sharedStringEscapesAreLimitedAfterDecodingAndOversizedDecodedNotesAreRejected() {
        val note = "_x0041_".repeat(571) + "abc"
        val files = unzip(workbook(note))
        val sheet = files.getValue("xl/worksheets/sheet2.xml").toString(Charsets.UTF_8)
        val pattern = Regex("<c r=\"G2\" t=\"inlineStr\">.*?</c>", RegexOption.DOT_MATCHES_ALL)
        files["xl/worksheets/sheet2.xml"] = pattern.replace(sheet, "<c r=\"G2\" t=\"s\"><v>0</v></c>").toByteArray()
        files["xl/sharedStrings.xml"] = ("<sst><si><t>" + note.replace("_x0041_", "_x005F_x0041_") + "</t></si></sst>").toByteArray()
        assertEquals(note, rows(zip(files)).single().note)
        files["xl/sharedStrings.xml"] = ("<sst><si><t>" + "a".repeat(4001) + "</t></si></sst>").toByteArray()
        assertThrows(IllegalArgumentException::class.java) { rows(zip(files)) }
    }

    @Test(timeout = 5000) fun enormousNumericExponentsAreRejectedBeforeAllocatingTheirExpandedInteger() {
        val original = unzip(workbook())
        val sheet = original.getValue("xl/worksheets/sheet2.xml").toString(Charsets.UTF_8)
        for (number in listOf("1E+1000000000", "1E-2147483647", "1000000001", "3.5")) {
            val files = original.toMutableMap().apply { this["xl/worksheets/sheet2.xml"] = sheet.replace("<v>3</v>", "<v>$number</v>").toByteArray() }
            assertThrows(IllegalArgumentException::class.java) { rows(zip(files)) }
        }
        val valid = original.toMutableMap().apply { this["xl/worksheets/sheet2.xml"] = sheet.replace("<v>3</v>", "<v>1E+9</v>").toByteArray() }
        assertEquals(1_000_000_000, rows(zip(valid)).single().quantity)
    }

    @Test fun copiedDetailInAnotherLanguageIsRejectedRatherThanCountedTwice() {
        val files = unzip(workbook())
        files["xl/workbook.xml"] = files.getValue("xl/workbook.xml").toString(Charsets.UTF_8)
            .replace("</sheets>", "<sheet name=\"Parts detail\" sheetId=\"6\" r:id=\"rId6\"/></sheets>").toByteArray()
        files["xl/_rels/workbook.xml.rels"] = files.getValue("xl/_rels/workbook.xml.rels").toString(Charsets.UTF_8)
            .replace("</Relationships>", "<Relationship Id=\"rId6\" Target=\"worksheets/sheet2.xml\"/></Relationships>").toByteArray()
        val failure = assertThrows(IllegalArgumentException::class.java) { rows(zip(files)) }
        assertTrue(failure.message.orEmpty().contains("record-1"))
    }

    @Test fun duplicateReportIdsAcrossCsvArchiveAreRejectedButStandardCsvDuplicateRowsRemainAllowed() {
        val report = "Country,Warehouse,BOM,Quantity,Condition,Note,Record ID\nNorway,Main,000Ab,3,Normal,note,record-1\n".toByteArray()
        val archive = zip(mapOf("配件明细.csv" to report, "Parts detail.csv" to report))
        assertThrows(IllegalArgumentException::class.java) { rows(archive) }
        val standard = "Type,BOM,SN,Quantity,Condition,Note\n" + "Part,000Ab,,3,Normal,note\n".repeat(2)
        assertEquals(2, InventoryImport.read(standard.byteInputStream(), null).size)
    }

    @Test fun csvSafetyFlagsPreserveMaximumNotesAndLiteralApostrophesWithoutEvaluatingAnything() {
        val notes = listOf("+" + "a".repeat(3999), "=" + "文".repeat(3999), "-stock", "@tag", "\nline 1\nline 2", "'+literal", "plain")
        for (language in AppLanguage.entries) for (note in notes) {
            val archive = output { InventoryFiles.writeCsvZip(snapshot(note), it, null, null, language) }
            assertEquals(note, rows(archive).single().note)
            val csv = unzip(archive).getValue(language.text(TextKey.PARTS_DETAIL_CSV))
            assertTrue(csv.toString(Charsets.UTF_8).contains(language.text(TextKey.CSV_NOTE_ESCAPED)))
            assertEquals(note, InventoryImport.read(csv.inputStream(), "report.csv").single().note)
            if (note.startsWith("+") || note.startsWith("=")) assertTrue(csv.toString(Charsets.UTF_8).contains("\"'$note\""))
        }
    }

    @Test fun legacySafetyApostrophesStayLiteralAndMalformedEscapeFlagsAreRejected() {
        val header = "Country,Warehouse,BOM,Quantity,Condition,Note,Record ID"
        val legacy = "$header\nNorway,Main,000Ab,3,Normal,'+literal,record-1\n"
        assertEquals("'+literal", InventoryImport.read(legacy.byteInputStream(), null).single().note)
        val markedHeader = "$header,Note escaped\n"
        assertEquals("'+literal", InventoryImport.read((markedHeader + "Norway,Main,000Ab,3,Normal,'+literal,record-1,0\n").byteInputStream(), null).single().note)
        for ((note, flag) in listOf("'+literal" to "2", "'+literal" to "", "literal" to "1", "'literal" to "1")) {
            assertThrows(IllegalArgumentException::class.java) {
                InventoryImport.read((markedHeader + "Norway,Main,000Ab,3,Normal,$note,record-1,$flag\n").byteInputStream(), null)
            }
        }
    }

    @Test fun previewWarnsBeforeMergedNotesExceedTheDatabaseLimit() {
        val state = snapshot("a".repeat(2500))
        val incoming = listOf(ImportRow(ItemKind.PART, "000Ab", "", 3, StockCondition.NORMAL, "b".repeat(2000)))
        assertNotNull(ImportPreview.calculate(state, incoming, "warehouse", DuplicateMode.ADD_QUANTITY).issue)
        assertNull(ImportPreview.calculate(state, incoming, "warehouse", DuplicateMode.NEW_ENTRY).issue)
        val batch = listOf(incoming.single().copy(note = "a".repeat(2500)), incoming.single())
        assertNotNull(ImportPreview.calculate(state.copy(records = emptyList()), batch, "warehouse", DuplicateMode.ADD_QUANTITY).issue)
    }

    @Test fun contentSniffingHandlesProvidersThatSplitTheUtf8BomAcrossShortReads() {
        val bytes = ("\uFEFF\n" + output { InventoryFiles.writeBackup(snapshot(), it) }.toString(Charsets.UTF_8)).toByteArray()
        val slow = object : InputStream() {
            var index = 0
            override fun read() = if (index == bytes.size) -1 else bytes[index++].toInt() and 255
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (index == bytes.size) return -1
                buffer[offset] = bytes[index++]; return 1
            }
            override fun available() = 0
        }
        assertEquals(snapshot(), (InventoryImport.readFile(slow, "renamed.csv") as InventoryImportContent.Backup).snapshot)
    }

    @Test fun deeplyNestedBackupJsonIsRejectedWithoutOverflowingTheThreadStack() {
        val payload = "{\"records\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}"
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        val envelope = JSONObject().put("format", "fieldstock-backup").put("version", 2).put("payload", payload).put("checksum", checksum).toString()
        assertThrows(IllegalArgumentException::class.java) { InventoryFiles.readBackup(envelope.byteInputStream()) }
        assertThrows(IllegalArgumentException::class.java) { InventoryFiles.readBackup(("{\"extra\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}").byteInputStream()) }
    }

    @Test fun jsonDepthGuardAcceptsBracketsAndEscapesInsideTextAndEnforcesActualNesting() {
        val text = "[}]".repeat(2000) + "\"\\\n"
        val json = JSONObject().put("text", text).toString()
        assertEquals(text, BoundedJson.objectValue(json).getString("text"))
        assertEquals(1, BoundedJson.arrayValue("[".repeat(64) + "0" + "]".repeat(64)).length())
        assertThrows(IllegalArgumentException::class.java) { BoundedJson.arrayValue("[".repeat(65) + "0" + "]".repeat(65)) }
        assertThrows(IllegalArgumentException::class.java) { BoundedJson.objectValue("{'x':'lenient'}") }
    }
}
