package com.portnoir.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.portnoir.PortNoirApp
import com.portnoir.net.ConnectionService
import com.portnoir.net.PairingPayload
import com.portnoir.net.rememberWifiConnected
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalGetImage::class)
@Composable
fun ScanScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val app = ctx.applicationContext as PortNoirApp
    val scope = rememberCoroutineScope()
    val busy = remember { AtomicBoolean(false) }
    var status by remember { mutableStateOf("Point the camera at the code on your Mac") }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    val wifi by rememberWifiConnected()

    fun onCode(raw: String) {
        if (!wifi) return
        if (!busy.compareAndSet(false, true)) return
        val payload = PairingPayload.parse(raw)
        if (payload == null) {
            status = "That is not a PortNoir code"
            busy.set(false)
            return
        }
        status = "Pairing with ${payload.name}…"
        scope.launch {
            app.connections.pair(payload)
                .onSuccess { ConnectionService.start(ctx); onBack() }
                .onFailure { status = "Pairing failed: ${it.message}. Show a new code on your Mac and try again."; busy.set(false) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (granted) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                val preview = PreviewView(c)
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    val provider = future.get()
                    val previewUse = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                    val scanner = BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(c)) { proxy ->
                        val media = proxy.image
                        if (media == null) { proxy.close(); return@setAnalyzer }
                        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let(::onCode) }
                            .addOnCompleteListener { proxy.close() }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, analysis)
                }, ContextCompat.getMainExecutor(c))
                preview
            })
        }
        WifiOffBanner(Modifier.align(Alignment.TopCenter).statusBarsPadding())
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).navigationBarsPadding(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (granted) status else "PortNoir needs the camera to scan the pairing code",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onBack) { Text("Cancel") }
            }
        }
    }
}
