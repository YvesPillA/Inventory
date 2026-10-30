package com.fieldstock.app.diagnostics

import java.security.MessageDigest

/** Partial taps and the entered key are never persisted or logged. */
class DeveloperGate(private val expectedHash: String) {
    private var taps = 0
    fun tap(): Boolean { taps++; return (taps == 6).also { if (it) taps = 0 } }
    fun accepts(value: String): Boolean {
        if(!expectedHash.matches(Regex("[a-f0-9]{64}"))) return false
        val expected = expectedHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    }
}
