package com.fieldstock.app.i18n

import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BilingualExportTest {
    @After fun resetLanguage() { AppLanguage.current = AppLanguage.CHINESE }
    private val record = StockRecord("r1", "t1", "w1", ItemKind.PART, "000aB", "", 7,
        StockCondition.DAMAGED, "中文 Normal {0} \"note\"", "", "manual", "manual", "", "", 1000, 2000, false)
    private val snapshot = InventorySnapshot(
        tasks = listOf(InventoryTask("t1", "兼容任务", 1000)),
        countries = listOf(Country("c1", "中国 Norway")),
        warehouses = listOf(Warehouse("w1", "c1", "正常 Warehouse")),
        records = listOf(record, record.copy(id = "r2", kind = ItemKind.MACHINE, sn = "000a-0B", quantity = 1, condition = StockCondition.PENDING)),
        changes = listOf(ChangeLog("a1", "r1", "UPDATE", "before 中文", "after English", 2000))
    )

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes().toString(Charsets.UTF_8)) }
        }
    }
    private fun cells(xml: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val cells = doc.getElementsByTagName("t")
        return (0 until cells.length).map { cells.item(it).textContent }
    }

    @Test fun xlsxUsesRequestedLanguageForEverySheetHeaderConditionAndUnit() {
        for (language in AppLanguage.entries) {
            AppLanguage.current = if (language == AppLanguage.ENGLISH) AppLanguage.CHINESE else AppLanguage.ENGLISH
            val output = ByteArrayOutputStream()
            InventoryFiles.writeXlsx(snapshot, output, null, "w1", language)
            val files = unzip(output.toByteArray())
            val detail = cells(files.getValue("xl/worksheets/sheet2.xml"))
            val summary = cells(files.getValue("xl/worksheets/sheet3.xml"))
            val pending = cells(files.getValue("xl/worksheets/sheet5.xml"))
            assertEquals(language.text(TextKey.COUNTRY), detail[0])
            assertTrue(detail.contains(language.condition(StockCondition.DAMAGED)))
            assertTrue(summary.contains(language.condition(StockCondition.DAMAGED)))
            assertTrue(pending.contains(language.condition(StockCondition.PENDING)))
            assertTrue(detail.contains(language.text(TextKey.PCS)))
            assertTrue(detail.contains("000aB"))
            assertTrue(detail.contains(record.note))
            assertTrue(detail.contains("正常 Warehouse"))
            assertTrue(pending.contains("000a-0B"))
            assertTrue(files.getValue("xl/workbook.xml").contains(language.text(TextKey.PARTS_SUMMARY)))
            assertTrue(cells(files.getValue("xl/worksheets/sheet1.xml")).contains(language.text(TextKey.COUNTING_RULES)))
        }
    }

    @Test fun csvNamesHeadersValuesAndInstructionsFollowRequestedLanguageWithoutTouchingUserText() {
        for (language in AppLanguage.entries) {
            AppLanguage.current = if (language == AppLanguage.ENGLISH) AppLanguage.CHINESE else AppLanguage.ENGLISH
            val output = ByteArrayOutputStream()
            InventoryFiles.writeCsvZip(snapshot, output, null, "w1", language)
            val files = unzip(output.toByteArray())
            val parts = files.getValue(language.text(TextKey.PARTS_DETAIL_CSV))
            val pending = files.getValue(language.text(TextKey.PENDING_VERIFICATION_CSV))
            assertEquals(3, files.keys.count { it.endsWith(".csv") })
            assertTrue(parts.startsWith("\uFEFF\"${language.text(TextKey.COUNTRY)}\""))
            assertTrue(parts.contains("\"${language.condition(StockCondition.DAMAGED)}\""))
            assertTrue(parts.contains("\"000aB\""))
            assertTrue(parts.contains("中文 Normal {0} \"\"note\"\""))
            assertTrue(pending.contains("\"000a-0B\""))
            assertTrue(pending.contains("\"${language.condition(StockCondition.PENDING)}\""))
            assertTrue(files.getValue(language.text(TextKey.CSV_INSTRUCTIONS_TXT)).contains("UTF-8"))
        }
    }

    @Test fun jsonBackupIsIdenticalAcrossLanguagesAndRestoresOriginalValues() {
        var previous: ByteArray? = null
        for (language in AppLanguage.entries) {
            AppLanguage.current = language
            val output = ByteArrayOutputStream()
            InventoryFiles.writeBackup(snapshot, output)
            val bytes = output.toByteArray()
            previous?.let { assertArrayEquals(it, bytes) }
            assertEquals(snapshot, InventoryFiles.readBackup(bytes.inputStream()))
            previous = bytes
        }
    }
}
