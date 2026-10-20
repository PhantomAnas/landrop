package com.phantomanas.landrop

import android.Manifest
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.LocalActivity
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        setContent {
            Root()
            KeepAwake()
        }
        askStartupPermissions()
        debugHook(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        debugHook(intent)
    }

    /**
     * DEBUG BUILDS ONLY (the release build has none of this): lets the emulator test
     * harness drive the app over adb, e.g.
     *   am start -n <pkg>/.MainActivity --es send "/path/a|/path/b"   (file paths this app can read)
     *   am start -n <pkg>/.MainActivity --es act accept|decline|allow|deny|pause|resume|stop|disconnect|connect:<addr>
     */
    private fun debugHook(intent: android.content.Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        intent.getStringExtra("send")?.let { paths ->
            val uris = paths.split("|").filter { it.isNotBlank() }.map { java.io.File(it) }
                .flatMap { f -> if (f.isDirectory) f.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList() else listOf(f) }
                .map { android.net.Uri.fromFile(it) }
            uris.forEach { val f = java.io.File(it.path!!); Log.i("debug", "file ${f.name} canRead=${f.canRead()} length=${f.length()}") }
            Log.i("debug", "send ${uris.size} file(s)")
            Outgoing.send(uris)
        }
        intent.getStringExtra("sendhist")?.let { names ->
            // Send files this app received earlier (MediaStore URIs it owns) - the real content-URI path.
            val wanted = names.split("|").filter { it.isNotBlank() }
            val uris = wanted.mapNotNull { n -> History.items.firstOrNull { it.name == n && it.direction == "received" && it.uri.isNotBlank() }?.uri }
                .map { android.net.Uri.parse(it) }
            Log.i("debug", "sendhist ${uris.size}/${wanted.size} file(s)")
            Outgoing.send(uris)
        }
        intent.getStringExtra("act")?.let { act ->
            Log.i("debug", "act $act")
            when {
                act == "accept" -> Incoming.accept()
                act == "decline" -> Incoming.decline()
                act == "allow" -> Link.allow()
                act == "deny" -> Link.deny()
                act == "pause" -> Transfers.current?.let { Transfers.pause(it) }
                act == "resume" -> Transfers.current?.let { Transfers.resume(it) }
                act == "stop" -> Transfers.current?.let { Transfers.cancel(it) }
                act == "disconnect" -> Link.disconnect()
                act == "speed" -> SpeedTest.start()
                act.startsWith("connect:") -> Thread { Link.requestPair(act.removePrefix("connect:")) }.start()
                act.startsWith("page:") -> Nav.screen = act.removePrefix("page:")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Wifi.multicast(true)         // so FIND on a PC can reach this phone (broadcasts are filtered otherwise)
    }

    override fun onStop() {
        super.onStop()
        if (!Transfers.busy) Wifi.multicast(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Backed out of the app (not a rotation, not a quick switch): end everything.
        if (isFinishing && !isChangingConfigurations) App.shutdown()
    }

    /** Notifications (for the transfer notification, Android 13+) and legacy storage (Android 9-). */
    private fun askStartupPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
}

/** Keeps the screen on while a transfer runs (setting). */
@Composable
private fun KeepAwake() {
    @Suppress("UNUSED_EXPRESSION") Prefs.revision.longValue
    val activity = LocalActivity.current
    val on = Prefs.keepAwake && Transfers.busy
    LaunchedEffect(on, activity) {
        val w = activity?.window ?: return@LaunchedEffect
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
