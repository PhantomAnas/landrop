package com.phantomanas.landrop

import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The LanDrop protocol (v2) - identical on the phone and on the PC, so either
 * side can connect, send or receive:
 *
 *   GET  /ping                         -> {"app":"landrop","version":2,"name":..,"port":..,"instance":..}
 *   POST /pair    {id, name, port}     -> asks to connect; the callee shows ALLOW / DENY
 *   GET  /pair?id=                     -> {"state":"pending|allowed|denied|unknown","token":..} (caller polls)
 *   POST /pair?cancel=1&id=            -> the caller withdrew the request
 *   POST /introduce {address}          -> "ask THAT device to connect" (a phone relaying two PCs' QR codes);
 *                                         the other device still shows ALLOW / DENY
 *   Everything below needs the pairing token (header X-LanDrop-Token) AND must come
 *   from the paired peer's IP - anything else is answered "unauthorized".
 *   POST /hello   {name}               -> the peer is back after a dropped link (token still valid)
 *   POST /bye                          -> the peer leaves for good (token forgotten)
 *   POST /offer   {id, from, files:[{fid,name,size}]} -> {"ok":true} | {"ok":false,"error":"busy"}
 *   GET  /offer?id=                    -> {"state":"pending|accepted|declined|unknown"}
 *   POST /chunk?id=&fid=&offset=&len=&sha=[&last=] body=bytes
 *        -> {"ok":true,"done":n,"complete":bool}   (dup:true when it was already there)
 *         | {"ok":false,"state":"paused|cancelled|pending|unknown"}
 *         | {"ok":false,"error":"checksum|range|offset|incomplete"}
 *        Chunks carry their own SHA-256 and may arrive on several connections in any
 *        order; the receiver writes each at its offset. Unknown sizes go sequentially
 *        with last=1 on the final chunk.
 *   POST /bundle?id=  body = u32 index length + JSON [{fid,size,sha}] + the files back to back
 *   POST /control?id=&action=pause|resume|cancel -> {"ok":true}
 *   GET  /status?id=                   -> {"state":"active|paused|pending|done|cancelled","received":n}
 *   POST /blast?n=  body n bytes / GET /blast?n= -> n bytes   (speed test, nothing stored)
 *
 * UDP discovery on PORT+1: "LANDROP_DISCOVER" -> "LANDROP_HERE <port> <instance> <name>".
 */
object Proto {
    const val PORT = 53555
    const val DISCOVERY_PORT = 53556
    const val APP = "landrop"
    const val VERSION = 2
    const val DISCOVER_CALL = "LANDROP_DISCOVER"
    const val DISCOVER_REPLY = "LANDROP_HERE"
    const val PIECE = 256 * 1024
    const val SCHEME = "landrop://"

    /** Random per-run id so a device ignores its own discovery echo. */
    val instance: String = UUID.randomUUID().toString().take(8)
}

/** One device that answered discovery. */
class Found(val address: String, val name: String, val id: String)

/** Tiny blocking HTTP helpers for the small control calls - call off the main thread. */
object Http {
    fun get(url: String, timeoutMs: Int = 4000, headers: Map<String, String> = emptyMap()): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            return read(conn)
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    fun postJson(url: String, body: JSONObject, timeoutMs: Int = 6000, headers: Map<String, String> = emptyMap()): JSONObject {
        val bytes = body.toString().toByteArray()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
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

/**
 * One kept-alive raw HTTP/1.1 connection to the peer for the chunk stream: no
 * connection per chunk, big send buffer, bodies written piece by piece so progress
 * is live. Reconnects by itself after an error.
 */
class PeerConn(address: String, private val timeoutMs: Int = 120_000) {
    private val host = address.substringBeforeLast(':')
    private val port = address.substringAfterLast(':').toIntOrNull() ?: Proto.PORT
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null

    private fun connect() {
        val s = Socket()
        s.tcpNoDelay = true
        s.sendBufferSize = 2 * 1024 * 1024
        s.receiveBufferSize = 1024 * 1024
        s.connect(InetSocketAddress(host, port), 10_000)
        s.soTimeout = timeoutMs
        socket = s
        input = BufferedInputStream(s.getInputStream(), 64 * 1024)
        output = s.getOutputStream()
    }

    /** POST [length] bytes; [body] writes them to the stream (reporting progress). */
    fun post(path: String, length: Long, body: (OutputStream) -> Unit): JSONObject {
        if (socket == null) connect()
        try {
            val head = "POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Length: $length\r\n${Link.TOKEN_HEADER}: ${Link.token}\r\nConnection: keep-alive\r\n\r\n"
            val out = output!!
            out.write(head.toByteArray())
            body(out)
            out.flush()
            return reply()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** GET; returns the body length and the stream positioned at the body. */
    fun getStream(path: String): Pair<Long, InputStream> {
        if (socket == null) connect()
        try {
            val head = "GET $path HTTP/1.1\r\nHost: $host\r\n${Link.TOKEN_HEADER}: ${Link.token}\r\nConnection: keep-alive\r\n\r\n"
            output!!.write(head.toByteArray())
            output!!.flush()
            val (length, _) = readHead()
            return length to input!!
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun readHead(): Pair<Long, Boolean> {
        val inp = input!!
        val status = Server.readLine(inp) ?: throw IOException("connection closed")
        if (!status.startsWith("HTTP/")) throw IOException("bad reply: ${status.take(40)}")
        var length = 0L
        var close = false
        while (true) {
            val line = Server.readLine(inp) ?: throw IOException("connection closed")
            if (line.isEmpty()) break
            val k = line.substringBefore(':').trim().lowercase()
            val v = line.substringAfter(':', "").trim()
            if (k == "content-length") length = v.toLongOrNull() ?: 0L
            if (k == "connection" && v.equals("close", ignoreCase = true)) close = true
        }
        return length to close
    }

    private fun reply(): JSONObject {
        val (length, close) = readHead()
        val buf = ByteArray(length.toInt().coerceIn(0, 1 shl 20))
        var got = 0
        while (got < buf.size) {
            val n = input!!.read(buf, got, buf.size - got)
            if (n < 0) throw IOException("connection closed")
            got += n
        }
        if (close) close()
        Link.touch()
        return JSONObject(String(buf, 0, got))
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }
}

/** Wi-Fi helpers: the multicast lock lets the phone RECEIVE discovery broadcasts (Samsung filters them otherwise). */
object Wifi {
    private var lock: WifiManager.MulticastLock? = null

    fun multicast(on: Boolean) {
        try {
            if (lock == null) {
                val wm = Storage.app.getSystemService(WifiManager::class.java) ?: return
                lock = wm.createMulticastLock("landrop:discovery").apply { setReferenceCounted(false) }
            }
            if (on) lock?.acquire() else lock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w("wifi", "multicast lock", e)
        }
    }

    /** Every site-local IPv4 of this device (wlan first), with its broadcast address. */
    fun addresses(): List<Pair<Inet4Address, InetAddress?>> {
        val out = mutableListOf<Pair<Inet4Address, InetAddress?>>()
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (ia in nic.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val pair = a to ia.broadcast
                        if (nic.name.startsWith("wlan")) out.add(0, pair) else out.add(pair)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun localIp(): String? = addresses().firstOrNull()?.first?.hostAddress
}

/**
 * Pairing and the live link. Nothing connects without consent on BOTH ends:
 * one side asks, the other sees ALLOW / DENY, and only then a pairing token
 * is shared that every later request must carry. A link that drops (Wi-Fi blip)
 * keeps its token and comes back by itself when the peer answers again - only
 * DISCONNECT (either side) forgets the pairing.
 */
object Link {
    enum class State { Off, Requesting, Connected, Lost }

    /** A device asking to connect to us. */
    class Request(val id: String, val ip: String, val port: Int, val name: String, val token: String) {
        val at = System.currentTimeMillis()
        @Volatile var answer = "pending"   // pending | allowed | denied
    }

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
    /** The pending request from another device - the UI shows ALLOW / DENY. */
    var incoming by mutableStateOf<Request?>(null)
        private set

    @Volatile var token = ""
        private set
    val authHeaders: Map<String, String> get() = mapOf(TOKEN_HEADER to token)
    const val TOKEN_HEADER = "X-LanDrop-Token"

    @Volatile private var lastRequest: Request? = null
    @Volatile private var cancelOutgoing = false
    @Volatile private var lastTraffic = 0L
    private var misses = 0
    private var started = false

    fun start() {
        if (started) return
        started = true
        Server.start(Proto.PORT)
        Thread { loop() }.apply { isDaemon = true; name = "link" }.start()
    }

    @Volatile private var troubled = 0

    /** Any successful exchange with the peer counts as a heartbeat. */
    fun touch() {
        lastTraffic = System.currentTimeMillis()
        misses = 0
        troubled = 0
    }

    /** A request to the peer failed: after a couple of those the heartbeat checks right away. */
    fun noteFailure() {
        troubled++
    }

    private fun loop() {
        var tick = 0
        while (!App.stopping) {
            try {
                if (tick % 3 == 0) localIp = Wifi.localIp() ?: ""
                when (state) {
                    State.Connected -> heartbeat()
                    State.Lost -> tryReconnect()
                    else -> {}
                }
                incoming?.let { r ->
                    if (System.currentTimeMillis() - r.at > 60_000) {
                        r.answer = "denied"
                        incoming = null
                        message = "The request from ${r.name} expired"
                    }
                }
            } catch (e: Exception) {
                Log.w("link", "loop hiccup", e)
            }
            tick++
            Thread.sleep(2000)
        }
    }

    private fun heartbeat() {
        // A transfer in progress proves the link; only ping when it is quiet (or when our own requests keep failing).
        if (troubled < 2 && System.currentTimeMillis() - lastTraffic < 5000) return
        if (ping(peerAddress, 4000) != null) {
            touch()
        } else if (++misses >= 3) {
            Log.i("link", "lost $peerName")
            state = State.Lost
            message = "Lost $peerName - reconnecting when it is back..."
        }
    }

    private fun tryReconnect() {
        if (ping(peerAddress, 3000) == null) return
        val r = try {
            Http.postJson("http://$peerAddress/hello", JSONObject().put("name", Prefs.deviceName), 4000, authHeaders)
        } catch (_: Exception) {
            return
        }
        if (r.optBoolean("ok")) {
            touch()
            state = State.Connected
            message = "Connected to $peerName"
            Log.i("link", "reconnected to $peerName")
        } else {
            drop("$peerName disconnected")
        }
    }

    /** The /ping reply when a LanDrop answers at [address], else null. */
    fun ping(address: String, timeoutMs: Int = 2500): JSONObject? = try {
        Http.get("http://$address/ping", timeoutMs).takeIf { it.optString("app") == Proto.APP }
    } catch (_: Exception) {
        null
    }

    /**
     * Every LanDrop on the network (not ourselves). A UDP call goes out of every
     * interface (plain and directed broadcast, repeated), and in parallel the local
     * subnet is swept with /ping so devices behind broadcast-filtering networks
     * (hotspots, some routers) are found too.
     */
    fun discoverAll(timeoutMs: Int = 3000): List<Found> {
        val found = LinkedHashMap<String, Found>()
        val lock = Any()
        val addresses = Wifi.addresses()
        val mine = addresses.map { "${it.first.hostAddress}:${Proto.PORT}" }.toSet()
        val deadline = System.currentTimeMillis() + timeoutMs
        Wifi.multicast(true)

        // UDP sockets: one unbound + one per interface address (a broadcast only leaves the interface it is bound to).
        val socks = mutableListOf<Pair<DatagramSocket, List<InetAddress>>>()
        try {
            socks += DatagramSocket().apply { broadcast = true; soTimeout = 200 } to listOf(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) { Log.w("link", "discovery socket", e) }
        for ((ip, bcast) in addresses) {
            try {
                val s = DatagramSocket(InetSocketAddress(ip, 0)).apply { broadcast = true; soTimeout = 200 }
                val dests = listOfNotNull(InetAddress.getByName("255.255.255.255"), bcast,
                    InetAddress.getByName(ip.hostAddress!!.substringBeforeLast('.') + ".255"))
                socks += s to dests.distinct()
            } catch (e: Exception) { Log.w("link", "discovery socket ${ip.hostAddress}", e) }
        }
        val call = Proto.DISCOVER_CALL.toByteArray()
        val listeners = socks.map { (s, _) ->
            Thread {
                val buffer = ByteArray(512)
                while (System.currentTimeMillis() < deadline) {
                    val reply = DatagramPacket(buffer, buffer.size)
                    try { s.receive(reply) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
                    // "LANDROP_HERE <port> <instance> <name words...>"
                    val parts = String(reply.data, 0, reply.length).trim().split(" ", limit = 4)
                    if (parts.size >= 3 && parts[0] == Proto.DISCOVER_REPLY && parts[2] != Proto.instance) {
                        val address = "${reply.address.hostAddress}:${parts[1]}"
                        if (address !in mine) synchronized(lock) {
                            found[address] = Found(address, parts.getOrNull(3)?.ifBlank { null } ?: "Device", parts[2])
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        // The sweep: /ping every address of the first two private /24 networks.
        val pool = Executors.newFixedThreadPool(64)
        val nets = addresses.map { it.first.hostAddress!!.substringBeforeLast('.') }.distinct().take(2)
        for (net in nets) for (i in 1..254) {
            val address = "$net.$i:${Proto.PORT}"
            if (address in mine) continue
            pool.execute {
                val info = ping(address, 800) ?: return@execute
                if (info.optString("instance") != Proto.instance) synchronized(lock) {
                    found.getOrPut(address) { Found(address, info.optString("name").ifBlank { "Device" }, info.optString("instance")) }
                }
            }
        }
        pool.shutdown()
        try {
            repeat(3) {
                for ((s, dests) in socks) for (d in dests) {
                    try { s.send(DatagramPacket(call, call.size, d, Proto.DISCOVERY_PORT)) } catch (_: Exception) {}
                }
                Thread.sleep(500)
            }
            listeners.forEach { it.join(maxOf(100L, deadline - System.currentTimeMillis() + 300)) }
            pool.awaitTermination(maxOf(500L, deadline + 1500 - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w("link", "discovery", e)
        } finally {
            socks.forEach { try { it.first.close() } catch (_: Exception) {} }
            pool.shutdownNow()
        }
        synchronized(lock) { return found.values.toList() }
    }

    fun normalize(address: String): String? {
        var a = address.trim().removePrefix(Proto.SCHEME).trimEnd('/')
        if (!a.contains(':')) a = "$a:${Proto.PORT}"
        return a.takeIf { Regex("^[0-9A-Za-z_.-]+:\\d{2,5}$").matches(it) }
    }

    /**
     * Ask the device at [address] to connect (blocking, up to 90 s while the other
     * side decides). Returns null on success, otherwise the message to show.
     */
    fun requestPair(address: String): String? {
        val a = normalize(address) ?: return "That does not look like an address."
        if (state == State.Connected || state == State.Lost) return "Disconnect first."
        if (state == State.Requesting) return "Already asking another device."
        cancelOutgoing = false
        state = State.Requesting
        message = "Asking $a..."
        try {
            val info = ping(a) ?: return fail("Nothing answers at $a.\nIs LanDrop open on that device, on the same network?")
            val name = info.optString("name").ifBlank { "Device" }
            val id = UUID.randomUUID().toString().take(12)
            val r = try {
                Http.postJson("http://$a/pair",
                    JSONObject().put("id", id).put("name", Prefs.deviceName).put("port", Proto.PORT))
            } catch (_: Exception) {
                return fail("$name did not answer the request.")
            }
            if (!r.optBoolean("ok")) {
                return fail(if (r.optString("error") == "busy")
                    "$name is busy - connected to another device or already deciding on a request."
                else "$name refused the request.")
            }
            message = "Waiting for $name to allow..."
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                if (cancelOutgoing) {
                    withdraw(a, id)
                    return fail("Request cancelled")
                }
                Thread.sleep(700)
                val st = try { Http.get("http://$a/pair?id=$id") } catch (_: Exception) { continue }
                when (st.optString("state")) {
                    "allowed" -> {
                        established(a, st.optString("name").ifBlank { name }, st.optString("token"))
                        return null
                    }
                    "denied" -> return fail("$name declined.")
                    "unknown" -> return fail("$name forgot the request - try again.")
                }
            }
            withdraw(a, id)
            return fail("$name did not answer in time.")
        } finally {
            if (state == State.Requesting) state = State.Off
        }
    }

    private fun withdraw(address: String, id: String) {
        try { Http.postJson("http://$address/pair?cancel=1&id=$id", JSONObject(), 3000) } catch (_: Exception) {}
    }

    private fun fail(text: String): String {
        state = State.Off
        message = text
        Log.i("link", "pair failed: $text")
        return text
    }

    /** The user gave up waiting. */
    fun cancelRequest() {
        cancelOutgoing = true
    }

    /** Tell [first] (a device whose QR was scanned) to ask [second] to connect. */
    fun introduce(first: String, second: String): String? {
        val a = normalize(first) ?: return "That does not look like an address."
        val b = normalize(second) ?: return "That does not look like an address."
        if (a == b) return "That is the same device twice - scan the other one."
        val name = ping(a)?.optString("name")?.ifBlank { null } ?: "The first device"
        val r = try {
            Http.postJson("http://$a/introduce", JSONObject().put("address", b), 6000)
        } catch (_: Exception) {
            return "$name did not answer."
        }
        return when {
            r.optBoolean("ok") -> null
            r.optString("error") == "busy" -> "$name is busy - it must be disconnected first."
            else -> "$name could not ask: ${r.optString("error")}"
        }
    }

    // ---- called by the server ------------------------------------------------

    fun onPairRequest(ip: String, port: Int, name: String, id: String): JSONObject {
        if (state != State.Off || incoming != null || id.isBlank()) {
            return JSONObject().put("ok", false).put("error", "busy")
        }
        val secret = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        val req = Request(id, ip, port, name, secret)
        lastRequest = req
        incoming = req
        message = "$name wants to connect"
        Log.i("link", "pair request from $name at $ip:$port")
        return JSONObject().put("ok", true).put("state", "pending")
    }

    fun onPairPoll(id: String): JSONObject {
        val r = lastRequest
        if (r == null || r.id != id) return JSONObject().put("state", "unknown")
        return if (r.answer == "allowed") {
            JSONObject().put("state", "allowed").put("token", r.token).put("name", Prefs.deviceName)
        } else JSONObject().put("state", r.answer)
    }

    fun onPairCancel(id: String) {
        val r = incoming ?: return
        if (r.id == id) {
            r.answer = "denied"
            incoming = null
            message = "${r.name} withdrew the request"
        }
    }

    /** Another device relayed an address to ask (see /introduce): behaves like typing it here. */
    fun onIntroduce(address: String): JSONObject {
        if (state != State.Off) return JSONObject().put("ok", false).put("error", "busy")
        val a = normalize(address) ?: return JSONObject().put("ok", false).put("error", "address")
        if (a in Wifi.addresses().map { "${it.first.hostAddress}:${Proto.PORT}" }) return JSONObject().put("ok", false).put("error", "self")
        Thread {
            val err = requestPair(a)
            if (err != null) message = err
        }.apply { isDaemon = true }.start()
        return JSONObject().put("ok", true)
    }

    /** The peer came back after a dropped link (its token was checked). */
    fun onHello(name: String): JSONObject {
        if (state == State.Lost) {
            touch()
            if (name.isNotBlank()) peerName = name
            state = State.Connected
            message = "Connected to $peerName"
            Log.i("link", "$peerName is back")
        } else touch()
        return JSONObject().put("ok", true).put("name", Prefs.deviceName)
    }

    /** The user pressed ALLOW on the popup. */
    fun allow() {
        val r = incoming ?: return
        incoming = null
        r.answer = "allowed"
        established("${r.ip}:${r.port}", r.name, r.token)
    }

    /** The user pressed DENY on the popup. */
    fun deny() {
        val r = incoming ?: return
        incoming = null
        r.answer = "denied"
        message = "You declined ${r.name}"
    }

    /** True only for the paired device: same IP as the peer AND the shared token (a dropped link keeps the pairing). */
    fun isPeer(ip: String, tokenHeader: String?): Boolean =
        (state == State.Connected || state == State.Lost) && token.isNotEmpty() && tokenHeader == token &&
            peerAddress.substringBefore(':') == ip

    private fun established(address: String, name: String, secret: String) {
        peerAddress = address
        peerName = name
        token = secret
        touch()
        state = State.Connected
        message = "Connected to $name"
        if (Prefs.peer != address) Prefs.peer = address
        Log.i("link", "connected to $name at $address")
    }

    private fun drop(text: String) {
        state = State.Off
        peerAddress = ""
        peerName = ""
        token = ""
        message = text
    }

    /** DISCONNECT: tell the peer, forget the token. Nothing reconnects by itself. */
    fun disconnect() {
        val a = peerAddress
        val h = authHeaders
        if (a.isNotBlank()) Thread {
            try { Http.postJson("http://$a/bye", JSONObject(), 3000, h) } catch (_: Exception) {}
        }.start()
        drop("Disconnected")
        Log.i("link", "disconnected by user")
    }

    /** The peer said /bye (the server checked the token first). */
    fun onBye() {
        val name = peerName
        drop("$name disconnected")
        Log.i("link", "$name said bye")
    }
}

/** HTTP server + UDP discovery responder, daemon threads for the app's life. */
object Server {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null

    fun start(port: Int) {
        Thread {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.receiveBufferSize = 1024 * 1024
                s.bind(InetSocketAddress(port))
                server = s
                Log.i("server", "listening on :$port")
                while (!App.stopping) {
                    val socket = s.accept()
                    Thread { handle(socket) }.apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                if (!App.stopping) Log.w("server", "stopped", e)
            }
        }.apply { isDaemon = true; name = "http" }.start()
        Thread {
            try {
                val sock = DatagramSocket(Proto.DISCOVERY_PORT)
                udp = sock
                while (!App.stopping) {
                    try {
                        val packet = DatagramPacket(ByteArray(256), 256)
                        sock.receive(packet)
                        if (String(packet.data, 0, packet.length).trim() == Proto.DISCOVER_CALL) {
                            val reply = "${Proto.DISCOVER_REPLY} $port ${Proto.instance} ${Prefs.deviceName}".toByteArray()
                            sock.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        }
                    } catch (e: Exception) {
                        if (App.stopping) break
                        Log.w("server", "discovery hiccup", e)
                        Thread.sleep(200)
                    }
                }
            } catch (e: Exception) {
                if (!App.stopping) Log.w("server", "discovery stopped", e)
            }
        }.apply { isDaemon = true; name = "udp" }.start()
    }

    /** Closes the listening sockets (app exit). */
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 90_000
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
            if (!App.stopping) Log.w("server", "request failed", e)
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
        var tokenHeader: String? = null
        var close = parts.getOrNull(2)?.trim() == "HTTP/1.0"
        while (true) {
            val header = readLine(input) ?: return false
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                length = header.substringAfter(':').trim().toLongOrNull() ?: 0L
            }
            if (header.startsWith("${Link.TOKEN_HEADER}:", ignoreCase = true)) {
                tokenHeader = header.substringAfter(':').trim()
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

        val authorized = Link.isPeer(remoteIp, tokenHeader)
        if (authorized) Link.touch()
        val body: String = when {
            method == "GET" && path == "/ping" ->
                JSONObject().put("app", Proto.APP).put("version", Proto.VERSION)
                    .put("name", Prefs.deviceName).put("port", Proto.PORT).put("instance", Proto.instance).toString()

            method == "POST" && path == "/pair" && query["cancel"] == "1" -> {
                jsonBody()
                Link.onPairCancel(query["id"] ?: "")
                """{"ok":true}"""
            }

            method == "POST" && path == "/pair" -> {
                val b = jsonBody()
                Link.onPairRequest(remoteIp, b.optInt("port", Proto.PORT),
                    b.optString("name").ifBlank { "Device" }.take(40), b.optString("id").take(32)).toString()
            }

            method == "GET" && path == "/pair" -> Link.onPairPoll(query["id"] ?: "").toString()

            method == "POST" && path == "/introduce" -> Link.onIntroduce(jsonBody().optString("address").take(64)).toString()

            // Everything below is for the paired device only.
            !authorized -> {
                Log.w("server", "unauthorized $method $path from $remoteIp")
                """{"ok":false,"error":"unauthorized"}"""
            }

            method == "POST" && path == "/hello" -> Link.onHello(jsonBody().optString("name").take(40)).toString()

            method == "POST" && path == "/bye" -> {
                jsonBody()
                Link.onBye()
                """{"ok":true}"""
            }

            method == "POST" && path == "/offer" -> Incoming.offer(jsonBody(), remoteIp).toString()

            method == "GET" && path == "/offer" ->
                JSONObject().put("state", Incoming.offerState(query["id"] ?: "")).toString()

            method == "POST" && path == "/chunk" -> Incoming.chunk(
                query["id"] ?: "", query["fid"] ?: "",
                query["offset"]?.toLongOrNull() ?: -1L, query["len"]?.toLongOrNull() ?: length,
                query["last"] == "1", query["sha"] ?: "", input, length).toString()

            method == "POST" && path == "/bundle" -> Incoming.bundle(query["id"] ?: "", input, length).toString()

            method == "POST" && path == "/control" -> {
                jsonBody()
                Incoming.control(query["id"] ?: "", query["action"] ?: "").toString()
            }

            method == "GET" && path == "/status" -> Incoming.status(query["id"] ?: "").toString()

            method == "POST" && path == "/blast" -> {
                drain(input, length)
                """{"ok":true,"n":$length}"""
            }

            method == "GET" && path == "/blast" -> {
                // Raw bytes for the speed test: write the head and the zeros straight out.
                val n = (query["n"]?.toLongOrNull() ?: 0L).coerceIn(0L, 64L * 1024 * 1024)
                val head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $n\r\nConnection: keep-alive\r\n\r\n"
                val out = s.getOutputStream()
                out.write(head.toByteArray())
                val block = ByteArray(Proto.PIECE)
                var left = n
                while (left > 0) {
                    val k = minOf(block.size.toLong(), left).toInt()
                    out.write(block, 0, k)
                    left -= k
                }
                out.flush()
                return !close
            }

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

    fun readLine(input: InputStream): String? {
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
