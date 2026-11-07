package com.cruxcoach.android.boardscan

import java.io.Closeable

/**
 * One camera frame, upright as the phone is held: pixels (ARGB), the focal length in pixels and the world's up
 * direction in camera coordinates (x right, y down, z forward) from the gravity sensor, if there is one.
 */
class ScanFrame(val argb: IntArray, val width: Int, val height: Int, val focalPx: Double, val up: DoubleArray?)

/** What one frame gave: the lit LEDs found, the ranking, and the time taken. */
class FrameOutcome(val leds: Int, val result: MatchResult, val detectMs: Long, val matchMs: Long)

/**
 * The scan pipeline for one board at one wall [angle] (degrees overhanging): lit LEDs in the frame, then the
 * recogniser. Frames are only read, never kept. Not thread-safe: one frame at a time.
 */
class BoardScanner(private val catalogue: ScanCatalogue, private val angle: Int, threads: Int) : Closeable {
    private val matcher = BoardMatcher(catalogue.index, MatchParams(threads = threads), catalogue.staticMarks)

    /**
     * [onProgress] gets the number of lit LEDs found and the ranking of each search step that was not sure yet,
     * while the next one runs.
     */
    fun scan(frame: ScanFrame, onProgress: (leds: Int, MatchResult) -> Unit = { _, _ -> }): FrameOutcome {
        val t0 = System.nanoTime()
        val blobs = LedDetector.detect(frame.argb, frame.width, frame.height)
        val t1 = System.nanoTime()
        val progress = { r: MatchResult -> onProgress(blobs.size, r) }
        val overhang = angle.toDouble()
        val w = frame.width
        val h = frame.height
        val f = frame.focalPx
        val result = when {
            frame.up == null -> {
                // no gravity sensor: assume an upright phone and try a range of tilts against the board
                val rects = Rectifier.sweep(f, w / 2.0, h / 2.0, SWEEP_TILTS, SWEEP_HEADINGS)
                matcher.recognise(blobs, w, h, f, rects, catalogue.palettes, rects, progress)
            }
            catalogue.searchMirrored ->
                matcher.recogniseWithMirror(blobs, w, h, f, frame.up, overhang, catalogue.palettes, progress)
            else -> matcher.recognise(blobs, w, h, f, frame.up, overhang, catalogue.palettes, progress)
        }
        val t2 = System.nanoTime()
        return FrameOutcome(blobs.size, result, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000)
    }

    override fun close() = matcher.close()

    private companion object {
        val SWEEP_TILTS = DoubleArray(8) { -10.0 + 10.0 * it }
        val SWEEP_HEADINGS = doubleArrayOf(-20.0, 0.0, 20.0)
    }
}
