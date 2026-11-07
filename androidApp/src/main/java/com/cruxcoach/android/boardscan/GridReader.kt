package com.cruxcoach.android.boardscan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the board itself before looking at the catalogue: the lit LEDs and the board's own coloured holds all sit
 * on the hole grid, so the grid's scale and phase come from all of them, and the coloured holds (a fixed, known
 * pattern) say which column and row is which. Then the lit holes are read off directly and looked up in the
 * catalogue. On a regular grid this avoids matching a problem one column or row off.
 */
class GridReader(private val ix: ClimbIndex, private val marks: List<StaticMark>, private val staticHue: Double) {
    private val x0: Double
    private val y0: Double
    private val sx: Double
    private val sy: Double
    private val nCols: Int
    private val nRows: Int
    /** Hole index per grid cell (-1 = none). */
    private val holeAt: IntArray
    /** Mark index per grid cell (-1 = none). */
    private val markAt: IntArray
    /** Hole -> climbs containing it (CSR), with the hold's role. */
    private val invOff: IntArray
    private val invClimb: IntArray
    private val invRole: ByteArray
    val usable: Boolean

    init {
        val xs = ix.holeX.map { it.toDouble() }.sorted()
        val ys = ix.holeY.map { it.toDouble() }.sorted()
        fun step(v: List<Double>): Double {
            val d = (1 until v.size).map { v[it] - v[it - 1] }.filter { it > 1e-3 * (v.last() - v.first()) }.sorted()
            return if (d.isEmpty()) 0.0 else d[d.size / 2]
        }
        sx = step(xs); sy = step(ys)
        x0 = xs.first(); y0 = ys.first()
        nCols = if (sx > 0) (round((xs.last() - x0) / sx).toInt() + 1) else 0
        nRows = if (sy > 0) (round((ys.last() - y0) / sy).toInt() + 1) else 0
        holeAt = IntArray(max(0, nCols * nRows)) { -1 }
        var onGrid = 0
        for (h in ix.holeX.indices) {
            val c = round((ix.holeX[h] - x0) / sx).toInt()
            val r = round((ix.holeY[h] - y0) / sy).toInt()
            if (c in 0 until nCols && r in 0 until nRows &&
                abs(ix.holeX[h] - (x0 + c * sx)) < 0.2 * sx && abs(ix.holeY[h] - (y0 + r * sy)) < 0.2 * sy) {
                holeAt[r * nCols + c] = h; onGrid++
            }
        }
        markAt = IntArray(holeAt.size) { -1 }
        for ((k, m) in marks.withIndex()) {
            val c = round((m.x - x0) / sx).toInt()
            val r = round((m.y - y0) / sy).toInt()
            if (c in 0 until nCols && r in 0 until nRows) markAt[r * nCols + c] = k
        }
        // a regular grid: (almost) every hole on a grid point, and enough coloured holds to pin it
        usable = marks.size >= 8 && nCols >= 3 && nRows >= 3 && onGrid >= 0.95 * ix.holeX.size
        val cnt = IntArray(ix.holeX.size + 1)
        for (s in ix.holes.indices) cnt[ix.holes[s].toInt() + 1]++
        for (h in 1 until cnt.size) cnt[h] += cnt[h - 1]
        invOff = cnt.copyOf()
        invClimb = IntArray(ix.holes.size)
        invRole = ByteArray(ix.holes.size)
        val fill = cnt.copyOf()
        for (c in 0 until ix.size) {
            for (s in ix.offsets[c] until ix.offsets[c + 1]) {
                val h = ix.holes[s].toInt()
                invClimb[fill[h]] = c; invRole[fill[h]] = ix.roles[s]; fill[h]++
            }
        }
    }

    private class Lattice(val rect: RectPair, val theta: Double, val s: Double, val ox: Double, val oy: Double, val coherence: Double)

