package com.fieldstock.app.importdata

import com.fieldstock.app.model.ItemKind
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream

class InventoryImportTest {
    private val header = "类型,BOM,SN,数量,状态,备注\r\n"
    private fun parse(csv: String, fileName: String? = "import.csv") = InventoryImport.read(csv.byteInputStream(), fileName)
    private fun rejected(csv: String, expectedLine: Int = 2): String {
        val failure = assertThrows(IllegalArgumentException::class.java) { parse(csv) }
        assertTrue(failure.message, failure.message.orEmpty().contains("第 $expectedLine 行"))
        return failure.message.orEmpty()
    }

    @Test fun templateIsUtf8BomAndHeaderOnlyWithNoExampleInventory() {
        val output = ByteArrayOutputStream()
        InventoryImport.writeTemplate(output)
        assertEquals("\uFEFF$header", output.toString(Charsets.UTF_8.name()))
        assertEquals(emptyList<ImportRow>(), InventoryImport.read(output.toByteArray().inputStream(), "template.csv"))
    }

    @Test fun mixedRecordsPreserveLeadingZerosCaseAndMaximumIdentifiers() {
        val rows = parse("\uFEFF" + header + "配件,0000123a,,12,正常,\r\n整机,ABCDEFGHIJKLMN,ABCDEFG-1234567,1,损坏,原样\n")
        assertEquals(2, rows.size)
        assertEquals(ImportRow(ItemKind.PART, "0000123a", "", 12, "正常", ""), rows[0])
        assertEquals("ABCDEFG-1234567", rows[1].sn)
        assertEquals("ABCDEFGHIJKLMN", rows[1].bom)
        assertEquals(1, rows[1].quantity)
    }

    @Test fun quotedCommasEscapedQuotesAndMultilineNotesRoundTripAsPlainText() {
        val note = "=SUM(1,2)\r\n第二行 \"引号\"\n第三行"
        val csv = header + "整机,000A,001-002,1,正常,\"=SUM(1,2)\r\n第二行 \"\"引号\"\"\n第三行\""
        assertEquals(note, parse(csv).single().note)
        assertEquals("001-002", parse(csv, "renamed.xlsx").single().sn) // Contents determine the format.
    }

    @Test fun blankRowsAreIgnoredAndPendingRecordsMayOmitIdentifiers() {
        val rows = parse("\r\n" + header + "\n,,,,,\r\n配件,,,3,待核查,标签丢失\n整机,,0001-02,1,待核查,\n")
        assertEquals(2, rows.size)
        assertEquals("标签丢失", rows[0].note)
        assertEquals("", rows[0].bom)
        assertEquals("0001-02", rows[1].sn)
        rejected(header + "配件,,,1,待核查,\n")
    }

    @Test fun malformedQuotesAndPhysicalLineAfterMultilineFieldAreReported() {
        listOf(
            "配件,001A,,1,正常,\"未闭合",
            "配件,001A,,1,正常,坏\"引号",
            "配件,001A,,1,正常,\"结束\"残余"
        ).forEach { rejected(header + it) }
        rejected(header + "配件,001A,,1,正常,\"第一行\n第二行\"\n整机,001A,坏号,1,正常,", 4)
    }

    @Test fun wrongOrRepeatedHeadersAndWrongColumnCountsAreRejected() {
        rejected("BOM,类型,SN,数量,状态,备注\n", 1)
        rejected(header + header)
        rejected(header + "配件,001A,,1,正常\n")
        rejected(header + "配件,001A,,1,正常,备注,多余\n")
        rejected("\r\n", 1)
    }

    @Test fun businessValidationRejectsInvalidIdentifiersKindsStatusesAndQuantities() {
        listOf(
            "零件,001A,,1,正常,", "配件,001A,,1,可用,",
            "配件,ABCDEFGHIJKLMNO,,1,正常,", "配件, 001A,,1,正常,",
            "配件,=1+1,,1,正常,", "配件,001A,001-002,1,正常,",
            "整机,001A,ABCDEFGH-1234567,1,正常,", "整机,001A,001--002,1,正常,",
            "整机,001A,-002,1,正常,", "整机,001A,001-002,2,正常,",
            "配件,001A,,0,正常,", "配件,001A,,-1,正常,",
            "配件,001A,,1.0,正常,", "配件,001A,,1e2,正常,",
            "配件,001A,,1000000001,正常,", "配件,001A,,9999999999999999,正常,"
        ).forEach { rejected(header + it) }
    }

    @Test fun duplicateSnRowsRemainForRepositoryToHandleInOneBatch() {
        val rows = parse(header + "整机,001A,001-002,1,正常,第一条\n整机,002B,001-002,1,损坏,第二条\n")
        assertEquals(2, rows.size)
        assertEquals(rows[0].sn, rows[1].sn)
        assertNotEquals(rows[0].bom, rows[1].bom)
    }

    @Test fun invalidUtf8XlsxAndOversizedFilesAreRejected() {
        val badUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        assertThrows(IllegalArgumentException::class.java) { InventoryImport.read(badUtf8.inputStream(), "bad.csv") }
        assertThrows(IllegalArgumentException::class.java) { InventoryImport.read(byteArrayOf(0x50, 0x4b, 0x03, 0x04).inputStream(), "book.xlsx") }
        val stream = object : InputStream() {
            var remaining = InventoryImport.MAX_FILE_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 'a'.code else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(remaining, length)
                buffer.fill('a'.code.toByte(), offset, offset + count)
                remaining -= count
                return count
            }
        }
        assertTrue(assertThrows(IllegalArgumentException::class.java) { InventoryImport.read(stream, null) }.message.orEmpty().contains("8 MiB"))
    }

    @Test fun maximumRecordCountAndNoteLengthAreEnforced() {
        val row = "配件,001A,,1,正常,\n"
        assertEquals(InventoryImport.MAX_ROWS, parse(header + row.repeat(InventoryImport.MAX_ROWS)).size)
        rejected(header + row.repeat(InventoryImport.MAX_ROWS + 1), InventoryImport.MAX_ROWS + 2)
        rejected(header + "配件,001A,,1,正常," + "文".repeat(4001))
    }
}
