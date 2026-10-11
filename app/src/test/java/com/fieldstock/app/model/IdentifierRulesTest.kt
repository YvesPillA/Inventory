package com.fieldstock.app.model

import org.junit.Assert.*
import org.junit.Test

class IdentifierRulesTest {
    @Test fun snExcludesOnlyOneHyphenFromFourteenCharacterLimit() {
        assertNull(IdentifierRules.validateSn("ABCDEFG-1234567"))
        assertNull(IdentifierRules.validateSn("A-0"))
        assertNotNull(IdentifierRules.validateSn("ABCDEFGH-1234567"))
        listOf("-ABC", "ABC-", "ABC--123", "AB CD-123", "ABC–123", "ABC-中文", "ABC-123\n").forEach {
            assertNotNull(it, IdentifierRules.validateSn(it))
        }
    }
    @Test fun bomRetainsTextRulesAndLeadingZeros() {
        assertNull(IdentifierRules.validateBom("000123A"))
        assertNull(IdentifierRules.validateBom("ABCDEFGHIJKLMN"))
        listOf("", "ABCDEFGHIJKLMNO", "ABC-123", "A B", "123\n", "１２３").forEach { assertNotNull(it, IdentifierRules.validateBom(it)) }
    }
}
