package com.cruxcoach.board

import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.games.EnduranceChallenge
import com.cruxcoach.domain.board.games.FloorIsLava
import com.cruxcoach.domain.board.games.GameFrame
import com.cruxcoach.domain.board.games.GameTimeline
import com.cruxcoach.domain.board.games.HoldsThief
import com.cruxcoach.domain.board.games.TraverseGame
import com.cruxcoach.domain.board.games.WallHold
import com.cruxcoach.domain.board.games.WallRows
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WallGamesTest {

    /** A [columns] × [rowCount] grid; placement id = row * 100 + column, 4 units apart like Aurora boards. */
    private fun grid(columns: Int, rowCount: Int): WallRows = WallRows.of(
        (0 until rowCount).flatMap { row ->
            (0 until columns).map { col -> WallHold(row * 100 + col, col * 4.0, row * 4.0 + 4.0) }
        },
    )

    private fun id(row: Int, col: Int) = row * 100 + col

    private fun ids(holds: List<BoardHold>) = holds.map { it.placementId }.toSet()

    // ── Rows ──────────────────────────────────────────────────────────

    @Test
    fun rowsRunBottomToTopAndLeftToRight() {
        val rows = WallRows.of(
            listOf(
                WallHold(5, 8.0, 12.0),
                WallHold(1, 4.0, 4.0),
                WallHold(2, 0.0, 4.0),
                WallHold(3, 4.0, 8.0),
                WallHold(4, 0.0, 12.0),
            ),
        )
        assertEquals(listOf(listOf(2, 1), listOf(3), listOf(4, 5)), rows.rows.map { r -> r.map { it.placementId } })
        assertEquals(0, rows.rowOf(1))
        assertEquals(2, rows.rowOf(5))
        assertNull(rows.rowOf(99))
        assertEquals(listOf(2, 1, 3, 4, 5), rows.holds.map { it.placementId })
    }

    @Test
    fun rowsAbsorbRoundingButKeepGridRowsApart() {
        val rows = WallRows.of(
            listOf(
                WallHold(1, 0.0, 30.0),
                WallHold(2, 1.0, 30.4),
                WallHold(3, 2.0, 42.0),
                WallHold(4, 3.0, 43.0),
            ),
        )
        assertEquals(3, rows.size)
        assertEquals(listOf(1, 2), rows.rows[0].map { it.placementId })
    }

    @Test
    fun rowsIgnoreDuplicatePlacements() {
        val rows = WallRows.of(listOf(WallHold(1, 0.0, 0.0), WallHold(1, 0.0, 0.0), WallHold(2, 4.0, 0.0)))
        assertEquals(2, rows.holds.size)
    }

    @Test
    fun shinyWallStandardRowsComeOutAsItsGridRows() {
        // Stored y of the bundled Standard geometry: kickers at 0 and 13, then
        // big and small rows alternating, three big rows at the top.
        val ys = listOf(0, 13, 30, 42, 55, 68, 80, 405, 425, 445)
        val rows = WallRows.of(ys.flatMapIndexed { r, y -> (0 until 3).map { WallHold(r * 10 + it, it * 20.0, y.toDouble()) } })
        assertEquals(ys.size, rows.size)
    }

    // ── Timeline ──────────────────────────────────────────────────────

    @Test
    fun timelineFindsTheFrameShowingAtATime() {
        val t = GameTimeline(
            listOf(GameFrame(0, emptyList(), 0), GameFrame(1000, emptyList(), 1), GameFrame(2000, emptyList(), 2)),
            steps = 2,
        )
        assertEquals(-1, t.indexAt(-1))
        assertEquals(0, t.indexAt(0))
        assertEquals(0, t.indexAt(999))
        assertEquals(1, t.indexAt(1000))
        assertEquals(2, t.indexAt(60_000))
        assertEquals(2000, t.durationMs)
    }

    @Test
    fun timelineRejectsFramesOutOfOrder() {
        assertFailsWith<IllegalArgumentException> {
            GameTimeline(listOf(GameFrame(1000, emptyList(), 0), GameFrame(0, emptyList(), 1)), steps = 1)
        }
    }

    // ── Floor is lava ─────────────────────────────────────────────────

    @Test
    fun lavaIntervalsFollowTheLevels() {
        assertEquals(listOf(3000L, 2000L, 1000L, 500L, 100L), (1..5).map { FloorIsLava.intervalMs(it) })
        assertEquals(3000L, FloorIsLava.intervalMs(0))
        assertEquals(100L, FloorIsLava.intervalMs(9))
    }

    @Test
    fun lavaRisesOneRowAtATimeFromTheBottom() {
        val rows = grid(columns = 3, rowCount = 4)
        val t = FloorIsLava.timeline(rows, level = 3)
        assertEquals(5, t.frames.size)
        assertEquals(4, t.steps)
        assertTrue(t.frames[0].holds.isEmpty(), "the wall starts dark")
        assertEquals(listOf(0L, 1000L, 2000L, 3000L, 4000L), t.frames.map { it.atMs })
        assertEquals(setOf(id(0, 0), id(0, 1), id(0, 2)), ids(t.frames[1].holds))
        assertEquals(9, t.frames[3].holds.size)
        assertEquals(12, t.frames.last().holds.size)
        assertTrue(t.frames.flatMap { it.holds }.all { it.roleId == HoldRole.START })
    }

    // ── Endurance ─────────────────────────────────────────────────────

    @Test
    fun enduranceIntervalsGrowWithTheLevel() {
        assertEquals(listOf(3000L, 5000L, 8000L, 10000L, 15000L), (1..5).map { EnduranceChallenge.intervalMs(it) })
    }

    @Test
    fun enduranceShowsTheStartThenOneRowAtATime() {
        val rows = grid(columns = 4, rowCount = 8)
        val route = listOf(
            BoardHold(id(5, 1), HoldRole.HAND),
            BoardHold(id(1, 0), HoldRole.START),
            BoardHold(id(1, 2), HoldRole.START),
            BoardHold(id(0, 1), HoldRole.FOOT),
            BoardHold(id(3, 0), HoldRole.FOOT),
            BoardHold(id(3, 3), HoldRole.HAND),
            BoardHold(id(7, 2), HoldRole.FINISH),
            BoardHold(999, HoldRole.HAND), // not on this wall
        )
        val groups = EnduranceChallenge.groups(route, rows)
        assertEquals(
            listOf(setOf(id(1, 0), id(1, 2), id(0, 1)), setOf(id(3, 0), id(3, 3)), setOf(id(5, 1)), setOf(id(7, 2))),
            groups.map { ids(it) },
        )
        assertEquals(listOf(id(3, 0), id(3, 3)), groups[1].map { it.placementId }, "a row lights left to right")
        assertEquals(HoldRole.FINISH, groups.last().single().roleId)

        val t = EnduranceChallenge.timeline(route, rows, level = 1)
        assertEquals(listOf(0L, 3000L, 6000L, 9000L), t.frames.map { it.atMs })
        assertEquals(7, t.frames.last().holds.size)
        assertEquals(3, t.frames.first().holds.size)
        assertEquals(4, t.steps)
    }

    @Test
    fun enduranceWithoutStartHoldsBuildsTheClimbFromTheBottomRow() {
        val rows = grid(columns = 2, rowCount = 4)
        val route = listOf(BoardHold(id(2, 0), HoldRole.HAND), BoardHold(id(0, 1), HoldRole.FOOT))
        assertEquals(listOf(setOf(id(0, 1)), setOf(id(2, 0))), EnduranceChallenge.groups(route, rows).map { ids(it) })
    }

    @Test
    fun enduranceOfAClimbOffTheWallIsEmpty() {
        val t = EnduranceChallenge.timeline(listOf(BoardHold(999, HoldRole.START)), grid(2, 2), level = 2)
        assertTrue(t.frames.isEmpty())
        assertEquals(0, t.steps)
    }

    // ── Hold thief ────────────────────────────────────────────────────

    @Test
    fun holdThiefBuildsAClimbForTheWholeWall() {
        val rows = grid(columns = 10, rowCount = 12) // 120 holds
        val route = HoldsThief.route(rows, densityPercent = 50, seed = 7)
        val start = route.filter { it.roleId == HoldRole.START }
        val top = route.filter { it.roleId == HoldRole.FINISH }
        val hands = route.filter { it.roleId == HoldRole.HAND }
        assertEquals(2, start.size)
        assertTrue(start.all { rows.rowOf(it.placementId)!! <= 1 }, "start from the two lowest rows")
        assertEquals((0 until 10).flatMap { listOf(id(10, it), id(11, it)) }.toSet(), ids(top), "every hold of the two top rows")
        assertEquals(49, hands.size, "half of the 98 other holds")
        assertTrue(route.none { it.roleId == HoldRole.FOOT })
        assertEquals(route.size, ids(route).size, "no hold twice")
    }

    @Test
    fun holdThiefIsRepeatablePerSeedAndDrawsEachDensityOnItsOwn() {
        val rows = grid(columns = 10, rowCount = 12)
        assertEquals(HoldsThief.route(rows, 40, seed = 3), HoldsThief.route(rows, 40, seed = 3))
        assertNotEquals(HoldsThief.route(rows, 40, seed = 3), HoldsThief.route(rows, 40, seed = 4))
        val at60 = HoldsThief.route(rows, 60, seed = 3)
        val at10 = HoldsThief.route(rows, 10, seed = 3)
        assertEquals(
            at60.filter { it.roleId == HoldRole.START },
            at10.filter { it.roleId == HoldRole.START },
            "the start stays when the density changes",
        )
        assertEquals(59, HoldsThief.handCount(at60))
        assertEquals(10, HoldsThief.handCount(at10))
        assertEquals(listOf(60, 50, 40, 30, 20, 10), HoldsThief.DENSITIES)
    }

    @Test
    fun stealingTakesOnlyHands() {
        val rows = grid(columns = 10, rowCount = 12)
        val route = HoldsThief.route(rows, 30, seed = 1)
        val hands = HoldsThief.handCount(route)
        val after = HoldsThief.steal(route, 5, Random(2))
        assertEquals(hands - 5, HoldsThief.handCount(after))
        assertEquals(route.filter { it.roleId != HoldRole.HAND }, after.filter { it.roleId != HoldRole.HAND })
        assertTrue(route.containsAll(after))
        assertEquals(0, HoldsThief.handCount(HoldsThief.steal(route, 1_000, Random(2))))
        assertEquals(route, HoldsThief.steal(route, -3, Random(2)))
    }

    @Test
    fun holdThiefOnATinyWallHasNoTopAndNeverFails() {
        assertTrue(HoldsThief.route(WallRows.of(emptyList()), 60, seed = 1).isEmpty())
        val two = HoldsThief.route(grid(columns = 3, rowCount = 2), 60, seed = 1)
        assertTrue(two.none { it.roleId == HoldRole.FINISH })
        assertEquals(2, two.count { it.roleId == HoldRole.START })
    }

    // ── Traverse ──────────────────────────────────────────────────────

    @Test
    fun traverseTempoFollowsTheDifficulty() {
        assertEquals(5000L, TraverseGame.intervalMs(0.0))
        assertEquals(3000L, TraverseGame.intervalMs(1.25))
        assertEquals(1000L, TraverseGame.intervalMs(2.5))
        assertEquals(625L, TraverseGame.intervalMs(3.75))
        assertEquals(250L, TraverseGame.intervalMs(5.0))
        assertEquals(250L, TraverseGame.intervalMs(9.0))
        assertEquals(5000L, TraverseGame.intervalMs(-1.0))
    }

    private val traverseRows = grid(columns = 12, rowCount = 6)

    /** Start on the left, top on the right; feet low, hands higher. */
    private val leftToRight = listOf(
        BoardHold(id(3, 0), HoldRole.START),
        BoardHold(id(3, 11), HoldRole.FINISH),
        BoardHold(id(4, 2), HoldRole.HAND),
        BoardHold(id(0, 1), HoldRole.FOOT),
        BoardHold(id(4, 5), HoldRole.HAND),
        BoardHold(id(0, 4), HoldRole.FOOT),
        BoardHold(id(1, 4), HoldRole.FOOT),
        BoardHold(id(3, 8), HoldRole.HAND),
        BoardHold(id(0, 10), HoldRole.FOOT),
    )

    @Test
    fun traverseOrderRunsFromTheStartTowardsTheTop() {
        assertEquals(
            listOf(id(0, 1), id(4, 2), id(0, 4), id(1, 4), id(4, 5), id(3, 8), id(0, 10)),
            TraverseGame.order(leftToRight, traverseRows).map { it.placementId },
        )
        // The same climb mirrored runs right to left.
        val mirrored = leftToRight.map { BoardHold(it.placementId / 100 * 100 + (11 - it.placementId % 100), it.roleId) }
        assertEquals(
            listOf(id(0, 10), id(4, 9), id(0, 7), id(1, 7), id(4, 6), id(3, 3), id(0, 1)),
            TraverseGame.order(mirrored, traverseRows).map { it.placementId },
        )
    }

    @Test
    fun traverseOfAnOrdinaryClimbRunsUpwards() {
        val climb = listOf(
            BoardHold(id(0, 5), HoldRole.START),
            BoardHold(id(5, 6), HoldRole.FINISH),
            BoardHold(id(3, 2), HoldRole.HAND),
            BoardHold(id(1, 8), HoldRole.HAND),
            BoardHold(id(2, 4), HoldRole.FOOT),
        )
        // Along the line from start to top, which here leans slightly right.
        assertEquals(listOf(id(1, 8), id(2, 4), id(3, 2)), TraverseGame.order(climb, traverseRows).map { it.placementId })
        // Without start and top, a wide set of holds runs left to right ...
        val noEnds = climb.filter { it.roleId == HoldRole.HAND || it.roleId == HoldRole.FOOT }
        assertEquals(listOf(id(3, 2), id(2, 4), id(1, 8)), TraverseGame.order(noEnds, traverseRows).map { it.placementId })
        // ... and a tall one bottom to top.
        val tall = listOf(
            BoardHold(id(5, 1), HoldRole.HAND),
            BoardHold(id(0, 2), HoldRole.FOOT),
            BoardHold(id(3, 0), HoldRole.HAND),
        )
        assertEquals(listOf(id(0, 2), id(3, 0), id(5, 1)), TraverseGame.order(tall, traverseRows).map { it.placementId })
    }

    @Test
    fun traversePacketsAreFeetUpToTheNextHand() {
        val ordered = TraverseGame.order(leftToRight, traverseRows)
        val top = leftToRight.filter { it.roleId == HoldRole.FINISH }
        assertEquals(
            listOf(
                listOf(id(0, 1), id(4, 2)),
                listOf(id(0, 4), id(1, 4), id(4, 5)),
                listOf(id(3, 8)),
                listOf(id(0, 10), id(3, 11)),
            ),
            TraverseGame.packets(ordered, top).map { p -> p.map { it.placementId } },
        )
    }

    @Test
    fun traverseKeepsASlidingWindowAndDropsTheStart() {
        // A long traverse along the bottom rows: one hand and one foot per column.
        val route = buildList {
            add(BoardHold(id(2, 0), HoldRole.START))
            add(BoardHold(id(2, 11), HoldRole.FINISH))
            for (col in 1..10) {
                add(BoardHold(id(0, col), HoldRole.FOOT))
                add(BoardHold(id(3, col), HoldRole.HAND))
            }
        }
        val t = TraverseGame.timeline(route, traverseRows, difficulty = 2.5)
        assertEquals(11, t.steps, "ten hand packets and the top")
        assertEquals((0..11).map { it * 1000L }, t.frames.map { it.atMs })
        assertEquals(setOf(id(2, 0)), ids(t.frames[0].holds))
        assertTrue(id(2, 0) in ids(t.frames[5].holds), "start still lit at packet 5")
        assertTrue(id(2, 0) !in ids(t.frames[6].holds), "start dark from packet 6")
        assertEquals(14, t.frames[7].holds.size, "seven packets of two holds")
        assertTrue(id(3, 1) !in ids(t.frames[8].holds), "packet 1 dark at packet 8")
        assertTrue(id(3, 2) in ids(t.frames[8].holds))
        assertTrue(id(2, 11) in ids(t.frames.last().holds), "the top stays at the end")
        val roles = t.frames.last().holds.associate { it.placementId to it.roleId }
        assertEquals(HoldRole.FOOT, roles[id(0, 10)])
        assertEquals(HoldRole.HAND, roles[id(3, 10)])
    }
}
