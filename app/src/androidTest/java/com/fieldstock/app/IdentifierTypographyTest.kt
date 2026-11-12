package com.fieldstock.app

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentifierTypographyTest {
    @Test fun bundledGlyphsHaveCircularOAndSlashedZeroAtEveryWeight() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (resource in listOf(R.font.identifier_regular, R.font.identifier_semibold, R.font.identifier_bold)) {
            val paint = Paint().apply {
                typeface = requireNotNull(ResourcesCompat.getFont(context, resource))
                textSize = 1000f
            }
            fun region(value: String): Pair<Region, RectF> {
                val path = Path()
                paint.getTextPath(value, 0, value.length, 0f, 0f, path)
                val bounds = RectF().also { path.computeBounds(it, true) }
                return Region().apply { setPath(path, Region(-2000, -2000, 2000, 2000)) } to bounds
            }
            val (letterO, oBounds) = region("O")
            assertEquals(oBounds.width(), oBounds.height(), 1f)
            assertFalse(letterO.contains(oBounds.centerX().toInt(), oBounds.centerY().toInt()))
            val (zero, zeroBounds) = region("0")
            assertTrue(zero.contains(zeroBounds.centerX().toInt(), zeroBounds.centerY().toInt()))
            val (one, oneBounds) = region("1")
            // The base extends to both sides of the stem.
            assertTrue(one.contains((oneBounds.left + 5).toInt(), (oneBounds.bottom - 5).toInt()))
            assertTrue(one.contains((oneBounds.right - 5).toInt(), (oneBounds.bottom - 5).toInt()))
            val (lowerI, iBounds) = region("i")
            assertTrue(lowerI.contains(iBounds.centerX().toInt(), (iBounds.top + 10).toInt()))
            assertTrue(paint.hasGlyph("l"))
        }
    }
}
