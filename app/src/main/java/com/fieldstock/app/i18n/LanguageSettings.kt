package com.fieldstock.app.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import java.util.Locale

object LanguageSettings {
    private const val KEY = "app_language"
    fun selected(context: Context): AppLanguage? = AppLanguage.fromTag(
        context.getSharedPreferences("fieldstock", Context.MODE_PRIVATE).getString(KEY, null)
    )

    fun select(context: Context, language: AppLanguage) {
        check(context.getSharedPreferences("fieldstock", Context.MODE_PRIVATE).edit()
            .putString(KEY, language.tag).commit())
        AppLanguage.current = language
    }

    fun localizedContext(context: Context): Context {
        val language = selected(context) ?: AppLanguage.CHINESE
        AppLanguage.current = language
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language.tag))
        return context.createConfigurationContext(configuration)
    }
}

/** Both inventory and camera activities use the saved language, regardless of device locale. */
open class LocalizedActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(LanguageSettings.localizedContext(newBase))
}
