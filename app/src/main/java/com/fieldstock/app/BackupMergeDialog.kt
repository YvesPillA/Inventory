package com.fieldstock.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldstock.app.backup.*
import com.fieldstock.app.i18n.*
import com.fieldstock.app.model.*

@Composable
internal fun BackupMergeDialog(vm: InventoryViewModel, candidate: InventorySnapshot) {
    var acceptedCountries by remember(candidate) { mutableStateOf(emptySet<String>()) }
    var warehouseChoices by remember(candidate) { mutableStateOf(emptyMap<String, BackupWarehouseChoice>()) }
    var mode by remember(candidate) { mutableStateOf(DuplicateMode.NEW_ENTRY) }
    val countries = remember(candidate, vm.snapshot) { BackupMerge.countryConflicts(vm.snapshot, candidate) }
    val warehouses = remember(candidate, vm.snapshot) { BackupMerge.warehouseConflicts(vm.snapshot, candidate) }
    val choices = BackupMergeChoices(acceptedCountries, warehouseChoices, mode)
    val prepared = remember(candidate, vm.snapshot, choices) { runCatching { BackupMerge.prepare(vm.snapshot, candidate, choices) } }
    val dismiss = { vm.restoreCandidate = null }
    AlertDialog(onDismissRequest = dismiss, title = { Text(tr(TextKey.MERGE_THIS_BACKUP)) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()).testTag("backup-merge-options"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(TextKey.BACKUP_MERGE_SUMMARY, candidate.warehouses.size, candidate.records.size), fontSize = 13.sp)
            vm.restoreDuplicateNotice(candidate).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 13.sp) }
            if (countries.isNotEmpty() || warehouses.isNotEmpty()) Text(tr(TextKey.BACKUP_RESOLUTION_HELP), color = Petrol)
            countries.forEach { conflict ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(conflict.incoming.id in acceptedCountries, { checked ->
                        acceptedCountries = if (checked) acceptedCountries + conflict.incoming.id else acceptedCountries - conflict.incoming.id
                    }, enabled = !vm.busy, modifier = Modifier.testTag("backup-country-${conflict.incoming.id}"))
                    Text(tr(TextKey.BACKUP_USE_COUNTRY, conflict.existing.name), fontSize = 14.sp)
                }
            }
            warehouses.forEach { conflict ->
                val selected = warehouseChoices[conflict.incoming.id]
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(TextKey.BACKUP_WAREHOUSE_CONFLICT, conflict.country, conflict.incoming.name), color = Petrol, fontSize = 14.sp)
                    listOf(BackupWarehouseAction.MERGE to TextKey.BACKUP_MERGE_WAREHOUSE, BackupWarehouseAction.RENAME to TextKey.BACKUP_RENAME_WAREHOUSE).forEach { (action, label) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected?.action == action, {
                                warehouseChoices = warehouseChoices + (conflict.incoming.id to BackupWarehouseChoice(action, selected?.name.orEmpty()))
                            }, enabled = !vm.busy, modifier = Modifier.testTag("backup-${action.name.lowercase()}-${conflict.incoming.id}"))
                            Text(tr(label), fontSize = 14.sp)
                        }
                    }
                    if (selected?.action == BackupWarehouseAction.RENAME) OutlinedTextField(selected.name, { name ->
                        warehouseChoices = warehouseChoices + (conflict.incoming.id to selected.copy(name = name))
                    }, label = { Text(tr(TextKey.WAREHOUSE_NAME)) }, enabled = !vm.busy, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("backup-rename-name-${conflict.incoming.id}"))
                }
            }
            // The same two choices used by CSV imports and warehouse merges.
            if (candidate.warehouses.any { incoming -> vm.snapshot.warehouses.any { it.id == incoming.id } } ||
                warehouses.any { warehouseChoices[it.incoming.id]?.action == BackupWarehouseAction.MERGE }) {
                DuplicateModePicker(mode) { mode = it }
            }
            prepared.getOrNull()?.takeIf { it.skipped > 0 }?.let { Text(tr(TextKey.BACKUP_ALREADY_IMPORTED, it.skipped), fontSize = 13.sp) }
            if (acceptedCountries.isNotEmpty() || warehouseChoices.isNotEmpty()) prepared.exceptionOrNull()?.let {
                Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (prepared.isSuccess) {
                vm.restoreCandidate = null; vm.duplicateOnRestore = false
                vm.restoreBackup(candidate, choices)
            }
        }, enabled = !vm.busy && prepared.isSuccess, modifier = Modifier.testTag("backup-confirm")) { Text(tr(TextKey.CHECK_AND_MERGE)) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(tr(TextKey.CANCEL)) } })
}
