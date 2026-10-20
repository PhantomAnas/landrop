package com.phantomanas.landrop

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID

enum class TState { Waiting, Active, Paused, Done, Cancelled, Failed }

class FileItem(val fid: String, val name: String, val size: Long, val uri: Uri?) {
    var done by mutableLongStateOf(0L)
    @Volatile var outUri: Uri? = null
    @Volatile var finished = false
    /** Small incoming files land here first and are published to Download/LanDrop in the background. */
    @Volatile var staged: File? = null
    @Volatile var published = false
    /** Running SHA-256 of the bytes sent / written so far (null = not verifying). */
    var digest: java.security.MessageDigest? = null
}

fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

/** One transfer (one or more files, one direction). All state is Compose-observable. */
class Batch(val id: String, val files: List<FileItem>, val outgoing: Boolean, val peer: String) {
    var state by mutableStateOf(TState.Waiting)
    var index by mutableIntStateOf(0)
    var doneBytes by mutableLongStateOf(0L)
    var speed by mutableLongStateOf(0L)
    var note by mutableStateOf("")
    var pausedBy by mutableStateOf("")   // "me" | "peer" | "link"
    @Volatile var lastActivity = System.currentTimeMillis()

    val totalBytes = files.sumOf { maxOf(it.size, 0L) }
    val finished get() = state == TState.Done || state == TState.Cancelled || state == TState.Failed
    val fraction get() = if (totalBytes <= 0L) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val current get() = files.getOrNull(index) ?: files.last()

    private var lastT = 0L
    private var lastB = 0L
    fun tickSpeed() {
        val now = System.currentTimeMillis()
        lastActivity = now
        if (lastT == 0L) { lastT = now; lastB = doneBytes; return }
        if (now - lastT >= 1000) {
            speed = (doneBytes - lastB) * 1000 / (now - lastT)
            lastT = now
            lastB = doneBytes
        }
    }

    val eta: Long get() = if (speed <= 0L || totalBytes <= 0L) -1 else (totalBytes - doneBytes) / speed
}

/** What the screens look at: the one running transfer + a pending incoming offer. */
object Transfers {
    var current by mutableStateOf<Batch?>(null)
    var offer by mutableStateOf<Batch?>(null)

    val busy get() = current?.let { !it.finished } == true

    fun dismiss() {
        if (current?.finished == true) current = null
    }

    fun pause(b: Batch) = if (b.outgoing) Outgoing.pause() else Incoming.pause()
    fun resume(b: Batch) = if (b.outgoing) Outgoing.resume() else Incoming.resume()
    fun cancel(b: Batch) = if (b.outgoing) Outgoing.cancel() else Incoming.cancel()
}

// ---- sending --------------------------------------------------------------------

object Outgoing {
    @Volatile private var batch: Batch? = null

    fun send(uris: List<Uri>) {
        if (Transfers.busy || uris.isEmpty()) return
        val peer = Link.peerAddress
        if (peer.isBlank()) return
        Thread { run(uris, Link.peerName) }.apply { isDaemon = true; name = "send" }.start()
    }

    fun describe(uri: Uri, index: Int): FileItem {
        var name = "file-${index + 1}"
        var size = -1L
        try {
            Storage.app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
            Log.w("send", "could not describe $uri", e)
        }
        if (size < 0) try {
            Storage.app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
        } catch (_: Exception) {
        }
        return FileItem("f${index}_${UUID.randomUUID().toString().take(8)}", name, size, uri)
    }

