package com.cruxcoach.android.ui.paintbox

import androidx.compose.ui.geometry.Offset
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
 * Turns a picture into hold colours. Pure: pixels and hold positions in, colours out.
 *
 * Each hold looks at the area around it, not at one pixel, so the outline of a
 * shape neither frays nor depends on where exactly a hold centre falls:
 *
 * 1. Every hold takes [SAMPLES] samples from a disc half as wide as the hold
 *    spacing. The share that belongs to the picture (opaque, not the
 *    background, not black) is the hold's coverage.
 * 2. A hold is lit when it is covered at least half as much as the best
 *    covered hold around it. Inside and at the edge of a filled area that is
 *    plain half coverage; along a stroke thinner than the hold spacing, where
 *    no hold is fully covered, the bar drops with the stroke, so it stays
 *    connected. Single gaps in a stroke and single holes in an area are
 *    closed, lone weak specks are dropped.
 * 3. A hold takes its most common colour, not a mix. Near duplicates and rare
 *    stray colours join the common ones, and a hold that differs from all its
 *    lit neighbours follows them.
 *
 * LEDs cannot show black: black parts stay off. A black shape on a removed
 * background is the picture itself and lights white.
 */
internal object PaintImageMapping {
    /** Samples per hold. With some 500 holds that is 16 000 pixel reads, whatever the size of the picture. */
    const val SAMPLES = 32

    /** OKLab distance up to which a pixel still is the background colour: covers JPEG noise and soft gradients. */
    private const val TOLERANCE = 0.075f
    /** From this OKLab lightness on, a background is light: nothing in the picture is a glow. */
    private const val LIGHT = 0.5f
    /** On a light background, a pixel this far from it belongs to the picture in full. */
    private const val CLEAR = 0.2f
    /** A lit hold is covered at least this much ... */
    private const val FLOOR = 0.16f
    /** ... and at least this share of the best covered hold around it. */
    private const val LOCAL = 0.5f
    private const val BRIDGE = 0.12f
    private const val FILL = 0.15f
    /** OKLab lightness below which a grey counts as black, and below which any colour does. */
    private const val DARK = 0.25f
    private const val BLACK = 0.14f
    /** OKLab chroma below which a colour is a grey. */
    private const val GREY = 0.02f
    private const val MERGE = 0.09f
    private const val RARE = 0.2f
    private const val MAX_COLORS = 12
    private const val LIFT = 0.6f
    private const val LEVEL_MIN = 0.35f

    @Volatile private var neighbours: Pair<Map<Int, Offset>, HoldGraph>? = null

    private val DISC_X = FloatArray(SAMPLES) { sqrt((it + 0.5f) / SAMPLES) * cos(it * 2.3999631f) }
    private val DISC_Y = FloatArray(SAMPLES) { sqrt((it + 0.5f) / SAMPLES) * sin(it * 2.3999631f) }
    private val LINEAR = FloatArray(256) { linear(it.toFloat()) }

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
     * @param points hold id to its position in the viewport
     * @param toPixel viewport position to pixel position in [pixels]
     * @param background the colour that stays dark (RGB), or null to light the whole picture
     * @param transparent the picture has a transparent background (see [PaintBackground.transparent])
     * @return hold id to palette colour, for the lit holds only
     */
    fun map(
        pixels: PaintPixels,
        toPixel: PaintAffine,
        points: Map<Int, Offset>,
        palette: List<Int>,
        background: Int? = null,
        transparent: Boolean = false,
    ): Map<Int, Int> {
        val n = points.size
        if (n == 0 || palette.isEmpty()) return emptyMap()
        val ids = IntArray(n)
        val x = FloatArray(n)
        val y = FloatArray(n)
        var index = 0
        for ((id, point) in points) { ids[index] = id; x[index] = point.x; y[index] = point.y; index++ }
        // The same holds are mapped again for every move of the photo: their neighbours are worked out once.
        val graph = neighbours?.takeIf { it.first === points }?.second ?: HoldGraph(x, y).also { neighbours = points to it }
        val near = graph.near

        // 1. Coverage: what share of each hold's surroundings belongs to the picture.
        val w = pixels.width
        val h = pixels.height
        val count = n * SAMPLES
        val rgb = IntArray(count) { -1 }
        val light = FloatArray(count)
        val chroma = FloatArray(count)
        val away = FloatArray(count)
        val weight = FloatArray(count)
        val lab = FloatArray(3)
        val backgroundLab = background?.let { FloatArray(3).also { out -> oklab(it, out) } }
        for (i in 0 until n) {
            val radius = graph.radius(i)
            for (s in 0 until SAMPLES) {
                val vx = x[i] + DISC_X[s] * radius
                val vy = y[i] + DISC_Y[s] * radius
                val px = toPixel.xx * vx + toPixel.xy * vy + toPixel.x0
                val py = toPixel.yx * vx + toPixel.yy * vy + toPixel.y0
                // Written so that a position that is not a number is outside the picture as well.
                if (!(px >= 0f && py >= 0f && px < w && py < h)) continue
                val pixel = pixels.argb[py.toInt() * w + px.toInt()]
                if ((pixel ushr 24) < 128) continue
                val o = i * SAMPLES + s
                rgb[o] = pixel and 0xffffff
                oklab(pixel, lab)
                light[o] = lab[0]
                chroma[o] = hypot(lab[1], lab[2])
                if (backgroundLab == null) weight[o] = 1f else {
                    away[o] = distance(lab, backgroundLab)
                    if (away[o] > TOLERANCE) weight[o] = 1f
                }
            }
        }
        var pictured = 0
        var black = 0
        val dark = BooleanArray(count)
        for (o in 0 until count) if (weight[o] > 0f) {
            pictured++
            if ((light[o] < DARK && chroma[o] < 0.08f) || light[o] < BLACK) { dark[o] = true; black++ }
        }
        val removed = transparent || backgroundLab != null
        // Black cannot be lit. It is the picture only when it is nearly all that is left of it.
        val ink = removed && pictured > 0 && black >= 0.6f * pictured && !(backgroundLab != null && backgroundLab[0] < DARK)
        if (backgroundLab != null) {
            // How unlike the background a pixel is, measured against the picture's own clear parts:
            // a glow or a JPEG halo around a shape counts only in part. Black that stays off is not
            // one of these parts, or a pale colour elsewhere in the picture would count as its halo.
            val clear = FloatArray(count)
            var size = 0
            for (o in 0 until count) if (weight[o] > 0f && (ink || !dark[o])) clear[size++] = away[o]
            if (size > 0) {
                clear.sort(0, size)
                var full = max(TOLERANCE * 1.5f, 0.5f * percentile(clear, size, 0.95f))
                // A glow needs a dark background. On a light one, what differs by more than a halo is
                // the picture: yellow, cyan and light grey lie close to white, far from a dark colour beside them.
                // When black is the picture, its soft edge is measured against black itself.
                if (!ink && backgroundLab[0] >= LIGHT) full = min(full, CLEAR)
                for (o in 0 until count) if (weight[o] > 0f) {
                    weight[o] = ((away[o] - TOLERANCE) / (full - TOLERANCE)).coerceIn(0f, 1f)
                }
            }
        }
        if (!ink) for (o in 0 until count) if (dark[o]) weight[o] = 0f
        val cover = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            for (s in 0 until SAMPLES) sum += weight[i * SAMPLES + s]
            cover[i] = sum / SAMPLES
        }

