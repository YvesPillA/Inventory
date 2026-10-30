package com.fieldstock.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fieldstock.app.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class Diagnostics244Test {
    @get:Rule val temporary=TemporaryFolder()
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    @Test fun gateRequiresSixSilentTapsAndExactPrivatelyConfiguredVerifier() {
        val gate=DeveloperGate(hash("test-only-passphrase"))
        repeat(5){assertFalse(gate.tap())};assertTrue(gate.tap());assertFalse(gate.tap())
        assertTrue(gate.accepts("test-only-passphrase"))
        assertFalse(gate.accepts("TEST-ONLY-PASSPHRASE"));assertFalse(gate.accepts(" test-only-passphrase"))
        assertFalse(DeveloperGate("").accepts("test-only-passphrase"))
    }
    @Test fun logRotationKeepsNewestEntriesWithinTheFileBudget() {
        val store=DiagnosticStore(temporary.newFolder(),1024,3)
        repeat(80){store.append("INFO","event.$it",mapOf("data" to "x".repeat(80)))}
        assertEquals(4,store.copies().size)
        assertTrue(store.copies().values.all{it.size<=1024})
        assertTrue(store.lines(limit=2).first().contains("event.79"))
        assertEquals(2,store.lines(limit=2).size)
        assertTrue(store.lines(query="event.0").isEmpty())
    }
    @Test fun logsSurviveReopenPreserveErrorDetailsAndRedactSecretFields() {
        val dir=temporary.newFolder();val store=DiagnosticStore(dir)
        store.append("INFO","import.open",mapOf("name" to "中文 file.xlsx","password" to "test-secret-value","developer_key" to "another-test-secret"))
        store.append("ERROR","import.failed",mapOf("bytes" to 23),"Outer failure\nCaused by: inner failure")
        val reopened=DiagnosticStore(dir)
        assertEquals(2,reopened.lines().size)
        assertTrue(reopened.lines("ERROR","").single().contains("inner failure"))
        val raw=reopened.copies().values.joinToString{it.toString(Charsets.UTF_8)}
        assertFalse(raw.contains("test-secret-value"));assertFalse(raw.contains("another-test-secret"))
        assertTrue(raw.contains("[redacted]"));assertTrue(raw.contains("中文 file.xlsx"))
        reopened.clear();assertTrue(reopened.lines().isEmpty())
    }
    @Test fun diagnosticBundleContainsProcessHistoryAndCountsWithoutStockDetailsAndLeavesCallerOpen() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        AppDiagnostics.initialize(context)
        AppDiagnostics.event("test.import.requested",mapOf("rows" to 400))
        AppDiagnostics.crash(Thread.currentThread(),IllegalStateException("outer-test",IllegalArgumentException("inner-test")))
        val privateNote="PRIVATE_INVENTORY_NOTE_FOR_TEST_244"
        val snapshot=InventorySnapshot(records=listOf(StockRecord("id","task","warehouse",ItemKind.PART,"ABC","",7,"正常",privateNote,"","manual","manual","","",1,1,false)))
        var closed=false
        val output=object:ByteArrayOutputStream(){override fun close(){closed=true;super.close()}}
        AppDiagnostics.writeBundle(context,snapshot,output)
        assertFalse(closed)
        val entries=linkedMapOf<String,ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use{zip->while(true){val entry=zip.nextEntry?:break;entries[entry.name]=zip.readBytes()}}
        assertTrue(entries.containsKey("environment.json"));assertTrue(entries.containsKey("app-logcat.txt"))
        val environment=JSONObject(entries.getValue("environment.json").toString(Charsets.UTF_8))
        assertEquals(1,environment.getJSONObject("inventory_counts").getInt("records"))
        val all=entries.values.joinToString{it.toString(Charsets.UTF_8)}
        assertTrue(all.contains("test.import.requested"));assertTrue(all.contains("inner-test"))
        assertFalse(all.contains(privateNote));assertFalse(all.contains("DEVELOPER_KEY_SHA256"))
    }
}
