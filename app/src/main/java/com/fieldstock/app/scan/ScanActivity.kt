package com.fieldstock.app.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
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
import com.fieldstock.app.i18n.*
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
 * On-device label OCR. Launch with extra "field" = "BOM", "SN", "PN" or "NOTE".
 * Only a deliberate confirmation returns RESULT_OK, with extras "value" and "raw".
 * The cropped review photo is temporary private cache, removed on rescan/exit.
 */
class ScanActivity : LocalizedActivity() {
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
    private val currentRoi: CodeBox get() = if (continuous || this.field == IdentifierField.NOTE) LIVE_ROI else SINGLE_ROI
    private val fieldLabel: String get() = if (this.field == IdentifierField.NOTE) tr(TextKey.NOTE) else this.field.name
    internal val visibleLiveCodes: List<TrackedCode> get() =
        liveFrame?.codes.orEmpty().filter { livePaused || it.stable }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        field = IdentifierField.entries.firstOrNull { it.name.equals(intent.getStringExtra(EXTRA_FIELD), true) } ?: IdentifierField.BOM
        continuous = savedInstanceState?.getBoolean("continuous")
            ?: getSharedPreferences("fieldstock", 0).getBoolean("continuous_scan", false)
        if (field == IdentifierField.NOTE) continuous = false
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
                            TextButton(onClick = { finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr(TextKey.BACK)) }
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(tr(if (field == IdentifierField.NOTE) TextKey.SCAN else TextKey.SCAN_IDENTIFIER, fieldLabel), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(tr(TextKey.ON_DEVICE_RECOGNITION_REVIEW_BEFORE_FILLING), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                        }
                        HorizontalDivider(color = Color(0xFFE0E7F0))
                        if (!showResult && field != IdentifierField.NOTE) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!continuous) Button(onClick = { changeScanMode(false) }, enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text(tr(TextKey.SINGLE_SCAN)) }
                            else OutlinedButton(onClick = { changeScanMode(false) }, enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text(tr(TextKey.SINGLE_SCAN)) }
                            if (continuous) Button(onClick = { changeScanMode(true) }, enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text(tr(TextKey.CONTINUOUS_SCAN)) }
                            else OutlinedButton(onClick = { changeScanMode(true) }, enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text(tr(TextKey.CONTINUOUS_SCAN)) }
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
            Text(tr(TextKey.READ_LABEL_IDENTIFIERS_WITH_THE_CAMERA), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(
                if (permissionDenied) tr(TextKey.CAMERA_PERMISSION_IS_OFF_ALLOW_ACCESS_AGAIN_OR_RETURN)
                else tr(TextKey.AIM_AT_THE_PRINTED_LETTERS_AND_DIGITS_ON_THE),
                fontSize = 16.sp,
                lineHeight = 25.sp,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { permissionRequest.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(if (permissionDenied) tr(TextKey.REQUEST_CAMERA_PERMISSION_AGAIN) else tr(TextKey.ALLOW_CAMERA_ACCESS)) }
            if (permissionDenied && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                OutlinedButton(
                    onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(tr(TextKey.ENABLE_CAMERA_IN_SETTINGS)) }
            }
            TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(TextKey.ENTER_MANUALLY)) }
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
                    Image(frozen.scene.asImageBitmap(), contentDescription = tr(TextKey.PAUSED_SCAN_FRAME),
                        contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                }
                Viewfinder(Modifier.fillMaxSize())
                if (continuous && frozen != null) LiveScanOverlay(visibleLiveCodes, frozen.roi, !busy, ::chooseLiveCode)
                if (!continuous) Text(
                    if (field == IdentifierField.NOTE) tr(TextKey.NOTES_SCAN_FRAME) else tr(TextKey.PLACE_THE_COMPLETE_LINE_INSIDE_THE_FRAME, fieldLabel),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                        .background(Color(0xB3122338), RoundedCornerShape(8.dp)).padding(12.dp),
                )
                if (busy) {
                    Box(Modifier.fillMaxSize().background(Color(0x66122338)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = Color.White)
                            Text(tr(TextKey.RECOGNIZING_TEXT_INSIDE_THE_FRAME), color = Color.White)
                        }
                    }
                }
            }
            if (continuous) LiveCodeList(visibleLiveCodes, livePaused, !busy, ::chooseLiveCode)
            Column(Modifier.fillMaxWidth().background(Color(0xFFF4F7FC)).padding(horizontal = 16.dp, vertical = if (continuous) 4.dp else 12.dp)) {
                if (!continuous) {
                  Text(
                    when (field) {
                        IdentifierField.SN -> tr(TextKey.SN_CONTAINS_ONE_HYPHEN_AND_UP_TO_14_LETTERS)
                        IdentifierField.PN -> tr(TextKey.PN_FORMAT_HELP)
                        IdentifierField.NOTE -> tr(TextKey.NOTES_SCAN_HELP)
                        IdentifierField.BOM -> tr(TextKey.BOM_SUPPORTS_UP_TO_14_LETTERS_AND_DIGITS)
                    },
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
            ) { Text(tr(TextKey.REOPEN_CAMERA)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { toggleTorch() },
                enabled = cameraReady && torchAvailable && !busy,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(if (cameraReady && !torchAvailable) tr(TextKey.NO_TORCH) else if (torchEnabled) tr(TextKey.TORCH_OFF) else tr(TextKey.TORCH_ON)) }
            Button(
                onClick = { if (continuous) pauseOrResumeLive() else recognizeFrame() },
                enabled = !busy && if (continuous) liveFrame != null else cameraReady && !processingFrame,
                modifier = Modifier.weight(1.6f).heightIn(min = 52.dp),
            ) { Text(if (busy) tr(TextKey.OPENING_REVIEW) else if (continuous) if (livePaused) tr(TextKey.RESUME_SCANNING) else tr(TextKey.PAUSE_FRAME) else if (field == IdentifierField.NOTE) tr(TextKey.RECOGNIZE_TEXT) else tr(TextKey.RECOGNIZE_IDENTIFIER), fontWeight = FontWeight.Bold) }
        }
        if (!continuous) TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(TextKey.ENTER_MANUALLY)) }
    }

    @Composable
    private fun ReviewResult(modifier: Modifier) {
        val validation = validationError()
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(tr(TextKey.CHECK_THE_LABEL_BEFORE_FILLING), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(tr(if (field == IdentifierField.NOTE) TextKey.NOTES_SCAN_HELP else TextKey.COMPARE_WITH_THE_PHOTO_CHECK_0_O_1_I), color = MaterialTheme.colorScheme.onSurfaceVariant)
            reviewPhoto?.let { photo -> LabelPhoto(photo) }
                ?: Text(tr(TextKey.LABEL_PHOTO_UNAVAILABLE_CHECK_THE_PHYSICAL_LABEL_OR_SCAN), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            if (candidates.isEmpty()) {
                Surface(color = Color(0xFFFFF1D6), shape = RoundedCornerShape(8.dp)) {
                    Text(tr(TextKey.NO_VALID_FOUND_SCAN_AGAIN_OR_ENTER_IT_MANUALLY, fieldLabel), Modifier.padding(14.dp))
                }
            } else {
                Text(if (candidates.size > 1) tr(TextKey.FOUND_CANDIDATES_SELECT_THE_CORRECT_IDENTIFIER, candidates.size) else tr(TextKey.RECOGNIZED_CANDIDATES), fontWeight = FontWeight.SemiBold)
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
                            Text(if (selected) tr(TextKey.SELECTED_2) else tr(TextKey.SELECT_2), color = Color(0xFF125BCB), fontSize = 13.sp)
                        }
                        if (candidate.corrections.isNotEmpty()) {
                            Text(tr(TextKey.ORIGINAL, candidate.raw), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            candidate.corrections.forEach { Text(it, fontSize = 13.sp, color = Color(0xFF805600)) }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = editedValue,
                onValueChange = { editedValue = it },
                label = { Text(if (field == IdentifierField.NOTE) tr(TextKey.REVIEW_NOTE) else tr(TextKey.FINAL_IDENTIFIER, fieldLabel)) },
                supportingText = {
                    Text(validation ?: when (field) {
                        IdentifierField.SN -> tr(TextKey.VALID_FORMAT_CHECK_EACH_CHARACTER_AGAINST_THE_LABEL)
                        IdentifierField.NOTE -> tr(TextKey.NOTES_SCAN_HELP)
                        else -> tr(TextKey.VALID_FORMAT_CHECK_LEADING_ZEROS_AND_LETTERS)
                    })
                },
                isError = editedValue.isNotEmpty() && validation != null,
                singleLine = field != IdentifierField.NOTE,
                maxLines = if (field == IdentifierField.NOTE) 8 else 1,
                keyboardOptions = KeyboardOptions(keyboardType = if (field == IdentifierField.NOTE) KeyboardType.Text else KeyboardType.Ascii, autoCorrect = false),
                modifier = Modifier.fillMaxWidth(),
            )
            if (field != IdentifierField.NOTE) CharacterReview(editedValue) { editedValue = it }
            Text(tr(TextKey.RAW_RECOGNIZED_TEXT), fontWeight = FontWeight.SemiBold)
            Surface(color = Color(0xFFEAF0F7), shape = RoundedCornerShape(8.dp)) {
                Text(rawText.ifBlank { tr(TextKey.NO_TEXT_RECOGNIZED) }, modifier = Modifier.fillMaxWidth().padding(14.dp), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
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
            ) { Text(tr(TextKey.SCAN_AGAIN)) }
            Button(
                onClick = {
                    if (validationError() == null) {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, editedValue).putExtra(EXTRA_RAW, rawText))
                        finish()
                    }
                },
                enabled = validationError() == null,
                modifier = Modifier.weight(1.6f).heightIn(min = 52.dp),
            ) { Text(tr(TextKey.CONFIRM_AND_FILL), fontWeight = FontWeight.Bold) }
        }
    }

    private fun validationError(): String? = when (field) {
        IdentifierField.BOM -> IdentifierRules.validateBom(editedValue)
        IdentifierField.SN -> IdentifierRules.validateSn(editedValue)
        IdentifierField.PN -> IdentifierRules.validatePn(editedValue)
        IdentifierField.NOTE -> IdentifierRules.validateScannedNote(editedValue)
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
            error = tr(TextKey.THE_FRAME_CHANGED_HOLD_STILL_BEFORE_PAUSING_AGAIN)
        }
    }

    internal fun publishLiveRecognition(scene: Bitmap, crop: Bitmap, text: String, lines: List<LiveTextLine>,
        roi: CodeBox = LIVE_ROI, capturedAt: Long = SystemClock.elapsedRealtime()) {
        liveFrame = LiveScanFrame(scene, crop, text, roi, liveTracker.update(LiveOcr.locate(lines, field)), capturedAt)
        liveRevision++
        if (SystemClock.elapsedRealtime() - capturedAt > LIVE_FRESH_MS) {
            livePaused = true
            error = tr(TextKey.RECOGNITION_TOOK_LONGER_THAN_EXPECTED_SO_THE_FRAME_WAS)
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
                        error = tr(TextKey.CONTINUOUS_RECOGNITION_FAILED_ADJUST_THE_LIGHTING_HOLD_STILL_AND)
                    }
                }
                .addOnCompleteListener {
                    processingFrame = false
                    if (!retained) { if (!crop.isRecycled) crop.recycle(); if (!scene.isRecycled) scene.recycle() }
                }
        } catch (_: Exception) {
            processingFrame = false
            crop.recycle(); scene.recycle()
            error = tr(TextKey.CONTINUOUS_RECOGNITION_IS_UNAVAILABLE_USE_SINGLE_SCAN_OR_MANUAL)
        }
    }

    internal fun chooseLiveCode(hit: TrackedCode) {
        if (busy) return
        val frame = liveFrame ?: return
        val current = visibleLiveCodes.find { it.id == hit.id && it.code.candidate.value == hit.code.candidate.value }
        if (current == null || (!livePaused && SystemClock.elapsedRealtime() - frame.capturedAt > LIVE_FRESH_MS)) {
            error = tr(TextKey.THIS_IDENTIFIER_HAS_LEFT_THE_FRAME_SELECT_IT_AGAIN)
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

    /** Single captures must read exactly the region shown for the requested field. */
    internal fun cropRecognitionFrame(scene: Bitmap): Bitmap? = cropFrame(scene, currentRoi)

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
                error = tr(TextKey.COULD_NOT_OPEN_THE_REAR_CAMERA_CLOSE_OTHER_CAMERA)
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
                    error = tr(TextKey.TORCH_UNAVAILABLE_ADJUST_THE_LIGHTING_AND_TRY_AGAIN)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {
            error = tr(TextKey.TORCH_UNAVAILABLE_ADJUST_THE_LIGHTING_AND_TRY_AGAIN)
        }
    }

    private fun recognizeFrame() {
        if (busy || processingFrame || showResult || !cameraReady) return
        com.fieldstock.app.diagnostics.AppDiagnostics.event("scan.recognition.started",mapOf("field" to field.name))
        val snapshot = previewView?.bitmap
        if (snapshot == null || snapshot.width < 2 || snapshot.height < 2) {
            snapshot?.recycle()
            error = tr(TextKey.CAMERA_PREVIEW_IS_NOT_READY_TRY_RECOGNIZING_AGAIN_SHORTLY)
            return
        }
        val crop = cropRecognitionFrame(snapshot)
        if (crop == null) {
            snapshot.recycle()
            error = tr(TextKey.COULD_NOT_READ_THE_CAMERA_PREVIEW_SCAN_AGAIN)
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
                        error = tr(TextKey.NO_TEXT_RECOGNIZED_MOVE_CLOSER_AND_KEEP_THE_LABEL)
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
                    com.fieldstock.app.diagnostics.AppDiagnostics.event("scan.recognition.failed",mapOf("field" to field.name),"ERROR",it)
                    busy = false
                    error = tr(TextKey.RECOGNITION_FAILED_ADJUST_THE_LIGHTING_AND_TRY_AGAIN_OR)
                }
                .addOnCompleteListener { processingFrame = false; if (!crop.isRecycled) crop.recycle() }
        } catch (failure: Exception) {
            if (!crop.isRecycled) crop.recycle()
            com.fieldstock.app.diagnostics.AppDiagnostics.event("scan.recognition.failed",mapOf("field" to field.name),"ERROR",failure)
            busy = false
            processingFrame = false
            error = tr(TextKey.RECOGNITION_UNAVAILABLE_TRY_AGAIN_OR_ENTER_IDENTIFIERS_MANUALLY)
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
        com.fieldstock.app.diagnostics.AppDiagnostics.event("scan.review.ready",mapOf("field" to field.name,"candidates" to candidates.size,"characters" to text.length))
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
            Text(tr(TextKey.CROPPED_LABEL_PHOTO), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text(tr(TextKey.RESET_PHOTO)) }
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
            Image(photo.asImageBitmap(), contentDescription = tr(TextKey.RECOGNIZED_LABEL_PHOTO_PINCH_TO_ZOOM),
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                })
        }
        Text(tr(TextKey.PINCH_TO_ZOOM_AND_DRAG_TO_REVIEW_THE_PHOTO), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
