package com.cruxcoach.domain.spraywall.detect

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The pure half of the hold segmentation model's run: cutting the photo into
 * tiles, resampling, turning the model's tensors into holds with outlines, and
 * merging what neighbouring tiles both saw. The inference itself runs in the
 * app (ONNX Runtime); everything here is plain Kotlin so it can be tested on
 * the JVM.
 *
 * Ported from BoardSesh's hold-detection pipeline (packages/shared/hold-detection,
 * https://github.com/boardsesh/boardsesh), Apache License 2.0. Changes: Kotlin,
 * a resampler for the photo and the tiles (the original uses libvips), a score
 * floor below the threshold so the editor's sensitivity slider can show more,
 * and the ring point limit as a setting.
 */

/** How one detection run is set up. The defaults are the published model's own settings. */
data class SegSettings(
    /** Model input side; the tile is stretched to [input] × [input]. */
    val input: Int = 312,
    /** The photo's long side is resized to this before tiling. */
    val longSide: Int = 1024,
    val rows: Int = 2,
    val cols: Int = 2,
    /** How much neighbouring tiles overlap, as a share of a tile's plain size. */
    val overlap: Double = 0.15,
    /** Score a hold needs to be shown by default. */
    val threshold: Float = 0.6f,
    /** Lowest score kept at all, for the sensitivity slider (≤ [threshold]). */
    val floor: Float = 0.6f,
    /** Two tiles saw the same hold when their boxes overlap more than this. */
    val nmsIou: Float = 0.5f,
    val outlines: Boolean = true,
    /** Mask grid upsampling before tracing. */
    val maskUpsample: Int = 4,
    /** Douglas–Peucker tolerance in upsampled mask pixels. */
    val simplifyTolerance: Float = 0.5f,
    val maxRingPoints: Int = 150,
    val resampler: Resampler = Resampler.LANCZOS3,
    /**
     * Where two tiles both saw a hold, keep the copy that is not cut by a
     * tile edge, and drop a cut copy that lies mostly inside a kept hold.
     * Off reproduces the reference merge (best score wins).
     */
    val seamAware: Boolean = false,
) {
    /** NATIVE: the platform's own bitmap scaling (fast); on the JVM it means BILINEAR. */
    enum class Resampler { LANCZOS3, BILINEAR, NATIVE }

    init {
        require(input in 64..1536 && longSide in 128..8192) { "bad sizes" }
        require(rows in 1..8 && cols in 1..8 && overlap in 0.0..0.9) { "bad tiling" }
        require(floor <= threshold) { "floor above threshold" }
    }

    companion object {
        /** ImageNet mean and standard deviation, the model's input normalisation. */
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/** A tile of the working image, in its pixels; [x1] and [y1] are exclusive. */
data class SegTile(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
}

/** One query that passed the score floor. The box is in working-image pixels; the outline in units of r. */
class SegDetection(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val score: Float,
    val outline: FloatArray?,
)

/** The model's raw outputs for one tile, found by shape (exporters do not name them consistently). */
class SegOutputs(
    /** `[queries, 4]` as normalised cx, cy, w, h in the tile. */
    val boxes: FloatArray,
    /** `[queries, classes]` logits. */
    val logits: FloatArray,
    val queries: Int,
    val classes: Int,
    /** `[queries, maskHeight, maskWidth]` mask logits, or null for a box-only model. */
    val masks: FloatArray? = null,
    val maskWidth: Int = 0,
    val maskHeight: Int = 0,
)

object SegPipeline {
    /** Working size: the photo with its long side at [longSide]. */
    fun workingSize(width: Int, height: Int, longSide: Int): Pair<Int, Int> {
        val scale = longSide.toDouble() / max(width, height)
        return max(1, roundHalfUp(width * scale)) to max(1, roundHalfUp(height * scale))
    }

    /**
     * Overlapping tiles covering the working image. Each tile is
     * `size / count × (1 + overlap)`; the last one is pushed back inside.
     */
    fun tiles(width: Int, height: Int, rows: Int, cols: Int, overlap: Double): List<SegTile> {
        if (rows == 1 && cols == 1) return listOf(SegTile(0, 0, width, height))
        val tileWidth = roundHalfToEven(width.toDouble() / cols * (1 + overlap))
        val tileHeight = roundHalfToEven(height.toDouble() / rows * (1 + overlap))
        val stepX = if (cols > 1) max(1, floor((width - tileWidth).toDouble() / max(1, cols - 1)).toInt()) else width
        val stepY = if (rows > 1) max(1, floor((height - tileHeight).toDouble() / max(1, rows - 1)).toInt()) else height
        val out = ArrayList<SegTile>(rows * cols)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x0 = min(col * stepX, max(0, width - tileWidth))
                val y0 = min(row * stepY, max(0, height - tileHeight))
                out += SegTile(x0, y0, min(x0 + tileWidth, width), min(y0 + tileHeight, height))
            }
        }
        return out
    }

    /** The model's input for one tile: the tile stretched to input², normalised, as NCHW floats. */
    fun inputTensor(work: RgbImage, tile: SegTile, settings: SegSettings): FloatArray {
        val crop = IntArray(tile.width * tile.height)
        for (y in 0 until tile.height) {
            work.argb.copyInto(crop, y * tile.width, (tile.y0 + y) * work.width + tile.x0, (tile.y0 + y) * work.width + tile.x1)
        }
        val size = settings.input
        return normalize(Resampling.resize(RgbImage(tile.width, tile.height, crop), size, size, settings.resampler).argb, size)
    }

    /** A tile already stretched to size² as ARGB, normalised with the ImageNet mean and std, as NCHW floats. */
    fun normalize(argb: IntArray, size: Int): FloatArray {
        val pixels = size * size
        val out = FloatArray(3 * pixels)
        val mean = SegSettings.MEAN
        val std = SegSettings.STD
        for (i in 0 until pixels) {
            val c = argb[i]
            out[i] = (((c shr 16) and 0xFF) / 255f - mean[0]) / std[0]
            out[pixels + i] = (((c shr 8) and 0xFF) / 255f - mean[1]) / std[1]
            out[2 * pixels + i] = ((c and 0xFF) / 255f - mean[2]) / std[2]
        }
        return out
    }

    /**
     * One tile's tensors to detections in working-image pixels. Scores are
     * sigmoid (focal loss: each logit is its own probability), the best class
     * counts, and a query below [SegSettings.floor] is dropped. The model is a
     * set predictor, so nothing inside one tile needs NMS.
     */
    fun decode(outputs: SegOutputs, tile: SegTile, settings: SegSettings): List<SegDetection> {
        val out = ArrayList<SegDetection>()
        val tileWidth = tile.width.toFloat()
        val tileHeight = tile.height.toFloat()
        for (q in 0 until outputs.queries) {
            var best = Float.NEGATIVE_INFINITY
            for (k in 0 until outputs.classes) best = max(best, outputs.logits[q * outputs.classes + k])
            val score = (1.0 / (1.0 + exp(-best.toDouble()))).toFloat()
            if (!(score >= settings.floor)) continue
            val cx = outputs.boxes[q * 4]
            val cy = outputs.boxes[q * 4 + 1]
            val w = outputs.boxes[q * 4 + 2]
            val h = outputs.boxes[q * 4 + 3]
            val nx0 = cx - w / 2
            val ny0 = cy - h / 2
            val nx1 = cx + w / 2
            val ny1 = cy + h / 2
            val masks = outputs.masks
            val outline = if (settings.outlines && masks != null && outputs.maskWidth > 0 && outputs.maskHeight > 0) {
                val cells = outputs.maskWidth * outputs.maskHeight
                MaskOutline.trace(
                    masks, q * cells, outputs.maskWidth, outputs.maskHeight,
                    nx0, ny0, nx1, ny1, tileWidth, tileHeight, settings,
                )
            } else {
                null
            }
            out += SegDetection(
                tile.x0 + nx0 * tileWidth, tile.y0 + ny0 * tileHeight,
                tile.x0 + nx1 * tileWidth, tile.y0 + ny1 * tileHeight,
                score, outline,
            )
        }
        return out
    }

    /** Folds the tiles' detections into one list: greedy NMS, best score first. */
    fun merge(perTile: List<List<SegDetection>>, iou: Float): List<SegDetection> {
        val all = perTile.flatten()
        if (all.size <= 1) return all
        return nms(all, all.indices.sortedByDescending { all[it].score }, iou, cut = null)
    }

    /**
     * [merge] that knows the tiles: a detection whose box touches an inner
     * tile edge was cut off there, so where it overlaps a copy from another
     * tile, the whole copy wins even with a lower score, and a cut copy that
     * lies mostly inside a kept hold is dropped as a duplicate.
     */
    fun merge(perTile: List<List<SegDetection>>, tiles: List<SegTile>, workWidth: Int, workHeight: Int, iou: Float): List<SegDetection> {
        val all = ArrayList<SegDetection>()
        val cut = ArrayList<Boolean>()
        for ((t, detections) in perTile.withIndex()) {
            val tile = tiles[t]
            for (d in detections) {
                all += d
                cut += (tile.x0 > 0 && d.x0 <= tile.x0 + SEAM_PX) || (tile.x1 < workWidth && d.x1 >= tile.x1 - SEAM_PX) ||
                    (tile.y0 > 0 && d.y0 <= tile.y0 + SEAM_PX) || (tile.y1 < workHeight && d.y1 >= tile.y1 - SEAM_PX)
            }
        }
        if (all.size <= 1) return all
        val order = all.indices.sortedWith(compareBy<Int> { cut[it] }.thenByDescending { all[it].score })
        return nms(all, order, iou, cut)
    }

    private fun nms(all: List<SegDetection>, order: List<Int>, iou: Float, cut: List<Boolean>?): List<SegDetection> {
        val suppressed = BooleanArray(all.size)
        val keep = ArrayList<SegDetection>()
        for (i in order) {
            if (suppressed[i]) continue
            keep += all[i]
            for (j in order) {
                if (j == i || suppressed[j]) continue
                if (boxIou(all[i], all[j]) > iou || (cut != null && cut[j] && insideShare(all[j], all[i]) > CUT_INSIDE)) suppressed[j] = true
            }
        }
        return keep
    }

    /** How much of [a]'s box lies inside [b]'s. */
    private fun insideShare(a: SegDetection, b: SegDetection): Float {
        val area = max(0f, a.x1 - a.x0) * max(0f, a.y1 - a.y0)
        val inter = max(0f, min(a.x1, b.x1) - max(a.x0, b.x0)) * max(0f, min(a.y1, b.y1) - max(a.y0, b.y0))
        return if (area > 0f) inter / area else 0f
    }

    private const val SEAM_PX = 2f
    private const val CUT_INSIDE = 0.6f

    fun boxIou(a: SegDetection, b: SegDetection): Float {
        val areaA = max(0f, a.x1 - a.x0) * max(0f, a.y1 - a.y0)
        val areaB = max(0f, b.x1 - b.x0) * max(0f, b.y1 - b.y0)
        val inter = max(0f, min(a.x1, b.x1) - max(a.x0, b.x0)) * max(0f, min(a.y1, b.y1) - max(a.y0, b.y0))
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }

    /**
     * Detections to candidates on the photo. The radius is the equal-area
     * circle of the box, `sqrt(w·h/π)`, so a square jug and a long rail of the
     * same area get the same size.
     */
    fun candidates(detections: List<SegDetection>, workWidth: Int, workHeight: Int): List<HoldCandidate> =
        detections.map { d ->
            val w = max(0f, d.x1 - d.x0)
            val h = max(0f, d.y1 - d.y0)
            HoldCandidate(
                x = ((d.x0 + d.x1) / 2f / workWidth).coerceIn(0f, 1f),
                y = ((d.y0 + d.y1) / 2f / workHeight).coerceIn(0f, 1f),
                radius = sqrt(w * h / kotlin.math.PI.toFloat()) / workWidth,
                confidence = d.score,
                outline = d.outline?.toList(),
            )
        }

    /** JavaScript's Math.round: halves go up. */
    fun roundHalfUp(value: Double): Int = floor(value + 0.5).toInt()

    /** Halves go to the even neighbour (Python's round). */
    fun roundHalfToEven(value: Double): Int {
        val f = floor(value)
        if (value - f != 0.5) return roundHalfUp(value)
        val i = f.toInt()
        return if (i % 2 == 0) i else i + 1
    }
}

