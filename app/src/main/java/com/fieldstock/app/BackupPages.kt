package com.fieldstock.app

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
                Text("每日自动完整备份", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Switch(vm.automaticBackupEnabled, vm::setAutomaticBackup, enabled = !vm.busy,
                    modifier = Modifier.semantics { contentDescription = "每日自动完整备份" })
            }
        } else Text("自动完整备份", style = MaterialTheme.typography.titleMedium)
        Text(if (vm.automaticBackupEnabled) "每天首次使用时保存当时的全部库存，最多保留最近 ${AutomaticBackups.KEEP} 份。收工可点“立即备份”。"
            else "每日自动备份已关闭，仍可手动立即备份。", fontSize = 13.sp, color = Petrol)
        Text(vm.automaticBackupEntries.firstOrNull()?.let { "最近本机备份：${InventoryViewModel.formatHistoryTime(it.at)}" }
            ?: "尚无本机完整备份", fontSize = 13.sp)
        vm.automaticBackupWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { vm.checkDailyBackup(force = true) }, enabled = !vm.busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("立即备份") }
            OutlinedButton(onClick = { opened = true }, enabled = !vm.busy && vm.automaticBackupEntries.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("查看本机备份") }
        }
        Text("另存或分享后可带到电脑、新手机。本机文件会随卸载删除；分享是否完成请在接收端核对。", fontSize = 12.sp, color = Petrol)
    }
    if (opened) AlertDialog(
        onDismissRequest = { opened = false },
        title = { Text("本机完整备份") },
        text = {
            Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("覆盖所有国家、仓库、作废和修改历史。选择时间后另存、分享或预览合并恢复。", fontSize = 13.sp)
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
        confirmButton = { TextButton(onClick = { opened = false }) { Text("关闭") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowActions(save: () -> Unit, share: () -> Unit, restore: () -> Unit, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = save, enabled = enabled) { Text("另存为") }
        TextButton(onClick = share, enabled = enabled) { Text("分享") }
        TextButton(onClick = restore, enabled = enabled) { Text("恢复") }
    }
}
