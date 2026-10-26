package com.fieldstock.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fieldstock.app.i18n.*

@Composable
internal fun FirstLanguageChooser(onChoose: (AppLanguage) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Mist) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
            Text("Choose language / 选择语言", style = MaterialTheme.typography.headlineSmall)
            Text("You can change this later in Settings.\n以后可在设置中切换。")
            Button(onClick = { onChoose(AppLanguage.ENGLISH) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("English") }
            OutlinedButton(onClick = { onChoose(AppLanguage.CHINESE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("简体中文") }
        }
    }
}

@Composable
internal fun LanguageSetting(enabled: Boolean, beforeChange: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Language / 语言 · ${if (AppLanguage.current == AppLanguage.ENGLISH) "English" else "简体中文"}")
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text("Choose language / 选择语言") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { language ->
                OutlinedButton(onClick = {
                    open = false
                    if (language != AppLanguage.current) {
                        LanguageSettings.select(activity, language)
                        beforeChange()
                        activity.recreate()
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (language == AppLanguage.ENGLISH) "English" else "简体中文")
                }
            }
        } }, confirmButton = {})
}
