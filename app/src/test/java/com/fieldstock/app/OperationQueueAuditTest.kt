package com.fieldstock.app

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.importdata.ImportRow
import com.fieldstock.app.importdata.InventoryImport
import com.fieldstock.app.i18n.TextKey
import com.fieldstock.app.i18n.tr
import com.fieldstock.app.i18n.StockCondition
import com.fieldstock.app.model.*
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OperationQueueAuditTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Queued work did not finish")
    }
    private fun idle(vm: InventoryViewModel) = !vm.busy && vm.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive }

    @Test fun repeatedConfirmationOfOneQueuedImportAddsTheFileOnlyOnce() {
        app.deleteDatabase(InventoryRepository.DATABASE_NAME)
        app.getSharedPreferences("fieldstock", 0).edit().clear().putBoolean("automatic_backup", false).commit()
        val warehouse = InventoryRepository(app).use { repo ->
            val w = repo.addWarehouse(repo.addCountry("Norway").id, "Main")
            repo.save(RecordInput(repo.snapshot().tasks.first().id, w.id, ItemKind.PART, "ABC01", "", 4, StockCondition.NORMAL, "local"))
            w.id
        }
        val vm = InventoryViewModel(app)
        val store = ViewModelStore().apply { put("test", vm) }
        val release = CountDownLatch(1)
        try {
            await { idle(vm) }
            vm.importRows = listOf(ImportRow(ItemKind.PART, "ABC01", "", 3, StockCondition.NORMAL, "incoming"))
            vm.perform("background work") { release.await(10, TimeUnit.SECONDS) }
            vm.applyImport(warehouse, DuplicateMode.ADD_QUANTITY)
            vm.applyImport(warehouse, DuplicateMode.ADD_QUANTITY)
            release.countDown()
            await { idle(vm) }
            InventoryRepository(app).use { repo ->
                assertEquals("A second tap on the same confirmation must not add stock twice", 7, repo.snapshot().records.single().quantity)
                assertEquals(1, repo.importReceipts().size)
            }
        } finally {
            release.countDown(); await { idle(vm) }; store.clear(); app.deleteDatabase(InventoryRepository.DATABASE_NAME)
        }
    }

    @Test fun clearingModelDoesNotBlockTheMainThreadBehindAnActiveTransaction() {
        app.deleteDatabase(InventoryRepository.DATABASE_NAME)
        app.getSharedPreferences("fieldstock", 0).edit().clear().putBoolean("automatic_backup", false).commit()
        val vm = InventoryViewModel(app)
        val store = ViewModelStore().apply { put("test", vm) }
        val lifetime = vm.viewModelScope.coroutineContext[Job]!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            await { idle(vm) }
            vm.perform("long transaction", recoveryLabel = "lifecycle regression") {
                entered.countDown(); release.await(10, TimeUnit.SECONDS)
            }
            await { entered.count == 0L }
            timer.schedule({ release.countDown() }, 2, TimeUnit.SECONDS)
            val started = System.nanoTime()
            store.clear()
            assertTrue("Closing the screen must not wait for a background transaction on the UI thread",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
        } finally {
            release.countDown(); timer.shutdownNow(); await { lifetime.isCompleted }; app.deleteDatabase(InventoryRepository.DATABASE_NAME)
        }
    }

    @Test fun queuedImportRechecksAReceiptWrittenByEarlierWork() {
        app.deleteDatabase(InventoryRepository.DATABASE_NAME)
        app.getSharedPreferences("fieldstock", 0).edit().clear().putBoolean("automatic_backup", false).commit()
        val input = InventoryRepository(app).use { repo ->
            val w = repo.addWarehouse(repo.addCountry("Norway").id, "Main")
            RecordInput(repo.snapshot().tasks.first().id, w.id, ItemKind.PART, "ABC01", "", 4, StockCondition.NORMAL, "local")
                .also { repo.save(it) }
        }
        val vm = InventoryViewModel(app)
        val store = ViewModelStore().apply { put("test", vm) }
        val release = CountDownLatch(1)
        val row = ImportRow(ItemKind.PART, "ABC01", "", 3, StockCondition.NORMAL, "incoming")
        try {
            await { idle(vm) }
            vm.importRows = listOf(row)
            vm.perform("earlier import") { repo ->
                release.await(10, TimeUnit.SECONDS)
                repo.importRecords(listOf(input.copy(quantity = 3, note = row.note)), DuplicateMode.ADD_QUANTITY,
                    ImportMetadata(InventoryImport.fingerprint(listOf(row)), "earlier.csv"))
            }
            vm.applyImport(input.warehouseId, DuplicateMode.ADD_QUANTITY)
            release.countDown(); await { idle(vm) }
            assertEquals(7, vm.snapshot.records.single().quantity)
            assertEquals(1, vm.importReceipts.size)
            assertEquals(tr(TextKey.THIS_FILE_CONTENT_WAS_IMPORTED_BEFORE_PREVIEW_IT_AND), vm.error)
        } finally {
            release.countDown(); await { idle(vm) }; store.clear(); app.deleteDatabase(InventoryRepository.DATABASE_NAME)
        }
    }

    @Test fun aFailedQueuedImportReleasesItsConfirmationSoTheUserCanRetry() {
        app.deleteDatabase(InventoryRepository.DATABASE_NAME)
        app.getSharedPreferences("fieldstock", 0).edit().clear().putBoolean("automatic_backup", false).commit()
        val record = InventoryRepository(app).use { repo ->
            val w = repo.addWarehouse(repo.addCountry("Norway").id, "Main")
            repo.save(RecordInput(repo.snapshot().tasks.first().id, w.id, ItemKind.PART, "ABC01", "", 4, StockCondition.NORMAL, "local"))
        }
        fun input(qty: Int) = RecordInput(record.taskId, record.warehouseId, record.kind, record.bom, "", qty, record.condition, record.note)
        val vm = InventoryViewModel(app)
        val store = ViewModelStore().apply { put("test", vm) }
        val release = CountDownLatch(1)
        try {
            await { idle(vm) }
            vm.importRows = listOf(ImportRow(ItemKind.PART, "ABC01", "", 3, StockCondition.NORMAL, "incoming"))
            vm.perform("concurrent quantity change") { repo -> release.await(10, TimeUnit.SECONDS); repo.save(input(1_000_000_000), record.id) }
            vm.applyImport(record.warehouseId, DuplicateMode.ADD_QUANTITY)
            release.countDown(); await { idle(vm) }
            assertNotNull(vm.error); assertEquals(1_000_000_000, vm.snapshot.records.single().quantity)
            assertNotNull(vm.importRows); assertTrue(vm.importReceipts.isEmpty())
            vm.error = null
            vm.perform("correct quantity") { it.save(input(4), record.id) }
            await { idle(vm) }
            vm.applyImport(record.warehouseId, DuplicateMode.ADD_QUANTITY)
            await { idle(vm) }
            assertNull(vm.error); assertEquals(7, vm.snapshot.records.single().quantity); assertEquals(1, vm.importReceipts.size)
        } finally {
            release.countDown(); await { idle(vm) }; store.clear(); app.deleteDatabase(InventoryRepository.DATABASE_NAME)
        }
    }

    @Test fun reopeningWaitsForPreviouslyGrantedWorkToFinishBeforeLoadingStock() {
        app.deleteDatabase(InventoryRepository.DATABASE_NAME)
        app.getSharedPreferences("fieldstock", 0).edit().clear().putBoolean("automatic_backup", false).commit()
        val record = InventoryRepository(app).use { repo ->
            val w = repo.addWarehouse(repo.addCountry("Norway").id, "Main")
            repo.save(RecordInput(repo.snapshot().tasks.first().id, w.id, ItemKind.PART, "ABC01", "", 4, StockCondition.NORMAL, "local"))
        }
        val first = InventoryViewModel(app)
        val storeA = ViewModelStore().apply { put("first", first) }
        val firstLifetime = first.viewModelScope.coroutineContext[Job]!!
        val storeB = ViewModelStore()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            await { idle(first) }
            first.perform("granted operation") { repo ->
                entered.countDown(); release.await(10, TimeUnit.SECONDS)
                repo.save(RecordInput(record.taskId, record.warehouseId, record.kind, record.bom, "", 7, record.condition, record.note), record.id)
            }
            await { entered.count == 0L }
            storeA.clear()
            val second = InventoryViewModel(app).also { storeB.put("second", it) }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(second.busy)
            release.countDown()
            await { idle(second) && firstLifetime.isCompleted }
            assertEquals(7, second.snapshot.records.single().quantity)
        } finally {
            release.countDown(); await { firstLifetime.isCompleted }; storeA.clear(); storeB.clear(); app.deleteDatabase(InventoryRepository.DATABASE_NAME)
        }
    }
}
