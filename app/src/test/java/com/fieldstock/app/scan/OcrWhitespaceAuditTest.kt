package com.fieldstock.app.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrWhitespaceAuditTest {
    @Test fun trailingWordIsNotAppendedToACompleteSerial() {
        assertEquals(listOf("AB-001"), OcrCandidates.extract("SN: AB-001 XYZ", IdentifierField.SN).map { it.value })
    }

    @Test fun trailingQuantityIsNotAppendedToACompleteSerial() {
        assertEquals(listOf("AB-001"), OcrCandidates.extract("SN: AB-001 23", IdentifierField.SN).map { it.value })
    }

    @Test fun spacesAroundTheSeparatorStillProduceAReviewedCorrection() {
        val candidate = OcrCandidates.extract("SN: AB － 001", IdentifierField.SN).single()
        assertEquals("AB-001", candidate.value)
        assertEquals("AB － 001", candidate.raw)
    }
}
