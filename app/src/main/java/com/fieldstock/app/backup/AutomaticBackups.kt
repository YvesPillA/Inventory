package com.fieldstock.app.backup

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
        check(directory.isDirectory || directory.mkdirs()) { "无法创建备份目录，请检查手机存储空间" }
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
            check(restored == snapshot) { "备份回读与库存不一致，未替换已有备份" }
            check(temporary.renameTo(destination)) { "无法保存完整备份，请检查手机存储空间" }
            verified += destination.name
            val entry = AutomaticBackup(destination.name, at, day, destination.length())
            // Retain the newly created file even after the phone's clock moves backwards.
            val stale = entries().filter { it.id != entry.id }.drop(KEEP - 1)
            check(stale.all { fileFor(it.id).delete().also { removed -> if (removed) verified -= it.id } }) {
                "新备份已保存，部分旧备份暂未清理，请稍后重试"
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
        require(NAME.matches(id)) { "备份文件名称无效" }
        return File(directory, id)
    }

    private fun currentClock() = clock ?: Clock.systemDefaultZone()

    companion object {
        const val KEEP = 7
        private val NAME = Regex("auto-(\\d{4}-\\d{2}-\\d{2})-(\\d+)-[a-f0-9-]{36}\\.json")
    }
}
