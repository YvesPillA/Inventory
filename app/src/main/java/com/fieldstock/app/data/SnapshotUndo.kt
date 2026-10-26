package com.fieldstock.app.data

import com.fieldstock.app.i18n.*

import com.fieldstock.app.model.*

/** Revert only rows touched by the operation. Later unrelated rows remain intact. */
internal object SnapshotUndo {
    fun <T> revertRows(label: String, before: List<T>, after: List<T>, current: List<T>, id: (T) -> String): List<T> {
        val a = before.associateBy(id)
        val b = after.associateBy(id)
        val live = current.associateByTo(linkedMapOf(), id)
        val changed = (a.keys + b.keys).filter { a[it] != b[it] }
        require(changed.all { live[it] == b[it] }) { tr(TextKey.CHANGED_AFTER_THIS_OPERATION_NOTHING_WAS_UNDONE_UNDO_LATER, label) }
        changed.forEach { key -> a[key]?.let { live[key] = it } ?: live.remove(key) }
        return live.values.toList()
    }

    fun prepare(before: InventorySnapshot, after: InventorySnapshot, current: InventorySnapshot) = InventorySnapshot(
        tasks = revertRows(tr(TextKey.LEGACY_GROUPS), before.tasks, after.tasks, current.tasks, InventoryTask::id),
        countries = revertRows(tr(TextKey.COUNTRY), before.countries, after.countries, current.countries, Country::id),
        warehouses = revertRows(tr(TextKey.WAREHOUSE), before.warehouses, after.warehouses, current.warehouses, Warehouse::id),
        records = revertRows(tr(TextKey.INVENTORY_RECORDS), before.records, after.records, current.records, StockRecord::id),
        changes = revertRows(tr(TextKey.CHANGE_HISTORY), before.changes, after.changes, current.changes, ChangeLog::id),
    )
}
