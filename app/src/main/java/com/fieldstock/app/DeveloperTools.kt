package com.fieldstock.app

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.fieldstock.app.diagnostics.AppDiagnostics
import com.fieldstock.app.diagnostics.DeveloperGate
import com.fieldstock.app.i18n.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable internal fun DeveloperEntry(vm: InventoryViewModel) {
    val gate=remember { DeveloperGate(BuildConfig.DEVELOPER_KEY_SHA256) }
    var prompt by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var incorrect by remember { mutableStateOf(false) }
    val interaction=remember { MutableInteractionSource() }
    Text(tr(TextKey.APP_VERSION),fontSize=12.sp,color=androidx.compose.ui.graphics.Color.Gray,
        modifier=Modifier.fillMaxWidth().testTag("app-version").clickable(interactionSource=interaction,indication=null) {
            if(gate.tap()){ key="";incorrect=false;prompt=true;AppDiagnostics.event("developer.key.requested") }
        })
    fun submit() {
        if(gate.accepts(key)){ key="";prompt=false;opened=true;AppDiagnostics.event("developer.unlocked") }
        else { incorrect=true;AppDiagnostics.event("developer.unlock.rejected",level="WARN") }
    }
    if(prompt) AlertDialog(onDismissRequest={prompt=false;key=""},title={Text(tr(TextKey.DEVELOPER_KEY))},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(key,{key=it.take(128);incorrect=false},modifier=Modifier.testTag("developer-key"),singleLine=true,
                visualTransformation=PasswordVisualTransformation(),isError=incorrect,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),
                keyboardActions=KeyboardActions(onDone={submit()}))
            if(incorrect) Text(tr(TextKey.DEVELOPER_KEY_ERROR),color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(onClick={submit()},enabled=key.isNotEmpty()){Text(tr(TextKey.DEVELOPER_ENTER))}},
        dismissButton={TextButton(onClick={prompt=false;key=""}){Text(tr(TextKey.CANCEL))}})
    if(opened) DeveloperConsole(vm) { opened=false;AppDiagnostics.event("developer.closed") }
}

@Composable internal fun DeveloperConsole(vm: InventoryViewModel, onDismiss: () -> Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<String>>(emptyList()) }
    var environment by remember { mutableStateOf("") }
    var device by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }
    fun failed(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;status=tr(TextKey.DIAGNOSTIC_FAILED,e.message ?: e.javaClass.simpleName);AppDiagnostics.event("diagnostics.failed",level="ERROR",failure=e)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri==null) AppDiagnostics.event("diagnostics.save.cancelled")
        else scope.launch {
            working=true
            try {
                val snapshot=vm.snapshot
                withContext(Dispatchers.IO){ context.contentResolver.openOutputStream(uri,"wt")?.use { AppDiagnostics.writeBundle(context,snapshot,it) } ?: error("Cannot open diagnostic output") }
                status=tr(TextKey.DIAGNOSTIC_SAVED);revision++
            } catch(e:Exception){failed(e)} finally{working=false}
        }
    }
    LaunchedEffect(query,level,revision) {
        try {
            val snapshot=vm.snapshot
            val data=withContext(Dispatchers.IO){ AppDiagnostics.lines(level,query) to AppDiagnostics.environment(context,snapshot).toString(2) }
            entries=data.first;environment=data.second
        } catch(e:Exception){failed(e)}
    }
    fun fileName()="FieldStock-diagnostics-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))+".zip"
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false)) {
        Surface(Modifier.fillMaxSize().testTag("developer-console")) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(tr(TextKey.DEVELOPER_MODE),Modifier.weight(1f),fontSize=21.sp,fontWeight=FontWeight.Bold)
                    TextButton(onClick=onDismiss,enabled=!working){Text(tr(TextKey.CLOSE))}
                }
                Text(tr(TextKey.DIAGNOSTIC_HELP),fontSize=12.sp,color=Petrol)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={revision++},enabled=!working,modifier=Modifier.weight(1f)){Text(tr(TextKey.DIAGNOSTIC_REFRESH))}
                    OutlinedButton(onClick={device=!device},modifier=Modifier.weight(1f)){Text(tr(TextKey.DIAGNOSTIC_DEVICE))}
                }
                if(device) SelectionContainer {
                    Text(environment,Modifier.fillMaxWidth().heightIn(max=170.dp).verticalScroll(rememberScrollState()),fontSize=11.sp,fontFamily=FontFamily.Monospace)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={try{save.launch(fileName())}catch(e:Exception){failed(e)}},enabled=!working,modifier=Modifier.weight(1f)){Text(tr(TextKey.DIAGNOSTIC_EXPORT))}
                    OutlinedButton(onClick={scope.launch {
                        working=true
                        try {
                            val snapshot=vm.snapshot
                            val uri=withContext(Dispatchers.IO){
                                val dir=File(context.cacheDir,"shared-backups").apply{check(isDirectory||mkdirs())}
                                val file=File(dir,fileName())
                                file.outputStream().use{AppDiagnostics.writeBundle(context,snapshot,it)}
                                dir.listFiles().orEmpty().filter{it.name.startsWith("FieldStock-diagnostics-")&&it.lastModified()<System.currentTimeMillis()-86_400_000}.forEach{it.delete()}
                                FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                            }
                            val intent=Intent(Intent.ACTION_SEND).apply {type="application/zip";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("Diagnostics",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                            context.startActivity(Intent.createChooser(intent,tr(TextKey.DIAGNOSTIC_SHARE)))
                            revision++
                        }catch(e:Exception){failed(e)}finally{working=false}
                    }},enabled=!working,modifier=Modifier.weight(1f)){Text(tr(TextKey.DIAGNOSTIC_SHARE))}
                }
                OutlinedTextField(query,{query=it.take(200)},label={Text(tr(TextKey.DIAGNOSTIC_SEARCH))},singleLine=true,modifier=Modifier.fillMaxWidth())
                ChoiceRow(listOf("" to tr(TextKey.DIAGNOSTIC_ALL),"WARN" to tr(TextKey.DIAGNOSTIC_WARN),"ERROR" to tr(TextKey.DIAGNOSTIC_ERRORS)),level,true){level=it}
                if(working) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(status.isNotEmpty()) Text(status,fontSize=12.sp,color=Petrol)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("diagnostic-logs"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(entries.isEmpty()) item{Text(tr(TextKey.DIAGNOSTIC_EMPTY),fontSize=13.sp)}
                    items(entries){entry->SelectionContainer{Text(entry,fontSize=11.sp,fontFamily=FontFamily.Monospace)}}
                }
                TextButton(onClick={clear=true},enabled=!working){Text(tr(TextKey.DIAGNOSTIC_CLEAR),color=MaterialTheme.colorScheme.error)}
            }
        }
    }
    if(clear) ConfirmDialog(tr(TextKey.DIAGNOSTIC_CLEAR),tr(TextKey.DIAGNOSTIC_CLEAR_CONFIRM),tr(TextKey.DIAGNOSTIC_CLEAR),{clear=false}) {
        clear=false;scope.launch{working=true;try{withContext(Dispatchers.IO){AppDiagnostics.clear()};revision++}catch(e:Exception){failed(e)}finally{working=false}}
    }
}
