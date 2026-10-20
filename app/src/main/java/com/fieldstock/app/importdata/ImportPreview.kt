package com.fieldstock.app.importdata

import com.fieldstock.app.model.*

data class ImportPreview(
    val rows: Int, val partsAdded: Long, val machinesAdded: Int, val pendingRows: Int,
    val voidedMachines: Int, val mergedParts: Int, val newEntries: Int,
    val partsAfter: Long, val machinesAfter: Int, val issue: String? = null,
) {
    fun description() = "文件共 $rows 行\n配件正式数量增加 $partsAdded 件\n整机正式数量增加 $machinesAdded 台\n文件中待核查 $pendingRows 行\n重复整机转作废 $voidedMachines 条\n配件并入已有条目 $mergedParts 行\n新增条目 $newEntries 条（含作废）\n导入后本仓：配件 $partsAfter 件，整机 $machinesAfter 台" +
        (issue?.let { "\n$it" } ?: "")

    companion object {
        fun calculate(snapshot: InventorySnapshot, rows: List<ImportRow>, warehouse: String, mode: DuplicateMode): ImportPreview {
            val active = snapshot.records.filter { !it.voided }
            val current = active.filter { it.warehouseId == warehouse }
            val serials = active.filter { it.kind == ItemKind.MACHINE && it.sn.isNotEmpty() }.mapTo(mutableSetOf()) { it.sn }
            val quantities = linkedMapOf<Pair<String, String>, Long>()
            current.filter { it.kind == ItemKind.PART && it.bom.isNotEmpty() }
                .sortedWith(compareBy<StockRecord> { it.createdAt }.thenBy { it.id })
                .forEach { quantities.putIfAbsent(it.bom to it.condition, it.quantity.toLong()) }
            var parts = 0L; var machines = 0; var duplicates = 0; var merged = 0; var entries = 0
            var issue: String? = if (snapshot.warehouses.none { it.id == warehouse }) "请选择有效的导入仓库" else null
            rows.forEach { row ->
                if (row.kind == ItemKind.PART) {
                    if (row.condition != "待核查") parts += row.quantity
                    val key = row.bom to row.condition
                    if (mode == DuplicateMode.ADD_QUANTITY && row.bom.isNotEmpty()) {
                        val old = quantities[key]
                        if (old == null) { quantities[key] = row.quantity.toLong(); entries++ }
                        else {
                            merged++; quantities[key] = old + row.quantity
                            if (old + row.quantity > 1_000_000_000L) issue = "BOM ${row.bom}（${row.condition}）合并后超过数量上限，请改选新增条目或修正文件"
                        }
                    } else entries++
                } else {
                    entries++
                    if (row.sn.isNotEmpty() && !serials.add(row.sn)) duplicates++
                    else if (row.condition != "待核查") machines++
                }
            }
            return ImportPreview(rows.size, parts, machines, rows.count { it.condition == "待核查" }, duplicates, merged, entries,
                current.filter { it.kind == ItemKind.PART && it.condition != "待核查" }.sumOf { it.quantity.toLong() } + parts,
                current.count { it.kind == ItemKind.MACHINE && it.condition != "待核查" } + machines, issue)
        }
    }
}
