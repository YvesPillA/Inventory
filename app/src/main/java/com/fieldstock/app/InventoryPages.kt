package com.fieldstock.app

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldstock.app.model.*

@Composable
internal fun WarehouseChooser(vm: InventoryViewModel, onEnter: (String) -> Unit, onRestore: () -> Unit, onUndone: () -> Unit = {}, backups: BackupActions) {
    Surface(color = Mist, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
          WarehouseHeader("盘库助手")
          Column(Modifier.weight(1f).navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("选择盘点仓库", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text("先选国家和仓库，再开始盘点。", color = Color(0xFF526775), fontSize = 14.sp)
            WarehousePicker(vm, "进入仓库", onEnter = onEnter)
            TextButton(onClick = onRestore, enabled = !vm.busy) { Text("从备份恢复") }
            if (vm.recoveryPoints.isNotEmpty()) RecoveryAccess(vm, onUndone)
            AutomaticBackupPanel(vm, backups)
            vm.message?.let { Text(it, color = Teal, fontSize = 13.sp) }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
          }
        }
    }
}

@Composable
private fun WarehousePicker(vm: InventoryViewModel, enterLabel: String, allowCreate: Boolean = true, onEnter: (String) -> Unit) {
    var countryId by rememberSaveable { mutableStateOf("") }
    var warehouseId by rememberSaveable { mutableStateOf("") }
    var countryMenu by remember { mutableStateOf(false) }
    var warehouseMenu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(vm.snapshot.countries) {
        if (vm.snapshot.countries.none { it.id == countryId }) {
            countryId = vm.snapshot.warehouses.find { it.id == vm.warehouseId }?.countryId ?: vm.snapshot.countries.firstOrNull()?.id.orEmpty()
        }
    }
    val warehouses = vm.snapshot.warehouses.filter { it.countryId == countryId }
    LaunchedEffect(countryId, vm.snapshot.warehouses) {
        if (warehouses.none { it.id == warehouseId }) warehouseId = warehouses.find { it.id == vm.warehouseId }?.id ?: warehouses.firstOrNull()?.id.orEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("国家", color = Petrol, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { countryMenu = true }, enabled = vm.snapshot.countries.isNotEmpty() && !vm.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("${vm.snapshot.countries.find { it.id == countryId }?.name ?: "请先创建国家"} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(countryMenu, { countryMenu = false }) {
                    vm.snapshot.countries.forEach { country -> DropdownMenuItem(text = { Text(country.name) }, onClick = { countryId = country.id; warehouseId = ""; countryMenu = false }) }
                }
            }
            if (allowCreate) TextButton(onClick = { name = ""; adding = "country" }, enabled = !vm.busy) { Text("新建国家") }
        }
        Text("仓库", color = Petrol, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { warehouseMenu = true }, enabled = warehouses.isNotEmpty() && !vm.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("${warehouses.find { it.id == warehouseId }?.name ?: "请先创建仓库"} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(warehouseMenu, { warehouseMenu = false }) {
                    warehouses.forEach { warehouse -> DropdownMenuItem(text = { Text(warehouse.name) }, onClick = { warehouseId = warehouse.id; warehouseMenu = false }) }
                }
            }
            if (allowCreate) TextButton(onClick = { name = ""; adding = "warehouse" }, enabled = countryId.isNotEmpty() && !vm.busy) { Text("新建仓库") }
        }
        Button(onClick = { onEnter(warehouseId) }, enabled = warehouses.any { it.id == warehouseId } && !vm.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(enterLabel, fontSize = 17.sp) }
    }
    adding?.let { type ->
        AlertDialog(onDismissRequest = { if (!vm.busy) adding = null }, title = { Text(if (type == "country") "新建国家" else "新建仓库") },
            text = { OutlinedTextField(name, { name = it }, label = { Text(if (type == "country") "国家名称" else "仓库名称") }, singleLine = true, enabled = !vm.busy) },
            confirmButton = { TextButton(onClick = {
                var createdId = ""
                vm.perform(if (type == "country") "国家已创建" else "仓库已创建", after = {
                    if (type == "country") { countryId = createdId; warehouseId = "" } else warehouseId = createdId
                    adding = null; name = ""
                }) { repository -> createdId = if (type == "country") repository.addCountry(name).id else repository.addWarehouse(countryId, name).id }
            }, enabled = name.isNotBlank() && !vm.busy) { Text("创建") } },
            dismissButton = { TextButton(onClick = { adding = null }, enabled = !vm.busy) { Text("取消") } })
    }
}

