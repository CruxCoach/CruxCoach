package com.cruxcoach.domain.spraywall.detect

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Classical hold detector: no model, no network, no new dependency.
 *
 * 1. **Panel model.** The wall panel is the one surface that covers most of
 *    the photo. Its colour is the densest spot of the chroma histogram; its
 *    brightness is estimated per grid cell from panel-coloured pixels and
 *    interpolated, so a gradient of light across the wall is part of the
 *    panel, not a hold.
 * 2. **Saliency.** Every pixel scores how far it is from the panel: the log
 *    brightness ratio (dark and white holds, independent of the light) and the
 *    chroma distance (coloured holds), in units of the panel's own noise, so
 *    plywood grain stays near zero. Plain darkening by up to a third is how a
 *    cast shadow looks and does not count.
 * 3. **Regions.** Salient pixels form hold regions (small gaps such as a bolt
 *    hole are filled). Every local maximum of the distance to the panel is
 *    the middle of a hold, so touching holds split at their neck; rays from
 *    the middle that stop at the panel or at the saddle to the next hold give
 *    the radius.
 * 4. **Blobs.** A scale-space difference of Gaussians on the saliency finds
 *    round-ish blobs from foot chips to large holds, also where a hold is too
 *    faint to form a region. Blobs confirm regions; a blob where no region is
 *    becomes a suggestion of its own. Line-shaped responses (panel seams) are
 *    dropped by the Hessian ratio, bumps on a hold's rim by the bigger blob.
 * 5. **T-nut holes.** Holes are small, dark, grey, all of one size and on a
 *    regular grid. Candidates of that kind that share a size and a spacing
 *    are dropped, so the bolt holes never turn into suggestions; anything
 *    smaller than a small foot chip is dropped anyway.
 * 6. **Confidence** grows with the contrast to the panel, a compact shape and
 *    agreement between region and blob; it is stretched so the editor's
 *    default threshold of 0.5 keeps the clear holds.
 */
object SprayHoldDetector : HoldDetector {
    /** Smallest hold reported, as a fraction of the photo width (below a small foot chip on a full-wall photo). */
    const val MIN_RADIUS = 0.004f
    /** Largest hold reported (the editor's largest hold). */
    const val MAX_RADIUS = 0.12f

    override fun detect(image: RgbImage): List<HoldCandidate> = Run(image).detect()

    // ── Tuning ──────────────────────────────────────────────────────────
    private const val GRID_CELLS = 18
    /** Saliency below this many noise units is panel. */
    private const val PANEL_LEVEL = 3f
    /** Saliency above this counts as fully salient (keeps tiny, very dark spots from dominating). */
    private const val SALIENCY_CAP = 10f
    /** Log brightness of a cast shadow (about 0.68 of the lit panel). */
    private const val SHADOW_DEPTH = 0.38f
    private const val SIGMA0 = 1.2f
    private const val SCALES_PER_OCTAVE = 3
    private const val PEAK_MIN = 0.12f
    private const val EDGE_RATIO = 8f
    /** Radius of a disc whose difference-of-Gaussians response peaks at sigma 1. */
    private const val RADIUS_PER_SIGMA = 1.42f
    /** Peak response of a disc of saliency 1 at its best scale. */
    private const val DISC_GAIN = 0.19f
    private const val RAYS = 16
    /** Saliency from which a pixel belongs to a hold region. */
    private const val MASK_LEVEL = 4.5f
    /** Gaps inside a hold region up to this share of the width (as a radius) are filled. */
    private const val GAP_RADIUS = 0.02f

    private class Peak(val x: Float, val y: Float, val radius: Float, val response: Float)

    private class Scored(val x: Float, val y: Float, val radius: Float, val confidence: Float, val holeLike: Boolean)

    private class Run(image: RgbImage) {
        val w = image.width
        val h = image.height
        val n = w * h
        private val argb = image.argb

        /** Saliency per pixel (uncapped), the log brightness ratio to the panel and the chroma distance from it. */
        lateinit var saliency: FloatArray
        lateinit var lumDiff: FloatArray
        lateinit var chromaDiff: FloatArray
        var lumNoise = 0.03f
        var chromaNoise = 0.02f

