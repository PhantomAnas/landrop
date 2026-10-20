package com.phantomanas.landrop

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Scans the "landrop://ip:port" QR the PC app shows, with the back camera. */
@Composable
fun ScanScreen() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var view by remember { mutableStateOf<PreviewView?>(null) }
    var asked by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Point the camera at the QR code in the PC app") }
    val done = remember { booleanArrayOf(false) }
    val provider = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    val preview = remember { androidx.camera.core.Preview.Builder().build() }
    val analysis = remember {
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        asked = true
        granted = ok
        if (!ok) status = "The camera permission is needed to scan. Type the address instead."
    }
    LaunchedEffect(Unit) { ask.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(Link.state) { if (Link.state == Link.State.Connected) Nav.screen = "home" }

    LaunchedEffect(asked, granted, view) {
        val v = view
        if (!asked || !granted || v == null || provider[0] != null) return@LaunchedEffect
        try {
            val p = ProcessCameraProvider.awaitInstance(context)
            provider[0] = p
            val selector = if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            val reader = MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
            }
            analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { image ->
                image.use {
                    if (done[0]) return@use
                    val text = try {
                        val buffer = it.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val source = PlanarYUVLuminanceSource(bytes, it.planes[0].rowStride, it.height,
                            0, 0, it.width, it.height, false)
                        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
                    } catch (_: Exception) {
                        null
                    } finally {
                        reader.reset()
                    }
                    if (text != null) {
                        val host = text.removePrefix(Proto.SCHEME).trim().trimEnd('/')
                        if (Regex("^[0-9A-Za-z_.-]+:\\d{2,5}$").matches(host)) {
                            done[0] = true
                            status = "Found $host - asking it to connect..."
                            Log.i("scan", "QR scanned: $host")
                            Thread {
                                val err = Link.requestPair(host)
                                if (err != null) {
                                    status = err
                                    done[0] = false
                                }
                            }.start()
                        }
                    }
                }
            }
            preview.surfaceProvider = v.surfaceProvider
            p.bindToLifecycle(owner, selector, preview, analysis)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("scan", "camera failed", e)
            status = "The camera could not be started: ${e.message}"
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            try { provider[0]?.unbind(preview, analysis) } catch (_: Exception) {}
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Gap(6.dp)
        Title("Scan a PC's QR code", 28.sp)
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(26.dp))) {
            AndroidView(
                factory = { c -> PreviewView(c).also { it.scaleType = PreviewView.ScaleType.FILL_CENTER; view = it } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Body(status, 15.sp, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        GlassButton("BACK", Modifier.fillMaxWidth(), 50.dp) { Nav.screen = "connect" }
        Gap(10.dp)
    }
}
