package com.fieldstock.app.i18n

import com.fieldstock.app.importdata.InventoryImport
import com.fieldstock.app.model.ItemKind
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class BilingualImportTest {
    @After fun resetLanguage() { AppLanguage.current = AppLanguage.CHINESE }

    private fun header(language: AppLanguage, withPn: Boolean = false): String = ByteArrayOutputStream().also {
        InventoryImport.writeTemplate(it, language)
    }.toString("UTF-8").let { if (withPn) it else it.replace(",PN\r\n", "\r\n") }

    @Test fun eitherTemplateAndEitherValueLanguageWorkUnderEitherUiLanguage() {
        for (ui in AppLanguage.entries) for (template in AppLanguage.entries) for (values in AppLanguage.entries) {
            AppLanguage.current = ui
            val types = if (values == AppLanguage.ENGLISH) listOf("Part", "Machine") else listOf("配件", "整机")
            val csv = header(template) +
                "${types[0]},000Ab,,3,${values.condition(StockCondition.NORMAL)},仓库 English\r\n" +
                "${types[1]},00Bom,000a-0B,1,${values.condition(StockCondition.DAMAGED)},\r\n" +
                "${types[0]},,,4,${values.condition(StockCondition.PENDING)},missing label\r\n"
            val rows = InventoryImport.read(csv.byteInputStream(), "mixed.csv")
            assertEquals(listOf(StockCondition.NORMAL, StockCondition.DAMAGED, StockCondition.PENDING), rows.map { it.condition })
            assertEquals("000Ab", rows[0].bom)
            assertEquals("000a-0B", rows[1].sn)
            assertEquals("仓库 English", rows[0].note)
            assertEquals(ItemKind.MACHINE, rows[1].kind)
            assertEquals(4, rows[2].quantity)
        }
    }

    @Test fun equivalentChineseEnglishAndMixedFilesHaveTheSameDuplicateFingerprint() {
        val zh = "类型,BOM,SN,数量,状态,备注\n配件,000a,,2,正常,note\n整机,00b,00C-00d,1,待核查,\"check, this\"\n"
        val en = "Type,BOM,SN,Quantity,Condition,Note\r\nPART,000a,,2,NORMAL,note\r\nMachine,00b,00C-00d,1,Pending verification,\"check, this\"\r\n"
        val mixed = "类型,BOM,sn,quantity,状态,Note\nParts,000a,,2,正常,note\n整机,00b,00C-00d,1,pending,\"check, this\"\n"
        val parsed = listOf(zh, en, mixed).map { InventoryImport.read(it.byteInputStream(), null) }
        assertEquals(parsed[0], parsed[1])
        assertEquals(parsed[1], parsed[2])
        assertEquals(1, parsed.map(InventoryImport::fingerprint).distinct().size)
    }

    @Test fun englishErrorsKeepPhysicalLineNumbersAndDoNotRelaxValidation() {
        AppLanguage.current = AppLanguage.ENGLISH
        val csv = header(AppLanguage.CHINESE) + "Parts,001a,,1,Normal,\"first\nsecond\"\nMachine,001a,broken,1,Normal,"
        val failure = assertThrows(IllegalArgumentException::class.java) { InventoryImport.read(csv.byteInputStream(), null) }
        assertTrue(failure.message, failure.message.orEmpty().startsWith("Line 4:"))
        assertTrue(failure.message.orEmpty().contains("SN must contain two groups"))
        for (invalid in listOf("Part,001a,00-01,1,Normal,", "Machine,001a,00-01,2,Normal,", "Part,001a,,0,Normal,", "Part,,,1,Pending verification,")) {
            assertThrows(IllegalArgumentException::class.java) { InventoryImport.read((header(AppLanguage.ENGLISH) + invalid).byteInputStream(), null) }
        }
    }

    @Test fun changingHeaderLanguageDoesNotAllowRepeatedHeadersOrWrongColumnOrder() {
        for (ui in AppLanguage.entries) {
            AppLanguage.current = ui
            assertThrows(IllegalArgumentException::class.java) {
                InventoryImport.read((header(AppLanguage.CHINESE) + header(AppLanguage.ENGLISH).removePrefix("\uFEFF")).byteInputStream(), null)
            }
            assertThrows(IllegalArgumentException::class.java) {
                InventoryImport.read("BOM,Type,SN,Quantity,Condition,Note\n".byteInputStream(), null)
            }
        }
    }

    @Test fun downloadedTemplatesAreHeaderOnlyAndAlwaysReadable() {
        assertEquals("\uFEFFType,BOM,SN,Quantity,Condition,Note,PN\r\n", header(AppLanguage.ENGLISH, true))
        assertEquals("\uFEFF类型,BOM,SN,数量,状态,备注,PN\r\n", header(AppLanguage.CHINESE, true))
        for (language in AppLanguage.entries) assertTrue(InventoryImport.read(header(language, true).byteInputStream(), null).isEmpty())
    }

    @Test fun formattingDoesNotInterpretOrTranslateUserArguments() {
        val note = "{1} 中文 \\n %s 正常"
        assertEquals("Original: $note", AppLanguage.ENGLISH.text(TextKey.ORIGINAL, note))
        for (key in TextKey.entries) {
            val placeholders = Regex("\\{(\\d+)\\}")
            assertEquals(key.name, placeholders.findAll(key.chinese).map { it.value }.toSet(), placeholders.findAll(key.english).map { it.value }.toSet())
            assertTrue(key.name, key.english.isNotBlank())
        }
    }
}
