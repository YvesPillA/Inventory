package com.fieldstock.app.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fieldstock.app.model.IdentifierRules
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/**
 * On-device label OCR. Launch with extra "field" = "BOM" or "SN".
 * Only a deliberate confirmation returns RESULT_OK, with extras "value" and "raw".
 * The cropped review photo is temporary private cache, removed on rescan/exit.
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
    private var reviewPhoto by mutableStateOf<Bitmap?>(null)
    private var reviewImageId: String? = null
    private val reviewImages by lazy { OcrReviewImages(this) }
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var boundPreview: Preview? = null
    private var previewView: PreviewView? = null
    private var recognizer: TextRecognizer? = null
    private var cameraGeneration = 0
    private var recognitionGeneration = 0
    private var activityAlive = true
    private var activityForeground by mutableStateOf(false)
    private var continuous by mutableStateOf(false)
    private var livePaused by mutableStateOf(false)
    private var liveFrame by mutableStateOf<LiveScanFrame?>(null)
    private val liveTracker = LiveCodeTracker()
    internal var processingFrame by mutableStateOf(false); private set
    private var liveOperation = 0L
    private var lastLiveStart = 0L
    internal var liveRevision = 0L; private set
    private val currentRoi: CodeBox get() = if (continuous) LIVE_ROI else SINGLE_ROI
    internal val visibleLiveCodes: List<TrackedCode> get() =
        liveFrame?.codes.orEmpty().filter { livePaused || it.stable }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        field = if (intent.getStringExtra(EXTRA_FIELD).equals("SN", ignoreCase = true)) IdentifierField.SN else IdentifierField.BOM
        continuous = savedInstanceState?.getBoolean("continuous")
            ?: getSharedPreferences("fieldstock", 0).getBoolean("continuous_scan", false)
        hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        reviewImages.clearExpired()
        if (savedInstanceState?.getBoolean("showResult") == true) {
            rawText = savedInstanceState.getString("rawText").orEmpty()
            editedValue = savedInstanceState.getString("editedValue").orEmpty()
            candidates = OcrCandidates.extract(rawText, field)
            selectedCandidate = candidates.firstOrNull { it.value == savedInstanceState.getString("selectedValue") }
            reviewImageId = savedInstanceState.getString("reviewImageId")
            reviewPhoto = reviewImages.load(reviewImageId)
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
        outState.putString("reviewImageId", reviewImageId)
        outState.putBoolean("continuous", continuous)
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
        clearLive()
        busy = false
        super.onStop()
    }

    override fun onDestroy() {
        activityAlive = false
        recognitionGeneration++
        unbindCamera()
        recognizer?.close()
        recognizer = null
        if (isFinishing) reviewImages.delete(reviewImageId)
        reviewPhoto = null
        super.onDestroy()
    }

    @Composable
    private fun ScannerScreen() {
        LaunchedEffect(continuous, activityForeground, hasPermission, cameraReady, showResult, livePaused) {
            if (continuous && activityForeground && hasPermission && cameraReady && !showResult && !livePaused) {
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    if (liveFrame?.let { now - it.capturedAt > LIVE_FRESH_MS } == true) {
                        liveFrame = null
                        liveTracker.reset()
                    }
                    if (!busy && !processingFrame && now - lastLiveStart >= 500L) recognizeLiveFrame()
                    delay(100L)
                }
            }
        }
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
                        if (!showResult) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!continuous) Button(onClick = { changeScanMode(false) }, enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("单次识别") }
                            else OutlinedButton(onClick = { changeScanMode(false) }, enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("单次识别") }
                            if (continuous) Button(onClick = { changeScanMode(true) }, enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("持续识别") }
                            else OutlinedButton(onClick = { changeScanMode(true) }, enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("持续识别") }
                        }
                    }
                }
            },
            bottomBar = {
                if (hasPermission || showResult || liveFrame != null) {
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
                !hasPermission && liveFrame == null -> PermissionScreen(Modifier.padding(padding))
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
                if (hasPermission) AndroidView(
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
                val frozen = liveFrame
                if (frozen != null && (livePaused || !hasPermission)) {
                    Image(frozen.scene.asImageBitmap(), contentDescription = "已暂停的扫描画面",
                        contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                }
                Viewfinder(Modifier.fillMaxSize())
                if (continuous && frozen != null) LiveScanOverlay(visibleLiveCodes, frozen.roi, !busy, ::chooseLiveCode)
                if (!continuous) Text(
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
            if (continuous) LiveCodeList(visibleLiveCodes, livePaused, !busy, ::chooseLiveCode)
            Column(Modifier.fillMaxWidth().background(Color(0xFFF4F7FC)).padding(horizontal = 16.dp, vertical = if (continuous) 4.dp else 12.dp)) {
                if (!continuous) {
                  Text(
                    if (field == IdentifierField.SN) "SN 含一个 -，字母和数字合计最多 14 位。"
                    else "BOM 支持数字和英文字母，最多 14 位。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }

    @Composable
    private fun Viewfinder(modifier: Modifier) {
        Canvas(modifier) {
            val roi = currentRoi
            val left = size.width * roi.left
            val top = size.height * roi.top
            val right = size.width * roi.right
            val bottom = size.height * roi.bottom
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
                onClick = { if (continuous) pauseOrResumeLive() else recognizeFrame() },
                enabled = !busy && if (continuous) liveFrame != null else cameraReady && !processingFrame,
                modifier = Modifier.weight(1.6f).heightIn(min = 52.dp),
            ) { Text(if (busy) "正在打开核对…" else if (continuous) if (livePaused) "继续识别" else "暂停画面" else "识别框内编号", fontWeight = FontWeight.Bold) }
        }
        if (!continuous) TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回手动输入") }
    }

    @Composable
    private fun ReviewResult(modifier: Modifier) {
        val validation = validationError()
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("核对标签后再填入", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("对照照片核对 0/O、1/I、8/B，保留前导零和大小写。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            reviewPhoto?.let { photo -> LabelPhoto(photo) }
                ?: Text("标签照片暂不可用。请对照实物核对，或重新扫描。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
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
            CharacterReview(editedValue) { editedValue = it }
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
        clearLive()
        busy = false
        error = null
        showResult = false
        reviewImages.delete(reviewImageId)
        reviewImageId = null
        reviewPhoto = null
        hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) permissionDenied = true
    }

    internal fun changeScanMode(live: Boolean) {
        if (busy) return
        clearLive()
        continuous = live
        error = null
        getSharedPreferences("fieldstock", 0).edit().putBoolean("continuous_scan", live).apply()
    }

    private fun clearLive() {
        recognitionGeneration++
        liveOperation++
        livePaused = false
        liveFrame = null
        liveTracker.reset()
        lastLiveStart = 0L
    }

    internal fun pauseOrResumeLive() {
        val frame = liveFrame ?: return
        if (livePaused) {
            clearLive()
            error = null
        } else if (SystemClock.elapsedRealtime() - frame.capturedAt <= LIVE_FRESH_MS) {
            recognitionGeneration++
            liveOperation++
            livePaused = true
            error = null
        } else {
            clearLive()
            error = "画面已更新，请停稳后再暂停"
        }
    }

    internal fun publishLiveRecognition(scene: Bitmap, crop: Bitmap, text: String, lines: List<LiveTextLine>,
        roi: CodeBox = LIVE_ROI, capturedAt: Long = SystemClock.elapsedRealtime()) {
        liveFrame = LiveScanFrame(scene, crop, text, roi, liveTracker.update(LiveOcr.locate(lines, field)), capturedAt)
        liveRevision++
        if (SystemClock.elapsedRealtime() - capturedAt > LIVE_FRESH_MS) {
            livePaused = true
            error = "本次识别较慢，已暂停画面；可点选编号或继续识别"
        } else error = null
    }

    private fun recognizeLiveFrame() {
        if (!continuous || livePaused || busy || processingFrame || showResult || !activityForeground || !cameraReady) return
        lastLiveStart = SystemClock.elapsedRealtime()
        if (previewView?.previewStreamState?.value != PreviewView.StreamState.STREAMING) return
        val preview = previewView?.bitmap ?: return
        if (preview.width < 2 || preview.height < 2) { preview.recycle(); return }
        val factor = minOf(1f, 1280f / maxOf(preview.width, preview.height))
        val scene = if (factor < 1f) Bitmap.createScaledBitmap(preview, (preview.width * factor).roundToInt().coerceAtLeast(2),
            (preview.height * factor).roundToInt().coerceAtLeast(2), true).also { if (it !== preview) preview.recycle() } else preview
        val roi = LIVE_ROI
        val crop = cropFrame(scene, roi)
        if (crop == null) { scene.recycle(); return }
        val capturedAt = lastLiveStart
        val generation = recognitionGeneration
        val operation = ++liveOperation
        processingFrame = true
        var retained = false
        try {
            val activeRecognizer = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
            activeRecognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { result ->
                    if (!activityAlive || !activityForeground || generation != recognitionGeneration ||
                        operation != liveOperation || !continuous || livePaused || showResult) return@addOnSuccessListener
                    publishLiveRecognition(scene, crop, result.text, result.locatedLines(crop.width, crop.height), roi, capturedAt)
                    retained = true
                }
                .addOnFailureListener {
                    if (activityAlive && generation == recognitionGeneration && operation == liveOperation) {
                        liveFrame = null
                        liveTracker.reset()
                        error = "持续识别暂未成功，请调整光线、停稳后重试"
                    }
                }
                .addOnCompleteListener {
                    processingFrame = false
                    if (!retained) { if (!crop.isRecycled) crop.recycle(); if (!scene.isRecycled) scene.recycle() }
                }
        } catch (_: Exception) {
            processingFrame = false
            crop.recycle(); scene.recycle()
            error = "持续识别暂不可用，可切换单次识别或手动输入"
        }
    }

    internal fun chooseLiveCode(hit: TrackedCode) {
        if (busy) return
        val frame = liveFrame ?: return
        val current = visibleLiveCodes.find { it.id == hit.id && it.code.candidate.value == hit.code.candidate.value }
        if (current == null || (!livePaused && SystemClock.elapsedRealtime() - frame.capturedAt > LIVE_FRESH_MS)) {
            error = "这个编号已移出画面，请重新点选，或先暂停画面"
            return
        }
        livePaused = true
        recognitionGeneration++
        liveOperation++
        val generation = recognitionGeneration
        busy = true
        val picture = frame.crop.copy(Bitmap.Config.ARGB_8888, false)
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { runCatching { reviewImages.save(picture) }.getOrNull() }
                val photo = withContext(Dispatchers.IO) { reviewImages.load(saved) }
                if (!activityAlive || generation != recognitionGeneration) { reviewImages.delete(saved); return@launch }
                reviewRecognition(frame.raw, saved, photo)
                selectedCandidate = candidates.firstOrNull { it.value == current.code.candidate.value }
                editedValue = current.code.candidate.value
            } finally { picture.recycle() }
        }
    }

    private fun cropFrame(scene: Bitmap, roi: CodeBox): Bitmap? = runCatching {
        val left = (scene.width * roi.left).roundToInt().coerceIn(0, scene.width - 1)
        val top = (scene.height * roi.top).roundToInt().coerceIn(0, scene.height - 1)
        val right = (scene.width * roi.right).roundToInt().coerceIn(left + 1, scene.width)
        val bottom = (scene.height * roi.bottom).roundToInt().coerceIn(top + 1, scene.height)
        Bitmap.createBitmap(scene, left, top, right - left, bottom - top)
    }.getOrNull()

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
        if (busy || processingFrame || showResult || !cameraReady) return
        val snapshot = previewView?.bitmap
        if (snapshot == null || snapshot.width < 2 || snapshot.height < 2) {
            error = "相机画面尚未就绪，请稍后再次点击识别。"
            return
        }
        val crop = try {
            val left = (snapshot.width * SINGLE_ROI.left).roundToInt().coerceIn(0, snapshot.width - 1)
            val top = (snapshot.height * SINGLE_ROI.top).roundToInt().coerceIn(0, snapshot.height - 1)
            val right = (snapshot.width * SINGLE_ROI.right).roundToInt().coerceIn(left + 1, snapshot.width)
            val bottom = (snapshot.height * SINGLE_ROI.bottom).roundToInt().coerceIn(top + 1, snapshot.height)
            Bitmap.createBitmap(snapshot, left, top, right - left, bottom - top)
        } catch (_: Exception) {
            snapshot.recycle()
            error = "无法读取取景画面，请重新扫描。"
            return
        }
        if (crop !== snapshot) snapshot.recycle()
        val generation = ++recognitionGeneration
        busy = true
        processingFrame = true
        error = null
        try {
            val activeRecognizer = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
            // PreviewView.bitmap already follows the displayed rotation and cropping.
            activeRecognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { result ->
                    if (!activityAlive || generation != recognitionGeneration) return@addOnSuccessListener
                    if (result.text.isBlank()) {
                        busy = false
                        error = "未识别到文字。请靠近标签、保持清晰后重试，也可返回手动输入。"
                    } else {
                        // The ML task releases crop on completion; the IO job owns this separate copy.
                        val picture = crop.copy(Bitmap.Config.ARGB_8888, false)
                        lifecycleScope.launch {
                            try {
                                val saved = withContext(Dispatchers.IO) {
                                    runCatching { reviewImages.save(picture) }.getOrNull()
                                }
                                val photo = withContext(Dispatchers.IO) { reviewImages.load(saved) }
                                if (!activityAlive || generation != recognitionGeneration) {
                                    reviewImages.delete(saved)
                                    return@launch
                                }
                                reviewRecognition(result.text, saved, photo)
                            } finally {
                                picture.recycle()
                            }
                        }
                    }
                }
                .addOnFailureListener {
                    if (!activityAlive || generation != recognitionGeneration) return@addOnFailureListener
                    busy = false
                    error = "识别失败。请调整光线后重试，或返回手动输入。"
                }
                .addOnCompleteListener { processingFrame = false; if (!crop.isRecycled) crop.recycle() }
        } catch (_: Exception) {
            if (!crop.isRecycled) crop.recycle()
            busy = false
            processingFrame = false
            error = "识别暂不可用，请重试或返回手动输入。"
        }
    }

    /** Shared by the recognition callback and device UI checks using generated labels. */
    internal fun reviewRecognition(text: String, imageId: String?, photo: Bitmap?) {
        clearLive()
        if (imageId != reviewImageId) reviewImages.delete(reviewImageId)
        reviewImageId = imageId
        reviewPhoto = photo
        rawText = text
        candidates = OcrCandidates.extract(rawText, field)
        selectedCandidate = candidates.singleOrNull()
        editedValue = selectedCandidate?.value.orEmpty()
        busy = false
        showResult = true
    }

    companion object {
        const val EXTRA_FIELD = "field"
        const val EXTRA_VALUE = "value"
        const val EXTRA_RAW = "raw"
        internal val SINGLE_ROI = CodeBox(0.08f, 0.36f, 0.92f, 0.64f)
        internal val LIVE_ROI = CodeBox(0.06f, 0.12f, 0.94f, 0.88f)
        internal const val LIVE_FRESH_MS = 2_000L
    }
}

@Composable
private fun LabelPhoto(photo: Bitmap) {
    var scale by remember(photo) { mutableStateOf(1f) }
    var offset by remember(photo) { mutableStateOf(Offset.Zero) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("本次标签局部照片", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("重置照片") }
        }
        Box(Modifier.fillMaxWidth().height(150.dp).background(Color.White).clipToBounds()
            .pointerInput(photo) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    val x = size.width * (scale - 1) / 2
                    val y = size.height * (scale - 1) / 2
                    offset = Offset((offset.x + pan.x).coerceIn(-x, x), (offset.y + pan.y).coerceIn(-y, y))
                }
            }) {
            Image(photo.asImageBitmap(), contentDescription = "本次识别标签照片，可双指放大",
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                })
        }
        Text("双指放大、拖动核对。照片仅暂存，退出扫描后清除。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