    private fun run(uris: List<Uri>, peerName: String) {
        val files = uris.mapIndexed { i, u -> describe(u, i) }
        val b = Batch(UUID.randomUUID().toString().take(12), files, true, peerName)
        batch = b
        Transfers.current = b
        b.note = "Waiting for $peerName to accept..."
        val offer = JSONObject().put("id", b.id).put("from", Prefs.deviceName)
            .put("files", JSONArray(files.map {
                JSONObject().put("fid", it.fid).put("name", it.name).put("size", it.size)
            }))
        val r = try {
            Http.postJson("http://${Link.peerAddress}/offer", offer, headers = Link.authHeaders)
        } catch (e: Exception) {
            return fail(b, "$peerName is not answering")
        }
        if (!r.optBoolean("ok")) {
            return fail(b, if (r.optString("error") == "busy") "$peerName is busy with another transfer" else "$peerName refused")
        }
        // Wait for the other side's answer.
        val deadline = System.currentTimeMillis() + 180_000
        while (true) {
            if (b.state == TState.Cancelled) { control("cancel"); return }
            val st = try {
                Http.get("http://${Link.peerAddress}/offer?id=${b.id}", headers = Link.authHeaders).optString("state")
            } catch (_: Exception) { "pending" }
            when (st) {
                "accepted" -> break
                "declined" -> return fail(b, "$peerName declined", TState.Cancelled)
                "unknown" -> return fail(b, "$peerName forgot the transfer")
            }
            if (System.currentTimeMillis() > deadline) return fail(b, "No answer from $peerName")
            Thread.sleep(500)
        }
        b.state = TState.Active
        b.note = ""
        TransferService.update()
        Log.i("send", "accepted - ${files.size} file(s), ${formatSize(b.totalBytes)}")

        var buffer = ByteArray(0)
        val verify = Prefs.verify
        for ((i, f) in files.withIndex()) {
            b.index = i
            val chunk = Prefs.chunkSizeFor(f.size)
            if (buffer.size != chunk) buffer = ByteArray(chunk)
            f.digest = if (verify) java.security.MessageDigest.getInstance("SHA-256") else null
            val input = try {
                Storage.app.contentResolver.openInputStream(f.uri!!) ?: throw IOException("no stream")
            } catch (e: Exception) {
                return fail(b, "Could not open ${f.name}")
            }
            input.use {
                var offset = 0L
                var eof = false
                while (!eof) {
                    if (!gate(b)) return
                    var n = 0
                    while (n < buffer.size) {
                        val k = input.read(buffer, n, buffer.size - n)
                        if (k < 0) { eof = true; break }
                        n += k
                    }
                    val last = eof || (f.size >= 0 && offset + n >= f.size)
                    if (last) eof = true
                    f.digest?.update(buffer, 0, n)
                    val sha = if (last) f.digest?.let { hex(it.digest()) } ?: "" else ""
                    val sent = deliver(b, f, buffer, n, offset, last, sha) ?: return
                    offset = sent
                    f.done = sent
                    b.doneBytes = files.take(i).sumOf { it.done } + sent
                    b.tickSpeed()
                }
            }
            f.finished = true
            History.add(HistoryItem("sent", f.name, maxOf(f.size, f.done), peerName,
                System.currentTimeMillis(), "done", f.uri.toString()))
        }
        b.state = TState.Done
        b.note = "All ${files.size} file(s) delivered to $peerName"
        b.speed = 0
        TransferService.update()
        Log.i("send", "done")
    }

    /** Waits while paused; false when the transfer was cancelled. */
    private fun gate(b: Batch): Boolean {
        var polls = 0
        while (true) {
            when (b.state) {
                TState.Active -> return true
                TState.Cancelled, TState.Failed, TState.Done -> return false
                TState.Waiting -> return false
                TState.Paused -> {
                    if (b.pausedBy == "link" && Link.state == Link.State.Connected) {
                        b.state = TState.Active
                        b.note = ""
                        return true
                    }
                    if (b.pausedBy == "peer" && polls++ % 3 == 0) {
                        val st = try {
                            Http.get("http://${Link.peerAddress}/status?id=${b.id}", headers = Link.authHeaders).optString("state")
                        } catch (_: Exception) { "paused" }
                        if (st == "active") { b.state = TState.Active; b.note = ""; return true }
                        if (st == "cancelled") { b.state = TState.Cancelled; b.note = "${b.peer} stopped the transfer"; TransferService.update(); return false }
                    }
                    Thread.sleep(300)
                }
            }
        }
    }

