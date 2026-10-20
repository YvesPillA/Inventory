package com.fieldstock.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OcrReviewImagesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun imageSurvivesStoreRecreationAndIsRemovedOnExit() {
        val photo = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val store = OcrReviewImages(context)
        val id = store.save(photo)
        val reopened = OcrReviewImages(context)
        assertEquals(80, reopened.load(id)!!.width)
        reopened.delete(id)
        assertNull(reopened.load(id))
        photo.recycle()
    }
    @Test fun largeCameraImageIsBoundedForReviewAndSourceIsNotRecycled() {
        val photo = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        val store = OcrReviewImages(context)
        val id = store.save(photo)
        assertEquals(1600, store.load(id)!!.width)
        assertEquals(800, store.load(id)!!.height)
        assertFalse(photo.isRecycled)
        store.delete(id); photo.recycle()
    }
    @Test fun stalePhotosAreRemovedAndArbitraryPathsCannotBeReadOrDeleted() {
        val store = OcrReviewImages(context)
        val photo = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val id = store.save(photo)
        val file = File(context.cacheDir, "ocr-review/$id")
        assertTrue(file.setLastModified(System.currentTimeMillis() - 48 * 60 * 60 * 1000L))
        store.clearExpired()
        assertNull(store.load(id))
        assertNull(store.load("../pending-export.json"))
        store.delete("../pending-export.json")
        photo.recycle()
    }
}