        fun detect(): List<HoldCandidate> {
            buildSaliency()
            val soft = FloatArray(n)
            for (i in 0 until n) soft[i] = (min(saliency[i], SALIENCY_CAP) - PANEL_LEVEL).coerceAtLeast(0f)
            val peaks = scaleSpacePeaks(soft)
            val scored = peaks.mapNotNull { score(it) }
            val blobs = suppress(scored)
            return dropTNutHoles(union(regions(), blobs)).map {
                HoldCandidate(
                    x = (it.x + 0.5f) / w,
                    y = (it.y + 0.5f) / h,
                    radius = (it.radius / w).coerceIn(MIN_RADIUS, MAX_RADIUS),
                    confidence = calibrated(it.confidence),
                )
            }.sortedByDescending { it.confidence }
        }

        // ── 1 + 2: panel model and saliency ─────────────────────────────

        private fun buildSaliency() {
            val lum = FloatArray(n)
            val ca = FloatArray(n)
            val cb = FloatArray(n)
            for (i in 0 until n) {
                val p = argb[i]
                val r = (p shr 16 and 0xFF).toFloat()
                val g = (p shr 8 and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                lum[i] = ln(y + 10f)
                val norm = 1f / (y + 24f)
                ca[i] = (r - g) * norm
                cb[i] = ((r + g) * 0.5f - b) * norm
            }
            val tmp = FloatArray(n)
            // A light blur takes JPEG noise and grain off before anything is measured.
            Blur.gauss(lum, tmp, w, h, 1f)
            Blur.gauss(ca, tmp, w, h, 1f)
            Blur.gauss(cb, tmp, w, h, 1f)

            val (a0, b0) = panelChroma(ca, cb)
            val cell = max(8, (max(w, h) + GRID_CELLS - 1) / GRID_CELLS)
            val gx = (w + cell - 1) / cell
            val gy = (h + cell - 1) / cell
            val gridL = FloatArray(gx * gy) { Float.NaN }
            val gridA = FloatArray(gx * gy) { Float.NaN }
            val gridB = FloatArray(gx * gy) { Float.NaN }
            val bufL = FloatArray(cell * cell)
            val bufA = FloatArray(cell * cell)
            val bufB = FloatArray(cell * cell)
            for (cy in 0 until gy) for (cx in 0 until gx) {
                var count = 0
                var total = 0
                val y1 = min(h, (cy + 1) * cell)
                val x1 = min(w, (cx + 1) * cell)
                var yy = cy * cell
                while (yy < y1) {
                    var xx = cx * cell
                    while (xx < x1) {
                        val i = yy * w + xx
                        total++
                        val da = ca[i] - a0
                        val db = cb[i] - b0
                        if (da * da + db * db < PANEL_CHROMA_TOLERANCE * PANEL_CHROMA_TOLERANCE) {
                            bufL[count] = lum[i]; bufA[count] = ca[i]; bufB[count] = cb[i]
                            count++
                        }
                        xx += 2
                    }
                    yy += 2
                }
                if (count >= max(6, total / 6)) {
                    val k = cy * gx + cx
                    gridL[k] = densestValue(bufL, count)
                    gridA[k] = densestValue(bufA, count)
                    gridB[k] = densestValue(bufB, count)
                }
            }
            fillAndSmooth(gridL, gx, gy, ln(128f))
            fillAndSmooth(gridA, gx, gy, a0)
            fillAndSmooth(gridB, gx, gy, b0)

            // From here on lum holds the difference to the panel's brightness, ca the chroma distance.
            for (y in 0 until h) {
                val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, (gy - 1).toFloat())
                for (x in 0 until w) {
                    val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (gx - 1).toFloat())
                    val i = y * w + x
                    lum[i] -= bilinear(gridL, gx, gy, fx, fy)
                    val da = ca[i] - bilinear(gridA, gx, gy, fx, fy)
                    val db = cb[i] - bilinear(gridB, gx, gy, fx, fy)
                    ca[i] = sqrt(da * da + db * db)
                }
            }
            val dL = lum
            val dC = ca

            // Noise of the panel: the spread of the densest half of the values.
            lumNoise = max(0.018f, spreadOfDensestHalf(dL))
            chromaNoise = max(0.012f, percentile(dC, 0.4f) * 1.25f)

            val s = cb
            for (i in 0 until n) {
                // Plain darkening by up to a third is how a shadow looks: it does not count.
                val l = dL[i]
                val zl = (if (l < 0f) min(0f, l + SHADOW_DEPTH) else l) / lumNoise
                val zc = dC[i] / chromaNoise
                s[i] = sqrt(zl * zl + zc * zc)
            }
            saliency = s
            lumDiff = dL
            chromaDiff = dC
        }

