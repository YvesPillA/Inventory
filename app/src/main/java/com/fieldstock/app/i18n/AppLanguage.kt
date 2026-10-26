package com.fieldstock.app.i18n

/** Storage values and user-entered text never pass through the text catalog. */
enum class AppLanguage(val tag: String) {
    CHINESE("zh"), ENGLISH("en");

    fun text(key: TextKey, vararg arguments: Any?): String {
        val template = if (this == ENGLISH) key.english else key.chinese
        return PLACEHOLDER.replace(template) { match ->
            arguments[match.groupValues[1].toInt()].toString()
        }
    }

    fun condition(value: String): String = if (this == CHINESE) value else when (value) {
        StockCondition.NORMAL -> "Normal"
        StockCondition.DAMAGED -> "Damaged"
        StockCondition.PENDING -> "Pending verification"
        else -> value
    }

    companion object {
        @Volatile var current: AppLanguage = CHINESE
        private val PLACEHOLDER = Regex("\\{(\\d+)\\}")
        fun fromTag(tag: String?): AppLanguage? = entries.find { it.tag == tag }
    }
}

/** Existing database/JSON values are a compatibility contract, independent of UI language. */
object StockCondition {
    const val NORMAL = "正常"
    const val DAMAGED = "损坏"
    const val PENDING = "待核查"

    fun parse(value: String): String? = when (value.lowercase(java.util.Locale.ROOT)) {
        NORMAL, "normal" -> NORMAL
        DAMAGED, "damaged" -> DAMAGED
        PENDING, "pending verification", "pending" -> PENDING
        else -> null
    }
}

fun tr(key: TextKey, vararg arguments: Any?): String = AppLanguage.current.text(key, *arguments)
fun conditionLabel(value: String): String = AppLanguage.current.condition(value)