/**
 * One query's mask logits to a ring around its box centre, in units of the
 * box's equal-area radius. The order matters: interpolate the logits, then
 * threshold at 0 (sigmoid 0.5); thresholding first steps every edge. The part
 * kept is the one under the box centre, not the largest, because a neighbour
 * can bleed into the mask.
 */
object MaskOutline {
    private const val MIN_RING_POINTS = 3
    private const val MAX_RING_COORDINATE = 4f

    fun trace(
        masks: FloatArray,
        offset: Int,
        maskWidth: Int,
        maskHeight: Int,
        bx0: Float,
        by0: Float,
        bx1: Float,
        by1: Float,
        tileWidth: Float,
        tileHeight: Float,
        settings: SegSettings,
    ): FloatArray? {
        val upsample = max(1, settings.maskUpsample)
        val width = maskWidth * upsample
        val height = maskHeight * upsample
        // Only the box's neighbourhood can be this hold; the margin grows with
        // the box so a big volume is not cut straight by its own window.
        val boxSpan = max((bx1 - bx0) * width, (by1 - by0) * height)
        val margin = ceil(2.0 * upsample + boxSpan / 2.0).toInt()
        val left = clamp(floor(bx0 * width).toInt() - margin, 0, width - 1)
        val top = clamp(floor(by0 * height).toInt() - margin, 0, height - 1)
        val right = clamp(ceil(bx1 * width).toInt() + margin, left + 1, width)
        val bottom = clamp(ceil(by1 * height).toInt() + margin, top + 1, height)
        val ww = right - left
        val wh = bottom - top
        if (ww < 2 || wh < 2) return null
        val filled = BooleanArray(ww * wh)
        // A bilinear sample is positive only if one of its four grid corners
        // is: skip the arithmetic everywhere else (most of the window).
        val positive = BooleanArray(maskWidth * maskHeight) { masks[offset + it] > 0f }
        for (y in 0 until wh) {
            val sy = (top + y).toFloat() / upsample
            val gy0 = floor((sy - 0.5f).coerceIn(0f, (maskHeight - 1).toFloat())).toInt()
            val gy1 = min(gy0 + 1, maskHeight - 1)
            for (x in 0 until ww) {
                val sx = (left + x).toFloat() / upsample
                val gx0 = floor((sx - 0.5f).coerceIn(0f, (maskWidth - 1).toFloat())).toInt()
                val gx1 = min(gx0 + 1, maskWidth - 1)
                if (!positive[gy0 * maskWidth + gx0] && !positive[gy0 * maskWidth + gx1] &&
                    !positive[gy1 * maskWidth + gx0] && !positive[gy1 * maskWidth + gx1]
                ) {
                    continue
                }
                filled[y * ww + x] = sample(masks, offset, maskWidth, maskHeight, sx, sy) > 0f
            }
        }
        val centreX = roundHalfUpF(((bx0 + bx1) / 2f) * width) - left
        val centreY = roundHalfUpF(((by0 + by1) / 2f) * height) - top
        val component = componentAt(filled, ww, wh, centreX, centreY) ?: return null
        val traced = traceBoundary(component, ww, wh)
        if (traced.size < MIN_RING_POINTS) return null
        val simplified = fitPoints(traced, settings.simplifyTolerance.toDouble(), settings.maxRingPoints) ?: return null
        // Window pixels to tile pixels, then units of r about the box centre.
        val scaleX = tileWidth / width
        val scaleY = tileHeight / height
        val boxWidth = max(0f, bx1 - bx0) * width * scaleX
        val boxHeight = max(0f, by1 - by0) * height * scaleY
        val radius = sqrt(boxWidth * boxHeight / kotlin.math.PI.toFloat())
        if (!(radius > 0f)) return null
        val originX = ((bx0 + bx1) / 2f) * width - left
        val originY = ((by0 + by1) / 2f) * height - top
        val ring = FloatArray(simplified.size * 2)
        for ((i, p) in simplified.withIndex()) {
            ring[2 * i] = round4((p.first - originX) * scaleX / radius)
            ring[2 * i + 1] = round4((p.second - originY) * scaleY / radius)
        }
        if (ring.any { !it.isFinite() || abs(it) > MAX_RING_COORDINATE }) return null
        return ring
    }

