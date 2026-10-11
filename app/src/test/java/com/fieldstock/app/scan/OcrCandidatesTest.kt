package com.fieldstock.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCandidatesTest {
    @Test fun preservesLeadingZeroesAndCase() {
        assertEquals("000aB012", OcrCandidates.extract("BOM: 000aB012", IdentifierField.BOM).single().value)
    }

    @Test fun selectsOnlyRequestedLabelFromMixedLine() {
        val text = "BOM: 00012 SN: AB01-0009"
        assertEquals(listOf("00012"), OcrCandidates.extract(text, IdentifierField.BOM).map { it.value })
        assertEquals(listOf("AB01-0009"), OcrCandidates.extract(text, IdentifierField.SN).map { it.value })
    }

    @Test fun acceptsFourteenSnCharactersPlusHyphen() {
        assertEquals("1234567-ABCDEFG", OcrCandidates.extract("SN: 1234567-ABCDEFG", IdentifierField.SN).single().value)
    }

    @Test fun neverTruncatesLongBomOrSn() {
        assertTrue(OcrCandidates.extract("123456789012345", IdentifierField.BOM).isEmpty())
        assertTrue(OcrCandidates.extract("12345678-ABCDEFG", IdentifierField.SN).isEmpty())
    }

    @Test fun doesNotJoinSeparateLinesIntoSn() {
        assertTrue(OcrCandidates.extract("AB12\n-\nCD34", IdentifierField.SN).isEmpty())
    }

    @Test fun retainsOriginalTextWhenCorrectingHyphenAndSpaces() {
        val candidate = OcrCandidates.extract("SN: AB01 － 0009", IdentifierField.SN).single()
        assertEquals("AB01 － 0009", candidate.raw)
        assertEquals("AB01-0009", candidate.value)
        assertEquals(2, candidate.corrections.size)
    }

    @Test fun doesNotReplaceConfusableCharacters() {
        assertEquals("O0I1-lL01", OcrCandidates.extract("O0I1-lL01", IdentifierField.SN).single().value)
    }

    @Test fun emitsMultipleCandidatesWithoutMergingSeparateCompleteCodes() {
        assertEquals(listOf("A123", "B456"), OcrCandidates.extract("BOM: A123 B456", IdentifierField.BOM).map { it.value })
    }

    @Test fun numericGroupingIsAnExplicitCorrection() {
        val candidate = OcrCandidates.extract("0012 3456", IdentifierField.BOM).single()
        assertEquals("00123456", candidate.value)
        assertFalse(candidate.corrections.isEmpty())
    }

    @Test fun doesNotOfferOrdinaryWordsFromProse() {
        assertTrue(OcrCandidates.extract("Please check the label", IdentifierField.BOM).isEmpty())
    }

    @Test fun neverSplitsMalformedSnIntoBomFragments() {
        assertTrue(OcrCandidates.extract("AB12-CD34", IdentifierField.BOM).isEmpty())
        assertTrue(OcrCandidates.extract("AB12-CD34-EF56", IdentifierField.SN).isEmpty())
    }

    @Test fun deduplicatesCandidates() {
        assertEquals(1, OcrCandidates.extract("SN: AB01-0009\nAB01-0009", IdentifierField.SN).size)
    }

    @Test fun ignoresOtherFieldWithoutInventingATarget() {
        assertTrue(OcrCandidates.extract("BOM: 00012", IdentifierField.SN).isEmpty())
        assertTrue(OcrCandidates.extract("SN: AB01-0009", IdentifierField.BOM).isEmpty())
    }

    @Test fun doesNotSilentlySliceMalformedTokens() {
        assertTrue(OcrCandidates.extract("BOM: AB_001", IdentifierField.BOM).isEmpty())
        assertTrue(OcrCandidates.extract("BOM: AB001/CD002", IdentifierField.BOM).isEmpty())
        assertTrue(OcrCandidates.extract("SN: AB001--CD002", IdentifierField.SN).isEmpty())
    }
}
