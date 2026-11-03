package com.fieldstock.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.i18n.*
import com.fieldstock.app.model.*
import com.fieldstock.app.onboarding.TutorialPreferences
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TutorialWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val originalFontScale = Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)

    @Before fun clean() {
        context.databaseList().forEach(context::deleteDatabase)
        context.getSharedPreferences("fieldstock", 0).edit().clear().putBoolean("automatic_backup", false).commit()
        AppLanguage.current = AppLanguage.CHINESE
    }

    @After fun close() {
        shell("settings put system font_scale $originalFontScale")
        scenario?.close()
        TutorialPreferences.finish(context)
        LanguageSettings.select(context, AppLanguage.CHINESE)
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun waitFor(tag: String) = compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun stage(number: Int) {
        waitFor("tutorial-step")
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("tutorial-step").assertTextEquals(tr(TextKey.TUTORIAL_STEP, number, 4)); true }.getOrDefault(false)
        }
        compose.waitForIdle()
    }
    private fun next(number: Int) { compose.onNodeWithTag("tutorial-next").assertIsDisplayed().performClick(); stage(number) }
    private fun fresh(language: AppLanguage) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Choose language / 选择语言").assertIsDisplayed()
        compose.onNodeWithText(if (language == AppLanguage.ENGLISH) "English" else "简体中文").performClick()
        stage(1)
        assertEquals(language, LanguageSettings.selected(context))
    }
    private fun chooser() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(tr(TextKey.ENTER_WAREHOUSE)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tutorial").assertDoesNotExist()
    }
    private fun capture(name: String) {
        compose.waitForIdle(); instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "tutorial-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun existing(language: AppLanguage): InventorySnapshot {
        InventoryRepository(context).use { repo ->
            val warehouse = repo.addWarehouse(repo.addCountry("Norway").id, "Main")
            repo.save(RecordInput(repo.snapshot().tasks.first().id, warehouse.id, ItemKind.PART, "000Ab", "", 5, StockCondition.NORMAL, "saved note"))
            context.getSharedPreferences("fieldstock", 0).edit().putString("warehouse", warehouse.id).commit()
        }
        LanguageSettings.select(context, language)
        val before = InventoryRepository(context).use { it.snapshot() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        chooser()
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithText(tr(TextKey.ENTER_WAREHOUSE)).assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithText(tr(TextKey.ENTER_WAREHOUSE)).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("count-bom").fetchSemanticsNodes().isNotEmpty() }
        return before
    }
    private fun settings() {
        // The bottom navigation reappears after the platform keyboard finishes closing.
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithText(tr(TextKey.SETTINGS)).assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.onNodeWithText(tr(TextKey.SETTINGS)).performClick()
        compose.onNodeWithText(tr(TextKey.TUTORIAL_REPLAY)).performScrollTo().assertIsDisplayed()
    }

    @Test fun firstEnglishGuideSupportsSwipeBackRotationAndCompletionWithoutInventingStock() {
        fresh(AppLanguage.ENGLISH)
        capture("en-1")
        compose.onNodeWithTag("tutorial-pager").performTouchInput { swipeLeft() }
        stage(2)
        compose.onNodeWithTag("tutorial-back").performClick(); stage(1)
        next(2)
        scenario!!.recreate(); stage(2)
        capture("en-2")
        next(3); capture("en-3")
        next(4); capture("en-4")
        compose.onNodeWithTag("tutorial-next").assertTextEquals("Get started").performClick()
        chooser()
        assertFalse(TutorialPreferences.pending(context))
        scenario!!.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
        chooser()
        InventoryRepository(context).use { repo ->
            val snapshot = repo.snapshot()
            assertTrue(snapshot.countries.isEmpty()); assertTrue(snapshot.warehouses.isEmpty())
            assertTrue(snapshot.records.isEmpty()); assertTrue(snapshot.changes.isEmpty())
        }
    }

    @Test fun chineseGuideCanBeSkippedAndDoesNotReturnOnNextLaunch() {
        fresh(AppLanguage.CHINESE); capture("zh-1")
        compose.onNodeWithTag("tutorial-skip").assertTextEquals("跳过").performClick()
        chooser()
        scenario!!.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
        chooser()
        assertEquals(AppLanguage.CHINESE, LanguageSettings.selected(context))
        assertFalse(TutorialPreferences.pending(context))
    }

    @Test fun unfinishedGuideIsStillAvailableAfterClosingTheApp() {
        fresh(AppLanguage.CHINESE); next(2)
        scenario!!.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
        stage(1)
        assertTrue(TutorialPreferences.pending(context))
        compose.onNodeWithTag("tutorial-skip").performClick(); chooser()
    }

    @Test fun replaySurvivesRotationAndKeepsUnsavedInputAndExistingInventory() {
        val before = existing(AppLanguage.CHINESE)
        compose.onNodeWithTag("count-bom").performTextReplacement("000Z9")
        compose.onNodeWithText("数量（件）").performTextReplacement("3")
        compose.onNodeWithText("数量（件）").performImeAction()
        settings()
        compose.onNodeWithText(tr(TextKey.TUTORIAL_REPLAY)).performClick(); stage(1)
        next(2); scenario!!.recreate(); stage(2); capture("zh-2")
        next(3); capture("zh-3")
        next(4); capture("zh-4")
        compose.onNodeWithTag("tutorial-skip").performClick()
        compose.onNodeWithText(tr(TextKey.TUTORIAL_REPLAY)).assertIsDisplayed()
        compose.onNodeWithText(tr(TextKey.COUNT)).performClick()
        compose.onNodeWithTag("count-bom").assertTextContains("000Z9")
        compose.onNodeWithText("数量（件）").assertTextContains("3")
        InventoryRepository(context).use { assertEquals(before, it.snapshot()) }
        assertFalse(TutorialPreferences.pending(context))
    }

    @Test fun changingLanguageUpdatesReplayWithoutStartingAutomaticTutorial() {
        val before = existing(AppLanguage.ENGLISH)
        settings()
        compose.onNodeWithText("View quick start").performClick(); stage(1)
        compose.onNodeWithTag("tutorial-skip").performClick()
        compose.onNodeWithText("Language / 语言 · English").performClick()
        compose.onNodeWithText("简体中文").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("查看新手教程").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tutorial").assertDoesNotExist()
        compose.onNodeWithText("查看新手教程").performClick(); stage(1)
        compose.onNodeWithText("先选一个仓库").assertIsDisplayed()
        compose.onNodeWithTag("tutorial-skip").performClick()
        InventoryRepository(context).use { assertEquals(before, it.snapshot()) }
    }

    @Test fun landscapeWithLargeTextKeepsNavigationAndScrollableInstructionsReachable() {
        fresh(AppLanguage.ENGLISH)
        try {
            shell("settings put system font_scale 1.5")
            scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(20_000) {
                val bounds = compose.onNodeWithTag("tutorial").fetchSemanticsNode().boundsInRoot
                bounds.width > bounds.height
            }
            stage(1); next(2); next(3); next(4)
            compose.onNodeWithText(tr(TextKey.TUTORIAL_BACKUP_TIP)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("tutorial-next").assertIsDisplayed()
            capture("en-landscape-large-text")
            compose.onNodeWithTag("tutorial-next").performClick(); chooser()
        } finally {
            shell("settings put system font_scale $originalFontScale")
            scenario?.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
}
