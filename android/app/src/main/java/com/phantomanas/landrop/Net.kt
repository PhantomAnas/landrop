package com.phantomanas.landrop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder

/**
 * The LanDrop protocol - identical on the phone and on the PC, so either
 * side can connect, send or receive:
 *
 *   GET  /ping                         -> {"app":"landrop","version":1,"name":..,"port":..}
 *   POST /hello   {name, port}         -> the caller becomes our peer
 *   POST /bye                          -> the caller leaves
 *   POST /offer   {id, from, files:[{fid,name,size}]} -> {"ok":true} | {"ok":false,"error":"busy"}
 *   GET  /offer?id=                    -> {"state":"pending|accepted|declined|unknown"}
 *   POST /chunk?id=&fid=&offset=&last= body=bytes -> {"ok":true,"received":n}
 *                                         | {"ok":false,"state":"paused|cancelled|pending|unknown"}
 *                                         | {"ok":false,"error":"offset","received":n}
 *   POST /control?id=&action=pause|resume|cancel -> {"ok":true}
 *   GET  /status?id=                   -> {"state":"active|paused|pending|done|cancelled","received":n}
 *
 * UDP discovery on PORT+1: "LANDROP_DISCOVER" -> "LANDROP_HERE <port> <name>".
 */
object Proto {
    const val PORT = 53555
    const val DISCOVERY_PORT = 53556
    const val APP = "landrop"
    const val VERSION = 1
    const val DISCOVER_CALL = "LANDROP_DISCOVER"
    const val DISCOVER_REPLY = "LANDROP_HERE"
    const val CHUNK = 4 * 1024 * 1024
    const val SCHEME = "landrop://"

    /** Random per-run id so a device ignores its own discovery echo. */
    val instance: String = java.util.UUID.randomUUID().toString().take(8)
}

/** One device that answered discovery. */
class Found(val address: String, val name: String, val id: String)

/** Tiny blocking HTTP helpers - call off the main thread. */
object Http {
    fun get(url: String, timeoutMs: Int = 4000): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            return read(conn)
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    fun postJson(url: String, body: JSONObject, timeoutMs: Int = 6000): JSONObject {
        val bytes = body.toString().toByteArray()
        return postBytes(url, bytes, bytes.size, timeoutMs, "application/json")
    }

    fun postBytes(
        url: String, bytes: ByteArray, length: Int, timeoutMs: Int = 60_000,
        contentType: String = "application/octet-stream",
    ): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(length)
            conn.outputStream.use { it.write(bytes, 0, length) }
            return read(conn)
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    private fun read(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            throw IOException("bad reply $code: ${text.take(120)}")
        }
    }
}

/** The connection to the PC: state, auto-connect, heartbeat. */
object Link {
    enum class State { Off, Searching, Connected }

    var state by mutableStateOf(State.Off)
        private set
    var peerAddress by mutableStateOf("")
        private set
    var peerName by mutableStateOf("")
        private set
    var message by mutableStateOf("Not connected")
        private set
    var localIp by mutableStateOf("")
        private set

    @Volatile private var userStopped = false
    @Volatile private var busy = false
    private var misses = 0
    private var started = false

    fun start() {
        if (started) return
        started = true
        Server.start(Proto.PORT)
        Thread { loop() }.apply { isDaemon = true; name = "link" }.start()
    }

    private fun loop() {
        var tick = 0
        while (true) {
            try {
                if (tick % 5 == 0) localIp = findLocalIp() ?: ""
                when {
                    state == State.Connected -> heartbeat()
                    busy -> {}
                    Prefs.autoConnect && !userStopped -> autoConnect(tick)
                    state == State.Searching -> { state = State.Off; message = "Not connected" }
                }
            } catch (e: Exception) {
                Log.w("link", "loop hiccup", e)
            }
            tick++
            Thread.sleep(2000)
        }
    }

    private fun heartbeat() {
        if (ping(peerAddress) != null) {
            misses = 0
        } else if (++misses >= 3) {
            Log.i("link", "lost $peerName")
            val name = peerName
            drop("Lost $name - looking for it again")
            userStopped = false
        }
    }

    private fun autoConnect(tick: Int) {
        if (state != State.Searching) {
            state = State.Searching
            message = "Looking for devices..."
        }
        val saved = Prefs.peer
        if (saved.isNotBlank() && tryConnect(saved)) return
        if (tick % 2 == 0) {
            val found = discoverAll()
            when {
                found.size == 1 -> tryConnect(found[0].address)
                found.size > 1 -> message = "${found.size} devices found - tap CONNECT to choose one"
            }
        }
    }

