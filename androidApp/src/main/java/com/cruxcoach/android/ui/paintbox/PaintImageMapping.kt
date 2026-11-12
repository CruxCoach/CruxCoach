package com.cruxcoach.android.ui.paintbox

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A picture as plain ARGB pixels (not premultiplied), row by row. */
class PaintPixels(val width: Int, val height: Int, val argb: IntArray) {
    init { require(width > 0 && height > 0 && argb.size == width * height) }
}

/** What the border of a picture says about its background. */
data class PaintBackground(
    /** Most of the border is transparent. Transparent parts are never lit. */
    val transparent: Boolean = false,
    /** The most common opaque colour of the border, as RGB. */
    val color: Int? = null,
    /** The border is clearly one colour, or transparent: leaving the background dark is the default. */
    val clear: Boolean = false,
)

/** Viewport point to pixel position: `x = xx * vx + xy * vy + x0`, `y = yx * vx + yy * vy + y0`. */
data class PaintAffine(val xx: Float, val xy: Float, val x0: Float, val yx: Float, val yy: Float, val y0: Float)

/**
 * A photo once its background is decided: for every pixel how much it belongs to the picture.
 * Made once per photo and background ([PaintImageMapping.picture]) and used for every placement.
 */
class PaintPicture internal constructor(
    val pixels: PaintPixels,
    /** Per pixel, 0 (background, black, transparent) to 1 (the picture). */
    internal val weight: FloatArray,
    /** Black is the picture itself and lights white. */
    val ink: Boolean,
    /** A background is left off: a colour or transparency. */
    val removed: Boolean,
    /** The box around the content, in pixels of [pixels]. */
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** The content looks the same mirrored left to right, top to bottom. */
    val mirrorX: Boolean,
    val mirrorY: Boolean,
)

/**
 * Turns a picture into hold colours. Pure: pixels and hold positions in, colours out.
 *
 * Once per photo ([picture]): how much each pixel belongs to the picture. The background, black
 * and transparent parts do not; a glow around a shape only where it is strong.
 *
 * For every placement ([map]):
 * 1. Every hold takes [SAMPLES] samples from a disc half as wide as the hold spacing, and again
 *    from a disc twice that size. The share that belongs to the picture is the hold's coverage.
 * 2. Areas: a hold is lit when it is half covered, judged together with the holds around it, so
 *    an outline comes out even and single holes close.
 * 3. Lines: whatever the wider disc still sees of the picture becomes a candidate, and the
 *    candidates are thinned, weakest first, to chains one hold wide that never break. A stroke
 *    thinner than the hold spacing stays one unbroken line, and close parallel lines stay apart.
 * 4. A hold takes its most common colour, not a mix. Near duplicates and rare stray colours join
 *    the common ones, and a patch of a few holds takes the colour around it.
 *
 * LEDs cannot show black: black parts stay off. A black shape on a removed background is the
 * picture itself and lights white.
 *
 * [place] finds where a photo falls most cleanly on the holds.
 */
internal object PaintImageMapping {
    /** Samples per hold and disc. With some 500 holds that is 32 000 reads, whatever the size of the picture. */
    const val SAMPLES = 32

    /** OKLab distance up to which a pixel still is the background colour: covers JPEG noise and soft gradients. */
    private const val TOLERANCE = 0.075f
    /** From this OKLab lightness on, a background is light: nothing in the picture is a glow. */
    private const val LIGHT = 0.5f
    /** On a light background, a pixel this far from it belongs to the picture in full. */
    private const val CLEAR = 0.16f
    /** OKLab lightness below which a grey counts as black, and below which any colour does. */
    private const val DARK = 0.25f
    private const val BLACK = 0.14f
    /** OKLab chroma below which a colour is a grey. */
    private const val GREY = 0.02f
    /**
     * A glow fades out, a shape ends at an edge. When this share of what differs from a dark
     * background differs only faintly (less than [FAINT] of the strongest parts), the picture
     * glows, and then only what is nearly as strong as its strongest parts counts.
     */
    private const val FAINT = 0.22f
    private const val GLOW_FROM = 0.08f
    private const val GLOW_FULL = 0.20f
    private const val GLOW_LOW = 0.45f
    private const val GLOW_HIGH = 0.72f
    private const val HISTOGRAM = 512
    /**
     * A flat tone is a narrow band of distances that holds this share of the picture and stands this many
     * times above the levels beside it. It is part of the picture however faint it is; a halo or a glow is
     * spread over many levels.
     */
    private const val FLAT_SHARE = 0.02f
    private const val FLAT_OVER = 3f
    /** How much the holds around a hold move its half-coverage bar. */
    private const val SMOOTH = 0.3f
    private const val FILL = 0.15f
    /** The wider disc of a hold, in tight discs. */
    private const val WIDE = 2f
    /** A hold is a candidate for a line when the wide disc sees this much, and this share of the best around. */
    private const val LINE = 0.06f
    private const val LINE_SHARE = 0.45f
    /** The end of a line stays while it is this strong, measured by the hold before it. */
    private const val LINE_END = 0.6f
    /** Two holds see the same line when what they see of the picture lies no farther apart than this, in their own distance. */
    private const val SAME = 1.25f
    /** A barely covered hold between two well covered ones on opposite sides lies in a valley. */
    private const val VALLEY = 0.15f
    private const val STRONG = 0.4f
    private const val MERGE = 0.09f
    private const val RARE = 0.2f
    private const val MAX_COLORS = 12
    private const val LIFT = 0.6f
    private const val LEVEL_MIN = 0.35f
    /** LED colours whose parts of the picture are this alike (OKLab) are one colour; more so with few LED colours. */
    private const val ALIKE = 0.07f
    private const val ALIKE_FEW = 0.12f
    /** A patch of a colour with fewer holds takes the colour around it, unless one of its holds is this much of one colour. */
    private const val PATCH = 4
    private const val SOLID = 0.8f
    /** A lone hold stays when its own disc is covered this much, or the wider one this much: a dot, not a speck. */
    private const val DOT = 0.4f
    private const val DOT_WIDE = 0.16f
    /** Placement: zoom steps of 1.25 % down to 90 %, shifts in sixths of the hold spacing up to one spacing. */
    private const val ZOOM_STEPS = 9
    private const val ZOOM_STEP = 0.0125f
    private const val SHIFT_STEPS = 6
    /** How many placements of the first pass are judged with their lines as well. */
    private const val KEEP = 60
    private const val MIRROR_X = 1.5f
    private const val MIRROR_Y = 1f
    private const val SMALLER = 0.3f
    private const val FARTHER = 0.004f
    private const val OFF_LINE = 0.6f

