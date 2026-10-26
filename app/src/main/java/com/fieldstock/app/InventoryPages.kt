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
import com.fieldstock.app.i18n.*
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
          WarehouseHeader(tr(TextKey.FIELDSTOCK))
          Column(Modifier.weight(1f).navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(tr(TextKey.CHOOSE_A_WAREHOUSE), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(tr(TextKey.SELECT_A_COUNTRY_AND_WAREHOUSE_TO_START_COUNTING), color = Color(0xFF526775), fontSize = 14.sp)
            WarehousePicker(vm, tr(TextKey.ENTER_WAREHOUSE), onEnter = onEnter)
            TextButton(onClick = onRestore, enabled = !vm.busy) { Text(tr(TextKey.RESTORE_FROM_BACKUP)) }
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
        Text(tr(TextKey.COUNTRY), color = Petrol, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { countryMenu = true }, enabled = vm.snapshot.countries.isNotEmpty() && !vm.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("${vm.snapshot.countries.find { it.id == countryId }?.name ?: tr(TextKey.CREATE_A_COUNTRY_FIRST)} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(countryMenu, { countryMenu = false }) {
                    vm.snapshot.countries.forEach { country -> DropdownMenuItem(text = { Text(country.name) }, onClick = { countryId = country.id; warehouseId = ""; countryMenu = false }) }
                }
            }
            if (allowCreate) TextButton(onClick = { name = ""; adding = "country" }, enabled = !vm.busy) { Text(tr(TextKey.NEW_COUNTRY)) }
        }
        Text(tr(TextKey.WAREHOUSE), color = Petrol, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { warehouseMenu = true }, enabled = warehouses.isNotEmpty() && !vm.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("${warehouses.find { it.id == warehouseId }?.name ?: tr(TextKey.CREATE_A_WAREHOUSE_FIRST)} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(warehouseMenu, { warehouseMenu = false }) {
                    warehouses.forEach { warehouse -> DropdownMenuItem(text = { Text(warehouse.name) }, onClick = { warehouseId = warehouse.id; warehouseMenu = false }) }
                }
            }
            if (allowCreate) TextButton(onClick = { name = ""; adding = "warehouse" }, enabled = countryId.isNotEmpty() && !vm.busy) { Text(tr(TextKey.NEW_WAREHOUSE)) }
        }
        Button(onClick = { onEnter(warehouseId) }, enabled = warehouses.any { it.id == warehouseId } && !vm.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(enterLabel, fontSize = 17.sp) }
    }
    adding?.let { type ->
        AlertDialog(onDismissRequest = { if (!vm.busy) adding = null }, title = { Text(if (type == "country") tr(TextKey.NEW_COUNTRY) else tr(TextKey.NEW_WAREHOUSE)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(if (type == "country") tr(TextKey.COUNTRY_NAME) else tr(TextKey.WAREHOUSE_NAME)) }, singleLine = true, enabled = !vm.busy) },
            confirmButton = { TextButton(onClick = {
                var createdId = ""
                vm.perform(if (type == "country") tr(TextKey.COUNTRY_CREATED) else tr(TextKey.WAREHOUSE_CREATED), after = {
                    if (type == "country") { countryId = createdId; warehouseId = "" } else warehouseId = createdId
                    adding = null; name = ""
                }) { repository -> createdId = if (type == "country") repository.addCountry(name).id else repository.addWarehouse(countryId, name).id }
            }, enabled = name.isNotBlank() && !vm.busy) { Text(tr(TextKey.CREATE)) } },
            dismissButton = { TextButton(onClick = { adding = null }, enabled = !vm.busy) { Text(tr(TextKey.CANCEL)) } })
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
        Text(tr(TextKey.SETTINGS), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LanguageSetting(enabled = !vm.busy, beforeChange = vm::onLanguageChanged)
        Text(tr(TextKey.SWITCH_OR_CREATE_A_COUNTRY_AND_WAREHOUSE), style = MaterialTheme.typography.titleMedium)
        WarehousePicker(vm, tr(TextKey.SWITCH_TO_THIS_WAREHOUSE), onEnter = onChoose)
        HorizontalDivider()
        Text(tr(TextKey.RENAME_CURRENT_LOCATION), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { country?.let { rename = "country" to it.id; name = it.name } }, enabled = country != null && !vm.busy) { Text(tr(TextKey.RENAME_CURRENT_COUNTRY)) }
        TextButton(onClick = { warehouse?.let { rename = "warehouse" to it.id; name = it.name } }, enabled = warehouse != null && !vm.busy) { Text(tr(TextKey.RENAME_CURRENT_WAREHOUSE)) }
        TextButton(onClick = { deleting = "warehouse" to warehouse!!.id; deleteStep = 0 }, enabled = warehouse != null && !vm.busy) { Text(tr(TextKey.DELETE_CURRENT_WAREHOUSE), color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { deleting = "country" to country!!.id; deleteStep = 0 }, enabled = country != null && !vm.busy) { Text(tr(TextKey.DELETE_CURRENT_COUNTRY), color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = { merging = true; mergeTarget = ""; mergeStep = 0 }, enabled = warehouse != null && vm.snapshot.warehouses.any { it.countryId == warehouse.countryId && it.id != warehouse.id } && !vm.busy,
            modifier = Modifier.fillMaxWidth()) { Text(tr(TextKey.MERGE_CURRENT_WAREHOUSE)) }
        HorizontalDivider()
        Text(tr(TextKey.SAVE_FEEDBACK), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(TextKey.VIBRATE_ON_SAVE), Modifier.weight(1f)); Switch(vm.vibrationEnabled, vm::setVibration, enabled = !vm.busy, modifier = Modifier.semantics { contentDescription = tr(TextKey.VIBRATE_ON_SAVE) })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(TextKey.SOUND_ALERTS), Modifier.weight(1f)); Switch(vm.soundEnabled, vm::setSound, enabled = !vm.busy, modifier = Modifier.semantics { contentDescription = tr(TextKey.SOUND_ALERTS) })
        }
        Text(tr(TextKey.A_SHORT_ALERT_CONFIRMS_A_SAVE_A_LONGER_ALERT), fontSize = 13.sp, color = Petrol)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.testFeedback(false) }, enabled = !vm.busy) { Text(tr(TextKey.TEST_SAVE_ALERT)) }
            TextButton(onClick = { vm.testFeedback(true) }, enabled = !vm.busy) { Text(tr(TextKey.TEST_DUPLICATE_ALERT)) }
        }
        HorizontalDivider()
        AutomaticBackupPanel(vm, backups, settings = true)
        HorizontalDivider()
        RecoveryAccess(vm, onStructureChanged)
        OutlinedButton(onClick = onRestore, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(tr(TextKey.MERGE_FROM_BACKUP)) }
        Text(tr(TextKey.BOM_UP_TO_14_LETTERS_OR_DIGITS_SN_TWO), fontSize = 14.sp, color = Color(0xFF526775))
        Text(tr(TextKey.APP_VERSION), fontSize = 12.sp, color = Color.Gray)
        vm.message?.let { Text(it, color = Teal, fontSize = 13.sp) }
    }
    rename?.let { request -> AlertDialog(onDismissRequest = { rename = null }, title = { Text(tr(TextKey.RENAME)) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(tr(TextKey.NEW_NAME)) }, singleLine = true, enabled = !vm.busy) },
        confirmButton = { TextButton(onClick = { vm.perform(tr(TextKey.NAME_UPDATED), after = { rename = null }) {
            if (request.first == "country") it.renameCountry(request.second, name) else it.renameWarehouse(request.second, name)
        } }, enabled = name.isNotBlank() && !vm.busy) { Text(tr(TextKey.SAVE_NAME)) } },
        dismissButton = { TextButton(onClick = { rename = null }) { Text(tr(TextKey.CANCEL)) } }) }
    deleting?.let { request ->
        val ids = if (request.first == "country") vm.snapshot.warehouses.filter { it.countryId == request.second }.map { it.id }.toSet() else setOf(request.second)
        val countryName = if (request.first == "country") vm.snapshot.countries.find { it.id == request.second }?.name.orEmpty() else country?.name.orEmpty()
        val warehouseNames = vm.snapshot.warehouses.filter { it.id in ids }.joinToString("、") { it.name }.ifEmpty { tr(TextKey.NO_WAREHOUSES) }
        val count = vm.snapshot.records.count { it.warehouseId in ids }
        ConfirmDialog(if (deleteStep == 0) tr(TextKey.DELETE, if (request.first == "country") tr(TextKey.COUNTRY) else tr(TextKey.WAREHOUSE)) else tr(TextKey.CONFIRM_DELETION_AGAIN),
            tr(TextKey.COUNTRY_WAREHOUSES_DELETE_RECORDS_INCLUDING_VOIDED_RECORDS_AND_THEIR, countryName, warehouseNames, count, if (request.first == "country") tr(TextKey.THIS_COUNTRY_AND_ALL_ITS_WAREHOUSES) else tr(TextKey.THIS_WAREHOUSE)),
            if (deleteStep == 0) tr(TextKey.CONTINUE_DELETION) else if (request.first == "country") tr(TextKey.CONFIRM_DELETE_COUNTRY) else tr(TextKey.CONFIRM_DELETE_WAREHOUSE), { deleting = null; deleteStep = 0 }) {
            if (deleteStep == 0) deleteStep = 1 else vm.perform(tr(TextKey.DELETED), recoveryLabel = tr(TextKey.DELETE_2, if (request.first == "country") tr(TextKey.COUNTRY) else tr(TextKey.WAREHOUSE), countryName, warehouseNames).take(512), after = { deleting = null; deleteStep = 0; onStructureChanged() }) {
                if (request.first == "country") it.deleteCountry(request.second) else it.deleteWarehouse(request.second)
            }
        }
    }
    if (merging && warehouse != null) {
        val targets = vm.snapshot.warehouses.filter { it.countryId == warehouse.countryId && it.id != warehouse.id }
        val target = targets.find { it.id == mergeTarget }
        val sourceCount = vm.snapshot.records.count { it.warehouseId == warehouse.id }
        val targetCount = vm.snapshot.records.count { it.warehouseId == mergeTarget }
        val summary = tr(TextKey.COUNTRY_SOURCE_RECORDS_TARGET_RECORDS_THE_SOURCE_WAREHOUSE_WILL, country?.name, warehouse.name, sourceCount, target?.name ?: tr(TextKey.PLEASE_SELECT), targetCount)
        AlertDialog(onDismissRequest = { merging = false }, title = { Text(if (mergeStep == 0) tr(TextKey.MERGE_WAREHOUSES) else tr(TextKey.CONFIRM_WAREHOUSE_MERGE_AGAIN)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(summary)
                if (mergeStep == 0) {
                    Box {
                        OutlinedButton(onClick = { mergeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("${target?.name ?: tr(TextKey.CHOOSE_TARGET_WAREHOUSE)} ▾") }
                        DropdownMenu(mergeMenu, { mergeMenu = false }) { targets.forEach { option -> DropdownMenuItem(text = { Text(option.name) }, onClick = { mergeTarget = option.id; mergeMenu = false }) } }
                    }
                    DuplicateModePicker(mergeMode) { mergeMode = it }
                } else Text(if (mergeMode == DuplicateMode.ADD_QUANTITY) tr(TextKey.ADD_PARTS_WITH_THE_SAME_BOM_AND_CONDITION_TO) else tr(TextKey.KEEP_PARTS_WITH_THE_SAME_BOM_AND_CONDITION_AS))
            }
        }, confirmButton = { TextButton(onClick = {
            if (mergeStep == 0) mergeStep = 1 else {
                val source = warehouse.id; val destination = mergeTarget
                vm.performMutation(tr(TextKey.WAREHOUSES_MERGED), recoveryLabel = tr(TextKey.MERGE_WAREHOUSES_2, country?.name, warehouse.name, target?.name).take(512), after = { merging = false; onStructureChanged(); onChoose(destination) }) { it.mergeWarehouses(source, destination, mergeMode) }
            }
        }, enabled = target != null && !vm.busy) { Text(if (mergeStep == 0) tr(TextKey.CONTINUE_MERGE) else tr(TextKey.CONFIRM_WAREHOUSE_MERGE)) } }, dismissButton = { TextButton(onClick = { merging = false }) { Text(tr(TextKey.CANCEL)) } })
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun RecordsPage(vm: InventoryViewModel, onEdit: (StockRecord) -> Unit, onVoid: (StockRecord) -> Unit,
    onRestore: (StockRecord) -> Unit, onDelete: (StockRecord) -> Unit, onClear: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("active") }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var batchAction by remember { mutableStateOf<String?>(null) }
    val voided = category == "voided"
    val categoryRecords = vm.snapshot.records.filter { it.warehouseId == vm.warehouseId && it.voided == voided }
    val records = categoryRecords.filter {
        (filter == "ALL" || if (filter == "PART") it.kind == ItemKind.PART else if (filter == "MACHINE") it.kind == ItemKind.MACHINE else it.condition == StockCondition.PENDING) &&
            (query.isBlank() || it.bom.contains(query.trim(), true) || it.sn.contains(query.trim(), true) || it.note.contains(query.trim(), true))
    }.sortedByDescending { it.updatedAt }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceRow(listOf("active" to tr(TextKey.ACTIVE), "voided" to tr(TextKey.VOIDED)), category, !vm.busy) { category = it; selecting = false; selected = emptySet() }
        OutlinedTextField(query, { query = it }, label = { Text(tr(TextKey.SEARCH_BOM_SN_OR_NOTES)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ALL" to tr(TextKey.ALL), "PART" to tr(TextKey.PARTS), "MACHINE" to tr(TextKey.MACHINE), "PENDING" to tr(TextKey.PENDING_FILTER)).forEach { (id, title) ->
                FilterChip(filter == id, { filter = id }, label = { Text(title, fontSize = 12.sp) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(TextKey.RECORDS, records.size, categoryRecords.size), Modifier.weight(1f), fontSize = 12.sp, color = Petrol)
            if (!voided) TextButton(onClick = { selecting = !selecting; selected = emptySet() }, enabled = !vm.busy) { Text(if (selecting) tr(TextKey.CANCEL_SELECTION) else tr(TextKey.SELECT)) }
            TextButton(onClick = { onClear(category) }, enabled = categoryRecords.isNotEmpty() && !vm.busy) {
                Text(if (voided) tr(TextKey.CLEAR_ALL_VOIDED) else tr(TextKey.CLEAR_ALL_ACTIVE), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
        if (selecting && !voided) {
            val picked = categoryRecords.filter { it.id in selected }
            val canMerge = picked.size >= 2 && picked.all { it.kind == ItemKind.PART && it.bom.isNotEmpty() } && picked.map { it.bom to it.condition }.distinct().size == 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = records.map { it.id }.toSet() }) { Text(tr(TextKey.SELECT_ALL)) }
                Text(tr(TextKey.SELECTED, selected.size), Modifier.weight(1f), fontSize = 12.sp)
                TextButton(onClick = { batchAction = "void" }, enabled = selected.isNotEmpty() && !vm.busy) { Text(tr(TextKey.VOID_SELECTED)) }
                TextButton(onClick = { batchAction = "merge" }, enabled = canMerge && !vm.busy) { Text(tr(TextKey.MERGE_PARTS)) }
            }
        }
        if (!voided) Text(tr(TextKey.INCLUDES_NORMAL_DAMAGED_AND_PENDING_RECORDS_VIEW_VOIDED_RECORDS), fontSize = 11.sp, color = Color.Gray)
        vm.message?.let { Text(it, color = Teal, fontSize = 12.sp) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (records.isEmpty()) item { Text(if (voided) tr(TextKey.NO_VOIDED_RECORDS) else tr(TextKey.NO_MATCHING_RECORDS), color = Color.Gray, modifier = Modifier.padding(vertical = 20.dp)) }
            items(records, key = { it.id }) { record ->
                Surface(color = if (voided) Color(0xFFE9EEF1) else Color.White, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) selected = if (record.id in selected) selected - record.id else selected + record.id },
                        onLongClick = { if (!voided) { selecting = true; selected = selected + record.id } }).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (selecting) Checkbox(record.id in selected, { checked -> selected = if (checked) selected + record.id else selected - record.id }, enabled = !vm.busy)
                            Text(if (record.kind == ItemKind.PART) tr(TextKey.PARTS_PCS, record.quantity) else tr(TextKey.MACHINE_1_UNIT), fontWeight = FontWeight.Medium)
                            Text(if (voided) tr(TextKey.VOIDED_2) else conditionLabel(record.condition), color = if (record.condition == StockCondition.NORMAL && !voided) Teal else Amber, fontSize = 12.sp)
                        }
                        Text(record.bom.ifEmpty { tr(TextKey.BOM_PENDING_VERIFICATION) }, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                        if (record.kind == ItemKind.MACHINE) Text(record.sn.ifEmpty { tr(TextKey.SN_PENDING_VERIFICATION) }, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                        if (record.note.isNotEmpty()) Text(record.note, fontSize = 13.sp)
                        Text(InventoryViewModel.formatTime(record.updatedAt), fontSize = 11.sp, color = Color.Gray)
                        if (!selecting) Row {
                            if (voided) {
                                TextButton(onClick = { onRestore(record) }, enabled = !vm.busy) { Text(tr(TextKey.RESTORE_2)) }
                                TextButton(onClick = { onDelete(record) }, enabled = !vm.busy) { Text(tr(TextKey.DELETE_3), color = MaterialTheme.colorScheme.error) }
                            } else {
                                TextButton(onClick = { onEdit(record) }, enabled = !vm.busy) { Text(tr(TextKey.EDIT)) }
                                TextButton(onClick = { onVoid(record) }, enabled = !vm.busy) { Text(tr(TextKey.VOIDED)) }
                            }
                        }
                    }
                }
            }
        }
    }
    batchAction?.let { action ->
        val picked = categoryRecords.filter { it.id in selected }
        ConfirmDialog(if (action == "void") tr(TextKey.VOID_SELECTED_RECORDS) else tr(TextKey.MERGE_SELECTED_PARTS),
            tr(TextKey.RECORDS_2, vm.warehouseLabel(vm.warehouseId), picked.size, if (action == "merge") tr(TextKey.BOM_CONDITION_TOTAL_PCS, picked.firstOrNull()?.bom, conditionLabel(picked.firstOrNull()?.condition.orEmpty()), picked.sumOf { it.quantity.toLong() }) else tr(TextKey.VOIDED_RECORDS_ARE_EXCLUDED_FROM_TOTALS_AND_CAN_BE)),
            if (action == "void") tr(TextKey.CONFIRM_VOID_SELECTED) else tr(TextKey.CONFIRM_MERGE), { batchAction = null }) {
            val ids = selected; batchAction = null
            vm.perform(if (action == "void") tr(TextKey.SELECTED_RECORDS_VOIDED) else tr(TextKey.SELECTED_PARTS_MERGED), recoveryLabel = "${if (action == "void") tr(TextKey.VOID_SELECTED) else tr(TextKey.MERGE_PARTS)}：${vm.warehouseLabel(vm.warehouseId)}", after = { selected = emptySet(); selecting = false }) {
                if (action == "void") it.voidRecords(ids) else it.mergeParts(ids)
            }
        }
    }
}

