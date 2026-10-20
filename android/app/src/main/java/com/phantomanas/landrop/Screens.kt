package com.phantomanas.landrop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object Nav {
    var screen by mutableStateOf("home")
    /** What the scanner does with a code: "connect" (pair with it) | "linkA" / "linkB" (relay two PCs). */
    var scanMode by mutableStateOf("connect")
    var linkFirst by mutableStateOf("")
    var linkStatus by mutableStateOf("")
}

fun copyToClipboard(context: Context, text: String) {
    try {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("LanDrop address", text))
    } catch (e: Exception) {
        Log.w("ui", "clipboard", e)
    }
}

fun openFolder(context: Context) {
    for (intent in Inbox.folderIntents()) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
    Log.w("ui", "no app can show the folder")
}

fun myAddress(): String = if (Link.localIp.isBlank()) "" else "${Link.localIp}:${Proto.PORT}"

@Composable
fun Root() {
    val activity = LocalActivity.current
    Box(Modifier.fillMaxSize().background(
        Brush.verticalGradient(0f to Color(0xFF0B0E16), 0.55f to Color(0xFF131A2B), 1f to Color(0xFF0A0C12)))) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(
                listOf(Palette.Blue.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * 0.5f, size.height * 0.12f), radius = size.width * 0.85f),
                radius = size.width * 0.85f, center = Offset(size.width * 0.5f, size.height * 0.12f))
        }
        AnimatedContent(
            targetState = Nav.screen,
            transitionSpec = {
                (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 24 }) togetherWith fadeOut(tween(160))
            },
            label = "nav",
        ) { s ->
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp)) {
                when (s) {
                    "connect" -> ConnectScreen()
                    "scan" -> ScanScreen()
                    "qr" -> QrScreen()
                    "history" -> HistoryScreen()
                    "settings" -> SettingsScreen()
                    else -> HomeScreen()
                }
            }
        }
        Transfers.offer?.let { OfferPopup(it) }
        Link.incoming?.let { PairPopup(it) }
    }
    BackHandler {
        when (Nav.screen) {
            "home" -> {
                // Leaving the app: a running transfer continues in the background (notification);
                // otherwise the app really ends - no server, no threads left behind.
                if (Transfers.busy) activity?.moveTaskToBack(true) else activity?.finish()
            }
            "scan", "qr" -> Nav.screen = "connect"
            else -> Nav.screen = "home"
        }
    }
}

// ---- home -----------------------------------------------------------------------

@Composable
fun AddressRow(context: Context) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    val address = myAddress()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Body("This phone", 12.sp, Palette.Faint)
            Title(if (address.isBlank()) "No Wi-Fi" else address, 18.sp, maxLines = 1)
        }
        GlassButton(if (copied) "COPIED" else "COPY", Modifier.width(96.dp), 38.dp, Palette.Blue,
            enabled = address.isNotBlank(), textSize = 12.sp) {
            copyToClipboard(context, address)
            copied = true
        }
    }
}

