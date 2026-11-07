package com.cruxcoach.android.boardscan

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End to end on synthetic boards: lit LEDs projected through the same camera model the recogniser assumes
 * (gravity, board angle, heading), then recognised against a small catalogue.
 */
class BoardMatcherTest {

    private val f = 1000.0
    private val frameW = 720
    private val frameH = 1280
    private val up = doubleArrayOf(0.02, -0.95, 0.31)
    private val overhang = 40.0
    private val matchers = ArrayList<BoardMatcher>()

    @AfterTest
    fun closeMatchers() = matchers.forEach { it.close() }

    private class Board(val x: FloatArray, val y: FloatArray, val climbs: List<List<Pair<Int, Int>>>)

    private fun index(b: Board, spacing: Double, extent: DoubleArray, mpu: Double): ClimbIndex {
        val offsets = IntArray(b.climbs.size + 1)
        for ((c, cl) in b.climbs.withIndex()) offsets[c + 1] = offsets[c] + cl.size
        val holes = ShortArray(offsets.last())
        val roles = ByteArray(offsets.last())
        for ((c, cl) in b.climbs.withIndex()) {
            for ((k, hr) in cl.withIndex()) {
                holes[offsets[c] + k] = hr.first.toShort()
                roles[offsets[c] + k] = hr.second.toByte()
            }
        }
        val ascents = IntArray(b.climbs.size) { b.climbs.size - it }
        return ClimbIndex(b.x, b.y, offsets, holes, roles, ascents, spacing, spacing, extent, mpu)
    }

    /** Board -> image for a camera at heading 0 that sees the board centre at the frame centre. */
    private fun boardToImage(scale: Double, centreX: Double, centreY: Double): Hom {
        val r = Rectifier.rects(f, frameW / 2.0, frameH / 2.0, up, overhang, doubleArrayOf(0.0)).single()
        val fwd = Hom.diag(1.0, -1.0, 1.0) * r
        val c = DoubleArray(2)
        fwd.apply(frameW / 2.0, frameH / 2.0, c)
        val a = Hom(doubleArrayOf(scale, 0.0, c[0] - scale * centreX, 0.0, scale, c[1] - scale * centreY, 0.0, 0.0, 1.0))
        return fwd.inverse() * a
    }

    private fun blobs(h: Hom, pts: List<Triple<Double, Double, Double>>): List<Blob> {
        val t = DoubleArray(2)
        return pts.map { (x, y, hue) ->
            h.apply(x, y, t)
            Blob(t[0], t[1], hue, 120.0, 40, 255)
        }
    }

    /** A Kilter-like board: holes on a jittered grid, mirror-symmetric about its centre line. */
    private fun kilterBoard(rnd: Random): Board {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (j in 0 until 20) {
            for (i in 0 until 8) {
                val x = 6f + 8f * i + rnd.nextFloat() * 3f - 1.5f
                val y = 6f + 8f * j + rnd.nextFloat() * 3f - 1.5f
                xs.add(x); ys.add(y)
                xs.add(144f - x); ys.add(y)
            }
        }
        val n = xs.size
        val climbs = (0 until 400).map {
            val picked = (0 until n).shuffled(rnd).take(8 + rnd.nextInt(5))
            val byHeight = picked.sortedBy { ys[it] }
            byHeight.mapIndexed { k, hole ->
                val role = when {
                    k < 2 -> Roles.FOOT
                    k < 4 -> Roles.START
                    k == byHeight.size - 1 -> Roles.FINISH
                    else -> Roles.HAND
                }
                hole to role
            }
        }
        return Board(xs.toFloatArray(), ys.toFloatArray(), climbs)
    }

    private fun matcher(ix: ClimbIndex, marks: List<StaticMark> = emptyList()) =
        BoardMatcher(ix, MatchParams(threads = 2), marks).also { matchers.add(it) }