    @Volatile private var neighbours: Pair<Map<Int, Offset>, HoldGraph>? = null

    /** The samples of a disc: the same under a mirror in x and in y, each standing for the same area. */
    private val DISC_X = FloatArray(SAMPLES)
    private val DISC_Y = FloatArray(SAMPLES)
    private val LINEAR = FloatArray(256) { linear(it.toFloat()) }

    init {
        var sample = 0
        for ((count, radius, turn) in listOf(Triple(4, 0.25f, 0.5f), Triple(8, 0.5f, 0.5f), Triple(8, sqrt(0.5f), 0f), Triple(12, sqrt(0.8125f), 0.5f))) {
            for (j in 0 until count) {
                val angle = (j + turn) * 2f * PI.toFloat() / count
                DISC_X[sample] = radius * cos(angle)
                DISC_Y[sample] = radius * sin(angle)
                sample++
            }
        }
    }

    private fun linear(channel: Float): Float {
        val c = (channel / 255f).coerceIn(0f, 1f)
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun oklab(r: Float, g: Float, b: Float, out: FloatArray) {
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    private fun oklab(rgb: Int, out: FloatArray) =
        oklab(LINEAR[(rgb shr 16) and 255], LINEAR[(rgb shr 8) and 255], LINEAR[rgb and 255], out)

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val l = a[0] - b[0]; val u = a[1] - b[1]; val v = a[2] - b[2]
        return sqrt(l * l + u * u + v * v)
    }

    /** Looks at the border of the picture: transparent, clearly one colour, or neither. */
    fun background(pixels: PaintPixels): PaintBackground {
        val w = pixels.width
        val h = pixels.height
        val band = max(1, (min(w, h) * 0.02f).roundToInt())
        val stride = max(1, min(w, h) / 256)
        var total = 0
        val border = ArrayList<Int>()
        for (y in 0 until h step stride) {
            val edgeRow = y < band || y >= h - band
            for (x in 0 until w step stride) if (edgeRow || x < band || x >= w - band) {
                total++
                val pixel = pixels.argb[y * w + x]
                if ((pixel ushr 24) >= 128) border.add(pixel and 0xffffff)
            }
        }
        if (border.size < 0.5f * total) return PaintBackground(transparent = true, clear = true)
        val counts = IntArray(4096)
        border.forEach { counts[coarse(it)]++ }
        val common = counts.indices.maxByOrNull { counts[it] } ?: 0
        val seed = mean(border.filter { coarse(it) == common })
        val seedLab = FloatArray(3).also { oklab(linear(seed[0]), linear(seed[1]), linear(seed[2]), it) }
        val lab = FloatArray(3)
        val close = border.filter { oklab(it, lab); distance(lab, seedLab) <= TOLERANCE }
        val color = mean(close.ifEmpty { border })
        return PaintBackground(
            color = (color[0].roundToInt() shl 16) or (color[1].roundToInt() shl 8) or color[2].roundToInt(),
            clear = close.size >= 0.7f * border.size && border.size >= 0.9f * total,
        )
    }

    private fun coarse(rgb: Int) = (((rgb shr 20) and 15) shl 8) or (((rgb shr 12) and 15) shl 4) or ((rgb shr 4) and 15)

    private fun mean(colors: List<Int>): FloatArray {
        var r = 0L; var g = 0L; var b = 0L
        colors.forEach { r += (it shr 16) and 255; g += (it shr 8) and 255; b += it and 255 }
        val n = max(1, colors.size).toFloat()
        return floatArrayOf(r / n, g / n, b / n)
    }

    /**
     * Decides, for every pixel, how much it belongs to the picture.
     *
     * @param background the colour that stays dark (RGB), or null to light the whole picture
     * @param transparent the picture has a transparent background (see [PaintBackground.transparent])
     */
    fun picture(pixels: PaintPixels, background: Int? = null, transparent: Boolean = false): PaintPicture {
        val w = pixels.width
        val h = pixels.height
        val count = w * h
        val weight = FloatArray(count)
        val backgroundLab = background?.let { FloatArray(3).also { out -> oklab(it, out) } }
        val away = if (backgroundLab != null) FloatArray(count) else null
        val dark = BooleanArray(count)
        val lab = FloatArray(3)
        // Neighbouring pixels mostly share their colour: the last one is remembered.
        var last = -1
        var light = 0f
        var chroma = 0f
        var far = 0f
        var pictured = 0
        var black = 0
        for (o in 0 until count) {
            val pixel = pixels.argb[o]
            if ((pixel ushr 24) < 128) continue
            val rgb = pixel and 0xffffff
            if (rgb != last) {
                oklab(rgb, lab)
                last = rgb
                light = lab[0]
                chroma = hypot(lab[1], lab[2])
                far = if (backgroundLab != null) distance(lab, backgroundLab) else 0f
            }
            if (away != null) {
                away[o] = far
                if (far <= TOLERANCE) continue
            }
            weight[o] = 1f
            pictured++
            if ((light < DARK && chroma < 0.08f) || light < BLACK) { dark[o] = true; black++ }
        }
        val removed = transparent || backgroundLab != null
        // Black cannot be lit. It is the picture only when it is nearly all that is left of it.
        val ink = removed && pictured > 0 && black >= 0.6f * pictured && !(backgroundLab != null && backgroundLab[0] < DARK)
        if (away != null && backgroundLab != null) {
            // How unlike the background a pixel is, measured against the picture's own clear parts: a halo
            // around a shape counts only in part. Black that stays off is not one of these parts, or a pale
            // colour elsewhere in the picture would count as its halo.
            val bins = IntArray(HISTOGRAM)
            var size = 0
            for (o in 0 until count) if (weight[o] > 0f && (ink || !dark[o])) {
                bins[min(HISTOGRAM - 1, (away[o] * HISTOGRAM / 2f).toInt())]++
                size++
            }
            if (size > 0) {
                val top = quantile(bins, size, 0.95f)
                val level = flatLevels(bins, size)
                fun flat(o: Int) = away[o] >= 1.5f * TOLERANCE && level[min(HISTOGRAM - 1, (away[o] * HISTOGRAM / 2f).toInt())]
                var low = TOLERANCE
                var full = max(TOLERANCE * 1.5f, 0.5f * top)
                if (!ink) {
                    // Without a glow, what differs from the background by more than a halo is the picture, on a
                    // light ground and on a dark one: yellow lies close to white, navy close to black.
                    val capped = min(full, CLEAR)
                    full = capped
                    if (backgroundLab[0] < LIGHT) {
                        // A glow needs a dark background. When much of what differs from it differs only faintly,
                        // without being a flat tone, the picture glows: then only its strong parts count.
                        var faint = 0
                        for (o in 0 until count) if (weight[o] > 0f && !dark[o] && away[o] < FAINT * top && !flat(o)) faint++
                        val glow = ((faint.toFloat() / size - GLOW_FROM) / (GLOW_FULL - GLOW_FROM)).coerceIn(0f, 1f)
                        low += glow * (GLOW_LOW * top - low)
                        full = capped + glow * (GLOW_HIGH * top - capped)
                    }
                }
                for (o in 0 until count) if (weight[o] > 0f) {
                    weight[o] = if (flat(o)) 1f else ((away[o] - low) / (full - low)).coerceIn(0f, 1f)
                }
            }
        }
        if (!ink) for (o in 0 until count) if (dark[o]) weight[o] = 0f

        // The content: where the picture is, without a few stray pixels, and whether it mirrors.
        val columns = IntArray(w)
        val rows = IntArray(h)
        var total = 0
        for (py in 0 until h) for (px in 0 until w) if (weight[py * w + px] > 0.5f) { columns[px]++; rows[py]++; total++ }
        if (total < 16) return PaintPicture(pixels, weight, ink, removed, 0f, 0f, w.toFloat(), h.toFloat(), false, false)
        val across = span(columns, total)
        val down = span(rows, total)
        var bothX = 0
        var anyX = 0
        var bothY = 0
        var anyY = 0
        for (py in down[0] until down[1]) for (px in across[0] until across[1]) {
            val here = weight[py * w + px] > 0.5f
            val mirroredX = weight[py * w + (across[0] + across[1] - 1 - px)] > 0.5f
            val mirroredY = weight[(down[0] + down[1] - 1 - py) * w + px] > 0.5f
            if (here && mirroredX) bothX++
            if (here || mirroredX) anyX++
            if (here && mirroredY) bothY++
            if (here || mirroredY) anyY++
        }
        return PaintPicture(pixels, weight, ink, removed, across[0].toFloat(), down[0].toFloat(), across[1].toFloat(), down[1].toFloat(),
            mirrorX = bothX > 0.9f * anyX, mirrorY = bothY > 0.9f * anyY)
    }

    /** Which bins of the distance histogram are flat tones (see [FLAT_SHARE]). */
    private fun flatLevels(bins: IntArray, size: Int): BooleanArray {
        val n = bins.size
        val sums = IntArray(n + 1)
        for (bin in 0 until n) sums[bin + 1] = sums[bin] + bins[bin]
        fun total(from: Int, to: Int) = sums[to.coerceIn(0, n)] - sums[from.coerceIn(0, n)]
        val flat = BooleanArray(n)
        for (bin in 0 until n) {
            if (bins[bin] == 0) continue
            val band = total(bin - 3, bin + 4)
            if (band < FLAT_SHARE * size) continue
            // against the denser of the two sides: at the edge of the range one side is empty
            val beside = max(total(bin - 24, bin - 7), total(bin + 8, bin + 25)) / 17f
            if (band / 7f >= FLAT_OVER * beside) for (k in max(0, bin - 3)..min(n - 1, bin + 3)) flat[k] = true
        }
        return flat
    }

    /** First and one past the last index that hold all but the outer 0.03 % of [total]. */
    private fun span(counts: IntArray, total: Int): IntArray {
        var sum = 0
        var first = -1
        var last = counts.size - 1
        for (i in counts.indices) {
            sum += counts[i]
            if (first < 0 && sum > 0.0003f * total) first = i
            if (sum >= 0.9997f * total) { last = i; break }
        }
        return intArrayOf(max(0, first), last + 1)
    }

    /** The value below which [share] of the [size] counted distances lie; the bins span 0 to 2. */
    private fun quantile(bins: IntArray, size: Int, share: Float): Float {
        val position = share * (size - 1)
        var seen = 0f
        for (bin in bins.indices) {
            if (bins[bin] > 0 && seen + bins[bin] > position) return (bin + (position - seen) / bins[bin]) * 2f / bins.size
            seen += bins[bin]
        }
        return 2f
    }

    private fun graph(points: Map<Int, Offset>, x: FloatArray, y: FloatArray): HoldGraph =
        // The same holds are mapped again for every move of the photo: their neighbours are worked out once.
        neighbours?.takeIf { it.first === points }?.second ?: HoldGraph(x, y).also { neighbours = points to it }

    /**
     * What share of each hold's surroundings belongs to the picture. [reach] widens the disc.
     * With [rgb] and [share], every sample that belongs to the picture is kept as well; with [footX]
     * and [footY], where in the viewport the picture lies that the hold sees.
     */
    private fun cover(
        picture: PaintPicture, toPixel: PaintAffine, x: FloatArray, y: FloatArray, graph: HoldGraph, reach: Float,
        rgb: IntArray? = null, share: FloatArray? = null, footX: FloatArray? = null, footY: FloatArray? = null,
    ): FloatArray {
        val w = picture.pixels.width
        val h = picture.pixels.height
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val radius = graph.radius(i) * reach
            var sum = 0f
            var sumX = 0f
            var sumY = 0f
            for (s in 0 until SAMPLES) {
                val vx = x[i] + DISC_X[s] * radius
                val vy = y[i] + DISC_Y[s] * radius
                val px = toPixel.xx * vx + toPixel.xy * vy + toPixel.x0
                val py = toPixel.yx * vx + toPixel.yy * vy + toPixel.y0
                // Written so that a position that is not a number is outside the picture as well.
                if (!(px >= 0f && py >= 0f && px < w && py < h)) continue
                val o = py.toInt() * w + px.toInt()
                val weight = picture.weight[o]
                if (weight <= 0f) continue
                sum += weight
                sumX += weight * (vx - x[i])
                sumY += weight * (vy - y[i])
                if (rgb != null && share != null) {
                    rgb[i * SAMPLES + s] = picture.pixels.argb[o] and 0xffffff
                    share[i * SAMPLES + s] = weight
                }
            }
            out[i] = sum / SAMPLES
            if (footX != null && footY != null) {
                footX[i] = if (sum > 0f) x[i] + sumX / sum else x[i]
                footY[i] = if (sum > 0f) y[i] + sumY / sum else y[i]
            }
        }
        return out
    }

