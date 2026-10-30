package com.fieldstock.app

import android.content.Intent
import android.content.ClipData
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import com.fieldstock.app.model.*
import com.fieldstock.app.scan.ScanActivity
import com.fieldstock.app.i18n.*
import com.fieldstock.app.diagnostics.AppDiagnostics
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

internal val Petrol = Color(0xFF123E52)
internal val Blue = Color(0xFF1463C7)
internal val Teal = Color(0xFF0B7B77)
internal val Mist = Color(0xFFF4F7FA)
internal val Ink = Color(0xFF193543)
internal val Amber = Color(0xFF995010)

class MainActivity : LocalizedActivity() {
    override fun onStart() {
        super.onStart()
        if (LanguageSettings.selected(this) != null) ViewModelProvider(this)[InventoryViewModel::class.java].checkDailyBackup()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = if (LanguageSettings.selected(this) == null)
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, secondary = Teal, surface = Color.White,
                background = Mist, onSurface = Ink, onBackground = Ink, surfaceVariant = Color(0xFFE5EDF3),
                secondaryContainer = Color(0xFFDDEEEB), onSecondaryContainer = Petrol,
                primaryContainer = Color(0xFFE5EFFC), onPrimaryContainer = Petrol)) {
                if (LanguageSettings.selected(this) == null) FirstLanguageChooser { language ->
                    LanguageSettings.select(this, language)
                    recreate()
                } else FieldStockApp()
            }
        }
    }
}

