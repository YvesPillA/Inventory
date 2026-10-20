package com.fieldstock.app.scan

import com.fieldstock.app.model.IdentifierRules
import org.junit.Assert.*
import org.junit.Test

class OcrCharacterReviewTest {
    @Test fun offersChoicesWithoutChangingCodeCaseOrLeadingZeroes() {
        val code = "00O1Il8Bb"
        assertEquals(code.indices.toList(), OcrCharacterReview.positions(code))
        assertEquals(listOf('0', 'O', 'o'), OcrCharacterReview.options('O'))
        assertEquals(listOf('1', 'I', 'i', 'l'), OcrCharacterReview.options('l'))
        assertEquals(listOf('8', 'B', 'b'), OcrCharacterReview.options('8'))
        assertEquals("00O1Il8Bb", code)
    }

    @Test fun changesExactlyOneExplicitPosition() {
        assertEquals("0001Il8Bb", OcrCharacterReview.replace("00O1Il8Bb", 2, '0'))
        assertEquals("00O1Il8B8", OcrCharacterReview.replace("00O1Il8Bb", 8, '8'))
    }

    @Test fun maximumSnRetainsHyphenAndAllFourteenCharacters() {
        val code = "000000O-I888888"
        val fixed = OcrCharacterReview.replace(code, 6, '0')
        assertEquals("0000000-I888888", fixed)
        assertNull(IdentifierRules.validateSn(fixed))
        assertFalse(7 in OcrCharacterReview.positions(code))
    }

    @Test fun invalidPositionOrUnrelatedReplacementCannotChangeCode() {
        assertThrows(IllegalArgumentException::class.java) { OcrCharacterReview.replace("A0-B8", 2, '0') }
        assertThrows(IllegalArgumentException::class.java) { OcrCharacterReview.replace("A0-B8", 0, '8') }
        assertThrows(IllegalArgumentException::class.java) { OcrCharacterReview.replace("A0-B8", 99, 'I') }
        assertTrue(OcrCharacterReview.positions("ACD-2345679").isEmpty())
    }
}
