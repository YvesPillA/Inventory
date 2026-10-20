package com.fieldstock.app.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveScanInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<ScanActivity>? = null
    @After fun close() { scenario?.close() }

    private data class Label(val scene: Bitmap, val crop: Bitmap, val text: String, val lines: List<LiveTextLine>)

    private fun label(codes: List<String>, field: String = "BOM"): Label {
        val scene = Bitmap.createBitmap(720, 500, Bitmap.Config.ARGB_8888)
        val crop = Bitmap.createBitmap(634, 380, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = if (codes.size <= 3) 42f else 20f; typeface = Typeface.MONOSPACE
        }
        val lines = codes.mapIndexed { index, code ->
            val y = 18f + (index + 0.5f) * 342f / codes.size
            Canvas(crop).apply { if (index == 0) drawColor(Color.WHITE); drawText("$field: $code", 24f, y, paint) }
            LiveTextLine("$field: $code", CodeBox(0.04f, ((y - paint.textSize) / 380).coerceAtLeast(0.01f), 0.9f, y / 380 + 0.01f))
        }
        Canvas(scene).apply { drawColor(Color.DKGRAY); drawBitmap(crop, 43f, 60f, null) }
        return Label(scene, crop, codes.joinToString("\n") { "$field: $it" }, lines)
    }

    private fun show(codes: List<String>, field: String = "BOM", paused: Boolean = true): Label {
        val label = label(codes, field)
        scenario = ActivityScenario.launchActivityForResult(Intent(context, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_FIELD, field))
        compose.onNodeWithText("持续识别").performClick()
        scenario!!.onActivity {
            repeat(2) { _ -> it.publishLiveRecognition(label.scene, label.crop, label.text, label.lines) }
            if (paused) it.pauseOrResumeLive()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("画面编号 1 ${codes.first()}").fetchSemanticsNodes().isNotEmpty() }
        return label
    }

    private fun value() = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 3_000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.cacheDir, "ui-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun aMultipleRowsHaveSeparatedOverlaysAndClickingSecondSelectsOnlyThatCode() {
        val codes = listOf("000123A", "000456B", "000789C")
        show(codes)
        val bounds = codes.mapIndexed { index, code ->
            compose.onNodeWithContentDescription("画面编号 ${index + 1} $code").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        bounds.zipWithNext().forEach { (a, b) -> assertTrue(a.bottom <= b.top) }
        capture("continuous-three-rows")
        compose.onNodeWithContentDescription("画面编号 2 000456B").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("核对标签后再填入").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("000456B", value())
        compose.onNodeWithText("确认并填入").performClick()
        assertEquals(Activity.RESULT_OK, scenario!!.result.resultCode)
        assertEquals("000456B", scenario!!.result.resultData.getStringExtra(ScanActivity.EXTRA_VALUE))
        assertTrue(scenario!!.result.resultData.getStringExtra(ScanActivity.EXTRA_RAW)!!.contains("000789C"))
    }

    @Test fun bDenseRowsRemainInTheCompleteListAndTheLastRowCanBeSelected() {
        val codes = (1..12).map { it.toString().padStart(8, '0') }
        show(codes)
        compose.onNodeWithText("12 个编号", substring = true).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(11)
        capture("continuous-dense-list")
        compose.onNodeWithContentDescription("列表编号 12 00000012").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("最终 BOM 编号").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("00000012", value())
    }

    @Test fun cStaleClickCannotSelectANewTargetEvenWhenTheValueMatches() {
        val label = show(listOf("A123", "B456"))
        scenario!!.onActivity { activity ->
            val old = activity.visibleLiveCodes.first()
            activity.changeScanMode(true)
            repeat(2) { activity.publishLiveRecognition(label.scene, label.crop, label.text, label.lines) }
            activity.pauseOrResumeLive()
            activity.chooseLiveCode(old)
        }
        compose.onNodeWithText("这个编号已移出画面", substring = true).assertIsDisplayed()
        compose.onNodeWithText("核对标签后再填入").assertDoesNotExist()
    }

    @Test fun dResumeAndModeSwitchRemoveOldRows() {
        show(listOf("A123", "B456"))
        compose.onNodeWithText("继续识别").performClick()
        compose.onNodeWithContentDescription("画面编号 1 A123").assertDoesNotExist()
        compose.onNodeWithText("单次识别").performClick()
        compose.onNodeWithContentDescription("列表编号 1 A123").assertDoesNotExist()
        compose.onNodeWithText("持续识别").assertIsDisplayed()
        assertFalse(context.getSharedPreferences("fieldstock", 0).getBoolean("continuous_scan", true))
    }

    @Test fun eMaximumSnSelectedFromListPreservesAllCharactersAndBothSegments() {
        show(listOf("AA-001", "ABCDEFG-1234567", "ZZ-009"), "SN")
        compose.onNodeWithContentDescription("列表编号 2 ABCDEFG-1234567").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("最终 SN 编号").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("ABCDEFG-1234567", value())
        compose.onNodeWithText("确认并填入").performClick()
        assertEquals("ABCDEFG-1234567", scenario!!.result.resultData.getStringExtra(ScanActivity.EXTRA_VALUE))
    }

    @Test fun fSlowRecognitionFreezesItsOwnFrameForSafeSelection() {
        val label = show(listOf("A123", "B456"))
        scenario!!.onActivity { activity ->
            activity.changeScanMode(true)
            activity.publishLiveRecognition(label.scene, label.crop, label.text, label.lines,
                capturedAt = SystemClock.elapsedRealtime() - 5_000)
        }
        compose.onNodeWithText("继续识别").assertIsDisplayed()
        compose.onNodeWithText("本次识别较慢", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("列表编号 2 B456").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("最终 BOM 编号").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("B456", value())
    }

    @Test fun gBundledModelLocatesThreeSeparatePrintedRows() {
        val source = label(listOf("000123A", "000456B", "000789C"))
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(source.crop, 0)), 30, TimeUnit.SECONDS)
            val located = LiveOcr.locate(result.locatedLines(source.crop.width, source.crop.height), IdentifierField.BOM)
            assertEquals(listOf("000123A", "000456B", "000789C"), located.map { it.candidate.value })
            assertTrue(located.zipWithNext().all { (a, b) -> a.bounds.bottom < b.bounds.top })
        } finally { recognizer.close() }
    }

    @Test fun zRealCameraPreviewProcessesRepeatedFramesAndRestartsAfterBackgrounding() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant com.fieldstock.app android.permission.CAMERA")
        ).use { it.readBytes() }
        scenario = ActivityScenario.launch(Intent(context, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_FIELD, "BOM"))
        compose.onNodeWithText("持续识别").performClick()
        fun revision(): Long {
            var revision = 0L
            scenario!!.onActivity { revision = it.liveRevision }
            return revision
        }
        compose.waitUntil(25_000) { revision() >= 1 }
        if (compose.onAllNodesWithText("继续识别").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("继续识别").performClick()
        compose.waitUntil(25_000) { revision() >= 3 }
        var checkedPendingSwitch = false
        compose.waitUntil(25_000) {
            scenario!!.onActivity { activity ->
                if (activity.processingFrame) {
                    activity.changeScanMode(false)
                    assertTrue("Switching modes must not release the actual in-flight frame", activity.processingFrame)
                    activity.changeScanMode(true)
                    assertTrue("Switching back must not enqueue another model task", activity.processingFrame)
                    checkedPendingSwitch = true
                }
            }
            checkedPendingSwitch
        }
        val previous = revision()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(25_000) { revision() > previous }
        capture("continuous-real-camera")
    }
}