@Composable
fun HomeScreen() {
    @Suppress("UNUSED_EXPRESSION") Prefs.revision.longValue
    val context = LocalContext.current
    var pendingSend by remember { mutableStateOf<List<Uri>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach {
                try {
                    context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
            }
            pendingSend = uris
        }
    }
    val connected = Link.state == Link.State.Connected
    val lost = Link.state == Link.State.Lost

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Gap(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("LanDrop", 36.sp, modifier = Modifier.weight(1f))
            StatusDot(
                when (Link.state) {
                    Link.State.Connected -> Palette.Green
                    Link.State.Requesting, Link.State.Lost -> Palette.Amber
                    Link.State.Off -> Color(0xFF8A93A6)
                },
                pulsing = Link.state == Link.State.Requesting || lost,
            )
        }

        GlassCard(Modifier.fillMaxWidth(), accent = if (connected) Palette.Green else if (lost) Palette.Amber else null) {
            Title(if (connected) "Connected" else if (lost) "Reconnecting..." else "Not connected", 22.sp)
            Body(if (connected) "${Link.peerName}  ·  ${Link.peerAddress}" else Link.message, 14.sp)
            Gap()
            when {
                connected -> ButtonRow {
                    GlassButton("SPEED TEST", Modifier.weight(1f), 50.dp, Palette.Violet,
                        enabled = !Transfers.busy && !SpeedTest.running, textSize = 14.sp) { SpeedTest.start() }
                    GlassButton("DISCONNECT", Modifier.weight(1f), 50.dp, Palette.Red, textSize = 14.sp) { Link.disconnect() }
                }
                lost -> GlassButton("DISCONNECT", Modifier.fillMaxWidth(), 50.dp, Palette.Red) { Link.disconnect() }
                else -> GlassButton("CONNECT", Modifier.fillMaxWidth(), 50.dp, Palette.Blue) { Nav.screen = "connect" }
            }
            if (connected && SpeedTest.result.isNotBlank()) {
                Gap(8.dp)
                Body(SpeedTest.result, 13.sp, Palette.Faint)
            }
            Gap(10.dp)
            AddressRow(context)
        }

        GlassCard(Modifier.fillMaxWidth(), accent = Palette.Cyan) {
            GlassButton("SEND FILES", Modifier.fillMaxWidth(), 64.dp, Palette.Cyan,
                enabled = connected && !Transfers.busy, textSize = 21.sp) {
                picker.launch(arrayOf("*/*"))
            }
            Gap(10.dp)
            Body(
                when {
                    !connected -> "Connect to a PC or phone first - then pick any files, any size."
                    Transfers.busy -> "A transfer is running - wait for it to finish."
                    else -> "Photos, videos, documents, anything - any size."
                }, 13.sp, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        Transfers.current?.let { TransferCard(it) }

        ButtonRow {
            GlassButton("HISTORY", Modifier.weight(1f), 50.dp, Palette.Violet) { Nav.screen = "history" }
            GlassButton("SETTINGS", Modifier.weight(1f), 50.dp) { Nav.screen = "settings" }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Body("Received files are saved in ${Inbox.folderLabel}", 13.sp, Palette.Faint, modifier = Modifier.weight(1f))
            GlassButton("OPEN FOLDER", Modifier.width(130.dp), 38.dp, textSize = 12.sp) { openFolder(context) }
        }
        Gap(10.dp)
    }

    pendingSend?.let { uris ->
        ConfirmSendPopup(uris, onCancel = { pendingSend = null }) {
            Outgoing.send(uris)
            pendingSend = null
        }
    }
}

@Composable
fun TransferCard(b: Batch) {
    val accent = when (b.state) {
        TState.Done -> Palette.Green
        TState.Cancelled, TState.Failed -> Palette.Red
        TState.Paused -> Palette.Amber
        else -> Palette.Blue
    }
    val transition = rememberInfiniteTransition(label = "wait")
    val blink by transition.animateFloat(0.45f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "blink")
    val stalled = b.state == TState.Active && Link.state == Link.State.Connected &&
        System.currentTimeMillis() - b.lastActivity > 6000
    GlassCard(Modifier.fillMaxWidth(), accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArrowBadge(b.outgoing, accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Title(
                    when (b.state) {
                        TState.Waiting -> if (b.outgoing) "Waiting for ${b.peer}" else "Incoming"
                        TState.Active -> if (b.outgoing) "Sending to ${b.peer}" else "Receiving from ${b.peer}"
                        TState.Paused -> "Paused"
                        TState.Done -> "Done"
                        TState.Cancelled -> "Stopped"
                        TState.Failed -> "Failed"
                    }, 20.sp,
                    modifier = if (b.state == TState.Waiting) Modifier.alpha(blink) else Modifier)
                Body("${if (b.files.size > 1) "File ${b.index + 1} of ${b.files.size}  ·  " else ""}${b.current.name}",
                    13.sp, maxLines = 2)
            }
        }
        Gap(14.dp)
        GlassProgress(b.fraction, accent, indeterminate = b.state == TState.Waiting)
        Gap(8.dp)
        Row(Modifier.fillMaxWidth()) {
            Title("${(b.fraction * 100).toInt()}%", 18.sp)
            Spacer(Modifier.weight(1f))
            Body("${formatSize(b.doneBytes)} / ${formatSize(b.totalBytes)}", 13.sp)
        }
        if (b.state == TState.Active && b.speed > 0) {
            val streams = if (b.outgoing && b.streams > 1) "   ·   ${b.streams} streams" else ""
            Body("${formatSize(b.speed)}/s   ${formatEta(b.eta)}$streams", 13.sp, Palette.Faint)
        }
        val note = if (stalled && b.note.isBlank()) "Waiting for ${b.peer}..." else b.note
        if (note.isNotBlank()) { Gap(4.dp); Body(note, 14.sp) }
        Gap(14.dp)
        when (b.state) {
            TState.Waiting -> GlassButton("CANCEL", Modifier.fillMaxWidth(), 48.dp, Palette.Red) { Transfers.cancel(b) }
            TState.Active -> ButtonRow {
                GlassButton("PAUSE", Modifier.weight(1f), 48.dp, Palette.Amber) { Transfers.pause(b) }
                GlassButton("STOP", Modifier.weight(1f), 48.dp, Palette.Red) { Transfers.cancel(b) }
            }
            TState.Paused -> ButtonRow {
                GlassButton("RESUME", Modifier.weight(1f), 48.dp, Palette.Green) { Transfers.resume(b) }
                GlassButton("STOP", Modifier.weight(1f), 48.dp, Palette.Red) { Transfers.cancel(b) }
            }
            else -> GlassButton("OK", Modifier.fillMaxWidth(), 48.dp, Palette.Green) { Transfers.dismiss() }
        }
    }
}

