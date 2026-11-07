package com.cruxcoach.android.ui.boardscan

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.cruxcoach.android.boardscan.ScanFrame
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Hands camera frames to a scan on request. Between requests every frame is closed unread; a requested frame is
 * copied once into memory for the recogniser and dropped after it. Nothing is written anywhere.
 */
class ScanFrameGrabber {
    private val pending = AtomicReference<CompletableDeferred<ScanFrame>?>(null)

    /** Focal length over the sensor's long side (mm / mm); the frame's focal length in pixels follows from it. */
    @Volatile var focalPerLongSide: Double? = null

    /** The latest gravity reading in the phone's coordinates (x right, y up, z out of the screen); points up. */
    @Volatile var gravity: FloatArray? = null

    /** The next camera frame after this call. */
    suspend fun next(): ScanFrame {
        val d = CompletableDeferred<ScanFrame>()
        pending.set(d)
        try {
            return d.await()
        } finally {
            pending.compareAndSet(d, null)
        }
    }

    /** Called on the camera's analysis thread for every frame. */
    fun offer(image: ImageProxy) {
        val d = pending.getAndSet(null) ?: return
        // a failure (e.g. out of memory on an older phone) goes to the waiting scan, not the camera thread
        try {
            d.complete(toFrame(image))
        } catch (t: Throwable) {
            d.completeExceptionally(t)
        }
    }

    private fun toFrame(image: ImageProxy): ScanFrame {
        val plane = image.planes[0]
        val buf = plane.buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val argb = rotateRgba(bytes, image.width, image.height, plane.rowStride, plane.pixelStride, image.imageInfo.rotationDegrees)
        val turned = image.imageInfo.rotationDegrees % 180 != 0
        val w = if (turned) image.height else image.width
        val h = if (turned) image.width else image.height
        val f = (focalPerLongSide ?: FALLBACK_FOCAL_PER_LONG_SIDE) * max(w, h)
        // camera coordinates of a portrait phone's back camera: x right, y down, z into the scene
        val up = gravity?.let { g -> doubleArrayOf(g[0].toDouble(), -g[1].toDouble(), -g[2].toDouble()) }
        return ScanFrame(argb, w, h, f, up)
    }

    companion object {
        /** A typical main camera (~26 mm equivalent) when the camera does not report its optics. */
        const val FALLBACK_FOCAL_PER_LONG_SIDE = 0.75
    }
}

/**
 * RGBA rows (with padding) turned upright by [rotation] degrees clockwise, as ARGB pixels. Pure; JVM-testable.
 */
internal fun rotateRgba(bytes: ByteArray, w: Int, h: Int, rowStride: Int, pixelStride: Int, rotation: Int): IntArray {
    val turned = rotation % 180 != 0
    val ow = if (turned) h else w
    val out = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * rowStride
        for (x in 0 until w) {
            val o = row + x * pixelStride
            val c = (0xff shl 24) or ((bytes[o].toInt() and 0xff) shl 16) or
                ((bytes[o + 1].toInt() and 0xff) shl 8) or (bytes[o + 2].toInt() and 0xff)
            val i = when (rotation) {
                90 -> x * ow + (h - 1 - y)
                180 -> (h - 1 - y) * ow + (w - 1 - x)
                270 -> (w - 1 - x) * ow + y
                else -> y * ow + x
            }
            out[i] = c
        }
    }
    return out
}

/** CameraX preview plus an RGBA analysis stream at about 1280x720 feeding [grabber]; gravity from the sensors. */
@Composable
internal fun ScanCameraView(grabber: ScanFrameGrabber, onCameraError: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val view = remember { PreviewView(context) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    LaunchedEffect(Unit) {
        val cameras = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                ).build(),
            )
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { image -> image.use { grabber.offer(it) } }
        runCatching {
            cameras.unbindAll()
            val camera = cameras.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            grabber.focalPerLongSide = focalPerLongSide(camera)
        }.onFailure {
            Log.w("BoardScan", "camera bind failed", it)
            onCameraError()
        }
        provider = cameras
    }
    DisposableEffect(Unit) {
        val listener = GravityListener(context, grabber)
        listener.start()
        // Unbind first, so no frame reaches the analyzer after its executor is gone.
        onDispose {
            listener.stop()
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(modifier = modifier, factory = { view })
}

/** Focal length over the sensor's long side, from the camera's reported optics; null when it reports none. */
@OptIn(ExperimentalCamera2Interop::class)
private fun focalPerLongSide(camera: Camera): Double? = runCatching {
    val info = Camera2CameraInfo.from(camera.cameraInfo)
    val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
    val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    if (focal == null || sensor == null || focal <= 0f) null else focal.toDouble() / max(sensor.width, sensor.height)
}.getOrNull()

/** Keeps [ScanFrameGrabber.gravity] current: the gravity sensor, or a smoothed accelerometer where there is none. */
private class GravityListener(context: Context, private val grabber: ScanFrameGrabber) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val sensor = gravitySensor ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val smoothed = FloatArray(3)
    private var seen = false

    fun start() {
        sensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        if (gravitySensor != null) {
            grabber.gravity = event.values.copyOf(3)
            return
        }
        val a = if (seen) 0.1f else 1f
        for (i in 0 until 3) smoothed[i] += a * (event.values[i] - smoothed[i])
        seen = true
        grabber.gravity = smoothed.copyOf()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