    /** Bilinear sample at a fractional grid position (pixel centres at +0.5). */
    internal fun sample(m: FloatArray, offset: Int, w: Int, h: Int, x: Float, y: Float): Float {
        val cx = (x - 0.5f).coerceIn(0f, (w - 1).toFloat())
        val cy = (y - 0.5f).coerceIn(0f, (h - 1).toFloat())
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = cx - x0
        val fy = cy - y0
        val tl = m[offset + y0 * w + x0]
        val tr = m[offset + y0 * w + x1]
        val bl = m[offset + y1 * w + x0]
        val br = m[offset + y1 * w + x1]
        return tl * (1 - fx) * (1 - fy) + tr * fx * (1 - fy) + bl * (1 - fx) * fy + br * fx * fy
    }

    /** The 4-connected component at (x, y), or the nearest one when the centre is empty (a crescent). */
    internal fun componentAt(filled: BooleanArray, w: Int, h: Int, x: Int, y: Int): BooleanArray? {
        var sx = clamp(x, 0, w - 1)
        var sy = clamp(y, 0, h - 1)
        if (!filled[sy * w + sx]) {
            val found = nearestSet(filled, w, h, sx, sy) ?: return null
            sx = found.first
            sy = found.second
        }
        val component = BooleanArray(filled.size)
        val stack = IntArray(filled.size)
        var top = 0
        stack[top++] = sy * w + sx
        component[sy * w + sx] = true
        while (top > 0) {
            val index = stack[--top]
            val cx = index % w
            val cy = index / w
            for (d in 0 until 4) {
                val nx = cx + DX4[d]
                val ny = cy + DY4[d]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val n = ny * w + nx
                if (component[n] || !filled[n]) continue
                component[n] = true
                stack[top++] = n
            }
        }
        return component
    }