@Composable
fun ConfirmSendPopup(uris: List<Uri>, onCancel: () -> Unit, onSend: () -> Unit) {
    var files by remember { mutableStateOf<List<FileItem>?>(null) }
    LaunchedEffect(uris) {
        files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            uris.mapIndexed { i, u -> Outgoing.describe(u, i) }
        }
    }
    Dimmer {
        GlassCard(Modifier.fillMaxWidth(), solid = true) {
            val list = files
            Title("Send to ${Link.peerName}?", 24.sp)
            Gap(8.dp)
            if (list == null) {
                Body("Reading the files...")
            } else {
                list.take(6).forEach { Body("•  ${it.name}  (${formatSize(it.size)})", 14.sp, maxLines = 1) }
                if (list.size > 6) Body("…and ${list.size - 6} more", 14.sp)
                Gap(8.dp)
                Title("${list.size} file${if (list.size > 1) "s" else ""}  ·  ${formatSize(list.sumOf { maxOf(it.size, 0L) })}", 16.sp)
            }
            Gap(18.dp)
            ButtonRow {
                GlassButton("CANCEL", Modifier.weight(1f), 50.dp, Palette.Red, onClick = onCancel)
                GlassButton("SEND", Modifier.weight(1f), 50.dp, Palette.Green, enabled = list != null, onClick = onSend)
            }
        }
    }
}

@Composable
fun OfferPopup(b: Batch) {
    Dimmer {
        GlassCard(Modifier.fillMaxWidth(), accent = Palette.Blue, solid = true) {
            Title("${b.peer} wants to send you", 24.sp)
            Gap(8.dp)
            b.files.take(6).forEach { Body("•  ${it.name}  (${formatSize(it.size)})", 14.sp, maxLines = 1) }
            if (b.files.size > 6) Body("…and ${b.files.size - 6} more", 14.sp)
            Gap(8.dp)
            Title("${b.files.size} file${if (b.files.size > 1) "s" else ""}  ·  ${formatSize(b.totalBytes)}", 16.sp)
            Body("Saved in ${Inbox.folderLabel}", 13.sp, Palette.Faint)
            Gap(18.dp)
            ButtonRow {
                GlassButton("DECLINE", Modifier.weight(1f), 50.dp, Palette.Red) { Incoming.decline() }
                GlassButton("ACCEPT", Modifier.weight(1f), 50.dp, Palette.Green) { Incoming.accept() }
            }
        }
    }
}

@Composable
fun PairPopup(r: Link.Request) {
    Dimmer {
        GlassCard(Modifier.fillMaxWidth(), accent = Palette.Blue, solid = true) {
            Title("${r.name} wants to connect", 24.sp)
            Gap(6.dp)
            Body("From ${r.ip}:${r.port}. Allow only a device you recognise - once connected it can send you files (you still accept each transfer).", 14.sp)
            Gap(18.dp)
            ButtonRow {
                GlassButton("DENY", Modifier.weight(1f), 50.dp, Palette.Red) { Link.deny() }
                GlassButton("ALLOW", Modifier.weight(1f), 50.dp, Palette.Green) { Link.allow() }
            }
        }
    }
}

// ---- connect -------------------------------------------------------------------