@Composable
internal fun SettingsPage(vm: InventoryViewModel, onChoose: (String) -> Unit, onRestore: () -> Unit, onStructureChanged: () -> Unit = {}, backups: BackupActions) {
    var rename by remember { mutableStateOf<Pair<String, String>?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteStep by remember { mutableIntStateOf(0) }
    var merging by remember { mutableStateOf(false) }
    var mergeTarget by remember { mutableStateOf("") }
    var mergeMenu by remember { mutableStateOf(false) }
    var mergeStep by remember { mutableIntStateOf(0) }
    var mergeMode by remember { mutableStateOf(DuplicateMode.NEW_ENTRY) }
    val warehouse = vm.snapshot.warehouses.find { it.id == vm.warehouseId }
    val country = vm.snapshot.countries.find { it.id == warehouse?.countryId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("切换或创建国家和仓库", style = MaterialTheme.typography.titleMedium)
        WarehousePicker(vm, "切换到此仓库", onEnter = onChoose)
        HorizontalDivider()
        Text("修改当前名称", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { country?.let { rename = "country" to it.id; name = it.name } }, enabled = country != null && !vm.busy) { Text("修改当前国家名称") }
        TextButton(onClick = { warehouse?.let { rename = "warehouse" to it.id; name = it.name } }, enabled = warehouse != null && !vm.busy) { Text("修改当前仓库名称") }
        TextButton(onClick = { deleting = "warehouse" to warehouse!!.id; deleteStep = 0 }, enabled = warehouse != null && !vm.busy) { Text("删除当前仓库", color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { deleting = "country" to country!!.id; deleteStep = 0 }, enabled = country != null && !vm.busy) { Text("删除当前国家", color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = { merging = true; mergeTarget = ""; mergeStep = 0 }, enabled = warehouse != null && vm.snapshot.warehouses.any { it.countryId == warehouse.countryId && it.id != warehouse.id } && !vm.busy,
            modifier = Modifier.fillMaxWidth()) { Text("合并当前仓库") }
        HorizontalDivider()
        Text("保存反馈", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("保存震动", Modifier.weight(1f)); Switch(vm.vibrationEnabled, vm::setVibration, enabled = !vm.busy, modifier = Modifier.semantics { contentDescription = "保存震动" })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("提示声音", Modifier.weight(1f)); Switch(vm.soundEnabled, vm::setSound, enabled = !vm.busy, modifier = Modifier.semantics { contentDescription = "提示声音" })
        }
        Text("成功为短提示，重复 SN 为较长提示。声音默认关闭，遵循手机静音及通知音量。", fontSize = 13.sp, color = Petrol)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.testFeedback(false) }, enabled = !vm.busy) { Text("试成功提示") }
            TextButton(onClick = { vm.testFeedback(true) }, enabled = !vm.busy) { Text("试重复提示") }
        }
        HorizontalDivider()
        AutomaticBackupPanel(vm, backups, settings = true)
        HorizontalDivider()
        RecoveryAccess(vm, onStructureChanged)
        OutlinedButton(onClick = onRestore, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("从备份合并恢复") }
        Text("BOM：字母数字最多14位。\nSN：两段字母数字合计最多14位，横线自动补齐。\n前导零和大小写保留，SN在所有仓库中唯一。", fontSize = 14.sp, color = Color(0xFF526775))
        Text("盘库助手 0.2.4 · 单机离线版", fontSize = 12.sp, color = Color.Gray)
        vm.message?.let { Text(it, color = Teal, fontSize = 13.sp) }
    }
    rename?.let { request -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("修改名称") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("新名称") }, singleLine = true, enabled = !vm.busy) },
        confirmButton = { TextButton(onClick = { vm.perform("名称已更新", after = { rename = null }) {
            if (request.first == "country") it.renameCountry(request.second, name) else it.renameWarehouse(request.second, name)
        } }, enabled = name.isNotBlank() && !vm.busy) { Text("保存名称") } },
        dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } }) }
    deleting?.let { request ->
        val ids = if (request.first == "country") vm.snapshot.warehouses.filter { it.countryId == request.second }.map { it.id }.toSet() else setOf(request.second)
        val countryName = if (request.first == "country") vm.snapshot.countries.find { it.id == request.second }?.name.orEmpty() else country?.name.orEmpty()
        val warehouseNames = vm.snapshot.warehouses.filter { it.id in ids }.joinToString("、") { it.name }.ifEmpty { "无仓库" }
        val count = vm.snapshot.records.count { it.warehouseId in ids }
        ConfirmDialog(if (deleteStep == 0) "删除${if (request.first == "country") "国家" else "仓库"}？" else "再次确认删除",
            "国家：$countryName\n仓库：$warehouseNames\n将删除 $count 条记录（含作废）及修改历史，并删除${if (request.first == "country") "这个国家及其全部仓库" else "这个仓库"}。操作前自动保留本机恢复点，最近五次重要操作可撤销。",
            if (deleteStep == 0) "继续删除" else if (request.first == "country") "确认删除国家" else "确认删除仓库", { deleting = null; deleteStep = 0 }) {
            if (deleteStep == 0) deleteStep = 1 else vm.perform("已删除", recoveryLabel = "删除${if (request.first == "country") "国家" else "仓库"}：$countryName / $warehouseNames".take(512), after = { deleting = null; deleteStep = 0; onStructureChanged() }) {
                if (request.first == "country") it.deleteCountry(request.second) else it.deleteWarehouse(request.second)
            }
        }
    }
    if (merging && warehouse != null) {
        val targets = vm.snapshot.warehouses.filter { it.countryId == warehouse.countryId && it.id != warehouse.id }
        val target = targets.find { it.id == mergeTarget }
        val sourceCount = vm.snapshot.records.count { it.warehouseId == warehouse.id }
        val targetCount = vm.snapshot.records.count { it.warehouseId == mergeTarget }
        val summary = "国家：${country?.name}\n源仓库：${warehouse.name}（$sourceCount 条）\n目标仓库：${target?.name ?: "请选择"}（$targetCount 条）\n合并后删除源仓库，作废记录随同迁移。"
        AlertDialog(onDismissRequest = { merging = false }, title = { Text(if (mergeStep == 0) "合并仓库" else "再次确认合并仓库") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(summary)
                if (mergeStep == 0) {
                    Box {
                        OutlinedButton(onClick = { mergeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("${target?.name ?: "选择目标仓库"} ▾") }
                        DropdownMenu(mergeMenu, { mergeMenu = false }) { targets.forEach { option -> DropdownMenuItem(text = { Text(option.name) }, onClick = { mergeTarget = option.id; mergeMenu = false }) } }
                    }
                    DuplicateModePicker(mergeMode) { mergeMode = it }
                } else Text(if (mergeMode == DuplicateMode.ADD_QUANTITY) "同 BOM、同状态配件添加到已有数量。" else "同 BOM、同状态配件保留为新增条目。")
            }
        }, confirmButton = { TextButton(onClick = {
            if (mergeStep == 0) mergeStep = 1 else {
                val source = warehouse.id; val destination = mergeTarget
                vm.performMutation("仓库已合并", recoveryLabel = "合并仓库：${country?.name} / ${warehouse.name} → ${target?.name}".take(512), after = { merging = false; onStructureChanged(); onChoose(destination) }) { it.mergeWarehouses(source, destination, mergeMode) }
            }
        }, enabled = target != null && !vm.busy) { Text(if (mergeStep == 0) "继续合并" else "确认合并仓库") } }, dismissButton = { TextButton(onClick = { merging = false }) { Text("取消") } })
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun RecordsPage(vm: InventoryViewModel, onEdit: (StockRecord) -> Unit, onVoid: (StockRecord) -> Unit,
    onRestore: (StockRecord) -> Unit, onDelete: (StockRecord) -> Unit, onClear: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("active") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var batchAction by remember { mutableStateOf<String?>(null) }
    val voided = category == "voided"
    val categoryRecords = vm.snapshot.records.filter { it.warehouseId == vm.warehouseId && it.voided == voided }
    val records = categoryRecords.filter {
        (filter == "全部" || if (filter == "配件") it.kind == ItemKind.PART else if (filter == "整机") it.kind == ItemKind.MACHINE else it.condition == "待核查") &&
            (query.isBlank() || it.bom.contains(query.trim(), true) || it.sn.contains(query.trim(), true) || it.note.contains(query.trim(), true))
    }.sortedByDescending { it.updatedAt }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceRow(listOf("active" to "正常", "voided" to "作废"), category, !vm.busy) { category = it; selecting = false; selected = emptySet() }
        OutlinedTextField(query, { query = it }, label = { Text("搜索 BOM、SN 或备注") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "配件", "整机", "待核查").forEach { title -> FilterChip(filter == title, { filter = title }, label = { Text(title, fontSize = 12.sp) }) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${records.size} / ${categoryRecords.size} 条", Modifier.weight(1f), fontSize = 12.sp, color = Petrol)
            if (!voided) TextButton(onClick = { selecting = !selecting; selected = emptySet() }, enabled = !vm.busy) { Text(if (selecting) "取消多选" else "多选") }
            TextButton(onClick = { onClear(category) }, enabled = categoryRecords.isNotEmpty() && !vm.busy) {
                Text(if (voided) "一键清除作废" else "一键清空正常", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
        if (selecting && !voided) {
            val picked = categoryRecords.filter { it.id in selected }
            val canMerge = picked.size >= 2 && picked.all { it.kind == ItemKind.PART && it.bom.isNotEmpty() } && picked.map { it.bom to it.condition }.distinct().size == 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = records.map { it.id }.toSet() }) { Text("全选") }
                Text("已选 ${selected.size}", Modifier.weight(1f), fontSize = 12.sp)
                TextButton(onClick = { batchAction = "void" }, enabled = selected.isNotEmpty() && !vm.busy) { Text("批量作废") }
                TextButton(onClick = { batchAction = "merge" }, enabled = canMerge && !vm.busy) { Text("合并配件") }
            }
        }
        if (!voided) Text("包含正常、损坏和待核查；作废记录另页查看。", fontSize = 11.sp, color = Color.Gray)
        vm.message?.let { Text(it, color = Teal, fontSize = 12.sp) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (records.isEmpty()) item { Text(if (voided) "没有作废记录。" else "没有符合条件的记录。", color = Color.Gray, modifier = Modifier.padding(vertical = 20.dp)) }
            items(records, key = { it.id }) { record ->
                Surface(color = if (voided) Color(0xFFE9EEF1) else Color.White, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) selected = if (record.id in selected) selected - record.id else selected + record.id },
                        onLongClick = { if (!voided) { selecting = true; selected = selected + record.id } }).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (selecting) Checkbox(record.id in selected, { checked -> selected = if (checked) selected + record.id else selected - record.id }, enabled = !vm.busy)
                            Text(if (record.kind == ItemKind.PART) "配件 · ${record.quantity} 件" else "整机 · 1 台", fontWeight = FontWeight.Medium)
                            Text(if (voided) "已作废" else record.condition, color = if (record.condition == "正常" && !voided) Teal else Amber, fontSize = 12.sp)
                        }
                        Text(record.bom.ifEmpty { "BOM 待核查" }, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                        if (record.kind == ItemKind.MACHINE) Text(record.sn.ifEmpty { "SN 待核查" }, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                        if (record.note.isNotEmpty()) Text(record.note, fontSize = 13.sp)
                        Text(InventoryViewModel.formatTime(record.updatedAt), fontSize = 11.sp, color = Color.Gray)
                        if (!selecting) Row {
                            if (voided) {
                                TextButton(onClick = { onRestore(record) }, enabled = !vm.busy) { Text("还原") }
                                TextButton(onClick = { onDelete(record) }, enabled = !vm.busy) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            } else {
                                TextButton(onClick = { onEdit(record) }, enabled = !vm.busy) { Text("修改") }
                                TextButton(onClick = { onVoid(record) }, enabled = !vm.busy) { Text("作废") }
                            }
                        }
                    }
                }
            }
        }
    }
    batchAction?.let { action ->
        val picked = categoryRecords.filter { it.id in selected }
        ConfirmDialog(if (action == "void") "作废所选记录？" else "合并所选配件？",
            "${vm.warehouseLabel(vm.warehouseId)}\n共 ${picked.size} 条${if (action == "merge") "，BOM：${picked.firstOrNull()?.bom}，状态：${picked.firstOrNull()?.condition}，合计 ${picked.sumOf { it.quantity.toLong() }} 件" else "，作废后不再计数，可以还原"}。",
            if (action == "void") "确认批量作废" else "确认合并", { batchAction = null }) {
            val ids = selected; batchAction = null
            vm.perform(if (action == "void") "所选记录已作废" else "所选配件已合并", recoveryLabel = "${if (action == "void") "批量作废" else "合并配件"}：${vm.warehouseLabel(vm.warehouseId)}", after = { selected = emptySet(); selecting = false }) {
                if (action == "void") it.voidRecords(ids) else it.mergeParts(ids)
            }
        }
    }
}