        // 2. Which holds are lit.
        val lit = BooleanArray(n)
        for (i in 0 until n) {
            var best = cover[i]
            for (j in near[i]) if (cover[j] > best) best = cover[j]
            lit[i] = cover[i] > 0f && cover[i] >= max(FLOOR, LOCAL * best)
        }
        // Close the gaps along a stroke: a partly covered hold between two lit holds that nothing else connects.
        repeat(2) {
            val add = (0 until n).filter { i ->
                if (lit[i] || cover[i] < BRIDGE) return@filter false
                val on = near[i].filter { lit[it] }
                on.indices.any { a -> (a + 1 until on.size).any { b ->
                    on[b] !in near[on[a]] && near[on[a]].none { it != i && lit[it] && it in near[on[b]] }
                } }
            }
            add.forEach { lit[it] = true }
        }
        // Close single holes: a partly covered hold that is almost surrounded.
        repeat(2) {
            val add = (0 until n).filter { i ->
                !lit[i] && near[i].size >= 3 && cover[i] >= FILL && near[i].count { lit[it] } >= 0.75f * near[i].size
            }
            add.forEach { lit[it] = true }
        }
        // Drop lone specks unless the picture really covers that hold.
        (0 until n).filter { i -> lit[i] && cover[i] < 0.8f && near[i].none { lit[it] } }.forEach { lit[it] = false }

        // 3. Colours: each lit hold takes its most common colour.
        val red = FloatArray(n)
        val green = FloatArray(n)
        val blue = FloatArray(n)
        for (i in 0 until n) if (lit[i]) {
            if (ink) { red[i] = 255f; green[i] = 255f; blue[i] = 255f; continue }
            val from = i * SAMPLES
            var all = 0f
            var leader = -1
            var leaderWeight = 0f
            for (s in 0 until SAMPLES) if (weight[from + s] > 0f) {
                all += weight[from + s]
                val key = fine(rgb[from + s])
                var same = 0f
                for (t in 0 until SAMPLES) if (weight[from + t] > 0f && fine(rgb[from + t]) == key) same += weight[from + t]
                if (same > leaderWeight || (same == leaderWeight && key < leader)) { leader = key; leaderWeight = same }
            }
            // No colour clearly leads (a gradient, a photo): take the mean.
            val mixed = leaderWeight < 0.5f * all
            var sum = 0f
            for (s in 0 until SAMPLES) {
                val o = from + s
                if (weight[o] <= 0f || (!mixed && fine(rgb[o]) != leader)) continue
                sum += weight[o]
                red[i] += ((rgb[o] shr 16) and 255) * weight[o]
                green[i] += ((rgb[o] shr 8) and 255) * weight[o]
                blue[i] += (rgb[o] and 255) * weight[o]
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
        // A hold that differs from all its lit neighbours follows most of them.
        repeat(2) {
            val change = HashMap<Int, Int>()
            for (i in holds) {
                val around = near[i].filter { lit[it] }.map { color[it] }
                if (around.size < 3 || color[i] in around) continue
                val (most, times) = around.groupingBy { it }.eachCount().entries
                    .sortedWith(compareBy({ -it.value }, { it.key })).first()
                if (times >= 0.6f * around.size) change[i] = most
            }
            change.forEach { (i, to) -> color[i] = to }
        }
        return holds.associate { ids[it] to palette[color[it]] }
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
        private val spacing: Float
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
