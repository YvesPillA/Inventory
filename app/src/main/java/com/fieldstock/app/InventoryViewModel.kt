package com.fieldstock.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class PendingExport(val kind: String, val snapshot: InventorySnapshot, val taskId: String?, val warehouseId: String?)

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = InventoryRepository(application)
    private val preferences = application.getSharedPreferences("fieldstock", 0)
    private val operations = Mutex()
    private val exportCache = File(application.cacheDir, "pending-export.json")
    var snapshot by mutableStateOf(InventorySnapshot()); private set
    var busy by mutableStateOf(false); private set
    // Legacy task IDs remain in backups for compatibility; they no longer scope inventory.
    val storageTaskId: String get() = snapshot.tasks.firstOrNull()?.id.orEmpty()
    var warehouseId by mutableStateOf(preferences.getString("warehouse", "") ?: ""); private set
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var duplicate by mutableStateOf<StockRecord?>(null)
    var duplicateOnRestore by mutableStateOf(false)
    var migrationVoidedCount by mutableIntStateOf(0); private set
    var restoreCandidate by mutableStateOf<InventorySnapshot?>(null)
    var pendingExport: PendingExport? = null
    var lastExport by mutableStateOf(preferences.getString("last_export", "尚未导出") ?: "尚未导出"); private set
    var lastBackupAt by mutableStateOf(preferences.getLong("backup_at", 0)); private set

    init { perform("") {} }

    fun select(warehouse: String) {
        warehouseId = warehouse
        preferences.edit().remove("task").putString("warehouse", warehouse).apply()
    }

    fun perform(success: String, after: () -> Unit = {}, work: (InventoryRepository) -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
          operations.withLock {
            try {
                val (fresh, migratedCount) = withContext(Dispatchers.IO) {
                    work(repository)
                    repository.snapshot() to repository.pendingMigrationVoidedCount()
                }
                snapshot = fresh
                migrationVoidedCount = migratedCount
                select(warehouseId.takeIf { id -> fresh.warehouses.any { it.id == id } } ?: fresh.warehouses.firstOrNull()?.id.orEmpty())
                if (success.isNotEmpty()) message = success
                after()
            } catch (e: DuplicateSnException) {
                duplicate = e.existing
            } catch (e: Exception) {
                error = e.message ?: "操作未完成，请重试"
            } finally { busy = false }
          }
        }
    }

    fun restoreVoided(id: String) {
        duplicateOnRestore = true
        perform("记录已还原") { it.restoreVoided(id) }
    }

    fun acknowledgeMigration() {
        perform("", after = { migrationVoidedCount = 0 }) { it.acknowledgeMigrationNotice() }
    }

    fun prepareExport(kind: String, currentWarehouse: Boolean, launchPicker: () -> Unit) {
        if (busy) return
        busy = true
        val request = PendingExport(kind, snapshot, null,
            if (kind != "backup" && currentWarehouse) warehouseId else null)
        viewModelScope.launch {
            operations.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        exportCache.outputStream().use { InventoryFiles.writeBackup(request.snapshot, it) }
                        // Persist before showing another Activity so process recreation can finish the export.
                        check(preferences.edit().putString("pending_kind", request.kind).putString("pending_task", request.taskId)
                            .putString("pending_warehouse", request.warehouseId).commit()) { "无法保存导出请求" }
                    }
                    pendingExport = request
                    launchPicker()
                } catch (e: Exception) { error = "无法准备导出：${e.message}" }
                finally { busy = false }
            }
        }
    }

    fun export(uri: Uri?) {
        if (uri == null) { clearPendingExport(); return }
        viewModelScope.launch {
          operations.withLock {
            busy = true
            try {
                val request = pendingExport ?: withContext(Dispatchers.IO) {
                    val kind = preferences.getString("pending_kind", null) ?: error("导出请求已失效，请重新导出")
                    val cached = exportCache.inputStream().use(InventoryFiles::readBackup)
                    PendingExport(kind, cached, preferences.getString("pending_task", null), preferences.getString("pending_warehouse", null))
                }
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        when (request.kind) {
                            "xlsx" -> InventoryFiles.writeXlsx(request.snapshot, output, request.taskId, request.warehouseId)
                            "csv" -> InventoryFiles.writeCsvZip(request.snapshot, output, request.taskId, request.warehouseId)
                            else -> InventoryFiles.writeBackup(request.snapshot, output)
                        }
                    } ?: error("无法打开保存位置，请重新选择")
                }
                val at = System.currentTimeMillis()
                lastExport = "${formatTime(at)} · ${if (request.kind == "backup") "完整备份" else if (request.warehouseId == null) "全部仓库" else warehouseLabel(request.warehouseId)}"
                preferences.edit().putString("last_export", lastExport).apply()
                if (request.kind == "backup") {
                    lastBackupAt = request.snapshot.records.maxOfOrNull { it.updatedAt } ?: at
                    preferences.edit().putLong("backup_at", lastBackupAt).apply()
                }
                message = "文件已保存，请复制到电脑或其他备份位置"
            } catch (e: Exception) { error = "文件未保存成功：${e.message ?: "请重新选择位置"}" }
            finally { clearPendingExport(); busy = false }
          }
        }
    }

    private fun clearPendingExport() {
        pendingExport = null
        preferences.edit().remove("pending_kind").remove("pending_task").remove("pending_warehouse").apply()
        exportCache.delete()
    }

    fun previewRestore(uri: Uri?) {
        if (uri == null || busy) return
        busy = true
        viewModelScope.launch {
            try {
                restoreCandidate = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(InventoryFiles::readBackup)
                        ?: error("无法打开备份文件")
                }
            } catch (e: Exception) { error = "备份未读取：${e.message}" }
            finally { busy = false }
        }
    }

    fun warehouseLabel(id: String): String {
        val warehouse = snapshot.warehouses.find { it.id == id } ?: return "未选择仓库"
        return "${snapshot.countries.find { it.id == warehouse.countryId }?.name.orEmpty()} / ${warehouse.name}"
    }

    override fun onCleared() { repository.close(); super.onCleared() }

    companion object {
        fun formatTime(at: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    }
}
