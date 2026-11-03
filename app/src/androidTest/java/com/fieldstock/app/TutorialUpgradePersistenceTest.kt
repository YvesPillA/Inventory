package com.fieldstock.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldstock.app.i18n.LanguageSettings
import com.fieldstock.app.i18n.TextKey
import com.fieldstock.app.i18n.tr
import com.fieldstock.app.onboarding.TutorialPreferences
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run after completing TutorialWorkflowTest's English guide and reinstalling the release APK. */
@RunWith(AndroidJUnit4::class)
class TutorialUpgradePersistenceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun completedGuideStaysDismissedAfterApkReplacement() {
        assumeTrue("Requires the retained completed-guide fixture and install -r",
            InstrumentationRegistry.getArguments().getString("tutorialUpgrade") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val language = LanguageSettings.selected(context)
        assertNotNull(language)
        assertTrue(TutorialPreferences.completed(context))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20_000) { compose.onAllNodesWithText(tr(TextKey.ENTER_WAREHOUSE)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("tutorial").assertDoesNotExist()
            assertFalse(TutorialPreferences.pending(context))
            assertTrue(TutorialPreferences.completed(context))
            assertEquals(language, LanguageSettings.selected(context))
        }
    }
}
