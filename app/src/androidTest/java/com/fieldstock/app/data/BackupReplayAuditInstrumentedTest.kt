package com.fieldstock.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldstock.app.i18n.StockCondition
import com.fieldstock.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs unchanged against the previous APK to demonstrate the original quantity regression. */
@RunWith(AndroidJUnit4::class)
class BackupReplayAuditInstrumentedTest {
    @Test fun restoringMergingWarehousesAndRestoringAgainDoesNotRecountTheSameStock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        val original = InventoryRepository(context).use { repo ->
            val w = repo.addWarehouse(repo.addCountry("Norway").id, "Main")
            repo.save(RecordInput(repo.snapshot().tasks.first().id, w.id, ItemKind.PART, "ABC01", "", 6, StockCondition.NORMAL, "source"))
            repo.snapshot()
        }
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        InventoryRepository(context).use { repo ->
            repo.importBackup(original)
            val target = repo.addWarehouse(repo.snapshot().countries.single().id, "Destination")
            repo.save(RecordInput(repo.snapshot().tasks.first().id, target.id, ItemKind.PART, "ABC01", "", 4, StockCondition.NORMAL, "local"))
            repo.mergeWarehouses(original.warehouses.single().id, target.id, DuplicateMode.ADD_QUANTITY)
            assertEquals(10, repo.snapshot().records.sumOf { it.quantity })
            repo.importBackup(original)
            assertEquals("The six source items were already counted in the merged ten", 10, repo.snapshot().records.sumOf { it.quantity })
            assertEquals(1, repo.snapshot().warehouses.size)
        }
    }
}