    /** Filled parts: half coverage, judged together with the holds around, so outlines come out even. */
    private fun areas(graph: HoldGraph, cover: FloatArray): BooleanArray {
        val near = graph.near
        val n = cover.size
        val area = BooleanArray(n)
        for (i in 0 until n) {
            var sum = 0f
            for (j in near[i]) sum += cover[j]
            val around = if (near[i].isEmpty()) 0f else sum / near[i].size
            area[i] = cover[i] > FILL && cover[i] + SMOOTH * (around - 0.5f) >= 0.5f
        }
        // Close single holes: a partly covered hold that is almost surrounded.
        repeat(2) {
            val add = ArrayList<Int>()
            for (i in 0 until n) if (!area[i] && near[i].size >= 3 && cover[i] >= FILL) {
                var on = 0
                for (j in near[i]) if (area[j]) on++
                if (on >= 0.75f * near[i].size) add.add(i)
            }
            add.forEach { area[it] = true }
        }
        return area
    }

    /**
     * The areas and, thinned to chains, the lines: everything that is lit.
     * [footX] and [footY] tell where the picture lies that a hold's wide disc sees.
     */
    private fun lit(
        graph: HoldGraph, x: FloatArray, y: FloatArray, cover: FloatArray, wide: FloatArray, area: BooleanArray,
        footX: FloatArray, footY: FloatArray,
    ): BooleanArray {
        val near = graph.near
        val n = cover.size
        // Two lit holds belong together when both lie in an area, or when what they see of the picture lies no
        // farther apart than they do: holds on either side of the gap between two lines see different lines.
        fun linked(i: Int, j: Int) = (area[i] && area[j]) ||
            hypot(footX[i] - footX[j], footY[i] - footY[j]) <= SAME * hypot(x[i] - x[j], y[i] - y[j])
        val lit = area.copyOf()
        val line = BooleanArray(n)
        for (i in 0 until n) if (!area[i]) {
            var best = wide[i]
            for (j in near[i]) if (wide[j] > best) best = wide[j]
            line[i] = wide[i] >= max(LINE, LINE_SHARE * best)
        }
        // A hold in the dark valley between two strong parts of the picture joins nothing: left out,
        // close parallel lines do not grow together.
        for (i in 0 until n) if (line[i] && cover[i] < VALLEY) {
            val strong = near[i].filter { cover[it] >= STRONG }
            search@ for (a in strong.indices) for (b in a + 1 until strong.size) {
                val ax = x[strong[a]] - x[i]
                val ay = y[strong[a]] - y[i]
                val bx = x[strong[b]] - x[i]
                val by = y[strong[b]] - y[i]
                if (ax * bx + ay * by <= -0.5f * hypot(ax, ay) * hypot(bx, by)) { line[i] = false; break@search }
            }
        }
        for (i in 0 until n) if (line[i]) lit[i] = true
        // Thin the candidates to chains: weakest first, never breaking a connection, never shortening a strong end.
        // A candidate that also touches a lit hold it does not belong with lies between two lines and goes first,
        // so the lines end up apart.
        val between = BooleanArray(n) { i -> line[i] && near[i].any { lit[it] && !linked(i, it) } }
        val order = (0 until n).filter { line[it] }
            .sortedWith(compareBy<Int>({ if (between[it]) 0 else 1 }, { wide[it] }, { cover[it] }, { it }))
        var changed = true
        while (changed) {
            changed = false
            for (i in order) if (lit[i]) {
                var on = 0
                var only = -1
                for (j in near[i]) if (lit[j] && linked(i, j)) { on++; only = j }
                val drop = when (on) {
                    0 -> cover[i] < DOT && wide[i] < DOT_WIDE
                    1 -> wide[i] < max(LINE, LINE_END * wide[only])
                    else -> spare(i, lit, near, ::linked)
                }
                if (drop) { lit[i] = false; changed = true }
            }
        }
        // What is left of two line holds that touch no area is a dot: one hold, the stronger.
        val seen = BooleanArray(n)
        for (i in 0 until n) if (lit[i] && !area[i] && !seen[i]) {
            val group = arrayListOf(i)
            val stack = ArrayDeque(listOf(i))
            var alone = true
            seen[i] = true
            while (stack.isNotEmpty()) {
                val from = stack.removeLast()
                for (to in near[from]) if (lit[to]) {
                    if (area[to]) alone = false else if (!seen[to]) { seen[to] = true; group.add(to); stack.add(to) }
                }
            }
            if (alone && group.size == 2) {
                val a = group[0]
                val b = group[1]
                val keep = when {
                    wide[a] != wide[b] -> if (wide[a] > wide[b]) a else b
                    cover[a] != cover[b] -> if (cover[a] > cover[b]) a else b
                    else -> min(a, b)
                }
                lit[a] = a == keep
                lit[b] = b == keep
            }
        }
        // A lone hold stays when the picture covers a good part of it: a dot about a hold in size, not a speck.
        for (i in 0 until n) if (lit[i] && cover[i] < DOT && wide[i] < DOT_WIDE && near[i].none { lit[it] }) lit[i] = false
        return lit
    }

