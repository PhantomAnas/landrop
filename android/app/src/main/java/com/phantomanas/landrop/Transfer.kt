package com.phantomanas.landrop

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

enum class TState { Waiting, Active, Paused, Done, Cancelled, Failed }

class FileItem(val fid: String, val name: String, val size: Long, val uri: Uri?) {
    var done by mutableLongStateOf(0L)          // bytes acknowledged (sender) / written and verified (receiver)
    @Volatile var outUri: Uri? = null
    @Volatile var finished = false
    /** Small incoming files land here first and are published to Download/LanDrop in the background. */
    @Volatile var staged: File? = null
    @Volatile var published = false
    /** Sender: false when the source cannot be read at an offset (then it goes sequentially). */
    @Volatile var seekable = true
    /** Receiver: offsets of the chunks that are safely in the file. */
    val chunksDone = HashSet<Long>()
}

fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

/** One transfer (one or more files, one direction). All state is Compose-observable. */
class Batch(val id: String, val files: List<FileItem>, val outgoing: Boolean, val peer: String) {
    var state by mutableStateOf(TState.Waiting)
    var index by mutableIntStateOf(0)
    var doneBytes by mutableLongStateOf(0L)
    var speed by mutableLongStateOf(0L)
    var note by mutableStateOf("")
    var pausedBy by mutableStateOf("")   // "me" | "peer"
    var streams by mutableIntStateOf(1)
    @Volatile var lastActivity = System.currentTimeMillis()

    val totalBytes = files.sumOf { maxOf(it.size, 0L) }
    val finished get() = state == TState.Done || state == TState.Cancelled || state == TState.Failed
    val fraction get() = if (totalBytes <= 0L) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val current get() = files.getOrNull(index) ?: files.last()

    private val lock = Any()
    private var doneSum = 0L
    private var live = 0L        // bytes of the chunks in flight right now
    private var lastT = 0L
    private var lastB = 0L

    /** [n] more bytes are on the wire (negative: a failed attempt is rolled back). */
    fun progress(n: Long) = synchronized(lock) {
        live = maxOf(0L, live + n)
        doneBytes = doneSum + live
        val now = System.currentTimeMillis()
        lastActivity = now
        if (lastT == 0L) { lastT = now; lastB = doneBytes }
        else if (now - lastT >= 1000) {
            speed = (doneBytes - lastB) * 1000 / (now - lastT)
            lastT = now
            lastB = doneBytes
        }
    }

    /** [n] bytes of [f] are safely across: move them from live to done. */
    fun credit(f: FileItem, n: Long) = synchronized(lock) {
        live = maxOf(0L, live - n)
        doneSum += n
        f.done += n
        doneBytes = doneSum + live
    }

    val eta: Long get() = if (speed <= 0L || totalBytes <= 0L) -1 else maxOf(0L, totalBytes - doneBytes) / speed
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

    /** App exit: stop whatever runs so no partial file and no thread is left behind. */
    fun shutdown() {
        current?.let { if (!it.finished) cancel(it) }
        offer?.let { Incoming.decline() }
    }
}

// ---- sending --------------------------------------------------------------------

object Outgoing {
    const val BUNDLE_FILE_MAX = 1024L * 1024        // files up to this size may be bundled
    const val BUNDLE_MAX = 4L * 1024 * 1024          // bytes of file data per bundle
    const val BUNDLE_FILES_MAX = 400

    private sealed class Unit {
        class Bundle(val files: List<FileItem>) : Unit()
        class Chunk(val f: FileItem, val offset: Long, val len: Int) : Unit()
        class Stream(val f: FileItem) : Unit()
    }

    @Volatile private var batch: Batch? = null
    @Volatile private var failed = false
    private var units: Iterator<Unit>? = null
    private val unitLock = Any()

    fun send(uris: List<Uri>) {
        if (Transfers.busy) { Log.i("send", "busy - not sending"); return }
        if (uris.isEmpty()) return
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
        var seekable = true
        try {
            Storage.app.contentResolver.openFileDescriptor(uri, "r")?.use {
                if (it.statSize >= 0) { if (size < 0) size = it.statSize } else seekable = false
            }
        } catch (_: Exception) {
        }
        return FileItem("f${index}_${UUID.randomUUID().toString().take(8)}", name, size, uri).also { it.seekable = seekable }
    }

