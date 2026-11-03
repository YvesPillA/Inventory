package com.fieldstock.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldstock.app.backup.*
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.i18n.*
import com.fieldstock.app.importdata.ImportRow
import com.fieldstock.app.model.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class Audit244WorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var vm: InventoryViewModel
    private lateinit var backup: InventorySnapshot
    private lateinit var warehouse: Warehouse

    @Before fun seed() {
        context.databaseList().forEach(context::deleteDatabase)
        context.getSharedPreferences("fieldstock", 0).edit().clear().putBoolean("automatic_backup", false).commit()
        InventoryRepository(context).use { repo ->
            val source = repo.addWarehouse(repo.addCountry("Norway").id, "Main")
            repo.save(RecordInput(repo.snapshot().tasks.first().id, source.id, ItemKind.PART, "ABC01", "", 6, StockCondition.NORMAL, "source"))
            backup = repo.snapshot()
        }
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        InventoryRepository(context).use { repo ->
            warehouse = repo.addWarehouse(repo.addCountry("Norway").id, "Main")
            repo.save(RecordInput(repo.snapshot().tasks.first().id, warehouse.id, ItemKind.PART, "ABC01", "", 4, StockCondition.NORMAL, "local"))
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
    private fun preview(candidate: InventorySnapshot = backup) { scenario!!.onActivity { vm.restoreCandidate = candidate }; compose.waitForIdle() }
    private fun choose(action: String) = compose.onNodeWithTag("backup-$action-${backup.warehouses.single().id}").performScrollTo().performClick()

    @Test fun duplicateConfirmationDuringBusyWorkImportsOnceOnTheRealDatabase() {
        launch()
        val release = CountDownLatch(1)
        try {
            scenario!!.onActivity {
                vm.importRows = listOf(ImportRow(ItemKind.PART, "ABC01", "", 3, StockCondition.NORMAL, "incoming"))
                vm.perform("background fixture") { release.await(10, TimeUnit.SECONDS) }
                vm.applyImport(warehouse.id, DuplicateMode.ADD_QUANTITY)
                vm.applyImport(warehouse.id, DuplicateMode.ADD_QUANTITY)
            }
            release.countDown()
            compose.waitUntil(20_000) { !vm.busy }
            assertEquals(7, vm.snapshot.records.single().quantity)
            assertEquals(1, vm.importReceipts.size)
        } finally { release.countDown() }
    }

    @Test fun warehouseRenameChoicesAndTypedNameSurviveActivityRecreation() {
        launch(AppLanguage.ENGLISH); preview()
        compose.onNodeWithTag("backup-country-${backup.countries.single().id}").performScrollTo().performClick()
        choose("rename")
        val field = "backup-rename-name-${backup.warehouses.single().id}"
        compose.onNodeWithTag(field).performScrollTo().performTextInput("Imported Main")
        scenario!!.recreate()
        scenario!!.onActivity { vm = ViewModelProvider(it)[InventoryViewModel::class.java] }
        compose.onNodeWithTag(field).performScrollTo().assertTextContains("Imported Main")
        compose.onNodeWithTag("backup-confirm").assertIsEnabled().performClick()
        compose.waitUntil(20_000) { !vm.busy && vm.snapshot.warehouses.size == 2 }
        assertEquals(setOf("Main", "Imported Main"), vm.snapshot.warehouses.map { it.name }.toSet())
        assertEquals(listOf(4, 6), vm.snapshot.records.map { it.quantity }.sorted())
    }

    @Test fun rememberedWarehouseMappingStillOffersBothQuantityChoicesForNewRecords() {
        InventoryRepository(context).use { repo ->
            repo.importBackup(backup, BackupMergeChoices(backup.countries.map { it.id }.toSet(),
                backup.warehouses.associate { it.id to BackupWarehouseChoice(BackupWarehouseAction.MERGE) }))
        }
        launch()
        val newer = backup.copy(records = backup.records + backup.records.single().copy(id = "new-source-record", quantity = 2))
        preview(newer)
        compose.onNodeWithText(tr(TextKey.CREATE_NEW_ENTRY)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(tr(TextKey.ADD_TO_EXISTING_QUANTITY)).performScrollTo().performClick()
        compose.onNodeWithTag("backup-confirm").performClick()
        compose.waitUntil(20_000) { !vm.busy && vm.restoreCandidate == null }
        assertEquals(12, vm.snapshot.records.sumOf { it.quantity })
        assertEquals(2, vm.snapshot.records.size)
    }

    @Test fun sameIdRenamedLocationsCanBeExplicitlyReusedWithoutRenamingThemBack() {
        InventoryRepository(context).use { repo ->
            val current = repo.snapshot()
            backup = current.copy(records = emptyList(), changes = emptyList())
            repo.renameCountry(warehouse.countryId, "Norge")
            repo.renameWarehouse(warehouse.id, "Current Main")
        }
        launch(); preview()
        compose.onNodeWithTag("backup-country-${backup.countries.single().id}").performScrollTo().performClick()
        compose.onNodeWithTag("backup-rename-${warehouse.id}").assertDoesNotExist()
        choose("merge")
        compose.onNodeWithTag("backup-confirm").performClick()
        compose.waitUntil(20_000) { !vm.busy && vm.restoreCandidate == null }
        assertEquals("Norge", vm.snapshot.countries.single().name)
        assertEquals("Current Main", vm.snapshot.warehouses.single().name)
        assertEquals(4, vm.snapshot.records.single().quantity)
    }

    @Test fun diagnosticPickerResultSurvivesRecreationEvenAfterTheConsoleIsClosed() {
        launch(); click(TextKey.SETTINGS)
        scenario!!.onActivity { vm.openDeveloperSession() }
        compose.onNodeWithTag("developer-console").assertIsDisplayed()
        val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("application/zip") }
        // A blocking monitor with no result holds the external result for later delivery.
        val monitor = instrumentation.addMonitor(filter, null, true)
        val file = File(File(context.cacheDir, "shared-backups").apply { mkdirs() }, "diagnostic-recreated.zip")
        file.delete()
        var requestCode = -1
        try {
            click(TextKey.DIAGNOSTIC_EXPORT)
            assertEquals(1, monitor.hits)
            scenario!!.onActivity { activity ->
                val saved = Bundle().also(activity.activityResultRegistry::onSaveInstanceState)
                val launched = saved.getStringArrayList(saved.keySet().single { it.endsWith("LAUNCHED_KEYS") })!!.single()
                val keys = saved.getStringArrayList(saved.keySet().single { it.endsWith("REGISTERED_KEYS") })!!
                val codes = saved.getIntegerArrayList(saved.keySet().single { it.endsWith("REGISTERED_RCS") })!!
                requestCode = codes[keys.indexOf(launched)]
            }
            scenario!!.recreate()
            scenario!!.onActivity { vm = ViewModelProvider(it)[InventoryViewModel::class.java] }
            assertTrue(vm.developerUnlocked)
            scenario!!.onActivity { vm.closeDeveloperSession() }
            compose.onNodeWithTag("developer-console").assertDoesNotExist()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            scenario!!.onActivity { assertTrue(it.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))) }
            compose.waitUntil(20_000) { !vm.diagnosticsWorking && file.length() > 0 }
            assertFalse(vm.developerUnlocked)
            assertEquals(tr(TextKey.DIAGNOSTIC_SAVED), vm.diagnosticsStatus)
            ZipFile(file).use { zip ->
                val env = JSONObject(zip.getInputStream(zip.getEntry("environment.json")).bufferedReader().readText())
                assertEquals(1, env.getJSONObject("inventory_counts").getInt("records"))
                assertEquals("2.4.4-hotfix-audit-20261030", env.getJSONObject("app").getString("revision"))
                assertNotNull(zip.getEntry("operations/events.jsonl"))
            }
        } finally { instrumentation.removeMonitor(monitor); file.delete() }
    }
}
