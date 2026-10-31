package com.fieldstock.app.importdata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.i18n.*
import com.fieldstock.app.model.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ImportNotePersistenceAuditTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repository: InventoryRepository
    private lateinit var warehouse: Warehouse

    @Before fun seed() {
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        repository = InventoryRepository(context)
        warehouse = repository.addWarehouse(repository.addCountry("Norway").id, "Main")
    }

    @After fun close() {
        repository.close()
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        AppLanguage.current = AppLanguage.CHINESE
    }

    private fun persist(bytes: ByteArray, name: String, expected: String) {
        val row = InventoryImport.read(bytes.inputStream(), name).single()
        assertEquals(expected, row.note)
        repository.importRecords(listOf(RecordInput(repository.snapshot().tasks.first().id, warehouse.id,
            row.kind, row.bom, row.sn, row.quantity, row.condition, row.note, pn = row.pn)), DuplicateMode.NEW_ENTRY)
        assertEquals(expected, repository.snapshot().records.last().note)
    }

    @Test fun exportedReportsKeepNoteWhitespaceThroughCrossLanguageDatabaseImport() {
        val note = "\n first line\r\nsecond line \n"
        val state = InventorySnapshot(listOf(InventoryTask("task", "First", 1)),
            listOf(Country("country", "Norway")), listOf(Warehouse("warehouse", "country", "Main")),
            listOf(StockRecord("record", "task", "warehouse", ItemKind.PART, "000Ab", "", 3,
                StockCondition.NORMAL, note, "", "manual", "manual", "", "", 1, 1, false)))
        for (language in AppLanguage.entries) {
            AppLanguage.current = if (language == AppLanguage.CHINESE) AppLanguage.ENGLISH else AppLanguage.CHINESE
            val xlsx = ByteArrayOutputStream().also { InventoryFiles.writeXlsx(state, it, null, null, language) }.toByteArray()
            persist(xlsx, "report.xlsx", note)
            val csv = ByteArrayOutputStream().also { InventoryFiles.writeCsvZip(state, it, null, null, language) }.toByteArray()
            persist(csv, "report.zip", note)
        }
    }

    @Test fun standardCsvKeepsLeadingAndTrailingSpacesInPersistedNotes() {
        val note = "  中文 English note  "
        for ((language, header) in listOf(AppLanguage.ENGLISH to "类型,BOM,SN,数量,状态,备注",
                AppLanguage.CHINESE to "Type,BOM,SN,Quantity,Condition,Note")) {
            AppLanguage.current = language
            persist(("$header\nPart,000Ab,,3,Normal,\"$note\"\n").toByteArray(), "template.csv", note)
        }
    }
}
