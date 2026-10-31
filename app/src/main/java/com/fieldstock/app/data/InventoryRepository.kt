package com.fieldstock.app.data

import com.fieldstock.app.i18n.*

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fieldstock.app.model.ChangeLog
import com.fieldstock.app.model.Country
import com.fieldstock.app.model.DuplicateSnException
import com.fieldstock.app.model.DuplicateMode
import com.fieldstock.app.model.IdentifierRules
import com.fieldstock.app.model.InventorySnapshot
import com.fieldstock.app.model.InventoryTask
import com.fieldstock.app.model.ItemKind
import com.fieldstock.app.model.MutationResult
import com.fieldstock.app.model.RecordInput
import com.fieldstock.app.model.StockRecord
import com.fieldstock.app.model.Warehouse
import com.fieldstock.app.model.ImportMetadata
import com.fieldstock.app.model.ImportReceipt
import com.fieldstock.app.model.RecoveryPoint
import com.fieldstock.app.model.BoundedJson
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.backup.BackupMerge
import com.fieldstock.app.backup.BackupMergeChoices
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale
import java.util.UUID

/** All public operations are serialized; callers should use a background dispatcher. */
class InventoryRepository(context: Context) : AutoCloseable {
    private val helper = InventoryDatabase(context.applicationContext)

    @Synchronized
    fun snapshot(): InventorySnapshot = transaction { db -> readSnapshot(db) }

    @Synchronized
    fun importReceipts(): List<ImportReceipt> = transaction { readReceipts(it) }

    @Synchronized
    fun recoveryPoints(): List<RecoveryPoint> = transaction { db ->
        db.query("recovery_points", arrayOf("id", "label", "at", "record_count", "undone"), null, null, null, null, "rowid DESC").use { c ->
            buildList { while (c.moveToNext()) add(RecoveryPoint(c.string("id"), c.string("label"), c.long("at"), c.int("record_count"), c.int("undone") != 0)) }
        }
    }

    /** Snapshot, stock changes, import receipt and recovery point commit together or not at all. */
    @Synchronized
    fun <T> recoverable(label: String, work: (InventoryRepository) -> T): T = transaction { db ->
        require(label.isNotBlank() && label.length <= 512) { tr(TextKey.INVALID_RECOVERY_POINT_NAME) }
        val before = readSnapshot(db)
        val receiptsBefore = readReceipts(db)
        val beforeBytes = encodeSnapshot(before)
        val result = work(this)
        val after = readSnapshot(db)
        val receiptsAfter = readReceipts(db)
        if (before != after || receiptsBefore != receiptsAfter) {
            val afterBytes = encodeSnapshot(after)
            val oldReceipts = receiptsJson(receiptsBefore)
            val newReceipts = receiptsJson(receiptsAfter)
            val size = beforeBytes.size.toLong() + afterBytes.size + oldReceipts.toByteArray(Charsets.UTF_8).size + newReceipts.toByteArray(Charsets.UTF_8).size
            require(size <= MAX_RECOVERY_BYTES) { tr(TextKey.RECOVERY_POINT_EXCEEDS_THE_SIZE_LIMIT_NO_CHANGES_WERE) }
            val a = before.records.associateBy { it.id }; val b = after.records.associateBy { it.id }
            db.insertOrThrow("recovery_points", null, ContentValues().apply {
                put("id", newId()); put("label", label); put("at", System.currentTimeMillis()); put("undone", 0)
                put("record_count", (a.keys + b.keys).count { a[it] != b[it] })
                put("before_blob", beforeBytes); put("after_blob", afterBytes)
                put("before_receipts", oldReceipts); put("after_receipts", newReceipts); put("size_bytes", size)
            })
            val stored = db.query("recovery_points", arrayOf("id", "size_bytes"), null, null, null, null, "rowid ASC").use { c ->
                buildList { while (c.moveToNext()) add(c.string("id") to c.long("size_bytes")) }
            }.toMutableList()
            var total = stored.sumOf { it.second }
            while (stored.size > 5 || total > MAX_RECOVERY_BYTES) {
                val oldest = stored.removeAt(0); total -= oldest.second
                db.delete("recovery_points", "id = ?", arrayOf(oldest.first))
            }
        }
        result
    }

    @Synchronized
    fun undoRecovery(id: String): MutationResult = transaction { db ->
        data class Stored(val before: InventorySnapshot, val after: InventorySnapshot, val beforeReceipts: List<ImportReceipt>, val afterReceipts: List<ImportReceipt>)
        val stored = db.query("recovery_points", arrayOf("undone"), "id = ?", arrayOf(id), null, null, null).use { c ->
            require(c.moveToFirst()) { tr(TextKey.RECOVERY_POINT_WAS_REMOVED_OR_DOES_NOT_EXIST) }
            require(c.int("undone") == 0) { tr(TextKey.THIS_OPERATION_HAS_ALREADY_BEEN_UNDONE) }
            Stored(InventoryFiles.readBackup(readRecoveryBytes(db, id, "before_blob").inputStream()),
                InventoryFiles.readBackup(readRecoveryBytes(db, id, "after_blob").inputStream()),
                parseReceipts(readRecoveryBytes(db, id, "before_receipts").toString(Charsets.UTF_8)),
                parseReceipts(readRecoveryBytes(db, id, "after_receipts").toString(Charsets.UTF_8)))
        }
        val current = readSnapshot(db)
        var target = SnapshotUndo.prepare(stored.before, stored.after, current)
        val receipts = SnapshotUndo.revertRows(tr(TextKey.IMPORT_HISTORY), stored.beforeReceipts, stored.afterReceipts, readReceipts(db), ImportReceipt::id)
        val old = stored.before.records.associateBy { it.id }; val after = stored.after.records.associateBy { it.id }
        val restoring = old.keys.filterTo(mutableSetOf()) { old[it] != after[it] }
        val serials = target.records.filter { it.id !in restoring && !it.voided && it.kind == ItemKind.MACHINE && it.sn.isNotEmpty() }.mapTo(mutableSetOf()) { it.sn }
        val duplicates = mutableListOf<StockRecord>()
        val audit = mutableListOf<ChangeLog>()
        target = target.copy(records = target.records.map { record ->
            if (record.id in restoring && !record.voided && record.kind == ItemKind.MACHINE && record.sn.isNotEmpty() && !serials.add(record.sn)) {
                val now = maxOf(System.currentTimeMillis(), record.updatedAt)
                val voided = record.copy(voided = true, updatedAt = now)
                duplicates += voided
                audit += ChangeLog(newId(), record.id, "VOID", recordJson(record), recordJson(voided), now)
                voided
            } else record
        }, changes = target.changes)
        target = target.copy(changes = target.changes + audit)
        // Existing restore validation checks all references, names and audit data. The enclosing
        // transaction rolls back the replacement if a later unrelated row prevents this undo.
        listOf("changes", "records", "warehouses", "countries", "tasks").forEach { db.delete(it, null, null) }
        restore(target)
        db.delete("import_receipts", null, null)
        receipts.forEach { insertReceipt(db, it) }
        db.update("recovery_points", ContentValues().apply { put("undone", 1) }, "id = ?", arrayOf(id))
        MutationResult((old.keys + after.keys).count { old[it] != after[it] }, duplicates)
    }

    private fun encodeSnapshot(snapshot: InventorySnapshot): ByteArray = ByteArrayOutputStream().also { InventoryFiles.writeBackup(snapshot, it) }.toByteArray()

