package com.fieldstock.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import com.fieldstock.app.backup.AutomaticBackup
import com.fieldstock.app.backup.AutomaticBackups
import com.fieldstock.app.backup.BackupMergeChoices
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.importdata.ImportRow
import com.fieldstock.app.importdata.InventoryImport
import com.fieldstock.app.importdata.InventoryImportContent
import com.fieldstock.app.importdata.ImportPreview
import com.fieldstock.app.diagnostics.AppDiagnostics
import com.fieldstock.app.model.*
import com.fieldstock.app.i18n.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class PendingExport(val kind: String, val snapshot: InventorySnapshot, val taskId: String?, val warehouseId: String?,
    val language: AppLanguage = AppLanguage.current)
private data class LoadedInventory(val snapshot: InventorySnapshot, val migrated: Int, val receipts: List<ImportReceipt>, val recovery: List<RecoveryPoint>)
private data class AutomaticBackupStatus(val entries: List<AutomaticBackup>, val warning: String?)

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = InventoryRepository(application)
    private val preferences = application.getSharedPreferences("fieldstock", 0)
    private val feedback = StockFeedback(application)
    private val operations = inventoryOperations
    private val lifetimeJob = viewModelScope.coroutineContext[Job]!!
    private var cleared = false
    private var pendingOperations = 0
    private var importCommitPending = false
    private val exportCache = File(application.cacheDir, "pending-export.json")
    private val automaticBackups = AutomaticBackups(File(application.filesDir, "automatic-backups"))
    var snapshot by mutableStateOf(InventorySnapshot()); private set
    var busy by mutableStateOf(false); private set
    // Legacy task IDs remain in backups for compatibility; they no longer scope inventory.
    val storageTaskId: String get() = snapshot.tasks.firstOrNull()?.id.orEmpty()
    var warehouseId by mutableStateOf(preferences.getString("warehouse", "") ?: ""); private set
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var duplicate by mutableStateOf<StockRecord?>(null)
    var duplicateOnRestore by mutableStateOf(false)
    var duplicateSummary by mutableStateOf<List<StockRecord>>(emptyList())
    var importRows by mutableStateOf<List<ImportRow>?>(null)
    var importReading by mutableStateOf(false); private set
    var importResultVersion by mutableIntStateOf(0); private set
    private var fileReadGeneration = 0
    var importFileName by mutableStateOf(""); private set
    var importFingerprint by mutableStateOf(""); private set
    var importReceipts by mutableStateOf<List<ImportReceipt>>(emptyList()); private set
    var recoveryPoints by mutableStateOf<List<RecoveryPoint>>(emptyList()); private set
    var developerUnlocked by mutableStateOf(false); private set
    var diagnosticsWorking by mutableStateOf(false); private set
    var diagnosticsStatus by mutableStateOf(""); private set
    var diagnosticsResultVersion by mutableIntStateOf(0); private set
    var vibrationEnabled by mutableStateOf(preferences.getBoolean("feedback_vibration", true)); private set
    var soundEnabled by mutableStateOf(preferences.getBoolean("feedback_sound", false)); private set
    var migrationVoidedCount by mutableIntStateOf(0); private set
    var restoreCandidate by mutableStateOf<InventorySnapshot?>(null)
    var pendingExport: PendingExport? = null
    private var lastExportAt by mutableLongStateOf(preferences.getLong("last_export_at", 0))
    private var lastExportKind by mutableStateOf(preferences.getString("last_export_kind", "").orEmpty())
    private var lastExportWarehouse by mutableStateOf(preferences.getString("last_export_label", "").orEmpty())
    val lastExport: String get() = if (lastExportAt == 0L) preferences.getString("last_export", null)
        ?.takeUnless { it == TextKey.NO_EXPORTS_YET.chinese || it == TextKey.NO_EXPORTS_YET.english }
        ?: tr(TextKey.NO_EXPORTS_YET)
        else "${formatTime(lastExportAt)} · ${when (lastExportKind) {
            "template" -> tr(TextKey.STANDARD_IMPORT_TEMPLATE)
            "backup" -> tr(TextKey.FULL_BACKUP)
            else -> lastExportWarehouse.ifEmpty { tr(TextKey.ALL_WAREHOUSES) }
        }}"
    var lastBackupAt by mutableStateOf(preferences.getLong("backup_at", 0)); private set
    var automaticBackupEnabled by mutableStateOf(preferences.getBoolean("automatic_backup", true)); private set
    var automaticBackupEntries by mutableStateOf<List<AutomaticBackup>>(emptyList()); private set
    var automaticBackupWarning by mutableStateOf<String?>(null); private set

    init { perform("") {} }

    fun select(warehouse: String) {
        warehouseId = warehouse
        preferences.edit().remove("task").putString("warehouse", warehouse).apply()
    }

    /** Reserve synchronously so queued work never briefly enables another confirmation. */
    private fun launchOperation(finished: () -> Unit = {}, work: suspend () -> Unit) {
        if (cleared) { finished(); return }
        pendingOperations++
        busy = true
        viewModelScope.launch {
            try { operations.withLock { work() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppDiagnostics.event("operation.queue.failed", level = "ERROR", failure = failure)
                error = failure.message ?: tr(TextKey.OPERATION_INCOMPLETE_PLEASE_TRY_AGAIN)
            } finally {
                pendingOperations--
                busy = pendingOperations > 0
                finished()
            }
        }
    }

    fun perform(success: String, after: () -> Unit = {}, recoveryLabel: String? = null, notify: Boolean = false, queueIfBusy: Boolean = false, finished: () -> Unit = {}, work: (InventoryRepository) -> Unit) {
        if (busy && !queueIfBusy) { AppDiagnostics.event("operation.skipped_busy",mapOf("label" to success),"WARN"); return }
        val operation=java.util.UUID.randomUUID().toString().take(8)
        AppDiagnostics.event("operation.requested",mapOf("id" to operation,"label" to (recoveryLabel ?: success),"queued" to busy))
        launchOperation(finished) {
            val started=android.os.SystemClock.elapsedRealtime()
            try {
                updateAutomaticStatus(withContext(Dispatchers.IO) { automaticStatus() })
                val loaded = withContext(Dispatchers.IO) {
                    if (recoveryLabel == null) work(repository) else repository.recoverable(recoveryLabel, work)
                    LoadedInventory(repository.snapshot(), repository.pendingMigrationVoidedCount(), repository.importReceipts(), repository.recoveryPoints())
                }
                val fresh = loaded.snapshot
                snapshot = fresh
                migrationVoidedCount = loaded.migrated
                importReceipts = loaded.receipts
                recoveryPoints = loaded.recovery
                select(warehouseId.takeIf { id -> fresh.warehouses.any { it.id == id } } ?: fresh.warehouses.firstOrNull()?.id.orEmpty())
                if (success.isNotEmpty()) message = success
                after()
                AppDiagnostics.event("operation.completed",mapOf("id" to operation,"records" to fresh.records.size,"duration_ms" to (android.os.SystemClock.elapsedRealtime()-started)))
                if (notify) testFeedback(false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: DuplicateSnException) {
                AppDiagnostics.event("operation.duplicate_sn",mapOf("id" to operation),"WARN",e)
                duplicate = e.existing
                if (notify) testFeedback(true)
            } catch (e: Exception) {
                AppDiagnostics.event("operation.failed",mapOf("id" to operation),"ERROR",e)
                error = e.message ?: tr(TextKey.OPERATION_INCOMPLETE_PLEASE_TRY_AGAIN)
            }
        }
    }

    fun performMutation(success: String, after: () -> Unit = {}, recoveryLabel: String? = null, notify: Boolean = false, queueIfBusy: Boolean = false, finished: () -> Unit = {}, work: (InventoryRepository) -> MutationResult) {
        var result = MutationResult(0)
        perform(success, after = {
            AppDiagnostics.event("mutation.completed",mapOf("processed" to result.processed,"duplicate_machines" to result.duplicateMachines.size))
            duplicateSummary = result.duplicateMachines
            if (notify) testFeedback(result.duplicateMachines.isNotEmpty())
            after()
        }, recoveryLabel = recoveryLabel, queueIfBusy = queueIfBusy, finished = finished) { result = work(it) }
    }

    fun setVibration(enabled: Boolean) { vibrationEnabled = enabled; preferences.edit().putBoolean("feedback_vibration", enabled).apply() }
    fun setSound(enabled: Boolean) { soundEnabled = enabled; preferences.edit().putBoolean("feedback_sound", enabled).apply() }
    fun onLanguageChanged() {
        message = null; error = null; automaticBackupWarning = null
        checkDailyBackup()
    }
    fun testFeedback(duplicate: Boolean) = feedback.play(duplicate, vibrationEnabled, soundEnabled)

    private fun automaticStatus(force: Boolean = false): AutomaticBackupStatus {
        AppDiagnostics.event("backup.check",mapOf("force" to force,"enabled" to automaticBackupEnabled))
        val attempt = runCatching {
            if (force) automaticBackups.create(repository.snapshot())
            else if (automaticBackupEnabled) automaticBackups.ensureDaily { repository.snapshot() }
        }
        attempt.exceptionOrNull()?.let { AppDiagnostics.event("backup.failed",level="ERROR",failure=it) }
        return AutomaticBackupStatus(automaticBackups.entries(), attempt.exceptionOrNull()?.let { tr(TextKey.FULL_BACKUP_INCOMPLETE, it.message ?: tr(TextKey.CHECK_THE_PHONE_S_AVAILABLE_STORAGE)) })
    }

    private fun updateAutomaticStatus(status: AutomaticBackupStatus) {
        automaticBackupEntries = status.entries
        automaticBackupWarning = status.warning
    }

    fun setAutomaticBackup(enabled: Boolean) {
        automaticBackupEnabled = enabled
        preferences.edit().putBoolean("automatic_backup", enabled).apply()
        if (enabled) checkDailyBackup()
    }

    /** Checked on startup, returning to the app, and before mutations. No background service needed. */
    fun checkDailyBackup(force: Boolean = false) {
        if (busy) return
        launchOperation {
            val status = withContext(Dispatchers.IO) { automaticStatus(force) }
            updateAutomaticStatus(status)
            if (force && status.warning == null) message = tr(TextKey.FULL_BACKUP_SAVED_ON_THIS_PHONE_SAVE_A_COPY)
        }
    }

    fun previewAutomaticBackup(id: String) {
        if (busy) return
        launchOperation {
            try { restoreCandidate = withContext(Dispatchers.IO) { automaticBackups.read(id) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = tr(TextKey.COULD_NOT_READ_LOCAL_BACKUP, failure.message) }
        }
    }

    fun shareAutomaticBackup(id: String, ready: (Uri) -> Unit) {
        if (busy) return
        launchOperation {
                try {
                    val application = getApplication<Application>()
                    val uri = withContext(Dispatchers.IO) {
                        val folder = File(application.cacheDir, "shared-backups")
                        check(folder.isDirectory || folder.mkdirs()) { tr(TextKey.COULD_NOT_PREPARE_THE_FILE_FOR_SHARING) }
                        // The ID is validated by copyTo before any file is published.
                        require(automaticBackupEntries.any { it.id == id }) { tr(TextKey.THIS_BACKUP_HAS_BEEN_REMOVED_PLEASE_SELECT_ANOTHER) }
                        val file = File(folder, id)
                        if (!file.exists()) {
                            val temporary = File.createTempFile("sharing-", ".tmp", folder)
                            try {
                                temporary.outputStream().use { automaticBackups.copyTo(id, it) }
                                check(temporary.renameTo(file)) { tr(TextKey.COULD_NOT_PREPARE_THE_FILE_FOR_SHARING) }
                            } finally { temporary.delete() }
                        }
                        val expired = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                        folder.listFiles().orEmpty().filter { it.name != id && it.lastModified() < expired }.forEach { it.delete() }
                        FileProvider.getUriForFile(application, "${application.packageName}.files", file)
                    }
                    ready(uri)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = tr(TextKey.COULD_NOT_SHARE_BACKUP, failure.message) }
        }
    }

    fun undoRecovery(point: RecoveryPoint, after: () -> Unit = {}) { performMutation(tr(TextKey.UNDONE_2, point.label), after = after, notify = true) { it.undoRecovery(point.id) } }

    fun restoreVoided(id: String, mode: DuplicateMode = DuplicateMode.NEW_ENTRY) {
        val record = snapshot.records.find { it.id == id }
        performMutation(tr(TextKey.RESTORE_COMPLETE), recoveryLabel = tr(TextKey.RESTORE_VOIDED_RECORD, record?.let { warehouseLabel(it.warehouseId) + " / " + it.bom } ?: id), notify = true) { it.restoreVoided(id, mode) }
    }

    fun restoreBackup(candidate: InventorySnapshot, choices: BackupMergeChoices = BackupMergeChoices()) {
        AppDiagnostics.event("backup.merge.confirmed",mapOf("records" to candidate.records.size,"warehouses" to candidate.warehouses.size,
            "country_resolutions" to choices.countries.size,"warehouse_merges" to choices.warehouses.values.count { it.action.name == "MERGE" },
            "warehouse_renames" to choices.warehouses.values.count { it.action.name == "RENAME" },"parts_mode" to choices.mode.name))
        performMutation(tr(TextKey.BACKUP_MERGED), recoveryLabel = tr(TextKey.MERGE_JSON_BACKUP), notify = true, queueIfBusy = true) { repository ->
            repository.importBackup(candidate, choices)
        }
    }

    fun acknowledgeMigration() {
        perform("", after = { migrationVoidedCount = 0 }) { it.acknowledgeMigrationNotice() }
    }

    internal fun openDeveloperSession() {
        developerUnlocked = true
        diagnosticsStatus = ""
        AppDiagnostics.event("developer.unlocked")
    }

    internal fun closeDeveloperSession() {
        developerUnlocked = false
        AppDiagnostics.event("developer.closed")
    }

    /** The result handler outlives the console and waits for initialization after recreation. */
    fun exportDiagnostics(uri: Uri?) {
        if (uri == null) { AppDiagnostics.event("diagnostics.save.cancelled"); return }
        if (diagnosticsWorking) return
        diagnosticsWorking = true
        diagnosticsStatus = ""
        launchOperation(finished = { diagnosticsWorking = false }) {
            try {
                val application = getApplication<Application>()
                val current = snapshot
                withContext(Dispatchers.IO) {
                    application.contentResolver.openOutputStream(uri, "wt")?.use { AppDiagnostics.writeBundle(application, current, it) }
                        ?: error(tr(TextKey.COULD_NOT_OPEN_THE_SAVE_LOCATION_PLEASE_SELECT_ANOTHER))
                }
                diagnosticsStatus = tr(TextKey.DIAGNOSTIC_SAVED)
                message = diagnosticsStatus
                diagnosticsResultVersion++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppDiagnostics.event("diagnostics.failed", level = "ERROR", failure = failure)
                diagnosticsStatus = tr(TextKey.DIAGNOSTIC_FAILED, failure.message ?: failure.javaClass.simpleName)
                error = diagnosticsStatus
            }
        }
    }

    fun prepareExport(kind: String, currentWarehouse: Boolean, automaticId: String? = null, launchPicker: () -> Unit) {
        if (busy) return
        AppDiagnostics.event("export.prepare",mapOf("kind" to kind,"current_warehouse" to currentWarehouse))
        val current = PendingExport(kind, snapshot, null,
            if (kind != "backup" && currentWarehouse) warehouseId else null)
        launchOperation {
                try {
                    val request = withContext(Dispatchers.IO) {
                        val request = if (automaticId == null) current
                            else PendingExport("backup", automaticBackups.read(automaticId), null, null)
                        exportCache.outputStream().use { InventoryFiles.writeBackup(request.snapshot, it) }
                        // Persist before showing another Activity so process recreation can finish the export.
                        check(preferences.edit().putString("pending_kind", request.kind).putString("pending_task", request.taskId)
                            .putString("pending_warehouse", request.warehouseId).putString("pending_language", request.language.tag).commit()) { tr(TextKey.COULD_NOT_SAVE_THE_EXPORT_REQUEST) }
                        request
                    }
                    pendingExport = request
                    launchPicker()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { AppDiagnostics.event("export.prepare.failed",level="ERROR",failure=e); error = tr(TextKey.COULD_NOT_PREPARE_EXPORT, e.message) }
        }
    }

    fun export(uri: Uri?) {
        AppDiagnostics.event("export.picker.result",AppDiagnostics.uriInfo(uri)+mapOf("cancelled" to (uri==null)))
        if (uri == null) { clearPendingExport(); return }
        launchOperation {
            try {
                val request = pendingExport ?: withContext(Dispatchers.IO) {
                    val kind = preferences.getString("pending_kind", null) ?: error(tr(TextKey.THE_EXPORT_REQUEST_HAS_EXPIRED_PLEASE_EXPORT_AGAIN))
                    val cached = exportCache.inputStream().use(InventoryFiles::readBackup)
                    PendingExport(kind, cached, preferences.getString("pending_task", null), preferences.getString("pending_warehouse", null),
                        AppLanguage.fromTag(preferences.getString("pending_language", null)) ?: AppLanguage.current)
                }
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        when (request.kind) {
                            "template" -> InventoryImport.writeTemplate(output, request.language)
                            "xlsx" -> InventoryFiles.writeXlsx(request.snapshot, output, request.taskId, request.warehouseId, request.language)
                            "csv" -> InventoryFiles.writeCsvZip(request.snapshot, output, request.taskId, request.warehouseId, request.language)
                            else -> InventoryFiles.writeBackup(request.snapshot, output)
                        }
                    } ?: error(tr(TextKey.COULD_NOT_OPEN_THE_SAVE_LOCATION_PLEASE_SELECT_ANOTHER))
                }
                val at = System.currentTimeMillis()
                lastExportAt = at
                lastExportKind = request.kind
                lastExportWarehouse = request.warehouseId?.let(::warehouseLabel).orEmpty()
                preferences.edit().putString("last_export", lastExport).putLong("last_export_at", at)
                    .putString("last_export_kind", lastExportKind).putString("last_export_label", lastExportWarehouse).apply()
                if (request.kind == "backup") {
                    lastBackupAt = request.snapshot.records.maxOfOrNull { it.updatedAt } ?: at
                    preferences.edit().putLong("backup_at", lastBackupAt).apply()
                }
                message = tr(TextKey.FILE_SAVED_COPY_IT_TO_A_COMPUTER_OR_ANOTHER)
                AppDiagnostics.event("export.completed",mapOf("kind" to request.kind))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { AppDiagnostics.event("export.failed",level="ERROR",failure=e); error = tr(TextKey.FILE_COULD_NOT_BE_SAVED, e.message ?: tr(TextKey.PLEASE_SELECT_ANOTHER_LOCATION)) }
            finally { clearPendingExport() }
        }
    }

    private fun clearPendingExport() {
        pendingExport = null
        preferences.edit().remove("pending_kind").remove("pending_task").remove("pending_warehouse").remove("pending_language").apply()
        exportCache.delete()
    }

    fun previewRestore(uri: Uri?) = readSelectedDocument(uri, backupOnly = true)
    fun previewImport(uri: Uri?) = readSelectedDocument(uri, backupOnly = false)

    private fun readSelectedDocument(uri: Uri?, backupOnly: Boolean) {
        AppDiagnostics.event("import.picker.result",AppDiagnostics.uriInfo(uri)+mapOf("cancelled" to (uri==null),"busy" to busy,"backup_only" to backupOnly))
        if (uri == null) return
        val generation=++fileReadGeneration
        importReading=true
        importRows=null; importFileName=""; importFingerprint=""; restoreCandidate=null
        message=tr(TextKey.READING_IMPORT_FILE)
        if(busy) AppDiagnostics.event("import.queued",mapOf("request" to generation))
        launchOperation {
            if(generation!=fileReadGeneration) return@launchOperation
            val started=android.os.SystemClock.elapsedRealtime()
            var consumed=0L
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    var size:Long?=null
                    val name = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) null else {
                            val sizeIndex=cursor.getColumnIndex(OpenableColumns.SIZE)
                            if(sizeIndex>=0 && !cursor.isNull(sizeIndex)) size=cursor.getLong(sizeIndex)
                            val nameIndex=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if(nameIndex>=0)cursor.getString(nameIndex) else null
                        }
                    } }.getOrNull()?.filterNot(Char::isISOControl)?.take(200)?.ifBlank { null } ?: uri.lastPathSegment?.take(200) ?: tr(TextKey.CSV_FILE)
                    AppDiagnostics.event("import.file.open",AppDiagnostics.uriInfo(uri)+mapOf("request" to generation,"name" to name,"size" to size,"mime" to runCatching { resolver.getType(uri) }.getOrNull()))
                    val content = resolver.openInputStream(uri)?.use { source ->
                        val counted=object:java.io.FilterInputStream(source) {
                            override fun read():Int=super.read().also { if(it>=0)consumed++ }
                            override fun read(b:ByteArray,off:Int,len:Int):Int=super.read(b,off,len).also { if(it>0)consumed+=it }
                        }
                        if(backupOnly)InventoryImportContent.Backup(InventoryFiles.readBackup(counted)) else InventoryImport.readFile(counted,name)
                    } ?: error(tr(TextKey.COULD_NOT_OPEN_THE_IMPORT_FILE))
                    content to name
                }
                if(generation!=fileReadGeneration) return@launchOperation
                importRows = null; importFileName = ""; importFingerprint = ""; restoreCandidate = null
                when (val content = loaded.first) {
                    is InventoryImportContent.Rows -> {
                        importRows = content.rows; importFileName = loaded.second; importFingerprint = InventoryImport.fingerprint(content.rows)
                        message = if(content.rows.isEmpty())tr(TextKey.THE_FILE_CONTAINS_ONLY_A_HEADER_WITH_NO_DATA) else tr(TextKey.READ_ROWS_CHOOSE_A_TARGET_WAREHOUSE_AND_CONFIRM_IMPORT, content.rows.size)
                        AppDiagnostics.event("import.preview.ready",mapOf("request" to generation,"rows" to content.rows.size,"bytes" to consumed))
                    }
                    is InventoryImportContent.Backup -> {
                        message = null; restoreCandidate = content.snapshot
                        AppDiagnostics.event("import.backup.ready",mapOf("request" to generation,"records" to content.snapshot.records.size,"warehouses" to content.snapshot.warehouses.size,"bytes" to consumed))
                    }
                }
                importResultVersion++
            } catch(e:kotlinx.coroutines.CancellationException) { AppDiagnostics.event("import.cancelled",mapOf("request" to generation),"WARN");throw e }
            catch (e: Exception) {
                AppDiagnostics.event("import.file.failed",mapOf("request" to generation,"bytes" to consumed),"ERROR",e)
                if(generation==fileReadGeneration) {
                    importRows = null; importFileName = ""; importFingerprint = ""; restoreCandidate = null; message=null
                    error = tr(TextKey.COULD_NOT_READ_IMPORT_FILE, e.message)
                }
            } finally {
                AppDiagnostics.event("import.read.finished",mapOf("request" to generation,"duration_ms" to (android.os.SystemClock.elapsedRealtime()-started)))
                if(generation==fileReadGeneration) importReading=false
            }
        }
    }

    fun importDuplicateNotice(): String {
        val seen = snapshot.records.filter { !it.voided && it.kind == ItemKind.MACHINE && it.sn.isNotEmpty() }.mapTo(mutableSetOf()) { it.sn }
        val duplicates = importRows.orEmpty().filter { it.kind == ItemKind.MACHINE && it.sn.isNotEmpty() && !seen.add(it.sn) }
        return if (duplicates.isEmpty()) "" else tr(TextKey.THESE_DUPLICATE_MACHINES_WILL_BE_IMPORTED_AS_VOIDED_RECORDS, duplicates.size) +
            duplicates.groupingBy { "BOM ${it.bom} / SN ${it.sn}" }.eachCount().entries.joinToString("\n") { "${it.key} ×${it.value}" }
    }

    fun repeatImportNotice(warehouse: String): String {
        val matches = importReceipts.filter { it.fingerprint == importFingerprint }
        if (matches.isEmpty()) return ""
        val sameTarget = matches.count { it.warehouseId == warehouse }
        return tr(TextKey.THE_SAME_FILE_CONTENT_WAS_IMPORTED_TIMES_INCLUDING_TIMES, matches.size, sameTarget) +
            matches.take(5).joinToString("\n") { tr(TextKey.ROWS, formatHistoryTime(it.at), it.warehouseLabel, it.rows, it.fileName) }
    }

    fun importPreview(warehouse: String, mode: DuplicateMode) = ImportPreview.calculate(snapshot, importRows.orEmpty(), warehouse, mode)

    fun applyImport(warehouse: String, mode: DuplicateMode, allowRepeat: Boolean = false) {
        if (importCommitPending) {
            AppDiagnostics.event("import.confirmation.already_pending", level = "WARN")
            return
        }
        AppDiagnostics.event("import.confirmed",mapOf("rows" to importRows?.size,"mode" to mode.name,"allow_repeat" to allowRepeat))
        val rows = importRows ?: return
        if (rows.isEmpty()) { error = tr(TextKey.THE_FILE_CONTAINS_ONLY_A_HEADER_WITH_NO_DATA); return }
        val issue = importPreview(warehouse, mode).issue
        if (issue != null) { error = issue; return }
        if (!allowRepeat && repeatImportNotice(warehouse).isNotEmpty()) { error = tr(TextKey.THIS_FILE_CONTENT_WAS_IMPORTED_BEFORE_PREVIEW_IT_AND); return }
        val task = storageTaskId
        val metadata = ImportMetadata(importFingerprint.ifEmpty { InventoryImport.fingerprint(rows) }, importFileName.ifEmpty { tr(TextKey.CSV_FILE) })
        importCommitPending = true
        performMutation(tr(TextKey.IMPORT_COMPLETE), after = { importRows = null; importFileName = ""; importFingerprint = "" },
            recoveryLabel = tr(TextKey.IMPORT_2, metadata.fileName, warehouseLabel(warehouse)).take(512), notify = true, queueIfBusy = true,
            finished = { importCommitPending = false }) { repository ->
            require(allowRepeat || repository.importReceipts().none { it.fingerprint == metadata.fingerprint }) {
                tr(TextKey.THIS_FILE_CONTENT_WAS_IMPORTED_BEFORE_PREVIEW_IT_AND)
            }
            repository.importRecords(rows.map { row -> RecordInput(task, warehouse, row.kind, row.bom, row.sn, row.quantity, row.condition, row.note,
                sourceBom = "import", sourceSn = if (row.kind == ItemKind.MACHINE) "import" else "manual",
                pn = row.pn, sourcePn = if (row.pn.isNotEmpty()) "import" else "manual") }, mode, metadata)
        }
    }

    fun restoreDuplicateNotice(candidate: InventorySnapshot): String {
        val existingIds = snapshot.records.mapTo(mutableSetOf()) { it.id }
        val seen = snapshot.records.filter { !it.voided && it.kind == ItemKind.MACHINE && it.sn.isNotEmpty() }.mapTo(mutableSetOf()) { it.sn }
        val duplicates = candidate.records.filter { it.id !in existingIds && !it.voided && it.kind == ItemKind.MACHINE && it.sn.isNotEmpty() && !seen.add(it.sn) }
        return if (duplicates.isEmpty()) "" else tr(TextKey.THESE_NEW_MACHINES_HAVE_DUPLICATE_SNS_AND_WILL_BE, duplicates.size) +
            duplicates.groupingBy { "BOM ${it.bom} / SN ${it.sn}" }.eachCount().entries.joinToString("\n") { "${it.key} ×${it.value}" }
    }

    fun warehouseLabel(id: String): String {
        val warehouse = snapshot.warehouses.find { it.id == id } ?: return tr(TextKey.NO_WAREHOUSE_SELECTED)
        return "${snapshot.countries.find { it.id == warehouse.countryId }?.name.orEmpty()} / ${warehouse.name}"
    }

    override fun onCleared() {
        cleared = true
        lifetimeJob.cancel()
        feedback.close()
        if (lifetimeJob.isCompleted) repository.close()
        else repositoryCleanup.launch {
            lifetimeJob.join()
            runCatching { repository.close() }.onFailure { AppDiagnostics.event("repository.close.failed", level = "ERROR", failure = it) }
        }
        super.onCleared()
    }

    companion object {
        private val inventoryOperations = Mutex()
        private val repositoryCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun formatTime(at: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
        fun formatHistoryTime(at: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    }
}
