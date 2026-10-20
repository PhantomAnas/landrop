package com.phantomanas.landrop

import android.Manifest
import android.app.Activity
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
import androidx.compose.ui.platform.LocalContext
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
    val activity = LocalContext.current as? Activity
    val on = Prefs.keepAwake && Transfers.busy
    LaunchedEffect(on, activity) {
        val w = activity?.window ?: return@LaunchedEffect
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