    /**
     * Grid scale, rotation and phase in the rectified view: the rectification, rotation and scale under which the
     * points line up best with a grid of the board's column and row spacing (phase coherence).
     */
    private fun fitLattice(px: DoubleArray, py: DoubleArray, rects: List<RectPair>): Lattice? {
        val n = px.size
        if (n < 6) return null
        val rx = DoubleArray(n)
        val ry = DoubleArray(n)
        val t = DoubleArray(2)
        val found = ArrayList<Lattice>()
        for (rp in rects) {
            var ok = true
            for (i in 0 until n) {
                if (rp.fwd.m[6] * px[i] + rp.fwd.m[7] * py[i] + rp.fwd.m[8] <= 0) { ok = false; break }
                rp.fwd.apply(px[i], py[i], t); rx[i] = t[0]; ry[i] = t[1]
            }
            if (!ok) continue
            // scale guess from neighbour distances: neighbouring lit or coloured holds are one grid step apart
            val nn = DoubleArray(n)
            for (i in 0 until n) {
                var d = Double.MAX_VALUE
                for (j in 0 until n) if (j != i) d = min(d, hypot(rx[i] - rx[j], ry[i] - ry[j]))
                nn[i] = d
            }
            nn.sort()
            val s0 = nn[n / 4] / min(sx, sy)
            // split or extra blobs can make neighbours look closer than one grid step, so search well above the
            // guess; a grid at half or a third of the true step lines up just as well, so the coarsest good one wins
            var th = -6.0
            while (th <= 6.0) {
                val ct = cos(th * PI / 180); val st = sin(th * PI / 180)
                var s = 0.7 * s0
                while (s <= 3.0 * s0) {
                    var cxr = 0.0; var cxi = 0.0; var cyr = 0.0; var cyi = 0.0
                    for (i in 0 until n) {
                        val qx = ct * rx[i] + st * ry[i]
                        val qy = -st * rx[i] + ct * ry[i]
                        val u = 2 * PI * qx / (s * sx)
                        val v = 2 * PI * qy / (s * sy)
                        cxr += cos(u); cxi += sin(u); cyr += cos(v); cyi += sin(v)
                    }
                    val coh = (hypot(cxr, cxi) + hypot(cyr, cyi)) / (2.0 * n)
                    if (coh >= 0.4) {
                        val ox = atan2(cxi, cxr) / (2 * PI) * s * sx
                        val oy = atan2(cyi, cyr) / (2 * PI) * s * sy
                        found.add(Lattice(rp, th, s, ox, oy, coh))
                    }
                    s *= 1.02
                }
                th += 3.0
            }
        }
        if (found.isEmpty()) return null
        val top = found.maxOf { it.coherence }
        val good = found.filter { it.coherence >= 0.85 * top }
        val sMax = good.maxOf { it.s }
        return good.filter { it.s >= sMax / 1.05 }.maxByOrNull { it.coherence }
    }

    /**
     * Candidates from reading the board directly, or empty when the board cannot be pinned down. [poseFound]: the
     * board's position is known from the grid and its coloured holds; [cands] may still be empty. [focus]: the problem
     * asked for in [read], scored on its own (null when the board was not pinned down or too few holes were lit).
     */
    class Result(val cands: List<Candidate>, val poseFound: Boolean, val focus: Candidate? = null)

