package com.phantomanas.landrop

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Nav {
    var screen by mutableStateOf("home")
}

@Composable
fun Root() {
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
                    "history" -> HistoryScreen()
                    "settings" -> SettingsScreen()
                    else -> HomeScreen()
                }
            }
        }
        Transfers.offer?.let { OfferPopup(it) }
    }
    BackHandler(enabled = Nav.screen != "home") {
        Nav.screen = if (Nav.screen == "scan") "connect" else "home"
    }
}

// ---- home -----------------------------------------------------------------------

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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Gap(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("LanDrop", 36.sp, modifier = Modifier.weight(1f))
            StatusDot(
                when (Link.state) {
                    Link.State.Connected -> Palette.Green
                    Link.State.Searching -> Palette.Amber
                    Link.State.Off -> Color(0xFF8A93A6)
                },
                pulsing = Link.state == Link.State.Searching,
            )
        }

        GlassCard(Modifier.fillMaxWidth(), accent = if (connected) Palette.Green else null) {
            Title(if (connected) "Connected" else "Not connected", 22.sp)
            Body(if (connected) "${Link.peerName}  ·  ${Link.peerAddress}" else Link.message, 14.sp)
            Gap()
            if (connected) {
                GlassButton("DISCONNECT", Modifier.fillMaxWidth(), 50.dp, Palette.Red) { Link.disconnect() }
            } else {
                GlassButton("CONNECT", Modifier.fillMaxWidth(), 50.dp, Palette.Blue) {
                    Link.resumeAuto()
                    Nav.screen = "connect"
                }
            }
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
        Body("Files you receive are saved in ${Inbox.folderLabel}", 13.sp, Palette.Faint,
            TextAlign.Center, Modifier.fillMaxWidth())
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
    val stalled = b.state == TState.Active && !b.outgoing &&
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
            Body("${formatSize(b.speed)}/s   ${formatEta(b.eta)}", 13.sp, Palette.Faint)
        }
        val note = if (stalled) "Waiting for ${b.peer}..." else b.note
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
        GlassCard(Modifier.fillMaxWidth()) {
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
        GlassCard(Modifier.fillMaxWidth(), accent = Palette.Blue) {
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

// ---- connect -------------------------------------------------------------------

@Composable
fun ConnectScreen() {
    var address by remember { mutableStateOf(Prefs.peer) }
    var status by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    LaunchedEffect(Link.state) {
        if (Link.state == Link.State.Connected) Nav.screen = "home"
    }

    fun connectTo(target: String) {
        working = true
        status = "Connecting..."
        found = emptyList()
        Thread {
            val err = Link.connect(target)
            status = err ?: ""
            working = false
        }.start()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Gap(6.dp)
        Title("Connect to a device", 30.sp)
        GlassCard(Modifier.fillMaxWidth()) {
            Body("This phone's address", 13.sp)
            Title(if (Link.localIp.isBlank()) "No Wi-Fi" else "${Link.localIp}:${Proto.PORT}", 24.sp)
            Body("Another PC or phone can connect to this one by typing this.", 13.sp, Palette.Faint)
        }
        GlassButton("FIND DEVICES ON THIS NETWORK", Modifier.fillMaxWidth(), 56.dp, Palette.Blue, enabled = !working) {
            working = true
            status = "Searching the network..."
            found = emptyList()
            Link.resumeAuto()
            Thread {
                val list = Link.discoverAll(2500)
                when (list.size) {
                    0 -> {
                        status = "No device answered. Open LanDrop on the other device, same Wi-Fi (on a PC, allow Python through the Windows firewall)."
                        working = false
                    }
                    1 -> {
                        address = list[0].address
                        val err = Link.connect(list[0].address)
                        status = err ?: ""
                        working = false
                    }
                    else -> {
                        found = list
                        status = "${list.size} devices found - pick one"
                        working = false
                    }
                }
            }.start()
        }
        if (found.isNotEmpty()) {
            GlassCard(Modifier.fillMaxWidth(), accent = Palette.Blue) {
                Body("Devices found", 13.sp)
                Gap(8.dp)
                found.forEach { f ->
                    GlassButton("${f.name}  ·  ${f.address}", Modifier.fillMaxWidth(), 48.dp, Palette.Green,
                        textSize = 14.sp) { address = f.address; connectTo(f.address) }
                    Gap(8.dp)
                }
            }
        }
        GlassButton("SCAN THE QR CODE ON A PC", Modifier.fillMaxWidth(), 56.dp, Palette.Cyan, enabled = !working) {
            Nav.screen = "scan"
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Body("Or type the other device's address", 14.sp)
            Gap(8.dp)
            GlassField(address, { address = it }, "192.168.1.20:${Proto.PORT}",
                Modifier.fillMaxWidth(), androidx.compose.ui.text.input.KeyboardType.Uri)
            Gap(10.dp)
            GlassButton("CONNECT", Modifier.fillMaxWidth(), 50.dp, Palette.Green,
                enabled = !working && address.isNotBlank()) { connectTo(address) }
        }
        if (status.isNotBlank() || working) {
            Body(if (working && status.isBlank()) "Working..." else status, 15.sp,
                if (working) Palette.Muted else Palette.Amber, TextAlign.Center, Modifier.fillMaxWidth())
        }
        GlassButton("BACK", Modifier.fillMaxWidth(), 50.dp) { Nav.screen = "home" }
        Gap(10.dp)
    }
}

// ---- history -------------------------------------------------------------------

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Gap(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("History", 30.sp, modifier = Modifier.weight(1f))
            if (History.items.isNotEmpty()) {
                GlassButton("CLEAR", Modifier.width(110.dp), 42.dp, Palette.Red) { History.clear() }
            }
        }
        if (History.items.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Body("Nothing yet. Files you send or receive will be listed here - tap one to open it.")
            }
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(History.items) { item ->
                    val sent = item.direction == "sent"
                    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp, onClick = {
                        try {
                            Inbox.viewIntent(item.uri, item.name)?.let { context.startActivity(it) }
                        } catch (e: Exception) {
                            Log.w("history", "could not open ${item.name}", e)
                        }
                    }) {
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
        GlassButton("BACK", Modifier.fillMaxWidth(), 50.dp) { Nav.screen = "home" }
        Gap(10.dp)
    }
}

// ---- settings ------------------------------------------------------------------

@Composable
fun SettingsScreen() {
    @Suppress("UNUSED_EXPRESSION") Prefs.revision.longValue
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
        SwitchRow("Auto-connect", "Reconnect to the last device and search the network by itself",
            Prefs.autoConnect) { Prefs.autoConnect = it; if (it) Link.resumeAuto() }
        SwitchRow("Ask before receiving", "Show accept / decline when a device sends files",
            Prefs.askBeforeReceive) { Prefs.askBeforeReceive = it }
        SwitchRow("Keep the screen on", "While a transfer is running",
            Prefs.keepAwake) { Prefs.keepAwake = it }
        ChoiceRow("Transfer mode", "Auto picks the chunk size per file. Fast = big chunks for a solid Wi-Fi; Reliable = small chunks that retry cheaply on a flaky one.",
            listOf("auto" to "AUTO", "fast" to "FAST", "reliable" to "RELIABLE"), Prefs.transferMode) { Prefs.transferMode = it }
        SwitchRow("Verify every file", "SHA-256 checksum on arrival - a damaged file is deleted and reported",
            Prefs.verify) { Prefs.verify = it }
        SwitchRow("Wi-Fi boost while transferring", "Keeps Wi-Fi at full speed instead of power saving",
            Prefs.wifiBoost) { Prefs.wifiBoost = it }
        GlassCard(Modifier.fillMaxWidth()) {
            Body("Received files", 13.sp)
            Title(Inbox.folderLabel, 20.sp)
            Body("Open it with the Files app or any file manager.", 13.sp, Palette.Faint)
        }
        Body("LanDrop v${BuildConfig.VERSION_NAME}  ·  port ${Proto.PORT}", 12.sp, Palette.Faint,
            TextAlign.Center, Modifier.fillMaxWidth())
        GlassButton("BACK", Modifier.fillMaxWidth(), 50.dp) { Nav.screen = "home" }
        Gap(10.dp)
    }
}