    /**
     * The set pixel nearest to (x, y) by Chebyshev distance; ties go to the
     * one above, then to the left, as the reference's square spiral finds
     * them. One pass instead of the spiral: the spiral rescans the window for
     * every radius, which on an empty mask costs seconds per photo.
     */
    internal fun nearestSet(filled: BooleanArray, w: Int, h: Int, x: Int, y: Int): Pair<Int, Int>? {
        var best = Int.MAX_VALUE
        var bx = -1
        var by = -1
        for (ny in 0 until h) {
            for (nx in 0 until w) {
                if (!filled[ny * w + nx]) continue
                val d = max(abs(nx - x), abs(ny - y))
                if (d < best) {
                    best = d
                    bx = nx
                    by = ny
                }
            }
        }
        return if (bx < 0) null else bx to by
    }

    /** Moore-neighbourhood walk around the component's outer edge, once. */
    internal fun traceBoundary(component: BooleanArray, w: Int, h: Int): List<Pair<Float, Float>> {
        val start = component.indexOfFirst { it }
        if (start < 0) return emptyList()
        val startX = start % w
        val startY = start / w
        val ring = arrayListOf(startX.toFloat() to startY.toFloat())
        var x = startX
        var y = startY
        var direction = 0
        val maxSteps = 4 * (w + h) + 16
        for (step in 0 until maxSteps) {
            var moved = false
            for (probe in 0 until 8) {
                val candidate = (direction + 6 + probe) % 8
                val nx = x + DX8[candidate]
                val ny = y + DY8[candidate]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                if (!component[ny * w + nx]) continue
                x = nx
                y = ny
                direction = candidate
                moved = true
                break
            }
            if (!moved) break
            if (x == startX && y == startY) break
            ring += x.toFloat() to y.toFloat()
        }
        return ring
    }

