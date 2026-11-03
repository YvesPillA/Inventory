package com.fieldstock.app.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fieldstock.app.i18n.AppLanguage
import com.fieldstock.app.i18n.LanguageSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TutorialPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("fieldstock", 0)
    @Before fun clear() { prefs.edit().clear().commit(); AppLanguage.current = AppLanguage.CHINESE }
    @After fun reset() { AppLanguage.current = AppLanguage.CHINESE }

    @Test fun firstLanguageChoicePersistsBothLanguageAndPendingTutorialAcrossLocalizedContexts() {
        assertFalse(TutorialPreferences.pending(context))
        assertNull(LanguageSettings.selected(context))
        LanguageSettings.select(context, AppLanguage.ENGLISH, beginTutorial = true)
        val recreatedContext = LanguageSettings.localizedContext(context)
        assertEquals(AppLanguage.ENGLISH, LanguageSettings.selected(recreatedContext))
        assertTrue(TutorialPreferences.pending(recreatedContext))
    }

    @Test fun finishingOrSkippingDoesNotResetLanguageWarehouseOrOtherPreferences() {
        prefs.edit().putString("warehouse", "existing-warehouse").putBoolean("feedback_sound", true).commit()
        LanguageSettings.select(context, AppLanguage.CHINESE, beginTutorial = true)
        TutorialPreferences.finish(context)
        assertTrue(TutorialPreferences.completed(context))
        assertFalse(TutorialPreferences.pending(LanguageSettings.localizedContext(context)))
        assertEquals(AppLanguage.CHINESE, LanguageSettings.selected(context))
        assertEquals("existing-warehouse", prefs.getString("warehouse", null))
        assertTrue(prefs.getBoolean("feedback_sound", false))
        LanguageSettings.select(context, AppLanguage.ENGLISH)
        assertFalse(TutorialPreferences.pending(context))
    }

    @Test fun existingInstallationsAndLanguageChangesDoNotOptIntoAutomaticTutorial() {
        LanguageSettings.select(context, AppLanguage.ENGLISH)
        assertFalse(TutorialPreferences.pending(context))
        LanguageSettings.select(context, AppLanguage.CHINESE)
        assertFalse(TutorialPreferences.pending(context))
        LanguageSettings.select(context, AppLanguage.ENGLISH, beginTutorial = true)
        LanguageSettings.select(context, AppLanguage.CHINESE)
        assertTrue(TutorialPreferences.pending(context))
    }

    @Test fun completionWinsOverLaterStartupRequestsAndLanguageChoices() {
        LanguageSettings.select(context, AppLanguage.ENGLISH, beginTutorial = true)
        TutorialPreferences.finish(context)
        // A later app build must not rearm a guide the user has already dismissed.
        LanguageSettings.select(context, AppLanguage.CHINESE, beginTutorial = true)
        assertTrue(TutorialPreferences.completed(LanguageSettings.localizedContext(context)))
        assertFalse(TutorialPreferences.pending(context))
    }
}
