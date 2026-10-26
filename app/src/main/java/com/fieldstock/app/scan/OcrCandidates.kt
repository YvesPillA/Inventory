package com.fieldstock.app.scan

import com.fieldstock.app.i18n.*

/** The field requested by the inventory form. OCR never fills both fields implicitly. */
enum class IdentifierField { BOM, SN }

data class OcrCandidate(
    val raw: String,
    val value: String,
    val corrections: List<String> = emptyList(),
)

/**
 * Conservative, Android-free candidate extraction. Every candidate retains its source text.
 * Nothing is truncated, case and leading zeroes are preserved, and separate lines are never joined.
 */
object OcrCandidates {
    private val fieldLabel = Regex(
        "(?i)(?<![A-Za-z0-9])(?:BOM(?:\\s*码)?|S/?N(?:\\s*号)?|SERIAL(?:\\s+(?:NO\\.?|NUMBER))?)(?=\\s*[:：=]|\\s+|$)\\s*[:：=]?\\s*",
    )
    // Keep malformed punctuation inside a token so AB_001 is rejected, never sliced into AB / 001.
    private val token = Regex("[^\\s,;，；()\\[\\]{}]+(?:\\s*[-－–—−]\\s*[^\\s,;，；()\\[\\]{}]+)*")
    private val reservedWords = setOf("BOM", "SN", "S", "N", "SERIAL", "NO", "NUMBER", "MODEL", "QTY")

    fun extract(rawText: String, field: IdentifierField): List<OcrCandidate> {
        val result = mutableListOf<OcrCandidate>()
        rawText.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val labels = fieldLabel.findAll(line).toList()
            if (labels.isEmpty()) {
                addFromSegment(line, field, explicitlyLabelled = false, result)
            } else {
                labels.forEachIndexed { index, label ->
                    val labelledField = if (label.value.trimStart().startsWith("BOM", true)) {
                        IdentifierField.BOM
                    } else {
                        IdentifierField.SN
                    }
                    if (labelledField == field) {
                        val end = labels.getOrNull(index + 1)?.range?.first ?: line.length
                        val segment = line.substring(label.range.last + 1, end).trim()
                        addFromSegment(segment, field, explicitlyLabelled = true, result)
                    }
                }
            }
        }
        return result.distinctBy(OcrCandidate::value)
    }

    private fun addFromSegment(
        source: String,
        field: IdentifierField,
        explicitlyLabelled: Boolean,
        result: MutableList<OcrCandidate>,
    ) {
        if (source.isBlank()) return
        val hyphensNormalized = source.map { if (it in "－–—−") '-' else it }.joinToString("")
        val compact = hyphensNormalized.filterNot(Char::isWhitespace)
        val hasSpaces = hyphensNormalized.any(Char::isWhitespace)
        if (isValid(compact, field) && (!hasSpaces || mayJoinSpaces(hyphensNormalized, field))) {
            val corrections = buildList {
                if (source != hyphensNormalized) add(tr(TextKey.HYPHEN_NORMALIZED_TO))
                if (hasSpaces) add(tr(TextKey.SPACES_WITHIN_THE_SAME_LINE_WERE_REMOVED_PLEASE_CHECK))
            }
            result += OcrCandidate(source, compact, corrections)
            return
        }
        token.findAll(source).forEach { match ->
            val raw = match.value
            val normalizedHyphens = raw.map { if (it in "－–—−") '-' else it }.joinToString("")
            val value = normalizedHyphens.filterNot(Char::isWhitespace)
            // Unlabelled prose must not generate one suggestion for every ordinary word.
            if (isValid(value, field) && (explicitlyLabelled || value.any(Char::isDigit))) {
                val corrections = buildList {
                    if (raw != normalizedHyphens) add(tr(TextKey.HYPHEN_NORMALIZED_TO))
                    if (normalizedHyphens.any(Char::isWhitespace)) add(tr(TextKey.SPACES_AROUND_THE_HYPHEN_WERE_REMOVED_PLEASE_CHECK))
                }
                result += OcrCandidate(raw, value, corrections)
            }
        }
    }

    private fun isValid(value: String, field: IdentifierField): Boolean {
        if (value.uppercase() in reservedWords) return false
        return when (field) {
            IdentifierField.BOM -> value.length in 1..14 && value.all(::isAsciiAlphanumeric)
            IdentifierField.SN -> {
                val parts = value.split('-')
                parts.size == 2 && parts.all { it.isNotEmpty() && it.all(::isAsciiAlphanumeric) } &&
                    parts.sumOf(String::length) <= 14
            }
        }
    }

    private fun mayJoinSpaces(value: String, field: IdentifierField): Boolean {
        if (field == IdentifierField.SN) return value.count { it == '-' } == 1
        val parts = value.trim().split(Regex("\\s+"))
        return parts.all { part -> part.all { it in '0'..'9' } } ||
            (parts.first().all { it in 'A'..'Z' || it in 'a'..'z' } &&
                parts.drop(1).isNotEmpty() && parts.drop(1).all { part -> part.all { it in '0'..'9' } }) ||
            parts.all { it.length == 1 }
    }

    private fun isAsciiAlphanumeric(char: Char): Boolean =
        char in '0'..'9' || char in 'A'..'Z' || char in 'a'..'z'
}