    /** The lit holds that [i] belongs with stay connected to each other without it. */
    private fun spare(i: Int, lit: BooleanArray, near: Array<IntArray>, linked: (Int, Int) -> Boolean): Boolean {
        val on = near[i].filter { lit[it] && linked(i, it) }
        if (on.size <= 1) return true
        val seen = BooleanArray(on.size)
        val stack = ArrayDeque<Int>()
        seen[0] = true
        stack.add(0)
        var reached = 1
        while (stack.isNotEmpty()) {
            val from = on[stack.removeLast()]
            for (k in on.indices) if (!seen[k] && on[k] in near[from] && linked(from, on[k])) { seen[k] = true; reached++; stack.add(k) }
        }
        return reached == on.size
    }

    /** As [map] for a prepared picture, preparing it first. */
    fun map(
        pixels: PaintPixels,
        toPixel: PaintAffine,
        points: Map<Int, Offset>,
        palette: List<Int>,
        background: Int? = null,
        transparent: Boolean = false,
    ): Map<Int, Int> = map(picture(pixels, background, transparent), toPixel, points, palette)

    /**
     * @param points hold id to its position in the viewport
     * @param toPixel viewport position to pixel position in the picture
     * @return hold id to palette colour, for the lit holds only
     */
    fun map(picture: PaintPicture, toPixel: PaintAffine, points: Map<Int, Offset>, palette: List<Int>): Map<Int, Int> {
        val n = points.size
        if (n == 0 || palette.isEmpty()) return emptyMap()
        val ids = IntArray(n)
        val x = FloatArray(n)
        val y = FloatArray(n)
        var index = 0
        for ((id, point) in points) { ids[index] = id; x[index] = point.x; y[index] = point.y; index++ }
        val graph = graph(points, x, y)
        val near = graph.near
        val count = n * SAMPLES
        val rgb = IntArray(count)
        val weight = FloatArray(count)
        val cover = cover(picture, toPixel, x, y, graph, 1f, rgb, weight)
        val rgbWide = IntArray(count)
        val weightWide = FloatArray(count)
        val footX = FloatArray(n)
        val footY = FloatArray(n)
        val wide = cover(picture, toPixel, x, y, graph, WIDE, rgbWide, weightWide, footX, footY)
        val lit = lit(graph, x, y, cover, wide, areas(graph, cover), footX, footY)
        val removed = picture.removed
        val ink = picture.ink
        val lab = FloatArray(3)

        // Colours: each lit hold takes its most common colour.
        val red = FloatArray(n)
        val green = FloatArray(n)
        val blue = FloatArray(n)
        // how much of what a hold sees is its leading colour
        val pure = FloatArray(n)
        for (i in 0 until n) if (lit[i]) {
            val from = i * SAMPLES
            // A hold on a thin line may see nothing of it in its own disc: then the wider one tells the colour.
            var seen = 0f
            for (s in 0 until SAMPLES) seen += weight[from + s]
            val colors = if (seen > 0.5f) rgb else rgbWide
            val shares = if (seen > 0.5f) weight else weightWide
            var all = 0f
            var leader = -1
            var leaderWeight = 0f
            for (s in 0 until SAMPLES) if (shares[from + s] > 0f) {
                all += shares[from + s]
                val key = fine(colors[from + s])
                var same = 0f
                for (t in 0 until SAMPLES) if (shares[from + t] > 0f && fine(colors[from + t]) == key) same += shares[from + t]
                if (same > leaderWeight || (same == leaderWeight && key < leader)) { leader = key; leaderWeight = same }
            }
            if (all <= 0f) { lit[i] = false; continue }
            pure[i] = leaderWeight / all
            if (ink) { red[i] = 255f; green[i] = 255f; blue[i] = 255f; continue }
            // No colour clearly leads (a gradient, a photo): take the mean.
            val mixed = leaderWeight < 0.5f * all
            var sum = 0f
            for (s in 0 until SAMPLES) {
                val o = from + s
                if (shares[o] <= 0f || (!mixed && fine(colors[o]) != leader)) continue
                sum += shares[o]
                red[i] += ((colors[o] shr 16) and 255) * shares[o]
                green[i] += ((colors[o] shr 8) and 255) * shares[o]
                blue[i] += (colors[o] and 255) * shares[o]
            }
            red[i] /= sum; green[i] /= sum; blue[i] /= sum
        }
        val holds = (0 until n).filter { lit[it] }
        if (holds.isEmpty()) return emptyMap()
        val paletteLab = Array(palette.size) { FloatArray(3).also { out -> oklab(PaintPalette.rgb(palette[it]), out) } }
        // A few fixed LED colours (MoonBoard, Quantum) always shine fully; so does a shape on a removed background.
        // A whole picture keeps its light and dark, lifted so that no LED is nearly off.
        val few = palette.size <= 16
        val values = FloatArray(holds.size) { max(red[holds[it]], max(green[holds[it]], blue[holds[it]])) }.also { it.sort() }
        val brightest = max(1f, percentile(values, values.size, 0.9f))
        val color = IntArray(n) { -1 }
        for (i in holds) {
            val value = max(1f, max(red[i], max(green[i], blue[i])))
            val level = if (removed || few) 1f else (value / brightest).pow(LIFT).coerceIn(LEVEL_MIN, 1f)
            oklab(linear(red[i]), linear(green[i]), linear(blue[i]), lab)
            val saturation = hypot(lab[1], lab[2])
            if (saturation < GREY) oklab(linear(255f * level), linear(255f * level), linear(255f * level), lab)
            else if (few) {
                // The hue decides, not the lightness: a light blue is blue, not green.
                color[i] = paletteLab.indices.maxByOrNull { p ->
                    val entry = paletteLab[p]
                    (entry[1] * lab[1] + entry[2] * lab[2]) / max(1e-6f, hypot(entry[1], entry[2]) * saturation)
                } ?: 0
                continue
            } else {
                val scale = 255f * level / value
                oklab(linear(red[i] * scale), linear(green[i] * scale), linear(blue[i] * scale), lab)
            }
            color[i] = paletteLab.indices.minByOrNull { distance(paletteLab[it], lab) } ?: 0
        }
        // Few colours: near duplicates join the more common colour, and so does a rare colour that is still close.
        val uses = holds.groupingBy { color[it] }.eachCount()
        val enough = max(2, ceil(0.02f * holds.size).toInt())
        val kept = ArrayList<Int>()
        val merged = HashMap<Int, Int>()
        for ((entry, used) in uses.entries.sortedWith(compareBy({ -it.value }, { it.key }))) {
            val nearest = kept.minByOrNull { distance(paletteLab[it], paletteLab[entry]) }
            val gap = nearest?.let { distance(paletteLab[it], paletteLab[entry]) } ?: Float.MAX_VALUE
            val joins = nearest != null && (gap < MERGE || kept.size >= MAX_COLORS || (used < enough && gap < RARE))
            if (joins) merged[entry] = nearest!! else { kept.add(entry); merged[entry] = entry }
        }
        for (i in holds) color[i] = merged.getValue(color[i])
        // A hold that differs from all its lit neighbours follows most of them, unless it is clearly one colour:
        // then it is a detail of the picture, an eye or a dot.
        repeat(2) {
            val change = HashMap<Int, Int>()
            for (i in holds) {
                val around = near[i].filter { lit[it] }.map { color[it] }
                if (around.size < 3 || color[i] in around || pure[i] >= SOLID) continue
                val (most, times) = around.groupingBy { it }.eachCount().entries
                    .sortedWith(compareBy({ -it.value }, { it.key })).first()
                if (times >= 0.6f * around.size) change[i] = most
            }
            change.forEach { (i, to) -> color[i] = to }
        }
        // Two LED colours whose parts of the picture look alike are one colour of the picture: a hue between
        // two of the board's few colours must not flip from hold to hold. A photo that fills the frame keeps
        // its shades and its bands of colour.
        while (removed) {
            val groups = holds.groupBy { color[it] }
            if (groups.size < 2) break
            val means = groups.mapValues { (_, members) ->
                var r = 0f
                var g = 0f
                var b = 0f
                members.forEach { r += red[it]; g += green[it]; b += blue[it] }
                FloatArray(3).also { out -> oklab(linear(r / members.size), linear(g / members.size), linear(b / members.size), out) }
            }
            val keys = groups.keys.sorted()
            var closest = if (few) ALIKE_FEW else ALIKE
            var keep = -1
            var drop = -1
            for (a in keys) for (b in keys) if (a < b) {
                val gap = distance(means.getValue(a), means.getValue(b))
                if (gap < closest) {
                    closest = gap
                    if (groups.getValue(a).size >= groups.getValue(b).size) { keep = a; drop = b } else { keep = b; drop = a }
                }
            }
            if (keep < 0) break
            groups.getValue(drop).forEach { color[it] = keep }
        }
        // A patch of a colour smaller than a few holds is noise at this size (small print, a speckle): it takes
        // the colour around it, unless it mostly borders on dark holds, as a line does. A small patch with a hold
        // that is clearly one colour is a detail, an eye or a dot, and stays. Nor in a photo that fills the frame:
        // there a few holds may be the sun.
        val visited = BooleanArray(n)
        for (start in holds) if (removed && !visited[start]) {
            val patch = arrayListOf(start)
            val stack = ArrayDeque(listOf(start))
            visited[start] = true
            while (stack.isNotEmpty()) {
                val from = stack.removeLast()
                for (to in near[from]) if (lit[to] && !visited[to] && color[to] == color[start]) { visited[to] = true; patch.add(to); stack.add(to) }
            }
            if (patch.size >= PATCH || patch.any { pure[it] >= SOLID }) continue
            val around = ArrayList<Int>()
            var dark = 0
            for (from in patch) for (to in near[from]) {
                if (!lit[to]) dark++ else if (color[to] != color[start]) around.add(color[to])
            }
            if (around.isEmpty() || around.size < dark) continue
            val most = around.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).first().key
            patch.forEach { color[it] = most }
        }
        return holds.associate { ids[it] to palette[color[it]] }
    }

    /**
     * Where a photo falls most cleanly on the holds.
     *
     * A new photo ([from] is null) is fitted with its content to the area of the holds. Then, as for a
     * photo that was placed by hand, placements close by are tried: shifts up to one hold spacing and a
     * slightly smaller zoom. The one wins on which the holds are either inside the picture or outside
     * it, lines run over holds, and a picture that mirrors gets a mirrored pattern. A photo without a
     * removed background has no shapes to settle and is only fitted.
     *
     * A board may light only [most] holds of one colour of its [palette]: a new photo is then made smaller,
     * about its content, until it does not ask for more.
     *
     * @param image the size the transform refers to (the photo's own size)
     * @param points hold id to its position in the viewport
     */
    fun place(
        picture: PaintPicture, image: Size, viewport: Size, points: Map<Int, Offset>, from: PaintImageTransform? = null,
        palette: List<Int> = emptyList(), most: Int = Int.MAX_VALUE,
    ): PaintImageTransform {
        val fallback = from ?: PaintImageTransform()
        val n = points.size
        if (n < 3 || viewport.width <= 0f || viewport.height <= 0f || image.width <= 0f || image.height <= 0f) return fallback
        val x = FloatArray(n)
        val y = FloatArray(n)
        var index = 0
        for ((_, point) in points) { x[index] = point.x; y[index] = point.y; index++ }
        val graph = graph(points, x, y)
        val near = graph.near
        val spacing = graph.spacing
        if (spacing <= 0f) return fallback
        // the content box, in the coordinates of the photo
        val kx = image.width / picture.pixels.width
        val ky = image.height / picture.pixels.height
        val left = picture.left * kx
        val right = picture.right * kx
        val top = picture.top * ky
        val bottom = picture.bottom * ky
        if (right - left <= 0f || bottom - top <= 0f) return fallback
        val base = min(viewport.width / image.width, viewport.height / image.height)
        val qx = (left + right) / 2 - image.width / 2
        val qy = (top + bottom) / 2 - image.height / 2
        var minX = x[0]
        var maxX = x[0]
        var minY = y[0]
        var maxY = y[0]
        for (i in 1 until n) { minX = min(minX, x[i]); maxX = max(maxX, x[i]); minY = min(minY, y[i]); maxY = max(maxY, y[i]) }
        val fitted = from ?: run {
            val pad = 0.5f * spacing
            val zoom = (min((maxX - minX + 2 * pad) / (right - left), (maxY - minY + 2 * pad) / (bottom - top)) / base).coerceIn(.1f, 20f)
            val scale = base * zoom
            PaintImageTransform(Offset(((minX + maxX) / 2 - qx * scale) / viewport.width, ((minY + maxY) / 2 - qy * scale) / viewport.height), zoom, 0f)
        }
        // A placement that is not a number cannot be settled.
        if (!(fitted.zoom.isFinite() && fitted.center.x.isFinite() && fitted.center.y.isFinite() && fitted.degrees.isFinite())) return fallback

        fun settle(start: PaintImageTransform): PaintImageTransform {
            val degrees = start.degrees
            val mirrorX = picture.mirrorX && abs(degrees) <= 0.5f
            val mirrorY = picture.mirrorY && abs(degrees) <= 0.5f
            val radians = degrees * PI.toFloat() / 180f
            val rx = qx * cos(radians) - qy * sin(radians)
            val ry = qx * sin(radians) + qy * cos(radians)
            // where the content's centre lies now, in the viewport
            val centreX = start.center.x * viewport.width + rx * base * start.zoom
            val centreY = start.center.y * viewport.height + ry * base * start.zoom
            val pad = 0.55f * spacing
            val partners = HashMap<Int, IntArray>()
            fun uneven(lit: BooleanArray, axis: Float, upright: Boolean): Float {
                val partner = partners.getOrPut((axis * 100f).roundToInt() * 2 + if (upright) 1 else 0) {
                    IntArray(n) { i ->
                        val tx = if (upright) 2 * axis - x[i] else x[i]
                        val ty = if (upright) y[i] else 2 * axis - y[i]
                        var best = -1
                        var bestGap = 0.12f * spacing
                        for (j in 0 until n) {
                            if (abs(y[j] - ty) >= bestGap || abs(x[j] - tx) >= bestGap) continue
                            val gap = hypot(x[j] - tx, y[j] - ty)
                            if (gap < bestGap) { bestGap = gap; best = j }
                        }
                        best
                    }
                }
                var on = 0
                var paired = 0
                for (i in 0 until n) if (lit[i]) { on++; if (partner[i] >= 0 && lit[partner[i]]) paired++ }
                return if (on == 0) 1f else 1f - paired.toFloat() / on
            }
            fun mirrored(lit: BooleanArray, ax: Float, ay: Float) =
                (if (mirrorX) MIRROR_X * uneven(lit, ax, true) else 0f) + (if (mirrorY) MIRROR_Y * uneven(lit, ay, false) else 0f)

            class Trial(val score: Float, val transform: PaintImageTransform, val rest: Float, val ax: Float, val ay: Float)
            val trials = ArrayList<Trial>()
            for (step in 0 until ZOOM_STEPS) {
                val smaller = step * ZOOM_STEP
                val zoom = start.zoom * (1f - smaller)
                if (zoom < .1f) break
                val scale = base * zoom
                val halfWidth = (right - left) / 2 * scale
                val halfHeight = (bottom - top) / 2 * scale
                for (sx in -SHIFT_STEPS..SHIFT_STEPS) for (sy in -SHIFT_STEPS..SHIFT_STEPS) {
                    val ax = centreX + sx * spacing / SHIFT_STEPS
                    val ay = centreY + sy * spacing / SHIFT_STEPS
                    // A new photo keeps its content on the holds.
                    if (from == null && (ax - halfWidth < minX - pad - 1e-3f || ay - halfHeight < minY - pad - 1e-3f ||
                            ax + halfWidth > maxX + pad + 1e-3f || ay + halfHeight > maxY + pad + 1e-3f)) continue
                    val transform = PaintImageTransform(Offset((ax - rx * scale) / viewport.width, (ay - ry * scale) / viewport.height), zoom, degrees)
                    val cover = cover(picture, transform.pixelMap(image, viewport, picture.pixels), x, y, graph, 1f)
                    val area = areas(graph, cover)
                    // Beside an area a partly covered hold is a ragged outline. Away from any area it is a line, and a
                    // line is placed well when few holds carry it strongly instead of many weakly.
                    var off = 0f
                    var sum = 0f
                    for (i in 0 until n) {
                        sum += cover[i]
                        off += when {
                            area[i] -> 1f - cover[i]
                            cover[i] <= 0f -> 0f
                            near[i].any { area[it] } -> cover[i]
                            else -> cover[i] * (1f - min(1f, cover[i] / 0.5f))
                        }
                    }
                    val rest = SMALLER * smaller + FARTHER * (abs(sx) + abs(sy)).toFloat() / SHIFT_STEPS
                    trials.add(Trial(off / max(1f, sum) + mirrored(area, ax, ay) + rest, transform, rest, ax, ay))
                }
            }
            if (trials.isEmpty()) return start
            // The best of the first pass again, now with their lines.
            var best = start
            var bestScore = Float.MAX_VALUE
            for (trial in trials.withIndex().sortedWith(compareBy({ it.value.score }, { it.index })).take(KEEP).map { it.value }) {
                val toPixel = trial.transform.pixelMap(image, viewport, picture.pixels)
                val cover = cover(picture, toPixel, x, y, graph, 1f)
                val footX = FloatArray(n)
                val footY = FloatArray(n)
                val wide = cover(picture, toPixel, x, y, graph, WIDE, footX = footX, footY = footY)
                val area = areas(graph, cover)
                val lit = lit(graph, x, y, cover, wide, area, footX, footY)
                var strongest = 0f
                for (i in 0 until n) if (lit[i] && !area[i] && wide[i] > strongest) strongest = wide[i]
                if (strongest <= 0f) strongest = 1f
                var off = 0f
                var sum = 0f
                for (i in 0 until n) {
                    sum += cover[i]
                    off += if (area[i]) 1f - cover[i] else if (lit[i]) OFF_LINE * (1f - min(1f, wide[i] / strongest)) else cover[i]
                }
                val score = off / max(1f, sum) + mirrored(lit, trial.ax, trial.ay) + trial.rest
                if (score < bestScore) { bestScore = score; best = trial.transform }
            }
            return best
        }
        if (from != null) return settle(from)

        fun fits(transform: PaintImageTransform): Boolean {
            if (most == Int.MAX_VALUE || palette.isEmpty()) return true
            val colors = map(picture, transform.pixelMap(image, viewport, picture.pixels), points, palette)
            return colors.values.groupingBy { it }.eachCount().values.all { it <= most }
        }
        // smaller about the content's centre, which stays where it is (a new photo is not turned)
        fun smaller(transform: PaintImageTransform): PaintImageTransform {
            val zoom = transform.zoom * 0.95f
            val less = base * (transform.zoom - zoom)
            return transform.copy(center = Offset(transform.center.x + qx * less / viewport.width, transform.center.y + qy * less / viewport.height), zoom = zoom)
        }
        var start = fitted
        var tries = 0
        while (tries++ < 24 && start.zoom > .1f && !fits(start)) start = smaller(start)
        if (!picture.removed) return start
        var best = settle(start)
        tries = 0
        while (tries++ < 4 && start.zoom > .1f && !fits(best)) { start = smaller(start); best = settle(start) }
        return best
    }

    /** Five bits a channel: colours that look the same share a bin. */
    private fun fine(rgb: Int) = (((rgb shr 19) and 31) shl 10) or (((rgb shr 11) and 31) shl 5) or ((rgb shr 3) and 31)

    /** Of the first [size] values of [sorted]. */
    private fun percentile(sorted: FloatArray, size: Int, share: Float): Float {
        val position = share * (size - 1)
        val below = floor(position).toInt()
        val above = min(size - 1, below + 1)
        return sorted[below] + (sorted[above] - sorted[below]) * (position - below)
    }

    /**
     * Which holds are neighbours, from their positions alone, so it works for the staggered Kilter
     * rows, the MoonBoard grid with its missing cells and a Quantum board alike: two holds are
     * neighbours when no third hold lies in the circle between them (shrunk a little, so the
     * diagonals of a square grid count) and they are not far apart.
     */
    private class HoldGraph(x: FloatArray, y: FloatArray) {
        private val nearest: FloatArray
        /** The usual distance from a hold to the next one. */
        val spacing: Float
        val near: Array<IntArray>

        init {
            val n = x.size
            nearest = FloatArray(n) { Float.MAX_VALUE }
            for (i in 0 until n) for (j in i + 1 until n) {
                val d = hypot(x[i] - x[j], y[i] - y[j])
                if (d < nearest[i]) nearest[i] = d
                if (d < nearest[j]) nearest[j] = d
            }
            if (n == 1) nearest[0] = 0f
            spacing = nearest.sorted()[n / 2]
            val reach = 2.3f * spacing
            val around = Array(n) { i -> (0 until n).filter { it != i && hypot(x[i] - x[it], y[i] - y[it]) <= reach } }
            val links = Array(n) { ArrayList<Int>() }
            for (i in 0 until n) for (j in around[i]) if (j > i) {
                val mx = (x[i] + x[j]) / 2
                val my = (y[i] + y[j]) / 2
                val inside = hypot(x[i] - x[j], y[i] - y[j]) / 2 * 0.95f
                if (around[i].none { it != j && hypot(x[it] - mx, y[it] - my) < inside }) { links[i].add(j); links[j].add(i) }
            }
            near = Array(n) { links[it].toIntArray() }
        }

        /** Half the distance to the next hold, kept near the board's usual spacing. */
        fun radius(i: Int) = nearest[i].coerceIn(0.7f * spacing, 1.3f * spacing) * 0.5f
    }
}