@Composable
fun ConnectScreen() {
    val context = LocalContext.current
    var address by remember { mutableStateOf(Prefs.peer) }
    var status by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Found>?>(null) }
    val requesting = Link.state == Link.State.Requesting
    LaunchedEffect(Link.state) {
        if (Link.state == Link.State.Connected) Nav.screen = "home"
    }

    fun ask(target: String) {
        status = ""
        Thread {
            val err = Link.requestPair(target)
            status = err ?: ""
        }.start()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Gap(6.dp)
        Title("Connect to a device", 30.sp)
        Body("The other device must allow the connection before anything is sent.", 14.sp)

        if (requesting) {
            GlassCard(Modifier.fillMaxWidth(), accent = Palette.Amber) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(Palette.Amber, pulsing = true)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Title("Asking...", 20.sp)
                        Body(Link.message, 14.sp)
                    }
                }
                Gap(12.dp)
                GlassButton("CANCEL REQUEST", Modifier.fillMaxWidth(), 48.dp, Palette.Red) { Link.cancelRequest() }
            }
        }

        GlassButton(if (scanning) "SCANNING..." else "FIND DEVICES ON THIS NETWORK", Modifier.fillMaxWidth(), 56.dp,
            Palette.Blue, enabled = !scanning && !requesting) {
            scanning = true
            status = ""
            found = null
            Thread {
                val list = Link.discoverAll(3000)
                found = list
                scanning = false
                if (list.isEmpty()) status = "No device answered. Open LanDrop on the other device, same Wi-Fi or LAN. On a PC press FIX FIREWALL in its Settings - or type its address below."
            }.start()
        }
        found?.takeIf { it.isNotEmpty() }?.let { list ->
            GlassCard(Modifier.fillMaxWidth(), accent = Palette.Blue) {
                Body("${list.size} device${if (list.size > 1) "s" else ""} found - tap one to ask it", 13.sp)
                Gap(8.dp)
                list.forEach { f ->
                    GlassButton("${f.name}  ·  ${f.address}", Modifier.fillMaxWidth(), 48.dp, Palette.Green,
                        enabled = !requesting, textSize = 14.sp) { address = f.address; ask(f.address) }
                    Gap(8.dp)
                }
            }
        }
        ButtonRow {
            GlassButton("SCAN A QR CODE", Modifier.weight(1f), 56.dp, Palette.Cyan, enabled = !requesting, textSize = 14.sp) {
                Nav.scanMode = "connect"
                Nav.screen = "scan"
            }
            GlassButton("SHOW MY QR CODE", Modifier.weight(1f), 56.dp, Palette.Cyan, enabled = !requesting, textSize = 14.sp) {
                Nav.screen = "qr"
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Body("Or type the other device's address", 14.sp)
            Gap(8.dp)
            GlassField(address, { address = it }, "192.168.1.20:${Proto.PORT}",
                Modifier.fillMaxWidth(), androidx.compose.ui.text.input.KeyboardType.Uri)
            Gap(10.dp)
            GlassButton("ASK TO CONNECT", Modifier.fillMaxWidth(), 50.dp, Palette.Green,
                enabled = !requesting && address.isNotBlank()) { ask(address) }
        }
        GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
            AddressRow(context)
            Gap(6.dp)
            Body("Another PC or phone can ask this one by typing it; you then get ALLOW / DENY.", 13.sp, Palette.Faint)
        }
        GlassCard(Modifier.fillMaxWidth(), padding = 16.dp, accent = Palette.Violet) {
            Title("Connect two PCs with this phone", 17.sp)
            Body("Scan the QR code of the first PC, then of the second: the first PC asks the second, which shows ALLOW / DENY.", 13.sp)
            Gap(10.dp)
            GlassButton("LINK TWO PCs (SCAN BOTH QR CODES)", Modifier.fillMaxWidth(), 48.dp, Palette.Violet, textSize = 13.sp) {
                Nav.scanMode = "linkA"
                Nav.linkFirst = ""
                Nav.linkStatus = ""
                Nav.screen = "scan"
            }
            if (Nav.linkStatus.isNotBlank()) { Gap(8.dp); Body(Nav.linkStatus, 14.sp, Palette.Amber) }
        }
        if (status.isNotBlank()) {
            Body(status, 15.sp, Palette.Amber, TextAlign.Center, Modifier.fillMaxWidth())
        }
        GlassButton("BACK", Modifier.fillMaxWidth(), 50.dp) { Nav.screen = "home" }
        Gap(10.dp)
    }
}

