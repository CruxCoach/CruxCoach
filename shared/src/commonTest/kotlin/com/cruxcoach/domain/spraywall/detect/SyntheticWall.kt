package com.cruxcoach.domain.spraywall.detect

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic spray-wall photos with known holds, to measure the detector.
 *
 * A wall is a panel (plywood with grain and sheet seams, or paint), a grid of
 * T-nut holes, and holds of many sizes, shapes and colours with shading and a
 * drop shadow, some of them touching. Light falls off across the wall and
 * towards the corners, and there is sensor noise. Holds cover the holes they
 * sit on. Everything is seeded, so a scenario renders the same every time.
 */
internal object SyntheticWall {
    enum class Panel(val r: Int, val g: Int, val b: Int, val grain: Boolean) {
        PLYWOOD(206, 168, 118, true),
        BLACK(34, 34, 37, false),
        WHITE(236, 235, 230, false),
        GREY(132, 132, 135, false),
    }

    data class Colour(val name: String, val r: Int, val g: Int, val b: Int)

    val RED = Colour("red", 205, 38, 42)
    val BLUE = Colour("blue", 36, 82, 200)
    val YELLOW = Colour("yellow", 242, 212, 40)
    val GREEN = Colour("green", 46, 168, 72)
    val ORANGE = Colour("orange", 244, 128, 30)
    val PURPLE = Colour("purple", 128, 58, 160)
    val PINK = Colour("pink", 242, 118, 172)
    val TEAL = Colour("teal", 24, 160, 160)
    val BLACK = Colour("black", 28, 28, 30)
    val WHITE = Colour("white", 240, 240, 236)
    val GREY = Colour("grey", 118, 118, 122)
    /** Wooden holds on a plywood panel: hard on purpose, they differ mainly by shading. */
    val WOOD = Colour("wood", 178, 134, 88)
    val COLOURFUL = listOf(RED, BLUE, YELLOW, GREEN, ORANGE, PURPLE, PINK, TEAL)

    data class Spec(
        val name: String,
        val panel: Panel,
        val palette: List<Colour>,
        val holdCount: Int,
        /** Width of the wall shown in the photo; sets the size of holds and holes in pixels. */
        val wallWidthMeters: Float = 2.4f,
        /** 0 = even light, 1 = strong fall-off across the wall and into the corners. */
        val unevenLight: Float = 0.3f,
        /** Share of holds placed right next to another one. */
        val touching: Float = 0.25f,
        val width: Int = 1024,
        val height: Int = 768,
        val seed: Int = 1,
        val tNutSpacingMeters: Float = 0.2f,
        val holeRadiusMeters: Float = 0.006f,
        /** How much a hold's cast shadow darkens the panel (0.32 = to 68 %). */
        val shadow: Float = 0.32f,
        /** Sensor noise, standard deviation per channel. */
        val noise: Float = 3.2f,
        /** Out of focus: radius of a box blur applied twice. */
        val blur: Int = 0,
    )

    /** A hold as drawn: centre and area-equivalent radius in pixels. */
    data class Truth(val x: Float, val y: Float, val radius: Float, val colour: String)

    class Wall(val spec: Spec, val image: RgbImage, val holds: List<Truth>, val holes: List<Pair<Float, Float>>, val holeRadius: Float)

    private class Shape(
        val cx: Float, val cy: Float, val r: Float,
        val harmonics: FloatArray, val phases: FloatArray, val colour: Colour,
        val boltX: Float, val boltY: Float, val boltR: Float,
    ) {
        fun radiusAt(theta: Float): Float {
            var f = 1f
            for (k in harmonics.indices) f += harmonics[k] * cos((k + 2) * theta + phases[k])
            return r * f
        }
        val maxR: Float get() = r * (1f + harmonics.sum())
        fun reachTowards(x: Float, y: Float): Float = radiusAt(atan2(y - cy, x - cx))
        val equivalentRadius: Float get() = r * sqrt(1f + harmonics.fold(0f) { s, a -> s + a * a / 2f })
    }

