package com.fieldstock.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fieldstock.app.model.ChangeLog
import com.fieldstock.app.model.Country
import com.fieldstock.app.model.DuplicateSnException
import com.fieldstock.app.model.IdentifierRules
import com.fieldstock.app.model.InventorySnapshot
import com.fieldstock.app.model.InventoryTask
import com.fieldstock.app.model.ItemKind
import com.fieldstock.app.model.RecordInput
import com.fieldstock.app.model.StockRecord
import com.fieldstock.app.model.Warehouse
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** All public operations are serialized; callers should use a background dispatcher. */
class InventoryRepository(context: Context) : AutoCloseable {
    private val helper = InventoryDatabase(context.applicationContext)

    @Synchronized
    fun snapshot(): InventorySnapshot = transaction { db -> readSnapshot(db) }

    @Synchronized
    fun addTask(name: String): InventoryTask {
        val task = InventoryTask(newId(), checkedName(name, "盘点任务"), System.currentTimeMillis())
        transaction { db ->
            require(!taskNameExists(db, task.name)) { "已存在同名盘点任务" }
            insertTask(db, task)
        }
        return task
    }

    @Synchronized
    fun renameTask(id: String, name: String): InventoryTask = transaction { db ->
        val checked = checkedName(name, "盘点任务")
        val previous = db.query("tasks", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
            require(cursor.moveToFirst()) { "盘点任务不存在" }
            InventoryTask(cursor.string("id"), cursor.string("name"), cursor.long("created_at"))
        }
        require(!taskNameExists(db, checked, id)) { "已存在同名盘点任务" }
        db.update("tasks", ContentValues().apply { put("name", checked) }, "id = ?", arrayOf(id))
        previous.copy(name = checked)
    }

    @Synchronized
    fun addCountry(name: String): Country {
        val country = Country(newId(), checkedName(name, "国家"))
        transaction { db ->
            require(!exists(db, "countries", "name_key = ?", arrayOf(nameKey(country.name)))) {
                "这个国家已存在"
            }
            insertCountry(db, country)
        }
        return country
    }

    @Synchronized
    fun renameCountry(id: String, name: String): Country = transaction { db ->
        val checked = checkedName(name, "国家")
        require(exists(db, "countries", "id = ?", arrayOf(id))) { "国家不存在" }
        require(!exists(db, "countries", "name_key = ? AND id != ?", arrayOf(nameKey(checked), id))) {
            "这个国家已存在"
        }
        db.update("countries", ContentValues().apply {
            put("name", checked); put("name_key", nameKey(checked))
        }, "id = ?", arrayOf(id))
        Country(id, checked)
    }

    @Synchronized
    fun addWarehouse(countryId: String, name: String): Warehouse {
        val warehouse = Warehouse(newId(), countryId, checkedName(name, "仓库"))
        transaction { db ->
            require(exists(db, "countries", "id = ?", arrayOf(countryId))) { "国家不存在" }
            require(!exists(db, "warehouses", "country_id = ? AND name_key = ?",
                arrayOf(countryId, nameKey(warehouse.name)))) { "该国家已存在同名仓库" }
            insertWarehouse(db, warehouse)
        }
        return warehouse
    }

    @Synchronized
    fun renameWarehouse(id: String, name: String): Warehouse = transaction { db ->
        val checked = checkedName(name, "仓库")
        val previous = db.query("warehouses", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
            require(cursor.moveToFirst()) { "仓库不存在" }
            Warehouse(cursor.string("id"), cursor.string("country_id"), cursor.string("name"))
        }
        require(!exists(db, "warehouses", "country_id = ? AND name_key = ? AND id != ?",
            arrayOf(previous.countryId, nameKey(checked), id))) { "该国家已存在同名仓库" }
        db.update("warehouses", ContentValues().apply {
            put("name", checked); put("name_key", nameKey(checked))
        }, "id = ?", arrayOf(id))
        previous.copy(name = checked)
    }

