package com.fieldstock.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.backup.AutomaticBackups
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.scan.ScanActivity
import com.fieldstock.app.scan.CodeBox
import com.fieldstock.app.scan.LiveTextLine
import com.fieldstock.app.model.ItemKind
import com.fieldstock.app.model.RecordInput
import com.fieldstock.app.model.StockRecord
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestName
import java.io.File

@RunWith(AndroidJUnit4::class)
class ManualWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val testName = TestName()
    private lateinit var context: Context
    private lateinit var warehouseId: String
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun startClean() {
        context = ApplicationProvider.getApplicationContext()
        context.databaseList().forEach { context.deleteDatabase(it) }
        context.getSharedPreferences("fieldstock", 0).edit().clear().commit()
        File(context.filesDir, "automatic-backups").deleteRecursively()
        File(context.cacheDir, "shared-backups").deleteRecursively()
        InventoryRepository(context).use {
            val country = it.addCountry("测试国")
            warehouseId = it.addWarehouse(country.id, "测试仓").id
        }
        // Keep the intended warehouse selected when a scope test also creates another warehouse.
        context.getSharedPreferences("fieldstock", 0).edit().putString("warehouse", warehouseId).commit()
    }
    @After fun close() {
        // Keep visual evidence for small-screen and keyboard layout review.
        capture(testName.methodName)
        scenario?.close()
    }
    private fun capture(label: String) {
        runCatching {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.cacheDir, "ui-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun launchAndEnterWarehouse() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("进入仓库").fetchSemanticsNodes().singleOrNull()
                ?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
        }
        compose.onNodeWithText("进入仓库").assertIsDisplayed().assertIsEnabled().performClick()
        awaitForm()
    }

    private fun awaitForm() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("BOM").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("BOM").assertIsDisplayed()
        compose.onNodeWithText("保存这一条").assertIsDisplayed()
    }

    private fun enter(label: String, text: String) {
        compose.onNodeWithText(label).assertIsDisplayed().performTextReplacement(text)
    }
    private fun save() { compose.onNodeWithText("保存这一条").assertIsDisplayed().assertIsEnabled().performClick() }
    private fun records(): List<StockRecord> = InventoryRepository(context).use { it.snapshot().records }
    private fun count(): Int = records().size
    private fun awaitBackups(expected: Int) {
        lateinit var model: InventoryViewModel
        scenario!!.onActivity { model = ViewModelProvider(it)[InventoryViewModel::class.java] }
        compose.waitUntil(15_000) { !model.busy && model.automaticBackupEntries.size == expected }
    }

    private fun seed(
        kind: ItemKind,
        bom: String,
        sn: String = "",
        quantity: Int = 1,
        voided: Boolean = false,
        warehouse: String = warehouseId,
        condition: String = "正常",
    ): StockRecord =
        InventoryRepository(context).use { repository ->
            val snapshot = repository.snapshot()
            val record = repository.save(RecordInput(
                taskId = snapshot.tasks.single().id,
                warehouseId = warehouse,
                kind = kind,
                bom = bom,
                sn = sn,
                quantity = quantity,
                condition = condition,
                note = "",
            ))
            if (voided) repository.voidRecord(record.id)
            repository.snapshot().records.single { it.id == record.id }
        }

    private fun openRecords() {
        compose.onNodeWithText("记录").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("正常").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("正常").onFirst().assertIsDisplayed()
    }

    private fun chooseNormalRecords() {
        // Normal status text can also appear in rows; the filter chip is first in composition order.
        compose.onAllNodesWithText("正常").onFirst().assertIsDisplayed().performClick()
    }

    private fun chooseVoidedRecords() {
        // The first exact match is the filter chip; active record rows also have a 作废 action.
        compose.onAllNodesWithText("作废").onFirst().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("一键清除作废").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun editableText(label: String): String? = compose.onAllNodesWithText(label)
        .fetchSemanticsNodes().firstOrNull { it.config.contains(SemanticsProperties.EditableText) }
        ?.config?.get(SemanticsProperties.EditableText)?.text

    /** A committed database row precedes snapshot refresh and form clearing on the main thread. */
    private fun awaitClearedReadyForm(vararg labels: String) {
        compose.waitUntil(10_000) {
            val buttons = compose.onAllNodesWithText("保存这一条").fetchSemanticsNodes()
            val enabled = buttons.singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
            enabled && labels.all { editableText(it) == "" }
        }
        labels.forEach { label -> assertEquals("保存成功后 $label 应已清空", "", editableText(label)) }
        compose.onNodeWithText("保存这一条").assertIsEnabled()
    }

    @Test fun partsPersistLeadingZeroAndAdditionalQuantityRequiresConfirmation() {
        launchAndEnterWarehouse()
        enter("BOM", "000123A"); enter("数量（件）", "10")
        compose.onNodeWithText("保存这一条").assertIsDisplayed()
        capture("parts-keyboard")
        save()
        compose.waitUntil(10_000) { count() == 1 }
        awaitClearedReadyForm("BOM", "数量（件）")
        enter("BOM", "000123A"); enter("数量（件）", "5"); save()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("相同配件如何入库？").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("相同配件如何入库？").assertIsDisplayed()
        assertEquals(1, count())
        compose.onNodeWithText("新增条目").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { count() == 2 }
        awaitClearedReadyForm("BOM", "数量（件）")
        assertEquals(15, records().sumOf { it.quantity })
        assertTrue(records().all { it.bom == "000123A" })
        scenario?.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("进入仓库").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("BOM").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("进入仓库").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("进入仓库").assertIsDisplayed().performClick()
        }
        awaitForm()
        assertEquals(2, count())
        openRecords()
        compose.onAllNodesWithText("000123A").onFirst().assertIsDisplayed()
    }

    @Test fun machineQuarantinesDuplicateAndAcceptsFifteenCharacterSn() {
        launchAndEnterWarehouse()
        compose.onNodeWithText("整机").assertIsDisplayed().performClick()
        capture("machine-form")
        enter("BOM", "000123A"); enter("SN 前段", "ABCDEFG"); enter("SN 后段", "1234567")
        compose.onNodeWithText("保存这一条").assertIsDisplayed()
        capture("machine-keyboard")
        save()
        compose.waitUntil(10_000) { count() == 1 }
        awaitClearedReadyForm("BOM", "SN 前段", "SN 后段")
        enter("BOM", "000123A"); enter("SN 前段", "ABCDEFG"); enter("SN 后段", "1234567"); save()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("发现重复 SN").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("发现重复 SN").assertIsDisplayed()
        assertEquals(1, count())
        compose.onNodeWithText("确认转入作废").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { count() == 2 && compose.onAllNodesWithText("重复 SN 已保留在作废页").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("重复 SN 已保留在作废页").assertIsDisplayed()
        assertEquals(1, records().count { it.voided })
        val record = records().single { !it.voided }
        assertEquals(ItemKind.MACHINE, record.kind)
        assertEquals("ABCDEFG-1234567", record.sn)
        assertEquals(1, record.quantity)
    }

    @Test fun voidedRecordCanBeRestoredFromVoidedTab() {
        val original = seed(ItemKind.PART, "VOID001", quantity = 8)
        launchAndEnterWarehouse()
        openRecords()
        // The last exact match belongs to the sole active row, after the filter chip.
        compose.onAllNodesWithText("作废").onLast().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("确认作废").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("确认作废").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { records().single().voided }
        chooseVoidedRecords()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("还原").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("VOID001").assertIsDisplayed()
        compose.onNodeWithText("还原").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { !records().single().voided }
        chooseNormalRecords()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("VOID001").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("VOID001").assertIsDisplayed()
        assertEquals(original.id, records().single().id)
        assertEquals(8, records().single().quantity)
    }

    @Test fun conflictingSnRestoreKeepsOriginalVoidedAndExistingMachineActive() {
        val voided = seed(ItemKind.MACHINE, "OLD001", "ABCD-000123", voided = true)
        val active = seed(ItemKind.MACHINE, "NEW001", "ABCD-000123")
        launchAndEnterWarehouse()
        openRecords()
        chooseVoidedRecords()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("还原").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("还原").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("SN 已存在，保持作废").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("SN 已存在，保持作废").assertIsDisplayed()
        compose.onNodeWithText("确认保留作废").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("重复 SN 已保留在作废页").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("知道了").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("重复 SN 已保留在作废页").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("OLD001").assertIsDisplayed()
        compose.onNodeWithText("还原").assertIsDisplayed()
        val after = records()
        assertEquals(2, after.size)
        assertTrue(after.single { it.id == voided.id }.voided)
        assertFalse(after.single { it.id == active.id }.voided)
        assertEquals(1, after.count { !it.voided && it.sn == "ABCD-000123" })
    }

    @Test fun deletingVoidedRowRequiresConfirmationAndPreservesActiveRecords() {
        val voided = seed(ItemKind.PART, "REMOVE001", quantity = 4, voided = true)
        val active = seed(ItemKind.PART, "KEEP001", quantity = 7)
        launchAndEnterWarehouse()
        openRecords()
        chooseVoidedRecords()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("删除").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("删除").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("确认删除").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, count())
        compose.onNodeWithText("确认删除").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { count() == 1 }
        assertTrue(records().none { it.id == voided.id })
        assertEquals(active.id, records().single().id)
        assertFalse(records().single().voided)
        assertEquals(7, records().single().quantity)
        chooseNormalRecords()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("KEEP001").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("KEEP001").assertIsDisplayed()
    }

    @Test fun clearingVoidedRecordsRequiresConfirmationAndKeepsActiveInventory() {
        seed(ItemKind.PART, "CLEAR001", quantity = 3, voided = true)
        seed(ItemKind.MACHINE, "CLEAR002", "CLEAR-000002", voided = true)
        val active = seed(ItemKind.MACHINE, "KEEP002", "KEEP-000002")
        launchAndEnterWarehouse()
        openRecords()
        chooseVoidedRecords()
        compose.onNodeWithText("一键清除作废").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("确认清除").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(3, count())
        compose.onNodeWithText("确认清除").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { count() == 1 }
        assertEquals(active.id, records().single().id)
        assertFalse(records().single().voided)
        chooseNormalRecords()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("KEEP002").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("KEEP002").assertIsDisplayed()
    }

    @Test fun clearingNormalRequiresTwoConfirmationsAndOnlyClearsCurrentWarehouseNonVoidedRows() {
        val otherWarehouse = InventoryRepository(context).use { repository ->
            repository.addWarehouse(repository.snapshot().countries.single().id, "其他仓").id
        }
        val normal = seed(ItemKind.PART, "CURNORMAL", quantity = 5)
        val damaged = seed(ItemKind.PART, "CURDAMAGED", quantity = 2, condition = "损坏")
        val pending = seed(ItemKind.PART, "CURPENDING", quantity = 1, condition = "待核查")
        val currentVoided = seed(ItemKind.PART, "CURVOID", quantity = 3, voided = true)
        val otherActive = seed(ItemKind.PART, "OTHERLIVE", quantity = 12, warehouse = otherWarehouse)
        val otherVoided = seed(ItemKind.PART, "OTHVOID", quantity = 4, voided = true, warehouse = otherWarehouse)
        launchAndEnterWarehouse()
        openRecords()
        compose.onNodeWithText("一键清空正常").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("继续清空").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("首次确认前不可删除数据", 6, count())
        compose.onNodeWithText("继续清空").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("确认清空正常").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("第一次确认后，第二次确认前不可删除数据", 6, count())
        compose.onNodeWithText("确认清空正常").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { count() == 3 }
        val after = records()
        assertEquals(setOf(currentVoided.id, otherActive.id, otherVoided.id), after.map { it.id }.toSet())
        assertTrue(after.none { it.id in setOf(normal.id, damaged.id, pending.id) })
        assertTrue(after.single { it.id == currentVoided.id }.voided)
        assertFalse(after.single { it.id == otherActive.id }.voided)
        assertTrue(after.single { it.id == otherVoided.id }.voided)
        assertEquals(12, after.single { it.id == otherActive.id }.quantity)
        chooseVoidedRecords()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("CURVOID").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("CURVOID").assertIsDisplayed()
    }

    @Test fun choosingAddQuantityUpdatesExistingPartInsteadOfCreatingAnotherRow() {
        val original = seed(ItemKind.PART, "ADD001", quantity = 10)
        launchAndEnterWarehouse()
        enter("BOM", "ADD001"); enter("数量（件）", "5"); save()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("添加到已有数量").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(10, records().single().quantity)
        compose.onNodeWithText("添加到已有数量").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { records().singleOrNull()?.quantity == 15 }
        awaitClearedReadyForm("BOM", "数量（件）")
        assertEquals(original.id, records().single().id)
    }

    @Test fun multiSelectCanMergeMatchingPartsThenBatchVoidTheMergedRecord() {
        seed(ItemKind.PART, "MERGE001", quantity = 3)
        seed(ItemKind.PART, "MERGE001", quantity = 4)
        launchAndEnterWarehouse()
        openRecords()
        compose.onAllNodesWithText("MERGE001").onFirst().performTouchInput { longClick() }
        compose.onNodeWithText("全选").assertIsDisplayed().performClick()
        compose.onNodeWithText("合并配件").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("确认合并").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("确认合并").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { count() == 1 && records().single().quantity == 7 }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("多选").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("多选").assertIsDisplayed().performClick()
        compose.onNodeWithText("全选").assertIsDisplayed().performClick()
        compose.onNodeWithText("批量作废").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("确认批量作废").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("确认批量作废").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { records().single().voided }
        assertEquals(7, records().single().quantity)
    }

    private fun openSettings() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("删除当前仓库").performScrollTo().assertIsDisplayed()
    }

    @Test fun deletingWarehouseNamesTargetAndWaitsForSecondConfirmation() {
        val removed = seed(ItemKind.PART, "REMOVE", quantity = 3, voided = true)
        val other = InventoryRepository(context).use { it.addWarehouse(it.snapshot().countries.single().id, "保留仓").id }
        val keep = seed(ItemKind.PART, "KEEP", warehouse = other)
        launchAndEnterWarehouse(); openSettings()
        compose.onNodeWithText("删除当前仓库").performClick()
        compose.onNodeWithText("国家：测试国", substring = true).assertIsDisplayed()
        compose.onNodeWithText("仓库：测试仓", substring = true).assertIsDisplayed()
        assertEquals(2, count())
        compose.onNodeWithText("继续删除").performClick()
        compose.onNodeWithText("仓库：测试仓", substring = true).assertIsDisplayed()
        assertEquals(2, count())
        compose.onNodeWithText("确认删除仓库").performClick()
        compose.waitUntil(10_000) { records().map { it.id } == listOf(keep.id) }
        InventoryRepository(context).use {
            assertTrue(it.snapshot().warehouses.none { w -> w.id == warehouseId })
            assertTrue(it.snapshot().changes.none { log -> log.recordId == removed.id })
        }
    }

    @Test fun deletingCountryRemovesAllItsWarehousesOnlyAfterTwoConfirmations() {
        val second = InventoryRepository(context).use { it.addWarehouse(it.snapshot().countries.single().id, "测试二仓").id }
        seed(ItemKind.PART, "FIRST"); seed(ItemKind.PART, "SECOND", warehouse = second, voided = true)
        val other = InventoryRepository(context).use { it.addWarehouse(it.addCountry("保留国").id, "保留仓").id }
        val keep = seed(ItemKind.PART, "KEEP", warehouse = other)
        launchAndEnterWarehouse(); openSettings()
        compose.onNodeWithText("删除当前国家").performScrollTo().performClick()
        compose.onNodeWithText("国家：测试国", substring = true).assertIsDisplayed()
        assertEquals(3, count())
        compose.onNodeWithText("继续删除").performClick()
        compose.onNodeWithText("测试二仓", substring = true).assertIsDisplayed()
        assertEquals(3, count())
        compose.onNodeWithText("确认删除国家").performClick()
        compose.waitUntil(10_000) { records().map { it.id } == listOf(keep.id) }
        InventoryRepository(context).use { assertEquals(listOf("保留国"), it.snapshot().countries.map { country -> country.name }) }
    }

    @Test fun mergingWarehousesAddsMatchingPartsKeepsConditionsAndMovesArchivesAfterTwoConfirmations() {
        val target = InventoryRepository(context).use { it.addWarehouse(it.snapshot().countries.single().id, "目标仓").id }
        seed(ItemKind.PART, "SAME", quantity = 3)
        seed(ItemKind.PART, "SAME", quantity = 2, condition = "损坏")
        val archived = seed(ItemKind.MACHINE, "MODEL", "AA-001", voided = true)
        seed(ItemKind.MACHINE, "MODEL", "AA-001", warehouse = target)
        seed(ItemKind.PART, "SAME", quantity = 4, warehouse = target)
        launchAndEnterWarehouse(); openSettings()
        compose.onNodeWithText("合并当前仓库").performScrollTo().performClick()
        compose.onNodeWithText("选择目标仓库 ▾").performClick()
        compose.onNodeWithText("目标仓").performClick()
        compose.onNodeWithText("添加到已有数量").performScrollTo().performClick()
        assertEquals(5, count())
        compose.onNodeWithText("继续合并").performClick()
        compose.onNodeWithText("源仓库：测试仓", substring = true).assertIsDisplayed()
        compose.onNodeWithText("目标仓库：目标仓", substring = true).assertIsDisplayed()
        assertEquals(5, count())
        compose.onNodeWithText("确认合并仓库").performClick()
        compose.waitUntil(10_000) { count() == 4 && records().all { it.warehouseId == target } }
        assertEquals(7, records().single { it.kind == ItemKind.PART && it.condition == "正常" }.quantity)
        assertEquals(2, records().single { it.condition == "损坏" }.quantity)
        assertTrue(records().single { it.id == archived.id }.voided)
        InventoryRepository(context).use { assertEquals(0, it.restore(it.snapshot())) }
    }

    @Test fun csvImportUsesChosenCountryWarehouseAndQuarantinesBatchDuplicatesTogether() {
        seed(ItemKind.MACHINE, "OLDMODEL", "AA-001")
        val target = InventoryRepository(context).use { it.addWarehouse(it.addCountry("导入国").id, "导入仓").id }
        val original = seed(ItemKind.PART, "000123A", quantity = 4, warehouse = target)
        launchAndEnterWarehouse()
        val csv = File(context.cacheDir, "test-import.csv").apply { writeText(
            "\uFEFF类型,BOM,SN,数量,状态,备注\r\n配件,000123A,,3,正常,追加\r\n配件,000123A,,2,损坏,分开\r\n整机,MODEL,AA-001,1,正常,\r\n整机,MODEL,BB-002,1,正常,\r\n整机,MODEL,BB-002,1,正常,\r\n", Charsets.UTF_8) }
        scenario!!.onActivity { ViewModelProvider(it)[InventoryViewModel::class.java].previewImport(Uri.fromFile(csv)) }
        compose.onNodeWithText("处理").performClick()
        compose.onNodeWithText("导入").performClick()
        compose.onNodeWithText("测试国 ▾").performScrollTo().performClick()
        compose.onNodeWithText("导入国").performClick()
        compose.onNodeWithText("选择为导入目标").performScrollTo().performClick()
        compose.onNodeWithText("导入目标：导入国 / 导入仓").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("添加到已有数量").performScrollTo().performClick()
        compose.onNodeWithText("预览并确认导入").performScrollTo().performClick()
        compose.onAllNodesWithText("目标：导入国 / 导入仓", substring = true).onLast().assertIsDisplayed()
        assertEquals(2, count())
        compose.onNodeWithText("确认导入").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { count() == 6 && compose.onAllNodesWithText("重复 SN 已保留在作废页").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, records().count { it.voided })
        assertEquals(7, records().single { it.id == original.id }.quantity)
        assertTrue(records().filter { it.id != original.id && it.bom != "OLDMODEL" }.all { it.warehouseId == target })
        compose.onNodeWithText("知道了").assertIsDisplayed().performClick()
        compose.onNodeWithText("盘点").performClick()
        compose.onNodeWithText("测试国 / 测试仓").assertIsDisplayed()
    }

    @Test fun repeatedCsvShowsQuantityPreviewAndCanBeUndoneAsOneOperation() {
        val original = seed(ItemKind.PART, "SUM001", quantity = 4)
        launchAndEnterWarehouse()
        val content = "类型,BOM,SN,数量,状态,备注\n配件,SUM001,,3,正常,\n整机,MODEL,AA-001,1,正常,\n整机,MODEL,AA-001,1,正常,\n"
        val first = File(context.cacheDir, "first.csv").apply { writeText(content, Charsets.UTF_8) }
        scenario!!.onActivity { ViewModelProvider(it)[InventoryViewModel::class.java].previewImport(Uri.fromFile(first)) }
        compose.onNodeWithText("处理").performClick(); compose.onNodeWithText("导入").performClick()
        compose.onNodeWithText("添加到已有数量").performScrollTo().performClick()
        compose.onNodeWithText("预览并确认导入").performScrollTo().performClick()
        compose.onAllNodesWithText("配件正式数量增加 3 件", substring = true).onLast().assertIsDisplayed()
        assertEquals(4, records().single().quantity)
        capture("quantity-preview")
        compose.onNodeWithText("确认导入").performClick()
        compose.waitUntil(15_000) { count() == 3 && compose.onAllNodesWithText("重复 SN 已保留在作废页").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("知道了").performClick()
        val renamed = File(context.cacheDir, "renamed.csv").apply { writeText("\uFEFF" + content.replace("\n", "\r\n"), Charsets.UTF_8) }
        scenario!!.onActivity { ViewModelProvider(it)[InventoryViewModel::class.java].previewImport(Uri.fromFile(renamed)) }
        compose.onNodeWithText("预览并确认导入").performScrollTo().performClick()
        compose.onNodeWithText("这个文件内容曾导入过").assertIsDisplayed()
        assertEquals(7, records().single { it.id == original.id }.quantity)
        capture("repeat-import-warning")
        compose.onNodeWithText("仍要重复导入").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { count() == 5 && compose.onAllNodesWithText("知道了").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("知道了").performClick()
        assertEquals(10, records().single { it.id == original.id }.quantity)
        openSettings()
        compose.onNodeWithText("撤销重要操作").performScrollTo().performClick()
        compose.onAllNodesWithText("撤销这次操作").onFirst().performClick()
        compose.onNodeWithText("确认撤销").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { count() == 3 && records().single { it.id == original.id }.quantity == 7 }
        InventoryRepository(context).use { assertEquals(1, it.importReceipts().size); assertTrue(it.recoveryPoints().first().undone) }
    }

    @Test fun lastCountryDeletionCanBeUndoneDirectlyFromWarehouseChooser() {
        val original = seed(ItemKind.PART, "RESTORE001", quantity = 9)
        launchAndEnterWarehouse(); openSettings()
        compose.onNodeWithText("删除当前国家").performScrollTo().performClick()
        compose.onNodeWithText("继续删除").performClick(); compose.onNodeWithText("确认删除国家").performClick()
        compose.waitUntil(15_000) { count() == 0 && compose.onAllNodesWithText("选择盘点仓库").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("撤销重要操作").performScrollTo().performClick()
        compose.onNodeWithText("撤销这次操作").performClick()
        compose.onNodeWithText("确认撤销").performClick()
        compose.waitUntil(15_000) { records().singleOrNull()?.id == original.id }
        assertEquals(9, records().single().quantity)
        InventoryRepository(context).use { assertEquals("测试国", it.snapshot().countries.single().name) }
    }

    @Test fun vibrationAndSoundPreferencesPersistAndFeedbackPreviewDoesNotCreateInventory() {
        launchAndEnterWarehouse(); openSettings()
        compose.onNodeWithContentDescription("保存震动").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithContentDescription("提示声音").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("试成功提示").performScrollTo().performClick()
        compose.onNodeWithText("试重复提示").performScrollTo().performClick()
        assertEquals(0, count())
        scenario!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("保存震动").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("保存震动").performScrollTo().assertIsOff()
        compose.onNodeWithContentDescription("提示声音").performScrollTo().assertIsOn()
    }

    @Test fun localBackupCanRestoreAfterDeletingLastCountry() {
        val active = seed(ItemKind.PART, "BACKUP023", quantity = 8)
        val archived = seed(ItemKind.MACHINE, "M023", "AA-023", voided = true)
        launchAndEnterWarehouse()
        openSettings()
        compose.onNodeWithContentDescription("每日自动完整备份").performScrollTo().assertIsOn()
        compose.onNodeWithText("立即备份").performScrollTo().performClick()
        awaitBackups(2)
        capture("automatic-backup-settings")
        compose.onNodeWithText("删除当前国家").performScrollTo().performClick()
        compose.onNodeWithText("继续删除").performClick()
        compose.onNodeWithText("确认删除国家").performClick()
        compose.waitUntil(15_000) { count() == 0 && compose.onAllNodesWithText("选择盘点仓库").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("查看本机备份").performScrollTo().performClick()
        capture("automatic-backup-list")
        compose.onAllNodesWithText("恢复").onFirst().performClick()
        compose.onNodeWithText("合并这个备份？").assertIsDisplayed()
        compose.onNodeWithText("检查并合并").performClick()
        compose.waitUntil(15_000) { count() == 2 }
        assertEquals(8, records().single { it.id == active.id }.quantity)
        assertTrue(records().single { it.id == archived.id }.voided)
        InventoryRepository(context).use { assertTrue(it.snapshot().changes.any { change -> change.recordId == active.id }) }
    }

    @Test fun automaticBackupCanBeDisabledAndManualBackupStillWorksAfterRecreation() {
        seed(ItemKind.PART, "MANUAL023", quantity = 3)
        launchAndEnterWarehouse(); openSettings()
        compose.onNodeWithContentDescription("每日自动完整备份").performScrollTo().assertIsOn().performClick().assertIsOff()
        scenario!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("每日自动完整备份").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("每日自动完整备份").performScrollTo().assertIsOff()
        val backups = AutomaticBackups(File(context.filesDir, "automatic-backups"))
        assertEquals(1, backups.entries().size)
        compose.onNodeWithText("立即备份").performScrollTo().performClick()
        awaitBackups(2)
        assertEquals(3, backups.read(backups.entries().first().id).records.single().quantity)
        assertEquals(1, count())
    }

    @Test fun backupFailureDoesNotBlockSavingAndCanBeRetried() {
        val blocked = File(context.filesDir, "automatic-backups").apply { writeText("blocked for IO failure test") }
        launchAndEnterWarehouse()
        enter("BOM", "SAVE023"); enter("数量（件）", "6"); save()
        compose.waitUntil(10_000) { count() == 1 }
        openSettings()
        compose.onNodeWithText("完整备份未完成", substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(blocked.delete())
        compose.onNodeWithText("立即备份").performScrollTo().performClick()
        val backups = AutomaticBackups(blocked)
        awaitBackups(1)
        assertEquals(6, backups.read(backups.entries().first().id).records.single().quantity)
        compose.onNodeWithText("完整备份未完成", substring = true).assertDoesNotExist()
        assertEquals(1, count())
    }

    @Test fun sharedBackupUsesReadableContentUriAndKeepsInventory() {
        val saved = seed(ItemKind.PART, "SHARE023", quantity = 4)
        launchAndEnterWarehouse()
        val entry = AutomaticBackups(File(context.filesDir, "automatic-backups")).entries().single()
        var shared: Uri? = null
        scenario!!.onActivity { activity ->
            ViewModelProvider(activity)[InventoryViewModel::class.java].shareAutomaticBackup(entry.id) { shared = it }
        }
        compose.waitUntil(15_000) { shared != null }
        assertEquals("content", shared!!.scheme)
        assertEquals("com.fieldstock.app.files", shared!!.authority)
        val data = context.contentResolver.openInputStream(shared!!)!!.use(InventoryFiles::readBackup)
        assertEquals(saved.id, data.records.single().id)
        assertEquals(4, data.records.single().quantity)
        assertEquals(1, count())
        val firstUri = shared!!
        seed(ItemKind.PART, "SHARELATER", quantity = 2)
        scenario!!.onActivity { activity -> ViewModelProvider(activity)[InventoryViewModel::class.java].checkDailyBackup(force = true) }
        val backups = AutomaticBackups(File(context.filesDir, "automatic-backups"))
        awaitBackups(2)
        val newer = backups.entries().first()
        shared = null
        scenario!!.onActivity { activity -> ViewModelProvider(activity)[InventoryViewModel::class.java].shareAutomaticBackup(newer.id) { shared = it } }
        compose.waitUntil(15_000) { shared != null }
        assertEquals(2, context.contentResolver.openInputStream(shared!!)!!.use(InventoryFiles::readBackup).records.size)
        assertEquals(saved.id, context.contentResolver.openInputStream(firstUri)!!.use(InventoryFiles::readBackup).records.single().id)
        assertEquals(2, count())
    }

    @Test fun savingSelectedAutomaticBackupUsesItsSnapshotInsteadOfCurrentInventory() {
        val original = seed(ItemKind.PART, "OLD023", quantity = 5)
        launchAndEnterWarehouse()
        val entry = AutomaticBackups(File(context.filesDir, "automatic-backups")).entries().single()
        seed(ItemKind.PART, "NEW023", quantity = 2)
        var ready = false
        scenario!!.onActivity { activity ->
            ViewModelProvider(activity)[InventoryViewModel::class.java].prepareExport("backup", false, automaticId = entry.id) { ready = true }
        }
        compose.waitUntil(15_000) { ready }
        val output = File(context.cacheDir, "selected-automatic-backup.json")
        scenario!!.onActivity { activity -> ViewModelProvider(activity)[InventoryViewModel::class.java].export(Uri.fromFile(output)) }
        compose.waitUntil(15_000) { output.exists() && !context.getSharedPreferences("fieldstock", 0).contains("pending_kind") }
        val data = output.inputStream().use(InventoryFiles::readBackup)
        assertEquals(original.id, data.records.single().id)
        assertEquals(5, data.records.single().quantity)
        assertEquals(2, count())
        output.delete()
    }

    private fun provideLiveLabels(field: String, codes: List<String>) {
        var scanner: ScanActivity? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitUntil(10_000) {
            instrumentation.runOnMainSync {
                scanner = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ScanActivity>().singleOrNull()
            }
            scanner != null
        }
        val crop = Bitmap.createBitmap(634, 380, Bitmap.Config.ARGB_8888)
        val scene = Bitmap.createBitmap(720, 500, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 36f; typeface = Typeface.MONOSPACE }
        Canvas(crop).apply {
            drawColor(Color.WHITE)
            codes.forEachIndexed { index, code -> drawText("$field: $code", 24f, 75f + index * 114f, paint) }
        }
        Canvas(scene).apply { drawColor(Color.DKGRAY); drawBitmap(crop, 43f, 60f, null) }
        val lines = codes.mapIndexed { index, code ->
            LiveTextLine("$field: $code", CodeBox(0.04f, 0.1f + index * 0.25f, 0.9f, 0.2f + index * 0.25f))
        }
        instrumentation.runOnMainSync {
            scanner!!.changeScanMode(true)
            repeat(2) { scanner!!.publishLiveRecognition(scene, crop, codes.joinToString("\n") { "$field: $it" }, lines) }
            scanner!!.pauseOrResumeLive()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("画面编号 2 ${codes[1]}").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun continuousBomSelectionReturnsToFormAndOnlySaveCreatesInventory() {
        launchAndEnterWarehouse()
        compose.onNodeWithText("扫描 BOM").performClick()
        provideLiveLabels("BOM", listOf("000123A", "000456B", "000789C"))
        assertEquals(0, count())
        compose.onNodeWithContentDescription("画面编号 2 000456B").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("最终 BOM 编号").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, count())
        compose.onNodeWithText("确认并填入").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("保存这一条").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("000456B", compose.onNodeWithText("BOM").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        enter("数量（件）", "7"); save()
        compose.waitUntil(10_000) { count() == 1 }
        assertEquals("000456B", records().single().bom)
        assertEquals(7, records().single().quantity)
        assertEquals("ocr", records().single().sourceBom)
    }

    @Test fun continuousSnSelectionFillsBothSegmentsAndKeepsTheEnteredBom() {
        launchAndEnterWarehouse()
        compose.onNodeWithText("整机").performClick()
        enter("BOM", "MODEL024")
        compose.onNodeWithText("扫描 SN").performClick()
        provideLiveLabels("SN", listOf("AA-001", "ABCDEFG-1234567", "ZZ-009"))
        compose.onNodeWithContentDescription("画面编号 2 ABCDEFG-1234567").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("最终 SN 编号").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, count())
        compose.onNodeWithText("确认并填入").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("SN 前段").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("MODEL024", compose.onNodeWithText("BOM").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("ABCDEFG", compose.onNodeWithText("SN 前段").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("1234567", compose.onNodeWithText("SN 后段").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        save()
        compose.waitUntil(10_000) { count() == 1 }
        assertEquals("ABCDEFG-1234567", records().single().sn)
        assertEquals("MODEL024", records().single().bom)
        assertEquals("ocr", records().single().sourceSn)
    }
}
