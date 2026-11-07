package com.cruxcoach.android.boardscan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Candidate(
    val climb: Int,
    val palette: String,
    /** Ranking score: the LED evidence plus the board-fingerprint bonus. */
    val score: Double,
    val matched: Int,
    val missing: Int,
    val extra: Int,
    val resid: Double,
    val boardToImage: Hom,
    /** The LED evidence alone (matched, missing, extra, colour conflicts). */
    val ledScore: Double = score,
    /** The problem is lit mirrored (Kilter); [boardToImage] then maps the mirrored board. */
    val mirrored: Boolean = false,
)

class MatchResult(
    val ranked: List<Candidate>,
    val sure: Boolean,
    val coarseMs: Long,
    val verifyMs: Long,
    val tiers: Int,
    val palette: String?,
)

class MatchParams(
    /** Guesses of the camera's heading relative to the board (degrees) for the catalogue search. */
    val headings: DoubleArray = doubleArrayOf(-20.0, 0.0, 20.0),
    /** Headings for the final pose fit (with sensors). */
    val fineHeadings: DoubleArray = DoubleArray(13) { -30.0 + 5.0 * it },
    /** The most climbed problems searched first; the rest only when they give no sure answer. */
    val tierSize: Int = 20000,
    val maxRotDeg: Double = 15.0,
    val nTopBlobs: Int = 3,
    val nBottomBlobs: Int = 4,
    val loose: Double = 1.5,
    val tight: Double = 0.55,
    val keepPerRect: Int = 200,
    val verifyK: Int = 100,
    val sureScore: Double = 5.0,
    val sureMargin: Double = 2.5,
    val sureFraction: Double = 0.6,
    /** A sure answer needs this many matched LEDs, unless the problem is smaller and every hold matched. */
    val sureMinMatched: Int = 7,
    val sureMaxExtra: Int = 3,
    /** Mean distance of the matched LEDs from their holds, in hole spacings. */
    val sureMaxResid: Double = 0.3,
    /** Threads for the catalogue search. */
    val threads: Int = 1,
)

internal val W_MATCH = doubleArrayOf(1.2, 1.0, 1.2, 0.5)
internal val W_MISS = doubleArrayOf(0.8, 0.7, 0.8, 0.15)
internal val W_EXTRA = doubleArrayOf(1.0, 1.0, 1.0, 0.3)
private const val W_CONFLICT = 1.0
private const val W_STATIC = 3.0

/**
 * Recognises which problem of one board configuration is lit, from the LED blobs of one camera frame.
 *
 * Kilter-style boards are matched against the catalogue (most climbed problems first); boards whose holes sit on a
 * regular grid and that have coloured holds of their own ([staticMarks], MoonBoard) are read directly by
 * [GridReader]. The answer is a ranking plus a strict "sure" flag; when it is not sure, the caller offers the best
 * three or waits for more frames. Frames and results are never stored.
 */
