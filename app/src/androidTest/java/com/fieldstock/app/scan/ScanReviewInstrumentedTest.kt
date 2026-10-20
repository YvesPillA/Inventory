package com.fieldstock.app.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScanReviewInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<ScanActivity>? = null
    @After fun close() { scenario?.close() }

    private fun launch(field: String, code: String): String {
        val image = Bitmap.createBitmap(900, 240, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            drawText(code, 32f, 150f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 72f; typeface = Typeface.MONOSPACE
            })
        }
        val store = OcrReviewImages(context)
        val id = store.save(image)
        image.recycle()
        scenario = ActivityScenario.launchActivityForResult(Intent(context, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_FIELD, field))
        scenario!!.onActivity { it.reviewRecognition("$field: $code", id, store.load(id)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("核对标签后再填入").fetchSemanticsNodes().isNotEmpty() }
        return id
    }

    private fun finalValue(): String = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
        .config[SemanticsProperties.EditableText].text

    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 3_000)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.cacheDir, "ui-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun bomCharacterChoiceChangesOnePositionOnlyAndConfirmReturnsOriginalOcrText() {
        val id = launch("BOM", "00O1I8")
        compose.onNodeWithContentDescription("本次识别标签照片，可双指放大").assertIsDisplayed()
        assertEquals("00O1I8", finalValue())
        capture("ocr-photo")
        compose.onNodeWithContentDescription("核对第 3 位 O").performScrollTo().performClick()
        compose.onNodeWithText("数字 0").performScrollTo().performClick()
        assertEquals("0001I8", finalValue())
        capture("ocr-character-review")
        compose.onNodeWithText("确认并填入").assertIsDisplayed().performClick()
        val result = scenario!!.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertEquals("0001I8", result.resultData.getStringExtra(ScanActivity.EXTRA_VALUE))
        assertEquals("BOM: 00O1I8", result.resultData.getStringExtra(ScanActivity.EXTRA_RAW))
        compose.waitUntil(10_000) { !File(context.cacheDir, "ocr-review/$id").exists() }
    }

    @Test fun photoAndEditedMaximumSnSurviveActivityRecreation() {
        val id = launch("SN", "000000O-I888888")
        compose.onNodeWithContentDescription("核对第 7 位 O").performScrollTo().performClick()
        compose.onNodeWithText("数字 0").performScrollTo().performClick()
        assertEquals("0000000-I888888", finalValue())
        scenario!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("核对标签后再填入").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("0000000-I888888", finalValue())
        compose.onNodeWithContentDescription("本次识别标签照片，可双指放大").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("确认并填入").assertIsDisplayed().performClick()
        assertEquals("0000000-I888888", scenario!!.result.resultData.getStringExtra(ScanActivity.EXTRA_VALUE))
        compose.waitUntil(10_000) { !File(context.cacheDir, "ocr-review/$id").exists() }
    }

    @Test fun rescanClearsPreviousPhotoAndDoesNotConfirmAnything() {
        val id = launch("BOM", "B008")
        compose.onNodeWithText("重新扫描").performClick()
        compose.onNodeWithText("核对标签后再填入").assertDoesNotExist()
        assertFalse(File(context.cacheDir, "ocr-review/$id").exists())
        scenario!!.close()
        scenario = null
    }

    @Test fun missingNewPhotoNeverDisplaysPreviousLabelPicture() {
        val id = launch("BOM", "B008")
        scenario!!.onActivity { it.reviewRecognition("BOM: 000123", null, null) }
        compose.onNodeWithContentDescription("本次识别标签照片，可双指放大").assertDoesNotExist()
        compose.onNodeWithText("标签照片暂不可用", substring = true).assertIsDisplayed()
        assertEquals("000123", finalValue())
        assertFalse(File(context.cacheDir, "ocr-review/$id").exists())
    }
}