@Composable
internal fun DuplicateModePicker(mode: DuplicateMode, onSelect: (DuplicateMode) -> Unit) {
    Column {
        Text("同 BOM、同状态配件的处理方式", fontSize = 13.sp, color = Petrol)
        listOf(DuplicateMode.ADD_QUANTITY to "添加到已有数量", DuplicateMode.NEW_ENTRY to "新增条目").forEach { (value, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == value, onClick = { onSelect(value) })
                TextButton(onClick = { onSelect(value) }) { Text(label) }
            }
        }
    }
}

@Composable
internal fun ProcessingPage(vm: InventoryViewModel, onExport: (String) -> Unit, onImport: () -> Unit, onTemplate: () -> Unit, backups: BackupActions) {
    var section by rememberSaveable { mutableStateOf("export") }
    var targetWarehouse by rememberSaveable { mutableStateOf(vm.warehouseId) }
    var mode by remember { mutableStateOf(DuplicateMode.NEW_ENTRY) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ChoiceRow(listOf("import" to "导入", "export" to "导出"), section, !vm.busy) { section = it } }
        Box(Modifier.weight(1f)) {
            if (section == "export") ExportPage(vm, onExport, backups)
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("导入库存文件", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("使用 CSV 模板，BOM、SN 按文本填写以保留前导零。导入目标独立选择，不改变当前盘点仓库。", fontSize = 14.sp, color = Petrol)
                Text("本机从 0.2.2 开始记录导入历史；旧版或其他手机导入过的文件仍需自行核对。", fontSize = 13.sp, color = Petrol)
                OutlinedButton(onClick = onTemplate, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("下载导入模板") }
                Button(onClick = onImport, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("选择 CSV 文件") }
                WarehousePicker(vm, "选择为导入目标", allowCreate = false) { targetWarehouse = it }
                Text("导入目标：${vm.warehouseLabel(targetWarehouse)}", fontWeight = FontWeight.Medium)
                DuplicateModePicker(mode) { mode = it }
                vm.importRows?.let { rows ->
                    val preview = vm.importPreview(targetWarehouse, mode)
                    Text("文件：${vm.importFileName}\n已读取 ${rows.size} 行，确认后导入所选仓库。", color = Teal)
                    Text(preview.description(), fontSize = 14.sp)
                    val repeated = vm.repeatImportNotice(targetWarehouse)
                    if (repeated.isNotEmpty()) Text(repeated, color = Amber, fontSize = 13.sp)
                    Button(onClick = { confirm = true }, enabled = rows.isNotEmpty() && preview.issue == null && !vm.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("预览并确认导入") }
                }
                Text("需要退回整批操作时，可在设置中点“撤销重要操作”。", color = Petrol, fontSize = 13.sp)
                vm.message?.let { Text(it, color = Teal, fontSize = 13.sp) }
            }
        }
    }
    if (confirm) {
        val repeated = vm.repeatImportNotice(targetWarehouse)
        ConfirmDialog(if (repeated.isEmpty()) "确认导入到此仓库？" else "这个文件内容曾导入过", "目标：${vm.warehouseLabel(targetWarehouse)}\n文件：${vm.importFileName}\n${repeated}\n${vm.importPreview(targetWarehouse, mode).description()}\n${if (mode == DuplicateMode.ADD_QUANTITY) "同 BOM、同状态配件添加到已有数量" else "配件新增条目"}。\n${vm.importDuplicateNotice()}\n操作前自动保留本机恢复点。", if (repeated.isEmpty()) "确认导入" else "仍要重复导入", { confirm = false }) {
            confirm = false; vm.applyImport(targetWarehouse, mode, allowRepeat = true)
        }
    }
}

