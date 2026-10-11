package com.fieldstock.app.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.fieldstock.app.model.IdentifierRules
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.roundToInt

/**
 * On-device label OCR. Launch with extra "field" = "BOM" or "SN".
 * Only a deliberate confirmation returns RESULT_OK, with extras "value" and "raw".
 * Preview snapshots are processed in memory and are never written to disk.
 */
class ScanActivity : ComponentActivity() {
    private var field = IdentifierField.BOM
    private var hasPermission by mutableStateOf(false)
    private var permissionDenied by mutableStateOf(false)
    private var cameraReady by mutableStateOf(false)
    private var torchAvailable by mutableStateOf(false)
    private var torchEnabled by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var showResult by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var rawText by mutableStateOf("")
    private var candidates by mutableStateOf(emptyList<OcrCandidate>())
    private var editedValue by mutableStateOf("")
    private var selectedCandidate by mutableStateOf<OcrCandidate?>(null)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var boundPreview: Preview? = null
    private var previewView: PreviewView? = null
    private var recognizer: TextRecognizer? = null
    private var cameraGeneration = 0
    private var recognitionGeneration = 0
    private var activityAlive = true
    private var activityForeground = false

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        field = if (intent.getStringExtra(EXTRA_FIELD).equals("SN", ignoreCase = true)) IdentifierField.SN else IdentifierField.BOM
        hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (savedInstanceState?.getBoolean("showResult") == true) {
            rawText = savedInstanceState.getString("rawText").orEmpty()
            editedValue = savedInstanceState.getString("editedValue").orEmpty()
            candidates = OcrCandidates.extract(rawText, field)
            selectedCandidate = candidates.firstOrNull { it.value == savedInstanceState.getString("selectedValue") }
            showResult = true
        }
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF125BCB),
                    onPrimary = Color.White,
                    background = Color(0xFFF4F7FC),
                    surface = Color.White,
                    onSurface = Color(0xFF172B45),
                    onSurfaceVariant = Color(0xFF52647A),
                    error = Color(0xFFB3261E),
                ),
            ) {
                ScannerScreen()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("showResult", showResult)
        outState.putString("rawText", rawText)
        outState.putString("editedValue", editedValue)
        outState.putString("selectedValue", selectedCandidate?.value)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        activityForeground = true
        hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) permissionDenied = false
    }

    override fun onStop() {
        activityForeground = false
        try {
            camera?.cameraControl?.enableTorch(false)
        } catch (_: Exception) {
            // A camera revoked or disconnected while backgrounding must not crash the form.
        }
        torchEnabled = false
        // An in-flight result must not replace the screen after leaving the scanner.
        recognitionGeneration++
        busy = false
        super.onStop()
    }

    override fun onDestroy() {
        activityAlive = false
        recognitionGeneration++
        unbindCamera()
        recognizer?.close()
        recognizer = null
        super.onDestroy()
    }

    @Composable
    private fun ScannerScreen() {
        Scaffold(
            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
            topBar = {
                Surface(color = Color.White) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text("扫描 ${field.name} 编号", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("本机识别 · 确认后填入", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                        }
                        HorizontalDivider(color = Color(0xFFE0E7F0))
                    }
                }
            },
            bottomBar = {
                if (hasPermission || showResult) {
                    Surface(color = Color.White, shadowElevation = 4.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (showResult) ResultActions() else CameraActions()
                        }
                    }
                }
            },
        ) { padding ->
            when {
                showResult -> ReviewResult(Modifier.padding(padding))
                !hasPermission -> PermissionScreen(Modifier.padding(padding))
                else -> CameraStage(Modifier.padding(padding))
            }
        }
    }

    @Composable
    private fun PermissionScreen(modifier: Modifier) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("用相机读取标签编号", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(
                if (permissionDenied) "相机权限未开启。你可以再次授权，或返回盘库表单手动输入。"
                else "对准标签上的印刷数字和字母。识别在手机上完成，无需联网。",
                fontSize = 16.sp,
                lineHeight = 25.sp,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { permissionRequest.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(if (permissionDenied) "再次申请相机权限" else "允许使用相机") }
            if (permissionDenied && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                OutlinedButton(
                    onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("到设置开启相机") }
            }
            TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回手动输入") }
        }
    }

    @Composable
    private fun CameraStage(modifier: Modifier) {
        DisposableEffect(Unit) { onDispose { unbindCamera() } }
        Column(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF122338))) {
                AndroidView(
                    factory = { context ->
                        PreviewView(context).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewView = this
                            bindCamera(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Viewfinder(Modifier.fillMaxSize())
                Text(
                    "将 ${field.name} 一整行放入框内",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                        .background(Color(0xB3122338), RoundedCornerShape(8.dp)).padding(12.dp),
                )
                if (busy) {
                    Box(Modifier.fillMaxSize().background(Color(0x66122338)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = Color.White)
                            Text("正在识别框内文字…", color = Color.White)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(Color(0xFFF4F7FC)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    if (field == IdentifierField.SN) "SN 含一个 -，字母和数字合计最多 14 位。"
                    else "BOM 支持数字和英文字母，最多 14 位。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }

    @Composable
    private fun Viewfinder(modifier: Modifier) {
        Canvas(modifier) {
            val left = size.width * ROI_LEFT
            val top = size.height * ROI_TOP
            val right = size.width * ROI_RIGHT
            val bottom = size.height * ROI_BOTTOM
            val shade = Color.Black.copy(alpha = 0.48f)
            drawRect(shade, Offset.Zero, Size(size.width, top))
            drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
            drawRect(shade, Offset(0f, top), Size(left, bottom - top))
            drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
            drawRect(Color.White.copy(alpha = 0.8f), Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
            val corner = minOf(28.dp.toPx(), (bottom - top) / 3)
            val stroke = 5.dp.toPx()
            val marker = Color(0xFF66C4FF)
            listOf(
                Triple(Offset(left, top), 1f, 1f),
                Triple(Offset(right, top), -1f, 1f),
                Triple(Offset(left, bottom), 1f, -1f),
                Triple(Offset(right, bottom), -1f, -1f),
            ).forEach { (origin, x, y) ->
                drawLine(marker, origin, origin + Offset(corner * x, 0f), stroke)
                drawLine(marker, origin, origin + Offset(0f, corner * y), stroke)
            }
        }
    }

    @Composable
    private fun CameraActions() {
        if (!cameraReady && error != null) {
            TextButton(
                onClick = { previewView?.let { bindCamera(it) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("重新打开相机") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { toggleTorch() },
                enabled = cameraReady && torchAvailable && !busy,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(if (cameraReady && !torchAvailable) "无补光灯" else if (torchEnabled) "关闭补光" else "打开补光") }
            Button(
                onClick = { recognizeFrame() },
                enabled = cameraReady && !busy,
                modifier = Modifier.weight(1.6f).heightIn(min = 52.dp),
            ) { Text(if (busy) "识别中…" else "识别框内编号", fontWeight = FontWeight.Bold) }
        }
        TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回手动输入") }
    }

    @Composable
    private fun ReviewResult(modifier: Modifier) {
        val validation = validationError()
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("核对标签后再填入", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("注意 O / 0、I / 1。点击候选后可以修改；保持前导零。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (candidates.isEmpty()) {
                Surface(color = Color(0xFFFFF1D6), shape = RoundedCornerShape(8.dp)) {
                    Text("没有找到符合格式的 ${field.name}。请重扫，或在下方手动输入。", Modifier.padding(14.dp))
                }
            } else {
                Text(if (candidates.size > 1) "发现 ${candidates.size} 个候选，请点选正确编号" else "识别候选", fontWeight = FontWeight.SemiBold)
                candidates.forEach { candidate ->
                    val selected = selectedCandidate == candidate
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (selected) Color(0xFFE8F0FE) else Color.White, RoundedCornerShape(8.dp))
                            .border(if (selected) 2.dp else 1.dp, if (selected) Color(0xFF125BCB) else Color(0xFFCDD8E7), RoundedCornerShape(8.dp))
                            .clickable { selectedCandidate = candidate; editedValue = candidate.value }
                            .heightIn(min = 56.dp).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(candidate.value, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(if (selected) "已选择" else "点选", color = Color(0xFF125BCB), fontSize = 13.sp)
                        }
                        if (candidate.corrections.isNotEmpty()) {
                            Text("原文：${candidate.raw}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            candidate.corrections.forEach { Text(it, fontSize = 13.sp, color = Color(0xFF805600)) }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = editedValue,
                onValueChange = { editedValue = it },
                label = { Text("最终 ${field.name} 编号") },
                supportingText = {
                    Text(validation ?: if (field == IdentifierField.SN) "格式有效，请与标签逐字核对。" else "格式有效，请核对前导零和字母。")
                },
                isError = editedValue.isNotEmpty() && validation != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("本次识别原文", fontWeight = FontWeight.SemiBold)
            Surface(color = Color(0xFFEAF0F7), shape = RoundedCornerShape(8.dp)) {
                Text(rawText.ifBlank { "未识别到文字" }, modifier = Modifier.fillMaxWidth().padding(14.dp), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    @Composable
    private fun ResultActions() {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { rescan() },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text("重新扫描") }
            Button(
                onClick = {
                    if (validationError() == null) {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, editedValue).putExtra(EXTRA_RAW, rawText))
                        finish()
                    }
                },
                enabled = validationError() == null,
                modifier = Modifier.weight(1.6f).heightIn(min = 52.dp),
            ) { Text("确认并填入", fontWeight = FontWeight.Bold) }
        }
    }

    private fun validationError(): String? = when (field) {
        IdentifierField.BOM -> IdentifierRules.validateBom(editedValue)
        IdentifierField.SN -> IdentifierRules.validateSn(editedValue)
    }

    private fun rescan() {
        recognitionGeneration++
        busy = false
        error = null
        showResult = false
        hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) permissionDenied = true
    }

    private fun bindCamera(view: PreviewView) {
        val generation = ++cameraGeneration
        cameraReady = false
        error = null
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!activityAlive || generation != cameraGeneration || showResult || !hasPermission) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                boundPreview?.let { cameraProvider.unbind(it) }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                boundPreview = preview
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                torchAvailable = camera?.cameraInfo?.hasFlashUnit() == true
                // The stream may still be starting; recognizeFrame handles a missing snapshot safely.
                cameraReady = true
            } catch (_: Exception) {
                cameraReady = false
                error = "无法打开后置相机，请关闭其他相机应用后重试，或返回手动输入。"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        cameraGeneration++
        cameraReady = false
        torchEnabled = false
        torchAvailable = false
        try {
            camera?.cameraControl?.enableTorch(false)
            boundPreview?.let { provider?.unbind(it) }
        } catch (_: Exception) {
            // Lifecycle teardown must not prevent returning to manual entry.
        }
        boundPreview = null
        camera = null
        previewView = null
    }

    private fun toggleTorch() {
        val activeCamera = camera ?: return
        val desired = !torchEnabled
        try {
            val future = activeCamera.cameraControl.enableTorch(desired)
            future.addListener({
                if (!activityAlive || !activityForeground || camera !== activeCamera) return@addListener
                try {
                    future.get()
                    torchEnabled = desired
                } catch (_: Exception) {
                    error = "补光灯暂不可用，请调整现场光线后重试。"
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {
            error = "补光灯暂不可用，请调整现场光线后重试。"
        }
    }

    private fun recognizeFrame() {
        if (busy || showResult || !cameraReady) return
        val snapshot = previewView?.bitmap
        if (snapshot == null || snapshot.width < 2 || snapshot.height < 2) {
            error = "相机画面尚未就绪，请稍后再次点击识别。"
            return
        }
        val crop = try {
            val left = (snapshot.width * ROI_LEFT).roundToInt().coerceIn(0, snapshot.width - 1)
            val top = (snapshot.height * ROI_TOP).roundToInt().coerceIn(0, snapshot.height - 1)
            val right = (snapshot.width * ROI_RIGHT).roundToInt().coerceIn(left + 1, snapshot.width)
            val bottom = (snapshot.height * ROI_BOTTOM).roundToInt().coerceIn(top + 1, snapshot.height)
            Bitmap.createBitmap(snapshot, left, top, right - left, bottom - top)
        } catch (_: Exception) {
            snapshot.recycle()
            error = "无法读取取景画面，请重新扫描。"
            return
        }
        if (crop !== snapshot) snapshot.recycle()
        val generation = ++recognitionGeneration
        busy = true
        error = null
        try {
            val activeRecognizer = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
            // PreviewView.bitmap already follows the displayed rotation and cropping.
            activeRecognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { result ->
                    if (!activityAlive || generation != recognitionGeneration) return@addOnSuccessListener
                    busy = false
                    if (result.text.isBlank()) {
                        error = "未识别到文字。请靠近标签、保持清晰后重试，也可返回手动输入。"
                    } else {
                        rawText = result.text
                        candidates = OcrCandidates.extract(rawText, field)
                        selectedCandidate = candidates.singleOrNull()
                        editedValue = selectedCandidate?.value.orEmpty()
                        showResult = true
                    }
                }
                .addOnFailureListener {
                    if (!activityAlive || generation != recognitionGeneration) return@addOnFailureListener
                    busy = false
                    error = "识别失败。请调整光线后重试，或返回手动输入。"
                }
                .addOnCompleteListener { if (!crop.isRecycled) crop.recycle() }
        } catch (_: Exception) {
            if (!crop.isRecycled) crop.recycle()
            busy = false
            error = "识别暂不可用，请重试或返回手动输入。"
        }
    }

    companion object {
        const val EXTRA_FIELD = "field"
        const val EXTRA_VALUE = "value"
        const val EXTRA_RAW = "raw"
        private const val ROI_LEFT = 0.08f
        private const val ROI_TOP = 0.36f
        private const val ROI_RIGHT = 0.92f
        private const val ROI_BOTTOM = 0.64f
    }
}
