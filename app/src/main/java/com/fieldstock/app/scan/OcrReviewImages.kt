package com.fieldstock.app.scan

import com.fieldstock.app.i18n.*

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.UUID

/** A temporary picture of the exact OCR region. It never becomes an inventory attachment. */
internal class OcrReviewImages(context: Context) {
    private val directory = File(context.cacheDir, "ocr-review")

    fun save(bitmap: Bitmap): String {
        check(directory.isDirectory || directory.mkdirs()) { tr(TextKey.COULD_NOT_CACHE_THE_LABEL_PHOTO) }
        val id = "review-${UUID.randomUUID()}.jpg"
        val file = File(directory, id)
        val ratio = minOf(1f, MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val picture = if (ratio < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
        try {
            file.outputStream().use { check(picture.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            return id
        } catch (failure: Exception) {
            file.delete()
            throw failure
        } finally {
            if (picture !== bitmap) picture.recycle()
        }
    }

    fun load(id: String?): Bitmap? {
        val file = resolve(id) ?: return null
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..MAX_EDGE || bounds.outHeight !in 1..MAX_EDGE) return null
        return BitmapFactory.decodeFile(file.path)
    }

    fun delete(id: String?) { resolve(id)?.delete() }

    fun clearExpired() {
        val before = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles().orEmpty().filter { it.lastModified() < before && NAME.matches(it.name) }.forEach { it.delete() }
    }

    private fun resolve(id: String?): File? = id?.takeIf(NAME::matches)?.let { File(directory, it) }

    companion object {
        const val MAX_EDGE = 1600
        private val NAME = Regex("review-[a-f0-9-]{36}\\.jpg")
    }
}