        /** The panel's colour: the peak of a smoothed chroma histogram. */
        private fun panelChroma(ca: FloatArray, cb: FloatArray): Pair<Float, Float> {
            val bins = 2 * CHROMA_BINS + 1
            val hist = FloatArray(bins * bins)
            var i = 0
            while (i < n) {
                val bx = (ca[i] / CHROMA_BIN).roundToInt().coerceIn(-CHROMA_BINS, CHROMA_BINS) + CHROMA_BINS
                val by = (cb[i] / CHROMA_BIN).roundToInt().coerceIn(-CHROMA_BINS, CHROMA_BINS) + CHROMA_BINS
                hist[by * bins + bx] += 1f
                i += 3
            }
            var best = 0
            var bestValue = -1f
            for (y in 1 until bins - 1) for (x in 1 until bins - 1) {
                var v = 0f
                for (dy in -1..1) for (dx in -1..1) v += hist[(y + dy) * bins + x + dx]
                if (v > bestValue) { bestValue = v; best = y * bins + x }
            }
            // Refine to the mean of the pixels near the peak.
            val pa = (best % bins - CHROMA_BINS) * CHROMA_BIN
            val pb = (best / bins - CHROMA_BINS) * CHROMA_BIN
            var sa = 0.0
            var sb = 0.0
            var c = 0
            i = 0
            while (i < n) {
                if (abs(ca[i] - pa) <= 2 * CHROMA_BIN && abs(cb[i] - pb) <= 2 * CHROMA_BIN) { sa += ca[i]; sb += cb[i]; c++ }
                i += 3
            }
            return if (c == 0) pa to pb else (sa / c).toFloat() to (sb / c).toFloat()
        }

        // ── 3: scale-space blobs ────────────────────────────────────────

        private fun scaleSpacePeaks(soft: FloatArray): List<Peak> {
            val out = ArrayList<Peak>()
            val k = 2f.pow(1f / SCALES_PER_OCTAVE)
            val sigmas = FloatArray(SCALES_PER_OCTAVE + 3) { SIGMA0 * k.pow(it) }
            val maxSigma = MAX_RADIUS * w / RADIUS_PER_SIGMA
            var base = soft.copyOf()
            var ow = w
            var oh = h
            var octave = 0
            var tmp = FloatArray(ow * oh)
            Blur.gauss(base, tmp, ow, oh, SIGMA0)
            while (min(ow, oh) >= 12) {
                val scale = (1 shl octave).toFloat()
                if (SIGMA0 * scale > maxSigma) break
                val size = ow * oh
                if (tmp.size < size) tmp = FloatArray(size)
                var prev = base
                var nextBase: FloatArray? = null
                val dogs = arrayOfNulls<FloatArray>(3)
                for (s in 1 until sigmas.size) {
                    val cur = prev.copyOf()
                    Blur.gauss(cur, tmp, ow, oh, sqrt(sigmas[s] * sigmas[s] - sigmas[s - 1] * sigmas[s - 1]))
                    val dog = FloatArray(size)
                    for (i in 0 until size) dog[i] = prev[i] - cur[i]
                    dogs[0] = dogs[1]; dogs[1] = dogs[2]; dogs[2] = dog
                    if (s == SCALES_PER_OCTAVE) nextBase = cur
                    // DoG index s-1 is complete; its middle neighbour (s-2) can be searched.
                    val layer = s - 2
                    if (layer >= 1 && dogs[0] != null) {
                        findPeaks(dogs[0]!!, dogs[1]!!, dogs[2]!!, ow, oh, octave, layer, sigmas, out)
                    }
                    prev = cur
                }
                val nb = nextBase ?: break
                val nw = ow / 2
                val nh = oh / 2
                if (nw < 12 || nh < 12) break
                base = FloatArray(nw * nh)
                for (y in 0 until nh) for (x in 0 until nw) {
                    val i = 2 * y * ow + 2 * x
                    base[y * nw + x] = 0.25f * (nb[i] + nb[i + 1] + nb[i + ow] + nb[i + ow + 1])
                }
                ow = nw; oh = nh; octave++
            }
            return out
        }

