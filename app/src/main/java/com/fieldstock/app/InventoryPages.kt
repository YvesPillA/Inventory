package com.fieldstock.app

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldstock.app.model.*

@Composable
internal fun WarehouseChooser(vm: InventoryViewModel, onEnter: (String) -> Unit, onRestore: () -> Unit) {
    Surface(color = Mist, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
          WarehouseHeader("盘库助手")
          Column(Modifier.weight(1f).navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("选择盘点仓库", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text("先选国家和仓库，再开始盘点。", color = Color(0xFF526775), fontSize = 14.sp)
            WarehousePicker(vm, "进入仓库", onEnter)
            TextButton(onClick = onRestore, enabled = !vm.busy) { Text("从备份恢复") }
            vm.message?.let { Text(it, color = Teal, fontSize = 13.sp) }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
          }
        }
    }
}

@Composable
private fun WarehousePicker(vm: InventoryViewModel, enterLabel: String, onEnter: (String) -> Unit) {
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
            TextButton(onClick = { name = ""; adding = "country" }, enabled = !vm.busy) { Text("新建国家") }
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
            TextButton(onClick = { name = ""; adding = "warehouse" }, enabled = countryId.isNotEmpty() && !vm.busy) { Text("新建仓库") }
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
internal fun SettingsPage(vm: InventoryViewModel, onChoose: (String) -> Unit, onRestore: () -> Unit) {
    var rename by remember { mutableStateOf<Pair<String, String>?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("切换或创建国家和仓库", style = MaterialTheme.typography.titleMedium)
        WarehousePicker(vm, "切换到此仓库", onChoose)
        HorizontalDivider()
        Text("修改当前名称", style = MaterialTheme.typography.titleMedium)
        val warehouse = vm.snapshot.warehouses.find { it.id == vm.warehouseId }
        val country = vm.snapshot.countries.find { it.id == warehouse?.countryId }
        TextButton(onClick = { country?.let { rename = "country" to it.id; name = it.name } }, enabled = country != null && !vm.busy) { Text("修改当前国家名称") }
        TextButton(onClick = { warehouse?.let { rename = "warehouse" to it.id; name = it.name } }, enabled = warehouse != null && !vm.busy) { Text("修改当前仓库名称") }
        HorizontalDivider()
        OutlinedButton(onClick = onRestore, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("从备份合并恢复") }
        Text("BOM：字母数字最多14位。\nSN：两段字母数字合计最多14位，横线自动补齐。\n前导零和大小写保留，SN在所有仓库中唯一。", fontSize = 14.sp, color = Color(0xFF526775))
        Text("盘库助手 0.2.0 · 单机离线版", fontSize = 12.sp, color = Color.Gray)
        vm.message?.let { Text(it, color = Teal, fontSize = 13.sp) }
    }
    rename?.let { request -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("修改名称") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("新名称") }, singleLine = true, enabled = !vm.busy) },
        confirmButton = { TextButton(onClick = { vm.perform("名称已更新", after = { rename = null }) {
            if (request.first == "country") it.renameCountry(request.second, name) else it.renameWarehouse(request.second, name)
        } }, enabled = name.isNotBlank() && !vm.busy) { Text("保存名称") } },
        dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } }) }
}

@Composable
internal fun RecordsPage(vm: InventoryViewModel, onEdit: (StockRecord) -> Unit, onVoid: (StockRecord) -> Unit,
    onRestore: (StockRecord) -> Unit, onDelete: (StockRecord) -> Unit, onClear: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("active") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    val voided = category == "voided"
    val categoryRecords = vm.snapshot.records.filter { it.warehouseId == vm.warehouseId && it.voided == voided }
    val records = categoryRecords.filter {
        (filter == "全部" || if (filter == "配件") it.kind == ItemKind.PART else if (filter == "整机") it.kind == ItemKind.MACHINE else it.condition == "待核查") &&
            (query.isBlank() || it.bom.contains(query.trim(), true) || it.sn.contains(query.trim(), true) || it.note.contains(query.trim(), true))
    }.sortedByDescending { it.updatedAt }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceRow(listOf("active" to "正常", "voided" to "作废"), category, !vm.busy) { category = it }
        OutlinedTextField(query, { query = it }, label = { Text("搜索 BOM、SN 或备注") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "配件", "整机", "待核查").forEach { title -> FilterChip(filter == title, { filter = title }, label = { Text(title, fontSize = 12.sp) }) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${records.size} / ${categoryRecords.size} 条", Modifier.weight(1f), fontSize = 12.sp, color = Petrol)
            TextButton(onClick = { onClear(category) }, enabled = categoryRecords.isNotEmpty() && !vm.busy) {
                Text(if (voided) "一键清除作废" else "一键清空正常", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
        if (!voided) Text("包含正常、损坏和待核查；作废记录另页查看。", fontSize = 11.sp, color = Color.Gray)
        vm.message?.let { Text(it, color = Teal, fontSize = 12.sp) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (records.isEmpty()) item { Text(if (voided) "没有作废记录。" else "没有符合条件的记录。", color = Color.Gray, modifier = Modifier.padding(vertical = 20.dp)) }
            items(records, key = { it.id }) { record ->
                Surface(color = if (voided) Color(0xFFE9EEF1) else Color.White, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (record.kind == ItemKind.PART) "配件 · ${record.quantity} 件" else "整机 · 1 台", fontWeight = FontWeight.Medium)
                            Text(if (voided) "已作废" else record.condition, color = if (record.condition == "正常" && !voided) Teal else Amber, fontSize = 12.sp)
                        }
                        Text(record.bom.ifEmpty { "BOM 待核查" }, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                        if (record.kind == ItemKind.MACHINE) Text(record.sn.ifEmpty { "SN 待核查" }, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                        if (record.note.isNotEmpty()) Text(record.note, fontSize = 13.sp)
                        Text(InventoryViewModel.formatTime(record.updatedAt), fontSize = 11.sp, color = Color.Gray)
                        Row {
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
}

@Composable
internal fun ExportPage(vm: InventoryViewModel, onExport: (String) -> Unit) {
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
        vm.message?.let { Text(it, color = Teal, fontSize = 13.sp) }
        Text("每天收工后，把 Excel 和完整备份复制到电脑或其他位置。", fontSize = 14.sp, color = Petrol)
    }
}