@Composable
internal fun DuplicateModePicker(mode: DuplicateMode, onSelect: (DuplicateMode) -> Unit) {
    Column {
        Text(tr(TextKey.PARTS_WITH_THE_SAME_BOM_AND_CONDITION), fontSize = 13.sp, color = Petrol)
        listOf(DuplicateMode.ADD_QUANTITY to tr(TextKey.ADD_TO_EXISTING_QUANTITY), DuplicateMode.NEW_ENTRY to tr(TextKey.CREATE_NEW_ENTRY)).forEach { (value, label) ->
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
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ChoiceRow(listOf("import" to tr(TextKey.IMPORT), "export" to tr(TextKey.EXPORT)), section, !vm.busy) { section = it } }
        Box(Modifier.weight(1f)) {
            if (section == "export") ExportPage(vm, onExport, backups)
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(tr(TextKey.IMPORT_INVENTORY_FILE), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(tr(TextKey.USE_THE_CSV_TEMPLATE_ENTER_BOM_AND_SN_AS), fontSize = 14.sp, color = Petrol)
                Text(tr(TextKey.BILINGUAL_IMPORT_HELP), fontSize = 13.sp, color = Petrol)
                Text(tr(TextKey.CSV_VALUE_HELP), fontSize = 13.sp, color = Petrol)
                Text(tr(TextKey.IMPORT_HISTORY_HAS_BEEN_RECORDED_ON_THIS_PHONE_SINCE), fontSize = 13.sp, color = Petrol)
                OutlinedButton(onClick = onTemplate, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(TextKey.DOWNLOAD_IMPORT_TEMPLATE)) }
                Button(onClick = onImport, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(TextKey.CHOOSE_CSV_FILE)) }
                WarehousePicker(vm, tr(TextKey.USE_AS_IMPORT_DESTINATION), allowCreate = false) { targetWarehouse = it }
                Text(tr(TextKey.IMPORT_DESTINATION, vm.warehouseLabel(targetWarehouse)), fontWeight = FontWeight.Medium)
                DuplicateModePicker(mode) { mode = it }
                vm.importRows?.let { rows ->
                    val preview = vm.importPreview(targetWarehouse, mode)
                    Text(tr(TextKey.FILE_READ_ROWS_CONFIRM_TO_IMPORT_INTO_THE_SELECTED, vm.importFileName, rows.size), color = Teal)
                    Text(preview.description(), fontSize = 14.sp)
                    val repeated = vm.repeatImportNotice(targetWarehouse)
                    if (repeated.isNotEmpty()) Text(repeated, color = Amber, fontSize = 13.sp)
                    Button(onClick = { confirm = true }, enabled = rows.isNotEmpty() && preview.issue == null && !vm.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(TextKey.PREVIEW_AND_CONFIRM_IMPORT)) }
                }
                Text(tr(TextKey.TO_REVERSE_A_BATCH_OPERATION_USE_UNDO_IMPORTANT_OPERATION), color = Petrol, fontSize = 13.sp)
                vm.message?.let { Text(it, color = Teal, fontSize = 13.sp) }
            }
        }
    }
    if (confirm) {
        val repeated = vm.repeatImportNotice(targetWarehouse)
        ConfirmDialog(if (repeated.isEmpty()) tr(TextKey.IMPORT_INTO_THIS_WAREHOUSE) else tr(TextKey.THIS_FILE_CONTENT_WAS_IMPORTED_BEFORE), tr(TextKey.TARGET_FILE_A_LOCAL_RECOVERY_POINT_IS_SAVED_BEFORE, vm.warehouseLabel(targetWarehouse), vm.importFileName, repeated, vm.importPreview(targetWarehouse, mode).description(), if (mode == DuplicateMode.ADD_QUANTITY) tr(TextKey.ADD_PARTS_WITH_THE_SAME_BOM_AND_CONDITION_TO_2) else tr(TextKey.CREATE_NEW_ENTRIES_FOR_PARTS), vm.importDuplicateNotice()), if (repeated.isEmpty()) tr(TextKey.CONFIRM_IMPORT) else tr(TextKey.IMPORT_AGAIN_ANYWAY), { confirm = false }) {
            confirm = false; vm.applyImport(targetWarehouse, mode, allowRepeat = true)
        }
    }
}

