package com.fieldstock.app.data

import com.fieldstock.app.model.*

/** Revert only rows touched by the operation. Later unrelated rows remain intact. */
internal object SnapshotUndo {
    fun <T> revertRows(label: String, before: List<T>, after: List<T>, current: List<T>, id: (T) -> String): List<T> {
        val a = before.associateBy(id)
        val b = after.associateBy(id)
        val live = current.associateByTo(linkedMapOf(), id)
        val changed = (a.keys + b.keys).filter { a[it] != b[it] }
        require(changed.all { live[it] == b[it] }) { "${label}在这次操作后又有改动，未撤销。请先撤销后续相关操作，其他数据保持。" }
        changed.forEach { key -> a[key]?.let { live[key] = it } ?: live.remove(key) }
        return live.values.toList()
    }

    fun prepare(before: InventorySnapshot, after: InventorySnapshot, current: InventorySnapshot) = InventorySnapshot(
        tasks = revertRows("兼容分组", before.tasks, after.tasks, current.tasks, InventoryTask::id),
        countries = revertRows("国家", before.countries, after.countries, current.countries, Country::id),
        warehouses = revertRows("仓库", before.warehouses, after.warehouses, current.warehouses, Warehouse::id),
        records = revertRows("库存记录", before.records, after.records, current.records, StockRecord::id),
        changes = revertRows("修改历史", before.changes, after.changes, current.changes, ChangeLog::id),
    )
}