    fun render(spec: Spec): Wall {
        val rnd = Random(spec.seed)
        val w = spec.width
        val h = spec.height
        val pxPerM = w / spec.wallWidthMeters
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)

        // Panel with grain and sheet seams.
        val grainPhase = rnd.nextFloat() * 10f
        val seamEvery = 1.22f * pxPerM
        val seamOffset = rnd.nextFloat() * seamEvery
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var f = 1f
            if (spec.panel.grain) {
                f += 0.035f * sin(2f * PI.toFloat() * (x / 31f + 0.35f * sin(y / 47f + grainPhase))) +
                    0.02f * sin(y / 9f + x / 140f)
                val seam = ((x - seamOffset) % seamEvery + seamEvery) % seamEvery
                if (seam < 1.5f) f *= 0.82f
            }
            r[i] = spec.panel.r * f; g[i] = spec.panel.g * f; b[i] = spec.panel.b * f
        }

        // T-nut holes on a square grid with a random phase.
        val spacing = spec.tNutSpacingMeters * pxPerM
        val holeR = max(1f, spec.holeRadiusMeters * pxPerM)
        val ox = rnd.nextFloat() * spacing
        val oy = rnd.nextFloat() * spacing
        val holes = ArrayList<Pair<Float, Float>>()
        var hy = oy
        while (hy < h) {
            var hx = ox
            while (hx < w) { holes += hx to hy; hx += spacing }
            hy += spacing
        }
        for ((hx, hyy) in holes) {
            disc(w, h, hx, hyy, holeR) { i, a ->
                r[i] = mix(r[i], 92f, a); g[i] = mix(g[i], 90f, a); b[i] = mix(b[i], 88f, a)
            }
            disc(w, h, hx, hyy, holeR * 0.68f) { i, a ->
                r[i] = mix(r[i], 18f, a); g[i] = mix(g[i], 18f, a); b[i] = mix(b[i], 20f, a)
            }
        }

        // Holds.
        val shapes = ArrayList<Shape>()
        var attempts = 0
        while (shapes.size < spec.holdCount && attempts < spec.holdCount * 200) {
            attempts++
            val radius = holdRadius(rnd) * pxPerM
            val n = 3
            val harmonics = FloatArray(n) { (rnd.nextFloat() * 0.16f) / (it + 1) }
            val phases = FloatArray(n) { rnd.nextFloat() * 2f * PI.toFloat() }
            val colour = spec.palette[rnd.nextInt(spec.palette.size)]
            val reach = radius * (1f + harmonics.sum())
            val (cx, cy) = if (shapes.isNotEmpty() && rnd.nextFloat() < spec.touching) {
                val other = shapes[rnd.nextInt(shapes.size)]
                val angle = rnd.nextFloat() * 2f * PI.toFloat()
                val d = other.r * 1.1f + radius * (0.95f + rnd.nextFloat() * 0.25f)
                (other.cx + cos(angle) * d) to (other.cy + sin(angle) * d)
            } else {
                (reach + rnd.nextFloat() * (w - 2 * reach)) to (reach + rnd.nextFloat() * (h - 2 * reach))
            }
            if (cx - reach < 0 || cy - reach < 0 || cx + reach >= w || cy + reach >= h) continue
            // Holds touch but cannot overlap: they are solid things bolted to the wall.
            val clash = shapes.any {
                val d = sqrt((it.cx - cx) * (it.cx - cx) + (it.cy - cy) * (it.cy - cy))
                d < it.reachTowards(cx, cy) + radius * (1f + harmonics.sum()) * 0.92f
            }
            if (clash) continue
            val bolt = radius > 0.028f * pxPerM
            shapes += Shape(
                cx, cy, radius, harmonics, phases, colour,
                boltX = cx + (rnd.nextFloat() - 0.5f) * radius * 0.4f,
                boltY = cy + (rnd.nextFloat() - 0.5f) * radius * 0.4f,
                boltR = if (bolt) 0.0055f * pxPerM else 0f,
            )
        }
        // Shadows first (light from the top left), then the holds.
        for (s in shapes) {
            val sx = s.cx + s.r * 0.14f
            val sy = s.cy + s.r * 0.24f
            shape(w, h, s, sx, sy, soft = max(1.5f, s.r * 0.15f)) { i, a ->
                val f = 1f - spec.shadow * a
                r[i] *= f; g[i] *= f; b[i] *= f
            }
        }
        for (s in shapes) {
            shape(w, h, s, s.cx, s.cy, soft = 1f) { i, a ->
                val x = i % w
                val y = i / w
                val dx = (x - s.cx) / s.maxR
                val dy = (y - s.cy) / s.maxR
                // Lit from the top left: brighter there, darker towards the bottom right and the rim.
                val rim = (dx * dx + dy * dy).coerceAtMost(1f)
                val shade = (1.1f - 0.22f * (dx + dy) - 0.18f * rim).coerceIn(0.55f, 1.25f)
                val speckle = 1f + (hash(x, y, spec.seed) - 0.5f) * 0.08f
                val f = shade * speckle
                r[i] = mix(r[i], (s.colour.r * f).coerceAtMost(255f), a)
                g[i] = mix(g[i], (s.colour.g * f).coerceAtMost(255f), a)
                b[i] = mix(b[i], (s.colour.b * f).coerceAtMost(255f), a)
            }
            if (s.boltR > 0f) disc(w, h, s.boltX, s.boltY, s.boltR) { i, a ->
                r[i] = mix(r[i], r[i] * 0.3f, a); g[i] = mix(g[i], g[i] * 0.3f, a); b[i] = mix(b[i], b[i] * 0.3f, a)
            }
        }

        // Uneven light and sensor noise.
        val light = spec.unevenLight
        val spotX = rnd.nextFloat() * w
        val spotY = rnd.nextFloat() * h * 0.5f
        val argb = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val nx = x.toFloat() / w
            val ny = y.toFloat() / h
            val vx = nx - 0.5f
            val vy = ny - 0.5f
            val spot = sqrt((x - spotX) * (x - spotX) + (y - spotY) * (y - spotY)) / w
            val illum = (1f - light * 0.45f * nx) * (1f - light * 0.2f * ny) * (1f - light * 0.9f * (vx * vx + vy * vy)) *
                (1f + light * 0.35f * (1f - spot.coerceAtMost(1f)))
            fun channel(v: Float): Int = (v * illum + gauss(rnd) * spec.noise).roundToInt().coerceIn(0, 255)
            argb[i] = (0xFF shl 24) or (channel(r[i]) shl 16) or (channel(g[i]) shl 8) or channel(b[i])
        }

        repeat(if (spec.blur > 0) 2 else 0) { boxBlur(argb, w, h, spec.blur) }

        val truths = shapes.map { Truth(it.cx, it.cy, it.equivalentRadius, it.colour.name) }
        // A hole counts only where no hold covers it.
        val visibleHoles = holes.filter { (hx, hyy) ->
            hx in 0f..(w - 1f) && hyy in 0f..(h - 1f) && shapes.none { s ->
                val dx = hx - s.cx
                val dy = hyy - s.cy
                sqrt(dx * dx + dy * dy) < s.radiusAt(atan2(dy, dx)) + holeR
            }
        }
        return Wall(spec, RgbImage(w, h, argb), truths, visibleHoles, holeR)
    }

    private fun boxBlur(argb: IntArray, w: Int, h: Int, r: Int) {
        val copy = argb.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0; var c = 0
            for (dy in -r..r) for (dx in -r..r) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                val p = copy[yy * w + xx]
                sr += p shr 16 and 0xFF; sg += p shr 8 and 0xFF; sb += p and 0xFF; c++
            }
            argb[y * w + x] = (0xFF shl 24) or ((sr / c) shl 16) or ((sg / c) shl 8) or (sb / c)
        }
    }

    /** Hold radius in metres: many foot chips and small holds, fewer big ones. */
    private fun holdRadius(rnd: Random): Float {
        val u = rnd.nextFloat()
        return when {
            u < 0.30f -> 0.015f + rnd.nextFloat() * 0.013f
            u < 0.65f -> 0.03f + rnd.nextFloat() * 0.02f
            u < 0.90f -> 0.05f + rnd.nextFloat() * 0.04f
            else -> 0.09f + rnd.nextFloat() * 0.07f
        }
    }

    private inline fun disc(w: Int, h: Int, cx: Float, cy: Float, radius: Float, paint: (Int, Float) -> Unit) {
        val x0 = max(0, (cx - radius - 1).toInt())
        val x1 = min(w - 1, (cx + radius + 1).toInt())
        val y0 = max(0, (cy - radius - 1).toInt())
        val y1 = min(h - 1, (cy + radius + 1).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            val a = (radius + 0.5f - d).coerceIn(0f, 1f)
            if (a > 0f) paint(y * w + x, a)
        }
    }

    private inline fun shape(w: Int, h: Int, s: Shape, cx: Float, cy: Float, soft: Float, paint: (Int, Float) -> Unit) {
        val reach = s.maxR + soft + 1
        val x0 = max(0, (cx - reach).toInt())
        val x1 = min(w - 1, (cx + reach).toInt())
        val y0 = max(0, (cy - reach).toInt())
        val y1 = min(h - 1, (cy + reach).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx
            val dy = y - cy
            val d = sqrt(dx * dx + dy * dy)
            val edge = s.radiusAt(atan2(dy, dx))
            val a = ((edge - d) / soft + 0.5f).coerceIn(0f, 1f)
            if (a > 0f) paint(y * w + x, a)
        }
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var v = x * 374761393 + y * 668265263 + seed * 2147483647
        v = (v xor (v ushr 13)) * 1274126177
        return ((v xor (v ushr 16)) and 0xFFFF) / 65535f
    }

    private fun gauss(rnd: Random): Float {
        // Sum of uniforms: close enough to a normal for sensor noise.
        return (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() - 2f) * 1.732f
    }

    // ── Scoring ─────────────────────────────────────────────────────────

    data class Score(
        val truths: Int, val found: Int, val matched: Int, val onHoles: Int,
        val missedByColour: Map<String, Int>,
    ) {
        val precision: Float get() = if (found == 0) 1f else matched.toFloat() / found
        val recall: Float get() = if (truths == 0) 1f else matched.toFloat() / truths
    }

    /**
     * Greedy one-to-one matching: a candidate hits a hold when its centre lies
     * within 60 % of the hold's radius (plus 2 px) and its radius is within
     * 0.45–2.2 times the hold's.
     */
    fun score(wall: Wall, candidates: List<HoldCandidate>, threshold: Float): Score {
        val w = wall.image.width
        val h = wall.image.height
        val found = candidates.filter { it.confidence >= threshold }
        val pairs = ArrayList<Triple<Int, Int, Float>>()
        found.forEachIndexed { ci, c ->
            val cx = c.x * w
            val cy = c.y * h
            val cr = c.radius * w
            wall.holds.forEachIndexed { ti, t ->
                val d = sqrt((cx - t.x) * (cx - t.x) + (cy - t.y) * (cy - t.y))
                val ratio = cr / t.radius
                if (d <= 0.6f * t.radius + 2f && ratio in 0.45f..2.2f) pairs += Triple(ci, ti, d / t.radius)
            }
        }
        pairs.sortBy { it.third }
        val usedC = HashSet<Int>()
        val usedT = HashSet<Int>()
        for ((ci, ti, _) in pairs) if (ci !in usedC && ti !in usedT) { usedC += ci; usedT += ti }
        val onHoles = found.indices.count { ci ->
            ci !in usedC && wall.holes.any { (hx, hy) ->
                val dx = found[ci].x * w - hx
                val dy = found[ci].y * h - hy
                sqrt(dx * dx + dy * dy) <= wall.holeRadius * 1.5f + 2f
            }
        }
        val missed = wall.holds.indices.filter { it !in usedT }.groupingBy { wall.holds[it].colour }.eachCount()
        return Score(wall.holds.size, found.size, usedT.size, onHoles, missed)
    }
}