        private fun findPeaks(
            below: FloatArray, mid: FloatArray, above: FloatArray,
            ow: Int, oh: Int, octave: Int, layer: Int, sigmas: FloatArray, out: MutableList<Peak>,
        ) {
            val scale = (1 shl octave).toFloat()
            for (y in 1 until oh - 1) {
                for (x in 1 until ow - 1) {
                    val i = y * ow + x
                    val v = mid[i]
                    if (v < PEAK_MIN) continue
                    if (!isMax(v, mid, i, ow, strict = true) || !isMax(v, below, i, ow, strict = false) || !isMax(v, above, i, ow, strict = false)) continue
                    // Lines (panel seams, the wall's edge) have one strong curvature only.
                    val dxx = mid[i + 1] + mid[i - 1] - 2 * v
                    val dyy = mid[i + ow] + mid[i - ow] - 2 * v
                    val dxy = (mid[i + ow + 1] - mid[i + ow - 1] - mid[i - ow + 1] + mid[i - ow - 1]) * 0.25f
                    val tr = dxx + dyy
                    val det = dxx * dyy - dxy * dxy
                    if (det <= 0f || tr * tr / det >= (EDGE_RATIO + 1) * (EDGE_RATIO + 1) / EDGE_RATIO) continue
                    // Sub-pixel position and in-between scale from parabolas.
                    val ox = parabola(mid[i - 1], v, mid[i + 1])
                    val oy = parabola(mid[i - ow], v, mid[i + ow])
                    val os = parabola(below[i], v, above[i])
                    // A DoG between sigma s and s+1 answers best at their geometric mean.
                    val sigma = sigmas[layer] * 2f.pow((os + 0.5f) / SCALES_PER_OCTAVE) * scale
                    val cx = (x + ox) * scale + (scale - 1f) * 0.5f
                    val cy = (y + oy) * scale + (scale - 1f) * 0.5f
                    out += Peak(cx, cy, sigma * RADIUS_PER_SIGMA, v)
                }
            }
        }

        // ── 4 + 5: radius, confidence and holes ─────────────────────────

        private fun score(p: Peak): Scored? {
            val r0 = p.radius
            if (r0 > MAX_RADIUS * w * 1.5f) return null
            // The core: median saliency (a bolt hole in the hold does not pull it down), darkness and own colour.
            val core = max(1f, r0 * 0.35f)
            val step = max(1, (core / 3f).toInt())
            val values = ArrayList<Float>()
            var dark = 0f
            var ownChroma = 0f
            var dy = -core.toInt()
            while (dy <= core) {
                var dx = -core.toInt()
                while (dx <= core) {
                    if (dx * dx + dy * dy <= core * core) {
                        val x = (p.x + dx).roundToInt()
                        val y = (p.y + dy).roundToInt()
                        if (x in 0 until w && y in 0 until h) {
                            val i = y * w + x
                            values += saliency[i]
                            dark -= lumDiff[i]
                            val c = argb[i]
                            val r = (c shr 16 and 0xFF)
                            val g = (c shr 8 and 0xFF)
                            val b = (c and 0xFF)
                            ownChroma += (max(r, max(g, b)) - min(r, min(g, b))).toFloat() / (max(r, max(g, b)) + 24f)
                        }
                    }
                    dx += step
                }
                dy += step
            }
            if (values.isEmpty()) return null
            values.sort()
            val centre = values[values.size / 2]
            if (centre < PANEL_LEVEL) return null
            // Walk out along rays until the saliency falls well below the core.
            val stop = max(PANEL_LEVEL + 0.5f, 0.4f * centre)
            val cap = r0 * 1.8f
            val lengths = FloatArray(RAYS)
            var closed = 0
            for (ray in 0 until RAYS) {
                val angle = ray * (2.0 * kotlin.math.PI / RAYS)
                val ux = kotlin.math.cos(angle).toFloat()
                val uy = kotlin.math.sin(angle).toFloat()
                var t = 0f
                val dt = max(0.5f, r0 * 0.04f)
                var ended = false
                while (t < cap) {
                    val x = (p.x + ux * t).roundToInt()
                    val y = (p.y + uy * t).roundToInt()
                    if (x !in 0 until w || y !in 0 until h) break
                    if (saliency[y * w + x] < stop) { ended = true; break }
                    t += dt
                }
                if (ended) closed++
                lengths[ray] = t
            }
            lengths.sort()
            val rayRadius = (lengths[RAYS / 2 - 1] + lengths[RAYS / 2]) * 0.5f
            val radius = sqrt(r0 * rayRadius.coerceIn(0.5f * r0, 1.6f * r0))
            if (radius < MIN_RADIUS * w) return null
            val closedShare = closed.toFloat() / RAYS

            val strength = p.response / DISC_GAIN
            var confidence = 1f - exp(-strength / 2.2f)
            confidence *= 0.65f + 0.35f * closedShare
            if (radius < 0.006f * w) confidence *= 0.8f
            val n = values.size
            val holeLike = radius < HOLE_MAX_RADIUS * w && dark / n / lumNoise > 3f && ownChroma / n < 0.18f
            return Scored(p.x, p.y, radius, confidence, holeLike)
        }

