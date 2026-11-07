package com.cruxcoach.android.boardscan

import java.io.Closeable
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * One camera frame, upright as the phone is held: pixels (ARGB), the focal length in pixels and the world's up
 * direction in camera coordinates (x right, y down, z forward) from the gravity sensor, if there is one.
 */
class ScanFrame(val argb: IntArray, val width: Int, val height: Int, val focalPx: Double, val up: DoubleArray?)

/**
 * What one frame gave: the lit LEDs found, the ranking, and the time taken. The LED positions and the frame's
 * geometry stay for test mode's look at the lit problem ([BoardScanner.explain]); the pixels do not.
 */
class FrameOutcome(
    val result: MatchResult,
    val blobs: List<Blob>,
    val width: Int,
    val height: Int,
    val focalPx: Double,
    val up: DoubleArray?,
    /** Mean brightness of the frame (0-255): how bright the camera exposed it. */
    val brightness: Int,
    val detectMs: Long,
    /** Time of the whole search; for a ranking reported while the search still ran, the time until then. */
    val matchMs: Long,
) {
    val leds: Int get() = blobs.size
}

/**
 * The scan pipeline for one board at one wall [angle] (degrees overhanging): lit LEDs in the frame, then the
 * recogniser. Frames are only read, never kept. Not thread-safe: one frame at a time.
 */
class BoardScanner(private val catalogue: ScanCatalogue, private val angle: Int, threads: Int) : Closeable {
    private val matcher = BoardMatcher(catalogue.index, MatchParams(threads = threads), catalogue.staticMarks)

    /** [onProgress] gets the ranking of each search step that was not sure yet, while the next one runs. */
    fun scan(frame: ScanFrame, onProgress: (FrameOutcome) -> Unit = {}): FrameOutcome {
        val t0 = System.nanoTime()
        val blobs = LedDetector.detect(frame.argb, frame.width, frame.height)
        val brightness = meanBrightness(frame.argb)
        val t1 = System.nanoTime()
        val detectMs = (t1 - t0) / 1_000_000
        fun outcome(r: MatchResult) = FrameOutcome(r, blobs, frame.width, frame.height, frame.focalPx, frame.up,
            brightness, detectMs, (System.nanoTime() - t1) / 1_000_000)
        val progress = { r: MatchResult -> onProgress(outcome(r)) }
        val result = when {
            frame.up == null -> {
                val rects = sweep(frame.focalPx, frame.width, frame.height)
                matcher.recognise(blobs, frame.width, frame.height, frame.focalPx, rects, catalogue.palettes, rects, progress)
            }
            catalogue.searchMirrored -> matcher.recogniseWithMirror(blobs, frame.width, frame.height, frame.focalPx,
                frame.up, angle.toDouble(), catalogue.palettes, progress)
            else -> matcher.recognise(blobs, frame.width, frame.height, frame.focalPx, frame.up, angle.toDouble(),
                catalogue.palettes, progress)
        }
        return outcome(result)
    }

    /**
     * How well the problem at catalogue row [climb] (lit [mirrored] or not) fits the frame of [o] on its own; for
     * test mode, when the lit problem was not offered. Null when it cannot be placed on the frame.
     */
    fun explain(o: FrameOutcome, climb: Int, mirrored: Boolean): Candidate? {
        // a mirrored problem is the original one in the mirrored frame, as the scan searches it
        val blobs = if (!mirrored) o.blobs else o.blobs.map { Blob(o.width - 1 - it.x, it.y, it.hue, it.chroma, it.area, it.peak) }
        val up = o.up ?: run {
            val rects = sweep(o.focalPx, o.width, o.height)
            return matcher.explain(climb, blobs, o.width, o.height, o.focalPx, rects, catalogue.palettes)
        }
        val upM = if (mirrored) doubleArrayOf(-up[0], up[1], up[2]) else up
        return matcher.explain(climb, blobs, o.width, o.height, o.focalPx, upM, angle.toDouble(), catalogue.palettes)
    }

    /** Metres from the camera to the board's centre, as the pose of [c] in the frame of [o] places it. */
    fun distanceM(o: FrameOutcome, c: Candidate): Double? {
        val ix = catalogue.index
        val e = ix.extent
        return cameraDistance(c.boardToImage, o.focalPx, o.width / 2.0, o.height / 2.0, ix.metresPerUnit,
            0.5 * (e[0] + e[1]), 0.5 * (e[2] + e[3]))
    }

    override fun close() = matcher.close()

    companion object {
        private val SWEEP_TILTS = DoubleArray(8) { -10.0 + 10.0 * it }
        private val SWEEP_HEADINGS = doubleArrayOf(-20.0, 0.0, 20.0)

        /** No gravity sensor: assume an upright phone and try a range of tilts against the board. */
        private fun sweep(f: Double, w: Int, h: Int) = Rectifier.sweep(f, w / 2.0, h / 2.0, SWEEP_TILTS, SWEEP_HEADINGS)

        /** Degrees the camera looks up from level (negative: down), from the up direction in camera coordinates. */
        fun cameraPitchDeg(up: DoubleArray): Double {
            val n = sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2])
            return if (n <= 0) 0.0 else Math.toDegrees(asin((up[2] / n).coerceIn(-1.0, 1.0)))
        }

        /** Mean of max(r, g, b) over every 4th pixel. */
        internal fun meanBrightness(argb: IntArray): Int {
            if (argb.isEmpty()) return 0
            var sum = 0L
            var n = 0
            var i = 0
            while (i < argb.size) {
                val c = argb[i]
                sum += maxOf((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff)
                n++
                i += 4
            }
            return (sum / n).toInt()
        }
    }
}
