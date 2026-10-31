package com.fieldstock.app.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.i18n.*
import com.fieldstock.app.model.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DataBackupAuditRegressionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repository: InventoryRepository
    @Before fun seed() {
        AppLanguage.current = AppLanguage.CHINESE
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        repository = InventoryRepository(context)
    }
    @After fun close() {
        repository.close()
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        AppLanguage.current = AppLanguage.CHINESE
    }
    private fun input(warehouse: Warehouse, quantity: Int = 6, note: String = "source note") =
        RecordInput(repository.snapshot().tasks.first().id, warehouse.id, ItemKind.PART, "00Ab1", "", quantity, StockCondition.NORMAL, note)
    private fun reset() {
        repository.close()
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        repository = InventoryRepository(context)
    }
    private fun originalBackup(future: Boolean = false, quantity: Int = 6): InventorySnapshot {
        val warehouse = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        repository.save(input(warehouse, quantity))
        val snapshot = repository.snapshot()
        val at = System.currentTimeMillis() + 100_000
        val backup = if (!future) snapshot else snapshot.copy(
            records = snapshot.records.map { it.copy(createdAt = at, updatedAt = at) },
            changes = snapshot.changes.map { it.copy(at = at, after = JSONObject(it.after).put("createdAt", at).put("updatedAt", at).toString()) })
        reset()
        return backup
    }
    private fun portable(snapshot: InventorySnapshot): InventorySnapshot {
        val bytes = ByteArrayOutputStream().also { InventoryFiles.writeBackup(snapshot, it) }.toByteArray()
        return bytes.inputStream().use(InventoryFiles::readBackup)
    }
    private fun oldStyle(snapshot: InventorySnapshot): InventorySnapshot {
        fun strip(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.remove("backupImport"); value.remove("backupOrigins")
                    value.keys().asSequence().toList().forEach { strip(value.opt(it)) }
                }
                is JSONArray -> for (i in 0 until value.length()) strip(value.opt(i))
            }
        }
        return snapshot.copy(changes = snapshot.changes.map { change ->
            val after = JSONObject(change.after); strip(after)
            change.copy(after = after.toString())
        })
    }

    @Test fun rememberedCountryMappingStillExposesNewWarehouseConflictsAfterCountryRename() {
        val source = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        repository.save(input(source))
        val backup = repository.snapshot()
        reset()
        val country = repository.addCountry("NORWAY")
        val local = repository.addWarehouse(country.id, "Main")
        repository.importBackup(backup, BackupMergeChoices(setOf(source.countryId), mapOf(source.id to BackupWarehouseChoice(BackupWarehouseAction.MERGE))))
        repository.renameCountry(country.id, "Norge")
        val localExtra = repository.addWarehouse(country.id, "Extra")
        val incomingExtra = source.copy(id = "incoming-extra", name = "Extra")
        val expanded = backup.copy(warehouses = backup.warehouses + incomingExtra,
            records = backup.records + backup.records.single().copy(id = "extra-part", warehouseId = incomingExtra.id))

        assertTrue(BackupMerge.countryConflicts(repository.snapshot(), expanded).isEmpty())
        val conflict = BackupMerge.warehouseConflicts(repository.snapshot(), expanded).single()
        assertEquals(incomingExtra.id, conflict.incoming.id)
        assertEquals(localExtra.id, conflict.existing.id)
        assertEquals(1, repository.importBackup(expanded, BackupMergeChoices(warehouses = mapOf(
            incomingExtra.id to BackupWarehouseChoice(BackupWarehouseAction.MERGE)))).processed)
        assertEquals(setOf(local.id, localExtra.id), repository.snapshot().warehouses.map { it.id }.toSet())
        assertEquals("Norge", repository.snapshot().countries.single().name)
    }

    @Test fun manualPartMergeArchivesEveryOriginalSourceChange() {
        val warehouse = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        val first = repository.save(input(warehouse, 4))
        val second = repository.save(input(warehouse, 5, "original source note"))
        val order = listOf(first, second).sortedWith(compareBy<StockRecord> { it.createdAt }.thenBy { it.id })
        val source = order.last()
        repository.save(input(warehouse, 6, "edited source note"), source.id)
        val sourceHistory = repository.snapshot().changes.filter { it.recordId == source.id }
        assertEquals(2, sourceHistory.size)

        repository.mergeParts(setOf(first.id, second.id))

        val state = repository.snapshot()
        assertEquals(order.first().quantity + 6, state.records.single().quantity)
        val archived = state.changes.joinToString { it.after }
        sourceHistory.forEach { change -> assertTrue("Merged source change ${change.id} must remain archived", archived.contains(change.id)) }
        assertEquals(0, repository.restore(state))
    }

    @Test fun warehouseQuantityMergeArchivesEveryOriginalSourceChange() {
        val country = repository.addCountry("Norway")
        val source = repository.addWarehouse(country.id, "Source")
        val target = repository.addWarehouse(country.id, "Target")
        repository.save(input(target, 4))
        val sourceRecord = repository.save(input(source, 5, "before edit"))
        repository.save(input(source, 6, "after edit"), sourceRecord.id)
        val sourceHistory = repository.snapshot().changes.filter { it.recordId == sourceRecord.id }

        repository.mergeWarehouses(source.id, target.id, DuplicateMode.ADD_QUANTITY)

        val state = repository.snapshot()
        assertEquals(10, state.records.single().quantity)
        val archived = state.changes.joinToString { it.after }
        sourceHistory.forEach { change -> assertTrue("Warehouse merge must retain source history ${change.id}", archived.contains(change.id)) }
        assertEquals(0, repository.restore(state))
    }

    @Test fun clockRollbackKeepsTheMostRecentlyCreatedRecoveryPoint() {
        val warehouse = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        repeat(5) { index -> repository.recoverable("earlier-$index") {
            it.saveIncoming(input(warehouse).copy(bom = "P$index"), DuplicateMode.NEW_ENTRY)
        } }
        val future = System.currentTimeMillis() + 24 * 60 * 60 * 1000
        context.openOrCreateDatabase(InventoryRepository.DATABASE_NAME, 0, null).use { db ->
            db.execSQL("UPDATE recovery_points SET at = ?", arrayOf(future))
        }

        repository.recoverable("latest") { it.saveIncoming(input(warehouse).copy(bom = "NEW"), DuplicateMode.NEW_ENTRY) }

        val points = repository.recoveryPoints()
        assertEquals(5, points.size)
        assertEquals("latest", points.first().label)
        repository.undoRecovery(points.first().id)
        assertFalse(repository.snapshot().records.any { it.bom == "NEW" })
        assertEquals(5, repository.snapshot().records.size)
    }

    @Test fun repeatingAnEmptyBackupAfterRenamingItsWarehouseCanExplicitlyReuseTheCurrentLocation() {
        val source = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        val backup = repository.snapshot()
        reset()
        val localCountry = repository.addCountry("NORWAY")
        val local = repository.addWarehouse(localCountry.id, "Main")
        repository.importBackup(backup, BackupMergeChoices(setOf(source.countryId), mapOf(source.id to
            BackupWarehouseChoice(BackupWarehouseAction.RENAME, "Imported Main"))))
        val before = repository.snapshot()
        val conflict = BackupMerge.warehouseConflicts(before, backup).single()
        assertEquals(source.id, conflict.incoming.id)
        assertEquals("Imported Main", conflict.existing.name)
        assertFalse(conflict.canRename)
        assertThrows(IllegalArgumentException::class.java) { repository.importBackup(backup, BackupMergeChoices(setOf(source.countryId))) }

        assertEquals(0, repository.importBackup(backup, BackupMergeChoices(setOf(source.countryId), mapOf(source.id to
            BackupWarehouseChoice(BackupWarehouseAction.MERGE)))).processed)

        assertEquals(before, repository.snapshot())
        assertEquals(setOf(local.id, source.id), repository.snapshot().warehouses.map { it.id }.toSet())
    }

    @Test fun countryAndWarehouseNamesChangedOnThisPhoneRequireConfirmationAndAreNeverOverwritten() {
        val source = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        repository.save(input(source))
        val backup = repository.snapshot()
        repository.renameCountry(source.countryId, "Norge")
        repository.renameWarehouse(source.id, "Renamed Main")
        val before = repository.snapshot()
        val country = BackupMerge.countryConflicts(before, backup).single()
        val warehouse = BackupMerge.warehouseConflicts(before, backup).single()
        assertEquals("Norge", country.existing.name)
        assertEquals("Renamed Main", warehouse.existing.name)
        assertFalse(warehouse.canRename)
        assertThrows(IllegalArgumentException::class.java) { repository.importBackup(backup) }
        assertEquals(before, repository.snapshot())

        assertEquals(0, repository.importBackup(backup, BackupMergeChoices(setOf(source.countryId), mapOf(source.id to
            BackupWarehouseChoice(BackupWarehouseAction.MERGE)))).processed)
        assertEquals(before, repository.snapshot())
    }

    @Test fun overlargeSourceHistoryCancelsTheWholeMergeInsteadOfTruncatingIt() {
        val warehouse = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        val first = repository.save(input(warehouse, 4, ""))
        val second = repository.save(input(warehouse, 6, ""))
        val source = listOf(first, second).maxWith(compareBy<StockRecord> { it.createdAt }.thenBy { it.id })
        repeat(30) { index -> repository.save(input(warehouse, 6, "$index" + "a".repeat(1200)), source.id) }
        val before = repository.snapshot()

        assertThrows(IllegalArgumentException::class.java) { repository.recoverable("merge") { it.mergeParts(setOf(first.id, second.id)) } }

        assertEquals(before, repository.snapshot())
        assertTrue(repository.recoveryPoints().isEmpty())
    }

    @Test fun deeplyNestedAuditInsideBackupIsRejectedBeforeAnyDataIsWritten() {
        val warehouse = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        val original = repository.save(input(warehouse))
        val before = repository.snapshot()
        val record = original.copy(id = "deep-record")
        val originalAudit = before.changes.single()
        val valid = JSONObject(originalAudit.after).put("id", record.id).toString()
        val deep = valid.dropLast(1) + ",\"extension\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}"
        val candidate = before.copy(records = listOf(record), changes = listOf(originalAudit.copy(id = "deep-audit", recordId = record.id, after = deep)))

        assertThrows(IllegalArgumentException::class.java) { repository.recoverable("deep import") { it.importBackup(candidate) } }

        assertEquals(before, repository.snapshot())
        assertTrue(repository.recoveryPoints().isEmpty())
    }

    @Test fun deeplyNestedMarkerSourceEncodedAsAStringIsRejectedBeforeImport() {
        val warehouse = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        val original = repository.save(input(warehouse))
        val before = repository.snapshot()
        val record = original.copy(id = "deep-marker-record")
        val originalAudit = before.changes.single()
        val valid = JSONObject(originalAudit.after).put("id", record.id)
        val source = valid.toString().dropLast(1) + ",\"extension\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}"
        val audit = valid.put("backupImport", JSONObject().put("source", source)
            .put("destination", JSONObject().put("warehouseId", warehouse.id))).toString()
        val candidate = before.copy(records = listOf(record), changes = listOf(originalAudit.copy(id = "deep-marker-audit", recordId = record.id, after = audit)))

        assertThrows(IllegalArgumentException::class.java) { repository.importBackup(candidate) }

        assertEquals(before, repository.snapshot())
    }

    @Test fun freshBackupThenManualPartMergeDoesNotAddTheOriginalFileAgain() {
        val backup = originalBackup(future = true)
        repository.importBackup(backup)
        val source = backup.records.single()
        val target = repository.save(input(backup.warehouses.single(), 4))

        repository.mergeParts(setOf(source.id, target.id))

        val merged = repository.snapshot()
        assertEquals(10, merged.records.single().quantity)
        assertFalse(merged.records.any { it.id == source.id })
        assertEquals(0, repository.importBackup(backup).processed)
        assertEquals(merged, repository.snapshot())
    }

    @Test fun freshBackupThenWarehouseMergeAndPhoneMigrationKeepOriginalReplaySafe() {
        val backup = originalBackup()
        repository.importBackup(backup)
        val source = backup.warehouses.single()
        val target = repository.addWarehouse(source.countryId, "Target")
        repository.save(input(target, 4))
        repository.mergeWarehouses(source.id, target.id, DuplicateMode.ADD_QUANTITY)
        val merged = repository.snapshot()
        assertEquals(10, merged.records.single().quantity)
        assertEquals(0, repository.importBackup(backup).processed)
        assertEquals(merged, repository.snapshot())

        val migration = portable(merged)
        reset()
        repository.importBackup(migration)
        val migrated = repository.snapshot()
        assertEquals(0, repository.importBackup(backup).processed)
        assertEquals(migrated, repository.snapshot())
        assertEquals(10, repository.snapshot().records.single().quantity)
        assertEquals(listOf(target.id), repository.snapshot().warehouses.map { it.id })
    }

    @Test fun freshMigrationKeepsOlderProcessedStatesOfTheSameRecordId() {
        val warehouse = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        val target = repository.save(input(warehouse, 4))
        val state = repository.snapshot()
        val backup = state.copy(records = state.records + target.copy(id = "source-six", quantity = 6))
        repository.importBackup(backup, BackupMergeChoices(mode = DuplicateMode.ADD_QUANTITY))
        assertEquals(10, repository.snapshot().records.single().quantity)
        val migration = portable(repository.snapshot())
        reset()
        repository.importBackup(migration)
        val migrated = repository.snapshot()

        assertEquals(0, repository.importBackup(backup).processed)

        assertEquals(migrated, repository.snapshot())
        assertEquals(10, repository.snapshot().records.single().quantity)
        assertThrows(IllegalArgumentException::class.java) { repository.importBackup(backup.copy(
            records = backup.records.map { if (it.id == target.id) it.copy(quantity = 99) else it })) }
        assertEquals(migrated, repository.snapshot())
    }

    @Test fun previouslyRestoredLegacyStockGetsIdentityProtectionWhenConsumedByAMerge() {
        val backup = originalBackup()
        repository.restore(backup)
        assertEquals(backup.changes.toSet(), repository.snapshot().changes.toSet())
        val source = backup.warehouses.single()
        val target = repository.addWarehouse(source.countryId, "Target")
        repository.save(input(target, 4))

        repository.mergeWarehouses(source.id, target.id, DuplicateMode.ADD_QUANTITY)

        val merged = repository.snapshot()
        assertEquals(10, merged.records.single().quantity)
        assertEquals(0, repository.importBackup(backup).processed)
        assertEquals(merged, repository.snapshot())
    }

    @Test fun legacyMergedSourcesWithoutMarkersAreRecognizedWithoutRewritingOldHistory() {
        val backup = originalBackup()
        repository.restore(backup)
        val source = backup.warehouses.single()
        val target = repository.addWarehouse(source.countryId, "Target")
        repository.save(input(target, 4))
        repository.mergeWarehouses(source.id, target.id, DuplicateMode.ADD_QUANTITY)
        val merged = repository.snapshot()
        // Model the exact older archive shape: full source record, no marker or source history.
        val legacy = merged.copy(changes = merged.changes.map { change ->
            val json = JSONObject(change.after)
            json.optJSONArray("mergedSources")?.let { array ->
                for (index in 0 until array.length()) array.getJSONObject(index).apply {
                    remove("backupImport"); remove("history"); remove("backupOrigins")
                }
            }
            change.copy(after = json.toString())
        })
        assertFalse(legacy.changes.any { it.after.contains("backupImport") })
        reset()
        repository.restore(legacy)
        val importedLegacy = repository.snapshot()

        assertEquals(0, repository.importBackup(backup).processed)

        assertEquals(importedLegacy, repository.snapshot())
        assertEquals(10, repository.snapshot().records.single().quantity)
        assertThrows(IllegalArgumentException::class.java) { repository.importBackup(backup.copy(
            records = backup.records.map { it.copy(quantity = 7) })) }
        assertEquals(importedLegacy, repository.snapshot())
        assertEquals(listOf(target.id), repository.snapshot().warehouses.map { it.id })
    }

    @Test fun legacyMachinesVoidedPartsAndNewEntryMovesKeepReplayIdentityWithAndWithoutMarkers() {
        val source = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
        repository.save(input(source, 6))
        repository.save(input(source, 1).copy(kind = ItemKind.MACHINE, bom = "", sn = "AA-001", pn = "00-A-01"))
        val voided = repository.save(input(source, 2).copy(bom = "VOID"))
        repository.voidRecord(voided.id)
        val backup = repository.snapshot()
        reset()
        for (mode in DuplicateMode.entries) {
            repository.restore(backup)
            val target = repository.addWarehouse(source.countryId, "Target")
            repository.save(input(target, 4))
            repository.mergeWarehouses(source.id, target.id, mode)
            val moved = repository.snapshot()
            assertEquals(10, moved.records.filter { it.kind == ItemKind.PART && !it.voided }.sumOf { it.quantity })
            assertEquals("00-A-01", moved.records.single { it.kind == ItemKind.MACHINE }.pn)
            assertTrue(moved.records.single { it.id == voided.id }.voided)
            assertEquals(0, repository.importBackup(backup).processed)
            assertEquals(moved, repository.snapshot())

            val legacy = oldStyle(moved)
            assertFalse(legacy.changes.any { it.after.contains("backupImport") })
            reset()
            repository.restore(legacy)
            val restoredLegacy = repository.snapshot()
            assertEquals(0, repository.importBackup(backup).processed)
            assertEquals(restoredLegacy, repository.snapshot())
            reset()
        }
    }

    @Test fun warehouseMoveCompatibilityDoesNotTreatAnOrdinaryQuantityEditAsAProcessedOldState() {
        val backup = originalBackup(quantity = 4)
        repository.restore(backup)
        val source = backup.warehouses.single()
        repository.save(input(source, 6), backup.records.single().id)
        val target = repository.addWarehouse(source.countryId, "Target")
        repository.mergeWarehouses(source.id, target.id, DuplicateMode.NEW_ENTRY)
        val legacy = oldStyle(repository.snapshot())
        reset()
        repository.restore(legacy)
        val before = repository.snapshot()

        assertThrows(IllegalArgumentException::class.java) { repository.importBackup(backup) }

        assertEquals(before, repository.snapshot())
        assertEquals(6, repository.snapshot().records.single().quantity)
    }
}