        // ── Regions: touching holds split at their neck ─────────────────

        /**
         * Hold regions from the saliency mask: every local maximum of the
         * distance to the panel is the middle of a hold, and its value the
         * radius of the largest circle inside it. Two touching holds have
         * two such maxima with a saddle at the contact.
         */
        private fun regions(): List<Scored> {
            val mask = BooleanArray(n) { saliency[it] >= MASK_LEVEL }
            fillGaps(mask)
            val d = FloatArray(n)
            val far = 1e6f
            for (i in 0 until n) d[i] = if (mask[i]) far else 0f
            val diag = 1.4142135f
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (d[i] == 0f) continue
                var v = d[i]
                v = min(v, if (x > 0) d[i - 1] + 1f else 1f)
                v = min(v, if (y > 0) d[i - w] + 1f else 1f)
                v = min(v, if (x > 0 && y > 0) d[i - w - 1] + diag else diag)
                v = min(v, if (x < w - 1 && y > 0) d[i - w + 1] + diag else diag)
                d[i] = v
            }
            for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (d[i] == 0f) continue
                var v = d[i]
                v = min(v, if (x < w - 1) d[i + 1] + 1f else 1f)
                v = min(v, if (y < h - 1) d[i + w] + 1f else 1f)
                v = min(v, if (x < w - 1 && y < h - 1) d[i + w + 1] + diag else diag)
                v = min(v, if (x > 0 && y < h - 1) d[i + w - 1] + diag else diag)
                d[i] = v
            }
            val minR = MIN_RADIUS * w * 0.8f
            val maxima = ArrayList<IntArray>()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val v = d[i]
                if (v < minR) continue
                // Ties on a plateau go to the first pixel in reading order.
                if (d[i - w - 1] >= v || d[i - w] >= v || d[i - w + 1] >= v || d[i - 1] >= v) continue
                if (d[i + 1] > v || d[i + w - 1] > v || d[i + w] > v || d[i + w + 1] > v) continue
                maxima += intArrayOf(x, y)
            }
            maxima.sortByDescending { d[it[1] * w + it[0]] }
            val kept = ArrayList<IntArray>()
            for (m in maxima) {
                val v = d[m[1] * w + m[0]]
                val clash = kept.any { k ->
                    val dx = (k[0] - m[0]).toFloat()
                    val dy = (k[1] - m[1]).toFloat()
                    dx * dx + dy * dy < (0.9f * d[k[1] * w + k[0]]).let { it * it }
                }
                if (!clash) kept += m
            }
            return kept.mapNotNull { m -> regionCandidate(m[0], m[1], d[m[1] * w + m[0]], mask, d) }
        }

        private fun regionCandidate(cx: Int, cy: Int, inscribed: Float, mask: BooleanArray, distance: FloatArray): Scored? {
            val lengths = FloatArray(RAYS)
            val cap = inscribed * 2.2f
            for (ray in 0 until RAYS) {
                val angle = ray * (2.0 * kotlin.math.PI / RAYS)
                val ux = kotlin.math.cos(angle).toFloat()
                val uy = kotlin.math.sin(angle).toFloat()
                var t = 0f
                var lowest = inscribed
                while (t < cap) {
                    val x = (cx + ux * t).roundToInt()
                    val y = (cy + uy * t).roundToInt()
                    if (x !in 0 until w || y !in 0 until h || !mask[y * w + x]) break
                    // Past a saddle the distance grows again: that is the next hold.
                    val v = distance[y * w + x]
                    if (v > lowest + 1.5f) break
                    lowest = min(lowest, v)
                    t += 0.5f
                }
                lengths[ray] = t
            }
            lengths.sort()
            val radius = lengths[(RAYS * 2) / 5].coerceIn(inscribed, inscribed * 1.6f)
            if (radius < MIN_RADIUS * w) return null
            // Contrast in the inner circle.
            val core = max(1f, inscribed * 0.7f)
            val step = max(1, (core / 4f).toInt())
            val values = ArrayList<Float>()
            var dark = 0f
            var ownChroma = 0f
            var dy = -core.toInt()
            while (dy <= core) {
                var dx = -core.toInt()
                while (dx <= core) {
                    if (dx * dx + dy * dy <= core * core) {
                        val x = cx + dx
                        val y = cy + dy
                        if (x in 0 until w && y in 0 until h) {
                            val i = y * w + x
                            values += saliency[i]
                            dark -= lumDiff[i]
                            val c = argb[i]
                            val r = (c shr 16 and 0xFF)
                            val g = (c shr 8 and 0xFF)
                            val b = (c and 0xFF)
                            ownChroma += (max(r, max(g, b)) - min(r, min(g, b))).toFloat() / (max(r, max(g, b)) + 24f)
                        }
                    }
                    dx += step
                }
                dy += step
            }
            if (values.isEmpty()) return null
            values.sort()
            val contrast = values[values.size / 2] - PANEL_LEVEL
            var confidence = 1f - exp(-contrast / 6f)
            // A compact region is more likely one hold than a smear.
            confidence *= (0.55f + 0.45f * (inscribed / radius))
            if (radius < 0.006f * w) confidence *= 0.8f
            val count = values.size
            val holeLike = radius < HOLE_MAX_RADIUS * w && dark / count / lumNoise > 3f && ownChroma / count < 0.18f
            return Scored(cx.toFloat(), cy.toFloat(), radius, confidence, holeLike)
        }

        /** Fills enclosed gaps (bolt holes, chalk, highlights) smaller than a small hold. */
        private fun fillGaps(mask: BooleanArray) {
            val limit = (kotlin.math.PI * (GAP_RADIUS * w) * (GAP_RADIUS * w)).toInt()
            val seen = BooleanArray(n)
            val queue = IntArray(n)
            for (start in 0 until n) {
                if (mask[start] || seen[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                seen[start] = true
                var border = false
                while (head < tail) {
                    val i = queue[head++]
                    val x = i % w
                    val y = i / w
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                    if (x > 0 && !mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
                    if (x < w - 1 && !mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
                    if (y > 0 && !mask[i - w] && !seen[i - w]) { seen[i - w] = true; queue[tail++] = i - w }
                    if (y < h - 1 && !mask[i + w] && !seen[i + w]) { seen[i + w] = true; queue[tail++] = i + w }
                }
                if (!border && tail <= limit) for (k in 0 until tail) mask[queue[k]] = true
            }
        }

        /**
         * Regions first; a blob joins only where no region already sits. A
         * region that a blob of about its size confirms keeps its confidence,
         * one without loses some.
         */
        private fun union(regions: List<Scored>, blobs: List<Scored>): List<Scored> {
            val out = ArrayList<Scored>(regions.size + blobs.size)
            for (r in regions) {
                val confirmed = blobs.any { b -> dist(r, b) < 0.5f * r.radius && b.radius / r.radius in 0.6f..1.6f }
                out += if (confirmed) r else Scored(r.x, r.y, r.radius, r.confidence * 0.8f, r.holeLike)
            }
            for (b in blobs) {
                val covered = regions.any { r ->
                    val d = dist(r, b)
                    d < 1.15f * r.radius || d < b.radius
                }
                if (!covered) out += Scored(b.x, b.y, b.radius, b.confidence * 0.9f, b.holeLike)
            }
            return out.sortedByDescending { it.confidence }
        }

        /**
         * T-nut holes: dark, grey, all of one size and on a regular grid.
         * When enough hole-like blobs share a size and a spacing, they are
         * the bolt holes and go; otherwise hole-like blobs only lose
         * confidence (a small black foot chip looks the same up close).
         */
        private fun dropTNutHoles(all: List<Scored>): List<Scored> {
            val holes = all.filter { it.holeLike }
            val dimmed = { all.map { if (it.holeLike) it.dimmed() else it } }
            if (holes.size < 8) return dimmed()
            val radii = holes.map { it.radius }.sorted()
            val typical = radii[radii.size / 2]
            val sameSize = holes.filter { abs(it.radius - typical) < 0.35f * typical }
            if (sameSize.size < max(8, holes.size / 2)) return dimmed()
            // A grid: most nearest-neighbour distances agree.
            val nearest = sameSize.map { a -> sameSize.filter { it !== a }.minOf { dist(a, it) } }.sorted()
            val spacing = nearest[nearest.size / 2]
            val regular = nearest.count { abs(it - spacing) < 0.2f * spacing }
            if (spacing < 4f * typical || regular < nearest.size / 2) return dimmed()
            return all.mapNotNull {
                when {
                    !it.holeLike -> it
                    it.radius < typical * 1.45f -> null
                    else -> it.dimmed()
                }
            }
        }

        private fun Scored.dimmed() = Scored(x, y, radius, confidence * 0.85f, holeLike)

        /**
         * One suggestion per hold:
         * - A big blob over two touching holds (two separate smaller blobs
         *   inside it, joined by a narrow neck) is dropped for the two.
         * - A small blob that lies inside a bigger one (a bump on its rim, a
         *   bolt hole) is dropped for the bigger one.
         * - Of two similar blobs whose centres lie inside each other, the
         *   stronger stays.
         */
        private fun suppress(all: List<Scored>): List<Scored> {
            val bySize = all.sortedByDescending { it.radius }
            val merged = BooleanArray(bySize.size)
            for (m in bySize.indices) {
                val big = bySize[m]
                val inside = bySize.filter {
                    it !== big && it.confidence >= 0.4f && it.radius in (0.25f * big.radius)..(0.85f * big.radius) &&
                        dist(it, big) < big.radius
                }
                search@ for (i in inside.indices) for (j in i + 1 until inside.size) {
                    val a = inside[i]
                    val c = inside[j]
                    if (dist(a, c) < 0.8f * (a.radius + c.radius)) continue
                    if (neck(a, c) < 1.2f * min(a.radius, c.radius)) { merged[m] = true; break@search }
                }
            }
            val kept = ArrayList<Scored>()
            for (m in bySize.indices) {
                if (merged[m]) continue
                val c = bySize[m]
                var drop = false
                var replace = -1
                for ((k, other) in kept.withIndex()) {
                    val d = dist(c, other)
                    if (other.radius >= 1.6f * c.radius && d < 1.05f * other.radius) { drop = true; break }
                    if (d < 0.72f * max(other.radius, c.radius)) {
                        if (c.confidence > other.confidence) replace = k else drop = true
                        break
                    }
                }
                when {
                    drop -> Unit
                    replace >= 0 -> kept[replace] = c
                    else -> kept += c
                }
            }
            return kept.sortedByDescending { it.confidence }
        }

        private fun dist(a: Scored, b: Scored): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return sqrt(dx * dx + dy * dy)
        }

        /** Salient width across the line between two blobs, halfway between them. */
        private fun neck(a: Scored, b: Scored): Float {
            val mx = (a.x + b.x) * 0.5f
            val my = (a.y + b.y) * 0.5f
            val d = dist(a, b).coerceAtLeast(1e-3f)
            val px = -(b.y - a.y) / d
            val py = (b.x - a.x) / d
            val limit = max(a.radius, b.radius) * 2f
            var width = 0f
            for (sign in intArrayOf(-1, 1)) {
                var t = 0f
                while (t < limit) {
                    val x = (mx + sign * px * t).roundToInt()
                    val y = (my + sign * py * t).roundToInt()
                    if (x !in 0 until w || y !in 0 until h || saliency[y * w + x] < PANEL_LEVEL + 0.5f) break
                    t += 0.75f
                }
                width += t
            }
            return width
        }

        private fun isMax(v: Float, a: FloatArray, i: Int, ow: Int, strict: Boolean): Boolean {
            if (strict) {
                return a[i - ow - 1] < v && a[i - ow] < v && a[i - ow + 1] < v && a[i - 1] < v &&
                    a[i + 1] < v && a[i + ow - 1] < v && a[i + ow] < v && a[i + ow + 1] < v
            }
            return a[i - ow - 1] <= v && a[i - ow] <= v && a[i - ow + 1] <= v && a[i - 1] <= v && a[i] <= v &&
                a[i + 1] <= v && a[i + ow - 1] <= v && a[i + ow] <= v && a[i + ow + 1] <= v
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private const val CHROMA_BIN = 0.02f
    private const val CHROMA_BINS = 60
    private const val PANEL_CHROMA_TOLERANCE = 0.09f
    private const val HOLE_MAX_RADIUS = 0.011f

    /**
     * Raw scores of clear holds sit above 0.9, doubtful ones between 0.5 and
     * 0.7. Stretched so that the editor's default threshold of 0.5 keeps the
     * clear ones and the slider reaches the doubtful ones.
     */
    private fun calibrated(raw: Float): Float = ((raw - 0.45f) / 0.5f).coerceIn(0f, 1f)

    private fun parabola(a: Float, b: Float, c: Float): Float {
        val d = a - 2 * b + c
        return if (d >= 0f) 0f else (0.5f * (a - c) / d).coerceIn(-0.5f, 0.5f)
    }

    /** The centre of the tightest window holding 30 % of the values: a mode that ignores holds covering up to half a cell. */
    private fun densestValue(buf: FloatArray, count: Int): Float {
        val v = buf.copyOf(count)
        v.sort()
        val k = max(3, (count * 0.3f).toInt()).coerceAtMost(count)
        var best = 0
        var width = Float.MAX_VALUE
        for (i in 0..count - k) {
            val d = v[i + k - 1] - v[i]
            if (d < width) { width = d; best = i }
        }
        return v[best + k / 2]
    }

    /** Robust spread around the mode: the half-width of the tightest window holding half of the values, as a standard deviation. */
    private fun spreadOfDensestHalf(values: FloatArray): Float {
        val step = max(1, values.size / 60_000)
        val v = FloatArray((values.size + step - 1) / step) { values[it * step] }
        v.sort()
        val k = v.size / 2
        var width = Float.MAX_VALUE
        for (i in 0..v.size - k) width = min(width, v[i + k - 1] - v[i])
        return width / 2f / 0.674f
    }

    private fun percentile(values: FloatArray, q: Float): Float {
        val step = max(1, values.size / 60_000)
        val v = FloatArray((values.size + step - 1) / step) { values[it * step] }
        v.sort()
        return v[((v.size - 1) * q).toInt()]
    }

    /** Fills cells without a panel estimate from their neighbours, then smooths the grid once. */
    private fun fillAndSmooth(grid: FloatArray, gx: Int, gy: Int, fallback: Float) {
        if (grid.all { it.isNaN() }) { grid.fill(fallback); return }
        var missing = grid.count { it.isNaN() }
        while (missing > 0) {
            val copy = grid.copyOf()
            for (y in 0 until gy) for (x in 0 until gx) {
                val i = y * gx + x
                if (!copy[i].isNaN()) continue
                var s = 0f
                var c = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx !in 0 until gx || yy !in 0 until gy) continue
                    val v = copy[yy * gx + xx]
                    if (!v.isNaN()) { s += v; c++ }
                }
                if (c > 0) { grid[i] = s / c; missing-- }
            }
        }
        val copy = grid.copyOf()
        for (y in 0 until gy) for (x in 0 until gx) {
            var s = 0f
            var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx !in 0 until gx || yy !in 0 until gy) continue
                // The cell itself counts double.
                val wgt = if (dx == 0 && dy == 0) 2 else 1
                s += copy[yy * gx + xx] * wgt
                c += wgt
            }
            grid[y * gx + x] = s / c
        }
    }

    private fun bilinear(grid: FloatArray, gx: Int, gy: Int, fx: Float, fy: Float): Float {
        val x0 = fx.toInt().coerceIn(0, gx - 1)
        val y0 = fy.toInt().coerceIn(0, gy - 1)
        val x1 = min(x0 + 1, gx - 1)
        val y1 = min(y0 + 1, gy - 1)
        val tx = fx - x0
        val ty = fy - y0
        val top = grid[y0 * gx + x0] * (1 - tx) + grid[y0 * gx + x1] * tx
        val bottom = grid[y1 * gx + x0] * (1 - tx) + grid[y1 * gx + x1] * tx
        return top * (1 - ty) + bottom * ty
    }
}

