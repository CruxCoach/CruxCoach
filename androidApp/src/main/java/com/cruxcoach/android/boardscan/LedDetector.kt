package com.cruxcoach.android.boardscan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One lit LED as seen in a frame: centre in pixels, mean hue (degrees), mean chroma, area in pixels and the
 * brightest value inside it.
 */
class Blob(
    val x: Double,
    val y: Double,
    val hue: Double,
    val chroma: Double,
    val area: Int,
    val peak: Int = 255,
)

class DetectorParams(
    /** How much more colourful than its surroundings a pixel must be. */
    val excess: Int = 40,
    val minChroma: Int = 45,
    val minValue: Int = 90,
    /** Side of the box the surroundings are averaged over, in pixels. */
    val window: Int = 21,
    val minArea: Int = 3,
    val maxAreaFrac: Double = 0.003,
    /** A blob's ring must be at most this colourful relative to the blob (a spot, not part of a coloured area). */
    val ringRatio: Double = 0.45,
)

/**
 * Finds lit LEDs: pixels clearly more colourful than their surroundings (a lit rim or LED dot on a grey or dark
 * board), not merely colourful (painted walls, mats, coloured holds), grouped into 8-connected blobs.
 */
object LedDetector {
    fun detect(argb: IntArray, w: Int, h: Int, p: DetectorParams = DetectorParams()): List<Blob> {
        val n = w * h
        val chroma = IntArray(n)
        val value = IntArray(n)
        val hue = FloatArray(n)
        for (i in 0 until n) {
            val c = argb[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val mx = max(r, max(g, b))
            chroma[i] = mx - min(r, min(g, b))
            value[i] = mx
        }
        // integral image of chroma for the local mean (window clamped at the borders)
        val integ = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += chroma[y * w + x]
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row
            }
        }
        val half = p.window / 2
        val mask = BooleanArray(n)
        for (y in 0 until h) {
            val y0 = max(0, y - half)
            val y1 = min(h, y + half + 1)
            for (x in 0 until w) {
                val i = y * w + x
                val ch = chroma[i]
                if (ch < p.minChroma || value[i] < p.minValue) continue
                val x0 = max(0, x - half)
                val x1 = min(w, x + half + 1)
                val s = integ[y1 * (w + 1) + x1] - integ[y0 * (w + 1) + x1] - integ[y1 * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
                val mean = s.toDouble() / ((x1 - x0) * (y1 - y0))
                if (ch - mean >= p.excess) {
                    mask[i] = true
                    val c = argb[i]
                    hue[i] = hueOf((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff).toFloat()
                }
            }
        }
        // union-find labelling
        val label = IntArray(n)
        var par = IntArray(n / 4 + 16)
        var next = 1
        fun find(a0: Int): Int {
            var a = a0
            while (par[a] != a) {
                par[a] = par[par[a]]
                a = par[a]
            }
            return a
        }
        val cand = IntArray(4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!mask[i]) continue
                cand[0] = if (x > 0) label[i - 1] else 0
                cand[1] = if (y > 0 && x > 0) label[i - w - 1] else 0
                cand[2] = if (y > 0) label[i - w] else 0
                cand[3] = if (y > 0 && x < w - 1) label[i - w + 1] else 0
                var root = 0
                for (l in cand) {
                    if (l == 0) continue
                    val r = find(l)
                    if (root == 0) {
                        root = r
                    } else if (r != root) {
                        if (r < root) {
                            par[root] = r
                            root = r
                        } else {
                            par[r] = root
                        }
                    }
                }
                if (root == 0) {
                    if (next >= par.size) par = par.copyOf(par.size * 2)
                    par[next] = next
                    root = next++
                }
                label[i] = root
            }
        }
        val cnt = IntArray(next)
        val sw = DoubleArray(next)
        val sx = DoubleArray(next)
        val sy = DoubleArray(next)
        val hc = DoubleArray(next)
        val hs = DoubleArray(next)
        val pk = IntArray(next)
        for (i in 0 until n) {
            if (!mask[i]) continue
            val r = find(label[i])
            val wgt = chroma[i].toDouble()
            if (value[i] > pk[r]) pk[r] = value[i]
            cnt[r]++
            sw[r] += wgt
            sx[r] += wgt * (i % w)
            sy[r] += wgt * (i / w)
            val a = hue[i] * PI / 180.0
            hc[r] += wgt * cos(a)
            hs[r] += wgt * sin(a)
        }
        val maxArea = p.maxAreaFrac * n
        val raw = ArrayList<Blob>()
        for (r in 1 until next) {
            if (cnt[r] < p.minArea || cnt[r] > maxArea) continue
            val hh = (atan2(hs[r], hc[r]) * 180.0 / PI + 360.0) % 360.0
            raw.add(Blob(sx[r] / sw[r], sy[r] / sw[r], hh, sw[r] / cnt[r], cnt[r], pk[r]))
        }
        // ring contrast: an LED is a colourful spot on a grey or dark board, not part of a colourful area
        val ring = IntArray(24)
        val kept = raw.filter { b ->
            val rad = max(3.0, 2.2 * sqrt(b.area / PI))
            for (k in 0 until 24) {
                val a = 2 * PI * k / 24
                val xx = (b.x + rad * cos(a)).toInt().coerceIn(0, w - 1)
                val yy = (b.y + rad * sin(a)).toInt().coerceIn(0, h - 1)
                ring[k] = chroma[yy * w + xx]
            }
            ring.sort()
            (ring[11] + ring[12]) / 2.0 < p.ringRatio * b.chroma
        }
        return mergeClose(kept)
    }

    /** Merges same-colour fragments of one LED (a lit rim can break into arcs). */
    private fun mergeClose(blobs: List<Blob>): List<Blob> {
        val order = blobs.indices.sortedByDescending { blobs[it].area }
        val used = BooleanArray(blobs.size)
        val out = ArrayList<Blob>()
        for (i in order) {
            if (used[i]) continue
            used[i] = true
            val grp = arrayListOf(blobs[i])
            val ri = sqrt(blobs[i].area / PI)
            for (j in order) {
                if (used[j]) continue
                val rj = sqrt(blobs[j].area / PI)
                val d = hypot(blobs[i].x - blobs[j].x, blobs[i].y - blobs[j].y)
                if (d < 1.6 * (ri + rj) + 2 && hueDist(blobs[i].hue, blobs[j].hue) < 30) {
                    grp.add(blobs[j])
                    used[j] = true
                }
            }
            var a = 0.0
            var x = 0.0
            var y = 0.0
            var c = 0.0
            var hc = 0.0
            var hs = 0.0
            var peak = 0
            for (g in grp) {
                peak = max(peak, g.peak)
                a += g.area
                x += g.x * g.area
                y += g.y * g.area
                c += g.chroma * g.area
                hc += cos(g.hue * PI / 180) * g.area
                hs += sin(g.hue * PI / 180) * g.area
            }
            out.add(Blob(x / a, y / a, (atan2(hs, hc) * 180 / PI + 360) % 360, c / a, a.toInt(), peak))
        }
        return out
    }

    fun hueOf(r: Int, g: Int, b: Int): Double {
        val mx = max(r, max(g, b))
        val c = (mx - min(r, min(g, b))).toDouble() + 1e-6
        val h = when (mx) {
            r -> ((g - b) / c).mod(6.0)
            g -> (b - r) / c + 2
            else -> (r - g) / c + 4
        }
        return (h * 60.0 + 360.0) % 360.0
    }
}

/** Distance between two hues in degrees (0..180). */
fun hueDist(a: Double, b: Double): Double = abs(((a - b + 180.0).mod(360.0)) - 180.0)
