package com.fieldstock.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fieldstock.app.model.*
import com.fieldstock.app.scan.ScanActivity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

internal val Petrol = Color(0xFF123E52)
internal val Blue = Color(0xFF1463C7)
internal val Teal = Color(0xFF0B7B77)
internal val Mist = Color(0xFFF4F7FA)
internal val Ink = Color(0xFF193543)
internal val Amber = Color(0xFF995010)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, secondary = Teal, surface = Color.White,
                background = Mist, onSurface = Ink, onBackground = Ink, surfaceVariant = Color(0xFFE5EDF3),
                secondaryContainer = Color(0xFFDDEEEB), onSecondaryContainer = Petrol,
                primaryContainer = Color(0xFFE5EFFC), onPrimaryContainer = Petrol)) { FieldStockApp() }
        }
    }
}

@Composable
private fun FieldStockApp(vm: InventoryViewModel = viewModel()) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var entered by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var kind by rememberSaveable { mutableStateOf("PART") }
    var bom by rememberSaveable { mutableStateOf("") }
    var snFirst by rememberSaveable { mutableStateOf("") }
    var snLast by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var condition by rememberSaveable { mutableStateOf("正常") }
    var note by rememberSaveable { mutableStateOf("") }
    var sourceBom by rememberSaveable { mutableStateOf("manual") }
    var sourceSn by rememberSaveable { mutableStateOf("manual") }
    var rawBom by rememberSaveable { mutableStateOf("") }
    var rawSn by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var keepBom by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var scanField by rememberSaveable { mutableStateOf("BOM") }
    var switchWarehouse by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<RecordInput?>(null) }
    var saveWarning by remember { mutableStateOf("") }
    var confirmVoid by remember { mutableStateOf<StockRecord?>(null) }
    var confirmDelete by remember { mutableStateOf<StockRecord?>(null) }
    var clearRecords by remember { mutableStateOf<String?>(null) }
    var activeClearStep by remember { mutableIntStateOf(0) }
    var confirmClearInput by remember { mutableStateOf(false) }
    val sn = if (snFirst.isEmpty() && snLast.isEmpty()) "" else "$snFirst-$snLast"
    val dirty = bom.isNotBlank() || snFirst.isNotBlank() || snLast.isNotBlank() || quantity.isNotBlank() || note.isNotBlank() || editingId != null

    fun clearForm(keep: Boolean = false) {
        if (!keep) { bom = ""; sourceBom = "manual"; rawBom = "" }
        snFirst = ""; snLast = ""; quantity = ""; note = ""; sourceSn = "manual"; rawSn = ""
        attempted = false; editingId = null; condition = "正常"
    }
    fun editRecord(record: StockRecord) {
        kind = record.kind.name; bom = record.bom
        snFirst = record.sn.substringBefore('-', ""); snLast = record.sn.substringAfter('-', "")
        quantity = if (record.kind == ItemKind.PART) record.quantity.toString() else ""
        condition = record.condition; note = record.note
        sourceBom = record.sourceBom; sourceSn = record.sourceSn; rawBom = record.rawBom; rawSn = record.rawSn
        editingId = record.id; attempted = false; keepBom = false; page = 0
    }
    fun submit(input: RecordInput) {
        focus.clearFocus()
        val updateId = editingId
        vm.duplicateOnRestore = false
        vm.perform(if (updateId == null) "已保存" else "已保存修改", after = {
            clearForm(keepBom && input.kind == ItemKind.MACHINE && updateId == null)
        }) { it.save(input, updateId) }
    }
    fun chooseWarehouse(id: String) {
        if (id != vm.warehouseId && dirty) switchWarehouse = id
        else { if (id != vm.warehouseId) { clearForm(); keepBom = false }; vm.select(id); entered = true; page = 0 }
    }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val value = result.data?.getStringExtra("value").orEmpty()
            val raw = result.data?.getStringExtra("raw").orEmpty()
            if (scanField == "BOM") { bom = value; rawBom = raw; sourceBom = "ocr" }
            else { snFirst = value.substringBefore('-', ""); snLast = value.substringAfter('-', ""); rawSn = raw; sourceSn = "ocr" }
        }
    }
    fun scan(field: String) {
        focus.clearFocus(); scanField = field
        scanLauncher.launch(Intent(context, ScanActivity::class.java).putExtra("field", field))
    }
    val createXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), vm::export)
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), vm::export)
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), vm::export)
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::previewRestore)
    fun restoreBackup() {
        if (dirty) vm.error = "还有未保存输入，请先保存或清空后再恢复备份"
        else { vm.duplicateOnRestore = false; restore.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
    }
    LaunchedEffect(vm.message) { if (vm.message != null) { delay(3000); vm.message = null } }

    if (!entered || vm.snapshot.warehouses.none { it.id == vm.warehouseId }) {
        WarehouseChooser(vm, onEnter = ::chooseWarehouse, onRestore = ::restoreBackup)
    } else Scaffold(containerColor = Mist, topBar = { WarehouseHeader(vm.warehouseLabel(vm.warehouseId)) }, bottomBar = {
        if (!keyboardVisible) NavigationBar(containerColor = Color.White) {
            val icons = listOf(Icons.Default.Edit, Icons.Default.List, Icons.Default.Share, Icons.Default.Settings)
            listOf("盘点", "记录", "导出", "设置").forEachIndexed { index, title ->
                NavigationBarItem(selected = page == index, onClick = { focus.clearFocus(); page = index },
                    icon = { Icon(icons[index], contentDescription = title) }, label = { Text(title) }, enabled = !vm.busy)
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (page) {
                0 -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val compact = maxHeight < 390.dp || keyboardVisible
                    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                      Column(Modifier.fillMaxWidth().align(Alignment.TopCenter), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                        if (!keyboardVisible) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { ChoiceRow(listOf("PART" to "配件", "MACHINE" to "整机"), kind, !vm.busy && editingId == null) {
                                kind = it; attempted = false
                            } }
                            TextButton(onClick = { focus.clearFocus(); moreOpen = true }, enabled = !vm.busy) { Text("更多") }
                        }
                        else Text(if (editingId != null) "修改${if (kind == "PART") "配件" else "整机"}" else if (kind == "PART") "配件" else "整机", fontSize = 13.sp, color = Petrol)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CodeField("BOM", bom, { bom = it; sourceBom = "manual"; rawBom = "" }, Modifier.weight(1f), !vm.busy,
                                attempted && !(condition == "待核查" && bom.isBlank()) && IdentifierRules.validateBom(bom.trim()) != null)
                            ScanButton("BOM", !vm.busy) { scan("BOM") }
                        }
                        if (kind == "MACHINE") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CodeField("SN 前段", snFirst, { snFirst = it; sourceSn = "manual"; rawSn = "" }, Modifier.weight(1f), !vm.busy)
                                Text("-", color = Petrol, fontSize = 20.sp)
                                CodeField("SN 后段", snLast, { snLast = it; sourceSn = "manual"; rawSn = "" }, Modifier.weight(1f), !vm.busy)
                                ScanButton("SN", !vm.busy) { scan("SN") }
                            }
                            if (!compact) Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("连续录入时保留 BOM", Modifier.weight(1f), fontSize = 14.sp)
                                Switch(keepBom, { keepBom = it }, enabled = !vm.busy && editingId == null)
                            }
                        } else OutlinedTextField(quantity, { quantity = it }, label = { Text("数量（件）") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            enabled = !vm.busy, isError = attempted && quantity.toIntOrNull()?.let { it in 1..1_000_000_000 } != true,
                            modifier = Modifier.fillMaxWidth())
                        if (!compact) Text("${if (editingId == null) condition else "修改记录 · $condition"}${if (note.isNotEmpty()) " · 有备注" else ""}",
                            color = if (condition == "正常") Teal else Amber, fontSize = 12.sp)
                      }
                      Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val formError = if (!attempted) null else when {
                            !(condition == "待核查" && bom.isBlank()) && IdentifierRules.validateBom(bom.trim()) != null -> IdentifierRules.validateBom(bom.trim())
                            kind == "MACHINE" && !(condition == "待核查" && sn.isBlank()) -> IdentifierRules.validateSn(sn.trim())
                            kind == "PART" && quantity.toIntOrNull()?.let { it in 1..1_000_000_000 } != true -> "数量须为正整数"
                            else -> null
                        }
                        Text(formError ?: vm.message.orEmpty(), color = if (formError == null) Teal else MaterialTheme.colorScheme.error,
                            fontSize = 12.sp, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 18.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                attempted = true
                                val count = if (kind == "MACHINE") 1 else quantity.toIntOrNull()
                                val pending = condition == "待核查"
                                val bomValid = (pending && bom.isBlank()) || IdentifierRules.validateBom(bom.trim()) == null
                                val snValid = kind != "MACHINE" || (pending && sn.isBlank()) || IdentifierRules.validateSn(sn.trim()) == null
                                if (pending && bom.isBlank() && sn.isBlank() && note.isBlank()) vm.error = "待核查记录至少填写一个编号或备注"
                                else if (bomValid && snValid && count != null && count in 1..1_000_000_000) {
                                    val previous = vm.snapshot.records.find { it.id == editingId }
                                    val input = RecordInput(previous?.taskId ?: vm.storageTaskId, vm.warehouseId, ItemKind.valueOf(kind), bom.trim(),
                                        if (kind == "MACHINE") sn.trim() else "", count, condition, note.trim(), location = previous?.location.orEmpty(),
                                        sourceBom = sourceBom, sourceSn = if (kind == "MACHINE") sourceSn else "manual", rawBom = rawBom, rawSn = if (kind == "MACHINE") rawSn else "")
                                    val active = vm.snapshot.records.filter { !it.voided && it.id != editingId }
                                    val parts = active.filter { it.kind == ItemKind.PART && it.warehouseId == vm.warehouseId && it.bom == input.bom && it.condition == input.condition }
                                    val closeSn = active.find { it.kind == ItemKind.MACHINE && it.sn != input.sn && it.sn.equals(input.sn, true) }
                                    if (editingId == null && kind == "PART" && parts.isNotEmpty()) {
                                        saveWarning = "此 BOM 和状态已有 ${parts.sumOf { it.quantity.toLong() }} 件，本次再追加 ${input.quantity} 件。若要修正数量，请取消并修改原记录。"
                                        pendingSave = input
                                    } else if (kind == "MACHINE" && closeSn != null) {
                                        saveWarning = "已有 ${closeSn.sn} 与本次 ${input.sn} 仅大小写不同，请核对是否为不同编号。"
                                        pendingSave = input
                                    } else submit(input)
                                }
                            }, enabled = !vm.busy, modifier = Modifier.weight(1f).height(48.dp)) { Text(if (editingId == null) "保存这一条" else "保存修改", fontSize = 17.sp) }
                            TextButton(onClick = { focus.clearFocus(); confirmClearInput = true }, enabled = dirty && !vm.busy) { Text(if (editingId == null) "清空" else "取消") }
                        }
                        if (!compact) {
                            val active = vm.snapshot.records.filter { !it.voided && it.warehouseId == vm.warehouseId }
                            Text("配件 ${active.filter { it.kind == ItemKind.PART && it.condition != "待核查" }.sumOf { it.quantity.toLong() }} 件    整机 ${active.count { it.kind == ItemKind.MACHINE && it.condition != "待核查" }} 台    待核查 ${active.count { it.condition == "待核查" }} 条", fontSize = 12.sp, color = Petrol, maxLines = 1)
                        }
                      }
                    }
                }
                1 -> RecordsPage(vm, onEdit = { if (dirty) vm.error = "还有未保存输入，请先保存或清空" else editRecord(it) },
                    onVoid = { confirmVoid = it }, onRestore = { vm.restoreVoided(it.id) }, onDelete = { confirmDelete = it },
                    onClear = { clearRecords = it; activeClearStep = 0 })
                2 -> ExportPage(vm) { type ->
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    vm.prepareExport(type, type != "backup") {
                        when (type) { "xlsx" -> createXlsx.launch("盘库_${stamp}.xlsx"); "csv" -> createCsv.launch("盘库CSV_${stamp}.zip"); else -> createBackup.launch("盘库完整备份_${stamp}.json") }
                    }
                }
                else -> SettingsPage(vm, onChoose = ::chooseWarehouse, onRestore = ::restoreBackup)
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (moreOpen) AlertDialog(onDismissRequest = { moreOpen = false }, title = { Text("状态和备注") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceRow(listOf("正常" to "正常", "损坏" to "损坏", "待核查" to "待核查"), condition, !vm.busy) { condition = it }
            if (condition == "待核查") Text("缺失编号可留空，至少填写一个编号或备注。待核查不计入正式数量。", fontSize = 13.sp)
            OutlinedTextField(note, { note = it }, label = { Text("备注（选填）") }, maxLines = 3)
        }
    }, confirmButton = { TextButton(onClick = { focus.clearFocus(); moreOpen = false }) { Text("完成") } })
    switchWarehouse?.let { next -> ConfirmDialog("切换仓库？", "还有未保存输入，切换会清空这些输入。", "放弃输入并切换", { switchWarehouse = null }) {
        clearForm(); keepBom = false; vm.select(next); entered = true; page = 0; switchWarehouse = null
    } }
    if (confirmClearInput) ConfirmDialog("清空未保存输入？", "已保存的记录不受影响。", "清空输入", { confirmClearInput = false }) { clearForm(); keepBom = false; confirmClearInput = false }
    pendingSave?.let { input -> ConfirmDialog("核对后保存", saveWarning, "确认保存", { pendingSave = null }) { pendingSave = null; submit(input) } }
    confirmVoid?.let { record -> ConfirmDialog("作废这条记录？", "${record.bom}\n${record.sn}\n作废后不再计数，可以在作废页还原。", "确认作废", { confirmVoid = null }) {
        confirmVoid = null; vm.perform("记录已作废") { it.voidRecord(record.id) }
    } }
    confirmDelete?.let { record -> ConfirmDialog("永久删除这条作废记录？", "${record.bom}\n${record.sn}\n删除后不能在作废页还原。", "确认删除", { confirmDelete = null }) {
        confirmDelete = null; vm.perform("作废记录已删除") { it.deleteVoided(record.id) }
    } }
    clearRecords?.let { category ->
        val isVoid = category == "voided"
        val count = vm.snapshot.records.count { it.warehouseId == vm.warehouseId && it.voided == isVoid }
        val second = !isVoid && activeClearStep == 1
        ConfirmDialog(if (isVoid) "清除本仓作废记录？" else if (second) "再次确认清空正常记录" else "清空本仓正常记录？",
            "${vm.warehouseLabel(vm.warehouseId)}\n共 $count 条${if (isVoid) "作废" else "非作废"}记录，将永久删除，不受搜索和筛选影响。${if (!isVoid) "\n作废记录和其他仓库不受影响。" else "\n正常记录和其他仓库不受影响。"}",
            if (isVoid) "确认清除" else if (second) "确认清空正常" else "继续清空", { clearRecords = null; activeClearStep = 0 }) {
            if (!isVoid && !second) activeClearStep = 1
            else {
                val warehouse = vm.warehouseId; clearRecords = null; activeClearStep = 0
                if (isVoid) vm.perform("本仓作废记录已清除") { it.clearVoided(warehouse) }
                else vm.perform("本仓正常记录已清空") { it.clearActive(warehouse) }
            }
        }
    }
    vm.duplicate?.let { record -> AlertDialog(onDismissRequest = { vm.duplicate = null }, title = { Text(if (vm.duplicateOnRestore) "SN 已存在，未还原" else "SN 已经录入") },
        text = { Text("${record.sn}\n${vm.warehouseLabel(record.warehouseId)}\nBOM：${record.bom}\n${if (vm.duplicateOnRestore) "SN 必须唯一，这条记录仍保留在作废页。" else "本次没有新增记录，请核查是否重复盘点。"}") },
        confirmButton = { TextButton(onClick = { vm.duplicate = null }) { Text(if (vm.duplicateOnRestore) "保留作废" else "返回核对") } }) }
    vm.error?.let { message -> AlertDialog(onDismissRequest = { vm.error = null }, title = { Text("操作未完成") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.error = null }) { Text("知道了") } }) }
    vm.restoreCandidate?.let { candidate -> ConfirmDialog("合并这个备份？", "包含 ${candidate.warehouses.size} 个仓库、${candidate.records.size} 条记录。相同记录不重复增加，冲突时整次取消。", "检查并合并", { vm.restoreCandidate = null }) {
        vm.restoreCandidate = null; vm.duplicateOnRestore = false
        vm.perform("备份已合并") { it.restore(candidate) }
    } }
    if (vm.migrationVoidedCount > 0) AlertDialog(onDismissRequest = {}, title = { Text("旧记录已按 SN 唯一规则整理") }, text = {
        Text("旧任务合并后有 ${vm.migrationVoidedCount} 条重复 SN 记录。每个 SN 保留最近修改的一条，其他移入作废页，原记录和修改历史均保留。")
    }, confirmButton = { TextButton(onClick = vm::acknowledgeMigration, enabled = !vm.busy) { Text("知道了") } })
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
            if (id == selected) Button(onClick = { onSelect(id) }, enabled = enabled, modifier = Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, maxLines = 1) }
            else OutlinedButton(onClick = { onSelect(id) }, enabled = enabled, modifier = Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, maxLines = 1) }
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
    FilledTonalButton(onClick = scan, enabled = enabled, modifier = Modifier.height(52.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("扫描 $field", fontSize = 12.sp) }
}

@Composable
internal fun ConfirmDialog(title: String, message: String, confirm: String, dismiss: () -> Unit, action: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = action) { Text(confirm) } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