@Composable
internal fun RecoveryAccess(vm: InventoryViewModel, onUndone: () -> Unit = {}) {
    var opened by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<RecoveryPoint?>(null) }
    OutlinedButton(onClick = { opened = true }, enabled = vm.recoveryPoints.isNotEmpty() && !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("撤销重要操作") }
    Text("导入、合并、删除和批量操作自动留恢复点，最多保留最近五次，容量较大时会提前清理旧点；仅在本机保存，不能替代导出备份。", fontSize = 13.sp, color = Petrol)
    if (opened) AlertDialog(onDismissRequest = { opened = false }, title = { Text("最近的重要操作") }, text = {
        Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            vm.recoveryPoints.forEach { point ->
                Column {
                    Text(point.label, fontWeight = FontWeight.Medium)
                    Text("${InventoryViewModel.formatHistoryTime(point.at)} · 涉及 ${point.records} 条库存", fontSize = 12.sp)
                    TextButton(onClick = { opened = false; chosen = point }, enabled = !point.undone && !vm.busy) { Text(if (point.undone) "已撤销" else "撤销这次操作") }
                }
                HorizontalDivider()
            }
        }
    }, confirmButton = { TextButton(onClick = { opened = false }) { Text("关闭") } })
    chosen?.let { point -> ConfirmDialog("撤销这次操作？", "${point.label}\n${InventoryViewModel.formatHistoryTime(point.at)} · 涉及 ${point.records} 条库存\n只退回这次操作，后来新增的其他记录会保留。相关记录若后来改过，会停止撤销并提示。\n恢复整机时若 SN 已被其他记录占用，重复整机会保留在作废页。", "确认撤销", { chosen = null }) {
        chosen = null; vm.undoRecovery(point, onUndone)
    } }
}

