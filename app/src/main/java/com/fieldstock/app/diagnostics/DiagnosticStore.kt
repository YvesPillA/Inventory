package com.fieldstock.app.diagnostics

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.ArrayDeque

/** Private, bounded JSON-lines history; logging never opens or changes the inventory database. */
class DiagnosticStore(private val directory: File, private val maxBytes: Int = 512 * 1024, private val archives: Int = 3) {
    init { require(maxBytes > 0 && archives in 1..8) }
    private fun file(index: Int) = File(directory, if (index == 0) "events.jsonl" else "events.$index.jsonl")

    @Synchronized fun append(level: String, event: String, fields: Map<String, Any?> = emptyMap(), stack: String = "", thread: String = Thread.currentThread().name) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create diagnostic log directory" }
        val data = JSONObject()
        fields.entries.take(24).forEach { (key,value) ->
            val sensitive = key.contains(Regex("(?i)password|passcode|secret|token|authorization|(^|_)key($|_)"))
            data.put(key.take(80), if (sensitive) "[redacted]" else value?.toString()?.take(1024) ?: JSONObject.NULL)
        }
        val record = JSONObject().put("at", Instant.now().toString()).put("level",level.take(12))
            .put("event",event.take(160)).put("thread",thread.take(160)).put("data",data)
            .put("stack",stack.take(16_000))
        val bytes = (record.toString()+"\n").toByteArray(Charsets.UTF_8)
        if (file(0).length() + bytes.size > maxBytes && file(0).exists()) {
            file(archives).delete()
            for (index in archives - 1 downTo 0) if (file(index).exists()) check(file(index).renameTo(file(index+1))) { "Cannot rotate diagnostic log" }
        }
        java.io.FileOutputStream(file(0),true).use { it.write(bytes) }
    }

    @Synchronized fun lines(level: String = "", query: String = "", limit: Int = 300): List<String> {
        require(limit in 1..1000)
        val result = ArrayDeque<String>()
        for (index in archives downTo 0) {
            val path = file(index)
            if (!path.isFile) continue
            path.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
                val record = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                if (level.isNotEmpty() && record.optString("level") != level) return@forEach
                if (query.isNotEmpty() && !line.contains(query.take(200),ignoreCase=true)) return@forEach
                val display = "${record.optString("at")}  ${record.optString("level")}  ${record.optString("event")}\n${record.optJSONObject("data") ?: "{}"}" +
                    record.optString("stack").takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
                result.addLast(display)
                while (result.size > limit) result.removeFirst()
            } }
        }
        return result.toList().asReversed()
    }
    @Synchronized fun copies(): Map<String, ByteArray> = (archives downTo 0).map(::file).filter { it.isFile }.associate { it.name to it.readBytes() }
    @Synchronized fun clear() { for (index in 0..archives) { val path=file(index); check(!path.exists() || path.delete()) { "Cannot clear diagnostic log" } } }
}