    /** Sends one chunk until it is acknowledged; returns the receiver's byte count, or null on cancel. */
    private fun deliver(b: Batch, f: FileItem, buffer: ByteArray, n: Int, offset: Long, last: Boolean, sha: String): Long? {
        while (true) {
            if (!gate(b)) return null
            val url = "http://${Link.peerAddress}/chunk?id=${b.id}&fid=${f.fid}&offset=$offset&last=${if (last) 1 else 0}" +
                (if (sha.isNotEmpty()) "&sha=$sha" else "")
            val r = try {
                Http.postBytes(url, buffer, n, headers = Link.authHeaders)
            } catch (e: Exception) {
                if (b.state == TState.Active) {
                    b.state = TState.Paused
                    b.pausedBy = "link"
                    b.note = "Connection lost - resumes when the link is back"
                    Log.w("send", "chunk failed, waiting for the link", e)
                }
                Thread.sleep(1000)
                continue
            }
            if (r.optBoolean("ok")) return r.optLong("received", offset + n)
            when (r.optString("state")) {
                "paused" -> {
                    if (b.state == TState.Active) { b.state = TState.Paused; b.pausedBy = "peer"; b.note = "Paused by ${b.peer}" }
                    continue
                }
                "cancelled", "unknown" -> {
                    b.state = TState.Cancelled
                    b.note = "${b.peer} stopped the transfer"
                    b.speed = 0
                    TransferService.update()
                    return null
                }
            }
            if (r.optString("error") == "checksum") {
                fail(b, "${f.name} arrived damaged - the checksum did not match. Try again.")
                return null
            }
            if (r.optString("error") == "offset") {
                val have = r.optLong("received", -1)
                if (have == offset + n) return have     // it arrived, only the reply was lost
                fail(b, "Out of sync with ${b.peer} - start again")
                return null
            }
            Thread.sleep(500)
        }
    }

    private fun fail(b: Batch, note: String, state: TState = TState.Failed) {
        b.state = state
        b.note = note
        b.speed = 0
        TransferService.update()
        Log.w("send", note)
    }

    private fun control(action: String) {
        val b = batch ?: return
        Thread {
            try {
                Http.postJson("http://${Link.peerAddress}/control?id=${b.id}&action=$action", JSONObject(), 4000, Link.authHeaders)
            } catch (e: Exception) {
                Log.w("send", "control $action failed", e)
            }
        }.start()
    }

    fun pause() {
        val b = batch ?: return
        if (b.state == TState.Active) { b.state = TState.Paused; b.pausedBy = "me"; b.note = "Paused"; b.speed = 0; control("pause") }
    }

    fun resume() {
        val b = batch ?: return
        if (b.state == TState.Paused) { b.state = TState.Active; b.pausedBy = ""; b.note = ""; control("resume") }
    }

    fun cancel() {
        val b = batch ?: return
        if (b.finished) return
        b.state = TState.Cancelled
        b.note = "Stopped"
        b.speed = 0
        control("cancel")       // also while still waiting: the receiver's popup must go
        TransferService.update()
    }
}

// ---- receiving --------------------------------------------------------------------

object Incoming {
    private val lock = Any()
    @Volatile private var batch: Batch? = null

