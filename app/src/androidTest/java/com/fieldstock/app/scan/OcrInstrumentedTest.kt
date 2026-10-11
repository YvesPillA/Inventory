package com.fieldstock.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Exercises the actual bundled Latin model against generated labels; no camera or photos required. */
@RunWith(AndroidJUnit4::class)
class OcrInstrumentedTest {
    private val appContext = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun bundledModelPreservesBomLeadingZeroes() {
        val expected = "000123A"
        val recognized = recognizePrintedLabel(expected)
        assertEquals(expected, recognized.trim())
        assertEquals(expected, OcrCandidates.extract(recognized, IdentifierField.BOM).single().value)
    }

    @Test
    fun bundledModelPreservesMaximumLengthSnAndHyphen() {
        val expected = "ABCDEFG-1234567"
        val recognized = recognizePrintedLabel(expected)
        assertEquals(expected, recognized.trim())
        assertEquals(expected, OcrCandidates.extract(recognized, IdentifierField.SN).single().value)
    }

    private fun recognizePrintedLabel(text: String): String {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 128f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isSubpixelText = true
        }
        val padding = 80
        val metrics = paint.fontMetrics
        val width = ceil(paint.measureText(text).toDouble()).toInt() + padding * 2
        val height = ceil((metrics.descent - metrics.ascent).toDouble()).toInt() + padding * 2
        val bitmap = Bitmap.createBitmap(appContext.resources.displayMetrics, width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(text, padding.toFloat(), padding - metrics.ascent, paint)
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
        // Recycling follows completion even if await times out; the worker may still hold the pixels.
        task.addOnCompleteListener { if (!bitmap.isRecycled) bitmap.recycle() }
        try {
            return Tasks.await(task, 60, TimeUnit.SECONDS).text
        } finally {
            recognizer.close()
        }
    }
}
