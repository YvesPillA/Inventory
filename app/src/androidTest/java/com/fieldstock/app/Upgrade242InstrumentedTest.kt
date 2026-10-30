package com.fieldstock.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.i18n.AppLanguage
import com.fieldstock.app.i18n.LanguageSettings
import com.fieldstock.app.i18n.StockCondition
import com.fieldstock.app.model.RecordInput
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Run immediately after replacing a real 2.4.1 APK seeded by BilingualWorkflowTest. */
@RunWith(AndroidJUnit4::class)
class Upgrade242InstrumentedTest {
    @Test fun real241InstallationKeepsStockPreferencesHistoryAndCanUsePnAfterUpgrade() {
        assumeTrue("Requires the retained 2.4.1 fixture; opt in with -e upgradeFixture242 true",
            InstrumentationRegistry.getArguments().getString("upgradeFixture242") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(AppLanguage.CHINESE, LanguageSettings.selected(context))
        InventoryRepository(context).use { repository ->
            val before = repository.snapshot()
            val record = before.records.single()
            assertEquals("000aB", record.bom); assertEquals("000a-0B", record.sn)
            assertEquals("中文 English note", record.note); assertEquals(StockCondition.DAMAGED, record.condition)
            assertEquals("", record.pn); assertEquals("manual", record.sourcePn)
            assertEquals("正常 Warehouse", before.warehouses.single().name)
            assertEquals(before.warehouses.single().id, context.getSharedPreferences("fieldstock", 0).getString("warehouse", null))
            assertEquals(1, before.changes.size)
            assertEquals(0, repository.restore(before))
            val bytes = ByteArrayOutputStream().also { InventoryFiles.writeBackup(before, it) }.toByteArray()
            assertEquals(before, InventoryFiles.readBackup(bytes.inputStream()))
            val updated = repository.save(RecordInput(record.taskId, record.warehouseId, record.kind, record.bom, record.sn,
                1, record.condition, record.note, pn = "001-NEW-A-02"), record.id)
            assertEquals("001-NEW-A-02", updated.pn)
            assertEquals(record.sn, updated.sn); assertEquals(record.note, updated.note)
        }
    }
}