/** This phone's address as a QR code (for another phone, or a PC with a webcam). Closes once connected. */
@Composable
fun QrScreen() {
    val context = LocalContext.current
    val address = myAddress()
    val bitmap = remember(address) { if (address.isBlank()) null else qrBitmap("${Proto.SCHEME}$address", 720) }
    LaunchedEffect(Link.state) { if (Link.state == Link.State.Connected) Nav.screen = "home" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Gap(6.dp)
        Title("Scan this with the other phone", 28.sp)
        Body("LanDrop on the other phone -> CONNECT -> SCAN A QR CODE. This screen closes by itself once connected.", 14.sp)
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(26.dp)).background(Color.White).padding(18.dp),
            contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), "QR code", Modifier.fillMaxSize())
            else Body("No Wi-Fi address yet", 16.sp, Color.Black)
        }
        GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) { AddressRow(context) }
        GlassButton("BACK", Modifier.fillMaxWidth(), 50.dp) { Nav.screen = "connect" }
        Gap(10.dp)
    }
}

fun qrBitmap(text: String, size: Int): Bitmap? = try {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
} catch (e: Exception) {
    Log.w("qr", "encode", e)
    null
}

// ---- history -------------------------------------------------------------------

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val selected = remember { mutableStateListOf<HistoryItem>() }
    var confirmDelete by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    val selecting = selected.isNotEmpty()

    fun open(item: HistoryItem) {
        try {
            Inbox.viewIntent(item.uri, item.name)?.let { context.startActivity(it) }
                ?: run { info = "This file cannot be opened from here" }
        } catch (e: Exception) {
            Log.w("history", "could not open ${item.name}", e)
            info = "No app can open ${item.name}"
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Gap(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title(if (selecting) "${selected.size} selected" else "History", 30.sp, modifier = Modifier.weight(1f))
            if (selecting) {
                GlassButton("ALL", Modifier.width(80.dp), 42.dp, textSize = 13.sp) {
                    selected.clear(); selected.addAll(History.items)
                }
                Spacer(Modifier.width(8.dp))
                GlassButton("NONE", Modifier.width(90.dp), 42.dp, textSize = 13.sp) { selected.clear() }
            } else if (History.items.isNotEmpty()) {
                GlassButton("CLEAR LIST", Modifier.width(130.dp), 42.dp, Palette.Red, textSize = 13.sp) { History.clear() }
            }
        }
        if (History.items.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Body("Nothing yet. Files you send or receive will be listed here - tap one to open it, hold to select.")
            }
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(History.items) { item ->
                    val sent = item.direction == "sent"
                    val isSel = item in selected
                    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp, selected = isSel,
                        onClick = {
                            if (selecting) { if (isSel) selected.remove(item) else selected.add(item) } else open(item)
                        },
                        onLongClick = { if (!isSel) selected.add(item) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ArrowBadge(sent, if (sent) Palette.Cyan else Palette.Green, 38.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Title(item.name, 16.sp, maxLines = 1)
                                Body("${if (sent) "Sent to" else "From"} ${item.peer}  ·  ${formatSize(item.size)}  ·  ${formatTime(item.time)}",
                                    12.sp, maxLines = 2)
                            }
                            if (item.status != "done") Body(item.status, 12.sp, Palette.Red)
                        }
                    }
                }
            }
        }
        if (info.isNotBlank()) Body(info, 13.sp, Palette.Amber, TextAlign.Center, Modifier.fillMaxWidth())
        if (selecting) {
            ButtonRow {
                GlassButton("OPEN", Modifier.weight(1f), 46.dp, Palette.Blue, enabled = selected.size == 1, textSize = 13.sp) {
                    selected.firstOrNull()?.let { open(it) }
                }
                GlassButton("SHOW FOLDER", Modifier.weight(1f), 46.dp, textSize = 13.sp) { openFolder(context) }
            }
            ButtonRow {
                GlassButton("DELETE FILES", Modifier.weight(1f), 46.dp, Palette.Red, textSize = 13.sp) { confirmDelete = true }
                GlassButton("REMOVE FROM LIST", Modifier.weight(1f), 46.dp, textSize = 13.sp) {
                    History.remove(selected.toList()); selected.clear()
                }
            }
            GlassButton("DONE", Modifier.fillMaxWidth(), 46.dp, Palette.Green) { selected.clear() }
        } else {
            Body("Tap a file to open it. Hold a file to select; then open, delete or remove several at once.", 12.sp, Palette.Faint,
                TextAlign.Center, Modifier.fillMaxWidth())
            GlassButton("BACK", Modifier.fillMaxWidth(), 50.dp) { Nav.screen = "home" }
        }
        Gap(10.dp)
    }

    if (confirmDelete) {
        val list = selected.toList()
        Dimmer {
            GlassCard(Modifier.fillMaxWidth(), accent = Palette.Red, solid = true) {
                Title("Delete ${list.size} file${if (list.size > 1) "s" else ""}?", 24.sp)
                Gap(8.dp)
                list.take(6).forEach { Body("•  ${it.name}", 14.sp, maxLines = 1) }
                if (list.size > 6) Body("…and ${list.size - 6} more", 14.sp)
                Gap(8.dp)
                Body("The files are deleted from this phone. This cannot be undone." +
                    (if (list.any { it.direction == "sent" }) " Sent files are your originals - they are deleted too when the app that holds them allows it." else ""), 13.sp)
                Gap(18.dp)
                ButtonRow {
                    GlassButton("KEEP", Modifier.weight(1f), 50.dp) { confirmDelete = false }
                    GlassButton("DELETE", Modifier.weight(1f), 50.dp, Palette.Red) {
                        confirmDelete = false
                        Thread {
                            val failed = list.count { Inbox.exists(it.uri) && !Inbox.delete(it.uri) }
                            History.remove(list)
                            info = if (failed > 0) "$failed file(s) could not be deleted (not owned by LanDrop)" else "Deleted ${list.size} file(s)"
                        }.start()
                        selected.clear()
                    }
                }
            }
        }
    }
}

