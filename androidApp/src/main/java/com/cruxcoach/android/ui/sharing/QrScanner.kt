package com.cruxcoach.android.ui.sharing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cruxcoach.android.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The back camera reading QR codes, after the person granted the camera (the
 * permission exists only in debug builds, where the sharing preview lives).
 * Frames stay in memory; the first code found ends the scan.
 */
@Composable
internal fun CameraQrScanner(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Column(modifier) {
            Text(stringResource(R.string.sharing_scan_camera), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("sharing_scan_camera"))
            if (asked) Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.sharing_scan_allow)) }
        }
        return
    }
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val found = remember { AtomicBoolean(false) }
    DisposableEffect(owner) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().aspectRatio(1f).testTag("sharing_scan_preview"),
        factory = { viewContext ->
            val view = PreviewView(viewContext)
            val future = ProcessCameraProvider.getInstance(viewContext)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    val text = image.use {
                        val plane = it.planes[0]
                        val bytes = ByteArray(plane.buffer.remaining()).also { b -> plane.buffer.get(b) }
                        QrCodes.decode(bytes, plane.rowStride, it.width, it.height)
                    }
                    if (text != null && found.compareAndSet(false, true)) view.post { onCode(text) }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(viewContext))
            view
        },
    )
}