    fun offer(json: JSONObject, fromIp: String): JSONObject = synchronized(lock) {
        if (Transfers.busy || Transfers.offer != null) return JSONObject().put("ok", false).put("error", "busy")
        val arr = json.optJSONArray("files") ?: JSONArray()
        val files = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FileItem(o.optString("fid", "f$i"), safeName(o.optString("name", "file")), o.optLong("size", -1), null)
        }
        if (files.isEmpty()) return JSONObject().put("ok", false).put("error", "empty")
        val b = Batch(json.optString("id").ifBlank { UUID.randomUUID().toString() }, files, false,
            json.optString("from").ifBlank { Link.peerName.ifBlank { fromIp } })
        batch = b
        // The receiver ALWAYS decides: every send session pops ACCEPT / DECLINE.
        b.note = "Waiting for your answer"
        Transfers.offer = b
        Thread {
            Thread.sleep(180_000)
            if (Transfers.offer === b) decline()
        }.apply { isDaemon = true }.start()
        Log.i("recv", "offer from ${b.peer}: ${files.size} file(s), ${formatSize(b.totalBytes)}")
        JSONObject().put("ok", true)
    }

    fun accept() {
        val b = Transfers.offer ?: return
        Transfers.offer = null
        accept(b)
    }

    private fun accept(b: Batch) {
        b.state = TState.Active
        b.note = ""
        b.lastActivity = System.currentTimeMillis()
        Transfers.current = b
        TransferService.update()
    }

    fun decline() {
        val b = Transfers.offer ?: return
        Transfers.offer = null
        b.state = TState.Cancelled
        b.note = "Declined"
    }

    fun offerState(id: String): String {
        val b = batch
        if (b == null || b.id != id) return "unknown"
        return when (b.state) {
            TState.Waiting -> "pending"
            TState.Active, TState.Paused, TState.Done -> "accepted"
            TState.Cancelled, TState.Failed -> "declined"
        }
    }

    fun chunk(id: String, fid: String, offset: Long, last: Boolean, sha: String, input: InputStream, length: Long): JSONObject {
        synchronized(lock) {
            val b = batch
            fun refuse(state: String): JSONObject {
                Server.drain(input, length)
                return JSONObject().put("ok", false).put("state", state)
            }
            if (b == null || b.id != id) return refuse("unknown")
            when (b.state) {
                TState.Waiting -> return refuse("pending")
                TState.Paused -> return refuse("paused")
                TState.Cancelled, TState.Failed -> return refuse("cancelled")
                else -> {}
            }
            val f = b.files.firstOrNull { it.fid == fid } ?: return refuse("unknown")
            if (f.finished || offset != f.done || length < 0 || (f.size >= 0 && offset + length > f.size)) {
                Server.drain(input, length)
                return JSONObject().put("ok", false).put("error", "offset").put("received", f.done)
            }
            if (f.outUri == null && f.staged == null && offset == 0L && last && length <= STAGE_MAX) {
                return stageSmall(b, f, sha, input, length)
            }
            val t0 = System.nanoTime()
            var tCreate = 0L
            try {
                if (f.outUri == null) {
                    f.outUri = Inbox.create(f.name)
                    f.digest = if (sha.isNotEmpty() || Prefs.verify) java.security.MessageDigest.getInstance("SHA-256") else null
                    tCreate = System.nanoTime() - t0
                }
                Inbox.write(f.outUri!!, offset, input, length, f.digest)
            } catch (e: Exception) {
                Log.w("recv", "could not save ${f.name}", e)
                b.state = TState.Failed
                b.note = "Could not save ${f.name}: ${e.message}"
                discardPartials(b)
                TransferService.update()
                return JSONObject().put("ok", false).put("state", "cancelled")
            }
            f.done += length
            b.doneBytes += length
            b.index = b.files.indexOf(f)
            b.tickSpeed()
            if (last) {
                val mine = f.digest?.let { hex(it.digest()) }
                if (sha.isNotEmpty() && mine != null && !sha.equals(mine, ignoreCase = true)) {
                    Log.w("recv", "checksum mismatch on ${f.name}")
                    try { Inbox.discard(f.outUri!!) } catch (_: Exception) {}
                    f.outUri = null
                    f.done = 0
                    b.state = TState.Failed
                    b.note = "${f.name} arrived damaged (checksum mismatch) - ask for it again"
                    discardPartials(b)
                    TransferService.update()
                    return JSONObject().put("ok", false).put("error", "checksum")
                }
                val tWrite = System.nanoTime() - t0 - tCreate
                Inbox.finish(f.outUri!!)
                val tFinish = System.nanoTime() - t0 - tCreate - tWrite
                f.finished = true
                f.published = true
                History.add(HistoryItem("received", f.name, f.done, b.peer,
                    System.currentTimeMillis(), "done", f.outUri.toString()))
                Log.i("recv", "saved ${f.name} (${formatSize(f.done)}) create=${tCreate / 1_000_000}ms write=${tWrite / 1_000_000}ms finish=${tFinish / 1_000_000}ms")
                maybeDone(b)
            }
            return JSONObject().put("ok", true).put("received", f.done)
        }
    }

    /** Files up to this size arrive in one request and go through the staging fast path. */
    private const val STAGE_MAX = 8L * 1024 * 1024

    /** One-request file: write it to the cache (fast), ack, and let [Publisher] move it into Download/LanDrop. */
    private fun stageSmall(b: Batch, f: FileItem, sha: String, input: InputStream, length: Long): JSONObject {
        val dir = File(Storage.app.cacheDir, "stage").apply { mkdirs() }
        val staged = File(dir, f.fid)
        val digest = if (sha.isNotEmpty() || Prefs.verify) java.security.MessageDigest.getInstance("SHA-256") else null
        try {
            FileOutputStream(staged).use { out ->
                Inbox.copyStream(input, length) { buf, n -> out.write(buf, 0, n); digest?.update(buf, 0, n) }
            }
        } catch (e: Exception) {
            Log.w("recv", "could not stage ${f.name}", e)
            staged.delete()
            b.state = TState.Failed
            b.note = "Could not save ${f.name}: ${e.message}"
            discardPartials(b)
            TransferService.update()
            return JSONObject().put("ok", false).put("state", "cancelled")
        }
        val mine = digest?.let { hex(it.digest()) }
        if (sha.isNotEmpty() && mine != null && !sha.equals(mine, ignoreCase = true)) {
            Log.w("recv", "checksum mismatch on ${f.name}")
            staged.delete()
            b.state = TState.Failed
            b.note = "${f.name} arrived damaged (checksum mismatch) - ask for it again"
            discardPartials(b)
            TransferService.update()
            return JSONObject().put("ok", false).put("error", "checksum")
        }
        f.staged = staged
        f.done = length
        f.finished = true
        b.doneBytes += length
        b.index = b.files.indexOf(f)
        b.tickSpeed()
        Publisher.enqueue(b, f)
        maybeDone(b)
        return JSONObject().put("ok", true).put("received", f.done)
    }

    /** Done once every file has arrived AND every staged file has been published. Called under [lock] or from the publisher. */
    fun maybeDone(b: Batch) = synchronized(lock) {
        if (b.state != TState.Active) return
        if (!b.files.all { it.finished }) return
        if (b.files.any { !it.published }) {
            b.note = "Saving into ${Inbox.folderLabel}..."
            return
        }
        b.state = TState.Done
        b.note = "Saved in ${Inbox.folderLabel}"
        b.speed = 0
        TransferService.update()
    }

    fun control(id: String, action: String): JSONObject = synchronized(lock) {
        val b = batch
        if (b == null || b.id != id) return JSONObject().put("ok", false).put("state", "unknown")
        when (action) {
            "pause" -> if (b.state == TState.Active) { b.state = TState.Paused; b.pausedBy = "peer"; b.note = "Paused by ${b.peer}"; b.speed = 0 }
            "resume" -> if (b.state == TState.Paused) { b.state = TState.Active; b.pausedBy = ""; b.note = "" }
            "cancel" -> if (Transfers.offer === b) {
                Transfers.offer = null
                b.state = TState.Cancelled
                b.note = "${b.peer} withdrew the offer"
            } else stop(b, "${b.peer} stopped the transfer")
        }
        JSONObject().put("ok", true)
    }

    fun status(id: String): JSONObject {
        val b = batch
        if (b == null || b.id != id) return JSONObject().put("state", "cancelled")
        val state = when (b.state) {
            TState.Active -> "active"
            TState.Paused -> "paused"
            TState.Waiting -> "pending"
            TState.Done -> "done"
            TState.Cancelled, TState.Failed -> "cancelled"
        }
        return JSONObject().put("state", state).put("received", b.current.done)
    }

    fun pause() = synchronized(lock) {
        val b = batch ?: return
        if (b.state == TState.Active) { b.state = TState.Paused; b.pausedBy = "me"; b.note = "Paused"; b.speed = 0 }
    }

    fun resume() = synchronized(lock) {
        val b = batch ?: return
        if (b.state == TState.Paused) { b.state = TState.Active; b.pausedBy = ""; b.note = "" }
    }

    fun cancel() = synchronized(lock) {
        val b = batch ?: return
        stop(b, "Stopped")
    }

    private fun stop(b: Batch, note: String) {
        if (b.finished) return
        b.state = TState.Cancelled
        b.note = note
        b.speed = 0
        discardPartials(b)
        TransferService.update()
        Log.i("recv", note)
    }

    private fun discardPartials(b: Batch) {
        b.files.filter { !it.finished && it.outUri != null }.forEach {
            try { Inbox.discard(it.outUri!!) } catch (e: Exception) { Log.w("recv", "discard failed", e) }
            it.outUri = null
        }
    }

    private val reservedNames = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }

    /** A name the sender chose is only ever a plain file name inside our folder. */
    private fun safeName(name: String): String {
        var n = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().trimEnd('.', ' ')
        if (n.isBlank() || n.all { it == '.' }) n = "file"
        if (n.substringBefore('.').uppercase() in reservedNames) n = "_$n"
        return n.take(180)
    }
}

