package com.fieldstock.app.scan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the image supplied to OCR, rather than injecting an already recognized note. */
@RunWith(AndroidJUnit4::class)
class ScanCropAuditInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<ScanActivity>? = null
    private var previousContinuous = false

    @Before fun singleCapture() {
        val preferences = context.getSharedPreferences("fieldstock", 0)
        previousContinuous = preferences.getBoolean("continuous_scan", false)
        preferences.edit().putBoolean("continuous_scan", false).commit()
    }

    @After fun close() {
        scenario?.close()
        context.getSharedPreferences("fieldstock", 0).edit().putBoolean("continuous_scan", previousContinuous).commit()
    }

    private fun crop(field: String): Pair<Bitmap, Bitmap> {
        val scene = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        Canvas(scene).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 230f, 1000f, 270f, Paint().apply { color = Color.RED })
            drawRect(0f, 730f, 1000f, 770f, Paint().apply { color = Color.BLUE })
        }
        scenario = ActivityScenario.launch(Intent(context, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_FIELD, field))
        var captured: Bitmap? = null
        scenario!!.onActivity { captured = it.cropRecognitionFrame(scene) }
        return scene to requireNotNull(captured)
    }

    private fun colors(bitmap: Bitmap) = (0 until bitmap.height).map { bitmap.getPixel(bitmap.width / 2, it) }.toSet()

    @Test fun notesIncludeBothOuterLinesInsideTheLargeViewfinder() {
        val (scene, captured) = crop("NOTE")
        try {
            val visible = colors(captured)
            assertTrue("Top note line must reach OCR", Color.RED in visible)
            assertTrue("Bottom note line must reach OCR", Color.BLUE in visible)
            assertFalse(scene.isRecycled)
        } finally { captured.recycle(); scene.recycle() }
    }

    @Test fun singleIdentifierStillUsesTheNarrowViewfinder() {
        val (scene, captured) = crop("BOM")
        try {
            assertEquals(setOf(Color.WHITE), colors(captured))
            assertFalse(scene.isRecycled)
        } finally { captured.recycle(); scene.recycle() }
    }
}
