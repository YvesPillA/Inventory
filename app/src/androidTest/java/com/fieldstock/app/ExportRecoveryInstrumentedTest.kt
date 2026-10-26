package com.fieldstock.app

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldstock.app.export.InventoryFiles
import com.fieldstock.app.model.InventorySnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the same persisted request used when a document picker outlives its ViewModel. */
@RunWith(AndroidJUnit4::class)
class ExportRecoveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun preparedBackupSurvivesViewModelDestructionAndExportsThePreparedSnapshot() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val preferences = application.getSharedPreferences("fieldstock", 0)
        val pendingFile = File(application.cacheDir, "pending-export.json")
        val outputFile = File.createTempFile("export-recovery-", ".json", application.cacheDir)
        val storeA = ViewModelStore()
        val storeB = ViewModelStore()
        // Export only changes these UI preferences, never inventory rows. Preserve prior values.
        val preservedKeys = listOf("task", "warehouse", "last_export", "backup_at", "last_export_at", "last_export_kind", "last_export_label")
        val previousPreferences = preservedKeys.associateWith { preferences.all[it] }
        clearPending(application)

        try {
            lateinit var modelA: InventoryViewModel
            onMain { modelA = ViewModelProvider(storeA, ViewModelProvider.AndroidViewModelFactory(application))[InventoryViewModel::class.java] }
            waitOnTestThread(modelA, "initial inventory load") { !it.busy }

            lateinit var preparedSnapshot: InventorySnapshot
            val pickerReady = CountDownLatch(1)
            onMain {
                preparedSnapshot = modelA.snapshot
                modelA.prepareExport("backup", false) { pickerReady.countDown() }
            }
            assertTrue("Preparing the export did not reach the document picker", pickerReady.await(30, TimeUnit.SECONDS))
            waitOnTestThread(modelA, "export preparation") { !it.busy }
            assertTrue("The prepared snapshot must be persisted before opening the picker", pendingFile.length() > 0L)
            assertEquals("backup", preferences.getString("pending_kind", null))
            onMain { storeA.clear() }

            lateinit var modelB: InventoryViewModel
            onMain { modelB = ViewModelProvider(storeB, ViewModelProvider.AndroidViewModelFactory(application))[InventoryViewModel::class.java] }
            waitOnTestThread(modelB, "recreated inventory load") { !it.busy }
            onMain {
                assertNull("The recreated ViewModel must use the persisted request, not an in-memory request", modelB.pendingExport)
                modelB.export(Uri.fromFile(outputFile))
            }
            waitOnTestThread(modelB, "export after recreation") {
                !it.busy && !preferences.contains("pending_kind") && outputFile.length() > 0L
            }

            assertTrue("The recovered request did not write a backup", outputFile.length() > 0L)
            val recovered = outputFile.inputStream().use(InventoryFiles::readBackup)
            assertEquals("Recreated export must use the exact prepared snapshot", preparedSnapshot, recovered)
            assertFalse("A successful export must clear the persisted snapshot", pendingFile.exists())
            assertFalse("A successful export must clear pending preferences", preferences.contains("pending_kind"))
        } finally {
            onMain { storeA.clear(); storeB.clear() }
            clearPending(application)
            val editor = preferences.edit()
            previousPreferences.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    else -> throw AssertionError("Unexpected preference type for $key")
                }
            }
            editor.commit()
            outputFile.delete()
        }
    }

    private fun onMain(block: () -> Unit) { instrumentation.runOnMainSync { block() } }

    /** Poll on the instrumentation thread; every state read happens on Android's main thread. */
    private fun waitOnTestThread(model: InventoryViewModel, reason: String, complete: (InventoryViewModel) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            var error: String? = null
            onMain { ready = complete(model); error = model.error }
            assertNull("ViewModel failed during $reason: $error", error)
            if (ready) return
            SystemClock.sleep(25L)
        }
        fail("Timed out waiting for $reason")
    }

    private fun clearPending(application: Application) {
        application.getSharedPreferences("fieldstock", 0).edit()
            .remove("pending_kind").remove("pending_task").remove("pending_warehouse").remove("pending_language").commit()
        File(application.cacheDir, "pending-export.json").delete()
    }
}
