package com.fieldstock.app.backup

import com.fieldstock.app.i18n.*

import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.model.InventorySnapshot
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class AutomaticBackup(val id: String, val at: Long, val day: LocalDate, val bytes: Long)

/**
 * Private, portable inventory backups. Callers serialize access with inventory operations.
 * Only a fully written, checksum-verified file is published; retention runs after publication.
 */
class AutomaticBackups(
    private val directory: File,
    private val clock: Clock? = null,
    private val writer: (InventorySnapshot, OutputStream) -> Unit = InventoryFiles::writeBackup,
) {
    private val verified = mutableSetOf<String>()

    fun entries(): List<AutomaticBackup> = directory.listFiles().orEmpty().mapNotNull { file ->
        if (!file.isFile) return@mapNotNull null
        val match = NAME.matchEntire(file.name) ?: return@mapNotNull null
        runCatching {
            AutomaticBackup(file.name, match.groupValues[2].toLong(), LocalDate.parse(match.groupValues[1]), file.length())
        }.getOrNull()
    }.sortedWith(compareByDescending<AutomaticBackup> { it.at }.thenByDescending { it.id })

    fun ensureDaily(snapshot: () -> InventorySnapshot): AutomaticBackup? {
        val today = LocalDate.now(currentClock())
        val validToday = entries().filter { it.day == today }.any { entry ->
            entry.id in verified || runCatching { read(entry.id); true }.getOrDefault(false)
        }
        return if (validToday) null else create(snapshot())
    }

    fun create(snapshot: InventorySnapshot): AutomaticBackup {
        check(directory.isDirectory || directory.mkdirs()) { tr(TextKey.COULD_NOT_CREATE_THE_BACKUP_FOLDER_CHECK_AVAILABLE_STORAGE) }
        val now = currentClock()
        val at = now.millis()
        val day = LocalDate.now(now)
        val destination = File(directory, "auto-$day-$at-${UUID.randomUUID()}.json")
        val temporary = File.createTempFile("writing-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                writer(snapshot, output)
                output.flush()
                output.fd.sync()
            }
            val restored = temporary.inputStream().use(InventoryFiles::readBackup)
            check(restored == snapshot) { tr(TextKey.BACKUP_VERIFICATION_DIFFERS_FROM_INVENTORY_EXISTING_BACKUPS_WERE_KEPT) }
            check(temporary.renameTo(destination)) { tr(TextKey.COULD_NOT_SAVE_THE_FULL_BACKUP_CHECK_AVAILABLE_STORAGE) }
            verified += destination.name
            val entry = AutomaticBackup(destination.name, at, day, destination.length())
            // Retain the newly created file even after the phone's clock moves backwards.
            val stale = entries().filter { it.id != entry.id }.drop(KEEP - 1)
            check(stale.all { fileFor(it.id).delete().also { removed -> if (removed) verified -= it.id } }) {
                tr(TextKey.NEW_BACKUP_SAVED_SOME_OLDER_BACKUPS_COULD_NOT_BE)
            }
            return entry
        } finally {
            temporary.delete()
        }
    }

    fun read(id: String): InventorySnapshot = fileFor(id).inputStream().use(InventoryFiles::readBackup).also { verified += id }

    /** Validate before exposing a file through Android's save/share picker. */
    fun copyTo(id: String, output: OutputStream) {
        read(id)
        fileFor(id).inputStream().use { it.copyTo(output) }
    }

    private fun fileFor(id: String): File {
        require(NAME.matches(id)) { tr(TextKey.INVALID_BACKUP_FILENAME) }
        return File(directory, id)
    }

    private fun currentClock() = clock ?: Clock.systemDefaultZone()

    companion object {
        const val KEEP = 7
        private val NAME = Regex("auto-(\\d{4}-\\d{2}-\\d{2})-(\\d+)-[a-f0-9-]{36}\\.json")
    }
}
