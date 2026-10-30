package com.fieldstock.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.i18n.*
import com.fieldstock.app.model.*
import com.fieldstock.app.scan.OcrReviewImages
import com.fieldstock.app.scan.ScanActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CountingWorkflow242Test {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var warehouse: String

    @Before fun seed() {
        context.databaseList().forEach(context::deleteDatabase)
        context.getSharedPreferences("fieldstock", 0).edit().clear().commit()
        File(context.filesDir, "automatic-backups").deleteRecursively()
        InventoryRepository(context).use { repo ->
            warehouse = repo.addWarehouse(repo.addCountry("Norway").id, "Main").id
        }
        context.getSharedPreferences("fieldstock", 0).edit().putString("warehouse", warehouse).commit()
    }
    @After fun close() { scenario?.close(); LanguageSettings.select(context, AppLanguage.CHINESE) }
    private fun text(key: TextKey, vararg args: Any?) = tr(key, *args)
    private fun waitFor(value: String) = compose.waitUntil(20_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun click(key: TextKey) = compose.onNodeWithText(text(key)).performClick()
    private fun records() = InventoryRepository(context).use { it.snapshot().records }
    private fun launch(language: AppLanguage) {
        LanguageSettings.select(context, language)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text(TextKey.ENTER_WAREHOUSE)).fetchSemanticsNodes().singleOrNull()
                ?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
        }
        click(TextKey.ENTER_WAREHOUSE); waitFor(text(TextKey.SAVE_RECORD))
    }
    private fun capture(name: String) {
        compose.waitForIdle(); instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "2.4.2-" + name + ".png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    /** Exercise the real scanner result/confirmation and ActivityResult callback without camera timing. */
    private fun confirmScan(raw: String) {
        var scanner: ScanActivity? = null
        compose.waitUntil(15_000) {
            instrumentation.runOnMainSync {
                scanner = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ScanActivity>().singleOrNull()
            }
            scanner != null
        }
        val bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val store = OcrReviewImages(context); val id = store.save(bitmap)
        instrumentation.runOnMainSync { scanner!!.reviewRecognition(raw, id, store.load(id)) }
        bitmap.recycle()
        waitFor(text(TextKey.CONFIRM_AND_FILL)); click(TextKey.CONFIRM_AND_FILL)
    }

    @Test fun typedAndScannedBomShowCurrentWarehouseActivePartsAndNoMatch() {
        InventoryRepository(context).use { repo ->
            val task = repo.snapshot().tasks.single().id
            val input = RecordInput(task, warehouse, ItemKind.PART, "000AB", "", 5, StockCondition.NORMAL, "Existing stock")
            repo.save(input); repo.save(input.copy(condition = StockCondition.DAMAGED, quantity = 2))
            repo.voidRecord(repo.save(input.copy(quantity = 99)).id)
            val other = repo.addWarehouse(repo.snapshot().countries.single().id, "Other")
            repo.save(input.copy(warehouseId = other.id, quantity = 100))
            repo.save(input.copy(kind = ItemKind.MACHINE, sn = "A-01", quantity = 1))
        }
        launch(AppLanguage.ENGLISH)
        compose.onNodeWithTag("count-bom").performTextReplacement("000AB")
        compose.onNodeWithText(text(TextKey.BOM_ALREADY_RECORDED, 2, 7)).assertIsDisplayed()
        capture("parts-keyboard-en")
        compose.onNodeWithTag("count-bom").performTextReplacement("NEW001")
        compose.onNodeWithText(text(TextKey.BOM_NOT_RECORDED)).assertIsDisplayed()
        compose.onNodeWithText(text(TextKey.SCAN, "BOM")).performClick()
        confirmScan("BOM: 000AB")
        waitFor(text(TextKey.SAVE_RECORD))
        compose.onNodeWithTag("count-bom").assertTextContains("000AB")
        compose.onNodeWithText(text(TextKey.BOM_ALREADY_RECORDED, 2, 7)).assertIsDisplayed()
        capture("parts-scanned-en")
        assertEquals(5, records().size)
    }

    @Test fun snOnlyMachineSavesWithEmptyBomAndPnAndStillRejectsEmptySn() {
        launch(AppLanguage.ENGLISH); click(TextKey.MACHINE)
        click(TextKey.SAVE_RECORD)
        compose.onNodeWithText(text(TextKey.ENTER_AN_SN)).assertIsDisplayed()
        assertTrue(records().isEmpty())
        compose.onNodeWithText(text(TextKey.SN_FIRST_PART)).performTextReplacement("000a")
        compose.onNodeWithText(text(TextKey.SN_LAST_PART)).performTextReplacement("01")
        capture("machine-keyboard-en")
        click(TextKey.SAVE_RECORD)
        compose.waitUntil(20_000) { records().size == 1 }
        val saved = records().single()
        assertEquals("000a-01", saved.sn); assertEquals("", saved.bom); assertEquals("", saved.pn)
        capture("machine-en")
    }

    @Test fun scannedPnAndMultilineNotesSurviveRecreationSaveSearchAndEditInChinese() {
        launch(AppLanguage.CHINESE); click(TextKey.MACHINE)
        compose.onNodeWithText(text(TextKey.SCAN, "PN")).performClick()
        confirmScan("PN: 000-aB-02-C")
        waitFor(text(TextKey.SAVE_RECORD))
        compose.onNodeWithTag("count-pn").assertTextContains("000-aB-02-C")
        click(TextKey.MORE)
        compose.onNodeWithText(text(TextKey.SCAN, text(TextKey.NOTE))).performClick()
        val note = "ABC 123 / +5 = 8, #2\nOK (A-B); @customer"
        confirmScan(note)
        waitFor(text(TextKey.NOTES_OPTIONAL))
        compose.onNodeWithTag("count-note").assertTextContains(note)
        capture("notes-zh")
        click(TextKey.DONE)
        scenario!!.recreate(); waitFor(text(TextKey.SAVE_RECORD))
        compose.onNodeWithTag("count-pn").assertTextContains("000-aB-02-C")
        compose.onNodeWithText(text(TextKey.SN_FIRST_PART)).performTextReplacement("000a")
        compose.onNodeWithText(text(TextKey.SN_LAST_PART)).performTextReplacement("01")
        click(TextKey.SAVE_RECORD)
        compose.waitUntil(20_000) { records().size == 1 }
        val saved = records().single()
        assertEquals("", saved.bom); assertEquals("000-aB-02-C", saved.pn)
        assertEquals("ocr", saved.sourcePn); assertEquals("PN: 000-aB-02-C", saved.rawPn); assertEquals(note, saved.note)
        waitFor(text(TextKey.RECORDS_3)); click(TextKey.RECORDS_3)
        compose.onNodeWithText(text(TextKey.SEARCH_BOM_SN_OR_NOTES)).performTextReplacement("aB-02")
        compose.onNodeWithText("PN: 000-aB-02-C").assertExists()
        closeSoftKeyboard()
        capture("pn-record-zh")
        compose.onNodeWithText(text(TextKey.EDIT)).performClick()
        compose.onNodeWithTag("count-pn").assertTextContains("000-aB-02-C")
        click(TextKey.MORE); compose.onNodeWithTag("count-note").assertTextContains(note)
    }

    @Test fun partsNotesScannerSupportsEnglishMultilinePunctuationAndSavesWithoutChangingBom() {
        launch(AppLanguage.ENGLISH)
        compose.onNodeWithTag("count-bom").performTextReplacement("000XY")
        compose.onNodeWithText(text(TextKey.QUANTITY_PCS)).performTextReplacement("4")
        closeSoftKeyboard()
        click(TextKey.MORE)
        compose.onNodeWithText(text(TextKey.SCAN, text(TextKey.NOTE))).performClick()
        val note = "Box #2: A+B / C\nQty = 4 (OK), 50%"
        confirmScan(note); waitFor(text(TextKey.NOTES_OPTIONAL))
        compose.onNodeWithTag("count-note").assertTextContains(note)
        capture("notes-en"); click(TextKey.DONE); click(TextKey.SAVE_RECORD)
        compose.waitUntil(20_000) { records().size == 1 }
        assertEquals("000XY", records().single().bom); assertEquals(4, records().single().quantity); assertEquals(note, records().single().note)
    }
}