    /** Keep each CursorWindow row small even when a saved snapshot exceeds 2 MiB. */
    private fun readRecoveryBytes(db: SQLiteDatabase, id: String, column: String): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 1
        while (true) {
            val chunk = db.rawQuery("SELECT substr(CAST($column AS BLOB), ?, 262144) FROM recovery_points WHERE id = ?", arrayOf(offset.toString(), id)).use { c ->
                check(c.moveToFirst()); c.getBlob(0)
            }
            if (chunk.isEmpty()) break
            output.write(chunk); offset += chunk.size
        }
        return output.toByteArray()
    }

    private fun readReceipts(db: SQLiteDatabase): List<ImportReceipt> = rows(db, "import_receipts", "at DESC, id DESC") { c ->
        ImportReceipt(c.string("id"), c.string("fingerprint"), c.string("file_name"), c.string("warehouse_id"), c.string("warehouse_label"), c.long("at"), c.int("row_count"))
    }

    private fun insertReceipt(db: SQLiteDatabase, value: ImportReceipt) {
        db.insertOrThrow("import_receipts", null, ContentValues().apply {
            put("id", value.id); put("fingerprint", value.fingerprint); put("file_name", value.fileName)
            put("warehouse_id", value.warehouseId); put("warehouse_label", value.warehouseLabel); put("at", value.at); put("row_count", value.rows)
        })
    }

    private fun receiptsJson(values: List<ImportReceipt>): String = JSONArray().apply { values.forEach { v ->
        put(JSONObject().put("id", v.id).put("fingerprint", v.fingerprint).put("fileName", v.fileName).put("warehouseId", v.warehouseId)
            .put("warehouseLabel", v.warehouseLabel).put("at", v.at).put("rows", v.rows))
    } }.toString()

    private fun parseReceipts(text: String): List<ImportReceipt> = BoundedJson.arrayValue(text).let { array -> (0 until array.length()).map { i ->
        val v = array.getJSONObject(i)
        ImportReceipt(v.getString("id"), v.getString("fingerprint"), v.getString("fileName"), v.getString("warehouseId"), v.getString("warehouseLabel"), v.getLong("at"), v.getInt("rows"))
    } }

    @Synchronized
    fun addTask(name: String): InventoryTask {
        val task = InventoryTask(newId(), checkedName(name, tr(TextKey.INVENTORY_TASK)), System.currentTimeMillis())
        transaction { db ->
            require(!taskNameExists(db, task.name)) { tr(TextKey.AN_INVENTORY_TASK_WITH_THIS_NAME_ALREADY_EXISTS) }
            insertTask(db, task)
        }
        return task
    }

    @Synchronized
    fun renameTask(id: String, name: String): InventoryTask = transaction { db ->
        val checked = checkedName(name, tr(TextKey.INVENTORY_TASK))
        val previous = db.query("tasks", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
            require(cursor.moveToFirst()) { tr(TextKey.INVENTORY_TASK_DOES_NOT_EXIST) }
            InventoryTask(cursor.string("id"), cursor.string("name"), cursor.long("created_at"))
        }
        require(!taskNameExists(db, checked, id)) { tr(TextKey.AN_INVENTORY_TASK_WITH_THIS_NAME_ALREADY_EXISTS) }
        db.update("tasks", ContentValues().apply { put("name", checked) }, "id = ?", arrayOf(id))
        previous.copy(name = checked)
    }

    @Synchronized
    fun addCountry(name: String): Country {
        val country = Country(newId(), checkedName(name, tr(TextKey.COUNTRY)))
        transaction { db ->
            require(!exists(db, "countries", "name_key = ?", arrayOf(nameKey(country.name)))) {
                tr(TextKey.THIS_COUNTRY_ALREADY_EXISTS)
            }
            insertCountry(db, country)
        }
        return country
    }

    @Synchronized
    fun renameCountry(id: String, name: String): Country = transaction { db ->
        val checked = checkedName(name, tr(TextKey.COUNTRY))
        require(exists(db, "countries", "id = ?", arrayOf(id))) { tr(TextKey.COUNTRY_DOES_NOT_EXIST) }
        require(!exists(db, "countries", "name_key = ? AND id != ?", arrayOf(nameKey(checked), id))) {
            tr(TextKey.THIS_COUNTRY_ALREADY_EXISTS)
        }
        db.update("countries", ContentValues().apply {
            put("name", checked); put("name_key", nameKey(checked))
        }, "id = ?", arrayOf(id))
        Country(id, checked)
    }

    @Synchronized
    fun addWarehouse(countryId: String, name: String): Warehouse {
        val warehouse = Warehouse(newId(), countryId, checkedName(name, tr(TextKey.WAREHOUSE)))
        transaction { db ->
            require(exists(db, "countries", "id = ?", arrayOf(countryId))) { tr(TextKey.COUNTRY_DOES_NOT_EXIST) }
            require(!exists(db, "warehouses", "country_id = ? AND name_key = ?",
                arrayOf(countryId, nameKey(warehouse.name)))) { tr(TextKey.A_WAREHOUSE_WITH_THIS_NAME_ALREADY_EXISTS_IN_THIS) }
            insertWarehouse(db, warehouse)
        }
        return warehouse
    }

    @Synchronized
    fun renameWarehouse(id: String, name: String): Warehouse = transaction { db ->
        val checked = checkedName(name, tr(TextKey.WAREHOUSE))
        val previous = db.query("warehouses", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
            require(cursor.moveToFirst()) { tr(TextKey.WAREHOUSE_DOES_NOT_EXIST) }
            Warehouse(cursor.string("id"), cursor.string("country_id"), cursor.string("name"))
        }
        require(!exists(db, "warehouses", "country_id = ? AND name_key = ? AND id != ?",
            arrayOf(previous.countryId, nameKey(checked), id))) { tr(TextKey.A_WAREHOUSE_WITH_THIS_NAME_ALREADY_EXISTS_IN_THIS) }
        db.update("warehouses", ContentValues().apply {
            put("name", checked); put("name_key", nameKey(checked))
        }, "id = ?", arrayOf(id))
        previous.copy(name = checked)
    }

    @Synchronized
    fun save(input: RecordInput, recordId: String? = null): StockRecord = transaction { db ->
        val previous = recordId?.let { findRecord(db, it) ?: error(tr(TextKey.RECORD_DOES_NOT_EXIST)) }
        require(previous?.voided != true) { tr(TextKey.VOIDED_RECORDS_CANNOT_BE_EDITED_CREATE_A_NEW_RECORD) }
        val now = maxOf(System.currentTimeMillis(), previous?.updatedAt ?: 0L)
        val record = StockRecord(
            id = previous?.id ?: newId(),
            taskId = input.taskId,
            warehouseId = input.warehouseId,
            kind = input.kind,
            bom = input.bom.trim(),
            sn = if (input.kind == ItemKind.MACHINE) input.sn.trim() else "",
            quantity = if (input.kind == ItemKind.MACHINE) 1 else input.quantity,
            condition = input.condition.trim(),
            note = input.note.trim(),
            location = input.location.trim(),
            sourceBom = input.sourceBom,
            sourceSn = input.sourceSn,
            rawBom = input.rawBom,
            rawSn = input.rawSn,
            createdAt = previous?.createdAt ?: now,
            updatedAt = maxOf(now, previous?.createdAt ?: 0L),
            voided = false,
            pn = if (input.kind == ItemKind.MACHINE) input.pn.trim() else "",
            sourcePn = if (input.kind == ItemKind.MACHINE) input.sourcePn else "manual",
            rawPn = if (input.kind == ItemKind.MACHINE) input.rawPn else "",
        )
        validateRecord(record)
        require(exists(db, "tasks", "id = ?", arrayOf(record.taskId))) { tr(TextKey.INVENTORY_TASK_DOES_NOT_EXIST) }
        require(exists(db, "warehouses", "id = ?", arrayOf(record.warehouseId))) { tr(TextKey.WAREHOUSE_DOES_NOT_EXIST) }
        if (record.kind == ItemKind.MACHINE && record.sn.isNotEmpty()) {
            activeDuplicate(db, record.sn, record.id)?.let {
                throw DuplicateSnException(it, tr(TextKey.AN_ACTIVE_MACHINE_ALREADY_USES_SN, record.sn))
            }
        }
        if (previous == null) insertRecord(db, record)
        else db.update("records", recordValues(record), "id = ?", arrayOf(record.id))
        insertChange(db, ChangeLog(newId(), record.id,
            if (previous == null) "CREATE" else "UPDATE",
            previous?.let(::recordJson) ?: "{}", recordJson(record), now))
        record
    }

    /** Idempotent: a second void leaves both inventory and audit history unchanged. */
    @Synchronized
    fun voidRecord(id: String) {
        transaction { db ->
            val previous = findRecord(db, id) ?: error(tr(TextKey.RECORD_DOES_NOT_EXIST))
            if (!previous.voided) {
                val now = maxOf(System.currentTimeMillis(), previous.updatedAt)
                val record = previous.copy(voided = true, updatedAt = now)
                db.update("records", recordValues(record), "id = ?", arrayOf(id))
                insertChange(db, ChangeLog(newId(), id, "VOID", recordJson(previous), recordJson(record), now))
            }
        }
    }

    /** Restore the original state and quantity; an occupied serial leaves this record voided. */
    @Synchronized
    fun restoreVoided(id: String, mode: DuplicateMode = DuplicateMode.NEW_ENTRY): MutationResult = transaction { db ->
        val previous = findRecord(db, id) ?: throw IllegalArgumentException(tr(TextKey.RECORD_DOES_NOT_EXIST))
        require(previous.voided) { tr(TextKey.ONLY_VOIDED_RECORDS_CAN_BE_RESTORED) }
        if (previous.kind == ItemKind.MACHINE && previous.sn.isNotEmpty()) {
            if (activeDuplicate(db, previous.sn, id) != null) {
                return@transaction MutationResult(0, listOf(previous))
            }
        }
        if (previous.kind == ItemKind.PART && mode == DuplicateMode.ADD_QUANTITY) {
            earliestPart(db, previous)?.let { target ->
                combineParts(db, target, listOf(previous), consumeSources = true)
                return@transaction MutationResult(1)
            }
        }
        val now = maxOf(System.currentTimeMillis(), previous.updatedAt)
        val restored = previous.copy(voided = false, updatedAt = now)
        db.update("records", recordValues(restored), "id = ?", arrayOf(id))
        insertChange(db, ChangeLog(newId(), id, "RESTORE", recordJson(previous), recordJson(restored), now))
        MutationResult(1)
    }

    @Synchronized
    fun saveIncoming(input: RecordInput, mode: DuplicateMode): MutationResult = transaction { db ->
        incoming(db, input, mode)
    }

    @Synchronized
    fun importRecords(inputs: List<RecordInput>, mode: DuplicateMode, metadata: ImportMetadata? = null): MutationResult = transaction { db ->
        val duplicates = mutableListOf<StockRecord>()
        inputs.forEach { duplicates += incoming(db, it, mode, preserveNote = true).duplicateMachines }
        if (metadata != null) {
            require(inputs.isNotEmpty() && inputs.map { it.warehouseId }.distinct().size == 1) { tr(TextKey.CHOOSE_A_WAREHOUSE_BEFORE_IMPORTING_A_FILE) }
            require(metadata.fingerprint.matches(Regex("[a-f0-9]{64}")) && metadata.fileName.length <= 200) { tr(TextKey.INVALID_IMPORT_FILE_INFORMATION) }
            val warehouse = inputs.first().warehouseId
            val label = db.rawQuery("SELECT c.name, w.name FROM warehouses w JOIN countries c ON c.id = w.country_id WHERE w.id = ?", arrayOf(warehouse)).use { c ->
                check(c.moveToFirst()); "${c.getString(0)} / ${c.getString(1)}"
            }
            insertReceipt(db, ImportReceipt(newId(), metadata.fingerprint, metadata.fileName, warehouse, label, System.currentTimeMillis(), inputs.size))
        }
        MutationResult(inputs.size, duplicates)
    }

    @Synchronized
    fun voidRecords(ids: Set<String>): Int = transaction { db ->
        val records = ids.map { findRecord(db, it) ?: throw IllegalArgumentException(tr(TextKey.RECORD_DOES_NOT_EXIST)) }
        val active = records.filter { !it.voided }
        active.forEach { before ->
            val now = maxOf(System.currentTimeMillis(), before.updatedAt)
            val after = before.copy(voided = true, updatedAt = now)
            db.update("records", recordValues(after), "id = ?", arrayOf(before.id))
            insertChange(db, ChangeLog(newId(), before.id, "VOID", recordJson(before), recordJson(after), now))
        }
        active.size
    }

    @Synchronized
    fun mergeParts(ids: Set<String>): MutationResult = transaction { db ->
        require(ids.size >= 2) { tr(TextKey.SELECT_AT_LEAST_TWO_PARTS_RECORDS) }
        val selected = ids.map { findRecord(db, it) ?: throw IllegalArgumentException(tr(TextKey.RECORD_DOES_NOT_EXIST)) }
            .sortedWith(compareBy<StockRecord> { it.createdAt }.thenBy { it.id })
        val target = selected.first()
        require(target.bom.isNotEmpty()) { tr(TextKey.PENDING_PARTS_WITHOUT_A_BOM_CANNOT_BE_MERGED) }
        require(selected.all { it.kind == ItemKind.PART && !it.voided && it.warehouseId == target.warehouseId &&
            it.bom == target.bom && it.condition == target.condition }) { tr(TextKey.ONLY_ACTIVE_PARTS_IN_THE_SAME_WAREHOUSE_WITH_THE) }
        combineParts(db, target, selected.drop(1), consumeSources = true)
        MutationResult(selected.size)
    }

    @Synchronized
    fun deleteWarehouse(id: String): Int = transaction { db ->
        require(exists(db, "warehouses", "id = ?", arrayOf(id))) { tr(TextKey.WAREHOUSE_DOES_NOT_EXIST) }
        db.delete("changes", "record_id IN (SELECT id FROM records WHERE warehouse_id = ?)", arrayOf(id))
        val count = db.delete("records", "warehouse_id = ?", arrayOf(id))
        rewriteAuditWarehouses(db, setOf(id))
        db.delete("import_receipts", "warehouse_id = ?", arrayOf(id))
        db.delete("warehouses", "id = ?", arrayOf(id))
        count
    }

    @Synchronized
    fun deleteCountry(id: String): Int = transaction { db ->
        require(exists(db, "countries", "id = ?", arrayOf(id))) { tr(TextKey.COUNTRY_DOES_NOT_EXIST) }
        val warehouses = db.query("warehouses", arrayOf("id"), "country_id = ?", arrayOf(id), null, null, null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.string("id")) }
        }
        db.delete("changes", "record_id IN (SELECT id FROM records WHERE warehouse_id IN (SELECT id FROM warehouses WHERE country_id = ?))", arrayOf(id))
        val count = db.delete("records", "warehouse_id IN (SELECT id FROM warehouses WHERE country_id = ?)", arrayOf(id))
        rewriteAuditWarehouses(db, warehouses)
        db.delete("import_receipts", "warehouse_id IN (SELECT id FROM warehouses WHERE country_id = ?)", arrayOf(id))
        db.delete("warehouses", "country_id = ?", arrayOf(id))
        db.delete("countries", "id = ?", arrayOf(id))
        count
    }

    @Synchronized
    fun mergeWarehouses(sourceId: String, targetId: String, mode: DuplicateMode): MutationResult = transaction { db ->
        require(sourceId != targetId) { tr(TextKey.SOURCE_AND_TARGET_WAREHOUSES_MUST_BE_DIFFERENT) }
        fun countryOf(id: String): String = db.query("warehouses", arrayOf("country_id"), "id = ?", arrayOf(id), null, null, null).use {
            require(it.moveToFirst()) { tr(TextKey.WAREHOUSE_DOES_NOT_EXIST) }; it.string("country_id")
        }
        require(countryOf(sourceId) == countryOf(targetId)) { tr(TextKey.ONLY_WAREHOUSES_IN_THE_SAME_COUNTRY_CAN_BE_MERGED) }
        val sources = db.query("records", null, "warehouse_id = ?", arrayOf(sourceId), null, null, "created_at ASC, id ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(readRecord(cursor)) }
        }
        val duplicates = mutableListOf<StockRecord>()
        sources.forEach { before ->
            val moved = before.copy(warehouseId = targetId)
            if (before.kind == ItemKind.PART && !before.voided && mode == DuplicateMode.ADD_QUANTITY) {
                val target = earliestPart(db, moved)
                if (target != null) {
                    combineParts(db, target, listOf(before), consumeSources = true)
                    return@forEach
                }
            }
            val duplicate = before.kind == ItemKind.MACHINE && !before.voided && before.sn.isNotEmpty() &&
                activeDuplicate(db, before.sn, before.id) != null
            val now = maxOf(System.currentTimeMillis(), before.updatedAt)
            val after = moved.copy(voided = before.voided || duplicate, updatedAt = now)
            db.update("records", recordValues(after), "id = ?", arrayOf(before.id))
            val audit = BoundedJson.objectValue(recordJson(after)).put("backupImport", JSONObject()
                .put("source", recordJson(before)).put("destination", JSONObject().put("warehouseId", targetId))).toString()
            require(audit.length <= 65536) { tr(TextKey.CHANGE_HISTORY_CONTENT_IS_TOO_LONG) }
            BoundedJson.checkDepth(audit)
            insertChange(db, ChangeLog(newId(), before.id, if (duplicate) "VOID" else "UPDATE", recordJson(before), audit, now))
            if (duplicate) duplicates += after
        }
        rewriteAuditWarehouses(db, setOf(sourceId), targetId)
        val targetLabel = db.rawQuery("SELECT c.name, w.name FROM warehouses w JOIN countries c ON c.id = w.country_id WHERE w.id = ?", arrayOf(targetId)).use { c ->
            check(c.moveToFirst()); "${c.getString(0)} / ${c.getString(1)}"
        }
        db.update("import_receipts", ContentValues().apply { put("warehouse_id", targetId); put("warehouse_label", targetLabel) }, "warehouse_id = ?", arrayOf(sourceId))
        db.delete("warehouses", "id = ?", arrayOf(sourceId))
        MutationResult(sources.size, duplicates)
    }

    /** Permanently remove one voided record and its associated change history. */
    @Synchronized
    fun deleteVoided(id: String) {
        transaction { db ->
            val record = findRecord(db, id) ?: throw IllegalArgumentException(tr(TextKey.RECORD_DOES_NOT_EXIST))
            require(record.voided) { tr(TextKey.ONLY_VOIDED_RECORDS_CAN_BE_PERMANENTLY_DELETED) }
            db.delete("changes", "record_id = ?", arrayOf(id))
            db.delete("records", "id = ?", arrayOf(id))
        }
    }

    /** Permanently clear voided records in this warehouse only; active records are untouched. */
    @Synchronized
    fun clearVoided(warehouseId: String): Int = transaction { db ->
        require(exists(db, "warehouses", "id = ?", arrayOf(warehouseId))) { tr(TextKey.WAREHOUSE_DOES_NOT_EXIST) }
        db.delete("changes", "record_id IN (SELECT id FROM records WHERE warehouse_id = ? AND voided = 1)",
            arrayOf(warehouseId))
        db.delete("records", "warehouse_id = ? AND voided = 1", arrayOf(warehouseId))
    }

    /** Caller supplies the two user confirmations; this removes active records in one warehouse. */
    @Synchronized
    fun clearActive(warehouseId: String): Int = transaction { db ->
        require(exists(db, "warehouses", "id = ?", arrayOf(warehouseId))) { tr(TextKey.WAREHOUSE_DOES_NOT_EXIST) }
        db.delete("changes", "record_id IN (SELECT id FROM records WHERE warehouse_id = ? AND voided = 0)",
            arrayOf(warehouseId))
        db.delete("records", "warehouse_id = ? AND voided = 0", arrayOf(warehouseId))
    }

    @Synchronized
    fun pendingMigrationVoidedCount(): Int = transaction { db ->
        db.query("metadata", arrayOf("value"), "key = ?", arrayOf(MIGRATION_NOTICE_KEY), null, null, null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    @Synchronized
    fun acknowledgeMigrationNotice() {
        transaction { db -> db.delete("metadata", "key = ?", arrayOf(MIGRATION_NOTICE_KEY)) }
    }

    /**
     * Merge a complete backup by ID. The entire combined state is checked before writing.
     * Identical IDs are skipped, changed IDs fail without overwriting anything.
     * Returns the number of newly imported stock records (including voided records).
     */
    @Synchronized
    fun restore(snapshot: InventorySnapshot): Int = transaction { db ->
        val current = readSnapshot(db)
        val tasks = mergeById(tr(TextKey.INVENTORY_TASK), current.tasks, snapshot.tasks, InventoryTask::id)
        val countries = mergeById(tr(TextKey.COUNTRY), current.countries, snapshot.countries, Country::id)
        val warehouses = mergeById(tr(TextKey.WAREHOUSE), current.warehouses, snapshot.warehouses, Warehouse::id)
        val currentIds = current.records.mapTo(mutableSetOf()) { it.id }
        val occupied = current.records.filter { it.kind == ItemKind.MACHINE && !it.voided && it.sn.isNotEmpty() }
            .associateByTo(mutableMapOf()) { it.sn }
        val duplicateAudits = mutableListOf<ChangeLog>()
        val incomingRecords = snapshot.records.map { record ->
            if (record.id !in currentIds && record.kind == ItemKind.MACHINE && !record.voided && record.sn.isNotEmpty()) {
                if (occupied.containsKey(record.sn)) {
                    val now = maxOf(System.currentTimeMillis(), record.updatedAt)
                    val voided = record.copy(voided = true, updatedAt = now)
                    duplicateAudits += ChangeLog(newId(), record.id, "VOID", recordJson(record),
                        BoundedJson.objectValue(recordJson(voided)).put("reason", "duplicate_backup_import").toString(), now)
                    voided
                } else { occupied[record.sn] = record; record }
            } else record
        }
        val records = mergeById(tr(TextKey.INVENTORY_RECORDS), current.records, incomingRecords, StockRecord::id) { existing, incoming ->
            existing == incoming || current.changes.any { log ->
                log.recordId == incoming.id && log.action == "VOID" && runCatching {
                    val after = BoundedJson.objectValue(log.after)
                    after.optString("reason") == "duplicate_backup_import" &&
                        recordFromJson(BoundedJson.objectValue(log.before)) == incoming && recordFromJson(after) == existing
                }.getOrDefault(false)
            }
        }
        val changes = mergeById(tr(TextKey.CHANGE_HISTORY), current.changes, snapshot.changes + duplicateAudits, ChangeLog::id)

        tasks.values.forEach {
            validateId(it.id)
            require(it.name == checkedName(it.name, tr(TextKey.INVENTORY_TASK))) { tr(TextKey.INVENTORY_TASK_NAME_HAS_LEADING_OR_TRAILING_SPACES) }
            require(it.createdAt >= 0) { tr(TextKey.INVALID_INVENTORY_TASK_TIMESTAMP) }
        }
        val countryNames = mutableSetOf<String>()
        countries.values.forEach {
            validateId(it.id)
            require(it.name == checkedName(it.name, tr(TextKey.COUNTRY))) { tr(TextKey.COUNTRY_NAME_HAS_LEADING_OR_TRAILING_SPACES) }
            require(countryNames.add(nameKey(it.name))) { tr(TextKey.BACKUP_CONTAINS_DUPLICATE_COUNTRY_NAMES, it.name) }
        }
        val warehouseNames = mutableSetOf<Pair<String, String>>()
        warehouses.values.forEach {
            validateId(it.id)
            validateId(it.countryId)
            require(it.countryId in countries) { tr(TextKey.THE_WAREHOUSE_S_COUNTRY_DOES_NOT_EXIST) }
            require(it.name == checkedName(it.name, tr(TextKey.WAREHOUSE))) { tr(TextKey.WAREHOUSE_NAME_HAS_LEADING_OR_TRAILING_SPACES) }
            require(warehouseNames.add(it.countryId to nameKey(it.name))) { tr(TextKey.DUPLICATE_WAREHOUSE_NAMES_IN_ONE_COUNTRY, it.name) }
        }
        val serials = mutableMapOf<String, StockRecord>()
        records.values.forEach {
            validateRecord(it)
            require(it.taskId in tasks) { tr(TextKey.THE_RECORD_S_INVENTORY_TASK_DOES_NOT_EXIST) }
            require(it.warehouseId in warehouses) { tr(TextKey.THE_RECORD_S_WAREHOUSE_DOES_NOT_EXIST) }
            if (it.kind == ItemKind.MACHINE && !it.voided && it.sn.isNotEmpty()) {
                val other = serials.put(it.sn, it)
                if (other != null) throw DuplicateSnException(other,
                    tr(TextKey.BACKUP_CONTAINS_DUPLICATE_ACTIVE_SN_NOTHING_WAS_IMPORTED_CHECK, it.sn))
            }
        }
        changes.values.forEach {
            validateId(it.id)
            validateId(it.recordId)
            require(it.recordId in records) { tr(TextKey.THE_RECORD_LINKED_TO_CHANGE_HISTORY_DOES_NOT_EXIST) }
            validateChange(it, tasks.keys, warehouses.keys)
        }

        val taskIds = current.tasks.mapTo(mutableSetOf(), InventoryTask::id)
        val countryIds = current.countries.mapTo(mutableSetOf(), Country::id)
        val warehouseIds = current.warehouses.mapTo(mutableSetOf(), Warehouse::id)
        val recordIds = current.records.mapTo(mutableSetOf(), StockRecord::id)
        val changeIds = current.changes.mapTo(mutableSetOf(), ChangeLog::id)
        snapshot.tasks.filter { it.id !in taskIds }.forEach { insertTask(db, it) }
        snapshot.countries.filter { it.id !in countryIds }.forEach { insertCountry(db, it) }
        snapshot.warehouses.filter { it.id !in warehouseIds }.forEach { insertWarehouse(db, it) }
        val imported = incomingRecords.filter { it.id !in recordIds }
        imported.forEach { insertRecord(db, it) }
        (snapshot.changes + duplicateAudits).filter { it.id !in changeIds }.forEach { insertChange(db, it) }
        imported.size
    }

    /** Name resolutions, original history, quantity merges and replay markers commit together. */
    @Synchronized
    fun importBackup(incoming: InventorySnapshot, choices: BackupMergeChoices = BackupMergeChoices()): MutationResult = transaction { db ->
        val current = readSnapshot(db)
        val recordIds = (current.records + incoming.records).mapTo(mutableSetOf()) { it.id }
        val taskIds = (current.tasks + incoming.tasks).mapTo(mutableSetOf()) { it.id }
        val warehouseIds = (current.warehouses + incoming.warehouses).mapTo(mutableSetOf()) { it.id }
        incoming.records.forEach(::validateRecord)
        incoming.changes.forEach { change ->
            validateId(change.id)
            require(change.recordId in recordIds) { tr(TextKey.THE_RECORD_LINKED_TO_CHANGE_HISTORY_DOES_NOT_EXIST) }
            validateChange(change, taskIds, warehouseIds)
        }
        val plan = BackupMerge.prepare(current, incoming, choices)
        val oldIds = current.records.mapTo(mutableSetOf()) { it.id }
        val count = restore(plan.snapshot)
        val added = plan.snapshot.records.filter { it.id !in oldIds }
        fun partKey(record: StockRecord) = Triple(record.warehouseId, record.bom, record.condition)
        val targets = current.records.filter { it.kind == ItemKind.PART && !it.voided && it.bom.isNotEmpty() }
            .sortedWith(compareBy<StockRecord> { it.createdAt }.thenBy { it.id }).groupBy(::partKey)
            .mapValuesTo(mutableMapOf()) { it.value.first().id }
        val changingTargets = added.filter { it.kind == ItemKind.PART && !it.voided && it.bom.isNotEmpty() && it.warehouseId in plan.quantityWarehouses }
            .mapNotNull { targets[partKey(it)] }.toSet()
        plan.snapshot.records.filter { it.id !in oldIds || it.id in changingTargets }.forEach { source ->
            plan.sources[source.id]?.let { original ->
                val stored = findRecord(db, source.id)!!
                val marker = JSONObject().put("source", recordJson(original))
                    .put("destination", JSONObject().put("warehouseId", stored.warehouseId))
                val after = BoundedJson.objectValue(recordJson(stored)).put("backupImport", marker).toString()
                insertChange(db, ChangeLog(newId(), stored.id, "UPDATE", recordJson(stored), after, maxOf(System.currentTimeMillis(), stored.updatedAt)))
            }
        }
        added.forEach { source ->
            if (source.kind != ItemKind.PART || source.voided || source.bom.isEmpty()) return@forEach
            val key = partKey(source)
            val target = targets[key]?.let { findRecord(db, it) }
            if (source.warehouseId in plan.quantityWarehouses && target != null) {
                combineParts(db, target, listOf(findRecord(db, source.id)!!), consumeSources = true)
            } else if (target == null) targets[key] = source.id
        }
        val newMachines = added.filter { !it.voided && it.kind == ItemKind.MACHINE }.mapNotNull { findRecord(db, it.id)?.takeIf { row -> row.voided } }
        // Validate rewritten history and merged state before the outer transaction can commit.
        restore(readSnapshot(db))
        MutationResult(count, newMachines)
    }

    @Synchronized
    override fun close() = helper.close()

    private fun incoming(db: SQLiteDatabase, input: RecordInput, mode: DuplicateMode, preserveNote: Boolean = false): MutationResult {
        val now = System.currentTimeMillis()
        var record = StockRecord(newId(), input.taskId, input.warehouseId, input.kind,
            input.bom.trim(), if (input.kind == ItemKind.MACHINE) input.sn.trim() else "",
            if (input.kind == ItemKind.MACHINE) 1 else input.quantity, input.condition.trim(),
            if (preserveNote) input.note else input.note.trim(), input.location.trim(),
            input.sourceBom, input.sourceSn, input.rawBom, input.rawSn, now, now, false,
            pn = if (input.kind == ItemKind.MACHINE) input.pn.trim() else "",
            sourcePn = if (input.kind == ItemKind.MACHINE) input.sourcePn else "manual",
            rawPn = if (input.kind == ItemKind.MACHINE) input.rawPn else "")
        validateRecord(record)
        require(exists(db, "tasks", "id = ?", arrayOf(record.taskId))) { tr(TextKey.LEGACY_RECORD_GROUP_DOES_NOT_EXIST) }
        require(exists(db, "warehouses", "id = ?", arrayOf(record.warehouseId))) { tr(TextKey.WAREHOUSE_DOES_NOT_EXIST) }
        if (record.kind == ItemKind.PART && mode == DuplicateMode.ADD_QUANTITY) {
            earliestPart(db, record)?.let { target ->
                combineParts(db, target, listOf(record), consumeSources = false)
                return MutationResult(1)
            }
        }
        val duplicate = record.kind == ItemKind.MACHINE && record.sn.isNotEmpty() && activeDuplicate(db, record.sn, record.id) != null
        if (duplicate) record = record.copy(voided = true)
        insertRecord(db, record)
        insertChange(db, ChangeLog(newId(), record.id, "CREATE", "{}", recordJson(record), now))
        return MutationResult(1, if (duplicate) listOf(record) else emptyList())
    }

    private fun earliestPart(db: SQLiteDatabase, source: StockRecord): StockRecord? {
        if (source.bom.isEmpty()) return null
        return db.query("records", null, "warehouse_id = ? AND bom = ? AND condition = ? AND kind = ? AND voided = 0 AND id != ?",
            arrayOf(source.warehouseId, source.bom, source.condition, ItemKind.PART.name, source.id),
            null, null, "created_at ASC, id ASC", "1").use { if (it.moveToFirst()) readRecord(it) else null }
    }

    private fun combineParts(db: SQLiteDatabase, target: StockRecord, sources: List<StockRecord>, consumeSources: Boolean) {
        val quantity = target.quantity.toLong() + sources.sumOf { it.quantity.toLong() }
        require(quantity in 1..MAX_QUANTITY.toLong()) { tr(TextKey.MERGED_QUANTITY_EXCEEDS_NO_RECORDS_WERE_CHANGED, MAX_QUANTITY) }
        val details = sources.filter { it.location.isNotEmpty() || it.note.isNotEmpty() }.map { source ->
            tr(TextKey.MERGED_SOURCE_PCS, source.quantity) + (if (source.location.isNotEmpty()) tr(TextKey.LOCATION, source.location) else "") +
                (if (source.note.isNotEmpty()) "：${source.note}" else "")
        }
        val now = maxOf(System.currentTimeMillis(), target.updatedAt, sources.maxOf { it.updatedAt })
        val after = target.copy(quantity = quantity.toInt(), note = (listOf(target.note).filter { it.isNotEmpty() } + details).joinToString("\n"), updatedAt = now)
        validateRecord(after)
        db.update("records", recordValues(after), "id = ?", arrayOf(target.id))
        val auditAfter = BoundedJson.objectValue(recordJson(after)).put("mergedSources", JSONArray().apply {
            sources.forEach { source -> put(BoundedJson.objectValue(recordJson(source)).apply {
                if (consumeSources) {
                    put("backupImport", JSONObject().put("source", recordJson(source))
                        .put("destination", JSONObject().put("warehouseId", target.warehouseId)))
                    val history = JSONArray()
                    var size = 0L
                    db.query("changes", null, "record_id = ?", arrayOf(source.id), null, null, "at ASC, id ASC").use { c ->
                        while (c.moveToNext()) {
                            val before = c.string("before_json"); val after = c.string("after_json")
                            size += before.length.toLong() + after.length
                            require(size <= 65536) { tr(TextKey.TOO_MANY_MERGE_SOURCES_MERGE_IN_SMALLER_BATCHES) }
                            history.put(JSONObject().put("id", c.string("id")).put("recordId", c.string("record_id"))
                                .put("action", c.string("action")).put("before", parseAuditJson(before)).put("after", parseAuditJson(after)).put("at", c.long("at")))
                        }
                    }
                    if (history.length() > 0) put("history", history)
                }
            }) }
        }).toString()
        require(auditAfter.length <= 65536) { tr(TextKey.TOO_MANY_MERGE_SOURCES_MERGE_IN_SMALLER_BATCHES) }
        BoundedJson.checkDepth(auditAfter)
        insertChange(db, ChangeLog(newId(), target.id, "UPDATE", recordJson(target), auditAfter, now))
        if (consumeSources) sources.forEach {
            db.delete("changes", "record_id = ?", arrayOf(it.id))
            db.delete("records", "id = ?", arrayOf(it.id))
        }
    }

    /** Historical identifiers remain archived as originalWarehouseId when their live warehouse is removed. */
    private fun rewriteAuditWarehouses(db: SQLiteDatabase, removed: Set<String>, replacement: String? = null) {
        if (removed.isEmpty()) return
        val records = rows(db, "records", "id ASC", ::readRecord).associateBy { it.id }
        val changes = rows(db, "changes", "id ASC") {
            ChangeLog(it.string("id"), it.string("record_id"), it.string("action"), it.string("before_json"), it.string("after_json"), it.long("at"))
        }
        fun rewrite(value: Any?, target: String) {
            when (value) {
                is JSONObject -> {
                    if (value.optString("warehouseId") in removed) {
                        if (!value.has("originalWarehouseId")) value.put("originalWarehouseId", value.getString("warehouseId"))
                        value.put("warehouseId", target)
                    }
                    value.keys().asSequence().toList().forEach { rewrite(value.opt(it), target) }
                }
                is JSONArray -> for (i in 0 until value.length()) rewrite(value.opt(i), target)
            }
        }
        changes.forEach { change ->
            val target = replacement ?: records.getValue(change.recordId).warehouseId
            val before = parseAuditJson(change.before); val after = parseAuditJson(change.after)
            rewrite(before, target); rewrite(after, target)
            db.update("changes", ContentValues().apply {
                put("before_json", before.toString()); put("after_json", after.toString())
            }, "id = ?", arrayOf(change.id))
        }
    }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun readSnapshot(db: SQLiteDatabase): InventorySnapshot = InventorySnapshot(
        tasks = rows(db, "tasks", "created_at ASC, id ASC") {
            InventoryTask(it.string("id"), it.string("name"), it.long("created_at"))
        },
        countries = rows(db, "countries", "name COLLATE NOCASE ASC, id ASC") {
            Country(it.string("id"), it.string("name"))
        },
        warehouses = rows(db, "warehouses", "name COLLATE NOCASE ASC, id ASC") {
            Warehouse(it.string("id"), it.string("country_id"), it.string("name"))
        },
        records = rows(db, "records", "created_at DESC, id ASC", ::readRecord),
        changes = rows(db, "changes", "at ASC, id ASC") {
            ChangeLog(it.string("id"), it.string("record_id"), it.string("action"),
                it.string("before_json"), it.string("after_json"), it.long("at"))
        },
    )

    private fun validateRecord(record: StockRecord) {
        validateId(record.id)
        validateId(record.taskId)
        validateId(record.warehouseId)
        require(record.condition in CONDITIONS) { tr(TextKey.CONDITION_MUST_BE_NORMAL_DAMAGED_OR_PENDING_VERIFICATION) }
        val pending = record.condition == StockCondition.PENDING
        require(!pending || record.bom.isNotEmpty() || record.sn.isNotEmpty() || record.pn.isNotEmpty() || record.note.isNotBlank()) {
            tr(TextKey.PENDING_DRAFTS_REQUIRE_AT_LEAST_ONE_IDENTIFIER_OR_A)
        }
        if ((record.kind == ItemKind.PART && !pending) || record.bom.isNotEmpty()) {
            IdentifierRules.validateBom(record.bom)?.let { throw IllegalArgumentException(it) }
        }
        require(record.bom == record.bom.trim()) { tr(TextKey.BOM_HAS_LEADING_OR_TRAILING_SPACES) }
        if (record.kind == ItemKind.MACHINE) {
            if (record.pn.isNotEmpty()) IdentifierRules.validatePn(record.pn)?.let { throw IllegalArgumentException(it) }
            require(record.pn == record.pn.trim()) { tr(TextKey.PN_HAS_LEADING_OR_TRAILING_SPACES) }
            if (!pending || record.sn.isNotEmpty()) {
                IdentifierRules.validateSn(record.sn)?.let { throw IllegalArgumentException(it) }
            }
            require(record.sn == record.sn.trim()) { tr(TextKey.SN_HAS_LEADING_OR_TRAILING_SPACES) }
            require(record.quantity == 1) { tr(TextKey.MACHINE_QUANTITY_MUST_BE_1) }
        } else {
            require(record.sn.isEmpty()) { tr(TextKey.PARTS_MUST_NOT_HAVE_AN_SN) }
            require(record.pn.isEmpty()) { tr(TextKey.PARTS_MUST_NOT_HAVE_A_PN) }
            require(record.quantity in 1..MAX_QUANTITY) { tr(TextKey.PARTS_QUANTITY_MUST_BE_BETWEEN_1_AND, MAX_QUANTITY) }
        }
        require(record.note.length <= 4000) { tr(TextKey.NOTES_MUST_NOT_EXCEED_4000_CHARACTERS) }
        require(record.location.length <= 100 && record.location.none(Char::isISOControl)) { tr(TextKey.LOCATION_MUST_NOT_EXCEED_100_CHARACTERS_OR_CONTAIN_LINE) }
        require(record.sourceBom in SOURCES && record.sourceSn in SOURCES && record.sourcePn in SOURCES) { tr(TextKey.INVALID_IDENTIFIER_INPUT_SOURCE) }
        require(record.rawBom.length <= 4000 && record.rawSn.length <= 4000 && record.rawPn.length <= 4000) { tr(TextKey.RAW_RECOGNIZED_TEXT_IS_TOO_LONG) }
        require(record.createdAt >= 0 && record.updatedAt >= record.createdAt) { tr(TextKey.INVALID_INVENTORY_RECORD_TIMESTAMP) }
    }

    private fun validateChange(change: ChangeLog, taskIds: Set<String>, warehouseIds: Set<String>) {
        require(change.action in setOf("CREATE", "UPDATE", "VOID", "RESTORE")) { tr(TextKey.INVALID_CHANGE_HISTORY_ACTION) }
        require(change.at >= 0) { tr(TextKey.INVALID_CHANGE_HISTORY_TIMESTAMP) }
        require(change.before.length <= 65536 && change.after.length <= 65536) { tr(TextKey.CHANGE_HISTORY_CONTENT_IS_TOO_LONG) }
        val before = parseAuditJson(change.before)
        val after = parseAuditJson(change.after)
        require((change.action == "CREATE") == (before.length() == 0)) { tr(TextKey.THE_BEFORE_RECORD_IN_CHANGE_HISTORY_DOES_NOT_MATCH) }
        require(after.length() != 0) { tr(TextKey.CHANGE_HISTORY_IS_MISSING_THE_AFTER_RECORD) }
        for (json in listOf(before, after)) {
            if (json.length() == 0) {
                require(change.action == "CREATE" && json === before) { tr(TextKey.CHANGE_HISTORY_IS_MISSING_THE_BEFORE_RECORD) }
                continue
            }
            val record = recordFromJson(json)
            validateRecord(record)
            BackupMerge.markerSources(json).forEach(::validateRecord)
            require(record.id == change.recordId) { tr(TextKey.CHANGE_HISTORY_RECORD_IDS_DO_NOT_MATCH) }
            require(record.taskId in taskIds && record.warehouseId in warehouseIds) { tr(TextKey.INVALID_CHANGE_HISTORY_REFERENCES) }
        }
        if (change.action == "RESTORE") {
            require(before.getBoolean("voided") && !after.getBoolean("voided")) { tr(TextKey.INVALID_VOIDED_STATE_IN_RESTORE_HISTORY) }
        }
    }

    private fun parseAuditJson(value: String): JSONObject = try {
        BoundedJson.objectValue(value)
    } catch (e: Exception) {
        throw IllegalArgumentException(tr(TextKey.INVALID_CHANGE_HISTORY_JSON), e)
    }

    private fun recordFromJson(json: JSONObject): StockRecord = try {
        StockRecord(json.getString("id"), json.getString("taskId"), json.getString("warehouseId"),
            ItemKind.valueOf(json.getString("kind")), json.getString("bom"), json.getString("sn"),
            json.getInt("quantity"), json.getString("condition"), json.getString("note"), json.optString("location", ""),
            json.getString("sourceBom"), json.getString("sourceSn"), json.getString("rawBom"),
            json.getString("rawSn"), json.getLong("createdAt"), json.getLong("updatedAt"),
            json.getBoolean("voided"), json.optString("pn", ""), json.optString("sourcePn", "manual"), json.optString("rawPn", ""))
    } catch (e: Exception) {
        throw IllegalArgumentException(tr(TextKey.CHANGE_HISTORY_IS_MISSING_A_COMPLETE_INVENTORY_RECORD), e)
    }

    private fun recordJson(record: StockRecord): String = JSONObject().apply {
        put("id", record.id)
        put("taskId", record.taskId)
        put("warehouseId", record.warehouseId)
        put("kind", record.kind.name)
        put("bom", record.bom)
        put("sn", record.sn)
        put("pn", record.pn)
        put("sourcePn", record.sourcePn)
        put("rawPn", record.rawPn)
        put("quantity", record.quantity)
        put("condition", record.condition)
        put("note", record.note)
        put("location", record.location)
        put("sourceBom", record.sourceBom)
        put("sourceSn", record.sourceSn)
        put("rawBom", record.rawBom)
        put("rawSn", record.rawSn)
        put("createdAt", record.createdAt)
        put("updatedAt", record.updatedAt)
        put("voided", record.voided)
    }.toString()

    private fun readRecord(cursor: Cursor) = StockRecord(
        cursor.string("id"), cursor.string("task_id"), cursor.string("warehouse_id"),
        ItemKind.valueOf(cursor.string("kind")), cursor.string("bom"), cursor.string("sn"),
        cursor.int("quantity"), cursor.string("condition"), cursor.string("note"), cursor.string("location"),
        cursor.string("source_bom"), cursor.string("source_sn"), cursor.string("raw_bom"),
        cursor.string("raw_sn"), cursor.long("created_at"), cursor.long("updated_at"),
        cursor.int("voided") != 0,
        cursor.string("pn"), cursor.string("source_pn"), cursor.string("raw_pn"),
    )

    private fun findRecord(db: SQLiteDatabase, id: String): StockRecord? =
        db.query("records", null, "id = ?", arrayOf(id), null, null, null).use {
            if (it.moveToFirst()) readRecord(it) else null
        }

    private fun activeDuplicate(db: SQLiteDatabase, sn: String, excludeId: String): StockRecord? =
        db.query("records", null, "kind = ? AND sn = ? AND voided = 0 AND id != ?",
            arrayOf(ItemKind.MACHINE.name, sn, excludeId), null, null, "created_at ASC, id ASC", "1").use {
            if (it.moveToFirst()) readRecord(it) else null
        }

    private fun recordValues(record: StockRecord) = ContentValues().apply {
        put("id", record.id)
        put("task_id", record.taskId)
        put("warehouse_id", record.warehouseId)
        put("kind", record.kind.name)
        put("bom", record.bom)
        put("sn", record.sn)
        put("pn", record.pn)
        put("source_pn", record.sourcePn)
        put("raw_pn", record.rawPn)
        put("quantity", record.quantity)
        put("condition", record.condition)
        put("note", record.note)
        put("location", record.location)
        put("source_bom", record.sourceBom)
        put("source_sn", record.sourceSn)
        put("raw_bom", record.rawBom)
        put("raw_sn", record.rawSn)
        put("created_at", record.createdAt)
        put("updated_at", record.updatedAt)
        put("voided", if (record.voided) 1 else 0)
    }

    private fun insertTask(db: SQLiteDatabase, value: InventoryTask) {
        db.insertOrThrow("tasks", null, ContentValues().apply {
            put("id", value.id); put("name", value.name); put("created_at", value.createdAt)
        })
    }

    private fun insertCountry(db: SQLiteDatabase, value: Country) {
        db.insertOrThrow("countries", null, ContentValues().apply {
            put("id", value.id); put("name", value.name); put("name_key", nameKey(value.name))
        })
    }

    private fun insertWarehouse(db: SQLiteDatabase, value: Warehouse) {
        db.insertOrThrow("warehouses", null, ContentValues().apply {
            put("id", value.id); put("country_id", value.countryId)
            put("name", value.name); put("name_key", nameKey(value.name))
        })
    }

    private fun insertRecord(db: SQLiteDatabase, value: StockRecord) {
        db.insertOrThrow("records", null, recordValues(value))
    }

    private fun insertChange(db: SQLiteDatabase, value: ChangeLog) {
        db.insertOrThrow("changes", null, ContentValues().apply {
            put("id", value.id); put("record_id", value.recordId); put("action", value.action)
            put("before_json", value.before); put("after_json", value.after); put("at", value.at)
        })
    }

    private fun <T> mergeById(label: String, current: List<T>, incoming: List<T>, id: (T) -> String,
        equivalent: (T, T) -> Boolean = { existing, item -> existing == item }): Map<String, T> {
        val combined = current.associateByTo(linkedMapOf(), id)
        val incomingIds = mutableSetOf<String>()
        incoming.forEach { item ->
            val itemId = id(item)
            validateId(itemId)
            require(incomingIds.add(itemId)) { tr(TextKey.DUPLICATE_ID_IN_BACKUP, label, itemId) }
            val existing = combined[itemId]
            require(existing == null || equivalent(existing, item)) { tr(TextKey.ID_EXISTS_WITH_DIFFERENT_CONTENT_NOTHING_WAS_IMPORTED, label, itemId) }
            combined[itemId] = existing ?: item
        }
        return combined
    }

    private fun exists(db: SQLiteDatabase, table: String, where: String, args: Array<String>): Boolean =
        db.query(table, arrayOf("id"), where, args, null, null, null, "1").use { it.moveToFirst() }

    private fun taskNameExists(db: SQLiteDatabase, name: String, excludeId: String? = null): Boolean =
        db.query("tasks", arrayOf("id", "name"), null, null, null, null, null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.string("id") != excludeId && nameKey(cursor.string("name")) == nameKey(name)) {
                    found = true
                    break
                }
            }
            found
        }

    private fun <T> rows(db: SQLiteDatabase, table: String, order: String, read: (Cursor) -> T): List<T> =
        db.query(table, null, null, null, null, null, order).use { cursor ->
            buildList { while (cursor.moveToNext()) add(read(cursor)) }
        }

    private fun checkedName(value: String, label: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 100 && it.none(Char::isISOControl)) {
            tr(TextKey.NAME_MUST_CONTAIN_1_TO_100_CHARACTERS_WITH_NO, label)
        }
    }

    private fun validateId(value: String) {
        require(ID_PATTERN.matches(value)) { tr(TextKey.INVALID_DATA_ID) }
    }

    private fun nameKey(value: String) = value.lowercase(Locale.ROOT)
    private fun newId() = UUID.randomUUID().toString()
    private fun Cursor.string(key: String) = getString(getColumnIndexOrThrow(key))
    private fun Cursor.long(key: String) = getLong(getColumnIndexOrThrow(key))
    private fun Cursor.int(key: String) = getInt(getColumnIndexOrThrow(key))

    private inner class InventoryDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE countries (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE TABLE warehouses (id TEXT PRIMARY KEY NOT NULL, country_id TEXT NOT NULL REFERENCES countries(id), name TEXT NOT NULL, name_key TEXT NOT NULL, UNIQUE(country_id, name_key))")
            db.execSQL("""CREATE TABLE records (
                id TEXT PRIMARY KEY NOT NULL,
                task_id TEXT NOT NULL REFERENCES tasks(id),
                warehouse_id TEXT NOT NULL REFERENCES warehouses(id),
                kind TEXT NOT NULL CHECK(kind IN ('PART', 'MACHINE')),
                bom TEXT NOT NULL, sn TEXT NOT NULL,
                pn TEXT NOT NULL DEFAULT '', source_pn TEXT NOT NULL DEFAULT 'manual', raw_pn TEXT NOT NULL DEFAULT '',
                quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000000),
                condition TEXT NOT NULL, note TEXT NOT NULL, location TEXT NOT NULL,
                source_bom TEXT NOT NULL, source_sn TEXT NOT NULL,
                raw_bom TEXT NOT NULL, raw_sn TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                voided INTEGER NOT NULL CHECK(voided IN (0, 1)),
                CHECK(kind != 'MACHINE' OR quantity = 1)
            )""".trimIndent())
            db.execSQL(GLOBAL_SN_INDEX)
            db.execSQL("CREATE INDEX records_by_location ON records(task_id, warehouse_id, voided)")
            db.execSQL("CREATE INDEX records_by_warehouse ON records(warehouse_id, voided)")
            db.execSQL("CREATE TABLE changes (id TEXT PRIMARY KEY NOT NULL, record_id TEXT NOT NULL REFERENCES records(id), action TEXT NOT NULL, before_json TEXT NOT NULL, after_json TEXT NOT NULL, at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX changes_by_record ON changes(record_id, at)")
            createRecoveryTables(db)
            db.insertOrThrow("tasks", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("name", "首次盘点")
                put("created_at", System.currentTimeMillis())
            })
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            require(oldVersion in 1..4 && newVersion == 5) { tr(TextKey.UNSUPPORTED_DATABASE_UPGRADE, oldVersion, newVersion) }
            val columns = db.rawQuery("PRAGMA table_info(records)", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            mapOf("pn" to "", "source_pn" to "manual", "raw_pn" to "").forEach { (name, default) ->
                if (name !in columns) db.execSQL("ALTER TABLE records ADD COLUMN " + name + " TEXT NOT NULL DEFAULT '" + default + "'")
            }
            if (oldVersion < 3) {
            // Preserve every old record. For serials previously repeated in different tasks,
            // keep the latest edited record active and audit the others as voided.
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY NOT NULL, value INTEGER NOT NULL)")
            db.execSQL("DROP INDEX IF EXISTS unique_active_sn")
            val candidates = db.query("records", null,
                "kind = ? AND voided = 0 AND sn != ''", arrayOf(ItemKind.MACHINE.name),
                null, null, "sn ASC, updated_at DESC, created_at DESC, id ASC").use { cursor ->
                buildList { while (cursor.moveToNext()) add(readRecord(cursor)) }
            }
            val seen = mutableSetOf<String>()
            var voidedCount = 0
            candidates.forEach { previous ->
                if (!seen.add(previous.sn)) {
                    val now = maxOf(System.currentTimeMillis(), previous.updatedAt)
                    val voided = previous.copy(voided = true, updatedAt = now)
                    db.update("records", recordValues(voided), "id = ?", arrayOf(previous.id))
                    insertChange(db, ChangeLog(newId(), previous.id, "VOID", recordJson(previous), recordJson(voided), now))
                    voidedCount++
                }
            }
            if (voidedCount > 0) {
                db.insertWithOnConflict("metadata", null, ContentValues().apply {
                    put("key", MIGRATION_NOTICE_KEY); put("value", voidedCount)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.execSQL(GLOBAL_SN_INDEX)
            db.execSQL("CREATE INDEX IF NOT EXISTS records_by_warehouse ON records(warehouse_id, voided)")
            }
            createRecoveryTables(db)
        }
    }

    private fun createRecoveryTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS import_receipts (id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, file_name TEXT NOT NULL, warehouse_id TEXT NOT NULL, warehouse_label TEXT NOT NULL, at INTEGER NOT NULL, row_count INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS imports_by_fingerprint ON import_receipts(fingerprint, at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS recovery_points (id TEXT PRIMARY KEY NOT NULL, label TEXT NOT NULL, at INTEGER NOT NULL, record_count INTEGER NOT NULL, undone INTEGER NOT NULL, before_blob BLOB NOT NULL, after_blob BLOB NOT NULL, before_receipts TEXT NOT NULL, after_receipts TEXT NOT NULL, size_bytes INTEGER NOT NULL)")
    }

    companion object {
        const val DATABASE_NAME = "fieldstock.db"
        private const val DATABASE_VERSION = 5
        private const val MAX_RECOVERY_BYTES = 96L * 1024 * 1024
        private const val GLOBAL_SN_INDEX = "CREATE UNIQUE INDEX unique_active_sn ON records(sn) WHERE kind = 'MACHINE' AND voided = 0 AND sn != ''"
        private const val MIGRATION_NOTICE_KEY = "migration_voided_duplicates"
        private const val MAX_QUANTITY = 1_000_000_000
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val SOURCES = setOf("manual", "ocr", "import")
        private val CONDITIONS = setOf(StockCondition.NORMAL, StockCondition.DAMAGED, StockCondition.PENDING)
    }
}