class BoardMatcher(
    private val ix: ClimbIndex,
    private val p: MatchParams = MatchParams(),
    /** The board's own coloured holds: they pin a regular LED grid (MoonBoard) to the right column and row. */
    private val staticMarks: List<StaticMark> = emptyList(),
) {
    private val staticHue: Double = if (staticMarks.isEmpty()) 0.0 else {
        val c = staticMarks.sumOf { cos(it.hue * PI / 180) }
        val s = staticMarks.sumOf { sin(it.hue * PI / 180) }
        (atan2(s, c) * 180 / PI + 360) % 360
    }
    private val grid: GridReader? = if (staticMarks.isEmpty()) null else GridReader(ix, staticMarks, staticHue).takeIf { it.usable }
    private val pool0 = java.util.concurrent.Executors.newFixedThreadPool(max(1, p.threads)) { r ->
        Thread(r, "board-scan").apply { isDaemon = true }
    }

    /** Stops the search threads; the matcher cannot be used afterwards. */
    fun close() {
        pool0.shutdownNow()
    }

    /**
     * Also looks for mirrored problems: a mirror image of the frame shows the mirrored problem as the original one
     * on a symmetric board, so the same search runs on the mirrored LEDs (x flipped, gravity's x flipped).
     */
    fun recogniseWithMirror(
        blobs: List<Blob>, frameW: Int, frameH: Int, f: Double, upCam: DoubleArray, overhangDeg: Double,
        palettes: List<Palette>, onProgress: ((MatchResult) -> Unit)? = null,
    ): MatchResult {
        val a = recognise(blobs, frameW, frameH, f, upCam, overhangDeg, palettes, onProgress)
        if (a.sure) return a
        onProgress?.invoke(a)
        checkInterrupted()
        val mb = blobs.map { Blob(frameW - 1 - it.x, it.y, it.hue, it.chroma, it.area, it.peak) }
        val b = recognise(mb, frameW, frameH, f, doubleArrayOf(-upCam[0], upCam[1], upCam[2]), overhangDeg, palettes)
        val flipX = Hom(doubleArrayOf(-1.0, 0.0, frameW - 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        val bm = b.ranked.map {
            Candidate(it.climb, it.palette, it.score, it.matched, it.missing, it.extra, it.resid, flipX * it.boardToImage, it.ledScore, true)
        }
        val all = (a.ranked + bm).sortedWith(compareBy<Candidate>({ -it.score }, { -ix.ascents[it.climb] }, { it.mirrored }))
        val seen = HashSet<Long>()
        val ranked = all.filter { seen.add(ix.sig[it.climb] * 2 + if (it.mirrored) 1 else 0) }
        val sure = if (b.sure && bm.isNotEmpty() && ranked.firstOrNull() === bm.first()) isSure(ranked) else false
        return MatchResult(ranked, sure, a.coarseMs + b.coarseMs, a.verifyMs + b.verifyMs, a.tiers + b.tiers, a.palette)
    }

    fun recognise(
        blobs: List<Blob>, frameW: Int, frameH: Int, f: Double, upCam: DoubleArray, overhangDeg: Double,
        palettes: List<Palette>, onProgress: ((MatchResult) -> Unit)? = null,
    ): MatchResult = recognise(blobs, frameW, frameH, f,
        Rectifier.rects(f, frameW / 2.0, frameH / 2.0, upCam, overhangDeg, p.headings), palettes,
        Rectifier.rects(f, frameW / 2.0, frameH / 2.0, upCam, overhangDeg, p.fineHeadings), onProgress)

    /**
     * [rects]: image -> rectified homographies to try (from sensors, or a sweep when there are none).
     * [fineRects]: a denser set for the final fit; the pose is one of them followed by an affine map, which stays
     * well constrained with few or clustered holds where a free homography would not.
     * [onProgress] gets the ranking after each search tier that was not sure, while the next one runs. An interrupt
     * of the calling thread stops the search between tiers with an [InterruptedException].
     */
    fun recognise(
        blobs: List<Blob>, frameW: Int, frameH: Int, f: Double, rects: List<Hom>, palettes: List<Palette>,
        fineRects: List<Hom> = rects, onProgress: ((MatchResult) -> Unit)? = null,
    ): MatchResult {
        val flip = Hom.diag(1.0, -1.0, 1.0)
        val fine = fineRects.map { r -> val fwd = flip * r; RectPair(fwd, fwd.inverse()) }
        if (blobs.size < 3) return MatchResult(emptyList(), false, 0, 0, 0, null)
        // palette: the one whose hues explain the blobs best; keep a second one when it is nearly as good
        val labelled = palettes.map { it to labelRoles(blobs, it) }.sortedBy { it.second.cost }
        val usePal = labelled.filter { it.second.cost <= labelled[0].second.cost * 1.15 + 1e-9 }
        val pool = HashMap<Long, Pair<Double, Hom>>()
        val poolRoles = HashMap<String, IntArray>()
        for ((pal, lab) in usePal) poolRoles[pal.name] = lab.roles
        var coarseMs = 0L
        var verifyMs = 0L
        // boards with a regular grid and coloured holds of their own: read the board first
        val tg = System.nanoTime()
        val gr = grid?.read(blobs, usePal[0].second.roles, usePal[0].first, fine, frameW, frameH)
        verifyMs += (System.nanoTime() - tg) / 1_000_000
        // once the board itself is pinned down, its reading is the answer: a search that ignores the board's own
        // holds can only add poses that disagree with it
        if (gr != null && gr.poseFound) return MatchResult(gr.cands, isSureGrid(gr.cands), 0, verifyMs, 0, usePal[0].first.name)
        val gridCands = gr?.cands ?: emptyList()
        var ranked: List<Candidate> = emptyList()
        var tiers = 0
        var from = 0
        while (from < ix.size) {
            val to = if (from == 0) min(ix.size, p.tierSize) else ix.size
            tiers++
            val t0 = System.nanoTime()
            for ((pi, pl) in usePal.withIndex()) {
                val (pal, lab) = pl
                for (hr in rects) coarse(blobs, lab.roles, pal.classes, hr, f, from, to, pi, pool)
            }
            val t1 = System.nanoTime()
            val cands = pool.entries.sortedByDescending { it.value.first }.take(p.verifyK)
            val verified = cands.map { e ->
                val climb = (e.key shr 4).toInt()
                val pi = (e.key and 15).toInt()
                val pal = usePal[pi].first
                verify(climb, e.value.second, blobs, poolRoles[pal.name]!!, pal, frameW, frameH, fine)
            }.plus(gridCands).sortedWith(compareBy<Candidate>({ -it.score }, { -ix.ascents[it.climb] }))
            val seen = HashSet<Long>()
            ranked = verified.filter { seen.add(ix.sig[it.climb]) }
            coarseMs += (t1 - t0) / 1_000_000
            verifyMs += (System.nanoTime() - t1) / 1_000_000
            // on a board that can be read directly, a search that could not pin the board down is never sure
            if (grid == null && isSure(ranked)) return MatchResult(ranked, true, coarseMs, verifyMs, tiers, usePal[0].first.name)
            from = to
            if (from < ix.size) {
                onProgress?.invoke(MatchResult(ranked, false, coarseMs, verifyMs, tiers, usePal[0].first.name))
                checkInterrupted()
            }
        }
        return MatchResult(ranked, false, coarseMs, verifyMs, tiers, usePal[0].first.name)
    }

    /**
     * How well one given problem fits the frame on its own: searched and verified like every other, but alone, so
     * a lit problem that was not offered shows whether its LEDs were seen at all (test mode). Null when it cannot be
     * placed on the frame. Mirrored problems: pass the mirrored frame, as [recogniseWithMirror] does.
     */
    fun explain(
        climb: Int, blobs: List<Blob>, frameW: Int, frameH: Int, f: Double, rects: List<Hom>, palettes: List<Palette>,
        fineRects: List<Hom> = rects,
    ): Candidate? {
        if (blobs.size < 3 || climb !in 0 until ix.size) return null
        val flip = Hom.diag(1.0, -1.0, 1.0)
        val fine = fineRects.map { r -> val fwd = flip * r; RectPair(fwd, fwd.inverse()) }
        val labelled = palettes.map { it to labelRoles(blobs, it) }.sortedBy { it.second.cost }
        val usePal = labelled.filter { it.second.cost <= labelled[0].second.cost * 1.15 + 1e-9 }
        val gr = grid?.read(blobs, usePal[0].second.roles, usePal[0].first, fine, frameW, frameH, focus = climb)
        if (gr != null && gr.poseFound) return gr.focus
        val pool = HashMap<Long, Pair<Double, Hom>>()
        for ((pi, pl) in usePal.withIndex()) for (hr in rects) coarse(blobs, pl.second.roles, pl.first.classes, hr, f, climb, climb + 1, pi, pool)
        return pool.entries.map { e ->
            val (pal, lab) = usePal[(e.key and 15).toInt()]
            verify(climb, e.value.second, blobs, lab.roles, pal, frameW, frameH, fine)
        }.maxByOrNull { it.score }
    }

    /** Overload of [explain] with the camera's gravity reading, as in [recognise]. */
    fun explain(
        climb: Int, blobs: List<Blob>, frameW: Int, frameH: Int, f: Double, upCam: DoubleArray, overhangDeg: Double,
        palettes: List<Palette>,
    ): Candidate? = explain(climb, blobs, frameW, frameH, f,
        Rectifier.rects(f, frameW / 2.0, frameH / 2.0, upCam, overhangDeg, p.headings), palettes,
        Rectifier.rects(f, frameW / 2.0, frameH / 2.0, upCam, overhangDeg, p.fineHeadings))

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    /**
     * With the board pinned down, the lit holes are read directly: sure when they are exactly one problem's holds
     * (every lit hole explained, no visible hold of it dark) and no other problem fits as well.
     */
    fun isSureGrid(r: List<Candidate>): Boolean {
        if (r.isEmpty()) return false
        val b = r[0]
        val second = if (r.size > 1) r[1].score else -1e9
        val m = ix.offsets[b.climb + 1] - ix.offsets[b.climb]
        // every hold of the problem lit and seen: holds hidden by a climber leave it open
        return b.extra == 0 && b.matched == m && b.matched >= 4 && b.score - second >= 0.5
    }

    fun isSure(r: List<Candidate>): Boolean {
        if (r.isEmpty()) return false
        val b = r[0]
        val m = ix.offsets[b.climb + 1] - ix.offsets[b.climb]
        val second = if (r.size > 1) r[1].score else 0.0
        // and the pose must explain the frame: few unexplained LEDs, holds close to their LEDs
        return b.ledScore >= p.sureScore && b.score - second >= p.sureMargin && b.matched >= max(4.0, ceil(p.sureFraction * m)) &&
            b.matched >= min(m, p.sureMinMatched) &&
            b.extra <= p.sureMaxExtra && b.resid <= p.sureMaxResid
    }

    /**
     * Plausible board->rectified scales: camera 0.8..9 m from the board, and no two lit LEDs closer than the
     * closest two holes (median nearest-neighbour distance, so a split or false blob cannot shrink it).
     */
    private fun scaleBounds(rx: DoubleArray, ry: DoubleArray, use: BooleanArray, f: Double): DoubleArray {
        val lo = f * ix.metresPerUnit / 9.0
        var hi = f * ix.metresPerUnit / 0.8
        val nn = ArrayList<Double>()
        for (i in rx.indices) {
            if (!use[i]) continue
            var d = Double.MAX_VALUE
            for (j in rx.indices) if (j != i && use[j]) d = min(d, hypot(rx[i] - rx[j], ry[i] - ry[j]))
            if (d < Double.MAX_VALUE) nn.add(d)
        }
        if (nn.size >= 3) {
            nn.sort()
            hi = min(hi, 1.6 * nn[nn.size / 2] / ix.minHoleDist)
        }
        return doubleArrayOf(lo, max(hi, lo * 1.05))
    }

    /** Similarity hypotheses from top/bottom anchor blobs, refined to an affine; best tight count per climb. */
    private fun coarse(
        blobs: List<Blob>, rbIn: IntArray, cls: IntArray, hr: Hom, f: Double, from: Int, to: Int, palIdx: Int,
        pool: HashMap<Long, Pair<Double, Hom>>,
    ) {
        val n = blobs.size
        val rx = DoubleArray(n)
        val ry = DoubleArray(n)
        val tmp = DoubleArray(2)
        val rbx = rbIn.copyOf()
        for (i in 0 until n) {
            val w = hr.m[6] * blobs[i].x + hr.m[7] * blobs[i].y + hr.m[8]
            hr.apply(blobs[i].x, blobs[i].y, tmp)
            rx[i] = tmp[0]; ry[i] = -tmp[1]
            if (w <= 0) rbx[i] = -1
        }
        // coarse colour class per blob (roles whose LED hues are close share a class, e.g. Kilter green/cyan)
        val rb = IntArray(n) { if (rbx[it] >= 0) cls[rbx[it]] else -1 }
        val rolesOfClass = Array(4) { k -> (0 until 4).filter { cls[it] == k }.toIntArray() }
        val anchorUse = BooleanArray(n) { rb[it] >= 0 && rb[it] != cls[Roles.FOOT] }
        val anchors = (0 until n).filter { anchorUse[it] }
        if (anchors.size < 2) return
        val tb = anchors.sortedByDescending { ry[it] }.take(p.nTopBlobs).toIntArray()
        val bb = anchors.sortedBy { ry[it] }.take(p.nBottomBlobs).toIntArray()
        val sr = scaleBounds(rx, ry, anchorUse, f)
        // blobs per hold role (via its class), for the inner loops
        val byRole = Array(4) { r -> (0 until n).filter { rb[it] == cls[r] }.toIntArray() }
        val count = IntArray(to - from)
        val trans = DoubleArray((to - from) * 6)
        val sp = ix.spacing
        val tanRot = kotlin.math.tan(p.maxRotDeg * PI / 180)
        val minAnchor2 = (1.5 * sp) * (1.5 * sp)
        val smin2 = sr[0] * sr[0]
        val smax2 = sr[1] * sr[1]
        fun run(c0: Int, c1: Int) {
            for (c in c0 until c1) {
                // a stopped scan: leave the rest of this chunk, the caller throws
                if ((c and 511) == 0 && Thread.currentThread().isInterrupted) return
                val a0i = ix.offsets[c]
                val m = ix.offsets[c + 1] - a0i
                var best = 0
                val mf = ix.nonFoot[c].toInt()
                val need = max(3, (mf + 1) / 2)
                for (i in tb) {
                    val ri = rb[i]
                    for (j in bb) {
                        if (i == j) continue
                        val rj = rb[j]
                        val vbx = rx[i] - rx[j]
                        val vby = ry[i] - ry[j]
                        val lb2 = vbx * vbx + vby * vby
                        for (ra in rolesOfClass[ri]) for (a in 0 until 2) {
                            val ta = ix.topSlots[c * 8 + ra * 2 + a].toInt()
                            if (ta < 0) break
                            for (rbb in rolesOfClass[rj]) for (b in 0 until 3) {
                                val tbh = ix.bottomSlots[c * 12 + rbb * 3 + b].toInt()
                                if (tbh < 0) break
                                if (tbh == ta) continue
                                val vhx = (ix.holdX[a0i + ta] - ix.holdX[a0i + tbh]).toDouble()
                                val vhy = (ix.holdY[a0i + ta] - ix.holdY[a0i + tbh]).toDouble()
                                val lh2 = vhx * vhx + vhy * vhy
                                if (lh2 < minAnchor2) continue
                                val s2 = lb2 / lh2
                                if (s2 < smin2 || s2 > smax2) continue
                                // similarity mapping vh onto vb: sc = s cos d, ss = s sin d
                                val sc = (vhx * vbx + vhy * vby) / lh2
                                val ss = (vhx * vby - vhy * vbx) / lh2
                                if (sc <= 0 || abs(ss) > tanRot * sc) continue
                                val s = sqrt(s2)
                                val bx = ix.holdX[a0i + tbh].toDouble()
                                val by = ix.holdY[a0i + tbh].toDouble()
                                val tx = rx[j] - (sc * bx - ss * by)
                                val ty = ry[j] - (ss * bx + sc * by)
                                // loose role-aware assignment -> affine normal equations
                                val r1 = (p.loose * sp * s).let { it * it }
                                var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0
                                var sxu = 0.0; var syu = 0.0; var su = 0.0; var sxv = 0.0; var syv = 0.0; var sv = 0.0
                                var npair = 0
                                var h = 0
                                while (h < m) {
                                    val hx = ix.holdX[a0i + h].toDouble()
                                    val hy = ix.holdY[a0i + h].toDouble()
                                    val px = sc * hx - ss * hy + tx
                                    val py = ss * hx + sc * hy + ty
                                    var bd = r1
                                    var bq = -1
                                    for (q in byRole[ix.roles[a0i + h].toInt()]) {
                                        val dx = px - rx[q]
                                        val dy = py - ry[q]
                                        val d2 = dx * dx + dy * dy
                                        if (d2 < bd) { bd = d2; bq = q }
                                    }
                                    if (bq >= 0) {
                                        npair++
                                        val u = rx[bq]; val v = ry[bq]
                                        sxx += hx * hx; sxy += hx * hy; syy += hy * hy; sx += hx; sy += hy
                                        sxu += hx * u; syu += hy * u; su += u; sxv += hx * v; syv += hy * v; sv += v
                                    } else if (h < mf && (h + 1 - npair) > mf - need) break
                                    h++
                                }
                                if (h < m || npair < need || npair <= best) continue
                                val nn = npair.toDouble()
                                val det = sxx * (syy * nn - sy * sy) - sxy * (sxy * nn - sy * sx) + sx * (sxy * sy - syy * sx)
                                if (abs(det) < 1e-9 * (sxx * syy * nn + 1.0)) continue
                                val i00 = (syy * nn - sy * sy) / det
                                val i01 = -(sxy * nn - sx * sy) / det
                                val i02 = (sxy * sy - sx * syy) / det
                                val i11 = (sxx * nn - sx * sx) / det
                                val i12 = -(sxx * sy - sx * sxy) / det
                                val i22 = (sxx * syy - sxy * sxy) / det
                                val a0 = i00 * sxu + i01 * syu + i02 * su
                                val a1 = i01 * sxu + i11 * syu + i12 * su
                                val a2 = i02 * sxu + i12 * syu + i22 * su
                                val b0 = i00 * sxv + i01 * syv + i02 * sv
                                val b1 = i01 * sxv + i11 * syv + i12 * sv
                                val b2 = i02 * sxv + i12 * syv + i22 * sv
                                val sa = sqrt(abs(a0 * b1 - a1 * b0))
                                if (sa < sr[0] * 0.5 || sa > sr[1] * 2.0) continue
                                val r2 = (p.tight * sp * sa).let { it * it }
                                var cnt = 0
                                var miss = 0
                                for (h2 in 0 until m) {
                                    val hx = ix.holdX[a0i + h2].toDouble()
                                    val hy = ix.holdY[a0i + h2].toDouble()
                                    val px = a0 * hx + a1 * hy + a2
                                    val py = b0 * hx + b1 * hy + b2
                                    var hit = false
                                    for (q in byRole[ix.roles[a0i + h2].toInt()]) {
                                        val dx = px - rx[q]
                                        val dy = py - ry[q]
                                        if (dx * dx + dy * dy < r2) { hit = true; break }
                                    }
                                    if (hit) cnt++ else {
                                        miss++
                                        if (m - miss <= best) break
                                    }
                                }
                                if (cnt > best) {
                                    best = cnt
                                    val o = (c - from) * 6
                                    trans[o] = a0; trans[o + 1] = a1; trans[o + 2] = a2
                                    trans[o + 3] = b0; trans[o + 4] = b1; trans[o + 5] = b2
                                }
                            }
                        }
                    }
                }
                count[c - from] = best
            }
        }
        val nThreads = p.threads
        checkInterrupted()
        if (nThreads <= 1 || to - from < 4096) run(from, to)
        else {
            val chunk = (to - from + nThreads * 4 - 1) / (nThreads * 4)
            val futures = try {
                (from until to step chunk).map { c0 -> pool0.submit { run(c0, min(to, c0 + chunk)) } }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                // the matcher was closed while this search ran
                throw InterruptedException("matcher closed").apply { initCause(e) }
            }
            try {
                futures.forEach { it.get() }
            } catch (e: InterruptedException) {
                futures.forEach { it.cancel(true) }
                throw e
            }
        }
        checkInterrupted()
        // keep the best per rectification, merge into the pool. Rank by matched holds minus a share of the
        // unmatched ones, so big climbs that half-match do not crowd out a small climb that matches fully.
        fun cs(k: Int): Double {
            val m = ix.offsets[from + k + 1] - ix.offsets[from + k]
            return count[k] - 0.6 * (m - count[k])
        }
        val order = (0 until to - from).filter { count[it] >= 3 }.sortedByDescending { cs(it) }.take(p.keepPerRect)
        val flipInv = hr.inverse() * Hom.diag(1.0, -1.0, 1.0)
        for (k in order) {
            val o = k * 6
            val aff = Hom(doubleArrayOf(trans[o], trans[o + 1], trans[o + 2], trans[o + 3], trans[o + 4], trans[o + 5], 0.0, 0.0, 1.0))
            val key = ((from + k).toLong() shl 4) or palIdx.toLong()
            val prev = pool[key]
            if (prev == null || cs(k) > prev.first) pool[key] = cs(k) to (flipInv * aff)
        }
    }

    /**
     * Board -> image pose from hold/blob pairs: for each rectification, an affine map from board to rectified
     * coordinates; the rectification whose affine is closest to a similarity with the smallest residual wins.
     */
    private fun fitPairs(climb: Int, ph: IntArray, pb: IntArray, blobs: List<Blob>, rects: List<RectPair>): Hom? {
        val k = ph.size
        if (k < 3) return null
        val a0i = ix.offsets[climb]
        val src = DoubleArray(2 * k)
        val dst = DoubleArray(2 * k)
        for (q in 0 until k) {
            val hh = ix.holes[a0i + ph[q]].toInt()
            src[2 * q] = ix.holeX[hh].toDouble(); src[2 * q + 1] = ix.holeY[hh].toDouble()
        }
        val t = DoubleArray(2)
        var best: Hom? = null
        var bestErr = Double.MAX_VALUE
        for (rp in rects) {
            var ok = true
            for (q in 0 until k) {
                val b = blobs[pb[q]]
                if (rp.fwd.m[6] * b.x + rp.fwd.m[7] * b.y + rp.fwd.m[8] <= 0) { ok = false; break }
                rp.fwd.apply(b.x, b.y, t); dst[2 * q] = t[0]; dst[2 * q + 1] = t[1]
            }
            if (!ok) continue
            val a = Hom.fitAffine(src, dst, k) ?: continue
            val m = a.m
            val det = m[0] * m[4] - m[1] * m[3]
            if (det <= 1e-12) continue
            val s = sqrt(det)
            var e = 0.0
            for (q in 0 until k) {
                a.apply(src[2 * q], src[2 * q + 1], t)
                val dx = t[0] - dst[2 * q]; val dy = t[1] - dst[2 * q + 1]
                e += dx * dx + dy * dy
            }
            // residual in hole spacings, plus how far the affine is from a similarity (shear, unequal axes)
            val err = sqrt(e / k) / (s * ix.spacing) + 0.5 * (abs(m[0] - m[4]) + abs(m[1] + m[3])) / s
            if (err < bestErr) { bestErr = err; best = rp.back * a }
        }
        return best
    }

    /** Iterative role-aware assignment + homography refinement in image space, then the score. */
    private fun verify(
        climb: Int, h0: Hom, blobs: List<Blob>, rbRole: IntArray, pal: Palette, frameW: Int, frameH: Int,
        rects: List<RectPair>,
    ): Candidate {
        val palette = pal.name
        val cls = pal.classes
        val rb = IntArray(rbRole.size) { if (rbRole[it] >= 0) cls[rbRole[it]] else -1 }
        val a0i = ix.offsets[climb]
        val m = ix.offsets[climb + 1] - a0i
        val n = blobs.size
        var hm = h0
        val px = DoubleArray(m)
        val py = DoubleArray(m)
        val loc = DoubleArray(m)
        val tmp = DoubleArray(2)
        val tmp2 = DoubleArray(2)
        var pairH = IntArray(0)
        var pairB = IntArray(0)
        val sp = ix.spacing
        for (it in 0 until 4) {
            for (h in 0 until m) {
                val hh = ix.holes[a0i + h].toInt()
                val x = ix.holeX[hh].toDouble()
                val y = ix.holeY[hh].toDouble()
                hm.apply(x, y, tmp); px[h] = tmp[0]; py[h] = tmp[1]
                hm.apply(x + sp, y, tmp2)
                val lx = hypot(tmp2[0] - tmp[0], tmp2[1] - tmp[1])
                hm.apply(x, y + sp, tmp2)
                val ly = hypot(tmp2[0] - tmp[0], tmp2[1] - tmp[1])
                loc[h] = 0.5 * (lx + ly)
            }
            val radF = if (it == 0) 0.8 else 0.6
            // candidate pairs sorted by distance, greedy one-to-one
            val ds = ArrayList<Triple<Double, Int, Int>>()
            for (h in 0 until m) {
                val role = cls[ix.roles[a0i + h].toInt()]
                for (b in 0 until n) {
                    if (rb[b] != role) continue
                    val d = hypot(px[h] - blobs[b].x, py[h] - blobs[b].y)
                    if (d <= radF * loc[h]) ds.add(Triple(d, h, b))
                }
            }
            ds.sortBy { it.first }
            val uh = BooleanArray(m)
            val ub = BooleanArray(n)
            val ph = ArrayList<Int>()
            val pb = ArrayList<Int>()
            for ((_, h, b) in ds) {
                if (uh[h] || ub[b]) continue
                uh[h] = true; ub[b] = true; ph.add(h); pb.add(b)
            }
            pairH = ph.toIntArray(); pairB = pb.toIntArray()
            val fit = fitPairs(climb, pairH, pairB, blobs, rects)
            if (fit != null) {
                hm = fit
                // trimmed refit: a lit rim around a big hold can sit half a hole spacing off its hole (or split in
                // two arcs); such pairs bend a free homography, so drop the worst ones and fit again
                if (it >= 1 && pairH.size >= 6) {
                    val res = DoubleArray(pairH.size)
                    for (q in pairH.indices) {
                        val hh = ix.holes[a0i + pairH[q]].toInt()
                        hm.apply(ix.holeX[hh].toDouble(), ix.holeY[hh].toDouble(), tmp)
                        res[q] = hypot(tmp[0] - blobs[pairB[q]].x, tmp[1] - blobs[pairB[q]].y)
                    }
                    val med = res.sorted()[res.size / 2]
                    val keep = pairH.indices.filter { res[it] <= max(2.5 * med, 0.2 * loc[pairH[it]]) }
                    if (keep.size >= 5 && keep.size < pairH.size) {
                        fitPairs(climb, IntArray(keep.size) { pairH[keep[it]] }, IntArray(keep.size) { pairB[keep[it]] }, blobs, rects)
                            ?.let { hm = it }
                    }
                }
            }
        }
        // final projection and score
        val matchedH = BooleanArray(m)
        val matchedB = BooleanArray(n)
        for (q in pairH.indices) { matchedH[pairH[q]] = true; matchedB[pairB[q]] = true }
        var score = 0.0
        var missing = 0
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        val locs = DoubleArray(m)
        for (h in 0 until m) {
            val hh = ix.holes[a0i + h].toInt()
            val x = ix.holeX[hh].toDouble()
            val y = ix.holeY[hh].toDouble()
            hm.apply(x, y, tmp); px[h] = tmp[0]; py[h] = tmp[1]
            hm.apply(x + sp, y, tmp2)
            locs[h] = hypot(tmp2[0] - tmp[0], tmp2[1] - tmp[1])
            x0 = min(x0, px[h]); x1 = max(x1, px[h]); y0 = min(y0, py[h]); y1 = max(y1, py[h])
            val role = ix.roles[a0i + h].toInt()
            if (matchedH[h]) score += W_MATCH[role]
            else if (px[h] >= 0 && px[h] < frameW && py[h] >= 0 && py[h] < frameH) {
                score -= W_MISS[role]; missing++
            }
        }
        val locH = locs.copyOf()
        locs.sort()
        val l = locs[m / 2]
        // the board's own coloured holds in this pose (MoonBoard): a colourful blob on one of them is paint, not an LED
        val sx = DoubleArray(staticMarks.size)
        val sy = DoubleArray(staticMarks.size)
        for ((k, s) in staticMarks.withIndex()) { hm.apply(s.x, s.y, tmp); sx[k] = tmp[0]; sy[k] = tmp[1] }
        fun onStatic(bl: Blob): Boolean {
            if (staticMarks.isEmpty() || hueDist(staticHue, bl.hue) >= 25) return false
            for (k in staticMarks.indices) {
                if (hypot(sx[k] - bl.x, sy[k] - bl.y) < 0.35 * l && hueDist(staticMarks[k].hue, bl.hue) < 25) return true
            }
            return false
        }
        // unexplained LEDs count anywhere on the board as this pose places it (not only around the climb's holds,
        // or a small climb matched at the wrong scale would ignore the rest of the lit problem); lights off the board
        // (other walls, lamps) do not count
        val inv = hm.inverse()
        val ex = ix.extent
        val mg = 0.5 * sp
        fun onBoard(bl: Blob): Boolean {
            val w = inv.m[6] * bl.x + inv.m[7] * bl.y + inv.m[8]
            if (w <= 0) return false
            inv.apply(bl.x, bl.y, tmp)
            return tmp[0] >= ex[0] - mg && tmp[0] <= ex[1] + mg && tmp[1] >= ex[2] - mg && tmp[1] <= ex[3] + mg
        }
        var extra = 0
        for (b in 0 until n) {
            if (matchedB[b] || rbRole[b] < 0) continue
            val bl = blobs[b]
            val near = bl.x >= x0 - 1.5 * l && bl.x <= x1 + 1.5 * l && bl.y >= y0 - 1.5 * l && bl.y <= y1 + 1.5 * l
            if (near || onBoard(bl)) {
                // the other arc of a lit rim that already matched a hold of this colour is not an extra LED
                var absorbed = false
                for (q in pairH.indices) {
                    val h = pairH[q]
                    if (cls[ix.roles[a0i + h].toInt()] == rb[b] && hypot(px[h] - bl.x, py[h] - bl.y) < 0.9 * locH[h]) { absorbed = true; break }
                }
                if (absorbed || onStatic(bl)) continue
                score -= W_EXTRA[rbRole[b]]; extra++
            }
        }
        // roles sharing a colour class: their blobs must still split by hue in the palette's order
        var conflicts = 0
        for (k in 0 until 4) {
            val rs = (0 until 4).filter { cls[it] == k }
            if (rs.size != 2) continue
            val lowRole = if (pal.hues[rs[0]] <= pal.hues[rs[1]]) rs[0] else rs[1]
            val items = pairH.indices.filter { cls[ix.roles[a0i + pairH[it]].toInt()] == k }
                .map { blobs[pairB[it]].hue to (ix.roles[a0i + pairH[it]].toInt() == lowRole) }
                .sortedBy { it.first }
            // threshold t splits: low-hue role below, the other above; minimise violations
            var bestV = items.size
            for (t in 0..items.size) {
                var v = 0
                for ((q, it) in items.withIndex()) if ((q < t) != it.second) v++
                bestV = min(bestV, v)
            }
            conflicts += bestV
        }
        score -= W_CONFLICT * conflicts
        // board fingerprint: non-LED colourful blobs must sit on the board's own coloured holds
        val ledScore = score
        var fingerprint = 0.0
        if (staticMarks.isNotEmpty()) {
            var support = 0
            var nStatic = 0
            for (b in 0 until n) {
                if (matchedB[b]) continue
                val bl = blobs[b]
                if (hueDist(staticHue, bl.hue) >= 25) continue
                nStatic++
                if (onStatic(bl)) support++
            }
            // share of the board's own coloured holds that the pose explains: high only for the right column/row
            fingerprint = W_STATIC * support / max(nStatic, 5)
        }
        var e2 = 0.0
        for (q in pairH.indices) {
            val d = hypot(px[pairH[q]] - blobs[pairB[q]].x, py[pairH[q]] - blobs[pairB[q]].y)
            e2 += d * d
        }
        val resid = if (pairH.isNotEmpty()) sqrt(e2 / pairH.size) / max(l, 1e-6) else 0.0
        return Candidate(climb, palette, ledScore + fingerprint, pairH.size, missing, extra, resid, hm, ledScore)
    }
}
