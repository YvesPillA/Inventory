package com.fieldstock.app.model

import com.fieldstock.app.i18n.TextKey
import com.fieldstock.app.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Limits untrusted JSON before Android's recursive parser can exhaust the thread stack. */
object BoundedJson {
    const val MAX_DEPTH = 64

    fun objectValue(text: String): JSONObject = value(text).let {
        require(it is JSONObject) { tr(TextKey.JSON_MUST_CONTAIN_A_SINGLE_OBJECT) }
        it
    }

    fun arrayValue(text: String): JSONArray = value(text).let {
        require(it is JSONArray) { tr(TextKey.JSON_MUST_CONTAIN_A_SINGLE_OBJECT) }
        it
    }

    private fun value(text: String): Any {
        checkDepth(text)
        return try {
            val tokenizer = JSONTokener(text)
            val parsed = tokenizer.nextValue()
            require(tokenizer.nextClean() == '\u0000') { tr(TextKey.JSON_MUST_CONTAIN_A_SINGLE_OBJECT) }
            parsed
        } catch (failure: Exception) {
            if (failure is IllegalArgumentException) throw failure
            throw IllegalArgumentException(tr(TextKey.JSON_MUST_CONTAIN_A_SINGLE_OBJECT), failure)
        }
    }

    fun checkDepth(text: String) {
        val brackets = java.util.ArrayDeque<Char>()
        var quoted = false
        var escaped = false
        var unicode = 0
        var literal = false
        fun valid(value: Boolean) { require(value) { tr(TextKey.JSON_MUST_CONTAIN_A_SINGLE_OBJECT) } }
        for (character in text) {
            if (quoted) {
                when {
                    unicode > 0 -> { valid(character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'); unicode-- }
                    escaped -> {
                        valid(character in "\"\\/bfnrtu")
                        if (character == 'u') unicode = 4
                        escaped = false
                    }
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                    else -> valid(character >= ' ')
                }
            } else when (character) {
                '"' -> { valid(!literal); quoted = true }
                '{', '[' -> {
                    valid(!literal && brackets.size < MAX_DEPTH)
                    brackets.addLast(character)
                }
                '}', ']' -> {
                    val expected = if (character == '}') '{' else '['
                    valid(brackets.pollLast() == expected)
                    literal = false
                }
                ':', ',', ' ', '\t', '\r', '\n' -> literal = false
                else -> {
                    // Backups use standard JSON. Reject extensions that could hide brackets from
                    // this preflight while Android's lenient tokenizer interprets them differently.
                    valid(character in "-+.eE0123456789truefalsn")
                    literal = true
                }
            }
        }
        valid(!quoted && !escaped && unicode == 0 && brackets.isEmpty())
    }
}