// ---- settings ------------------------------------------------------------------

@Composable
fun SettingsScreen() {
    @Suppress("UNUSED_EXPRESSION") Prefs.revision.longValue
    val context = LocalContext.current
    var name by remember { mutableStateOf(Prefs.deviceName) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Gap(6.dp)
        Title("Settings", 30.sp)
        GlassCard(Modifier.fillMaxWidth()) {
            Body("This phone's name (what other devices see)", 13.sp)
            Gap(8.dp)
            GlassField(name, { name = it; Prefs.deviceName = it }, Prefs.defaultName(), Modifier.fillMaxWidth())
        }
        GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
            Title("Always with your consent", 17.sp)
            Body("A device connects only after you ALLOW it, and every incoming transfer asks you first. A connection that drops (Wi-Fi blip) comes back by itself; DISCONNECT ends it for good.", 13.sp)
        }
        SwitchRow("Keep the screen on", "While a transfer is running",
            Prefs.keepAwake) { Prefs.keepAwake = it }
        ChoiceRow("Transfer mode", "Auto: 3 parallel streams, chunk size by file size. Fast: 4 streams, 8 MB chunks for a strong network. Reliable: 1 stream, 1 MB chunks for a flaky Wi-Fi. Every chunk is verified.",
            listOf("auto" to "AUTO", "fast" to "FAST", "reliable" to "RELIABLE"), Prefs.transferMode) { Prefs.transferMode = it }
        SwitchRow("Verify every chunk", "SHA-256 checksum on arrival - a damaged chunk is sent again",
            Prefs.verify) { Prefs.verify = it }
        SwitchRow("Wi-Fi boost while transferring", "Keeps Wi-Fi at full speed instead of power saving",
            Prefs.wifiBoost) { Prefs.wifiBoost = it }
        GlassCard(Modifier.fillMaxWidth()) {
            Body("Received files", 13.sp)
            Title(Inbox.folderLabel, 20.sp)
            Gap(10.dp)
            GlassButton("OPEN FOLDER", Modifier.fillMaxWidth(), 46.dp, Palette.Blue) { openFolder(context) }
        }
        Body("LanDrop v${BuildConfig.VERSION_NAME}  ·  port ${Proto.PORT}  ·  ${myAddress()}", 12.sp, Palette.Faint,
            TextAlign.Center, Modifier.fillMaxWidth())
        GlassButton("BACK", Modifier.fillMaxWidth(), 50.dp) { Nav.screen = "home" }
        Gap(10.dp)
    }
}