    /** Simplifies, and raises the tolerance until the ring has at most [maxPoints]. */
    internal fun fitPoints(points: List<Pair<Float, Float>>, tolerance: Double, maxPoints: Int): List<Pair<Float, Float>>? {
        var current = simplify(points, tolerance)
        var attempt = max(tolerance, 0.25)
        var round = 0
        while (round < 12 && current.size > maxPoints) {
            current = simplify(points, attempt)
            attempt *= 1.6
            round++
        }
        if (current.size > maxPoints || current.size < MIN_RING_POINTS) return null
        return current
    }

    /** Douglas–Peucker between the first and the last point. */
    internal fun simplify(points: List<Pair<Float, Float>>, tolerance: Double): List<Pair<Float, Float>> {
        if (points.size <= MIN_RING_POINTS || tolerance <= 0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var furthest = -1
            var furthestDistance = tolerance
            for (i in first + 1 until last) {
                val d = distance(points[i], points[first], points[last])
                if (d > furthestDistance) {
                    furthest = i
                    furthestDistance = d
                }
            }
            if (furthest >= 0) {
                keep[furthest] = true
                stack.addLast(first to furthest)
                stack.addLast(furthest to last)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun distance(p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Double {
        val dx = (b.first - a.first).toDouble()
        val dy = (b.second - a.second).toDouble()
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return hypot((p.first - a.first).toDouble(), (p.second - a.second).toDouble())
        val t = (((p.first - a.first) * dx + (p.second - a.second) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
    }

    private fun round4(v: Float): Float = (floor(v * 10000.0 + 0.5) / 10000.0).toFloat()
    private fun roundHalfUpF(v: Float): Int = floor(v + 0.5f).toInt()
    private fun clamp(v: Int, low: Int, high: Int): Int = if (v < low) low else if (v > high) high else v

    private val DX4 = intArrayOf(1, -1, 0, 0)
    private val DY4 = intArrayOf(0, 0, 1, -1)
    private val DX8 = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DY8 = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
}

/**
 * Separable resampling with an antialiased kernel (the filter widens with the
 * shrink factor), the way image libraries scale photos down. Lanczos-3 is the
 * default of the library the model was evaluated with; bilinear is cheaper.
 */
object Resampling {
    fun resize(image: RgbImage, width: Int, height: Int, kind: SegSettings.Resampler): RgbImage {
        if (image.width == width && image.height == height) return image
        val horizontal = pass(image.argb, image.width, image.height, width, true, kind)
        val vertical = pass(horizontal, width, image.height, height, false, kind)
        return RgbImage(width, height, vertical)
    }

    private fun pass(src: IntArray, w: Int, h: Int, outSize: Int, horizontal: Boolean, kind: SegSettings.Resampler): IntArray {
        val inSize = if (horizontal) w else h
        val outW = if (horizontal) outSize else w
        val outH = if (horizontal) h else outSize
        val out = IntArray(outW * outH)
        val scale = inSize.toDouble() / outSize
        val support = (if (kind == SegSettings.Resampler.LANCZOS3) 3.0 else 1.0) * max(1.0, scale)
        val filterScale = max(1.0, scale)
        val weights = DoubleArray(ceil(support * 2 + 2).toInt())
        for (o in 0 until outSize) {
            val centre = (o + 0.5) * scale
            val start = max(0, floor(centre - support).toInt())
            val end = min(inSize, ceil(centre + support).toInt())
            var total = 0.0
            val n = end - start
            for (k in 0 until n) {
                val wgt = kernel((start + k + 0.5 - centre) / filterScale, kind)
                weights[k] = wgt
                total += wgt
            }
            if (total != 0.0) for (k in 0 until n) weights[k] /= total
            val lines = if (horizontal) h else w
            for (line in 0 until lines) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (k in 0 until n) {
                    val c = if (horizontal) src[line * w + start + k] else src[(start + k) * w + line]
                    val wgt = weights[k]
                    r += ((c shr 16) and 0xFF) * wgt
                    g += ((c shr 8) and 0xFF) * wgt
                    b += (c and 0xFF) * wgt
                }
                val pixel = (0xFF shl 24) or (clamp255(r) shl 16) or (clamp255(g) shl 8) or clamp255(b)
                if (horizontal) out[line * outW + o] = pixel else out[o * outW + line] = pixel
            }
        }
        return out
    }

    private fun kernel(x: Double, kind: SegSettings.Resampler): Double = when (kind) {
        SegSettings.Resampler.BILINEAR, SegSettings.Resampler.NATIVE -> max(0.0, 1.0 - abs(x))
        SegSettings.Resampler.LANCZOS3 -> if (x == 0.0) 1.0 else if (abs(x) >= 3.0) 0.0 else sinc(x) * sinc(x / 3.0)
    }

    private fun sinc(x: Double): Double {
        val px = kotlin.math.PI * x
        return kotlin.math.sin(px) / px
    }

    private fun clamp255(v: Double): Int = floor(v + 0.5).toInt().coerceIn(0, 255)
}