    /** The /ping reply when a LanDrop answers at [address], else null. */
    fun ping(address: String): JSONObject? = try {
        Http.get("http://$address/ping", 2500).takeIf { it.optString("app") == Proto.APP }
    } catch (_: Exception) {
        null
    }

    /** One UDP broadcast; every LanDrop that answers within [timeoutMs] (not ourselves). */
    fun discoverAll(timeoutMs: Int = 2000): List<Found> {
        val found = LinkedHashMap<String, Found>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 400
            val call = Proto.DISCOVER_CALL.toByteArray()
            socket.send(DatagramPacket(call, call.size,
                InetAddress.getByName("255.255.255.255"), Proto.DISCOVERY_PORT))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                val reply = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(reply)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                // "LANDROP_HERE <port> <instance> <name words...>"
                val parts = String(reply.data, 0, reply.length).trim().split(" ", limit = 4)
                if (parts.size >= 3 && parts[0] == Proto.DISCOVER_REPLY && parts[2] != Proto.instance) {
                    val address = "${reply.address.hostAddress}:${parts[1]}"
                    found[address] = Found(address, parts.getOrNull(3)?.ifBlank { null } ?: "Device", parts[2])
                }
            }
        } catch (e: Exception) {
            Log.w("link", "discovery failed", e)
        } finally {
            socket?.close()
        }
        return found.values.toList()
    }

    /** Manual connect (blocking). Returns an error message, or null on success. */
    fun connect(address: String): String? {
        busy = true
        try {
            var a = address.trim().removePrefix(Proto.SCHEME).trimEnd('/')
            if (!a.contains(':')) a = "$a:${Proto.PORT}"
            if (!Regex("^[0-9A-Za-z_.-]+:\\d{2,5}$").matches(a)) return "That does not look like an address."
            message = "Connecting to $a..."
            val info = ping(a) ?: return "Nothing answers at $a.\nIs LanDrop open on that device, on the same Wi-Fi?"
            if (!tryConnect(a, info)) return "The device at $a refused the connection."
            return null
        } finally {
            busy = false
        }
    }

    private fun tryConnect(address: String, info: JSONObject? = ping(address)): Boolean {
        info ?: return false
        val reply = try {
            Http.postJson("http://$address/hello",
                JSONObject().put("name", Prefs.deviceName).put("port", Proto.PORT))
        } catch (_: Exception) {
            return false
        }
        if (!reply.optBoolean("ok")) return false
        established(address, info.optString("name").ifBlank { "Device" })
        return true
    }

    private fun established(address: String, name: String) {
        peerAddress = address
        peerName = name
        state = State.Connected
        misses = 0
        userStopped = false
        message = "Connected to $name"
        if (Prefs.peer != address) Prefs.peer = address
        Log.i("link", "connected to $name at $address")
    }

    private fun drop(text: String) {
        state = State.Off
        peerAddress = ""
        peerName = ""
        message = text
    }

    /** The user pressed DISCONNECT: tell the PC, stop auto-connecting until asked. */
    fun disconnect() {
        val a = peerAddress
        if (a.isNotBlank()) Thread {
            try { Http.postJson("http://$a/bye", JSONObject(), 3000) } catch (_: Exception) {}
        }.start()
        userStopped = true
        drop("Disconnected")
        Log.i("link", "disconnected by user")
    }

    /** The user asked to connect again - auto-connect may run. */
    fun resumeAuto() {
        userStopped = false
    }

    // ---- called by the server ------------------------------------------------

    fun onHello(ip: String, port: Int, name: String) {
        established("$ip:$port", name)
    }

    fun onBye(ip: String) {
        if (peerAddress.startsWith("$ip:")) {
            val name = peerName
            userStopped = true
            drop("$name disconnected")
            Log.i("link", "$name said bye")
        }
    }

    private fun findLocalIp(): String? {
        var best: String? = null
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (addr in nic.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (nic.name.startsWith("wlan")) return ip
                        if (best == null) best = ip
                    }
                }
            }
        } catch (_: Exception) {
        }
        return best
    }
}

