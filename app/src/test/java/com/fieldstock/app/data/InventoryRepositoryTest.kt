package com.fieldstock.app.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.fieldstock.app.model.Country
import com.fieldstock.app.model.DuplicateSnException
import com.fieldstock.app.model.DuplicateMode
import com.fieldstock.app.model.ImportMetadata
import com.fieldstock.app.model.InventorySnapshot
import com.fieldstock.app.model.InventoryTask
import com.fieldstock.app.model.ItemKind
import com.fieldstock.app.model.RecordInput
import com.fieldstock.app.model.StockRecord
import com.fieldstock.app.model.Warehouse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class InventoryRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: InventoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        repository = InventoryRepository(context)
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
    }

    @Test
    fun newInstallHasOneTaskAndNoInventedInventoryOrLocations() {
        val state = repository.snapshot()
        assertEquals(listOf("首次盘点"), state.tasks.map { it.name })
        assertTrue(state.countries.isEmpty())
        assertTrue(state.warehouses.isEmpty())
        assertTrue(state.records.isEmpty())
        assertTrue(state.changes.isEmpty())
    }

    @Test
    fun locationNamesAreUniqueWithinTheirProperScope() {
        val firstCountry = repository.addCountry("  Norway  ")
        assertEquals("Norway", firstCountry.name)
        assertThrows(IllegalArgumentException::class.java) { repository.addCountry("NORWAY") }
        val secondCountry = repository.addCountry("Sweden")
        repository.addWarehouse(firstCountry.id, " Main ")
        assertThrows(IllegalArgumentException::class.java) { repository.addWarehouse(firstCountry.id, "main") }
        repository.addWarehouse(secondCountry.id, "Main")
        assertThrows(IllegalArgumentException::class.java) { repository.addWarehouse("missing", "Main") }
        assertEquals(2, repository.snapshot().warehouses.size)
    }

    @Test
    fun renamingTaskCountryAndWarehousePreservesStockIdentityAndAudit() {
        val fixture = fixture()
        val record = repository.save(input(fixture).copy(quantity = 12, location = "A-03"))
        val history = repository.snapshot().changes
        val task = repository.renameTask(fixture.task.id, "  十月盘点  ")
        val country = repository.renameCountry(fixture.country.id, "Norway")
        val warehouse = repository.renameWarehouse(fixture.warehouse.id, "Oslo")
        assertEquals(fixture.task.id, task.id)
        assertEquals(fixture.task.createdAt, task.createdAt)
        assertEquals("十月盘点", task.name)
        assertEquals(fixture.country.id, country.id)
        assertEquals(fixture.warehouse.countryId, warehouse.countryId)
        val state = repository.snapshot()
        assertEquals(record, state.records.single())
        assertEquals(history, state.changes)
        assertEquals("Norway", state.countries.single().name)
        assertEquals("Oslo", state.warehouses.single().name)
        assertEquals(0, repository.restore(state))
    }

    @Test
    fun renameRejectsNameCollisionsWithoutChangingAnyRows() {
        val fixture = fixture()
        val otherTask = repository.addTask("Other task")
        val otherCountry = repository.addCountry("Sweden")
        val otherWarehouse = repository.addWarehouse(fixture.country.id, "Other warehouse")
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.renameTask(fixture.task.id, "OTHER TASK") }
        assertThrows(IllegalArgumentException::class.java) { repository.addTask("other TASK") }
        assertThrows(IllegalArgumentException::class.java) { repository.renameCountry(fixture.country.id, "SWEDEN") }
        assertThrows(IllegalArgumentException::class.java) { repository.renameWarehouse(fixture.warehouse.id, "OTHER WAREHOUSE") }
        assertThrows(IllegalArgumentException::class.java) { repository.renameTask(otherTask.id, " ") }
        assertThrows(IllegalArgumentException::class.java) { repository.renameCountry(otherCountry.id, "bad\nname") }
        assertThrows(IllegalArgumentException::class.java) { repository.renameWarehouse(otherWarehouse.id, " ") }
        assertEquals(before, repository.snapshot())
    }

    @Test
    fun warehouseRenameAllowsSameNameInAnotherCountryAndCaseOnlyCorrection() {
        val fixture = fixture()
        val otherCountry = repository.addCountry("Sweden")
        repository.addWarehouse(otherCountry.id, "Main")
        assertEquals("Main", repository.renameWarehouse(fixture.warehouse.id, "Main").name)
        assertEquals("MAIN", repository.renameWarehouse(fixture.warehouse.id, "MAIN").name)
        assertEquals("挪威", repository.renameCountry(fixture.country.id, "挪威").name)
        assertThrows(IllegalArgumentException::class.java) { repository.renameWarehouse("missing", "Main") }
        assertThrows(IllegalArgumentException::class.java) { repository.renameCountry("missing", "Denmark") }
        assertThrows(IllegalArgumentException::class.java) { repository.renameTask("missing", "Another task") }
    }

    @Test
    fun exactSerialDuplicateIsBlockedAcrossWarehousesAndLegacyTasks() {
        val fixture = fixture()
        val first = repository.save(input(fixture, ItemKind.MACHINE, sn = "Ab12-0009").copy(quantity = 700))
        assertEquals(1, first.quantity)
        val secondWarehouse = repository.addWarehouse(fixture.country.id, "Other")
        val error = assertThrows(DuplicateSnException::class.java) {
            repository.save(input(fixture, ItemKind.MACHINE, sn = first.sn).copy(warehouseId = secondWarehouse.id))
        }
        assertEquals(first.id, error.existing.id)
        repository.save(input(fixture, ItemKind.MACHINE, sn = "ab12-0009"))
        val otherTask = repository.addTask("复盘")
        assertThrows(DuplicateSnException::class.java) {
            repository.save(input(fixture, ItemKind.MACHINE, sn = first.sn).copy(taskId = otherTask.id))
        }
        assertEquals(2, repository.snapshot().records.size)
    }

    @Test
    fun editingKeepsRecordIdentityAndStoresCompleteBeforeAndAfter() {
        val fixture = fixture()
        val first = repository.save(input(fixture).copy(quantity = 2, location = "A-01"))
        val edited = repository.save(input(fixture).copy(quantity = 7, note = "箱内补录", location = "B-02"), first.id)
        assertEquals(first.id, edited.id)
        assertEquals(first.createdAt, edited.createdAt)
        val history = repository.snapshot().changes.single { it.action == "UPDATE" }
        val before = JSONObject(history.before)
        val after = JSONObject(history.after)
        assertEquals(2, before.getInt("quantity"))
        assertEquals("A-01", before.getString("location"))
        assertEquals(7, after.getInt("quantity"))
        assertEquals("B-02", after.getString("location"))
        assertEquals("箱内补录", after.getString("note"))
        assertEquals(first.id, after.getString("id"))
        assertEquals(1, repository.snapshot().records.size)
    }

    @Test
    fun voidingIsAuditedAndIdempotentAndReleasesSerial() {
        val fixture = fixture()
        val record = repository.save(input(fixture, ItemKind.MACHINE, sn = "001-ABC"))
        repository.voidRecord(record.id)
        repository.voidRecord(record.id)
        repository.save(input(fixture, ItemKind.MACHINE, sn = record.sn))
        val state = repository.snapshot()
        assertEquals(1, state.records.count { !it.voided })
        assertEquals(1, state.records.count { it.voided })
        assertEquals(1, state.changes.count { it.action == "VOID" })
        val audit = state.changes.single { it.action == "VOID" }
        assertFalse(JSONObject(audit.before).getBoolean("voided"))
        assertTrue(JSONObject(audit.after).getBoolean("voided"))
        assertThrows(IllegalArgumentException::class.java) { repository.save(input(fixture), record.id) }
    }

    @Test
    fun restoringVoidedPreservesOriginalStateAndWritesRestorableAudit() {
        val fixture = fixture()
        val first = repository.save(input(fixture).copy(condition = "损坏", quantity = 7, note = "包装破损", location = "A-01"))
        repository.voidRecord(first.id)
        assertEquals(1, repository.restoreVoided(first.id).processed)
        val restored = repository.snapshot().records.single()
        assertEquals(first.copy(updatedAt = restored.updatedAt), restored)
        val state = repository.snapshot()
        val audit = state.changes.single { it.action == "RESTORE" }
        assertTrue(JSONObject(audit.before).getBoolean("voided"))
        assertFalse(JSONObject(audit.after).getBoolean("voided"))
        assertEquals(7, JSONObject(audit.after).getInt("quantity"))
        assertEquals(0, repository.restore(state))
        assertThrows(IllegalArgumentException::class.java) { repository.restoreVoided(first.id) }
    }

    @Test
    fun restoringKnownSerialConflictKeepsVoidedRecordAndAuditUnchanged() {
        val fixture = fixture()
        val first = repository.save(input(fixture, ItemKind.MACHINE, sn = "AA-001"))
        repository.voidRecord(first.id)
        val otherTask = repository.addTask("旧兼容任务")
        val otherWarehouse = repository.addWarehouse(fixture.country.id, "另一个仓库")
        val replacement = repository.save(input(fixture, ItemKind.MACHINE, sn = first.sn).copy(taskId = otherTask.id, warehouseId = otherWarehouse.id))
        val before = repository.snapshot()
        val conflict = repository.restoreVoided(first.id)
        assertEquals(0, conflict.processed)
        assertEquals(first.id, conflict.duplicateMachines.single().id)
        assertEquals(before, repository.snapshot())
        repository.voidRecord(replacement.id)
        assertEquals(1, repository.restoreVoided(first.id).processed)
        assertFalse(repository.snapshot().records.single { it.id == first.id }.voided)
    }

    @Test
    fun emptySerialDraftsAndVoidedDuplicatesCanBeRestoredWithoutOccupyingSerial() {
        val fixture = fixture()
        val draft = input(fixture, ItemKind.MACHINE).copy(condition = "待核查")
        val first = repository.save(draft)
        val second = repository.save(draft.copy(bom = "BOM02"))
        repository.voidRecord(first.id)
        repository.voidRecord(second.id)
        repository.restoreVoided(first.id)
        repository.restoreVoided(second.id)
        assertEquals(2, repository.snapshot().records.count { !it.voided && it.sn.isEmpty() })
    }

    @Test
    fun permanentDeleteOnlyAcceptsVoidedAndRemovesAssociatedHistory() {
        val fixture = fixture()
        val active = repository.save(input(fixture))
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.deleteVoided(active.id) }
        assertEquals(before, repository.snapshot())
        val removed = repository.save(input(fixture).copy(quantity = 2))
        repository.voidRecord(removed.id)
        repository.deleteVoided(removed.id)
        val after = repository.snapshot()
        assertEquals(listOf(active), after.records)
        assertTrue(after.changes.none { it.recordId == removed.id })
        assertEquals(before.changes, after.changes)
        assertThrows(IllegalArgumentException::class.java) { repository.deleteVoided(removed.id) }
        assertEquals(0, repository.restore(after))
    }

    @Test
    fun clearingVoidedIsScopedToWarehouseAndPreservesActiveAndOtherWarehouse() {
        val fixture = fixture()
        val other = repository.addWarehouse(fixture.country.id, "另一仓")
        val active = repository.save(input(fixture))
        val removed = repository.save(input(fixture).copy(quantity = 2))
        val otherVoided = repository.save(input(fixture).copy(warehouseId = other.id, quantity = 3))
        repository.voidRecord(removed.id)
        repository.voidRecord(otherVoided.id)
        assertEquals(1, repository.clearVoided(fixture.warehouse.id))
        val state = repository.snapshot()
        assertEquals(setOf(active.id, otherVoided.id), state.records.mapTo(mutableSetOf()) { it.id })
        assertTrue(state.changes.none { it.recordId == removed.id })
        assertTrue(state.changes.any { it.recordId == otherVoided.id && it.action == "VOID" })
        assertEquals(0, repository.clearVoided(fixture.warehouse.id))
        assertThrows(IllegalArgumentException::class.java) { repository.clearVoided("missing") }
        assertEquals(state, repository.snapshot())
    }

    @Test
    fun clearingActiveIsScopedToWarehouseAndPreservesVoidedAndOtherWarehouse() {
        val fixture = fixture()
        val other = repository.addWarehouse(fixture.country.id, "另一仓")
        val removed = repository.save(input(fixture))
        val voided = repository.save(input(fixture).copy(quantity = 2))
        val otherActive = repository.save(input(fixture).copy(warehouseId = other.id, quantity = 3))
        repository.voidRecord(voided.id)
        assertEquals(1, repository.clearActive(fixture.warehouse.id))
        val state = repository.snapshot()
        assertEquals(setOf(voided.id, otherActive.id), state.records.mapTo(mutableSetOf()) { it.id })
        assertTrue(state.changes.none { it.recordId == removed.id })
        assertTrue(state.changes.any { it.recordId == voided.id && it.action == "VOID" })
        assertEquals(0, repository.clearActive(fixture.warehouse.id))
        assertThrows(IllegalArgumentException::class.java) { repository.clearActive("missing") }
        assertEquals(state, repository.snapshot())
    }

    @Test
    fun pendingDraftsAllowMissingIdentifiersAndMultipleEmptySerials() {
        val fixture = fixture()
        val first = repository.save(input(fixture, ItemKind.MACHINE).copy(condition = "待核查"))
        val noteOnly = repository.save(input(fixture, ItemKind.MACHINE).copy(
            condition = "待核查", bom = "", note = "A架有一台，标签破损", location = "A-01"))
        val serialOnly = repository.save(input(fixture, ItemKind.MACHINE, sn = "AA-001").copy(
            condition = "待核查", bom = ""))
        val part = repository.save(input(fixture).copy(condition = "待核查", bom = "", note = "外箱无编号", quantity = 3))
        assertEquals("", first.sn)
        assertEquals("", noteOnly.sn)
        assertEquals("", serialOnly.bom)
        assertEquals(3, part.quantity)
        val state = repository.snapshot()
        assertEquals(4, state.records.size)
        assertEquals(2, state.records.count { it.kind == ItemKind.MACHINE && it.sn.isEmpty() })
        assertEquals(0, repository.restore(state))
    }

    @Test
    fun pendingStillRejectsBadIdentifiersEmptyInformationAndInvalidStatus() {
        val fixture = fixture()
        val before = repository.snapshot()
        val invalid = listOf(
            input(fixture, ItemKind.MACHINE).copy(condition = "待核查", bom = "", note = " "),
            input(fixture).copy(condition = "待核查", bom = "", location = "A-01"),
            input(fixture).copy(condition = "待核查", bom = "ABC/123", note = "有备注也不能放过无效编号"),
            input(fixture, ItemKind.MACHINE, sn = "AAA--BBB").copy(condition = "待核查"),
            input(fixture).copy(condition = "待核查", quantity = 0),
            input(fixture).copy(condition = "其他"),
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { repository.save(value) }
        }
        assertEquals(before, repository.snapshot())
    }

    @Test
    fun promotingDraftToConfirmedRequiresCompleteIdentifiers() {
        val fixture = fixture()
        val machineInput = input(fixture, ItemKind.MACHINE).copy(condition = "待核查", bom = "", note = "标签待查")
        val machine = repository.save(machineInput)
        assertThrows(IllegalArgumentException::class.java) {
            repository.save(machineInput.copy(condition = "正常"), machine.id)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.save(machineInput.copy(condition = "损坏", bom = "BOM01"), machine.id)
        }
        val confirmed = repository.save(machineInput.copy(condition = "正常", bom = "BOM01", sn = "AA-001"), machine.id)
        assertEquals("正常", confirmed.condition)
        val partInput = input(fixture).copy(condition = "待核查", bom = "", note = "配件标签缺失")
        val part = repository.save(partInput)
        assertThrows(IllegalArgumentException::class.java) {
            repository.save(partInput.copy(condition = "正常"), part.id)
        }
        assertEquals("损坏", repository.save(partInput.copy(condition = "损坏", bom = "PART01"), part.id).condition)
    }

    @Test
    fun pendingWithKnownSerialStillReservesThatSerialAndRestoreChecksDrafts() {
        val fixture = fixture()
        val pending = repository.save(input(fixture, ItemKind.MACHINE, sn = "AA-001").copy(condition = "待核查", bom = ""))
        assertThrows(DuplicateSnException::class.java) {
            repository.save(input(fixture, ItemKind.MACHINE, sn = pending.sn))
        }
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            repository.restore(InventorySnapshot(records = listOf(pending.copy(id = "empty-draft", sn = "", note = ""))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.restore(InventorySnapshot(records = listOf(pending.copy(id = "bad-status", condition = "其他"))))
        }
        assertEquals(before, repository.snapshot())
    }

    @Test
    fun versionOneDatabaseUpgradesSerialIndexWithoutLosingRecords() {
        val fixture = fixture()
        val existing = repository.save(input(fixture, ItemKind.MACHINE, sn = "AA-001"))
        val before = repository.snapshot()
        repository.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(InventoryRepository.DATABASE_NAME).absolutePath,
            null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP INDEX IF EXISTS unique_active_sn")
            db.execSQL("CREATE UNIQUE INDEX unique_active_sn ON records(task_id, sn) WHERE kind = 'MACHINE' AND voided = 0")
            db.version = 1
        }
        repository = InventoryRepository(context)
        assertEquals(before, repository.snapshot())
        val draft = input(fixture, ItemKind.MACHINE).copy(condition = "待核查")
        repository.save(draft)
        repository.save(draft.copy(bom = "BOM02"))
        assertEquals(2, repository.snapshot().records.count { it.sn.isEmpty() })
        assertThrows(DuplicateSnException::class.java) { repository.save(input(fixture, ItemKind.MACHINE, sn = existing.sn)) }
    }

    @Test
    fun invalidWritesCannotCreatePartialRecordsOrAuditEntries() {
        val fixture = fixture()
        val before = repository.snapshot()
        val invalid = listOf(
            input(fixture).copy(quantity = 0),
            input(fixture).copy(quantity = 1_000_000_001),
            input(fixture).copy(bom = "ABC/999"),
            input(fixture).copy(bom = "123456789012345"),
            input(fixture).copy(taskId = "missing"),
            input(fixture).copy(warehouseId = "missing"),
            input(fixture, ItemKind.MACHINE, sn = "AAA-BBB-CCC"),
            input(fixture, ItemKind.MACHINE, sn = "-ABC"),
            input(fixture, ItemKind.MACHINE, sn = "12345678-1234567"),
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { repository.save(value) }
        }
        assertEquals(before, repository.snapshot())
    }

    @Test
    fun recordsAndAuditSurviveClosingAndReopeningDatabase() {
        val fixture = fixture()
        repository.save(input(fixture).copy(bom = "000123Ab", location = "01-02", quantity = 40))
        val before = repository.snapshot()
        repository.close()
        repository = InventoryRepository(context)
        assertEquals(before, repository.snapshot())
    }

    @Test
    fun backupRestoresOnFreshInstallAndRepeatedImportAddsNothing() {
        val fixture = fixture()
        val machine = repository.save(input(fixture, ItemKind.MACHINE, sn = "0001-Abc"))
        val part = repository.save(input(fixture).copy(quantity = 4, location = "Shelf-09"))
        repository.save(input(fixture).copy(quantity = 6, location = "Shelf-09"), part.id)
        repository.voidRecord(machine.id)
        val backup = repository.snapshot()
        repository.close()
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        repository = InventoryRepository(context)
        assertEquals(2, repository.restore(backup))
        val firstImport = repository.snapshot()
        assertEquals(backup.records.toSet(), firstImport.records.toSet())
        assertEquals(backup.changes.toSet(), firstImport.changes.toSet())
        assertEquals(0, repository.restore(backup))
        assertEquals(firstImport, repository.snapshot())
    }

    @Test
    fun conflictingExistingIdNeverOverwritesOrImportsOtherRows() {
        val fixture = fixture()
        val record = repository.save(input(fixture))
        val before = repository.snapshot()
        val malicious = InventorySnapshot(
            tasks = listOf(InventoryTask("new-task", "不得写入的任务", 1)),
            records = listOf(record.copy(note = "changed without review")),
        )
        assertThrows(IllegalArgumentException::class.java) { repository.restore(malicious) }
        assertEquals(before, repository.snapshot())
    }

    @Test
    fun restoreChecksCombinedSerialsIncludingExistingRecordsAndVoidedRecords() {
        val fixture = fixture()
        val first = repository.save(input(fixture, ItemKind.MACHINE, sn = "AbC-123"))
        val before = repository.snapshot()
        val duplicate = first.copy(id = "second-machine")
        val incoming = InventorySnapshot(records = listOf(duplicate))
        assertEquals(1, repository.restore(incoming))
        assertTrue(repository.snapshot().records.single { it.id == duplicate.id }.voided)
        val imported = repository.snapshot()
        assertEquals(0, repository.restore(incoming))
        assertEquals(imported, repository.snapshot())
        assertEquals(2, repository.snapshot().records.size)
    }

    @Test
    fun invalidBackupIdsRelationsQuantitiesAndHistoryRollbackEntireImport() {
        val fixture = fixture()
        val record = repository.save(input(fixture))
        val before = repository.snapshot()
        val invalidRecords = listOf(
            record.copy(id = "../bad-id"),
            record.copy(id = "new-record", warehouseId = "missing"),
            record.copy(id = "new-record", quantity = 0),
            record.copy(id = "new-record", kind = ItemKind.MACHINE, sn = "AA-BB", quantity = 2),
        )
        invalidRecords.forEach { invalid ->
            val candidate = InventorySnapshot(
                countries = listOf(Country("new-country", "不得写入的国家")),
                records = listOf(invalid),
            )
            assertThrows(IllegalArgumentException::class.java) { repository.restore(candidate) }
            assertEquals(before, repository.snapshot())
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.restore(InventorySnapshot(records = listOf(record, record)))
        }
        val audit = before.changes.single()
        assertThrows(IllegalArgumentException::class.java) {
            repository.restore(InventorySnapshot(changes = listOf(audit.copy(id = "bad-audit", after = "{}"))))
        }
        assertEquals(before, repository.snapshot())
    }

    @Test
    fun restoreRejectsBothIncomingSerialCollisionsBeforeWritingAnything() {
        val fixture = fixture()
        val template = StockRecord(
            id = "machine-a", taskId = fixture.task.id, warehouseId = fixture.warehouse.id,
            kind = ItemKind.MACHINE, bom = "000Ab", sn = "ABC-001", quantity = 1,
            condition = "正常", note = "", location = "", sourceBom = "manual", sourceSn = "manual",
            rawBom = "", rawSn = "", createdAt = 10, updatedAt = 10, voided = false,
        )
        val before = repository.snapshot()
        val otherTask = InventoryTask("legacy-task", "旧任务", 10)
        val incoming = InventorySnapshot(tasks = listOf(otherTask),
            records = listOf(template, template.copy(id = "machine-b", taskId = otherTask.id)))
        assertEquals(2, repository.restore(incoming))
        assertEquals(1, repository.snapshot().records.count { !it.voided })
        assertEquals(1, repository.snapshot().records.count { it.voided })
        assertEquals(0, repository.restore(incoming))
    }

    @Test
    fun versionTwoMigrationVoidsHistoricalDuplicatesAndPersistsNoticeWithoutDeletingAnything() {
        val fixture = fixture()
        val first = repository.save(input(fixture, ItemKind.MACHINE, sn = "AA-001"))
        val taskB = repository.addTask("兼容任务B")
        val taskC = repository.addTask("兼容任务C")
        val taskD = repository.addTask("兼容任务D")
        val taskE = repository.addTask("兼容任务E")
        val sameTime = first.updatedAt + 1_000
        val legacy = listOf(
            first.copy(id = "legacy-b", taskId = taskB.id, updatedAt = sameTime),
            first.copy(id = "legacy-c", taskId = taskC.id, createdAt = first.createdAt + 1, updatedAt = sameTime),
            first.copy(id = "legacy-a", taskId = taskD.id, createdAt = first.createdAt + 1, updatedAt = sameTime),
            first.copy(id = "legacy-old-void", taskId = taskE.id, voided = true),
        )
        repository.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(InventoryRepository.DATABASE_NAME).absolutePath,
            null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP INDEX unique_active_sn")
            db.execSQL("CREATE UNIQUE INDEX unique_active_sn ON records(task_id, sn) WHERE kind = 'MACHINE' AND voided = 0 AND sn != ''")
            legacy.forEach { db.insertOrThrow("records", null, legacyValues(it)) }
            db.execSQL("DROP TABLE metadata")
            db.version = 2
        }
        repository = InventoryRepository(context)
        val migrated = repository.snapshot()
        assertEquals(5, migrated.records.size)
        assertEquals("legacy-a", migrated.records.single { !it.voided }.id)
        assertEquals(3, migrated.changes.count { it.action == "VOID" })
        assertEquals(3, repository.pendingMigrationVoidedCount())
        assertEquals(legacy.last(), migrated.records.single { it.id == "legacy-old-void" })
        assertEquals(0, repository.restore(migrated))
        val winner = migrated.records.single { !it.voided }
        val edited = repository.save(input(fixture, ItemKind.MACHINE, sn = winner.sn).copy(taskId = winner.taskId, note = "核对保留"), winner.id)
        assertEquals("核对保留", edited.note)
        assertEquals(0, repository.restoreVoided(first.id).processed)
        assertThrows(DuplicateSnException::class.java) { repository.save(input(fixture, ItemKind.MACHINE, sn = winner.sn)) }
        repository.close()
        repository = InventoryRepository(context)
        assertEquals(3, repository.pendingMigrationVoidedCount())
        repository.acknowledgeMigrationNotice()
        assertEquals(0, repository.pendingMigrationVoidedCount())
    }

    private fun legacyValues(record: StockRecord) = ContentValues().apply {
        put("id", record.id); put("task_id", record.taskId); put("warehouse_id", record.warehouseId)
        put("kind", record.kind.name); put("bom", record.bom); put("sn", record.sn)
        put("quantity", record.quantity); put("condition", record.condition); put("note", record.note)
        put("location", record.location); put("source_bom", record.sourceBom); put("source_sn", record.sourceSn)
        put("raw_bom", record.rawBom); put("raw_sn", record.rawSn); put("created_at", record.createdAt)
        put("updated_at", record.updatedAt); put("voided", if (record.voided) 1 else 0)
    }

    @Test
    fun incomingPartsMergeByWarehouseBomAndConditionAndKeepSourceDetails() {
        val fixture = fixture()
        val first = repository.save(input(fixture).copy(quantity = 4, location = "A", note = "原备注"))
        assertEquals(1, repository.saveIncoming(input(fixture).copy(quantity = 6, location = "B", note = "来源备注", sourceBom = "import"), DuplicateMode.ADD_QUANTITY).processed)
        val merged = repository.snapshot().records.single()
        assertEquals(first.id, merged.id)
        assertEquals(10, merged.quantity)
        assertTrue(merged.note.contains("原备注") && merged.note.contains("来源备注") && merged.note.contains("B"))
        repository.saveIncoming(input(fixture).copy(condition = "损坏"), DuplicateMode.ADD_QUANTITY)
        repository.saveIncoming(input(fixture), DuplicateMode.NEW_ENTRY)
        assertEquals(3, repository.snapshot().records.size)
        assertEquals(0, repository.restore(repository.snapshot()))
    }

    @Test
    fun incomingDuplicateMachinesAreRetainedVoidedAndReportedOnce() {
        val fixture = fixture()
        val machine = input(fixture, ItemKind.MACHINE, "AA-001")
        repository.saveIncoming(machine, DuplicateMode.NEW_ENTRY)
        val result = repository.importRecords(listOf(machine, machine.copy(sn = "BB-002"), machine.copy(sn = "BB-002")), DuplicateMode.ADD_QUANTITY)
        assertEquals(3, result.processed)
        assertEquals(2, result.duplicateMachines.size)
        assertTrue(result.duplicateMachines.all { it.voided })
        assertEquals(2, repository.snapshot().records.count { !it.voided })
        assertEquals(2, repository.snapshot().records.count { it.voided })
        assertEquals(0, repository.restore(repository.snapshot()))
    }

    @Test
    fun batchImportAndQuantityOverflowRollbackEverything() {
        val fixture = fixture()
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            repository.importRecords(listOf(input(fixture), input(fixture).copy(bom = "bad/code")), DuplicateMode.ADD_QUANTITY)
        }
        assertEquals(before, repository.snapshot())
        repository.save(input(fixture).copy(quantity = 1_000_000_000))
        val full = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.saveIncoming(input(fixture), DuplicateMode.ADD_QUANTITY) }
        assertEquals(full, repository.snapshot())
    }

    @Test
    fun additiveRestoreConsumesSourceSoQuantityCannotBeAddedAgain() {
        val fixture = fixture()
        val target = repository.save(input(fixture).copy(quantity = 4))
        val source = repository.save(input(fixture).copy(quantity = 6, note = "待还原来源"))
        repository.voidRecord(source.id)
        assertEquals(1, repository.restoreVoided(source.id, DuplicateMode.ADD_QUANTITY).processed)
        val state = repository.snapshot()
        assertEquals(target.id, state.records.single().id)
        assertEquals(10, state.records.single().quantity)
        assertTrue(state.changes.none { it.recordId == source.id })
        assertTrue(state.changes.any { JSONObject(it.after).has("mergedSources") })
        assertThrows(IllegalArgumentException::class.java) { repository.restoreVoided(source.id, DuplicateMode.ADD_QUANTITY) }
        assertEquals(state, repository.snapshot())
        assertEquals(0, repository.restore(state))
    }

    @Test
    fun selectedPartsMergeChecksStateAndBatchVoidIsAtomic() {
        val fixture = fixture()
        val first = repository.save(input(fixture).copy(quantity = 4))
        val second = repository.save(input(fixture).copy(quantity = 6))
        val damaged = repository.save(input(fixture).copy(condition = "损坏"))
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.mergeParts(setOf(first.id, damaged.id)) }
        assertThrows(IllegalArgumentException::class.java) { repository.voidRecords(setOf(first.id, "missing")) }
        assertEquals(before, repository.snapshot())
        assertEquals(2, repository.mergeParts(setOf(first.id, second.id)).processed)
        val merged = repository.snapshot().records.single { it.condition == "正常" }
        assertEquals(10, merged.quantity)
        assertEquals(2, repository.voidRecords(setOf(merged.id, damaged.id)))
        assertEquals(0, repository.voidRecords(setOf(merged.id, damaged.id)))
        assertEquals(0, repository.restore(repository.snapshot()))
    }

    @Test
    fun warehouseMergeMovesArchivesAddsPartsAndKeepsBackupValid() {
        val fixture = fixture()
        val target = repository.addWarehouse(fixture.country.id, "目标仓")
        repository.save(input(fixture).copy(warehouseId = target.id, quantity = 4))
        repository.save(input(fixture).copy(quantity = 6, note = "来源仓备注"))
        val archived = repository.save(input(fixture).copy(quantity = 2))
        repository.voidRecord(archived.id)
        repository.save(input(fixture, ItemKind.MACHINE, "AA-001"))
        assertEquals(3, repository.mergeWarehouses(fixture.warehouse.id, target.id, DuplicateMode.ADD_QUANTITY).processed)
        val state = repository.snapshot()
        assertTrue(state.warehouses.none { it.id == fixture.warehouse.id })
        assertTrue(state.records.all { it.warehouseId == target.id })
        assertEquals(10, state.records.single { it.kind == ItemKind.PART && !it.voided }.quantity)
        assertTrue(state.records.single { it.id == archived.id }.voided)
        assertEquals(0, repository.restore(state))
        val otherCountry = repository.addCountry("其他国")
        val otherWarehouse = repository.addWarehouse(otherCountry.id, "其他国仓")
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.mergeWarehouses(target.id, otherWarehouse.id, DuplicateMode.NEW_ENTRY) }
        assertEquals(before, repository.snapshot())
    }

    @Test
    fun deletingCountryAndWarehouseCleansOnlyTheirRecordsAndKeepsSurvivingHistoryValid() {
        val fixture = fixture()
        val otherCountry = repository.addCountry("其他国")
        val otherWarehouse = repository.addWarehouse(otherCountry.id, "其他仓")
        val moved = repository.save(input(fixture))
        repository.save(input(fixture).copy(warehouseId = otherWarehouse.id), moved.id)
        val removed = repository.save(input(fixture).copy(quantity = 2))
        assertEquals(1, repository.deleteWarehouse(fixture.warehouse.id))
        var state = repository.snapshot()
        assertTrue(state.records.none { it.id == removed.id })
        assertEquals(moved.id, state.records.single().id)
        assertEquals(0, repository.restore(state))
        assertEquals(0, repository.deleteCountry(fixture.country.id))
        state = repository.snapshot()
        assertEquals(0, repository.restore(state))
        assertEquals(1, repository.deleteCountry(otherCountry.id))
        assertTrue(repository.snapshot().records.isEmpty())
        assertTrue(repository.snapshot().changes.isEmpty())
    }

    @Test
    fun warehouseMergeAndRestoreNewEntryKeepSeparateRowsAndVoidedDuplicates() {
        val fixture = fixture()
        val target = repository.addWarehouse(fixture.country.id, "目标仓")
        repository.save(input(fixture).copy(warehouseId = target.id, quantity = 4))
        val source = repository.save(input(fixture).copy(quantity = 6))
        val archived = repository.save(input(fixture).copy(quantity = 2))
        repository.voidRecord(archived.id)
        repository.mergeWarehouses(fixture.warehouse.id, target.id, DuplicateMode.NEW_ENTRY)
        assertEquals(3, repository.snapshot().records.size)
        assertEquals(10, repository.snapshot().records.filter { !it.voided }.sumOf { it.quantity })
        assertEquals(1, repository.restoreVoided(archived.id, DuplicateMode.NEW_ENTRY).processed)
        val state = repository.snapshot()
        assertEquals(3, state.records.size)
        assertTrue(state.records.all { !it.voided && it.warehouseId == target.id })
        assertEquals(6, state.records.single { it.id == source.id }.quantity)
        assertEquals(0, repository.restore(state))
    }

    @Test
    fun bulkAddQuantityDoesNotFillNotesWithGeneratedQuantityMessages() {
        val fixture = fixture()
        val result = repository.importRecords(List(500) { input(fixture) }, DuplicateMode.ADD_QUANTITY)
        assertEquals(500, result.processed)
        val state = repository.snapshot()
        assertEquals(500, state.records.single().quantity)
        assertEquals("", state.records.single().note)
        assertEquals(0, repository.restore(state))
    }

    @Test
    fun missingBomPendingPartsStaySeparateEvenWhenAddQuantityIsSelected() {
        val fixture = fixture()
        val draft = input(fixture).copy(bom = "", condition = "待核查", note = "未知配件")
        repository.importRecords(listOf(draft, draft.copy(note = "另一个未知配件")), DuplicateMode.ADD_QUANTITY)
        val state = repository.snapshot()
        assertEquals(2, state.records.size)
        assertThrows(IllegalArgumentException::class.java) { repository.mergeParts(state.records.mapTo(mutableSetOf()) { it.id }) }
        assertEquals(state, repository.snapshot())
    }

    @Test fun undoImportRestoresQuantityAndReceiptButPreservesLaterUnrelatedInventory() {
        val f = fixture()
        val original = repository.save(input(f).copy(quantity = 4))
        repository.recoverable("CSV导入") { it.importRecords(listOf(input(f).copy(quantity = 6), input(f, ItemKind.MACHINE, "AA-001")), DuplicateMode.ADD_QUANTITY, ImportMetadata("a".repeat(64), "a.csv")) }
        val point = repository.recoveryPoints().single()
        assertEquals(1, repository.importReceipts().size)
        val unrelated = repository.save(input(f).copy(bom = "OTHER", quantity = 7))
        assertEquals(2, repository.undoRecovery(point.id).processed)
        val state = repository.snapshot()
        assertEquals(setOf(original.id, unrelated.id), state.records.map { it.id }.toSet())
        assertEquals(4, state.records.single { it.id == original.id }.quantity)
        assertTrue(repository.importReceipts().isEmpty())
        assertTrue(repository.recoveryPoints().single().undone)
        assertThrows(IllegalArgumentException::class.java) { repository.undoRecovery(point.id) }
        assertEquals(0, repository.restore(state))
    }

    @Test fun undoRefusesRelatedEditsWithoutChangingAnything() {
        val f = fixture()
        val original = repository.save(input(f).copy(quantity = 4))
        repository.recoverable("相加") { it.importRecords(listOf(input(f)), DuplicateMode.ADD_QUANTITY) }
        repository.save(input(f).copy(quantity = 20), original.id)
        val before = repository.snapshot()
        val point = repository.recoveryPoints().single()
        assertThrows(IllegalArgumentException::class.java) { repository.undoRecovery(point.id) }
        assertEquals(before, repository.snapshot())
        assertFalse(repository.recoveryPoints().single().undone)
    }

    @Test fun failedImportantOperationRollsBackInventoryReceiptAndRecoveryPoint() {
        val f = fixture()
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            repository.recoverable("错误导入") { it.importRecords(listOf(input(f)), DuplicateMode.NEW_ENTRY, ImportMetadata("invalid", "x.csv")) }
        }
        assertEquals(before, repository.snapshot())
        assertTrue(repository.importReceipts().isEmpty())
        assertTrue(repository.recoveryPoints().isEmpty())
        repository.recoverable("无变化") { it.snapshot() }
        assertTrue(repository.recoveryPoints().isEmpty())
    }

    @Test fun undoCountryDeletionRestoresRecordsAndQuarantinesSerialsReusedSinceDeletion() {
        val f = fixture()
        val original = repository.save(input(f, ItemKind.MACHINE, "AA-001"))
        repository.recoverable("删除国家") { it.deleteCountry(f.country.id) }
        val point = repository.recoveryPoints().single()
        val other = repository.addWarehouse(repository.addCountry("其他国").id, "其他仓")
        val newMachine = repository.save(input(f, ItemKind.MACHINE, "AA-001").copy(warehouseId = other.id, bom = "OTHER"))
        val result = repository.undoRecovery(point.id)
        assertEquals(original.id, result.duplicateMachines.single().id)
        val state = repository.snapshot()
        assertTrue(state.records.single { it.id == original.id }.voided)
        assertFalse(state.records.single { it.id == newMachine.id }.voided)
        assertEquals(0, repository.restore(state))
    }

    @Test fun undoWarehouseMergeRestoresSourceAndImportHistoryWithoutRemovingLaterTargetRows() {
        val f = fixture()
        val target = repository.addWarehouse(f.country.id, "目标仓")
        val original = repository.save(input(f).copy(quantity = 4, warehouseId = target.id))
        repository.importRecords(listOf(input(f).copy(quantity = 6)), DuplicateMode.NEW_ENTRY, ImportMetadata("a".repeat(64), "a.csv"))
        repository.recoverable("合并仓库") { it.mergeWarehouses(f.warehouse.id, target.id, DuplicateMode.ADD_QUANTITY) }
        val point = repository.recoveryPoints().single()
        assertEquals(target.id, repository.importReceipts().single().warehouseId)
        val unrelated = repository.save(input(f).copy(bom = "OTHER", warehouseId = target.id))
        repository.undoRecovery(point.id)
        val state = repository.snapshot()
        assertTrue(state.warehouses.any { it.id == f.warehouse.id })
        assertEquals(4, state.records.single { it.id == original.id }.quantity)
        assertTrue(state.records.any { it.id == unrelated.id })
        assertEquals(f.warehouse.id, repository.importReceipts().single().warehouseId)
        assertEquals(0, repository.restore(state))
    }

    @Test fun nameConflictDuringUndoRollsBackTheEntireReplacement() {
        val f = fixture()
        repository.save(input(f))
        repository.recoverable("删除国家") { it.deleteCountry(f.country.id) }
        val point = repository.recoveryPoints().single()
        repository.addCountry(f.country.name)
        val current = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.undoRecovery(point.id) }
        assertEquals(current, repository.snapshot())
        assertFalse(repository.recoveryPoints().single().undone)
    }

    @Test fun recoveryPointsPersistAcrossReopenAndRetainOnlyLatestFiveOperations() {
        val f = fixture()
        repeat(7) { index -> repository.recoverable("导入 $index") { it.importRecords(listOf(input(f).copy(bom = "PART$index")), DuplicateMode.NEW_ENTRY) } }
        repository.close(); repository = InventoryRepository(context)
        val points = repository.recoveryPoints()
        assertEquals(5, points.size)
        assertEquals("导入 6", points.first().label)
        assertEquals("导入 2", points.last().label)
        repository.undoRecovery(points.first().id)
        assertEquals(6, repository.snapshot().records.size)
        assertTrue(repository.recoveryPoints().first().undone)
    }

    @Test fun schemaThreeUpgradeKeepsInventoryAndAddsEmptyOperationTables() {
        val f = fixture()
        repository.save(input(f))
        val before = repository.snapshot(); repository.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(InventoryRepository.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE recovery_points"); db.execSQL("DROP TABLE import_receipts"); db.version = 3
        }
        repository = InventoryRepository(context)
        assertEquals(before, repository.snapshot())
        assertTrue(repository.recoveryPoints().isEmpty())
        assertTrue(repository.importReceipts().isEmpty())
    }

    @Test fun undoLatestRepeatImportKeepsEarlierReceiptAndEarlierQuantity() {
        val f = fixture()
        repeat(2) { repository.recoverable("导入") { it.importRecords(listOf(input(f).copy(quantity = 3)), DuplicateMode.ADD_QUANTITY, ImportMetadata("a".repeat(64), "same.csv")) } }
        assertEquals(2, repository.importReceipts().size)
        val latest = repository.recoveryPoints().first()
        repository.undoRecovery(latest.id)
        assertEquals(3, repository.snapshot().records.single().quantity)
        assertEquals(1, repository.importReceipts().size)
    }

    @Test fun undoAdditiveRestoreReturnsConsumedSourceToVoidedAndKeepsOriginalQuantity() {
        val f = fixture()
        val target = repository.save(input(f).copy(quantity = 4))
        val source = repository.save(input(f).copy(quantity = 6))
        repository.voidRecord(source.id)
        val before = repository.snapshot()
        repository.recoverable("还原并相加") { it.restoreVoided(source.id, DuplicateMode.ADD_QUANTITY) }
        assertEquals(10, repository.snapshot().records.single().quantity)
        repository.undoRecovery(repository.recoveryPoints().single().id)
        assertEquals(before, repository.snapshot())
        assertEquals(4, repository.snapshot().records.single { it.id == target.id }.quantity)
        assertTrue(repository.snapshot().records.single { it.id == source.id }.voided)
    }

    private data class Fixture(val task: InventoryTask, val country: Country, val warehouse: Warehouse)

    private fun fixture(): Fixture {
        val country = repository.addCountry("挪威")
        return Fixture(repository.snapshot().tasks.first(), country, repository.addWarehouse(country.id, "奥斯陆仓库"))
    }

    private fun input(fixture: Fixture, kind: ItemKind = ItemKind.PART, sn: String = "") = RecordInput(
        taskId = fixture.task.id, warehouseId = fixture.warehouse.id, kind = kind,
        bom = "000123Ab", sn = sn, quantity = 1, condition = "正常", note = "",
    )
}