    fun read(
        blobs: List<Blob>, roles: IntArray, pal: Palette, rects: List<RectPair>, frameW: Int, frameH: Int, focus: Int = -1,
    ): Result {
        val none = Result(emptyList(), false)
        if (!usable) return none
        val use = blobs.indices.filter { roles[it] >= 0 || hueDist(blobs[it].hue, staticHue) < 25 }
        if (use.size < 6) return none
        val px = DoubleArray(use.size) { blobs[use[it]].x }
        val py = DoubleArray(use.size) { blobs[use[it]].y }
        val lat = fitLattice(px, py, rects) ?: return none
        if (lat.coherence < 0.5) return none
        // grid cell (i, j) per point in the lattice's own numbering
        val ct = cos(lat.theta * PI / 180); val st = sin(lat.theta * PI / 180)
        val t = DoubleArray(2)
        val ci = IntArray(use.size); val cj = IntArray(use.size); val on = BooleanArray(use.size)
        for (q in use.indices) {
            lat.rect.fwd.apply(px[q], py[q], t)
            val qx = ct * t[0] + st * t[1] - lat.ox
            val qy = -st * t[0] + ct * t[1] - lat.oy
            val u = qx / (lat.s * sx); val v = qy / (lat.s * sy)
            ci[q] = round(u).toInt(); cj[q] = round(v).toInt()
            on[q] = abs(u - ci[q]) < 0.3 && abs(v - cj[q]) < 0.3
        }
        // which column and row: the shift that puts the most coloured-hold blobs on the board's coloured holds
        val stat = use.indices.filter { on[it] && hueDist(blobs[use[it]].hue, staticHue) < 25 }
        if (stat.size < 5) return none
        val iMin = use.indices.filter { on[it] }.minOf { ci[it] }; val iMax = use.indices.filter { on[it] }.maxOf { ci[it] }
        val jMin = use.indices.filter { on[it] }.minOf { cj[it] }; val jMax = use.indices.filter { on[it] }.maxOf { cj[it] }
        var bestSup = -1e9; var second = -1e9; var bdc = 0; var bdr = 0
        for (dc in min(iMin, iMax - nCols + 1) - 1..max(iMin, iMax - nCols + 1) + 1) for (dr in min(jMin, jMax - nRows + 1) - 1..max(jMin, jMax - nRows + 1) + 1) {
            var sup = 0.0
            for (q in stat) {
                val c = ci[q] - dc; val r = cj[q] - dr
                if (c !in 0 until nCols || r !in 0 until nRows) { sup -= 1.0; continue }
                val k = markAt[r * nCols + c]
                if (k >= 0 && hueDist(marks[k].hue, blobs[use[q]].hue) < 25) sup += 1.0 else sup -= 0.5
            }
            for (q in use.indices) {
                if (!on[q] || roles[use[q]] < 0) continue
                val c = ci[q] - dc; val r = cj[q] - dr
                if (c !in 0 until nCols || r !in 0 until nRows) sup -= 1.0
            }
            if (sup > bestSup) { second = bestSup; bestSup = sup; bdc = dc; bdr = dr } else if (sup > second) second = sup
        }
        if (bestSup < 5 || bestSup < second + 3) return none
        // board -> rectified: q = s * (X - x0, Y - y0) + (ox + dc s sx, oy + dr s sy), rotated back by theta
        val s = lat.s
        val tx = lat.ox + bdc * s * sx - s * x0
        val ty = lat.oy + bdr * s * sy - s * y0
        val aff = Hom(doubleArrayOf(ct * s, -st * s, ct * tx - st * ty, st * s, ct * s, st * tx + ct * ty, 0.0, 0.0, 1.0))
        var hm = lat.rect.back * aff
        // refine on every point that sits on its cell: coloured holds on their marks, LEDs on their holes
        val src = ArrayList<Double>(); val dst = ArrayList<Double>()
        for (q in use.indices) {
            if (!on[q]) continue
            val c = ci[q] - bdc; val r = cj[q] - bdr
            if (c !in 0 until nCols || r !in 0 until nRows) continue
            val cell = r * nCols + c
            val b = blobs[use[q]]
            val k = markAt[cell]
            if (hueDist(b.hue, staticHue) < 25 && k >= 0) { src.add(marks[k].x); src.add(marks[k].y) }
            else if (roles[use[q]] >= 0 && holeAt[cell] >= 0) { src.add(ix.holeX[holeAt[cell]].toDouble()); src.add(ix.holeY[holeAt[cell]].toDouble()) }
            else continue
            dst.add(b.x); dst.add(b.y)
        }
        if (src.size >= 16) Hom.fit(src.toDoubleArray(), dst.toDoubleArray(), src.size / 2)?.let { hm = it }
        val inv = hm.inverse()
        // brightness that separates a lit LED from paint where the two share a colour (blue holds, blue LEDs)
        val paint = ArrayList<Int>()
        val led = ArrayList<Int>()
        for (b in blobs.indices) {
            val w = inv.m[6] * blobs[b].x + inv.m[7] * blobs[b].y + inv.m[8]
            if (w <= 0) continue
            inv.apply(blobs[b].x, blobs[b].y, t)
            val c = round((t[0] - x0) / sx).toInt(); val r = round((t[1] - y0) / sy).toInt()
            if (c !in 0 until nCols || r !in 0 until nRows) continue
            val k = markAt[r * nCols + c]
            if (k >= 0 && hueDist(blobs[b].hue, marks[k].hue) < 25) paint.add(blobs[b].peak) else if (roles[b] >= 0) led.add(blobs[b].peak)
        }
        paint.sort(); led.sort()
        val paintPeak = if (paint.isEmpty()) 0 else paint[paint.size / 2]
        val ledPeak = if (led.isEmpty()) 255 else led[led.size / 2]
        val litThr = if (ledPeak > paintPeak) (paintPeak + ledPeak) / 2.0 else 256.0
        // read the lit holes: hole -> (role class, peak of the brightest blob on it)
        val litRole = IntArray(ix.holeX.size) { -1 }
        val litPeak = IntArray(ix.holeX.size)
        val cls = pal.classes
        for (b in blobs.indices) {
            if (roles[b] < 0) continue
            val w = inv.m[6] * blobs[b].x + inv.m[7] * blobs[b].y + inv.m[8]
            if (w <= 0) continue
            inv.apply(blobs[b].x, blobs[b].y, t)
            val fc = (t[0] - x0) / sx; val fr = (t[1] - y0) / sy
            val c = round(fc).toInt(); val r = round(fr).toInt()
            if (c !in 0 until nCols || r !in 0 until nRows) continue
            if (abs(fc - c) > 0.4 || abs(fr - r) > 0.4) continue
            val cell = r * nCols + c
            val h = holeAt[cell]
            if (h < 0) continue
            val k = markAt[cell]
            if (k >= 0 && hueDist(blobs[b].hue, marks[k].hue) < 25 && blobs[b].peak < litThr) continue
            if (blobs[b].peak > litPeak[h]) { litPeak[h] = blobs[b].peak; litRole[h] = cls[roles[b]] }
        }
        val lit = litRole.indices.filter { litRole[it] >= 0 }
        if (lit.size < 3) return Result(emptyList(), true)
        // catalogue: climbs that contain the lit holes with the right colour
        val hit = IntArray(ix.size)
        for (h in lit) for (e in invOff[h] until invOff[h + 1]) if (cls[invRole[e].toInt()] == litRole[h]) hit[invClimb[e]]++
        val minHit = max(2, (lit.size + 1) / 2)
        val inFrame = BooleanArray(ix.holeX.size)
        for (h in ix.holeX.indices) {
            hm.apply(ix.holeX[h].toDouble(), ix.holeY[h].toDouble(), t)
            inFrame[h] = t[0] >= 0 && t[0] < frameW && t[1] >= 0 && t[1] < frameH
        }
        // what the camera can see: coloured holds that should be in view but are not found mark the parts a climber
        // (or anything else) hides; a hold that is not lit there is no evidence against a problem
        val markSeen = BooleanArray(marks.size)
        val markIn = BooleanArray(marks.size)
        val t2 = DoubleArray(2)
        for ((k, m) in marks.withIndex()) {
            hm.apply(m.x, m.y, t)
            markIn[k] = t[0] >= 0 && t[0] < frameW && t[1] >= 0 && t[1] < frameH
            if (!markIn[k]) continue
            hm.apply(m.x + sx, m.y, t2)
            val cell = hypot(t2[0] - t[0], t2[1] - t[1])
            for (b in blobs) if (hueDist(b.hue, m.hue) < 25 && hypot(b.x - t[0], b.y - t[1]) < 0.45 * cell) { markSeen[k] = true; break }
        }
        val vis = DoubleArray(ix.holeX.size)
        for (h in ix.holeX.indices) {
            if (!inFrame[h]) continue
            if (litRole[h] >= 0) { vis[h] = 1.0; continue }
            var sw = 0.0; var ss = 0.0
            for ((k, m) in marks.withIndex()) {
                if (!markIn[k]) continue
                val du = (m.x - ix.holeX[h]) / sx; val dv = (m.y - ix.holeY[h]) / sy
                val d2 = du * du + dv * dv
                if (d2 > 4.0) continue
                val w = 1.0 / (1.0 + d2)
                sw += w; if (markSeen[k]) ss += w
            }
            vis[h] = if (sw > 0) ss / sw else 0.7
        }
        fun score(c: Int): Candidate {
            var score = 0.0; var matched = 0; var missing = 0
            for (sl in ix.offsets[c] until ix.offsets[c + 1]) {
                val h = ix.holes[sl].toInt(); val role = ix.roles[sl].toInt()
                if (litRole[h] == cls[role]) { score += W_MATCH[role]; matched++ }
                else if (inFrame[h]) { score -= W_MISS[role] * vis[h]; if (vis[h] >= 0.5) missing++ }
            }
            val extra = lit.size - matched
            for (h in lit) {
                // an unexplained lit hole costs as much as an extra LED of its colour
                var inClimb = false
                for (sl in ix.offsets[c] until ix.offsets[c + 1]) if (ix.holes[sl].toInt() == h && cls[ix.roles[sl].toInt()] == litRole[h]) { inClimb = true; break }
                if (!inClimb) score -= W_EXTRA[min(3, litRole[h])]
            }
            return Candidate(c, pal.name, score, matched, missing, extra, 0.0, hm, score)
        }
        val out = ArrayList<Candidate>()
        for (c in 0 until ix.size) if (hit[c] >= minHit) out.add(score(c))
        out.sortWith(compareBy<Candidate>({ -it.score }, { -ix.ascents[it.climb] }))
        val seen = HashSet<Long>()
        return Result(out.filter { seen.add(ix.sig[it.climb]) }.take(40), true, if (focus in 0 until ix.size) score(focus) else null)
    }
}