@Composable
internal fun RecoveryAccess(vm: InventoryViewModel, onUndone: () -> Unit = {}) {
    var opened by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<RecoveryPoint?>(null) }
    OutlinedButton(onClick = { opened = true }, enabled = vm.recoveryPoints.isNotEmpty() && !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(TextKey.UNDO_IMPORTANT_OPERATION)) }
    Text(tr(TextKey.IMPORTS_MERGES_DELETIONS_AND_BATCH_OPERATIONS_SAVE_LOCAL_RECOVERY), fontSize = 13.sp, color = Petrol)
    if (opened) AlertDialog(onDismissRequest = { opened = false }, title = { Text(tr(TextKey.RECENT_IMPORTANT_OPERATIONS)) }, text = {
        Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            vm.recoveryPoints.forEach { point ->
                Column {
                    Text(point.label, fontWeight = FontWeight.Medium)
                    Text(tr(TextKey.INVENTORY_RECORDS_AFFECTED, InventoryViewModel.formatHistoryTime(point.at), point.records), fontSize = 12.sp)
                    TextButton(onClick = { opened = false; chosen = point }, enabled = !point.undone && !vm.busy) { Text(if (point.undone) tr(TextKey.UNDONE) else tr(TextKey.UNDO_THIS_OPERATION)) }
                }
                HorizontalDivider()
            }
        }
    }, confirmButton = { TextButton(onClick = { opened = false }) { Text(tr(TextKey.CLOSE)) } })
    chosen?.let { point -> ConfirmDialog(tr(TextKey.UNDO_THIS_OPERATION_2), tr(TextKey.INVENTORY_RECORDS_AFFECTED_ONLY_THIS_OPERATION_IS_REVERSED_UNRELATED, point.label, InventoryViewModel.formatHistoryTime(point.at), point.records), tr(TextKey.CONFIRM_UNDO), { chosen = null }) {
        chosen = null; vm.undoRecovery(point, onUndone)
    } }
}

