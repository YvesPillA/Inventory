package com.fieldstock.app

import android.app.Application
import android.net.Uri
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.i18n.*
import com.fieldstock.app.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BilingualWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var warehouse: String
    private lateinit var before: InventorySnapshot

    @Before fun seed() {
        application.databaseList().forEach(application::deleteDatabase)
        application.getSharedPreferences("fieldstock", 0).edit().clear().commit()
        AppLanguage.current = AppLanguage.CHINESE
        File(application.filesDir, "automatic-backups").deleteRecursively()
        InventoryRepository(application).use { repository ->
            val country = repository.addCountry("中国 Norway")
            warehouse = repository.addWarehouse(country.id, "正常 Warehouse").id
            repository.save(RecordInput(repository.snapshot().tasks.first().id, warehouse, ItemKind.MACHINE,
                "000aB", "000a-0B", 1, StockCondition.DAMAGED, "中文 English note"))
            before = repository.snapshot()
        }
        application.getSharedPreferences("fieldstock", 0).edit().putString("warehouse", warehouse).commit()
    }

    @After fun close() {
        scenario?.close()
        LanguageSettings.select(application, AppLanguage.CHINESE)
    }

    private fun waitFor(text: String) = compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun unchanged() = InventoryRepository(application).use { assertEquals(before, it.snapshot()) }
    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // Semantics can be ready before SurfaceFlinger presents the new frame.
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(application.getExternalFilesDir(null), "i18n-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun firstLaunchAsksOnceAndEnglishChoiceSurvivesRecreation() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Choose language / 选择语言").assertIsDisplayed()
        capture("first-use")
        compose.onNodeWithText("English").performClick()
        waitFor("Enter warehouse")
        assertEquals(AppLanguage.ENGLISH, LanguageSettings.selected(application))
        scenario!!.recreate()
        waitFor("Enter warehouse")
        compose.onNodeWithText("Choose language / 选择语言").assertDoesNotExist()
        compose.onNodeWithText("正常 Warehouse ▾").assertIsDisplayed()
        capture("warehouse-en")
        unchanged()
    }

    @Test fun switchingLanguageKeepsSavedStockAndUnsavedFormValues() {
        LanguageSettings.select(application, AppLanguage.ENGLISH)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Enter warehouse")
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithText("Enter warehouse").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithText("Enter warehouse").performClick()
        waitFor("Save record")
        capture("count-en")
        compose.onNodeWithText("BOM").performTextReplacement("000xY")
        compose.onNodeWithText("Quantity (pcs)").performTextReplacement("3")
        compose.onNodeWithText("Quantity (pcs)").performImeAction()
        waitFor("Settings")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Language / 语言 · English").performClick()
        compose.onNodeWithText("简体中文").performClick()
        waitFor("设置")
        compose.onNodeWithText("盘点").performClick()
        compose.onNodeWithText("BOM").assertTextContains("000xY")
        compose.onNodeWithText("数量（件）").assertTextContains("3")
        assertEquals(AppLanguage.CHINESE, LanguageSettings.selected(application))
        unchanged()
    }

    @Test fun preparedEnglishTemplateKeepsItsLanguageWhenViewModelIsRecreated() {
        LanguageSettings.select(application, AppLanguage.ENGLISH)
        val storeA = ViewModelStore(); val storeB = ViewModelStore()
        val file = File.createTempFile("bilingual-template-", ".csv", application.cacheDir)
        try {
            lateinit var modelA: InventoryViewModel
            onMain { modelA = ViewModelProvider(storeA, ViewModelProvider.AndroidViewModelFactory(application))[InventoryViewModel::class.java] }
            awaitIdle(modelA)
            val ready = CountDownLatch(1)
            onMain { modelA.prepareExport("template", false) { ready.countDown() } }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            awaitIdle(modelA)
            assertEquals("en", application.getSharedPreferences("fieldstock", 0).getString("pending_language", null))
            onMain { storeA.clear() }
            LanguageSettings.select(application, AppLanguage.CHINESE)
            lateinit var modelB: InventoryViewModel
            onMain { modelB = ViewModelProvider(storeB, ViewModelProvider.AndroidViewModelFactory(application))[InventoryViewModel::class.java] }
            awaitIdle(modelB)
            onMain { modelB.export(Uri.fromFile(file)) }
            awaitIdle(modelB)
            assertEquals("\uFEFFType,BOM,SN,Quantity,Condition,Note,PN\r\n", file.readText())
            assertFalse(application.getSharedPreferences("fieldstock", 0).contains("pending_language"))
            unchanged()
        } finally { onMain { storeA.clear(); storeB.clear() }; file.delete() }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun awaitIdle(model: InventoryViewModel) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var idle = false
            onMain { assertNull(model.error); idle = !model.busy }
            if (idle) return
            SystemClock.sleep(25)
        }
        fail("Timed out waiting for inventory operation")
    }
}
