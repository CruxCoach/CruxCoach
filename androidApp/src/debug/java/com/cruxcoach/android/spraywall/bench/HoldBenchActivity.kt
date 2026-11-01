package com.cruxcoach.android.spraywall.bench

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.cruxcoach.android.spraywall.HoldModel
import com.cruxcoach.android.spraywall.HoldModelStore
import com.cruxcoach.android.spraywall.OnnxHoldDetector
import com.cruxcoach.domain.spraywall.detect.HoldCandidate
import com.cruxcoach.domain.spraywall.detect.Resampling
import com.cruxcoach.domain.spraywall.detect.RgbImage
import com.cruxcoach.domain.spraywall.detect.SegSettings
import com.cruxcoach.domain.spraywall.detect.SprayHoldDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * Debug and test builds only: runs hold detection over the photos in
 * `files/bench/photos` with the settings given as extras and writes
 * `files/bench/out/<label>.json` (candidates, time per photo, peak memory).
 * Started over adb, one settings set per process, so the peak memory belongs
 * to that set:
 *
 *     am start -S -W -n <package>/com.cruxcoach.android.spraywall.bench.HoldBenchActivity \
 *         --es label base --ei rows 2 --ei cols 2 --es overlap 0.15 --ef floor 0.3
 *
 * Extras: mode (seg|classic), longSide, rows, cols, overlap, threshold, floor,
 * nms, outlines, upsample, tolerance, maxPoints, resampler (lanczos3|bilinear|native),
 * seam, threads, repeat (timing passes per photo), only (comma-separated photo names).
 */
class HoldBenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dir = File(filesDir, "bench")
        val label = intent.getStringExtra("label")?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }
        if (label == null || !File(dir, "photos").isDirectory) {
            finish()
            return
        }
        Thread {
            val out = File(dir, "out").apply { mkdirs() }
            val result = runCatching { run(dir, label) }.getOrElse { e ->
                JSONObject().put("label", label).put("error", "${e::class.java.simpleName}: ${e.message}")
            }
            val tmp = File(out, "$label.json.tmp")
            tmp.writeText(result.toString())
            tmp.renameTo(File(out, "$label.json"))
            runOnUiThread { finish() }
        }.start()
    }

    private fun run(dir: File, label: String): JSONObject {
        val x = intent
        val mode = x.getStringExtra("mode") ?: "seg"
        val settings = SegSettings(
            longSide = x.getIntExtra("longSide", 1024),
            rows = x.getIntExtra("rows", 2),
            cols = x.getIntExtra("cols", 2),
            overlap = x.getStringExtra("overlap")?.toDouble() ?: 0.15,
            threshold = x.getFloatExtra("threshold", 0.6f),
            floor = x.getFloatExtra("floor", 0.6f),
            nmsIou = x.getFloatExtra("nms", 0.5f),
            outlines = x.getBooleanExtra("outlines", true),
            maskUpsample = x.getIntExtra("upsample", 4),
            simplifyTolerance = x.getFloatExtra("tolerance", 0.5f),
            maxRingPoints = x.getIntExtra("maxPoints", 150),
            resampler = when (x.getStringExtra("resampler")) {
                "bilinear" -> SegSettings.Resampler.BILINEAR
                "native" -> SegSettings.Resampler.NATIVE
                else -> SegSettings.Resampler.LANCZOS3
            },
            seamAware = x.getBooleanExtra("seam", false),
        )
        val threads = x.getIntExtra("threads", OnnxHoldDetector.DEFAULT_THREADS)
        val repeat = x.getIntExtra("repeat", 1).coerceIn(1, 10)
        val only = x.getStringExtra("only")?.split(',')?.toSet()
        val photos = File(dir, "photos").listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            .filter { only == null || it.name in only }
            .sortedBy { it.name }
        val json = JSONObject()
            .put("label", label).put("mode", mode).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("cores", Runtime.getRuntime().availableProcessors()).put("threads", threads).put("repeat", repeat)
            .put("settings", JSONObject(settings.toString().substringAfter("(").removeSuffix(")").split(", ").associate {
                it.substringBefore("=") to it.substringAfter("=")
            }))
            .put("rssStartKb", status("VmRSS")).put("hwmStartKb", status("VmHWM"))
        val model = File(filesDir, "${HoldModelStore.DIR}/${HoldModel.FILE_NAME}").takeIf { it.length() == HoldModel.BYTES }
            ?: File(dir, "model.onnx")
        val t0 = System.nanoTime()
        val detector = if (mode == "seg") OnnxHoldDetector(model, threads) else null
        json.put("modelLoadMs", (System.nanoTime() - t0) / 1_000_000)
        val rows = JSONArray()
        try {
            for (photo in photos) {
                val d0 = System.nanoTime()
                val bitmap = if (mode == "seg") decode(photo, settings.longSide) else null
                val image = if (bitmap == null) load(photo, CLASSIC_LONG_SIDE) else null
                val decodeMs = (System.nanoTime() - d0) / 1_000_000
                val row = JSONObject().put("name", photo.name).put("decodeMs", decodeMs)
                    .put("width", bitmap?.width ?: image!!.width).put("height", bitmap?.height ?: image!!.height)
                var candidates: List<HoldCandidate> = emptyList()
                val passes = JSONArray()
                repeat(repeat) {
                    if (detector != null) {
                        val r = detector.detect(bitmap!!, settings)
                        candidates = r.candidates
                        passes.put(JSONObject().put("resizeMs", r.timing.resizeMs).put("inferMs", r.timing.inferMs)
                            .put("postMs", r.timing.postMs).put("totalMs", r.timing.totalMs).put("tiles", r.tiles))
                    } else {
                        val c0 = System.nanoTime()
                        candidates = SprayHoldDetector.detect(image!!)
                        passes.put(JSONObject().put("totalMs", (System.nanoTime() - c0) / 1_000_000))
                    }
                }
                row.put("passes", passes).put("candidates", JSONArray(candidates.map { c ->
                    JSONArray().put(c.x.toDouble()).put(c.y.toDouble()).put(c.radius.toDouble()).put(c.confidence.toDouble())
                        .put(c.outline?.let { ring -> JSONArray(ring.map { v -> (v * 1000).toInt() / 1000.0 }) } ?: JSONObject.NULL)
                }))
                rows.put(row)
                bitmap?.recycle()
                Log.i("HoldBench", "$label ${photo.name} ${candidates.size} holds, ${passes.optJSONObject(0)}")
            }
        } finally {
            detector?.close()
        }
        return json.put("photos", rows).put("rssEndKb", status("VmRSS")).put("hwmEndKb", status("VmHWM"))
    }

    /** The photo at a long side of at least [minLongSide] (power-of-two subsampling), as the app stores and loads it. */
    private fun decode(file: File, minLongSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val long = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (long / (sample * 2) >= minLongSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("cannot decode ${file.name}")
    }

    /** The photo at the classic detector's size. */
    private fun load(file: File, minLongSide: Int): RgbImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val long = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (long / (sample * 2) >= minLongSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("cannot decode ${file.name}")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = RgbImage(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        if (minLongSide != CLASSIC_LONG_SIDE || max(image.width, image.height) <= CLASSIC_LONG_SIDE) return image
        // The classic detector gets the app's own working size.
        val s = CLASSIC_LONG_SIDE.toFloat() / max(image.width, image.height)
        return Resampling.resize(image, max(1, (image.width * s).toInt()), max(1, (image.height * s).toInt()), SegSettings.Resampler.BILINEAR)
    }

    private fun status(key: String): Long =
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: -1

    private companion object {
        const val CLASSIC_LONG_SIDE = 1024
    }
}
