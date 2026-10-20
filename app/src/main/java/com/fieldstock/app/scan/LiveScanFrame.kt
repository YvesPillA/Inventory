package com.fieldstock.app.scan

import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text

internal data class LiveScanFrame(
    val scene: Bitmap,
    val crop: Bitmap,
    val raw: String,
    val roi: CodeBox,
    val codes: List<TrackedCode>,
    val capturedAt: Long,
)

internal fun Text.locatedLines(width: Int, height: Int): List<LiveTextLine> {
    fun android.graphics.Rect.normalized() = CodeBox(left.toFloat() / width, top.toFloat() / height,
        right.toFloat() / width, bottom.toFloat() / height)
    return textBlocks.flatMap { it.lines }.mapNotNull { line ->
        val box = line.boundingBox ?: return@mapNotNull null
        LiveTextLine(line.text, box.normalized(), line.elements.mapNotNull { element ->
            element.boundingBox?.let { LiveTextElement(element.text, it.normalized()) }
        })
    }
}