@Composable
internal fun ExportPage(vm: InventoryViewModel, onExport: (String) -> Unit, backups: BackupActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tr(TextKey.EXPORT), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(tr(TextKey.DETAILS_AND_SUMMARIES_COVER_ONLY_THE_CURRENT_WAREHOUSE), color = Color(0xFF526775), fontSize = 14.sp)
        Button(onClick = { onExport("xlsx") }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(tr(TextKey.EXPORT_EXCEL_DETAILS_AND_SUMMARY)) }
        OutlinedButton(onClick = { onExport("csv") }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(tr(TextKey.EXPORT_CSV_ZIP)) }
        Text(tr(TextKey.WHEN_OPENING_CSV_IN_EXCEL_SET_THE_BOM_AND), fontSize = 13.sp, color = Color(0xFF526775))
        HorizontalDivider()
        Text(tr(TextKey.FULL_BACKUP), fontSize = 19.sp, fontWeight = FontWeight.Medium)
        Text(tr(TextKey.INCLUDES_ALL_COUNTRIES_AND_WAREHOUSES_VOIDED_RECORDS_AND_CHANGE), fontSize = 14.sp)
        FilledTonalButton(onClick = { onExport("backup") }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(tr(TextKey.SAVE_FULL_BACKUP)) }
        Text(tr(TextKey.LAST_SUCCESSFUL_SAVE, vm.lastExport), fontSize = 13.sp, color = Color(0xFF526775))
        AutomaticBackupPanel(vm, backups)
        vm.message?.let { Text(it, color = Teal, fontSize = 13.sp) }
        Text(tr(TextKey.AT_THE_END_OF_EACH_DAY_COPY_THE_EXCEL), fontSize = 14.sp, color = Petrol)
    }
}
