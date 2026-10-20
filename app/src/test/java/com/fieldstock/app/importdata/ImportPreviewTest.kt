package com.fieldstock.app.importdata

import com.fieldstock.app.model.*
import org.junit.Assert.*
import org.junit.Test

class ImportPreviewTest {
    private val warehouse = Warehouse("wh", "country", "仓库")
    private fun record(id: String, kind: ItemKind, quantity: Int = 1, sn: String = "", condition: String = "正常", warehouseId: String = "wh") =
        StockRecord(id, "task", warehouseId, kind, "000A", sn, quantity, condition, "", "", "manual", "manual", "", "", 1, 1, false)
    private val header = "类型,BOM,SN,数量,状态,备注\n"

    @Test fun previewMatchesConfirmedQuantitiesAndCountsBothGlobalAndBatchSerialDuplicates() {
        val snapshot = InventorySnapshot(warehouses = listOf(warehouse), records = listOf(record("part", ItemKind.PART, 4), record("machine", ItemKind.MACHINE, sn = "AA-001", warehouseId = "other")))
        val rows = listOf(ImportRow(ItemKind.PART, "000A", "", 6, "正常", ""), ImportRow(ItemKind.PART, "000A", "", 2, "损坏", ""),
            ImportRow(ItemKind.MACHINE, "000A", "AA-001", 1, "正常", ""), ImportRow(ItemKind.MACHINE, "000A", "BB-002", 1, "正常", ""),
            ImportRow(ItemKind.MACHINE, "000A", "BB-002", 1, "正常", ""), ImportRow(ItemKind.PART, "", "", 8, "待核查", "未知"))
        val p = ImportPreview.calculate(snapshot, rows, "wh", DuplicateMode.ADD_QUANTITY)
        assertEquals(8L, p.partsAdded); assertEquals(1, p.machinesAdded); assertEquals(2, p.voidedMachines)
        assertEquals(12L, p.partsAfter); assertEquals(1, p.machinesAfter); assertEquals(1, p.mergedParts); assertEquals(5, p.newEntries)
        assertEquals(1, p.pendingRows); assertNull(p.issue)
    }

    @Test fun newEntryModeKeepsRowsAndAddQuantityModePredictsOverflow() {
        val snapshot = InventorySnapshot(warehouses = listOf(warehouse), records = listOf(record("part", ItemKind.PART, 1_000_000_000)))
        val rows = listOf(ImportRow(ItemKind.PART, "000A", "", 1, "正常", ""))
        assertNotNull(ImportPreview.calculate(snapshot, rows, "wh", DuplicateMode.ADD_QUANTITY).issue)
        val p = ImportPreview.calculate(snapshot, rows, "wh", DuplicateMode.NEW_ENTRY)
        assertNull(p.issue); assertEquals(1_000_000_001L, p.partsAfter); assertEquals(1, p.newEntries)
    }

    @Test fun sameContentWithDifferentFileNamesBomAndRecordLineEndingsHasSameFingerprint() {
        val a = InventoryImport.read((header + "配件,000A,,3,正常,\n").byteInputStream(), "a.csv")
        val b = InventoryImport.read(("\uFEFF" + (header + "配件,000A,,3,正常,\n").replace("\n", "\r\n")).byteInputStream(), "renamed.csv")
        assertEquals(InventoryImport.fingerprint(a), InventoryImport.fingerprint(b))
        assertNotEquals(InventoryImport.fingerprint(a), InventoryImport.fingerprint(a.map { it.copy(quantity = 4) }))
    }

    @Test fun fingerprintSeparatesValuesAndPreservesCodeCaseAndRowOrder() {
        val a = ImportRow(ItemKind.PART, "000A", "", 3, "正常", "a,b")
        val b = a.copy(bom = "000a", note = "b,a")
        assertNotEquals(InventoryImport.fingerprint(listOf(a)), InventoryImport.fingerprint(listOf(b)))
        assertNotEquals(InventoryImport.fingerprint(listOf(a, b)), InventoryImport.fingerprint(listOf(b, a)))
    }
}