/**
 * Moves staged small files into Download/LanDrop on a few background threads,
 * so MediaStore's per-file cost overlaps with the next files still arriving.
 */
object Publisher {
    private val queue = java.util.concurrent.LinkedBlockingQueue<Pair<Batch, FileItem>>()
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun enqueue(b: Batch, f: FileItem) {
        if (started.compareAndSet(false, true)) {
            repeat(3) { i -> Thread { loop() }.apply { isDaemon = true; name = "publish$i"; start() } }
        }
        queue.put(b to f)
    }

    private fun loop() {
        while (true) {
            val (b, f) = queue.take()
            try {
                publish(b, f)
            } catch (e: Exception) {
                Log.w("recv", "could not publish ${f.name}", e)
                f.published = true
                b.note = "Could not save ${f.name}: ${e.message}"
                History.add(HistoryItem("received", f.name, f.done, b.peer, System.currentTimeMillis(), "failed", ""))
            }
            Incoming.maybeDone(b)
        }
    }

    private fun publish(b: Batch, f: FileItem) {
        val staged = f.staged ?: return
        val uri = Inbox.create(f.name)
        try {
            val out = Storage.app.contentResolver.openOutputStream(uri, "w") ?: throw IOException("could not open")
            out.use { o -> staged.inputStream().use { it.copyTo(o, 256 * 1024) } }
            Inbox.finish(uri)
        } catch (e: Exception) {
            try { Inbox.discard(uri) } catch (_: Exception) {}
            throw e
        } finally {
            staged.delete()
            f.staged = null
        }
        f.outUri = uri
        f.published = true
        History.add(HistoryItem("received", f.name, f.done, b.peer, System.currentTimeMillis(), "done", uri.toString()))
    }
}

