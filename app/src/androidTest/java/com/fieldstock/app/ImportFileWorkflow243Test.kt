package com.fieldstock.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.i18n.*
import com.fieldstock.app.importdata.InventoryImport
import com.fieldstock.app.importdata.InventoryImportContent
import com.fieldstock.app.model.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImportFileWorkflow243Test {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var base: InventorySnapshot
    private lateinit var warehouse: String
    @Before fun seed() {
        context.databaseList().forEach(context::deleteDatabase)
        context.getSharedPreferences("fieldstock",0).edit().clear().commit()
        File(context.filesDir,"automatic-backups").deleteRecursively()
        InventoryRepository(context).use { repo ->
            warehouse = repo.addWarehouse(repo.addCountry("Norway").id,"Main").id
            base = repo.snapshot()
        }
        context.getSharedPreferences("fieldstock",0).edit().putString("warehouse",warehouse).putBoolean("feedback_sound",false).commit()
    }
    @After fun close() { scenario?.close(); LanguageSettings.select(context,AppLanguage.CHINESE) }
    private fun text(key: TextKey, vararg args: Any?) = tr(key,*args)
    private fun waitFor(value: String) = compose.waitUntil(20_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun click(key: TextKey) = compose.onNodeWithText(text(key)).performClick()
    private fun snapshot() = InventoryRepository(context).use { it.snapshot() }
    private fun launch(language: AppLanguage) {
        LanguageSettings.select(context,language)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(text(TextKey.ENTER_WAREHOUSE)); compose.waitForIdle()
        click(TextKey.ENTER_WAREHOUSE); waitFor(text(TextKey.SAVE_RECORD)); click(TextKey.FILES); click(TextKey.IMPORT)
        compose.onNodeWithTag("import-choose-file").assertIsDisplayed()
    }
    private fun fixture(): InventorySnapshot {
        val part = StockRecord("source-part",base.tasks.first().id,warehouse,ItemKind.PART,"000aB","",7,StockCondition.NORMAL,
            "Line 1: A+B / 2\n中文, #3", "", "manual", "manual", "", "", 1,1,false)
        val machine = part.copy(id="source-machine",kind=ItemKind.MACHINE,bom="",sn="000a-01",quantity=1,pn="00-A-b2-C")
        return base.copy(records=listOf(part,machine,part.copy(id="source-voided",bom="VOID01",voided=true)))
    }
    private fun file(name: String, write: (java.io.OutputStream) -> Unit): File {
        val dir = File(context.cacheDir,"shared-backups").apply { mkdirs() }
        return File(dir,name).also { it.outputStream().use(write) }
    }
    /** Exercise the real OpenDocument callback, with a deterministic returned document. */
    private fun choose(file: File) {
        val uri = FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val result = Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        val monitor = instrumentation.addMonitor(filter,result,true)
        try { compose.onNodeWithTag("import-choose-file").performScrollTo().performClick(); compose.waitForIdle(); assertEquals(1,monitor.hits) }
        finally { instrumentation.removeMonitor(monitor) }
    }
    private fun capture(name: String) {
        compose.waitForIdle(); instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null),"2.4.3-"+name+".png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
    @Test fun exportedExcelUsesTheSamePickerAndKeepsPnNotesAndExactDetailCounts() {
        launch(AppLanguage.ENGLISH)
        capture("import-en")
        val source = fixture()
        choose(file("inventory.xlsx") { InventoryFiles.writeXlsx(source,it,null,null,AppLanguage.CHINESE) })
        waitFor(text(TextKey.FILE_READ_ROWS_CONFIRM_TO_IMPORT_INTO_THE_SELECTED,"inventory.xlsx",2))
        assertTrue(snapshot().records.isEmpty())
        compose.onNodeWithText(text(TextKey.PREVIEW_AND_CONFIRM_IMPORT)).performScrollTo().performClick()
        waitFor(text(TextKey.CONFIRM_IMPORT)); click(TextKey.CONFIRM_IMPORT)
        compose.waitUntil(20_000) { snapshot().records.size == 2 }
        val rows = snapshot().records
        assertEquals(7,rows.single { it.kind == ItemKind.PART }.quantity)
        assertEquals("00-A-b2-C",rows.single { it.kind == ItemKind.MACHINE }.pn)
        assertTrue(rows.all { it.note == "Line 1: A+B / 2\n中文, #3" && it.warehouseId == warehouse })
        compose.onNodeWithTag("import-help").performScrollTo().assertIsDisplayed()
        capture("import-help-en")
    }
    @Test fun jsonFromImportPageRequiresConfirmationAndMergesOriginalWarehousesIdempotently() {
        launch(AppLanguage.CHINESE)
        capture("import-zh")
        val sourceWarehouse = Warehouse("source-warehouse","source-country","Original")
        val source = fixture().copy(countries=listOf(Country("source-country","Sweden")),warehouses=listOf(sourceWarehouse),
            records=fixture().records.map { it.copy(warehouseId=sourceWarehouse.id) })
        val backup = file("backup.json") { InventoryFiles.writeBackup(source,it) }
        choose(backup); waitFor(text(TextKey.MERGE_THIS_BACKUP)); capture("backup-confirm-zh")
        assertTrue(snapshot().records.isEmpty()); click(TextKey.CANCEL)
        assertTrue(snapshot().records.isEmpty())
        choose(backup); waitFor(text(TextKey.MERGE_THIS_BACKUP)); click(TextKey.CHECK_AND_MERGE)
        compose.waitUntil(20_000) { snapshot().records.size == 3 }
        assertEquals(source.records.toSet(),snapshot().records.toSet())
        assertEquals(2,snapshot().warehouses.size)
        choose(backup); waitFor(text(TextKey.MERGE_THIS_BACKUP)); click(TextKey.CHECK_AND_MERGE)
        waitFor(text(TextKey.BACKUP_MERGED)); assertEquals(3,snapshot().records.size)
    }
    @Test fun exportedEnglishWorkbookImportsIntoChineseInterface() {
        launch(AppLanguage.CHINESE)
        val source = fixture()
        choose(file("English-report.xlsx") { InventoryFiles.writeXlsx(source,it,null,null,AppLanguage.ENGLISH) })
        waitFor(text(TextKey.FILE_READ_ROWS_CONFIRM_TO_IMPORT_INTO_THE_SELECTED,"English-report.xlsx",2))
        compose.onNodeWithText(text(TextKey.PREVIEW_AND_CONFIRM_IMPORT)).performScrollTo().performClick()
        waitFor(text(TextKey.CONFIRM_IMPORT)); click(TextKey.CONFIRM_IMPORT)
        compose.waitUntil(20_000) { snapshot().records.size == 2 }
        assertEquals("000aB",snapshot().records.single { it.kind == ItemKind.PART }.bom)
        val machine = snapshot().records.single { it.kind == ItemKind.MACHINE }
        assertEquals("00-A-b2-C",machine.pn); assertEquals("000a-01",machine.sn)
        assertEquals("Line 1: A+B / 2\n中文, #3",machine.note)
    }
    @Test fun headerOnlyTemplateAndInvalidBackupDoNotImportOrLeaveAStalePreview() {
        launch(AppLanguage.ENGLISH)
        choose(file("template.csv") { InventoryImport.writeTemplate(it,AppLanguage.CHINESE) })
        waitFor(text(TextKey.FILE_READ_ROWS_CONFIRM_TO_IMPORT_INTO_THE_SELECTED,"template.csv",0))
        compose.onNodeWithText(text(TextKey.PREVIEW_AND_CONFIRM_IMPORT)).performScrollTo().assertIsNotEnabled()
        val csv = file("rows.csv") { it.write("Type,BOM,SN,Quantity,Condition,Note,PN\nMachine,,00a-01,1,Normal,Ready,00-A-B-C\n".toByteArray()) }
        choose(csv); waitFor(text(TextKey.FILE_READ_ROWS_CONFIRM_TO_IMPORT_INTO_THE_SELECTED,"rows.csv",1))
        val bytes = java.io.ByteArrayOutputStream().also { InventoryFiles.writeBackup(fixture(),it) }.toByteArray()
        val bad = JSONObject(bytes.toString(Charsets.UTF_8)).put("checksum","0".repeat(64)).toString()
        choose(file("bad.json") { it.write(bad.toByteArray()) })
        waitFor(text(TextKey.OK)); click(TextKey.OK)
        compose.onNodeWithText(text(TextKey.PREVIEW_AND_CONFIRM_IMPORT)).assertDoesNotExist()
        assertTrue(snapshot().records.isEmpty())
        choose(csv); waitFor(text(TextKey.FILE_READ_ROWS_CONFIRM_TO_IMPORT_INTO_THE_SELECTED,"rows.csv",1))
        compose.onNodeWithText(text(TextKey.PREVIEW_AND_CONFIRM_IMPORT)).performScrollTo().performClick()
        waitFor(text(TextKey.CONFIRM_IMPORT)); click(TextKey.CONFIRM_IMPORT)
        compose.waitUntil(20_000) { snapshot().records.size == 1 }
        assertEquals("00-A-B-C",snapshot().records.single().pn)
    }

    /** Customer files are pushed into app-private test storage, never bundled in the APK. */
    @Test fun providedDownloadsCanImportTheSame400RowsAndRestoreTheOriginalHistory() {
        assumeTrue("Optional downloaded samples", InstrumentationRegistry.getArguments().getString("downloadedSamples243") == "true")
        val dir = File(context.getExternalFilesDir(null),"compat-samples-243")
        val rows = File(dir,"sample.xlsx").inputStream().use { (InventoryImport.readFile(it,"sample.xlsx") as InventoryImportContent.Rows).rows }
        val backup = File(dir,"sample.json").inputStream().use { (InventoryImport.readFile(it,"sample.json") as InventoryImportContent.Backup).snapshot }
        assertTrue(File(dir,"template.csv").inputStream().use { (InventoryImport.readFile(it,"template.csv") as InventoryImportContent.Rows).rows }.isEmpty())
        assertEquals(400,rows.size); assertEquals(380,rows.count { it.kind == ItemKind.PART }); assertEquals(20,rows.count { it.kind == ItemKind.MACHINE })
        val expected = backup.records.filterNot { it.voided }.map { com.fieldstock.app.importdata.ImportRow(it.kind,it.bom,it.sn,it.quantity,it.condition,it.note,it.pn) }
        assertEquals(expected.groupingBy { it }.eachCount(),rows.groupingBy { it }.eachCount())
        InventoryRepository(context).use { repo ->
            repo.importRecords(rows.map { row -> RecordInput(base.tasks.first().id,warehouse,row.kind,row.bom,row.sn,row.quantity,row.condition,row.note,pn=row.pn) },DuplicateMode.NEW_ENTRY)
            assertEquals(400,repo.snapshot().records.size)
            assertEquals(20,repo.snapshot().records.count { it.kind == ItemKind.MACHINE && !it.voided })
        }
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        InventoryRepository(context).use { repo ->
            assertEquals(400,repo.restore(backup)); assertEquals(0,repo.restore(backup))
            assertEquals(backup.records.toSet(),repo.snapshot().records.toSet())
            assertEquals(backup.changes.toSet(),repo.snapshot().changes.toSet())
        }
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
    }
}
