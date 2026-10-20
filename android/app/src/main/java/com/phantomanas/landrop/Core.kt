package com.phantomanas.landrop

import android.app.Application
import android.content.Context
import android.util.Log as ALog
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logcat tag "Pam" + a copy in files/log.txt. */
object Log {
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "log.txt").also { if (it.length() > 512 * 1024) it.delete() }
    }

    fun i(tag: String, text: String) = write("I", tag, text, null)
    fun w(tag: String, text: String, e: Throwable? = null) = write("W", tag, text, e)

    private fun write(level: String, tag: String, text: String, e: Throwable?) {
        if (level == "W") ALog.w("Pam", "[$tag] $text", e) else ALog.i("Pam", "[$tag] $text")
        try {
            file?.appendText("$level [$tag] $text${e?.let { " - $it" } ?: ""}\n")
        } catch (_: Exception) {
        }
    }
}

object Storage {
    lateinit var app: Context
}

/** Settings - every write bumps [revision] so screens repaint instantly. */
object Prefs {
    val revision = mutableLongStateOf(0L)
    private val p by lazy { Storage.app.getSharedPreferences("landrop", Context.MODE_PRIVATE) }

    var deviceName: String
        get() = (p.getString("device_name", "") ?: "").ifBlank { defaultName() }
        set(v) = put { putString("device_name", v.trim()) }

    var keepAwake: Boolean
        get() = p.getBoolean("keep_awake", true)
        set(v) = put { putBoolean("keep_awake", v) }

    /** "auto" | "fast" (big chunks) | "reliable" (small chunks). */
    var transferMode: String
        get() = p.getString("transfer_mode", "auto") ?: "auto"
        set(v) = put { putString("transfer_mode", v) }

    /** SHA-256 check of every file on the receiving side. */
    var verify: Boolean
        get() = p.getBoolean("verify", true)
        set(v) = put { putBoolean("verify", v) }

    /** Hold a high-performance Wi-Fi lock while transferring. */
    var wifiBoost: Boolean
        get() = p.getBoolean("wifi_boost", true)
        set(v) = put { putBoolean("wifi_boost", v) }

    /**
     * (parallel streams, chunk bytes) for a file of [size] bytes, from the transfer mode.
     * Several streams keep a Wi-Fi link full even when single connections stall;
     * chunks stay small enough that one request never takes long on a slow link.
     */
    fun plan(size: Long): Pair<Int, Int> {
        val mb = 1024 * 1024
        return when (transferMode) {
            "fast" -> 4 to 8 * mb
            "reliable" -> 1 to 1 * mb
            else -> when {
                size >= 64L * mb -> 3 to 4 * mb
                size >= 4L * mb -> 3 to 2 * mb
                else -> 3 to 1 * mb
            }
        }
    }

    /** The last device we were connected to, "ip:port". */
    var peer: String
        get() = p.getString("peer", "") ?: ""
        set(v) = put { putString("peer", v) }

    fun defaultName(): String = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Phone"

    private fun put(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        p.edit().apply(edit).apply()
        revision.longValue += 1
    }
}

class HistoryItem(
    val direction: String,   // "sent" | "received"
    val name: String,
    val size: Long,
    val peer: String,
    val time: Long,
    val status: String,      // "done" | "stopped" | "failed"
    val uri: String,
)

/** Sent / received files, newest first, kept in files/history.json. */
object History {
    val items = mutableStateListOf<HistoryItem>()
    private val file by lazy { File(Storage.app.filesDir, "history.json") }

    fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryItem(o.optString("direction"), o.optString("name"), o.optLong("size"),
                    o.optString("peer"), o.optLong("time"), o.optString("status"), o.optString("uri"))
            }
            items.clear()
            items.addAll(list)
        } catch (e: Exception) {
            Log.w("history", "could not load", e)
        }
    }

    @Volatile private var dirty = false
    private val saver by lazy {
        Thread {
            while (true) {
                Thread.sleep(700)
                if (dirty) { dirty = false; save() }
            }
        }.apply { isDaemon = true; name = "history"; start() }
    }

    fun add(item: HistoryItem) {
        synchronized(this) {
            items.add(0, item)
            while (items.size > 500) items.removeAt(items.size - 1)
        }
        markDirty()
    }

    fun clear() {
        synchronized(this) { items.clear() }
        markDirty()
    }

    fun remove(which: Collection<HistoryItem>) {
        synchronized(this) { items.removeAll(which.toSet()) }
        markDirty()
    }

    /** Saves happen on one background thread, at most every 0.7 s - never on the transfer path. */
    private fun markDirty() {
        dirty = true
        saver
    }

    private fun save() {
        try {
            val snapshot = synchronized(this) { items.toList() }
            val arr = JSONArray()
            snapshot.forEach {
                arr.put(JSONObject().put("direction", it.direction).put("name", it.name)
                    .put("size", it.size).put("peer", it.peer).put("time", it.time)
                    .put("status", it.status).put("uri", it.uri))
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }     // never a half-written file
        } catch (e: Exception) {
            Log.w("history", "could not save", e)
        }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 0) return "?"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun formatTime(ms: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

fun formatEta(seconds: Long): String = when {
    seconds < 0 -> ""
    seconds < 60 -> "${seconds}s left"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s left"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m left"
}

class App : Application() {
    companion object {
        @Volatile var stopping = false

        /**
         * The user left the app for good (back out of it, or swiped it away): stop the
         * transfer, tell the peer, close the sockets and end the process - nothing keeps
         * running in the background.
         */
        fun shutdown() {
            if (stopping) return
            stopping = true
            Log.i("exit", "shutting down")
            Thread {
                try {
                    Transfers.shutdown()
                    if (Link.state == Link.State.Connected || Link.state == Link.State.Lost) Link.disconnect()
                    Thread.sleep(400)        // lets /bye and /control leave
                    Server.stop()
                    Wifi.multicast(false)
                    try { Storage.app.stopService(android.content.Intent(Storage.app, TransferService::class.java)) } catch (_: Exception) {}
                } catch (e: Exception) {
                    Log.w("exit", "shutdown", e)
                }
                Thread.sleep(200)
                android.os.Process.killProcess(android.os.Process.myPid())
            }.apply { isDaemon = true; name = "exit" }.start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Storage.app = this
        Log.init(this)
        History.load()
        Log.i("start", "LanDrop started on ${android.os.Build.MODEL}")
        Link.start()
    }
}
