package com.fieldstock.app

import com.fieldstock.app.i18n.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldstock.app.backup.AutomaticBackup
import com.fieldstock.app.backup.AutomaticBackups
import java.util.Locale

internal data class BackupActions(
    val save: (AutomaticBackup) -> Unit,
    val share: (AutomaticBackup) -> Unit,
    val restore: (AutomaticBackup) -> Unit,
)

@Composable
internal fun AutomaticBackupPanel(vm: InventoryViewModel, actions: BackupActions, settings: Boolean = false) {
    var opened by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (settings) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(TextKey.DAILY_FULL_BACKUP), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Switch(vm.automaticBackupEnabled, vm::setAutomaticBackup, enabled = !vm.busy,
                    modifier = Modifier.semantics { contentDescription = tr(TextKey.DAILY_FULL_BACKUP) })
            }
        } else Text(tr(TextKey.AUTOMATIC_FULL_BACKUPS), style = MaterialTheme.typography.titleMedium)
        Text(if (vm.automaticBackupEnabled) tr(TextKey.SAVE_ALL_INVENTORY_ON_THE_FIRST_USE_EACH_DAY, AutomaticBackups.KEEP)
            else tr(TextKey.DAILY_BACKUP_IS_OFF_YOU_CAN_STILL_BACK_UP), fontSize = 13.sp, color = Petrol)
        Text(vm.automaticBackupEntries.firstOrNull()?.let { tr(TextKey.LATEST_LOCAL_BACKUP, InventoryViewModel.formatHistoryTime(it.at)) }
            ?: tr(TextKey.NO_LOCAL_FULL_BACKUPS_YET), fontSize = 13.sp)
        vm.automaticBackupWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { vm.checkDailyBackup(force = true) }, enabled = !vm.busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(TextKey.BACK_UP_NOW)) }
            OutlinedButton(onClick = { opened = true }, enabled = !vm.busy && vm.automaticBackupEntries.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(TextKey.VIEW_LOCAL_BACKUPS)) }
        }
        Text(tr(TextKey.SAVE_A_COPY_OR_SHARE_A_BACKUP_TO_A), fontSize = 12.sp, color = Petrol)
    }
    if (opened) AlertDialog(
        onDismissRequest = { opened = false },
        title = { Text(tr(TextKey.LOCAL_FULL_BACKUPS)) },
        text = {
            Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(TextKey.INCLUDES_ALL_COUNTRIES_WAREHOUSES_VOIDED_RECORDS_AND_CHANGE_HISTORY), fontSize = 13.sp)
                vm.automaticBackupEntries.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(InventoryViewModel.formatHistoryTime(entry.at), fontWeight = FontWeight.Medium)
                        Text("${entry.day} · ${String.format(Locale.ROOT, "%.1f", entry.bytes / 1024.0)} KB", fontSize = 12.sp)
                        FlowRowActions(
                            save = { opened = false; actions.save(entry) },
                            share = { opened = false; actions.share(entry) },
                            restore = { opened = false; actions.restore(entry) },
                            enabled = !vm.busy,
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { opened = false }) { Text(tr(TextKey.CLOSE)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowActions(save: () -> Unit, share: () -> Unit, restore: () -> Unit, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = save, enabled = enabled) { Text(tr(TextKey.SAVE_AS)) }
        TextButton(onClick = share, enabled = enabled) { Text(tr(TextKey.SHARE)) }
        TextButton(onClick = restore, enabled = enabled) { Text(tr(TextKey.RESTORE)) }
    }
}
