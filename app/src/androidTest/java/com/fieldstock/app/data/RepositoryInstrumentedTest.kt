package com.fieldstock.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.database.DatabaseErrorHandler
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.model.DuplicateSnException
import com.fieldstock.app.model.DuplicateMode
import com.fieldstock.app.model.InventorySnapshot
import com.fieldstock.app.model.ItemKind
import com.fieldstock.app.model.RecordInput
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Exercises the device's actual SQLite implementation without touching the user's database. */
@RunWith(AndroidJUnit4::class)
class RepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var repository: InventoryRepository

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        context = IsolatedDatabaseContext(app, "repository-test-${UUID.randomUUID()}.db")
        repository = InventoryRepository(context)
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
    }

    @Test
    fun firstCountSavesQuantityLocationAndLeadingZeros() {
        val first = repository.snapshot()
        assertEquals(1, first.tasks.size)
        assertTrue(first.countries.isEmpty())
        assertTrue(first.records.isEmpty())
        val country = repository.addCountry("挪威")
        val warehouse = repository.addWarehouse(country.id, "奥斯陆仓库")
        val record = repository.save(RecordInput(
            taskId = first.tasks.single().id, warehouseId = warehouse.id, kind = ItemKind.PART,
            bom = "000Ab123", sn = "", quantity = 40, condition = "正常", note = "首次数量",
            location = "01-02",
        ))
        val saved = repository.snapshot()
        assertEquals(record, saved.records.single())
        assertEquals("000Ab123", saved.records.single().bom)
        assertEquals("01-02", saved.records.single().location)
        assertEquals(40, saved.records.single().quantity)
        assertEquals("CREATE", saved.changes.single().action)
    }

    @Test
    fun duplicateSerialIsBlockedAcrossCountriesWithoutPartialWrite() {
        val task = repository.snapshot().tasks.single()
        val norway = repository.addCountry("挪威")
        val sweden = repository.addCountry("瑞典")
        val oslo = repository.addWarehouse(norway.id, "奥斯陆")
        val stockholm = repository.addWarehouse(sweden.id, "斯德哥尔摩")
        val input = RecordInput(task.id, oslo.id, ItemKind.MACHINE, "BOM01", "0001-Abc99", 50, "正常", "")
        val saved = repository.save(input)
        assertEquals(1, saved.quantity)
        val before = repository.snapshot()
        val otherLegacyTask = repository.addTask("旧任务兼容检查")
        val failure = runCatching { repository.save(input.copy(warehouseId = stockholm.id, taskId = otherLegacyTask.id)) }.exceptionOrNull()
        assertTrue(failure is DuplicateSnException)
        assertEquals(saved.id, (failure as DuplicateSnException).existing.id)
        assertEquals(before.records, repository.snapshot().records)
        assertEquals(before.changes, repository.snapshot().changes)
    }

    @Test
    fun incompleteDraftsCoexistAndCannotBecomeConfirmedUntilIdentifiersAreComplete() {
        val task = repository.snapshot().tasks.single()
        val country = repository.addCountry("挪威")
        val warehouse = repository.addWarehouse(country.id, "待核查区")
        val input = RecordInput(task.id, warehouse.id, ItemKind.MACHINE, "", "", 1, "待核查", "A架标签破损")
        val first = repository.save(input)
        repository.save(input.copy(note = "B架标签缺失"))
        assertEquals(2, repository.snapshot().records.count { it.sn.isEmpty() && it.condition == "待核查" })
        val before = repository.snapshot()
        assertTrue(runCatching { repository.save(input.copy(condition = "正常"), first.id) }.isFailure)
        assertTrue(runCatching { repository.save(input.copy(note = "")) }.isFailure)
        assertEquals(before, repository.snapshot())
        val completed = repository.save(input.copy(bom = "000Ab1", sn = "0001-AA", condition = "正常"), first.id)
        assertEquals(first.id, completed.id)
        assertEquals("正常", completed.condition)
        val output = ByteArrayOutputStream()
        InventoryFiles.writeBackup(repository.snapshot(), output)
        val decoded = InventoryFiles.readBackup(ByteArrayInputStream(output.toByteArray()))
        assertEquals(0, repository.restore(decoded))
    }

    @Test
    fun backupRoundTripOnFreshDatabaseIsIdempotentAndKeepsAudit() {
        val task = repository.snapshot().tasks.single()
        val country = repository.addCountry("德国")
        val warehouse = repository.addWarehouse(country.id, "柏林仓")
        val input = RecordInput(task.id, warehouse.id, ItemKind.PART, "0001Aa", "", 7, "正常", "", "A-01")
        val record = repository.save(input)
        repository.save(input.copy(quantity = 9), record.id)
        val machine = repository.save(input.copy(kind = ItemKind.MACHINE, sn = "AA-001", quantity = 1))
        repository.voidRecord(machine.id)
        repository.restoreVoided(machine.id)
        val backup = repository.snapshot()
        assertEquals(1, backup.changes.count { it.action == "RESTORE" })
        val output = ByteArrayOutputStream()
        InventoryFiles.writeBackup(backup, output)
        val decoded = InventoryFiles.readBackup(ByteArrayInputStream(output.toByteArray()))
        repository.close()
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        repository = InventoryRepository(context)
        assertEquals(2, repository.restore(decoded))
        val restored = repository.snapshot()
        assertEquals(backup.records.toSet(), restored.records.toSet())
        assertEquals(backup.changes.toSet(), restored.changes.toSet())
        assertEquals(0, repository.restore(decoded))
        assertEquals(restored, repository.snapshot())
        val conflict = decoded.records.first().copy(note = "conflict")
        assertTrue(runCatching { repository.restore(InventorySnapshot(records = listOf(conflict))) }.isFailure)
        assertEquals(restored, repository.snapshot())
    }

    @Test
    fun closingAndReopeningKeepsCountsAndVoidedHistory() {
        val task = repository.snapshot().tasks.single()
        val country = repository.addCountry("瑞典")
        val warehouse = repository.addWarehouse(country.id, "仓库一")
        val input = RecordInput(task.id, warehouse.id, ItemKind.PART, "0000001", "", 6, "损坏", "外箱破损")
        repository.save(input)
        val voided = repository.save(input.copy(quantity = 2))
        repository.voidRecord(voided.id)
        val before = repository.snapshot()
        repository.close()
        repository = InventoryRepository(context)
        val after = repository.snapshot()
        assertEquals(before, after)
        assertEquals(6L, after.records.filter { !it.voided }.sumOf { it.quantity.toLong() })
        assertEquals(1, after.changes.count { it.action == "VOID" })
    }

    @Test
    fun restoringConflictAndPermanentClearsAreAtomicAndWarehouseScoped() {
        val task = repository.snapshot().tasks.single()
        val country = repository.addCountry("测试国")
        val current = repository.addWarehouse(country.id, "当前仓")
        val other = repository.addWarehouse(country.id, "其他仓")
        val input = RecordInput(task.id, current.id, ItemKind.PART, "BOM01", "", 7, "损坏", "外箱破损")
        repository.save(input)
        val voidedPart = repository.save(input.copy(quantity = 5))
        repository.voidRecord(voidedPart.id)
        val otherPart = repository.save(input.copy(warehouseId = other.id, quantity = 6))
        val otherVoided = repository.save(input.copy(warehouseId = other.id, quantity = 2))
        repository.voidRecord(otherVoided.id)
        val voidedMachine = repository.save(input.copy(kind = ItemKind.MACHINE, sn = "AA-001"))
        repository.voidRecord(voidedMachine.id)
        val replacement = repository.save(input.copy(kind = ItemKind.MACHINE, sn = "AA-001", warehouseId = other.id))
        val beforeConflict = repository.snapshot()
        assertEquals(0, repository.restoreVoided(voidedMachine.id).processed)
        assertEquals(beforeConflict, repository.snapshot())
        repository.deleteVoided(voidedMachine.id)
        assertTrue(repository.snapshot().changes.none { it.recordId == voidedMachine.id })
        assertEquals(1, repository.restoreVoided(voidedPart.id).processed)
        val restored = repository.snapshot().records.single { it.id == voidedPart.id }
        assertEquals("损坏", restored.condition)
        assertEquals(5, restored.quantity)
        assertEquals(2, repository.clearActive(current.id))
        assertEquals(1, repository.clearVoided(other.id))
        val finalState = repository.snapshot()
        assertEquals(setOf(otherPart.id, replacement.id), finalState.records.mapTo(mutableSetOf()) { it.id })
        assertEquals(setOf(otherPart.id, replacement.id), finalState.changes.mapTo(mutableSetOf()) { it.recordId })
        assertTrue(runCatching { repository.deleteVoided(replacement.id) }.isFailure)
        assertEquals(finalState, repository.snapshot())
    }

    @Test
    fun versionTwoMigrationVoidsDuplicatePreservesHistoryAndEnforcesGlobalDatabaseIndex() {
        val task = repository.snapshot().tasks.single()
        val legacyTask = repository.addTask("旧任务")
        val country = repository.addCountry("测试国")
        val warehouse = repository.addWarehouse(country.id, "测试仓")
        val input = RecordInput(task.id, warehouse.id, ItemKind.MACHINE, "BOM01", "AA-001", 1, "正常", "")
        val first = repository.save(input)
        repository.close()
        val databasePath = context.getDatabasePath(InventoryRepository.DATABASE_NAME).absolutePath
        SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP INDEX unique_active_sn")
            db.execSQL("CREATE UNIQUE INDEX unique_active_sn ON records(task_id, sn) WHERE kind = 'MACHINE' AND voided = 0 AND sn != ''")
            val duplicate = db.query("records", null, "id = ?", arrayOf(first.id), null, null, null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                ContentValues().also { DatabaseUtils.cursorRowToContentValues(cursor, it) }
            }
            duplicate.put("id", "legacy-newer")
            duplicate.put("task_id", legacyTask.id)
            duplicate.put("updated_at", first.updatedAt + 1_000)
            db.insertOrThrow("records", null, duplicate)
            db.execSQL("DROP TABLE metadata")
            db.version = 2
        }
        repository = InventoryRepository(context)
        val state = repository.snapshot()
        assertEquals(2, state.records.size)
        assertEquals("legacy-newer", state.records.single { !it.voided }.id)
        assertEquals(first.id, state.records.single { it.voided }.id)
        assertEquals(1, state.changes.count { it.action == "VOID" })
        assertEquals(1, repository.pendingMigrationVoidedCount())
        assertEquals(0, repository.restore(state))
        assertEquals(0, repository.restoreVoided(first.id).processed)
        repository.close()
        SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val duplicate = db.query("records", null, "id = ?", arrayOf("legacy-newer"), null, null, null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                ContentValues().also { DatabaseUtils.cursorRowToContentValues(cursor, it) }
            }
            duplicate.put("id", "must-fail-global-index")
            duplicate.put("task_id", task.id)
            assertTrue(runCatching { db.insertOrThrow("records", null, duplicate) }.exceptionOrNull() is SQLiteConstraintException)
        }
        repository = InventoryRepository(context)
        assertEquals(state, repository.snapshot())
        assertEquals(1, repository.pendingMigrationVoidedCount())
        repository.acknowledgeMigrationNotice()
        repository.close()
        repository = InventoryRepository(context)
        assertEquals(0, repository.pendingMigrationVoidedCount())
    }

    @Test fun recoveryLargerThanCursorWindowSurvivesRestartAndRestoresExactInventory() {
        val task = repository.snapshot().tasks.single()
        val warehouse = repository.addWarehouse(repository.addCountry("恢复国").id, "恢复仓")
        repository.importRecords(List(300) { i -> RecordInput(task.id, warehouse.id, ItemKind.PART, "P$i", "", 1, "正常", "恢复验证".repeat(800)) }, DuplicateMode.NEW_ENTRY)
        val before = repository.snapshot()
        repository.recoverable("清空大批库存") { it.clearActive(warehouse.id) }
        val point = repository.recoveryPoints().single()
        repository.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(InventoryRepository.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT length(before_blob) FROM recovery_points WHERE id = ?", arrayOf(point.id)).use { c ->
                assertTrue(c.moveToFirst()); assertTrue("Exercise a snapshot over 2 MiB", c.getLong(0) > 2 * 1024 * 1024)
            }
        }
        repository = InventoryRepository(context)
        assertEquals(300, repository.undoRecovery(point.id).processed)
        assertEquals(before, repository.snapshot())
        assertTrue(repository.recoveryPoints().single().undone)
        assertEquals(0, repository.restore(repository.snapshot()))
    }

    @Test fun conflictingCountryNameDuringUndoKeepsDeviceDatabaseIntact() {
        val task = repository.snapshot().tasks.single()
        val country = repository.addCountry("同名国")
        val warehouse = repository.addWarehouse(country.id, "原仓")
        repository.save(RecordInput(task.id, warehouse.id, ItemKind.PART, "BOM01", "", 3, "正常", ""))
        repository.recoverable("删除国家") { it.deleteCountry(country.id) }
        val point = repository.recoveryPoints().single()
        repository.addCountry(country.name)
        val before = repository.snapshot()
        assertTrue(runCatching { repository.undoRecovery(point.id) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, repository.snapshot())
        assertFalse(repository.recoveryPoints().single().undone)
    }

    /** Repository requests applicationContext, so preserve this wrapper at that boundary too. */
    private class IsolatedDatabaseContext(base: Context, private val testDatabaseName: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = baseContext.getDatabasePath(testDatabaseName)
        override fun deleteDatabase(name: String): Boolean = baseContext.deleteDatabase(testDatabaseName)
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = baseContext.openOrCreateDatabase(testDatabaseName, mode, factory)
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = baseContext.openOrCreateDatabase(testDatabaseName, mode, factory, errorHandler)
    }
}