/** Where received files go: Download/LanDrop (MediaStore on Android 10+). */
object Inbox {
    const val FOLDER = "LanDrop"
    const val folderLabel = "Download/LanDrop"
    private val resolver get() = Storage.app.contentResolver

    fun create(name: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("storage refused the file")
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        dir.mkdirs()
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var f = File(dir, name)
        var i = 1
        while (f.exists()) f = File(dir, "$stem (${i++})$ext")
        if (!f.createNewFile()) throw IOException("could not create ${f.name}")
        return Uri.fromFile(f)
    }

    fun write(uri: Uri, offset: Long, input: InputStream, length: Long, digest: java.security.MessageDigest? = null) {
        if (uri.scheme == "file") {
            RandomAccessFile(File(uri.path!!), "rw").use { raf ->
                raf.seek(offset)
                copyStream(input, length) { buf, n -> raf.write(buf, 0, n); digest?.update(buf, 0, n) }
            }
            return
        }
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("could not open the file")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { out ->
                out.channel.position(offset)
                copyStream(input, length) { buf, n -> out.write(buf, 0, n); digest?.update(buf, 0, n) }
                out.flush()
            }
        }
    }

    inline fun copyStream(input: InputStream, length: Long, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("the sender stopped early")
            sink(buf, n)
            left -= n
        }
    }

    fun finish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme != "file") {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    fun discard(uri: Uri) {
        if (uri.scheme == "file") File(uri.path!!).delete() else resolver.delete(uri, null, null)
    }

    fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** An intent that opens a received/sent file in whatever app handles it. */
    fun viewIntent(uriString: String, name: String): Intent? {
        if (uriString.isBlank()) return null
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") return null   // pre-10 path: not shareable to other apps without a provider
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime(name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