    private fun run(uris: List<Uri>, peerName: String) {
        val files = uris.mapIndexed { i, u -> describe(u, i) }
        val b = Batch(UUID.randomUUID().toString().take(12), files, true, peerName)
        batch = b
        failed = false
        Transfers.current = b
        b.note = "Waiting for $peerName to accept..."
        val offer = JSONObject().put("id", b.id).put("from", Prefs.deviceName).put("v", Proto.VERSION)
            .put("files", JSONArray(files.map {
                JSONObject().put("fid", it.fid).put("name", it.name).put("size", it.size)
            }))
        val r = try {
            Http.postJson("http://${Link.peerAddress}/offer", offer, headers = Link.authHeaders)
        } catch (e: Exception) {
            return fail(b, "$peerName is not answering", tellPeer = false)
        }
        if (!r.optBoolean("ok")) {
            return fail(b, if (r.optString("error") == "busy") "$peerName is busy with another transfer" else "$peerName refused", tellPeer = false)
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
                "declined" -> return fail(b, "$peerName declined", TState.Cancelled, tellPeer = false)
                "unknown" -> return fail(b, "$peerName forgot the transfer", tellPeer = false)
            }
            if (System.currentTimeMillis() > deadline) return fail(b, "No answer from $peerName", tellPeer = false)
            Thread.sleep(500)
        }
        b.state = TState.Active
        b.note = ""
        val streams = files.maxOf { Prefs.plan(it.size).first }
        b.streams = streams
        TransferService.update()
        Log.i("send", "accepted - ${files.size} file(s), ${formatSize(b.totalBytes)}, $streams streams")
        units = units(files).iterator()
        val workers = (0 until streams).map { i -> Thread { worker(b) }.apply { isDaemon = true; name = "send$i" } }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        if ((b.state == TState.Active || b.state == TState.Paused) && !failed) {
            if (files.all { it.finished }) {
                b.state = TState.Done
                b.note = "All ${files.size} file(s) delivered to $peerName"
                b.speed = 0
            } else fail(b, "Not every file made it - try again")
        }
        TransferService.update()
        Log.i("send", "finished: ${b.state}")
    }

    /** Bundles of small files, chunks of big ones (any order, any worker), sequential streams for unknown sizes. */
    private fun units(files: List<FileItem>): Sequence<Unit> = sequence {
        var i = 0
        while (i < files.size) {
            val f = files[i]
            if (f.size in 0..BUNDLE_FILE_MAX) {
                var j = i
                var total = 0L
                while (j < files.size && files[j].size in 0..BUNDLE_FILE_MAX &&
                    total + files[j].size <= BUNDLE_MAX && j - i < BUNDLE_FILES_MAX) {
                    total += files[j].size
                    j++
                }
                if (j - i >= 2) {
                    yield(Unit.Bundle(files.subList(i, j)))
                    i = j
                    continue
                }
            }
            when {
                f.size < 0 || !f.seekable -> yield(Unit.Stream(f))
                f.size == 0L -> yield(Unit.Chunk(f, 0, 0))
                else -> {
                    val chunk = Prefs.plan(f.size).second
                    var off = 0L
                    while (off < f.size) {
                        yield(Unit.Chunk(f, off, minOf(chunk.toLong(), f.size - off).toInt()))
                        off += chunk
                    }
                }
            }
            i++
        }
    }

    private fun nextUnit(): Unit? = synchronized(unitLock) {
        val it = units ?: return null
        if (it.hasNext()) it.next() else null
    }

    /** Reads a source file at arbitrary offsets (one per worker and file). */
    private class Reader(uri: Uri) {
        val pfd = Storage.app.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("no stream")
        val channel = FileInputStream(pfd.fileDescriptor).channel
        fun readAt(offset: Long, buf: ByteArray, len: Int) {
            var n = 0
            while (n < len) {
                val k = channel.read(ByteBuffer.wrap(buf, n, len - n), offset + n)
                if (k < 0) throw IOException("the file shrank while sending")
                n += k
            }
        }
        fun close() { try { channel.close() } catch (_: Exception) {}; try { pfd.close() } catch (_: Exception) {} }
    }

    private fun worker(b: Batch) {
        val conn = PeerConn(Link.peerAddress)
        val readers = HashMap<String, Reader>()
        var buf = ByteArray(0)
        try {
            while (true) {
                if (!gate(b, conn)) return
                val unit = nextUnit() ?: return
                val ok = when (unit) {
                    is Unit.Bundle -> { b.index = maxOf(b.index, b.files.indexOf(unit.files.last())); sendBundle(conn, b, unit.files) }
                    is Unit.Chunk -> {
                        b.index = maxOf(b.index, b.files.indexOf(unit.f))
                        if (buf.size < unit.len) buf = ByteArray(unit.len)
                        sendChunk(conn, b, readers, unit, buf)
                    }
                    is Unit.Stream -> { b.index = maxOf(b.index, b.files.indexOf(unit.f)); sendStream(conn, b, unit.f) }
                }
                if (!ok) return
            }
        } finally {
            conn.close()
            readers.values.forEach { it.close() }
        }
    }

    /**
     * One request with retries: a lost link waits for the reconnect, a lost reply is
     * answered by the receiver's own bookkeeping (duplicates are fine). Null when the
     * transfer is over.
     */
    private fun post(conn: PeerConn, b: Batch, path: String, length: Long, write: (OutputStream, (Int) -> kotlin.Unit) -> kotlin.Unit): JSONObject? {
        var attempt = 0
        while (true) {
            if (!gate(b, conn)) return null
            var credited = 0L
            val progress: (Int) -> kotlin.Unit = { n -> credited += n; b.progress(n.toLong()) }
            try {
                val r = conn.post(path, length) { out -> write(out, progress) }
                if (!r.optBoolean("ok")) b.progress(-credited)      // refused: those bytes do not count
                return r
            } catch (e: Exception) {
                b.progress(-credited)
                Link.noteFailure()
                attempt++
                if (b.state == TState.Active) {
                    b.note = if (Link.state != Link.State.Connected) "Connection lost - resumes when the link is back" else "Retrying..."
                }
                Log.w("send", "request failed ($attempt): $e")
                Thread.sleep(minOf(1000L + attempt * 500L, 4000L))
            }
        }
    }

    private fun writePieces(out: OutputStream, buf: ByteArray, len: Int, progress: (Int) -> kotlin.Unit) {
        var off = 0
        while (off < len) {
            val k = minOf(Proto.PIECE, len - off)
            out.write(buf, off, k)
            progress(k)
            off += k
        }
    }

    private fun sendChunk(conn: PeerConn, b: Batch, readers: HashMap<String, Reader>, u: Unit.Chunk, buf: ByteArray): Boolean {
        val f = u.f
        val sha = try {
            val reader = readers.getOrPut(f.fid) { Reader(f.uri!!) }
            reader.readAt(u.offset, buf, u.len)
            if (Prefs.verify) hex(MessageDigest.getInstance("SHA-256").apply { update(buf, 0, u.len) }.digest()) else ""
        } catch (e: Exception) {
            fail(b, "Could not read ${f.name}: ${e.message}")
            return false
        }
        val path = "/chunk?id=${b.id}&fid=${f.fid}&offset=${u.offset}&len=${u.len}" + (if (sha.isNotEmpty()) "&sha=$sha" else "")
        var tries = 0
        while (true) {
            val r = post(conn, b, path, u.len.toLong()) { out, progress -> writePieces(out, buf, u.len, progress) } ?: return false
            if (r.optBoolean("ok")) {
                b.credit(f, u.len.toLong())
                b.note = ""
                if (r.optBoolean("complete") || f.done >= f.size) fileDone(b, f)
                return true
            }
            if (!onRefusal(b, r)) return false
            when (r.optString("error")) {
                "checksum" -> if (++tries >= 3) { fail(b, "${f.name} keeps arriving damaged - try again later"); return false }
                "range", "unknown" -> { fail(b, "Out of sync with ${b.peer} - start again"); return false }
            }
        }
    }

    /** A file whose size is unknown or that cannot be read at an offset: sequential chunks, the last one flagged. */
    private fun sendStream(conn: PeerConn, b: Batch, f: FileItem): Boolean {
        val chunk = Prefs.plan(0).second
        val buf = ByteArray(chunk)
        val input = try {
            Storage.app.contentResolver.openInputStream(f.uri!!) ?: throw IOException("no stream")
        } catch (e: Exception) {
            fail(b, "Could not open ${f.name}")
            return false
        }
        input.use {
            var offset = 0L
            var eof = false
            while (!eof) {
                var n = 0
                while (n < buf.size) {
                    val k = input.read(buf, n, buf.size - n)
                    if (k < 0) { eof = true; break }
                    n += k
                }
                val last = eof || (f.size >= 0 && offset + n >= f.size)
                if (last) eof = true
                val sha = if (Prefs.verify) hex(MessageDigest.getInstance("SHA-256").apply { update(buf, 0, n) }.digest()) else ""
                val path = "/chunk?id=${b.id}&fid=${f.fid}&offset=$offset&len=$n&last=${if (last) 1 else 0}" +
                    (if (sha.isNotEmpty()) "&sha=$sha" else "")
                while (true) {
                    val r = post(conn, b, path, n.toLong()) { out, progress -> writePieces(out, buf, n, progress) } ?: return false
                    if (r.optBoolean("ok")) {
                        b.credit(f, n.toLong())
                        offset += n
                        break
                    }
                    if (!onRefusal(b, r)) return false
                    if (r.optString("error") == "offset") {
                        // The receiver is somewhere else - only a strictly sequential source can't rewind: give up cleanly.
                        fail(b, "Out of sync with ${b.peer} - start again")
                        return false
                    }
                }
            }
            fileDone(b, f)
            return true
        }
    }

    /** Sends [group] as one POST /bundle: 4-byte index length, JSON index [{fid,size,sha}], then the files back to back. */
    private fun sendBundle(conn: PeerConn, b: Batch, group: List<FileItem>): Boolean {
        val data = java.io.ByteArrayOutputStream()
        val index = JSONArray()
        for (f in group) {
            val bytes = try {
                Storage.app.contentResolver.openInputStream(f.uri!!)?.use { it.readBytes() } ?: throw IOException("no stream")
            } catch (e: Exception) {
                fail(b, "Could not open ${f.name}")
                return false
            }
            val sha = if (Prefs.verify) hex(MessageDigest.getInstance("SHA-256").digest(bytes)) else ""
            index.put(JSONObject().put("fid", f.fid).put("size", bytes.size).put("sha", sha))
            data.write(bytes)
        }
        val head = index.toString().toByteArray()
        val body = java.io.ByteArrayOutputStream(4 + head.size + data.size())
        body.write(byteArrayOf((head.size ushr 24).toByte(), (head.size ushr 16).toByte(), (head.size ushr 8).toByte(), head.size.toByte()))
        body.write(head)
        data.writeTo(body)
        val bytes = body.toByteArray()
        while (true) {
            val r = post(conn, b, "/bundle?id=${b.id}", bytes.size.toLong()) { out, progress -> writePieces(out, bytes, bytes.size, progress) }
                ?: return false
            if (r.optBoolean("ok")) {
                b.progress(-(bytes.size - group.sumOf { it.size }))     // the index header is not file data
                for (f in group) {
                    b.credit(f, f.size)
                    fileDone(b, f)
                }
                b.note = ""
                return true
            }
            if (!onRefusal(b, r)) return false
            fail(b, if (r.optString("error") == "checksum") "A file arrived damaged - try again" else "${b.peer} refused a bundle: ${r.optString("error")}")
            return false
        }
    }

    /** Handles the receiver's non-ok states; true when the caller may retry. */
    private fun onRefusal(b: Batch, r: JSONObject): Boolean {
        when (r.optString("state")) {
            "paused" -> {
                if (b.state == TState.Active) { b.state = TState.Paused; b.pausedBy = "peer"; b.note = "Paused by ${b.peer}"; b.speed = 0 }
                return true
            }
            "pending" -> { Thread.sleep(500); return true }
            "cancelled", "unknown" -> {
                b.state = TState.Cancelled
                b.note = "${b.peer} stopped the transfer"
                b.speed = 0
                TransferService.update()
                return false
            }
        }
        if (r.optString("error") == "unauthorized") {
            if (Link.state == Link.State.Connected) {
                fail(b, "${b.peer} no longer knows this connection - connect again", tellPeer = false)
                return false
            }
            Thread.sleep(1000)
        }
        return true
    }

    private fun fileDone(b: Batch, f: FileItem) {
        if (f.finished) return
        f.finished = true
        History.add(HistoryItem("sent", f.name, maxOf(f.size, f.done), b.peer, System.currentTimeMillis(), "done", f.uri.toString()))
    }

    /** Waits while paused or while the link is down; false when the transfer is over. */
    private fun gate(b: Batch, conn: PeerConn): Boolean {
        var polls = 0
        var waitedLink = false
        while (true) {
            if (App.stopping || b.finished || failed) return false
            when (b.state) {
                TState.Active -> {
                    when (Link.state) {
                        Link.State.Connected -> { if (waitedLink) b.note = ""; return true }
                        Link.State.Off, Link.State.Requesting -> { fail(b, "Disconnected - the transfer was stopped", TState.Cancelled, tellPeer = false); return false }
                        Link.State.Lost -> {
                            if (!waitedLink) {
                                waitedLink = true
                                conn.close()
                                b.note = "Connection lost - resumes when the link is back"
                                b.speed = 0
                            }
                            Thread.sleep(500)
                        }
                    }
                }
                TState.Paused -> {
                    if (b.pausedBy == "peer" && polls++ % 3 == 0) {
                        val st = try {
                            Http.get("http://${Link.peerAddress}/status?id=${b.id}", headers = Link.authHeaders).optString("state")
                        } catch (_: Exception) { "paused" }
                        if (st == "active") { b.state = TState.Active; b.pausedBy = ""; b.note = ""; continue }
                        if (st == "cancelled") { b.state = TState.Cancelled; b.note = "${b.peer} stopped the transfer"; TransferService.update(); return false }
                    }
                    Thread.sleep(300)
                }
                else -> return false
            }
        }
    }

    private fun fail(b: Batch, note: String, state: TState = TState.Failed, tellPeer: Boolean = true) {
        val wasRunning = b.state == TState.Active || b.state == TState.Paused
        failed = true
        b.state = state
        b.note = note
        b.speed = 0
        if (tellPeer && wasRunning && Link.state == Link.State.Connected) control("cancel")
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

    /** Files that arrive whole in one request go through the cache (fast) and are published in the background. */
    private const val STAGE_MAX = 8L * 1024 * 1024

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

    private fun refuse(input: InputStream, length: Long, state: String): JSONObject {
        Server.drain(input, length)
        return JSONObject().put("ok", false).put("state", state)
    }

    /** The batch this request belongs to, or null after a refusal was prepared in [out]. */
    private fun check(id: String, input: InputStream, length: Long, out: Array<JSONObject?>): Batch? {
        val b = batch
        if (b == null || b.id != id) { out[0] = refuse(input, length, "unknown"); return null }
        when (b.state) {
            TState.Waiting -> { out[0] = refuse(input, length, "pending"); return null }
            TState.Paused -> { out[0] = refuse(input, length, "paused"); return null }
            TState.Cancelled, TState.Failed, TState.Done -> { out[0] = refuse(input, length, "cancelled"); return null }
            else -> return b
        }
    }

    fun chunk(id: String, fid: String, offset: Long, len: Long, last: Boolean, sha: String, input: InputStream, length: Long): JSONObject {
        val b: Batch
        val f: FileItem
        val outUri: Uri?
        val stage: Boolean
        synchronized(lock) {
            val refusal = arrayOfNulls<JSONObject>(1)
            b = check(id, input, length, refusal) ?: return refusal[0]!!
            f = b.files.firstOrNull { it.fid == fid } ?: return refuse(input, length, "unknown").put("error", "unknown")
            if (offset < 0 || len < 0 || len != length) { Server.drain(input, length); return JSONObject().put("ok", false).put("error", "range") }
            if (f.finished || f.chunksDone.contains(offset)) {
                Server.drain(input, length)
                return JSONObject().put("ok", true).put("dup", true).put("done", f.done).put("complete", f.finished)
            }
            if (f.size >= 0 && offset + len > f.size) { Server.drain(input, length); return JSONObject().put("ok", false).put("error", "range").put("received", f.done) }
            if (f.size < 0 && offset != f.done) { Server.drain(input, length); return JSONObject().put("ok", false).put("error", "offset").put("received", f.done) }
            stage = f.outUri == null && f.staged == null && offset == 0L && f.size >= 0 && len == f.size && len <= STAGE_MAX
            if (!stage && f.outUri == null) {
                try {
                    f.outUri = Inbox.create(f.name)
                } catch (e: Exception) {
                    return broken(b, f, input, length, e)
                }
            }
            outUri = f.outUri
            b.index = maxOf(b.index, b.files.indexOf(f))
        }
        // The bytes come in OUTSIDE the lock so other connections keep flowing.
        val digest = if (sha.isNotEmpty()) MessageDigest.getInstance("SHA-256") else null
        var got = 0L
        val staged = if (stage) File(File(Storage.app.cacheDir, "stage").apply { mkdirs() }, f.fid) else null
        try {
            val sink: (ByteArray, Int) -> kotlin.Unit = { buf, n -> digest?.update(buf, 0, n); got += n; b.progress(n.toLong()) }
            val guard = { if (b.state == TState.Cancelled || b.state == TState.Failed) throw IOException("stopped") }
            if (staged != null) {
                FileOutputStream(staged).use { out -> Inbox.copyStream(input, len, guard) { buf, n -> out.write(buf, 0, n); sink(buf, n) } }
            } else {
                Inbox.writeAt(outUri!!, offset, input, len, guard, sink)
            }
        } catch (e: Exception) {
            b.progress(-got)
            staged?.delete()
            if (b.state == TState.Cancelled || b.state == TState.Failed) {
                Server.drain(input, len - got)
                synchronized(lock) { discardPartials(b) }
                return JSONObject().put("ok", false).put("state", "cancelled")
            }
            if (b.state == TState.Active) Log.w("recv", "chunk of ${f.name} at $offset broke off: $e")
            return JSONObject().put("ok", false).put("error", "incomplete")
        }
        if (digest != null && !sha.equals(hex(digest.digest()), ignoreCase = true)) {
            b.progress(-got)
            staged?.delete()
            Log.w("recv", "checksum mismatch on ${f.name} at $offset - asking again")
            return JSONObject().put("ok", false).put("error", "checksum")
        }
        synchronized(lock) {
            if (b.state == TState.Cancelled || b.state == TState.Failed) {
                b.progress(-got)
                staged?.delete()
                discardPartials(b)
                return JSONObject().put("ok", false).put("state", "cancelled")
            }
            if (f.finished || f.chunksDone.contains(offset)) {
                b.progress(-got)
                staged?.delete()
                return JSONObject().put("ok", true).put("dup", true).put("done", f.done).put("complete", f.finished)
            }
            f.chunksDone.add(offset)
            b.credit(f, len)
            val complete = (f.size >= 0 && f.done >= f.size) || (f.size < 0 && last)
            if (complete) {
                if (staged != null) {
                    f.staged = staged
                    f.finished = true
                    Publisher.enqueue(b, f)
                } else finish(b, f)
                maybeDone(b)
            }
            return JSONObject().put("ok", true).put("done", f.done).put("complete", complete)
        }
    }

    private fun broken(b: Batch, f: FileItem, input: InputStream, length: Long, e: Exception): JSONObject {
        Server.drain(input, length)
        Log.w("recv", "could not save ${f.name}", e)
        b.state = TState.Failed
        b.note = "Could not save ${f.name}: ${e.message}"
        discardPartials(b)
        TransferService.update()
        return JSONObject().put("ok", false).put("state", "cancelled")
    }

    /** Under the lock, once every byte of [f] is in its MediaStore file: publish it, log it. */
    private fun finish(b: Batch, f: FileItem) {
        try { Inbox.finish(f.outUri!!) } catch (e: Exception) { Log.w("recv", "finish failed", e) }
        f.finished = true
        f.published = true
        History.add(HistoryItem("received", f.name, f.done, b.peer, System.currentTimeMillis(), "done", f.outUri.toString()))
        Log.i("recv", "saved ${f.name} (${formatSize(f.done)})")
    }

    /** A bundle of small files: 4-byte index length, JSON index [{fid,size,sha}], then the bytes. */
    fun bundle(id: String, input: InputStream, length: Long): JSONObject {
        val b: Batch
        synchronized(lock) {
            val refusal = arrayOfNulls<JSONObject>(1)
            b = check(id, input, length, refusal) ?: return refusal[0]!!
        }
        val lenBytes = ByteArray(4)
        var got = 0
        while (got < 4) { val n = input.read(lenBytes, got, 4 - got); if (n < 0) break; got += n }
        val headLen = ((lenBytes[0].toInt() and 0xff) shl 24) or ((lenBytes[1].toInt() and 0xff) shl 16) or
            ((lenBytes[2].toInt() and 0xff) shl 8) or (lenBytes[3].toInt() and 0xff)
        if (got < 4 || headLen <= 0 || headLen > 4 * 1024 * 1024) return JSONObject().put("ok", false).put("error", "bad index")
        val head = ByteArray(headLen)
        got = 0
        while (got < headLen) { val n = input.read(head, got, headLen - got); if (n < 0) break; got += n }
        val index = try { JSONArray(String(head, 0, got)) } catch (_: Exception) { return JSONObject().put("ok", false).put("error", "bad index") }
        var expected = 4L + headLen
        for (k in 0 until index.length()) expected += index.getJSONObject(k).optLong("size", 0)
        if (expected != length) return JSONObject().put("ok", false).put("error", "size mismatch")
        val dir = File(Storage.app.cacheDir, "stage").apply { mkdirs() }
        for (k in 0 until index.length()) {
            val e = index.getJSONObject(k)
            val size = e.optLong("size", 0)
            val sha = e.optString("sha")
            val f = b.files.firstOrNull { it.fid == e.optString("fid") }
            if (f == null || f.finished || (f.size >= 0 && size != f.size)) {
                Server.drain(input, size)      // already have it (a resend) or not ours: skip its bytes
                continue
            }
            val staged = File(dir, f.fid)
            val digest = if (sha.isNotEmpty()) MessageDigest.getInstance("SHA-256") else null
            var gotBytes = 0L
            try {
                FileOutputStream(staged).use { out ->
                    Inbox.copyStream(input, size, { if (b.finished) throw IOException("stopped") }) { buf, n ->
                        out.write(buf, 0, n); digest?.update(buf, 0, n); gotBytes += n; b.progress(n.toLong())
                    }
                }
            } catch (ex: Exception) {
                staged.delete()
                b.progress(-gotBytes)
                if (b.finished) return JSONObject().put("ok", false).put("state", "cancelled")
                return JSONObject().put("ok", false).put("error", "incomplete")
            }
            if (digest != null && !sha.equals(hex(digest.digest()), ignoreCase = true)) {
                staged.delete()
                b.progress(-gotBytes)
                return JSONObject().put("ok", false).put("error", "checksum")
            }
            synchronized(lock) {
                if (f.finished || b.finished) { staged.delete(); b.progress(-gotBytes); return@synchronized }
                f.staged = staged
                f.chunksDone.add(0L)
                b.credit(f, size)
                f.finished = true
                b.index = maxOf(b.index, b.files.indexOf(f))
                Publisher.enqueue(b, f)
            }
        }
        maybeDone(b)
        return JSONObject().put("ok", true)
    }

    /** Done once every file has arrived AND every staged file has been published. */
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
        Log.i("recv", "batch done: ${b.files.size} file(s), ${formatSize(b.totalBytes)}")
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
            try { Inbox.discard(it.outUri!!); it.outUri = null } catch (e: Exception) { Log.w("recv", "discard failed", e) }
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
        if (b.state == TState.Cancelled || b.state == TState.Failed) { staged.delete(); f.staged = null; f.published = true; return }
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

/** Raw network speed to the peer, no disk, no hashing - tells whether a slow transfer is the app or the Wi-Fi. */
object SpeedTest {
    var running by mutableStateOf(false)
        private set
    var result by mutableStateOf("")
        private set

    fun start() {
        if (running || Link.state != Link.State.Connected) return
        running = true
        result = "Measuring upload..."
        Thread {
            try {
                val up = upload(3)
                result = "Upload ${formatSize(up)}/s - measuring download..."
                val down = download(3)
                result = "Speed test: upload ${formatSize(up)}/s  ·  download ${formatSize(down)}/s  ·  3 streams"
            } catch (e: Exception) {
                result = "Speed test failed: ${e.message}"
            }
            Log.i("speed", result)
            running = false
        }.apply { isDaemon = true }.start()
    }

    private fun upload(streams: Int): Long {
        val block = ByteArray(Proto.PIECE)
        var total = 0L
        val lock = Any()
        val stop = System.currentTimeMillis() + 4000
        val t0 = System.currentTimeMillis()
        val threads = (0 until streams).map {
            Thread {
                val conn = PeerConn(Link.peerAddress)
                try {
                    while (System.currentTimeMillis() < stop && Link.state == Link.State.Connected) {
                        val n = 4L * 1024 * 1024
                        val r = conn.post("/blast?n=$n", n) { out -> repeat((n / Proto.PIECE).toInt()) { out.write(block) } }
                        if (!r.optBoolean("ok")) break
                        synchronized(lock) { total += n }
                    }
                } catch (e: Exception) {
                    Log.w("speed", "upload", e)
                } finally { conn.close() }
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { it.join() }
        return total * 1000 / maxOf(1L, System.currentTimeMillis() - t0)
    }

    private fun download(streams: Int): Long {
        var total = 0L
        val lock = Any()
        val stop = System.currentTimeMillis() + 4000
        val t0 = System.currentTimeMillis()
        val threads = (0 until streams).map {
            Thread {
                val conn = PeerConn(Link.peerAddress)
                val buf = ByteArray(Proto.PIECE)
                try {
                    while (System.currentTimeMillis() < stop && Link.state == Link.State.Connected) {
                        val n = 4L * 1024 * 1024
                        val (length, input) = conn.getStream("/blast?n=$n")
                        var left = length
                        while (left > 0) {
                            val k = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (k < 0) throw IOException("closed")
                            left -= k
                            synchronized(lock) { total += k }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("speed", "download", e)
                } finally { conn.close() }
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { it.join() }
        return total * 1000 / maxOf(1L, System.currentTimeMillis() - t0)
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

    /** Writes [length] bytes from [input] at [offset] of the file behind [uri] (its own descriptor: safe in parallel). */
    fun writeAt(uri: Uri, offset: Long, input: InputStream, length: Long, guard: () -> kotlin.Unit, sink: (ByteArray, Int) -> kotlin.Unit) {
        if (uri.scheme == "file") {
            RandomAccessFile(File(uri.path!!), "rw").use { raf ->
                raf.seek(offset)
                copyStream(input, length, guard) { buf, n -> raf.write(buf, 0, n); sink(buf, n) }
            }
            return
        }
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("could not open the file")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { out ->
                out.channel.position(offset)
                copyStream(input, length, guard) { buf, n -> out.write(buf, 0, n); sink(buf, n) }
                out.flush()
            }
        }
    }

    inline fun copyStream(input: InputStream, length: Long, guard: () -> kotlin.Unit = {}, sink: (ByteArray, Int) -> kotlin.Unit) {
        val buf = ByteArray(Proto.PIECE)
        var left = length
        while (left > 0) {
            guard()
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

    /** Deletes a file this app received (MediaStore) or, when the provider allows it, one it sent. */
    fun delete(uriString: String): Boolean {
        if (uriString.isBlank()) return false
        val uri = Uri.parse(uriString)
        return try {
            when {
                uri.scheme == "file" -> File(uri.path!!).delete()
                DocumentsContract.isDocumentUri(Storage.app, uri) -> DocumentsContract.deleteDocument(resolver, uri)
                else -> resolver.delete(uri, null, null) > 0
            }
        } catch (e: Exception) {
            Log.w("history", "delete $uriString", e)
            false
        }
    }

    fun exists(uriString: String): Boolean {
        if (uriString.isBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") File(uri.path!!).exists()
            else resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
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

    /** Intents that show the Download/LanDrop folder, best first (file managers differ). */
    fun folderIntents(): List<Intent> {
        val docUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/$FOLDER")
        return listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }
}
