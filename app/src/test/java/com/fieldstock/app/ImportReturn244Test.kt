package com.fieldstock.app

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.fieldstock.app.data.InventoryRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ImportReturn244Test {
    @Test fun selectedDocumentWhileInitializingIsQueuedInsteadOfDiscarded() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.deleteDatabase(InventoryRepository.DATABASE_NAME)
        app.getSharedPreferences("fieldstock",0).edit().clear().putBoolean("automatic_backup",false).commit()
        val file = File(app.cacheDir,"selected-while-busy.csv").apply {
            writeText("Type,BOM,SN,Quantity,Condition,Note,PN\nPart,000aB,,7,Normal,queued,\n")
        }
        val vm = InventoryViewModel(app)
        val store = ViewModelStore().apply { put("test",vm) }
        try {
            assertTrue(vm.busy)
            vm.previewImport(Uri.fromFile(file))
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && (vm.busy || vm.importRows == null)) {
                Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
            }
            assertEquals("Selected document must survive the busy startup period",1,vm.importRows?.size)
            assertEquals("000aB",vm.importRows!!.single().bom)
        } finally { store.clear(); file.delete(); app.deleteDatabase(InventoryRepository.DATABASE_NAME) }
    }
}