/** HTTP server + UDP discovery responder, daemon threads for the app's life. */
object Server {
    fun start(port: Int) {
        Thread {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.receiveBufferSize = 1024 * 1024
                server.bind(java.net.InetSocketAddress(port))
                Log.i("server", "listening on :$port")
                while (true) {
                    val socket = server.accept()
                    Thread { handle(socket) }.apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                Log.w("server", "stopped", e)
            }
        }.apply { isDaemon = true; name = "http" }.start()
        Thread {
            try {
                val sock = DatagramSocket(Proto.DISCOVERY_PORT)
                while (true) {
                    try {
                        val packet = DatagramPacket(ByteArray(256), 256)
                        sock.receive(packet)
                        if (String(packet.data, 0, packet.length).trim() == Proto.DISCOVER_CALL) {
                            val reply = "${Proto.DISCOVER_REPLY} $port ${Proto.instance} ${Prefs.deviceName}".toByteArray()
                            sock.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        }
                    } catch (e: Exception) {
                        Log.w("server", "discovery hiccup", e)
                        Thread.sleep(200)
                    }
                }
            } catch (e: Exception) {
                Log.w("server", "discovery stopped", e)
            }
        }.apply { isDaemon = true; name = "udp" }.start()
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 60_000
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 1024 * 1024
            socket.use { s ->
                val input = CountingInput(BufferedInputStream(s.getInputStream(), 256 * 1024))
                val remoteIp = s.inetAddress.hostAddress ?: "?"
                while (true) {
                    if (!one(s, input, remoteIp)) break
                }
            }
        } catch (_: SocketTimeoutException) {
        } catch (e: Exception) {
            Log.w("server", "request failed", e)
        }
    }

    /** One request on an open connection; false when the connection should close. */
    private fun one(s: Socket, input: CountingInput, remoteIp: String): Boolean {
        val request = readLine(input) ?: return false
        val parts = request.split(" ")
        if (parts.size < 2) return false
        val method = parts[0]
        val fullPath = parts[1]
        val path = fullPath.substringBefore('?')
        val query = parseQuery(fullPath.substringAfter('?', ""))
        var length = 0L
        var close = parts.getOrNull(2)?.trim() == "HTTP/1.0"
        while (true) {
            val header = readLine(input) ?: return false
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                length = header.substringAfter(':').trim().toLongOrNull() ?: 0L
            }
            if (header.startsWith("Connection:", ignoreCase = true) &&
                header.substringAfter(':').trim().equals("close", ignoreCase = true)) close = true
        }
        val bodyStart = input.count

        fun reply(body: String) {
            val bytes = body.toByteArray()
            val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: ${if (close) "close" else "keep-alive"}\r\n\r\n"
            // One write for head + body: two writes trip Nagle + delayed ACK (~40 ms per reply).
            val headBytes = head.toByteArray()
            val all = ByteArray(headBytes.size + bytes.size)
            System.arraycopy(headBytes, 0, all, 0, headBytes.size)
            System.arraycopy(bytes, 0, all, headBytes.size, bytes.size)
            s.getOutputStream().apply {
                write(all)
                flush()
            }
        }

        fun jsonBody(): JSONObject {
            if (length <= 0 || length > 1024 * 1024) return JSONObject()
            val buf = ByteArray(length.toInt())
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            return try { JSONObject(String(buf, 0, read)) } catch (_: Exception) { JSONObject() }
        }

        val body: String = when {
            method == "GET" && path == "/ping" ->
                JSONObject().put("app", Proto.APP).put("version", Proto.VERSION)
                    .put("name", Prefs.deviceName).put("port", Proto.PORT).toString()

            method == "POST" && path == "/hello" -> {
                val b = jsonBody()
                Link.onHello(remoteIp, b.optInt("port", Proto.PORT), b.optString("name").ifBlank { "Device" })
                JSONObject().put("ok", true).put("name", Prefs.deviceName).toString()
            }

            method == "POST" && path == "/bye" -> {
                jsonBody()
                Link.onBye(remoteIp)
                """{"ok":true}"""
            }

            method == "POST" && path == "/offer" -> Incoming.offer(jsonBody(), remoteIp).toString()

            method == "GET" && path == "/offer" ->
                JSONObject().put("state", Incoming.offerState(query["id"] ?: "")).toString()

            method == "POST" && path == "/chunk" -> Incoming.chunk(
                query["id"] ?: "", query["fid"] ?: "",
                query["offset"]?.toLongOrNull() ?: -1L, query["last"] == "1",
                query["sha"] ?: "", input, length).toString()

            method == "POST" && path == "/control" -> {
                jsonBody()
                Incoming.control(query["id"] ?: "", query["action"] ?: "").toString()
            }

            method == "GET" && path == "/status" -> Incoming.status(query["id"] ?: "").toString()

            else -> """{"ok":false,"error":"unknown path"}"""
        }
        // Whatever the handler left unread must go before the next request can start.
        val left = length - (input.count - bodyStart)
        if (left > 0) {
            if (left > 32L * 1024 * 1024) close = true else drain(input, left)
        }
        reply(body)
        return !close
    }

    /** Counts bytes read so a handler's leftover body can be drained. */
    class CountingInput(private val inner: InputStream) : InputStream() {
        var count = 0L
            private set
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) count += it }
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            out.append(c.toChar())
            if (out.length > 8192) return out.toString()
        }
    }

    /** Read and discard [length] bytes so the client gets a clean reply. */
    fun drain(input: InputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }
}
