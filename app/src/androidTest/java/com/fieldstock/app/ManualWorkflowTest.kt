package com.fieldstock.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldstock.app.data.InventoryRepository
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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("核对后保存").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("核对后保存").assertIsDisplayed()
        assertEquals(1, count())
        compose.onNodeWithText("确认保存").assertIsDisplayed().performClick()
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

    @Test fun machineRejectsDuplicateAndAcceptsFifteenCharacterSn() {
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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("SN 已经录入").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("SN 已经录入").assertIsDisplayed()
        assertEquals(1, count())
        val record = records().single()
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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("SN 已存在，未还原").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("SN 已存在，未还原").assertIsDisplayed()
        compose.onNodeWithText("保留作废").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("SN 已存在，未还原").fetchSemanticsNodes().isEmpty() }
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
}