@Composable
internal fun ExportPage(vm: InventoryViewModel, onExport: (String) -> Unit, backups: BackupActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("导出", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("明细和汇总仅导出当前仓库。", color = Color(0xFF526775), fontSize = 14.sp)
        Button(onClick = { onExport("xlsx") }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("导出 Excel 明细与汇总") }
        OutlinedButton(onClick = { onExport("csv") }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("导出 CSV 压缩包") }
        Text("CSV 导入 Excel 时，将 BOM、SN 列设为文本以保留前导零。", fontSize = 13.sp, color = Color(0xFF526775))
        HorizontalDivider()
        Text("完整备份", fontSize = 19.sp, fontWeight = FontWeight.Medium)
        Text("包含所有国家和仓库、作废记录及修改历史。已永久删除的记录不再包含。", fontSize = 14.sp)
        FilledTonalButton(onClick = { onExport("backup") }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("保存完整备份") }
        Text("最近成功保存\n${vm.lastExport}", fontSize = 13.sp, color = Color(0xFF526775))
        AutomaticBackupPanel(vm, backups)
        vm.message?.let { Text(it, color = Teal, fontSize = 13.sp) }
        Text("每天收工后，把 Excel 和完整备份复制到电脑或其他位置。", fontSize = 14.sp, color = Petrol)
    }
}
