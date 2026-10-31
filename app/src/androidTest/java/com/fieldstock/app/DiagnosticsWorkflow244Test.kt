package com.fieldstock.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.diagnostics.AppDiagnostics
import com.fieldstock.app.export.InventoryFiles
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
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class DiagnosticsWorkflow244Test {
    @get:Rule val compose=createEmptyComposeRule()
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private var scenario:ActivityScenario<MainActivity>?=null
    private lateinit var vm:InventoryViewModel
    private lateinit var warehouse:String
    @Before fun seed(){
        context.databaseList().forEach(context::deleteDatabase)
        context.getSharedPreferences("fieldstock",0).edit().clear().putBoolean("automatic_backup",false).commit()
        File(context.filesDir,"automatic-backups").deleteRecursively()
        InventoryRepository(context).use{repo->warehouse=repo.addWarehouse(repo.addCountry("Norway").id,"Main").id}
        context.getSharedPreferences("fieldstock",0).edit().putString("warehouse",warehouse).commit()
    }
    @After fun close(){scenario?.close();LanguageSettings.select(context,AppLanguage.CHINESE)}
    private fun t(key:TextKey)=tr(key)
    private fun waitFor(value:String)=compose.waitUntil(20_000){compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()}
    private fun click(key:TextKey)=compose.onNodeWithText(t(key)).performClick()
    private fun launch(){
        LanguageSettings.select(context,AppLanguage.CHINESE)
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity{vm=ViewModelProvider(it)[InventoryViewModel::class.java]}
        compose.waitUntil(20_000){!vm.busy&&vm.snapshot.warehouses.isNotEmpty()}
        click(TextKey.ENTER_WAREHOUSE);waitFor(t(TextKey.SAVE_RECORD))
    }
    private fun shared(name:String)=File(File(context.cacheDir,"shared-backups").apply{mkdirs()},name)
    @Test fun fiveVersionTapsGiveNoPromptSixthRequestsKeyAndWrongInputCannotEnter(){
        launch();click(TextKey.SETTINGS)
        repeat(5){compose.onNodeWithTag("app-version").performScrollTo().performClick();compose.onNodeWithTag("developer-key").assertDoesNotExist()}
        compose.onNodeWithTag("app-version").performClick()
        compose.onNodeWithTag("developer-key").assertIsDisplayed().performTextInput("invalid-test-key")
        click(TextKey.DEVELOPER_ENTER)
        compose.onNodeWithText(t(TextKey.DEVELOPER_KEY_ERROR)).assertIsDisplayed()
        compose.onNodeWithTag("developer-console").assertDoesNotExist()
        click(TextKey.CANCEL)
        assertTrue(InventoryRepository(context).use{it.snapshot().records.isEmpty()})
    }
    @Test fun selectedFileDuringBusyOperationIsQueuedAndConfirmationIsVisible(){
        launch();click(TextKey.FILES);click(TextKey.IMPORT)
        val supplied=InstrumentationRegistry.getArguments().getString("downloadedSamples244")=="true"
        val file=if(supplied)File(context.getExternalFilesDir(null),"compat-samples-244/sample.xlsx").copyTo(shared("returned.xlsx"),true)
            else shared("returned.csv").apply{writeText("Type,BOM,SN,Quantity,Condition,Note,PN\nPart,000aB,,7,Normal,returned,\n")}
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        val release=CountDownLatch(1)
        try{
            scenario!!.onActivity{
                vm.perform("test background operation"){release.await(10,TimeUnit.SECONDS)}
                vm.previewImport(uri)
            }
            compose.onNodeWithTag("import-reading").assertIsDisplayed()
            release.countDown()
            compose.waitUntil(20_000){!vm.importReading&&vm.importRows!=null}
            assertEquals(if(supplied)400 else 1,vm.importRows!!.size)
            compose.waitForIdle();Thread.sleep(300);compose.waitForIdle()
            compose.onNodeWithText(t(TextKey.PREVIEW_AND_CONFIRM_IMPORT)).assertIsDisplayed().performClick()
            click(TextKey.CONFIRM_IMPORT)
            compose.waitUntil(20_000){!vm.busy&&vm.snapshot.records.size==(if(supplied)400 else 1)}
            assertTrue(AppDiagnostics.lines("","import.queued").isNotEmpty())
            assertTrue(AppDiagnostics.lines("","import.preview.ready").isNotEmpty())
        }finally{release.countDown()}
    }
    @Test fun backupSelectionDuringBusyOperationShowsMergeConfirmation(){
        launch();click(TextKey.FILES);click(TextKey.IMPORT)
        val file=shared("queued-backup.json").apply{outputStream().use{InventoryFiles.writeBackup(vm.snapshot,it)}}
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        val release=CountDownLatch(1)
        try{
            scenario!!.onActivity{vm.perform("test busy"){release.await(10,TimeUnit.SECONDS)};vm.previewImport(uri)}
            release.countDown();waitFor(t(TextKey.MERGE_THIS_BACKUP))
            assertTrue(vm.snapshot.records.isEmpty());click(TextKey.CANCEL)
        }finally{release.countDown()}
    }
    /** Render the console directly to test exports without putting the real unlock key in test files. */
    @Test fun consoleFiltersExportsDetailedLogsAndClearingPreservesStock(){
        InventoryRepository(context).use{repo->repo.save(RecordInput(repo.snapshot().tasks.first().id,warehouse,ItemKind.PART,"ABC","",7,StockCondition.NORMAL,"PRIVATE_STOCK_NOTE_244"))}
        launch()
        AppDiagnostics.event("test.error.244",failure=IllegalStateException("outer diagnostic",IllegalArgumentException("inner diagnostic")),level="ERROR")
        scenario!!.onActivity{activity->activity.setContent{MaterialTheme{DiagnosticExportHost(vm){DeveloperConsole(vm){}}}}}
        waitFor(t(TextKey.DIAGNOSTIC_EXPORT));click(TextKey.DIAGNOSTIC_ERRORS)
        compose.waitUntil(20_000){compose.onAllNodesWithText("inner diagnostic",substring=true).fetchSemanticsNodes().isNotEmpty()}
        val file=shared("diagnostics-test.zip")
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        val filter=IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);addDataType("application/zip")}
        val monitor=instrumentation.addMonitor(filter,Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri)),true)
        try{click(TextKey.DIAGNOSTIC_EXPORT);waitFor(t(TextKey.DIAGNOSTIC_SAVED));assertEquals(1,monitor.hits)}finally{instrumentation.removeMonitor(monitor)}
        val entries=linkedMapOf<String,String>()
        ZipInputStream(file.inputStream()).use{zip->while(true){val entry=zip.nextEntry?:break;entries[entry.name]=zip.readBytes().toString(Charsets.UTF_8)}}
        assertTrue(entries.containsKey("environment.json"));assertTrue(entries.containsKey("app-logcat.txt"))
        assertTrue(entries.values.any{it.contains("inner diagnostic")})
        assertFalse(entries.values.any{it.contains("PRIVATE_STOCK_NOTE_244")||it.contains("DEVELOPER_KEY_SHA256")})
        click(TextKey.DIAGNOSTIC_CLEAR)
        compose.onNodeWithText(t(TextKey.DIAGNOSTIC_CLEAR_CONFIRM)).assertIsDisplayed()
        // The dialog button has the same label as the console's clear action.
        compose.onAllNodesWithText(t(TextKey.DIAGNOSTIC_CLEAR)).onLast().performClick()
        compose.waitUntil(20_000){AppDiagnostics.lines("ERROR","test.error.244").isEmpty()}
        assertEquals(1,InventoryRepository(context).use{it.snapshot().records.size})
        assertEquals(7,InventoryRepository(context).use{it.snapshot().records.single().quantity})
    }
}
