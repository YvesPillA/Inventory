package com.fieldstock.app.importdata

import com.fieldstock.app.i18n.*

import com.fieldstock.app.model.*

data class ImportPreview(
    val rows: Int, val partsAdded: Long, val machinesAdded: Int, val pendingRows: Int,
    val voidedMachines: Int, val mergedParts: Int, val newEntries: Int,
    val partsAfter: Long, val machinesAfter: Int, val issue: String? = null,
) {
    fun description() = tr(TextKey.FILE_ROWS_CONFIRMED_PARTS_ADDED_PCS_CONFIRMED_MACHINES_ADDED, rows, partsAdded, machinesAdded, pendingRows, voidedMachines, mergedParts, newEntries, partsAfter, machinesAfter) +
        (issue?.let { "\n$it" } ?: "")

    companion object {
        fun calculate(snapshot: InventorySnapshot, rows: List<ImportRow>, warehouse: String, mode: DuplicateMode): ImportPreview {
            val active = snapshot.records.filter { !it.voided }
            val current = active.filter { it.warehouseId == warehouse }
            val serials = active.filter { it.kind == ItemKind.MACHINE && it.sn.isNotEmpty() }.mapTo(mutableSetOf()) { it.sn }
            val quantities = linkedMapOf<Pair<String, String>, Long>()
            val noteLengths = linkedMapOf<Pair<String, String>, Int>()
            current.filter { it.kind == ItemKind.PART && it.bom.isNotEmpty() }
                .sortedWith(compareBy<StockRecord> { it.createdAt }.thenBy { it.id })
                .forEach {
                    quantities.putIfAbsent(it.bom to it.condition, it.quantity.toLong())
                    noteLengths.putIfAbsent(it.bom to it.condition, it.note.length)
                }
            var parts = 0L; var machines = 0; var duplicates = 0; var merged = 0; var entries = 0
            var issue: String? = if (snapshot.warehouses.none { it.id == warehouse }) tr(TextKey.CHOOSE_A_VALID_IMPORT_WAREHOUSE) else null
            rows.forEach { row ->
                if (row.kind == ItemKind.PART) {
                    if (row.condition != StockCondition.PENDING) parts += row.quantity
                    val key = row.bom to row.condition
                    if (mode == DuplicateMode.ADD_QUANTITY && row.bom.isNotEmpty()) {
                        val old = quantities[key]
                        if (old == null) { quantities[key] = row.quantity.toLong(); noteLengths[key] = row.note.trim().length; entries++ }
                        else {
                            merged++; quantities[key] = old + row.quantity
                            val note = row.note.trim()
                            if (note.isNotEmpty()) {
                                val before = noteLengths[key] ?: 0
                                val detail = tr(TextKey.MERGED_SOURCE_PCS, row.quantity).length + 1 + note.length
                                // Once invalid, retain only the length sentinel instead of building
                                // an ever-growing note on the UI thread for a large duplicate batch.
                                noteLengths[key] = minOf(4001, before + (if (before > 0) 1 else 0) + detail)
                            }
                            if (old + row.quantity > 1_000_000_000L) issue = issue ?: tr(TextKey.BOM_EXCEEDS_THE_QUANTITY_LIMIT_AFTER_MERGING_CHOOSE_NEW, row.bom, conditionLabel(row.condition))
                            if ((noteLengths[key] ?: 0) > 4000) issue = issue ?: tr(TextKey.BOM_EXCEEDS_NOTE_LIMIT_AFTER_MERGING_CHOOSE_NEW, row.bom, conditionLabel(row.condition))
                        }
                    } else entries++
                } else {
                    entries++
                    if (row.sn.isNotEmpty() && !serials.add(row.sn)) duplicates++
                    else if (row.condition != StockCondition.PENDING) machines++
                }
            }
            return ImportPreview(rows.size, parts, machines, rows.count { it.condition == StockCondition.PENDING }, duplicates, merged, entries,
                current.filter { it.kind == ItemKind.PART && it.condition != StockCondition.PENDING }.sumOf { it.quantity.toLong() } + parts,
                current.count { it.kind == ItemKind.MACHINE && it.condition != StockCondition.PENDING } + machines, issue)
        }
    }
}