/** Gaussian blur approximated by three box blurs (constant cost per pixel for any sigma). */
internal object Blur {
    /** Blurs [data] in place; [tmp] must hold at least w*h values. */
    fun gauss(data: FloatArray, tmp: FloatArray, w: Int, h: Int, sigma: Float) {
        if (sigma < 0.3f) return
        for (r in boxRadii(sigma)) {
            if (r <= 0) continue
            horizontal(data, tmp, w, h, r)
            vertical(tmp, data, w, h, r)
        }
    }

    fun boxRadii(sigma: Float): IntArray {
        val n = 3
        val ideal = sqrt(12f * sigma * sigma / n + 1f)
        var wl = ideal.toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val m = ((12f * sigma * sigma - n * wl * wl - 4f * n * wl - 3f * n) / (-4f * wl - 4f)).roundToInt()
        return IntArray(n) { ((if (it < m) wl else wu) - 1) / 2 }
    }

    private fun horizontal(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        val last = w - 1
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (k in -r..r) acc += src[row + k.coerceIn(0, last)]
            for (x in 0 until w) {
                dst[row + x] = acc * norm
                acc += src[row + min(x + r + 1, last)] - src[row + max(x - r, 0)]
            }
        }
    }

    private fun vertical(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        val last = h - 1
        val acc = FloatArray(w)
        for (k in -r..r) {
            val row = k.coerceIn(0, last) * w
            for (x in 0 until w) acc[x] += src[row + x]
        }
        for (y in 0 until h) {
            val row = y * w
            val add = min(y + r + 1, last) * w
            val sub = max(y - r, 0) * w
            for (x in 0 until w) {
                dst[row + x] = acc[x] * norm
                acc[x] += src[add + x] - src[sub + x]
            }
        }
    }
}
