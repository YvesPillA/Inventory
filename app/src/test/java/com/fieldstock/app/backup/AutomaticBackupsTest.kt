package com.fieldstock.app.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AutomaticBackupsTest {
    @get:Rule val folder = TemporaryFolder()
    private fun clock(time: String = "2026-10-20T12:00:00Z", zone: String = "Asia/Hong_Kong") =
        Clock.fixed(Instant.parse(time), ZoneId.of(zone))
    private fun snapshot() = InventorySnapshot(
        tasks = listOf(InventoryTask("task", "历史兼容", 1)),
        countries = listOf(Country("c1", "挪威"), Country("c2", "瑞典")),
        warehouses = listOf(Warehouse("w1", "c1", "A"), Warehouse("w2", "c2", "B")),
        records = listOf(
            record("part", "w1", "000aB", 12),
            record("pending", "w2", "", 2, condition = "待核查"),
            record("void", "w2", "B008", 1, voided = true),
        ),
        changes = listOf(ChangeLog("audit", "part", "CREATE", "", "原始历史", 2)),
    )
    private fun record(id: String, warehouse: String, bom: String, quantity: Int, condition: String = "正常", voided: Boolean = false) =
        StockRecord(id, "task", warehouse, ItemKind.PART, bom, "", quantity, condition, "标签需核对", "旧库位",
            "ocr", "manual", "BOM: 000aB", "", 1, 2, voided)

    @Test fun firstUseCreatesExactlyOneBackupAndSameDayReopenDoesNotResnapshot() {
        val directory = folder.newFolder()
        val first = AutomaticBackups(directory, clock()).ensureDaily(::snapshot)!!
        val reopened = AutomaticBackups(directory, clock("2026-10-20T15:00:00Z"))
        assertNull(reopened.ensureDaily { error("Same-day reopen must not capture a new snapshot") })
        assertEquals(listOf(first), reopened.entries())
        assertEquals(snapshot(), reopened.read(first.id))
    }

    @Test fun nextLocalDayCreatesAnotherBackup() {
        val directory = folder.newFolder()
        AutomaticBackups(directory, clock("2026-10-20T15:59:59Z")).ensureDaily(::snapshot)
        val next = AutomaticBackups(directory, clock("2026-10-20T16:00:00Z"))
        assertNotNull(next.ensureDaily(::snapshot))
        assertEquals(listOf(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-20")), next.entries().map { it.day })
    }

    @Test fun travelTimeZoneUsesPhoneLocalDate() {
        val directory = folder.newFolder()
        val time = "2026-10-20T22:30:00Z"
        val hongKong = AutomaticBackups(directory, clock(time))
        assertEquals(LocalDate.parse("2026-10-21"), hongKong.ensureDaily(::snapshot)!!.day)
        val norway = AutomaticBackups(directory, clock(time, "Europe/Oslo"))
        assertNull(norway.ensureDaily { error("Same local day must reuse its valid backup") })
        val london = AutomaticBackups(directory, clock(time, "Europe/London"))
        assertEquals(LocalDate.parse("2026-10-20"), london.ensureDaily(::snapshot)!!.day)
    }

    @Test fun defaultClockSeesTimeZoneChangesWithoutRecreatingStore() {
        val original = TimeZone.getDefault()
        try {
            val directory = folder.newFolder()
            val store = AutomaticBackups(directory)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val first = store.ensureDaily(::snapshot)!!
            TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
            val second = store.ensureDaily(::snapshot)!!
            assertNotEquals(first.day, second.day)
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun successfulWriteRetainsSevenAndCanReadEveryRemainingBackup() {
        val directory = folder.newFolder()
        var last: AutomaticBackups? = null
        repeat(10) { index ->
            last = AutomaticBackups(directory, clock("2026-10-${10 + index}T12:00:00Z"))
            last!!.ensureDaily(::snapshot)
        }
        assertEquals(7, last!!.entries().size)
        assertEquals(LocalDate.parse("2026-10-13"), last!!.entries().last().day)
        last!!.entries().forEach { assertEquals(snapshot(), last!!.read(it.id)) }
    }

    @Test fun failedWriteKeepsAllOldFilesAndLeavesNoTemporaryOrPublishedFile() {
        val directory = folder.newFolder()
        val good = AutomaticBackups(directory, clock())
        val old = good.create(snapshot())
        val failing = AutomaticBackups(directory, clock("2026-10-21T12:00:00Z")) { _, output ->
            output.write("partial".toByteArray())
            throw IOException("No space")
        }
        assertThrows(IOException::class.java) { failing.ensureDaily(::snapshot) }
        assertEquals(listOf(old), good.entries())
        assertEquals(snapshot(), good.read(old.id))
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun failedReadbackDoesNotPublishOrPrune() {
        val directory = folder.newFolder()
        val good = AutomaticBackups(directory, clock())
        val old = good.create(snapshot())
        val invalid = AutomaticBackups(directory, clock()) { _, output -> output.write("{}".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { invalid.create(snapshot()) }
        assertEquals(listOf(old), good.entries())
    }

    @Test fun validButWrongSnapshotIsNotPublished() {
        val directory = folder.newFolder()
        val wrong = AutomaticBackups(directory, clock()) { _, output ->
            com.fieldstock.app.export.InventoryFiles.writeBackup(InventorySnapshot(), output)
        }
        assertThrows(IllegalStateException::class.java) { wrong.create(snapshot()) }
        assertTrue(wrong.entries().isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun damagedSameDayFileIsReplacedOnReopenAndCannotBeShared() {
        val directory = folder.newFolder()
        val old = AutomaticBackups(directory, clock()).create(snapshot())
        File(directory, old.id).writeText("{}")
        val reopened = AutomaticBackups(directory, clock())
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { reopened.copyTo(old.id, output) }
        assertEquals(0, output.size())
        assertNotNull(reopened.ensureDaily(::snapshot))
        assertEquals(2, reopened.entries().size)
    }

    @Test fun sameProcessRechecksCorruptedBackupInsteadOfTrustingItsPreviouslyVerifiedName() {
        val directory = folder.newFolder()
        val store = AutomaticBackups(directory, clock())
        val old = store.ensureDaily(::snapshot)!!
        File(directory, old.id).writeText("{}")

        val replacement = store.ensureDaily(::snapshot)

        assertNotNull("A cached filename must not suppress replacement of a damaged daily backup", replacement)
        assertNotEquals(old.id, replacement!!.id)
        assertEquals(snapshot(), store.read(replacement.id))
    }

    @Test fun manualBackupCanCaptureLaterInventoryOnSameDay() {
        val directory = folder.newFolder()
        val store = AutomaticBackups(directory, clock())
        val morning = store.ensureDaily(::snapshot)!!
        val later = snapshot().copy(records = snapshot().records.drop(1))
        val evening = store.create(later)
        assertNotEquals(morning.id, evening.id)
        assertEquals(snapshot(), store.read(morning.id))
        assertEquals(later, store.read(evening.id))
        assertNull(store.ensureDaily { error("Do not replace manually refreshed backup") })
    }

    @Test fun clockRollbackStillRetainsNewlyWrittenBackup() {
        val directory = folder.newFolder()
        repeat(7) { AutomaticBackups(directory, clock("2026-10-${20 + it}T12:00:00Z")).create(snapshot()) }
        val rollback = AutomaticBackups(directory, clock("2026-10-10T12:00:00Z"))
        val entry = rollback.create(snapshot())
        assertEquals(7, rollback.entries().size)
        assertEquals(snapshot(), rollback.read(entry.id))
    }

    @Test fun invalidOrTraversalIdentifierIsRejected() {
        val store = AutomaticBackups(folder.newFolder(), clock())
        listOf("../escape.json", "other.json", "/tmp/backup.json").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { store.read(id) }
        }
    }

    @Test fun missingDirectoryCanBeCreatedButFileInItsPlaceFailsWithoutLosingInventory() {
        val parent = folder.newFolder()
        val blocked = File(parent, "backups").apply { writeText("occupied") }
        assertThrows(IllegalStateException::class.java) { AutomaticBackups(blocked, clock()).create(snapshot()) }
        assertEquals("occupied", blocked.readText())
    }

    @Test fun portableBackupRestoresAllWarehousesVoidsAndHistoryIntoEmptyPhone() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        val original = InventoryRepository(context).use { repository ->
            val first = repository.addWarehouse(repository.addCountry("挪威").id, "A")
            val second = repository.addWarehouse(repository.addCountry("瑞典").id, "B")
            val task = repository.snapshot().tasks.first().id
            repository.save(RecordInput(task, first.id, ItemKind.PART, "000aB", "", 12, "正常", "标签原文"))
            repository.save(RecordInput(task, second.id, ItemKind.PART, "", "", 2, "待核查", "待查"))
            val archived = repository.save(RecordInput(task, second.id, ItemKind.MACHINE, "MODEL", "AA-023", 1, "正常", ""))
            repository.voidRecord(archived.id)
            repository.snapshot()
        }
        val store = AutomaticBackups(folder.newFolder(), clock())
        val entry = store.create(original)
        val bytes = ByteArrayOutputStream().also { store.copyTo(entry.id, it) }.toByteArray()
        val data = bytes.inputStream().use(com.fieldstock.app.export.InventoryFiles::readBackup)
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        try {
            InventoryRepository(context).use { repository ->
                repository.restore(data)
                val restored = repository.snapshot()
                assertEquals(original.records.toSet(), restored.records.toSet())
                assertEquals(original.countries.toSet(), restored.countries.toSet())
                assertEquals(original.warehouses.toSet(), restored.warehouses.toSet())
                assertEquals(original.changes.toSet(), restored.changes.toSet())
            }
        } finally { context.deleteDatabase(InventoryRepository.DATABASE_NAME) }
    }
}