@Composable
private fun FieldStockApp(vm: InventoryViewModel = viewModel()) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    var countHeight by remember { mutableIntStateOf(0) }
    fun clearFocus() = clearInputFocus(focus) { keyboard?.hide() }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var entered by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var kind by rememberSaveable { mutableStateOf("PART") }
    var bom by rememberSaveable { mutableStateOf("") }
    var pn by rememberSaveable { mutableStateOf("") }
    var snFirst by rememberSaveable { mutableStateOf("") }
    var snLast by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var condition by rememberSaveable { mutableStateOf(StockCondition.NORMAL) }
    var note by rememberSaveable { mutableStateOf("") }
    var sourceBom by rememberSaveable { mutableStateOf("manual") }
    var sourceSn by rememberSaveable { mutableStateOf("manual") }
    var rawBom by rememberSaveable { mutableStateOf("") }
    var rawSn by rememberSaveable { mutableStateOf("") }
    var sourcePn by rememberSaveable { mutableStateOf("manual") }
    var rawPn by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var keepBom by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var scanField by rememberSaveable { mutableStateOf("BOM") }
    var switchWarehouse by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<RecordInput?>(null) }
    var pendingParts by remember { mutableStateOf(false) }
    var pendingDuplicateSave by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<StockRecord?>(null) }
    var saveWarning by remember { mutableStateOf("") }
    var confirmVoid by remember { mutableStateOf<StockRecord?>(null) }
    var confirmDelete by remember { mutableStateOf<StockRecord?>(null) }
    var clearRecords by remember { mutableStateOf<String?>(null) }
    var activeClearStep by remember { mutableIntStateOf(0) }
    var confirmClearInput by remember { mutableStateOf(false) }
    val sn = if (snFirst.isEmpty() && snLast.isEmpty()) "" else "$snFirst-$snLast"
    val dirty = bom.isNotBlank() || pn.isNotBlank() || snFirst.isNotBlank() || snLast.isNotBlank() || quantity.isNotBlank() || note.isNotBlank() || editingId != null

    fun clearForm(keep: Boolean = false) {
        if (!keep) { bom = ""; sourceBom = "manual"; rawBom = "" }
        snFirst = ""; snLast = ""; quantity = ""; note = ""; sourceSn = "manual"; rawSn = ""
        pn = ""; sourcePn = "manual"; rawPn = ""
        attempted = false; editingId = null; condition = StockCondition.NORMAL
    }
    fun editRecord(record: StockRecord) {
        kind = record.kind.name; bom = record.bom; pn = record.pn
        snFirst = record.sn.substringBefore('-', ""); snLast = record.sn.substringAfter('-', "")
        quantity = if (record.kind == ItemKind.PART) record.quantity.toString() else ""
        condition = record.condition; note = record.note
        sourceBom = record.sourceBom; sourceSn = record.sourceSn; rawBom = record.rawBom; rawSn = record.rawSn
        sourcePn = record.sourcePn; rawPn = record.rawPn
        editingId = record.id; attempted = false; keepBom = false; page = 0
    }
    fun submit(input: RecordInput, mode: DuplicateMode = DuplicateMode.NEW_ENTRY) {
        clearFocus()
        val updateId = editingId
        vm.duplicateOnRestore = false
        val after = { clearForm(keepBom && input.kind == ItemKind.MACHINE && updateId == null) }
        if (updateId == null) vm.performMutation(tr(TextKey.SAVED), after = after, notify = true) { it.saveIncoming(input, mode) }
        else vm.perform(tr(TextKey.CHANGES_SAVED), after = after, notify = true) { it.save(input, updateId) }
    }
    fun chooseWarehouse(id: String) {
        if (id != vm.warehouseId && dirty) switchWarehouse = id
        else { if (id != vm.warehouseId) { clearForm(); keepBom = false }; vm.select(id); entered = true; page = 0 }
    }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val value = result.data?.getStringExtra("value").orEmpty()
            val raw = result.data?.getStringExtra("raw").orEmpty()
            when (scanField) {
                "BOM" -> { bom = value; rawBom = raw; sourceBom = "ocr" }
                "PN" -> { pn = value; rawPn = raw; sourcePn = "ocr" }
                "NOTE" -> { note = value }
                "SN" -> { snFirst = value.substringBefore('-', ""); snLast = value.substringAfter('-', ""); rawSn = raw; sourceSn = "ocr" }
            }
        }
    }
    fun scan(field: String) {
        clearFocus(); scanField = field
        scanLauncher.launch(Intent(context, ScanActivity::class.java).putExtra("field", field))
    }
    val createXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), vm::export)
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), vm::export)
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), vm::export)
    val createTemplate = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), vm::export)
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::previewImport)
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::previewRestore)
    LaunchedEffect(page,entered) { AppDiagnostics.event("ui.page",mapOf("page" to if(entered)page else -1)) }
    LaunchedEffect(vm.error) { vm.error?.let { AppDiagnostics.event("ui.error.shown",mapOf("message" to it),"ERROR") } }
    fun restoreBackup() {
        if (dirty) vm.error = tr(TextKey.THERE_IS_UNSAVED_INPUT_SAVE_OR_CLEAR_IT_BEFORE)
        else { vm.duplicateOnRestore = false; restore.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
    }
    val backupActions = BackupActions(
        save = { entry ->
            vm.prepareExport("backup", false, automaticId = entry.id) {
                createBackup.launch(tr(TextKey.FIELDSTOCK_FULL_BACKUP_JSON, entry.day, entry.at))
            }
        },
        share = { entry -> vm.shareAutomaticBackup(entry.id) { uri ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(tr(TextKey.FIELDSTOCK_FULL_BACKUP), uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, tr(TextKey.SHARE_FULL_BACKUP)))
        } },
        restore = { entry ->
            if (dirty) vm.error = tr(TextKey.THERE_IS_UNSAVED_INPUT_SAVE_OR_CLEAR_IT_BEFORE)
            else { vm.duplicateOnRestore = false; vm.previewAutomaticBackup(entry.id) }
        },
    )
    LaunchedEffect(vm.message) { if (vm.message != null) { delay(3000); vm.message = null } }

    if (!entered || vm.snapshot.warehouses.none { it.id == vm.warehouseId }) {
        WarehouseChooser(vm, onEnter = ::chooseWarehouse, onRestore = ::restoreBackup, onUndone = { clearForm(); keepBom = false }, backups = backupActions)
    } else Scaffold(containerColor = Mist, topBar = { WarehouseHeader(vm.warehouseLabel(vm.warehouseId)) }, bottomBar = {
        if (!keyboardVisible) NavigationBar(containerColor = Color.White) {
            val icons = listOf(Icons.Default.Edit, Icons.Default.List, Icons.Default.Share, Icons.Default.Settings)
            listOf(tr(TextKey.COUNT), tr(TextKey.RECORDS_3), tr(TextKey.FILES), tr(TextKey.SETTINGS)).forEachIndexed { index, title ->
                NavigationBarItem(selected = page == index, onClick = { clearFocus(); page = index },
                    icon = { Icon(icons[index], contentDescription = title) }, label = { Text(title) }, enabled = !vm.busy)
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (page) {
                // Keep focusable fields out of a constraint subcomposition during IME resizing.
                0 -> Box(Modifier.fillMaxSize().onSizeChanged { countHeight = it.height }) {
                    val compact = with(density) { countHeight.toDp() } < 390.dp || keyboardVisible
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                      Column(Modifier.fillMaxWidth().weight(1f).then(if (kind == "MACHINE") Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier),
                          verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                        if (!keyboardVisible) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { ChoiceRow(listOf("PART" to tr(TextKey.PARTS), "MACHINE" to tr(TextKey.MACHINE)), kind, !vm.busy && editingId == null) {
                                kind = it; attempted = false
                            } }
                            TextButton(onClick = { keyboard?.hide(); moreOpen = true }, enabled = !vm.busy, modifier = Modifier.testTag("count-more")) { Text(tr(TextKey.MORE)) }
                        }
                        else Text(if (editingId != null) tr(TextKey.EDIT_2, if (kind == "PART") tr(TextKey.PARTS) else tr(TextKey.MACHINE)) else if (kind == "PART") tr(TextKey.PARTS) else tr(TextKey.MACHINE), fontSize = 13.sp, color = Petrol)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CodeField(if (kind == "MACHINE") tr(TextKey.OPTIONAL_IDENTIFIER, "BOM") else "BOM", bom, { bom = it; sourceBom = "manual"; rawBom = "" }, Modifier.weight(1f).testTag("count-bom"), !vm.busy,
                                attempted && !((kind == "MACHINE" || condition == StockCondition.PENDING) && bom.isBlank()) && IdentifierRules.validateBom(bom.trim()) != null)
                            ScanButton("BOM", !vm.busy) { scan("BOM") }
                        }
                        if (kind == "MACHINE") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CodeField(tr(TextKey.OPTIONAL_IDENTIFIER, "PN"), pn, { pn = it; sourcePn = "manual"; rawPn = "" }, Modifier.weight(1f).testTag("count-pn"), !vm.busy,
                                    attempted && pn.isNotBlank() && IdentifierRules.validatePn(pn.trim()) != null)
                                ScanButton("PN", !vm.busy) { scan("PN") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CodeField(tr(TextKey.SN_FIRST_PART), snFirst, { snFirst = it; sourceSn = "manual"; rawSn = "" }, Modifier.weight(1f), !vm.busy)
                                Text("-", color = Petrol, fontSize = 20.sp)
                                CodeField(tr(TextKey.SN_LAST_PART), snLast, { snLast = it; sourceSn = "manual"; rawSn = "" }, Modifier.weight(1f), !vm.busy)
                                ScanButton("SN", !vm.busy) { scan("SN") }
                            }
                            if (!compact) Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tr(TextKey.KEEP_BOM_FOR_THE_NEXT_MACHINE), Modifier.weight(1f), fontSize = 14.sp)
                                Switch(keepBom, { keepBom = it }, enabled = !vm.busy && editingId == null)
                            }
                        } else OutlinedTextField(quantity, { quantity = it }, label = { Text(tr(TextKey.QUANTITY_PCS)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            enabled = !vm.busy, isError = attempted && quantity.toIntOrNull()?.let { it in 1..1_000_000_000 } != true,
                            modifier = Modifier.fillMaxWidth())
                        if (!compact) Text("${if (editingId == null) conditionLabel(condition) else tr(TextKey.EDITING_RECORD, conditionLabel(condition))}${if (note.isNotEmpty()) tr(TextKey.HAS_NOTES) else ""}",
                            color = if (condition == StockCondition.NORMAL) Teal else Amber, fontSize = 12.sp)
                        if (kind == "PART") PartBomLookup(vm.snapshot.records, vm.warehouseId, bom, Modifier.weight(1f))
                      }
                      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val formError = if (!attempted) null else when {
                            !((kind == "MACHINE" || condition == StockCondition.PENDING) && bom.isBlank()) && IdentifierRules.validateBom(bom.trim()) != null -> IdentifierRules.validateBom(bom.trim())
                            kind == "MACHINE" && pn.isNotBlank() && IdentifierRules.validatePn(pn.trim()) != null -> IdentifierRules.validatePn(pn.trim())
                            kind == "MACHINE" && !(condition == StockCondition.PENDING && sn.isBlank()) -> IdentifierRules.validateSn(sn.trim())
                            kind == "PART" && quantity.toIntOrNull()?.let { it in 1..1_000_000_000 } != true -> tr(TextKey.QUANTITY_MUST_BE_A_POSITIVE_INTEGER)
                            else -> null
                        }
                        Text(formError ?: vm.message.orEmpty(), color = if (formError == null) Teal else MaterialTheme.colorScheme.error,
                            fontSize = 12.sp, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 18.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                attempted = true
                                val count = if (kind == "MACHINE") 1 else quantity.toIntOrNull()
                                val pending = condition == StockCondition.PENDING
                                val bomValid = ((kind == "MACHINE" || pending) && bom.isBlank()) || IdentifierRules.validateBom(bom.trim()) == null
                                val pnValid = kind != "MACHINE" || pn.isBlank() || IdentifierRules.validatePn(pn.trim()) == null
                                val snValid = kind != "MACHINE" || (pending && sn.isBlank()) || IdentifierRules.validateSn(sn.trim()) == null
                                if (pending && bom.isBlank() && (kind != "MACHINE" || (sn.isBlank() && pn.isBlank())) && note.isBlank()) vm.error = tr(TextKey.PENDING_RECORDS_REQUIRE_AT_LEAST_ONE_IDENTIFIER_OR_A)
                                else if (bomValid && pnValid && snValid && count != null && count in 1..1_000_000_000) {
                                    clearFocus()
                                    val previous = vm.snapshot.records.find { it.id == editingId }
                                    val input = RecordInput(previous?.taskId ?: vm.storageTaskId, vm.warehouseId, ItemKind.valueOf(kind), bom.trim(),
                                        if (kind == "MACHINE") sn.trim() else "", count, condition, note.trim(), location = previous?.location.orEmpty(),
                                        sourceBom = sourceBom, sourceSn = if (kind == "MACHINE") sourceSn else "manual", rawBom = rawBom, rawSn = if (kind == "MACHINE") rawSn else "",
                                        pn = if (kind == "MACHINE") pn.trim() else "", sourcePn = if (kind == "MACHINE") sourcePn else "manual", rawPn = if (kind == "MACHINE") rawPn else "")
                                    val active = vm.snapshot.records.filter { !it.voided && it.id != editingId }
                                    val parts = active.filter { input.bom.isNotEmpty() && it.kind == ItemKind.PART && it.warehouseId == vm.warehouseId && it.bom == input.bom && it.condition == input.condition }
                                    val closeSn = active.find { it.kind == ItemKind.MACHINE && it.sn != input.sn && it.sn.equals(input.sn, true) }
                                    if (editingId == null && kind == "PART" && parts.isNotEmpty()) {
                                        saveWarning = tr(TextKey.BOM_EXISTING_PCS_THIS_ENTRY_PCS_ADD_TO_THE, vm.warehouseLabel(vm.warehouseId), input.bom, conditionLabel(input.condition), parts.sumOf { it.quantity.toLong() }, input.quantity)
                                        pendingParts = true; pendingDuplicateSave = false
                                        pendingSave = input
                                    } else if (editingId == null && kind == "MACHINE" && active.any { it.kind == ItemKind.MACHINE && it.sn == input.sn && input.sn.isNotEmpty() }) {
                                        saveWarning = tr(TextKey.SN_AN_ACTIVE_RECORD_ALREADY_USES_THIS_SN_IF, input.sn)
                                        pendingParts = false; pendingDuplicateSave = true; pendingSave = input
                                    } else if (kind == "MACHINE" && closeSn != null) {
                                        saveWarning = tr(TextKey.EXISTING_SN_AND_NEW_SN_DIFFER_ONLY_IN_LETTER, closeSn.sn, input.sn)
                                        pendingParts = false; pendingDuplicateSave = false
                                        pendingSave = input
                                    } else submit(input)
                                }
                            }, enabled = !vm.busy, modifier = Modifier.weight(1f).height(48.dp)) { Text(if (editingId == null) tr(TextKey.SAVE_RECORD) else tr(TextKey.SAVE_CHANGES), fontSize = 17.sp) }
                            TextButton(onClick = { clearFocus(); confirmClearInput = true }, enabled = dirty && !vm.busy) { Text(if (editingId == null) tr(TextKey.CLEAR) else tr(TextKey.CANCEL)) }
                        }
                        if (!compact) {
                            val active = vm.snapshot.records.filter { !it.voided && it.warehouseId == vm.warehouseId }
                            Text(tr(TextKey.PARTS_PCS_MACHINES_PENDING, active.filter { it.kind == ItemKind.PART && it.condition != StockCondition.PENDING }.sumOf { it.quantity.toLong() }, active.count { it.kind == ItemKind.MACHINE && it.condition != StockCondition.PENDING }, active.count { it.condition == StockCondition.PENDING }), fontSize = 12.sp, color = Petrol, maxLines = 1)
                        }
                      }
                    }
                }
                1 -> RecordsPage(vm, onEdit = { if (dirty) vm.error = tr(TextKey.THERE_IS_UNSAVED_INPUT_SAVE_OR_CLEAR_IT_FIRST) else editRecord(it) },
                    onVoid = { confirmVoid = it }, onRestore = { record ->
                        if (record.kind == ItemKind.PART && record.bom.isNotEmpty() && vm.snapshot.records.any { !it.voided && it.kind == ItemKind.PART && it.warehouseId == record.warehouseId && it.bom == record.bom && it.condition == record.condition }) pendingRestore = record
                        else if (record.kind == ItemKind.MACHINE && record.sn.isNotEmpty() && vm.snapshot.records.any { !it.voided && it.kind == ItemKind.MACHINE && it.sn == record.sn }) pendingRestore = record
                        else vm.restoreVoided(record.id)
                    }, onDelete = { confirmDelete = it },
                    onClear = { clearRecords = it; activeClearStep = 0 })
                2 -> ProcessingPage(vm, onExport = { type ->
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    vm.prepareExport(type, type != "backup") {
                        when (type) { "xlsx" -> createXlsx.launch(tr(TextKey.FIELDSTOCK_XLSX, stamp)); "csv" -> createCsv.launch(tr(TextKey.FIELDSTOCK_CSV_ZIP, stamp)); else -> createBackup.launch(tr(TextKey.FIELDSTOCK_FULL_BACKUP_JSON_2, stamp)) }
                    }
                }, onImport = {
                    clearFocus(); AppDiagnostics.event("import.picker.open")
                    try { importFile.launch(arrayOf("*/*")) }
                    catch(e:Exception){ AppDiagnostics.event("import.picker.failed",level="ERROR",failure=e);vm.error=tr(TextKey.COULD_NOT_READ_IMPORT_FILE,e.message) }
                },
                    onTemplate = { vm.prepareExport("template", false) { createTemplate.launch(tr(TextKey.FIELDSTOCK_IMPORT_TEMPLATE_CSV)) } }, backups = backupActions)
                else -> SettingsPage(vm, onChoose = ::chooseWarehouse, onRestore = ::restoreBackup, onStructureChanged = { clearForm(); keepBom = false }, backups = backupActions)
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (moreOpen) AlertDialog(onDismissRequest = { moreOpen = false }, title = { Text(tr(TextKey.CONDITION_AND_NOTES)) }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceRow(listOf(StockCondition.NORMAL, StockCondition.DAMAGED, StockCondition.PENDING).map {
                it to if (it == StockCondition.PENDING) tr(TextKey.PENDING_FILTER) else conditionLabel(it)
            }, condition, !vm.busy) { condition = it }
            if (condition == StockCondition.PENDING) Text(tr(TextKey.MISSING_IDENTIFIERS_MAY_BE_LEFT_BLANK_ENTER_AT_LEAST), fontSize = 13.sp)
            OutlinedTextField(note, { note = it }, label = { Text(tr(TextKey.NOTES_OPTIONAL)) }, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("count-note"))
            OutlinedButton(onClick = { scan("NOTE") }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text(tr(TextKey.SCAN, tr(TextKey.NOTE)))
            }
        }
    }, confirmButton = { TextButton(onClick = { keyboard?.hide(); moreOpen = false }) { Text(tr(TextKey.DONE)) } })
    switchWarehouse?.let { next -> ConfirmDialog(tr(TextKey.SWITCH_WAREHOUSE), tr(TextKey.SWITCHING_WILL_CLEAR_THE_UNSAVED_INPUT), tr(TextKey.DISCARD_INPUT_AND_SWITCH), { switchWarehouse = null }) {
        clearForm(); keepBom = false; vm.select(next); entered = true; page = 0; switchWarehouse = null
    } }
    if (confirmClearInput) ConfirmDialog(tr(TextKey.CLEAR_UNSAVED_INPUT), tr(TextKey.SAVED_RECORDS_ARE_UNAFFECTED), tr(TextKey.CLEAR_INPUT), { confirmClearInput = false }) { clearForm(); keepBom = false; confirmClearInput = false }
    pendingSave?.let { input ->
        if (pendingParts) DuplicateModeDialog(tr(TextKey.HOW_SHOULD_MATCHING_PARTS_BE_SAVED), saveWarning, { pendingSave = null }) { mode -> pendingSave = null; submit(input, mode) }
        else ConfirmDialog(if (pendingDuplicateSave) tr(TextKey.DUPLICATE_SN_FOUND) else tr(TextKey.REVIEW_BEFORE_SAVING), saveWarning,
            if (pendingDuplicateSave) tr(TextKey.CONFIRM_SAVE_AS_VOIDED) else tr(TextKey.CONFIRM_SAVE), { pendingSave = null }) { pendingSave = null; submit(input) }
    }
    pendingRestore?.let { record ->
        if (record.kind == ItemKind.PART) DuplicateModeDialog(tr(TextKey.HOW_SHOULD_MATCHING_PARTS_BE_RESTORED), tr(TextKey.BOM_QUANTITY_TO_RESTORE_PCS, vm.warehouseLabel(record.warehouseId), record.bom, conditionLabel(record.condition), record.quantity), { pendingRestore = null }) { mode ->
            pendingRestore = null; vm.restoreVoided(record.id, mode)
        } else ConfirmDialog(tr(TextKey.SN_ALREADY_EXISTS_KEEP_VOIDED), tr(TextKey.SN_AN_ACTIVE_MACHINE_ALREADY_USES_THIS_SN_THIS, record.sn), tr(TextKey.CONFIRM_KEEP_VOIDED), { pendingRestore = null }) {
            pendingRestore = null; vm.restoreVoided(record.id)
        }
    }
    confirmVoid?.let { record -> ConfirmDialog(tr(TextKey.VOID_THIS_RECORD), tr(TextKey.VOIDED_RECORDS_ARE_EXCLUDED_FROM_TOTALS_AND_CAN_BE_2, record.bom, record.sn), tr(TextKey.CONFIRM_VOID), { confirmVoid = null }) {
        confirmVoid = null; vm.perform(tr(TextKey.RECORD_VOIDED)) { it.voidRecord(record.id) }
    } }
    confirmDelete?.let { record -> ConfirmDialog(tr(TextKey.DELETE_THIS_VOIDED_RECORD), tr(TextKey.THE_RECORD_WILL_BE_REMOVED_FROM_THE_VOIDED_TAB, record.bom, record.sn), tr(TextKey.CONFIRM_DELETE), { confirmDelete = null }) {
        confirmDelete = null; vm.perform(tr(TextKey.VOIDED_RECORD_DELETED), recoveryLabel = tr(TextKey.DELETE_VOIDED_RECORD, vm.warehouseLabel(record.warehouseId), record.bom)) { it.deleteVoided(record.id) }
    } }
    clearRecords?.let { category ->
        val isVoid = category == "voided"
        val count = vm.snapshot.records.count { it.warehouseId == vm.warehouseId && it.voided == isVoid }
        val second = !isVoid && activeClearStep == 1
        ConfirmDialog(if (isVoid) tr(TextKey.CLEAR_THIS_WAREHOUSE_S_VOIDED_RECORDS) else if (second) tr(TextKey.CONFIRM_CLEARING_ACTIVE_RECORDS_AGAIN) else tr(TextKey.CLEAR_THIS_WAREHOUSE_S_ACTIVE_RECORDS),
            tr(TextKey.DELETE_ALL_RECORDS_FROM_THIS_WAREHOUSE_REGARDLESS_OF_SEARCH, vm.warehouseLabel(vm.warehouseId), count, if (isVoid) tr(TextKey.VOIDED) else tr(TextKey.ACTIVE_2), if (!isVoid) tr(TextKey.VOIDED_RECORDS_AND_OTHER_WAREHOUSES_ARE_UNAFFECTED) else tr(TextKey.ACTIVE_RECORDS_AND_OTHER_WAREHOUSES_ARE_UNAFFECTED)),
            if (isVoid) tr(TextKey.CONFIRM_CLEAR) else if (second) tr(TextKey.CONFIRM_CLEAR_ACTIVE) else tr(TextKey.CONTINUE_CLEARING), { clearRecords = null; activeClearStep = 0 }) {
            if (!isVoid && !second) activeClearStep = 1
            else {
                val warehouse = vm.warehouseId; clearRecords = null; activeClearStep = 0
                if (isVoid) vm.perform(tr(TextKey.THIS_WAREHOUSE_S_VOIDED_RECORDS_CLEARED), recoveryLabel = tr(TextKey.CLEAR_VOIDED, vm.warehouseLabel(warehouse))) { it.clearVoided(warehouse) }
                else vm.perform(tr(TextKey.THIS_WAREHOUSE_S_ACTIVE_RECORDS_CLEARED), recoveryLabel = tr(TextKey.CLEAR_ACTIVE, vm.warehouseLabel(warehouse))) { it.clearActive(warehouse) }
            }
        }
    }
    vm.duplicate?.let { record -> AlertDialog(onDismissRequest = { vm.duplicate = null }, title = { Text(if (vm.duplicateOnRestore) tr(TextKey.SN_ALREADY_EXISTS_NOT_RESTORED) else tr(TextKey.SN_ALREADY_RECORDED)) },
        text = { Text("${record.sn}\n${vm.warehouseLabel(record.warehouseId)}\nBOM：${record.bom}\n${if (vm.duplicateOnRestore) tr(TextKey.SNS_MUST_BE_UNIQUE_THIS_RECORD_REMAINS_VOIDED) else tr(TextKey.NO_NEW_RECORD_WAS_ADDED_CHECK_FOR_A_DUPLICATE)}") },
        confirmButton = { TextButton(onClick = { vm.duplicate = null }) { Text(if (vm.duplicateOnRestore) tr(TextKey.KEEP_VOIDED) else tr(TextKey.BACK_TO_REVIEW)) } }) }
    if (vm.duplicateSummary.isNotEmpty()) AlertDialog(onDismissRequest = { vm.duplicateSummary = emptyList() }, title = { Text(tr(TextKey.DUPLICATE_SNS_SAVED_AS_VOIDED)) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text(tr(TextKey.SAVED_DUPLICATE_MACHINE_RECORDS_ON_THE_VOIDED_TAB_EXISTING, vm.duplicateSummary.size))
            vm.duplicateSummary.forEach { Text("BOM：${it.bom}\nSN：${it.sn}\n${vm.warehouseLabel(it.warehouseId)}", modifier = Modifier.padding(top = 8.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
        } }, confirmButton = { TextButton(onClick = { vm.duplicateSummary = emptyList() }) { Text(tr(TextKey.OK)) } })
    vm.error?.let { message -> AlertDialog(onDismissRequest = { vm.error = null }, title = { Text(tr(TextKey.OPERATION_INCOMPLETE)) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.error = null }) { Text(tr(TextKey.OK)) } }) }
    vm.restoreCandidate?.let { candidate -> BackupMergeDialog(vm, candidate) }
    if (vm.migrationVoidedCount > 0) AlertDialog(onDismissRequest = {}, title = { Text(tr(TextKey.OLDER_RECORDS_UPDATED_FOR_UNIQUE_SNS)) }, text = {
        Text(tr(TextKey.FOUND_DUPLICATE_SN_RECORDS_ACROSS_OLDER_TASKS_THE_MOST, vm.migrationVoidedCount))
    }, confirmButton = { TextButton(onClick = vm::acknowledgeMigration, enabled = !vm.busy) { Text(tr(TextKey.OK)) } })
}

