package com.fieldstock.app.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.i18n.*
import com.fieldstock.app.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BackupMergeHotfixTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repository: InventoryRepository
    @Before fun setUp() { AppLanguage.current = AppLanguage.CHINESE; context.deleteDatabase(InventoryRepository.DATABASE_NAME); repository = InventoryRepository(context) }
    @After fun close() { repository.close(); context.deleteDatabase(InventoryRepository.DATABASE_NAME); AppLanguage.current = AppLanguage.CHINESE }
    private fun reset() { repository.close(); context.deleteDatabase(InventoryRepository.DATABASE_NAME); repository = InventoryRepository(context) }
    private fun input(w: Warehouse, qty: Int = 6) = RecordInput(repository.snapshot().tasks.first().id, w.id, ItemKind.PART, "00Ab1", "", qty, StockCondition.NORMAL, "source\n备注")
    private fun source(machine: Boolean = false): InventorySnapshot {
        val w = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        val part = repository.save(input(w, 5))
        repository.save(input(w), part.id)
        if (machine) repository.save(input(w).copy(kind = ItemKind.MACHINE, bom = "", sn = "00a-B1", pn = "PN-A-00-1", quantity = 1))
        return repository.snapshot().also { reset() }
    }
    private fun receiver(name: String = "Main"): Warehouse = repository.addWarehouse(repository.addCountry("NORWAY").id, name)
    private fun choices(backup: InventorySnapshot, action: BackupWarehouseAction = BackupWarehouseAction.MERGE, mode: DuplicateMode = DuplicateMode.NEW_ENTRY, name: String = "") =
        BackupMergeChoices(backup.countries.map { it.id }.toSet(), backup.warehouses.associate { it.id to BackupWarehouseChoice(action, name) }, mode)
    private fun roundTrip(state: InventorySnapshot): InventorySnapshot {
        val bytes = ByteArrayOutputStream().also { InventoryFiles.writeBackup(state, it) }.toByteArray()
        return InventoryFiles.readBackup(bytes.inputStream())
    }

    @Test fun sameCountryRequiresChoiceThenAddsBackupWarehouseToExistingCountry() {
        val backup = source(); val local = receiver("Other")
        val before = repository.snapshot()
        assertEquals(1, BackupMerge.countryConflicts(before, backup).size)
        assertThrows(IllegalArgumentException::class.java) { repository.importBackup(backup) }
        assertEquals(before, repository.snapshot())
        assertEquals(1, repository.importBackup(backup, choices(backup)).processed)
        val state = repository.snapshot()
        assertEquals(1, state.countries.size); assertEquals(2, state.warehouses.size)
        assertTrue(state.warehouses.all { it.countryId == local.countryId })
        assertEquals(0, repository.importBackup(backup).processed)
        assertEquals(state, repository.snapshot())
    }

    @Test fun warehouseConflictNeedsChoiceAndRenameNeverOverwritesLocalInventory() {
        val backup = source(); val local = receiver(); repository.save(input(local, 4))
        val before = repository.snapshot()
        assertEquals(1, BackupMerge.warehouseConflicts(before, backup).size)
        assertThrows(IllegalStateException::class.java) { repository.importBackup(backup, BackupMergeChoices(backup.countries.map { it.id }.toSet())) }
        assertThrows(IllegalArgumentException::class.java) { repository.importBackup(backup, choices(backup, BackupWarehouseAction.RENAME, name = "main")) }
        assertEquals(before, repository.snapshot())
        assertEquals(1, repository.importBackup(backup, choices(backup, BackupWarehouseAction.RENAME, name = "Imported Main")).processed)
        val state = repository.snapshot()
        assertEquals(4, state.records.single { it.warehouseId == local.id }.quantity)
        assertEquals(6, state.records.single { it.warehouseId != local.id }.quantity)
        assertEquals("Imported Main", state.warehouses.single { it.id != local.id }.name)
        assertEquals(0, repository.importBackup(backup).processed)
        assertEquals(state, repository.snapshot())
        assertEquals(0, repository.restore(roundTrip(state)))
    }

    @Test fun newEntryMergePreservesOriginalHistoryAndAllReferences() {
        val backup = source(); val local = receiver(); repository.save(input(local, 4))
        assertEquals(1, repository.importBackup(backup, choices(backup)).processed)
        val state = repository.snapshot()
        assertEquals(listOf(4, 6), state.records.map { it.quantity }.sorted())
        assertEquals(1, state.warehouses.size); assertTrue(state.records.all { it.warehouseId == local.id })
        assertTrue(state.changes.map { it.id }.containsAll(backup.changes.map { it.id }))
        assertEquals(0, repository.importBackup(backup).processed)
        assertEquals(state, repository.snapshot())
        assertEquals(0, repository.restore(roundTrip(state)))
        assertEquals(0, repository.importBackup(roundTrip(state)).processed)
    }

    @Test fun addQuantityArchivesHistoryIsReplaySafeAndCanBeUndone() {
        val backup = source(); val local = receiver(); repository.save(input(local, 4))
        val before = repository.snapshot()
        repository.recoverable("backup hotfix") { it.importBackup(backup, choices(backup, mode = DuplicateMode.ADD_QUANTITY)) }
        val state = repository.snapshot()
        assertEquals(10, state.records.single().quantity)
        val history = state.changes.joinToString { it.after }
        backup.changes.forEach { assertTrue(history.contains(it.id)) }
        assertEquals(0, repository.importBackup(backup).processed)
        assertEquals(state, repository.snapshot())
        assertEquals(0, repository.restore(roundTrip(state)))
        assertEquals(0, repository.importBackup(roundTrip(state)).processed)
        repository.undoRecovery(repository.recoveryPoints().single().id)
        assertEquals(before.records, repository.snapshot().records)
        assertEquals(before.countries, repository.snapshot().countries)
        assertEquals(before.warehouses, repository.snapshot().warehouses)
        assertEquals(1, repository.importBackup(backup, choices(backup, mode = DuplicateMode.ADD_QUANTITY)).processed)
        assertEquals(10, repository.snapshot().records.single().quantity)
    }

    @Test fun freshRestoreOfMergedBackupRetainsReplayProtection() {
        val backup = source(); val local = receiver(); repository.save(input(local, 4))
        repository.importBackup(backup, choices(backup, mode = DuplicateMode.ADD_QUANTITY))
        val state = roundTrip(repository.snapshot()); reset()
        repository.importBackup(state)
        assertEquals(0, repository.importBackup(backup).processed)
        assertEquals(10, repository.snapshot().records.single().quantity)
    }

    @Test fun overflowRollsBackCountriesRecordsMarkersAndRecoveryPoint() {
        val backup = source(); val local = receiver(); repository.save(input(local, 1_000_000_000))
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            repository.recoverable("overflow") { it.importBackup(backup, choices(backup, mode = DuplicateMode.ADD_QUANTITY)) }
        }
        assertEquals(before, repository.snapshot()); assertTrue(repository.recoveryPoints().isEmpty())
    }

    @Test fun changedSourceWithSameIdIsNeverAddedOrAllowedToOverwrite() {
        val backup = source(); receiver()
        repository.importBackup(backup, choices(backup))
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.importBackup(backup.copy(records = backup.records.map { it.copy(quantity = 99) })) }
        assertEquals(before, repository.snapshot())
    }

    @Test fun sameIdTargetInBackupIsSkippedOnReplayEvenWhenItsQuantityChangedInMerge() {
        val local = receiver(); val target = repository.save(input(local, 4))
        val state = repository.snapshot()
        val backup = state.copy(records = state.records + target.copy(id = "new-source", quantity = 6), changes = state.changes)
        repository.importBackup(backup, BackupMergeChoices(mode = DuplicateMode.ADD_QUANTITY))
        assertEquals(10, repository.snapshot().records.single().quantity)
        assertEquals(0, repository.importBackup(backup).processed)
        assertEquals(10, repository.snapshot().records.single().quantity)
    }

    @Test fun duplicateSerialIsVoidedAndPnSurvivesNameResolutionsAndReplay() {
        val backup = source(machine = true); val local = receiver()
        repository.save(input(local).copy(kind = ItemKind.MACHINE, sn = "00a-B1", bom = "", quantity = 1))
        val result = repository.importBackup(backup, choices(backup, mode = DuplicateMode.ADD_QUANTITY))
        assertEquals(1, result.duplicateMachines.size)
        assertEquals("PN-A-00-1", result.duplicateMachines.single().pn)
        assertEquals(0, repository.importBackup(backup).processed)
        assertEquals(0, repository.restore(roundTrip(repository.snapshot())))
    }

    @Test fun malformedDuplicateIdsCannotDisappearDuringResolutionOrReplay() {
        val backup = source(); receiver()
        repository.importBackup(backup, choices(backup))
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.importBackup(backup.copy(records = backup.records + backup.records)) }
        assertThrows(IllegalArgumentException::class.java) { repository.importBackup(backup.copy(countries = backup.countries + backup.countries)) }
        assertEquals(before, repository.snapshot())
    }

    @Test fun laterManualPartsAndWarehouseMergesKeepBackupReplayProtection() {
        val original = source(); val local = receiver()
        val older = repository.save(input(local, 4))
        val future = System.currentTimeMillis() + 100_000
        val backup = original.copy(records = original.records.map { it.copy(createdAt = future, updatedAt = future) }, changes = emptyList())
        repository.importBackup(backup, choices(backup))
        val importedId = backup.records.single().id
        repository.mergeParts(setOf(older.id, importedId))
        val target = repository.addWarehouse(local.countryId, "Target")
        repository.mergeWarehouses(local.id, target.id, DuplicateMode.ADD_QUANTITY)
        val before = repository.snapshot()
        assertEquals(10, before.records.single().quantity)
        assertEquals(0, repository.importBackup(backup).processed)
        assertEquals(before, repository.snapshot())
    }

    @Test fun multipleWarehousesCanMergeRenameAndAddUnderOneExistingCountry() {
        val backup = source()
        val original = backup.warehouses.single()
        val second = original.copy(id = "source-second", name = "Other")
        val third = original.copy(id = "source-third", name = "New")
        val more = backup.copy(warehouses = backup.warehouses + listOf(second, third),
            records = backup.records + listOf(backup.records.single().copy(id = "part-second", warehouseId = second.id), backup.records.single().copy(id = "part-third", warehouseId = third.id)))
        val local = receiver(); repository.addWarehouse(local.countryId, "Other")
        repository.save(input(local, 4))
        val options = choices(more, mode = DuplicateMode.ADD_QUANTITY).copy(warehouses = mapOf(
            original.id to BackupWarehouseChoice(BackupWarehouseAction.MERGE), second.id to BackupWarehouseChoice(BackupWarehouseAction.RENAME, "Imported Other")))
        repository.importBackup(more, options)
        val state = repository.snapshot()
        assertEquals(1, state.countries.size)
        assertEquals(setOf("Main", "Other", "Imported Other", "New"), state.warehouses.map { it.name }.toSet())
        assertEquals(listOf(6, 6, 10), state.records.map { it.quantity }.sorted())
        assertEquals(0, repository.importBackup(more).processed)
        assertEquals(state, repository.snapshot())
    }

    @Test fun renamedWarehouseCannotCollideWithAnotherIncomingWarehouse() {
        val backup = source(); receiver()
        val more = backup.copy(warehouses = backup.warehouses + backup.warehouses.single().copy(id = "other-new", name = "Next"))
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.importBackup(more, choices(backup, BackupWarehouseAction.RENAME, name = "Next")) }
        assertEquals(before, repository.snapshot())
    }

    @Test fun downloadedBackupMergesIntoExistingNorwayWarehouseAndRepeatsSafelyInBothLanguages() {
        val folder = System.getenv("FIELDSTOCK_COMPAT_SAMPLES_DIR")
        Assume.assumeTrue(!folder.isNullOrEmpty())
        val file = File(folder!!).listFiles()!!.single { it.extension == "json" }
        val backup = file.inputStream().use(InventoryFiles::readBackup)
        for (language in AppLanguage.entries) {
            AppLanguage.current = language
            val country = repository.addCountry(backup.countries.single().name)
            val warehouse = repository.addWarehouse(country.id, backup.warehouses.single().name)
            repository.importBackup(backup, choices(backup, mode = DuplicateMode.ADD_QUANTITY))
            val state = repository.snapshot()
            assertEquals(backup.records.filter { !it.voided && it.kind == ItemKind.PART }.sumOf { it.quantity.toLong() }, state.records.filter { !it.voided && it.kind == ItemKind.PART }.sumOf { it.quantity.toLong() })
            assertEquals(backup.records.count { it.kind == ItemKind.MACHINE }, state.records.count { it.kind == ItemKind.MACHINE })
            assertEquals(1, state.warehouses.size); assertTrue(state.records.all { it.warehouseId == warehouse.id })
            assertEquals(0, repository.importBackup(backup).processed)
            assertEquals(state, repository.snapshot())
            assertEquals(0, repository.restore(roundTrip(state)))
            reset()
        }
    }
}