    @Synchronized
    fun save(input: RecordInput, recordId: String? = null): StockRecord = transaction { db ->
        val previous = recordId?.let { findRecord(db, it) ?: error("记录不存在") }
        require(previous?.voided != true) { "已作废的记录不能修改，请重新录入" }
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
        )
        validateRecord(record)
        require(exists(db, "tasks", "id = ?", arrayOf(record.taskId))) { "盘点任务不存在" }
        require(exists(db, "warehouses", "id = ?", arrayOf(record.warehouseId))) { "仓库不存在" }
        if (record.kind == ItemKind.MACHINE && record.sn.isNotEmpty()) {
            activeDuplicate(db, record.sn, record.id)?.let {
                throw DuplicateSnException(it, "SN 已存在于未作废整机记录：${record.sn}")
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
            val previous = findRecord(db, id) ?: error("记录不存在")
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
    fun restoreVoided(id: String): StockRecord = transaction { db ->
        val previous = findRecord(db, id) ?: throw IllegalArgumentException("记录不存在")
        require(previous.voided) { "只能还原已作废的记录" }
        if (previous.kind == ItemKind.MACHINE && previous.sn.isNotEmpty()) {
            activeDuplicate(db, previous.sn, id)?.let {
                throw DuplicateSnException(it, "还原失败，SN 已存在于未作废整机记录：${previous.sn}")
            }
        }
        val now = maxOf(System.currentTimeMillis(), previous.updatedAt)
        val restored = previous.copy(voided = false, updatedAt = now)
        db.update("records", recordValues(restored), "id = ?", arrayOf(id))
        insertChange(db, ChangeLog(newId(), id, "RESTORE", recordJson(previous), recordJson(restored), now))
        restored
    }

    /** Permanently remove one voided record and its associated change history. */
    @Synchronized
    fun deleteVoided(id: String) {
        transaction { db ->
            val record = findRecord(db, id) ?: throw IllegalArgumentException("记录不存在")
            require(record.voided) { "只能彻底删除已作废的记录" }
            db.delete("changes", "record_id = ?", arrayOf(id))
            db.delete("records", "id = ?", arrayOf(id))
        }
    }

    /** Permanently clear voided records in this warehouse only; active records are untouched. */
    @Synchronized
    fun clearVoided(warehouseId: String): Int = transaction { db ->
        require(exists(db, "warehouses", "id = ?", arrayOf(warehouseId))) { "仓库不存在" }
        db.delete("changes", "record_id IN (SELECT id FROM records WHERE warehouse_id = ? AND voided = 1)",
            arrayOf(warehouseId))
        db.delete("records", "warehouse_id = ? AND voided = 1", arrayOf(warehouseId))
    }

    /** Caller supplies the two user confirmations; this removes active records in one warehouse. */
    @Synchronized
    fun clearActive(warehouseId: String): Int = transaction { db ->
        require(exists(db, "warehouses", "id = ?", arrayOf(warehouseId))) { "仓库不存在" }
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
        val tasks = mergeById("盘点任务", current.tasks, snapshot.tasks, InventoryTask::id)
        val countries = mergeById("国家", current.countries, snapshot.countries, Country::id)
        val warehouses = mergeById("仓库", current.warehouses, snapshot.warehouses, Warehouse::id)
        val records = mergeById("库存记录", current.records, snapshot.records, StockRecord::id)
        val changes = mergeById("修改历史", current.changes, snapshot.changes, ChangeLog::id)

        tasks.values.forEach {
            validateId(it.id)
            require(it.name == checkedName(it.name, "盘点任务")) { "盘点任务名称包含首尾空格" }
            require(it.createdAt >= 0) { "盘点任务时间无效" }
        }
        val countryNames = mutableSetOf<String>()
        countries.values.forEach {
            validateId(it.id)
            require(it.name == checkedName(it.name, "国家")) { "国家名称包含首尾空格" }
            require(countryNames.add(nameKey(it.name))) { "备份中存在重名国家：${it.name}" }
        }
        val warehouseNames = mutableSetOf<Pair<String, String>>()
        warehouses.values.forEach {
            validateId(it.id)
            validateId(it.countryId)
            require(it.countryId in countries) { "仓库对应的国家不存在" }
            require(it.name == checkedName(it.name, "仓库")) { "仓库名称包含首尾空格" }
            require(warehouseNames.add(it.countryId to nameKey(it.name))) { "同一国家存在重名仓库：${it.name}" }
        }
        val serials = mutableMapOf<String, StockRecord>()
        records.values.forEach {
            validateRecord(it)
            require(it.taskId in tasks) { "库存记录对应的盘点任务不存在" }
            require(it.warehouseId in warehouses) { "库存记录对应的仓库不存在" }
            if (it.kind == ItemKind.MACHINE && !it.voided && it.sn.isNotEmpty()) {
                val other = serials.put(it.sn, it)
                if (other != null) throw DuplicateSnException(other,
                    "备份含重复的未作废 SN：${it.sn}，整个备份未导入；请先在原设备核对并作废重复记录")
            }
        }
        changes.values.forEach {
            validateId(it.id)
            validateId(it.recordId)
            require(it.recordId in records) { "修改历史对应的库存记录不存在" }
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
        val imported = snapshot.records.filter { it.id !in recordIds }
        imported.forEach { insertRecord(db, it) }
        snapshot.changes.filter { it.id !in changeIds }.forEach { insertChange(db, it) }
        imported.size
    }

    @Synchronized
    override fun close() = helper.close()

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
        require(record.condition in CONDITIONS) { "物品状态须为正常、损坏或待核查" }
        val pending = record.condition == "待核查"
        require(!pending || record.bom.isNotEmpty() || record.sn.isNotEmpty() || record.note.isNotBlank()) {
            "待核查草稿至少填写一个编号或备注"
        }
        if (!pending || record.bom.isNotEmpty()) {
            IdentifierRules.validateBom(record.bom)?.let { throw IllegalArgumentException(it) }
        }
        require(record.bom == record.bom.trim()) { "BOM 包含首尾空格" }
        if (record.kind == ItemKind.MACHINE) {
            if (!pending || record.sn.isNotEmpty()) {
                IdentifierRules.validateSn(record.sn)?.let { throw IllegalArgumentException(it) }
            }
            require(record.sn == record.sn.trim()) { "SN 包含首尾空格" }
            require(record.quantity == 1) { "整机数量必须为 1" }
        } else {
            require(record.sn.isEmpty()) { "配件不能填写 SN" }
            require(record.quantity in 1..MAX_QUANTITY) { "配件数量必须在 1 到 $MAX_QUANTITY 之间" }
        }
        require(record.note.length <= 4000) { "备注不能超过 4000 字" }
        require(record.location.length <= 100 && record.location.none(Char::isISOControl)) { "库位不能超过 100 字，不能包含换行" }
        require(record.sourceBom in SOURCES && record.sourceSn in SOURCES) { "编号录入来源无效" }
        require(record.rawBom.length <= 4000 && record.rawSn.length <= 4000) { "识别原文过长" }
        require(record.createdAt >= 0 && record.updatedAt >= record.createdAt) { "库存记录时间无效" }
    }

    private fun validateChange(change: ChangeLog, taskIds: Set<String>, warehouseIds: Set<String>) {
        require(change.action in setOf("CREATE", "UPDATE", "VOID", "RESTORE")) { "修改历史操作无效" }
        require(change.at >= 0) { "修改历史时间无效" }
        require(change.before.length <= 65536 && change.after.length <= 65536) { "修改历史内容过长" }
        val before = parseAuditJson(change.before)
        val after = parseAuditJson(change.after)
        require((change.action == "CREATE") == (before.length() == 0)) { "修改历史的修改前记录与操作不符" }
        require(after.length() != 0) { "修改历史缺少修改后记录" }
        for (json in listOf(before, after)) {
            if (json.length() == 0) {
                require(change.action == "CREATE" && json === before) { "修改历史缺少修改前记录" }
                continue
            }
            val record = recordFromJson(json)
            validateRecord(record)
            require(record.id == change.recordId) { "修改历史记录 ID 不一致" }
            require(record.taskId in taskIds && record.warehouseId in warehouseIds) { "修改历史关联无效" }
        }
        if (change.action == "RESTORE") {
            require(before.getBoolean("voided") && !after.getBoolean("voided")) { "还原历史的作废状态不正确" }
        }
    }

    private fun parseAuditJson(value: String): JSONObject = try {
        JSONObject(value)
    } catch (e: Exception) {
        throw IllegalArgumentException("修改历史 JSON 无效", e)
    }

    private fun recordFromJson(json: JSONObject): StockRecord = try {
        StockRecord(json.getString("id"), json.getString("taskId"), json.getString("warehouseId"),
            ItemKind.valueOf(json.getString("kind")), json.getString("bom"), json.getString("sn"),
            json.getInt("quantity"), json.getString("condition"), json.getString("note"), json.optString("location", ""),
            json.getString("sourceBom"), json.getString("sourceSn"), json.getString("rawBom"),
            json.getString("rawSn"), json.getLong("createdAt"), json.getLong("updatedAt"),
            json.getBoolean("voided"))
    } catch (e: Exception) {
        throw IllegalArgumentException("修改历史缺少完整库存记录", e)
    }

    private fun recordJson(record: StockRecord): String = JSONObject().apply {
        put("id", record.id)
        put("taskId", record.taskId)
        put("warehouseId", record.warehouseId)
        put("kind", record.kind.name)
        put("bom", record.bom)
        put("sn", record.sn)
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

    private fun <T> mergeById(label: String, current: List<T>, incoming: List<T>, id: (T) -> String): Map<String, T> {
        val combined = current.associateByTo(linkedMapOf(), id)
        val incomingIds = mutableSetOf<String>()
        incoming.forEach { item ->
            val itemId = id(item)
            validateId(itemId)
            require(incomingIds.add(itemId)) { "备份中的$label ID 重复：$itemId" }
            val existing = combined[itemId]
            require(existing == null || existing == item) { "$label ID 已存在但内容不同，未导入：$itemId" }
            combined[itemId] = item
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
            "${label}名称须为 1 至 100 字，不能包含换行或控制字符"
        }
    }

    private fun validateId(value: String) {
        require(ID_PATTERN.matches(value)) { "数据 ID 无效" }
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
            db.insertOrThrow("tasks", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("name", "首次盘点")
                put("created_at", System.currentTimeMillis())
            })
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            require(oldVersion in 1..2 && newVersion == 3) { "未支持的数据库升级：$oldVersion → $newVersion" }
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
    }

    companion object {
        const val DATABASE_NAME = "fieldstock.db"
        private const val DATABASE_VERSION = 3
        private const val GLOBAL_SN_INDEX = "CREATE UNIQUE INDEX unique_active_sn ON records(sn) WHERE kind = 'MACHINE' AND voided = 0 AND sn != ''"
        private const val MIGRATION_NOTICE_KEY = "migration_voided_duplicates"
        private const val MAX_QUANTITY = 1_000_000_000
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val SOURCES = setOf("manual", "ocr")
        private val CONDITIONS = setOf("正常", "损坏", "待核查")
    }
}