    @Test
    fun recognisesALitKilterProblem() {
        val board = kilterBoard(Random(7))
        val ix = index(board, 8.0, doubleArrayOf(0.0, 144.0, 0.0, 168.0), 0.0254)
        val m = matcher(ix)
        val h = boardToImage(3.6, 72.0, 84.0)
        val climb = 123
        val pts = board.climbs[climb].map { (hole, role) ->
            Triple(board.x[hole].toDouble(), board.y[hole].toDouble(), Palette.KILTER.hues[role])
        }
        val r = m.recognise(blobs(h, pts), frameW, frameH, f, up, overhang, listOf(Palette.KILTER, Palette.CRUXCOACH))
        assertEquals(ix.sig[climb], ix.sig[r.ranked.first().climb])
        assertTrue(r.sure)
        assertFalse(r.ranked.first().mirrored)
    }

    @Test
    fun recognisesAMirroredKilterProblem() {
        val board = kilterBoard(Random(11))
        val ix = index(board, 8.0, doubleArrayOf(0.0, 144.0, 0.0, 168.0), 0.0254)
        val m = matcher(ix)
        val h = boardToImage(3.6, 72.0, 84.0)
        val climb = 42
        val pts = board.climbs[climb].map { (hole, role) ->
            Triple(144.0 - board.x[hole], board.y[hole].toDouble(), Palette.KILTER.hues[role])
        }
        val r = m.recogniseWithMirror(blobs(h, pts), frameW, frameH, f, up, overhang, listOf(Palette.KILTER))
        assertEquals(ix.sig[climb], ix.sig[r.ranked.first().climb])
        assertTrue(r.ranked.first().mirrored)
    }

    @Test
    fun isNeverSureWithTooFewLeds() {
        val board = kilterBoard(Random(7))
        val ix = index(board, 8.0, doubleArrayOf(0.0, 144.0, 0.0, 168.0), 0.0254)
        val m = matcher(ix)
        val h = boardToImage(3.6, 72.0, 84.0)
        val pts = board.climbs[5].take(3).map { (hole, role) ->
            Triple(board.x[hole].toDouble(), board.y[hole].toDouble(), Palette.KILTER.hues[role])
        }
        assertFalse(m.recognise(blobs(h, pts), frameW, frameH, f, up, overhang, listOf(Palette.KILTER)).sure)
    }

    @Test
    fun readsAGridBoardByItsColouredHolds() {
        val rnd = Random(3)
        val cols = 11
        val rows = 18
        val xs = FloatArray(cols * rows) { 120f + 70f * (it % cols) }
        val ys = FloatArray(cols * rows) { 50f + 75f * (it / cols) }
        val climbs = (0 until 600).map {
            val picked = (0 until cols * rows).shuffled(rnd).take(6 + rnd.nextInt(4)).sortedBy { ys[it] }
            picked.mapIndexed { k, hole ->
                hole to when {
                    k < 2 -> Roles.START
                    k == picked.size - 1 -> Roles.FINISH
                    else -> Roles.HAND
                }
            }
        }
        val board = Board(xs, ys, climbs)
        val ix = index(board, 70.0, doubleArrayOf(0.0, 1002.0, 0.0, 1430.0), 0.00283)
        val markCells = (0 until cols * rows).shuffled(rnd).take(40).toSet()
        val marks = markCells.map { StaticMark(xs[it].toDouble(), ys[it].toDouble(), 50.0) }
        val m = matcher(ix, marks)
        val h = boardToImage(0.42, 501.0, 690.0)
        val climb = 321
        val lit = climbs[climb].toMap()
        val pts = ArrayList<Triple<Double, Double, Double>>()
        for ((hole, role) in lit) pts.add(Triple(xs[hole].toDouble(), ys[hole].toDouble(), Palette.MOONBOARD.hues[role]))
        val t = DoubleArray(2)
        val blobs = blobs(h, pts).toMutableList()
        for (cell in markCells) {
            if (cell in lit) continue
            h.apply(xs[cell].toDouble(), ys[cell].toDouble(), t)
            blobs.add(Blob(t[0], t[1], 50.0, 100.0, 40, 200))
        }
        val r = m.recognise(blobs, frameW, frameH, f, up, overhang, listOf(Palette.MOONBOARD))
        assertEquals(ix.sig[climb], ix.sig[r.ranked.first().climb])
        assertTrue(r.sure)
        // read directly from the board, without the catalogue search
        assertEquals(0, r.tiers)
    }
}
