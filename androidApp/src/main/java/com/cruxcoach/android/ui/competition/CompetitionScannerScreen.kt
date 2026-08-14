package com.cruxcoach.android.ui.competition

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cruxcoach.android.R
import com.cruxcoach.android.competition.CompetitionQrDecoder
import com.cruxcoach.android.competition.CompetitionShareLink
import java.util.concurrent.Executors

/**
 * Scanning a competition QR with the app's own camera.
 *
 * Deliberately not the only way in: a competition can also be opened from an
 * App Link, from a pasted `naddr`, or from the share sheet, and all three keep
 * working. This exists because at a gym the code is on a wall and the phone is
 * already in the app, and switching to the system camera to come back again is
 * a worse version of the same thing.
 *
 * The rules this screen follows:
 *
 *   - the camera permission is requested when the scanner is opened, never at
 *     startup, and a refusal is a normal outcome with its own explanation
 *   - a permanent refusal says so and offers system settings, because asking
 *     again does nothing on Android and looks broken
 *   - the camera is released when the screen goes away, not when the process
 *     does
 *   - only the CruxCoach competition link forms are accepted; anything else is
 *     named rather than ignored, so somebody pointing at a climb QR is told it
 *     is a climb QR
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompetitionScannerScreen(
    onNavigateBack: () -> Unit,
    onCompetition: (CompetitionShareLink.Ref) -> Unit,
    decoder: CompetitionQrDecoder = remember { CompetitionQrDecoder() },
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var asked by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<CompetitionShareLink.Scan?>(null) }
    var cameraFailed by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        asked = true
        // Android gives no "never ask again" flag directly. A refusal that
        // arrives without the system dialog having been shown is what that
        // state looks like from here, and shouldShowRationale is how it is read.
        if (!result) permanentlyDenied = !shouldShowRationale(context)
    }

    // Asked on arrival, not at app start, and only once per visit.
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.comp_scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                cameraFailed -> {
                    // Permission was given and the camera still would not
                    // start: no back camera, or another app holding it. Saying
                    // "allow the camera" here would be wrong twice over.
                    Text(stringResource(R.string.comp_scan_camera_failed))
                    OtherWaysIn(onNavigateBack)
                }

                granted -> {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        CameraPreview(
                            decoder = decoder,
                            onText = { text ->
                                when (val scan = CompetitionShareLink.classify(text)) {
                                    is CompetitionShareLink.Scan.Competition -> onCompetition(scan.ref)
                                    else -> problem = scan
                                }
                            },
                            onCameraFailed = { cameraFailed = true },
                        )
                    }
                    Text(stringResource(R.string.comp_scan_hint))
                    problem?.let { scan ->
                        Text(
                            scanMessage(scan),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .testTag("competition_scan_problem")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }

                permanentlyDenied -> {
                    Text(stringResource(R.string.comp_scan_denied_forever))
                    Button(
                        onClick = { openAppSettings(context) },
                        modifier = Modifier.testTag("competition_scan_settings"),
                    ) { Text(stringResource(R.string.comp_scan_open_settings)) }
                    OtherWaysIn(onNavigateBack)
                }

                asked -> {
                    Text(stringResource(R.string.comp_scan_denied))
                    Button(
                        onClick = { launcher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.testTag("competition_scan_retry"),
                    ) { Text(stringResource(R.string.comp_scan_allow)) }
                    OtherWaysIn(onNavigateBack)
                }

                else -> Text(stringResource(R.string.comp_scan_asking))
            }
        }
    }
}

/** The scanner is a convenience; the ways in that never needed a camera stay. */
@Composable
private fun OtherWaysIn(onNavigateBack: () -> Unit) {
    Text(stringResource(R.string.comp_scan_other_ways))
    OutlinedButton(
        onClick = onNavigateBack,
        modifier = Modifier.testTag("competition_scan_back"),
    ) { Text(stringResource(R.string.comp_scan_paste_instead)) }
}

@Composable
private fun scanMessage(scan: CompetitionShareLink.Scan): String = when (scan) {
    is CompetitionShareLink.Scan.Climb -> stringResource(R.string.comp_scan_is_climb)
    CompetitionShareLink.Scan.OtherNostr -> stringResource(R.string.comp_scan_other_nostr)
    CompetitionShareLink.Scan.Damaged -> stringResource(R.string.comp_scan_damaged)
    else -> stringResource(R.string.comp_scan_unknown)
}

/**
 * The camera itself.
 *
 * One analyser, one executor, both shut down with the composable. Frames are
 * dropped rather than queued — a backlog of stale frames is how a scanner ends
 * up reading a code the phone is no longer pointing at.
 */
@Composable
private fun CameraPreview(
    decoder: CompetitionQrDecoder,
    onText: (String) -> Unit,
    onCameraFailed: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val handle by rememberUpdatedState(onText)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    // Duplicate suppression: a QR sits in frame for many frames, and firing on
    // every one of them would navigate repeatedly.
    val lastText = remember { arrayOfNulls<String>(1) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            // A provider that cannot start, or a device with no back camera,
            // must leave a message on screen rather than take the process down.
            val bound = runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        readFrame(decoder, image)?.let { text ->
                            // Duplicate suppression: a QR sits in frame for many
                            // frames and firing on each would navigate repeatedly.
                            if (text != lastText[0]) {
                                lastText[0] = text
                                previewView.post { handle(text) }
                            }
                        }
                    } catch (_: Exception) {
                        // One unreadable frame is not a reason to stop scanning.
                    } finally {
                        // Always: a frame that is not closed stalls the camera
                        // for good, and the screen simply freezes.
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }.isSuccess
            if (!bound) onCameraFailed()
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize().testTag("competition_scan_preview"),
    )
}

/**
 * Turn one analyser frame into text, if it holds any.
 *
 * The plane is repacked using its own `rowStride` and `pixelStride` rather than
 * assumed to be tightly packed, and cropped to the rectangle the camera calls
 * valid. Both are why a scanner can pass a synthetic test and fail on a phone.
 *
 * Rotation is tried second rather than first: `imageInfo.rotationDegrees` says
 * how the sensor is mounted, and a QR is square, so an upright code usually
 * decodes without it. Trying the cheap path first keeps the common case fast.
 */
private fun readFrame(decoder: CompetitionQrDecoder, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val crop = image.cropRect
    val width = if (crop.width() > 0) crop.width() else image.width
    val height = if (crop.height() > 0) crop.height() else image.height

    val packed = decoder.packLuminance(
        plane = bytes,
        width = width,
        height = height,
        rowStride = plane.rowStride,
        pixelStride = plane.pixelStride,
        left = crop.left.coerceAtLeast(0),
        top = crop.top.coerceAtLeast(0),
    ) ?: return null

    decoder.decode(packed, width, height)?.let { return it }
    // A code partly out of frame can read one way and not the other, and the
    // sensor's own orientation decides which.
    val quarterTurns = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
    return if (quarterTurns == 0) null else decoder.decode(packed, width, height, rotate = true)
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun shouldShowRationale(context: Context): Boolean {
    val activity = context as? android.app.Activity ?: return false
    return activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
