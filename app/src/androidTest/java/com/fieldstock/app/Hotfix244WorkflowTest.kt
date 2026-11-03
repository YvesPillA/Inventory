package com.fieldstock.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.i18n.*
import com.fieldstock.app.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Hotfix244WorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var vm: InventoryViewModel
    private lateinit var backup: InventorySnapshot
    private lateinit var warehouse: Warehouse
    @Before fun seed() {
        context.databaseList().forEach(context::deleteDatabase)
        context.getSharedPreferences("fieldstock", 0).edit().clear().putBoolean("automatic_backup", false).commit()
        InventoryRepository(context).use { repo ->
            val source = repo.addWarehouse(repo.addCountry("Norway").id, "Main")
            repo.save(RecordInput(repo.snapshot().tasks.first().id, source.id, ItemKind.PART, "00Ab1", "", 6, StockCondition.NORMAL, "备份 note"))
            backup = repo.snapshot()
        }
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        InventoryRepository(context).use { repo ->
            warehouse = repo.addWarehouse(repo.addCountry("NORWAY").id, "Main")
            repo.save(RecordInput(repo.snapshot().tasks.first().id, warehouse.id, ItemKind.PART, "00Ab1", "", 4, StockCondition.NORMAL, "local"))
        }
        context.getSharedPreferences("fieldstock", 0).edit().putString("warehouse", warehouse.id).commit()
    }
    @After fun close() { scenario?.close(); LanguageSettings.select(context, AppLanguage.CHINESE) }
    private fun click(key: TextKey) = compose.onNodeWithText(tr(key)).performClick()
    private fun launch(language: AppLanguage = AppLanguage.CHINESE) {
        LanguageSettings.select(context, language)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { vm = ViewModelProvider(it)[InventoryViewModel::class.java] }
        compose.waitUntil(20_000) { !vm.busy && vm.snapshot.warehouses.isNotEmpty() }
        click(TextKey.ENTER_WAREHOUSE)
    }
    private fun preview() { scenario!!.onActivity { vm.restoreCandidate = backup }; compose.onNodeWithTag("backup-confirm").assertIsDisplayed() }
    private fun acceptCountry() = compose.onNodeWithTag("backup-country-${backup.countries.single().id}").performScrollTo().performClick()
    private fun choose(action: String) = compose.onNodeWithTag("backup-$action-${backup.warehouses.single().id}").performScrollTo().performClick()
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(File(context.getExternalFilesDir(null), "hotfix-evidence").apply { mkdirs() }, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun moreDialogSurvivesKeyboardResizingRepeatedNotesAndPageChanges() {
        launch()
        repeat(5) {
            compose.onNodeWithTag("count-bom").performTextReplacement("00Ab1")
            compose.onNodeWithTag("count-bom").performClick()
            Espresso.closeSoftKeyboard(); compose.waitForIdle()
            compose.onNodeWithTag("count-more").performClick()
            compose.onNodeWithTag("count-note").assertIsDisplayed().performTextReplacement("note $it\n备注 + / -")
            click(TextKey.DONE)
            compose.onNodeWithTag("count-note").assertDoesNotExist()
            Espresso.closeSoftKeyboard(); compose.waitForIdle()
            click(TextKey.RECORDS_3); click(TextKey.COUNT)
        }
        assertEquals(4, vm.snapshot.records.single().quantity)
    }

    @Test fun chineseBackupMergeAddsQuantityAndReplayAddsNothing() {
        launch(); preview(); acceptCountry(); choose("merge")
        compose.onNodeWithText(tr(TextKey.ADD_TO_EXISTING_QUANTITY)).performScrollTo().performClick()
        capture("backup-merge-zh.png")
        compose.onNodeWithTag("backup-confirm").performClick()
        compose.waitUntil(20_000) { !vm.busy && vm.snapshot.records.singleOrNull()?.quantity == 10 }
        assertEquals(1, vm.snapshot.warehouses.size)
        preview(); compose.onNodeWithTag("backup-confirm").performClick()
        compose.waitUntil(20_000) { !vm.busy && vm.restoreCandidate == null }
        assertEquals(10, vm.snapshot.records.single().quantity)
    }

    @Test fun englishBackupRenameAddsSeparateWarehouse() {
        launch(AppLanguage.ENGLISH); preview(); acceptCountry(); choose("rename")
        compose.onNodeWithTag("backup-rename-name-${backup.warehouses.single().id}").performScrollTo().performTextInput("Imported Main")
        Espresso.closeSoftKeyboard(); compose.waitForIdle()
        capture("backup-rename-en.png")
        compose.onNodeWithTag("backup-confirm").performClick()
        compose.waitUntil(20_000) { !vm.busy && vm.snapshot.warehouses.size == 2 }
        assertEquals(listOf(4, 6), vm.snapshot.records.map { it.quantity }.sorted())
        assertEquals(1, vm.snapshot.countries.size)
    }

    @Test fun englishNewEntryMergeAndCancelledChoicesLeaveCorrectInventory() {
        launch(AppLanguage.ENGLISH); preview(); acceptCountry(); choose("merge")
        val before = vm.snapshot
        click(TextKey.CANCEL)
        compose.onNodeWithTag("backup-confirm").assertDoesNotExist()
        compose.waitForIdle()
        assertEquals(before, vm.snapshot)
        preview(); acceptCountry(); choose("merge")
        compose.onNodeWithText(tr(TextKey.CREATE_NEW_ENTRY)).performScrollTo().performClick()
        compose.onNodeWithTag("backup-confirm").performClick()
        compose.waitUntil(20_000) { !vm.busy && vm.snapshot.records.size == 2 }
        assertEquals(listOf(4, 6), vm.snapshot.records.map { it.quantity }.sorted())
        assertEquals(1, vm.snapshot.warehouses.size)
    }
}