@Composable
internal fun WarehouseHeader(label: String) {
    Box(Modifier.fillMaxWidth().background(Petrol).statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ChoiceRow(options: List<Pair<String, String>>, selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, label) ->
            if (id == selected) Button(onClick = { onSelect(id) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, maxLines = 2, textAlign = TextAlign.Center) }
            else OutlinedButton(onClick = { onSelect(id) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, maxLines = 2, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun CodeField(label: String, value: String, change: (String) -> Unit, modifier: Modifier, enabled: Boolean, error: Boolean = false) {
    OutlinedTextField(value, change, label = { Text(label, fontSize = 12.sp, maxLines = 1) }, singleLine = true, enabled = enabled, isError = error,
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 17.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next), modifier = modifier)
}

@Composable
private fun ScanButton(field: String, enabled: Boolean, scan: () -> Unit) {
    FilledTonalButton(onClick = scan, enabled = enabled, modifier = Modifier.height(52.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(tr(TextKey.SCAN, field), fontSize = 12.sp) }
}

@Composable
internal fun ConfirmDialog(title: String, message: String, confirm: String, dismiss: () -> Unit, action: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message, Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) },
        confirmButton = { TextButton(onClick = action) { Text(confirm) } }, dismissButton = { TextButton(onClick = dismiss) { Text(tr(TextKey.CANCEL)) } })
}

@Composable
internal fun DuplicateModeDialog(title: String, message: String, dismiss: () -> Unit, choose: (DuplicateMode) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { choose(DuplicateMode.ADD_QUANTITY) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(TextKey.ADD_TO_EXISTING_QUANTITY)) }
            OutlinedButton(onClick = { choose(DuplicateMode.NEW_ENTRY) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(TextKey.CREATE_NEW_ENTRY)) }
            TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text(tr(TextKey.CANCEL)) }
        } })
}
