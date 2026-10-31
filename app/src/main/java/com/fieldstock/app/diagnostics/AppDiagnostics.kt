package com.fieldstock.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.fieldstock.app.i18n.AppLanguage
import com.fieldstock.app.model.InventorySnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AppDiagnostics {
    const val BUILD_REVISION = "2.4.4-hotfix-audit-20261030"
    @Volatile private var store: DiagnosticStore? = null
    @Volatile var lastFailure: String? = null; private set
    private val writer = ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(512),
        { task -> Thread(task,"fieldstock-diagnostics").apply { isDaemon=true } },ThreadPoolExecutor.DiscardOldestPolicy())
    fun initialize(context: Context) { if (store == null) synchronized(this) { if (store == null) store=DiagnosticStore(File(context.filesDir,"diagnostics")) } }
    fun event(name: String, fields: Map<String,Any?> = emptyMap(), level: String = "INFO", failure: Throwable? = null) {
        val current=store ?: return
        val thread=Thread.currentThread().name
        val stack=failure?.let(Log::getStackTraceString).orEmpty()
        writer.execute { try { current.append(level,name,fields,stack,thread) } catch (e: Exception) { lastFailure=e.javaClass.simpleName+": "+e.message } }
    }
    fun crash(thread: Thread, failure: Throwable) {
        runCatching { store?.append("ERROR","process.uncaught_exception",mapOf("type" to failure.javaClass.name),Log.getStackTraceString(failure),thread.name) }
    }
    fun flush() { writer.submit {}.get(5,TimeUnit.SECONDS) }
    fun lines(level: String, query: String): List<String> { flush(); return store?.lines(level,query).orEmpty() }
    fun clear() { flush(); store?.clear(); event("diagnostics.cleared") }
    fun uriInfo(uri: android.net.Uri?) = mapOf("scheme" to uri?.scheme,"provider" to uri?.authority,
        "document_hash" to uri?.toString()?.let { text -> java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) } })

    @Suppress("DEPRECATION")
    fun environment(context: Context, snapshot: InventorySnapshot): JSONObject {
        val pkg=context.packageManager.getPackageInfo(context.packageName,0)
        val storage=StatFs(context.filesDir.absolutePath)
        val app=JSONObject().put("package",context.packageName).put("version",pkg.versionName)
            .put("revision", BUILD_REVISION)
            .put("versionCode",if(Build.VERSION.SDK_INT>=28)pkg.longVersionCode else pkg.versionCode.toLong())
            .put("language",AppLanguage.current.tag).put("debuggable",(context.applicationInfo.flags and 2)!=0)
        val device=JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("android",Build.VERSION.RELEASE)
            .put("sdk",Build.VERSION.SDK_INT).put("securityPatch",Build.VERSION.SECURITY_PATCH).put("abis",JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("timeZone",java.time.ZoneId.systemDefault().id).put("locale",java.util.Locale.getDefault().toLanguageTag())
        val counts=JSONObject().put("countries",snapshot.countries.size).put("warehouses",snapshot.warehouses.size)
            .put("records",snapshot.records.size).put("voided",snapshot.records.count { it.voided }).put("changes",snapshot.changes.size)
        val result=JSONObject().put("format","fieldstock-diagnostics").put("version",1).put("at",Instant.now().toString())
            .put("app",app).put("device",device).put("inventory_counts",counts)
            .put("storage_free_bytes",storage.availableBytes).put("heap_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
            .put("camera_permission",context.checkSelfPermission(android.Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
            .put("logger_error",lastFailure ?: JSONObject.NULL)
        if(Build.VERSION.SDK_INT>=30) runCatching {
            val exits=(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getHistoricalProcessExitReasons(context.packageName,0,5)
            result.put("recent_process_exits",JSONArray().also { array -> exits.forEach { exit ->
                array.put(JSONObject().put("at",exit.timestamp).put("reason",exit.reason).put("status",exit.status).put("description",exit.description))
            } })
        }.onFailure { result.put("process_exit_error",it.javaClass.simpleName) }
        return result
    }

    /** Restricted to this app process, with a bounded time and output. */
    private fun systemLog(context: Context): ByteArray {
        val temp=File.createTempFile("diagnostic-logcat-",".txt",context.cacheDir)
        var process: Process?=null
        return try {
            process=ProcessBuilder("logcat","-d","--pid=${android.os.Process.myPid()}","-v","threadtime","-t","1500")
                .redirectErrorStream(true).redirectOutput(temp).start()
            if(!process.waitFor(3,TimeUnit.SECONDS)) { process.destroyForcibly(); "App logcat timed out. Persistent operation logs are included.\n".toByteArray() }
            else temp.inputStream().use { input ->
                val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                while(out.size()<512*1024) { val count=input.read(buffer,0,minOf(buffer.size,512*1024-out.size())); if(count<0)break; out.write(buffer,0,count) }
                out.toByteArray()
            }
        } catch(e: Exception) { ("App logcat unavailable: "+e.javaClass.simpleName+": "+e.message+"\n").toByteArray() }
        finally { process?.destroy(); temp.delete() }
    }

    fun writeBundle(context: Context, snapshot: InventorySnapshot, output: OutputStream) {
        event("diagnostics.export.started"); flush()
        val environment=environment(context,snapshot).toString(2).toByteArray(Charsets.UTF_8)
        val logs=store?.copies().orEmpty()
        val logcat=systemLog(context)
        ZipOutputStream(object:java.io.FilterOutputStream(output){ override fun close(){ flush() } }).use { zip ->
            fun put(name:String,bytes:ByteArray){ zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() }
            put("environment.json",environment)
            logs.forEach { (name,bytes) -> put("operations/$name",bytes) }
            put("app-logcat.txt",logcat)
            put("README.txt","FieldStock diagnostics\nUTC timestamps. Persistent operation logs are bounded to four rolling files. App logcat is limited to the current app process. Android 11+ may supply recent app process exit reasons in environment.json.\n".toByteArray())
            zip.finish()
        }
        event("diagnostics.export.completed",mapOf("log_files" to logs.size))
    }
}
