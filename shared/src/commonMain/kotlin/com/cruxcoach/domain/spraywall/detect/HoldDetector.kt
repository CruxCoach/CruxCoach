package com.cruxcoach.domain.spraywall.detect

/**
 * Hold detection for spray walls (FEAT-078): finds the holds on a photo of a
 * wall so the climber only has to accept and correct them.
 *
 * Everything runs on the phone. [SprayHoldDetector] is classical computer
 * vision without any model file; [SegPipeline] holds the pure parts of the
 * trained segmentation model's run (tiles, decoding, outlines), whose
 * inference runs in the app.
 */

/** A picture as ARGB ints, row by row (the layout of Android's `Bitmap.getPixels`). */
class RgbImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(argb.size == width * height) { "pixel count does not match the size" }
    }
}

/**
 * A place that looks like a hold. Coordinates like a stored hold: [x] and [y]
 * run 0..1 across the photo's width and height, [radius] is a fraction of its
 * WIDTH. [confidence] runs 0..1; the editor's sensitivity slider is a
 * threshold on it. [outline] is the hold's shape when the detector knows it:
 * a closed ring `x0, y0, x1, y1, …` in units of the radius around the centre
 * (see [HoldOutline]); null means a circle.
 */
data class HoldCandidate(
    val x: Float,
    val y: Float,
    val radius: Float,
    val confidence: Float,
    val outline: List<Float>? = null,
)

fun interface HoldDetector {
    /** Candidates, best first. Must not touch the network or keep the image. */
    fun detect(image: RgbImage): List<HoldCandidate>
}
