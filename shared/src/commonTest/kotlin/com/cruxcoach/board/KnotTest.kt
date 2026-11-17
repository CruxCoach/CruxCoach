package com.cruxcoach.board

import com.cruxcoach.board.KnotBoardFixtures.INCH_M
import com.cruxcoach.board.KnotBoardFixtures.MOON_CELL_M
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.games.Knot
import com.cruxcoach.domain.board.games.KnotCaller
import com.cruxcoach.domain.board.games.KnotColour
import com.cruxcoach.domain.board.games.KnotDraw
import com.cruxcoach.domain.board.games.KnotHandGrades
import com.cruxcoach.domain.board.games.KnotLayout
import com.cruxcoach.domain.board.games.KnotLimb
import com.cruxcoach.domain.board.games.KnotSpacing
import com.cruxcoach.domain.board.games.WallHold
import com.cruxcoach.domain.board.games.WallRows
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnotTest {

    private val aurora = Knot.colours(BoardBrand.KILTER)!!
    private val moon = Knot.colours(BoardBrand.MOONBOARD)!!

    private class Board(val name: String, val rows: WallRows, val metresPerUnit: Double, val colours: List<KnotColour>)

    private val kilter12x12 = Board("Kilter 12x12", KnotBoardFixtures.kilter12x12, INCH_M, aurora)
    private val kilter7x10 = Board("Kilter 7x10", KnotBoardFixtures.kilter7x10, INCH_M, aurora)
    private val moonBoard = Board("MoonBoard", KnotBoardFixtures.moonBoard, MOON_CELL_M, moon)
    private val moonBoardMini = Board("Mini MoonBoard", KnotBoardFixtures.moonBoardMini, MOON_CELL_M, moon)
    private val kilter16x12 = Board("Kilter 16x12", KnotBoardFixtures.kilter16x12, INCH_M, aurora)
    private val boards = listOf(kilter7x10, kilter12x12, kilter16x12, moonBoard, moonBoardMini)

    private fun layout(board: Board, climbers: Int, spacing: KnotSpacing = KnotSpacing.CLOSE, draw: KnotDraw? = null): KnotLayout? =
        Knot.layout(board.rows, climbers, board.colours, board.metresPerUnit, spacing = spacing, draw = draw)

    /** Every board with every number of climbers it takes. */
    private val playable = boards.flatMap { board -> (1..Knot.maxClimbers(board.rows, board.metresPerUnit)).map { board to it } }

    private fun heightM(board: Board, id: Int): Double {
        val lowest = board.rows.holds.minOf { it.y }
        return Knot.LOWEST_ROW_M + (board.rows.holdOf(id)!!.y - lowest) * board.metresPerUnit
    }

    private fun xM(board: Board, id: Int): Double = board.rows.holdOf(id)!!.x * board.metresPerUnit

    // ── Rules ─────────────────────────────────────────────────────────

    @Test
    fun callTimeFallsWithTheLevel() {
        assertEquals(listOf(10_000L, 8_000L, 6_000L, 4_000L, 3_000L), (1..5).map(Knot::callMs))
        assertEquals(10_000L, Knot.callMs(0))
        assertEquals(3_000L, Knot.callMs(9))
    }

    @Test
    fun fieldAndSpotsGrowWithTheClimbers() {
        assertEquals(listOf(1.2, 2.0, 2.8, 3.6, 4.4), (1..5).map { kotlin.math.round(Knot.fieldWidthM(it) * 10) / 10.0 })
        assertEquals(listOf(2 to 1, 3 to 2, 4 to 3, 5 to 4, 6 to 5), (1..5).map { Knot.handSpots(it) to Knot.footSpots(it) })
    }

    @Test
    fun auroraBoardsGetFourColoursWithoutRedOrGreenAndMoonBoardItsThree() {
        listOf(BoardBrand.KILTER, BoardBrand.TENSION, BoardBrand.DECOY, BoardBrand.GRASSHOPPER, BoardBrand.SOILL, BoardBrand.TOUCHSTONE)
            .forEach { assertEquals(listOf(KnotColour.BLUE, KnotColour.YELLOW, KnotColour.MAGENTA, KnotColour.WHITE), Knot.colours(it)) }
        assertEquals(listOf(KnotColour.GREEN, KnotColour.BLUE, KnotColour.RED), Knot.colours(BoardBrand.MOONBOARD))
        // Shiny Wall's codes show colours the app doesn't know, and Quantum shares its lights.
        assertNull(Knot.colours(BoardBrand.SHINYWALL))
        assertNull(Knot.colours(BoardBrand.QUANTUM))
        assertNull(Knot.metresPerUnit(BoardBrand.QUANTUM))
    }

    @Test
    fun colourOrderIsShuffledPerSeedAndKeepsRedAwayFromGreen() {
        val orders = (0L until 200L).map { Knot.colourOrder(aurora, it) }
        orders.forEach { assertEquals(aurora.toSet(), it.toSet()) }
        assertTrue(orders.toSet().size > 10, "only ${orders.toSet().size} different orders")
        assertEquals(Knot.colourOrder(aurora, 7), Knot.colourOrder(aurora, 7))
        val moonOrders = (0L until 200L).map { Knot.colourOrder(moon, it) }
        // Blue is always in the middle, and both sides get green and red.
        moonOrders.forEach { assertEquals(KnotColour.BLUE, it[1]) }
        assertEquals(2, moonOrders.toSet().size)
    }

    // ── Width ─────────────────────────────────────────────────────────

    @Test
    fun climbersAtTheWallFollowTheSpanOfTheHolds() {
        // 0.8 m each: 4 on a 12 ft wide board, 5 on 16 ft, 2 on a 7x10 and a MoonBoard.
        assertEquals(4, Knot.maxClimbers(kilter12x12.rows, INCH_M))
        assertEquals(5, Knot.maxClimbers(kilter16x12.rows, INCH_M))
        assertEquals(2, Knot.maxClimbers(kilter7x10.rows, INCH_M))
        assertEquals(2, Knot.maxClimbers(moonBoard.rows, MOON_CELL_M))
        assertEquals(2, Knot.maxClimbers(moonBoardMini.rows, MOON_CELL_M))
        assertEquals(0, Knot.maxClimbers(WallRows.of(emptyList()), INCH_M))
        // A narrow strip of wall still takes one, and no wall more than the players of a game.
        assertEquals(1, Knot.maxClimbers(WallRows.of(listOf(WallHold(1, 0.0, 0.0), WallHold(2, 4.0, 40.0))), INCH_M))
        assertEquals(Knot.MAX_PLAYERS, Knot.maxClimbers(WallRows.of(listOf(WallHold(1, 0.0, 0.0), WallHold(2, 400.0, 40.0))), INCH_M))
        assertNull(layout(kilter7x10, 3))
        assertNull(layout(moonBoard, 3))
        assertNull(layout(kilter12x12, 5))
        assertNull(layout(kilter16x12, 6))
        assertNull(layout(kilter12x12, 0))
    }

    // ── Field ─────────────────────────────────────────────────────────

    private fun KnotLayout.handSpots(colour: Int) = stripes.indices.filter { pattern[it] == colour }.flatMap { stripes[it].first }
    private fun KnotLayout.footSpots(colour: Int) = stripes.indices.filter { pattern[it] == colour }.flatMap { stripes[it].second }

    @Test
    fun everyColourGetsItsFullSpotsInEverySpacingAndNoBoardLightsMoreThanTheTable() {
        // The table of #154, and the same 8 per climber beyond it.
        val table = mapOf(1 to 12, 2 to 20, 3 to 28, 4 to 36, 5 to 44)
        for ((board, climbers) in playable) for (spacing in KnotSpacing.entries) {
            val name = "${board.name}, $climbers, $spacing"
            val layout = assertNotNull(layout(board, climbers, spacing), name)
            assertEquals(layout.stripes.size, layout.pattern.size, name)
            for (colour in board.colours.indices) {
                assertEquals(Knot.handSpots(climbers), layout.handSpots(colour).size, "$name: hands")
                assertEquals(Knot.footSpots(climbers), layout.footSpots(colour).size, "$name: feet")
            }
            val all = layout.stripes.flatMap { it.first + it.second }
            assertEquals(all.size, all.toSet().size, "$name: a hold used twice")
            assertTrue(all.size <= table.getValue(climbers), "$name: ${all.size} lit")
            all.forEach { assertNotNull(board.rows.holdOf(it)) }
        }
    }

    @Test
    fun handsSitHighFeetLowAndEverythingStaysUnderTwoMetresTwenty() {
        for ((board, climbers) in playable) for (spacing in KnotSpacing.entries) {
            val layout = layout(board, climbers, spacing)!!
            layout.stripes.forEach { (hands, feet) ->
                hands.forEach {
                    val h = heightM(board, it)
                    assertTrue(h in Knot.HANDS_FROM_M..Knot.HANDS_TO_M, "${board.name}, $climbers: hand at $h m")
                }
                feet.forEach {
                    val h = heightM(board, it)
                    assertTrue(h <= Knot.FEET_TO_M, "${board.name}, $climbers: foot at $h m")
                }
            }
        }
    }

    @Test
    fun fieldWidthGrowsWithClimbersAndStaysCentred() {
        for (board in boards) {
            val left = board.rows.holds.minOf { it.x } * board.metresPerUnit
            val right = board.rows.holds.maxOf { it.x } * board.metresPerUnit
            val centre = (left + right) / 2
            var previous = 0.0
            for (climbers in 1..Knot.maxClimbers(board.rows, board.metresPerUnit)) {
                val xs = layout(board, climbers, KnotSpacing.WIDE)!!.spots.map { xM(board, it) }
                val width = xs.max() - xs.min()
                assertTrue(width <= Knot.fieldWidthM(climbers) + 0.01, "${board.name}, $climbers: $width m wide")
                assertTrue(width > previous, "${board.name}, $climbers: not wider than for ${climbers - 1}")
                assertTrue(kotlin.math.abs((xs.max() + xs.min()) / 2 - centre) < 0.3, "${board.name}, $climbers: off centre")
                previous = width
            }
        }
        // Solo on a big wall is about 1.2 m; three on a 12x12 about 2.8 m.
        val solo = layout(kilter12x12, 1)!!.spots.map { xM(kilter12x12, it) }
        assertTrue(solo.max() - solo.min() in 0.6..1.2)
        val three = layout(kilter12x12, 3)!!.spots.map { xM(kilter12x12, it) }
        assertTrue(three.max() - three.min() in 2.2..2.8)
    }

    @Test
    fun stripesRunLeftToRightAndSpotsKeepTheirDistance() {
        for ((board, climbers) in playable) for (spacing in KnotSpacing.entries) {
            val name = "${board.name}, $climbers, $spacing"
            val layout = layout(board, climbers, spacing)!!
            val centres = layout.stripes.map { (hands, feet) -> (hands + feet).map { xM(board, it) }.average() }.filter { !it.isNaN() }
            assertEquals(centres.sorted(), centres, "$name: stripes out of order")
            // Hands keep their distance from every spot; feet, on the few low rows, from each other at least a grid step.
            val hands = layout.stripes.flatMap { it.first }.map { board.rows.holdOf(it)!! }
            val feet = layout.stripes.flatMap { it.second }.map { board.rows.holdOf(it)!! }
            fun closest(a: List<WallHold>, b: List<WallHold>) = a.flatMap { p -> b.filter { it != p }.map { q -> hypot(p.x - q.x, p.y - q.y) * board.metresPerUnit } }.min()
            val handGap = closest(hands, hands + feet)
            assertTrue(handGap >= Knot.SPOT_SPACING_M - 0.03, "$name: hands $handGap m from a spot")
            val footGap = closest(feet, feet)
            assertTrue(footGap >= 0.19, "$name: feet $footGap m apart")
        }
    }

    @Test
    fun theSameWallGivesTheSameLayoutAndBigHoldsWinWhenAboutAsNear() {
        assertEquals(layout(kilter12x12, 2), layout(kilter12x12, 2))
        val plain = layout(kilter12x12, 1)!!
        // Every other hold is big: wherever one is about as near as the chosen hold, it takes the spot.
        val big = kilter12x12.rows.holds.map { it.placementId }.filter { it !in plain.spots }.toSet()
        val withTypes = Knot.layout(kilter12x12.rows, 1, aurora, INCH_M, bigHolds = big)!!
        assertTrue(withTypes.spots.count { it in big } > withTypes.spots.size / 2, "${withTypes.spots.count { it in big }} of ${withTypes.spots.size} big")
    }

    @Test
    fun aWallWithoutRoomForHandsHasNoField() {
        // Two low rows only: feet, but nowhere for the hands.
        val low = WallRows.of((0 until 40).map { WallHold(it, (it % 20) * 8.0, (it / 20) * 8.0) })
        assertEquals(4, Knot.maxClimbers(low, INCH_M))
        assertNull(Knot.layout(low, 1, aurora, INCH_M))
        val tall = WallRows.of(low.holds + (100 until 200).map { WallHold(it, (it % 20) * 8.0, 16.0 + ((it - 100) / 20) * 16.0) })
        assertNotNull(Knot.layout(tall, 1, aurora, INCH_M))
    }

    @Test
    fun layoutTakesColoursFromTheLeftAgainAndAgain() {
        val layout = layout(kilter7x10, 2)!!
        val order = Knot.colourOrder(aurora, 3)
        val field = layout.field(order)
        assertEquals(order, field.colours)
        assertEquals(List(layout.stripes.size) { order[it % 4] }, field.stripes.map { it.colour })
        assertEquals(20, field.spots.size)
        // A hand call blinks only that colour's hand spots, a foot call only its foot spots.
        val hands = field.without(order[0], hands = true)
        assertEquals(17, hands.size)
        assertEquals(layout.footSpots(0).toSet(), hands.filterValues { it == order[0] }.keys)
        val feet = field.without(order[0], hands = false)
        assertEquals(18, feet.size)
        assertEquals(layout.handSpots(0).toSet(), feet.filterValues { it == order[0] }.keys)
    }

    @Test
    fun whereRedMustNotTouchGreenTheColoursRunThereAndBack() {
        assertEquals(listOf(0, 1, 2), Knot.pattern(moon, 3))
        assertEquals(listOf(0, 1, 2, 1, 0, 1, 2), Knot.pattern(moon, 7))
        assertEquals(listOf(0, 1, 2, 3, 0, 1), Knot.pattern(aurora, 6))
        for (seed in 0L until 20L) {
            val field = layout(moonBoard, 2)!!.field(Knot.colourOrder(moon, seed))
            field.stripes.zipWithNext().forEach { (a, b) -> assertTrue(setOf(a.colour, b.colour) != setOf(KnotColour.RED, KnotColour.GREEN)) }
        }
    }

    // ── Spacing: the reach ────────────────────────────────────────────

    /** The reach of [layout]'s hand spots, as [Knot.reach] measures it, on the field of [climbers] on [board]. */
    private fun reachOf(board: Board, climbers: Int, layout: KnotLayout): Pair<Double, Double> {
        val left = board.rows.holds.minOf { it.x } * board.metresPerUnit
        val right = board.rows.holds.maxOf { it.x } * board.metresPerUnit
        val width = minOf(Knot.fieldWidthM(climbers), right - left + 0.2)
        val spots = layout.stripes.indices.flatMap { i -> layout.stripes[i].first.map { xM(board, it) to layout.pattern[i] } }
        return Knot.reach((left + right) / 2 - width / 2, width, climbers, spots, board.colours.size)
    }

    @Test
    fun spacingSetsTheReachForEveryNumberOfClimbersOnEveryBoard() {
        for ((board, climbers) in playable) {
            val reaches = KnotSpacing.entries.map { spacing ->
                val name = "${board.name}, $climbers, $spacing"
                val (farthest, span) = reachOf(board, climbers, layout(board, climbers, spacing)!!)
                println("Knot $name: farthest colour ${(farthest * 100).toInt()} cm, two farthest ${(span * 100).toInt()} cm apart")
                // About the spacing's reach: never far beyond it.
                assertTrue(farthest <= spacing.reachM + 0.15, "$name: farthest colour $farthest m")
                assertTrue(span <= spacing.spanM + 0.2, "$name: two farthest $span m apart")
                farthest
            }
            // Wider reaches further wherever the field has room for it. Solo the 1.2 m field keeps every colour close;
            // the MoonBoard's three colours, with blue twice between red and green, come out the same in every spacing.
            if (climbers >= 2 && board.colours == aurora) {
                assertTrue(reaches[0] < reaches[2], "${board.name}, $climbers: close ${reaches[0]}, wide ${reaches[2]}")
            }
        }
    }

    @Test
    fun theStripeCountFollowsTheReachAtTheStripesMiddles() {
        // Two climbers on 2.0 m: close needs the colours almost twice, wide not quite twice.
        assertEquals(7, Knot.stripeCount(2.0, 2, aurora, KnotSpacing.CLOSE))
        assertEquals(5, Knot.stripeCount(2.0, 2, aurora, KnotSpacing.WIDE))
        for (climbers in 1..5) for (spacing in KnotSpacing.entries) {
            val width = Knot.fieldWidthM(climbers)
            val count = Knot.stripeCount(width, climbers, aurora, spacing)
            val middles = Knot.pattern(aurora, count).mapIndexed { i, colour -> width / count * (i + 0.5) to colour }
            val (farthest, span) = Knot.reach(0.0, width, climbers, middles, 4)
            assertTrue(farthest <= spacing.reachM + 1e-6 && span <= spacing.spanM + 1e-6, "$climbers, $spacing")
            // One stripe fewer would reach too far.
            if (count > 4) {
                val fewer = Knot.pattern(aurora, count - 1).mapIndexed { i, colour -> width / (count - 1) * (i + 0.5) to colour }
                val (f, s) = Knot.reach(0.0, width, climbers, fewer, 4)
                assertTrue(f > spacing.reachM || s > spacing.spanM, "$climbers, $spacing: ${count - 1} would do")
            }
        }
    }

    // ── A fresh field every game ──────────────────────────────────────

    /** Hand grades for the 12x12, from 10 to 30 at random. */
    private val randomGrades = kotlin.random.Random(7).let { random -> kilter12x12.rows.holds.associate { it.placementId to 10.0 + random.nextDouble() * 20 } }

    /** Where the field of [climbers] on [board] begins, and how wide it is. */
    private fun fieldOf(board: Board, climbers: Int): Pair<Double, Double> {
        val left = board.rows.holds.minOf { it.x } * board.metresPerUnit
        val right = board.rows.holds.maxOf { it.x } * board.metresPerUnit
        val width = minOf(Knot.fieldWidthM(climbers), right - left + 0.2)
        return (left + right) / 2 - width / 2 to width
    }

    /** How far apart the easiest and hardest colour, and the easiest and hardest climber's part, are on average. */
    private fun spreads(layout: KnotLayout, climbers: Int, grades: Map<Int, Double>): Pair<Double, Double> {
        val (start, width) = fieldOf(kilter12x12, climbers)
        val colours = aurora.indices.map { colour -> layout.handSpots(colour).map { grades.getValue(it) }.average() }
        val parts = layout.stripes.flatMap { it.first }
            .groupBy({ ((xM(kilter12x12, it) - start) / (width / climbers)).toInt().coerceIn(0, climbers - 1) }, { grades.getValue(it) })
            .values.map { it.average() }
        return (colours.max() - colours.min()) to (parts.max() - parts.min())
    }

    @Test
    fun aDrawGivesAFreshFieldPerSeedAwayFromTheLastOne() {
        val first = layout(kilter12x12, 2, draw = KnotDraw(1, randomGrades))!!
        assertEquals(first, layout(kilter12x12, 2, draw = KnotDraw(1, randomGrades)))
        assertNotEquals(first.spots, layout(kilter12x12, 2, draw = KnotDraw(2, randomGrades))!!.spots)
        assertNotEquals(layout(kilter12x12, 2)!!.spots, first.spots)
        // The next game keeps away from this one's holds.
        val next = layout(kilter12x12, 2, draw = KnotDraw(2, randomGrades, avoid = first.spots))!!
        assertTrue((next.spots intersect first.spots).size <= first.spots.size / 10, "${(next.spots intersect first.spots).size} again")
        // A drawn field keeps the rules of every field.
        for (colour in aurora.indices) {
            assertEquals(Knot.handSpots(2), next.handSpots(colour).size)
            assertEquals(Knot.footSpots(2), next.footSpots(colour).size)
        }
    }

    @Test
    fun theHardestFifthOfTheRatedHandHoldsStaysDarkAndTheColoursAndPartsComeOutAboutEven() {
        for (climbers in 1..4) for (seed in 0L until 5L) {
            val name = "$climbers, seed $seed"
            val (start, width) = fieldOf(kilter12x12, climbers)
            val inField = randomGrades.keys.filter { id ->
                heightM(kilter12x12, id) in Knot.HANDS_FROM_M..Knot.HANDS_TO_M && xM(kilter12x12, id) in start..start + width
            }.sortedByDescending { randomGrades.getValue(it) }
            val hardest = inField.take(inField.size / 5).toSet()
            val drawn = layout(kilter12x12, climbers, KnotSpacing.MEDIUM, KnotDraw(seed, randomGrades))!!
            assertTrue(drawn.stripes.flatMap { it.first }.none { it in hardest }, "$name: one of the hardest fifth")
            // Fairer than taking the nearest holds: the colours, and with several climbers the parts, come out closer.
            val (colours, parts) = spreads(drawn, climbers, randomGrades)
            val (plainColours, plainParts) = spreads(layout(kilter12x12, climbers, KnotSpacing.MEDIUM)!!, climbers, randomGrades)
            println("Knot draw $name: colours ${(colours * 10).toInt() / 10.0} (nearest ${(plainColours * 10).toInt() / 10.0}), parts ${(parts * 10).toInt() / 10.0} (nearest ${(plainParts * 10).toInt() / 10.0})")
            assertTrue(colours + parts < plainColours + plainParts, "$name: no fairer than the nearest holds")
            assertTrue(colours <= 4.0 && parts <= 4.0, "$name: colours $colours, parts $parts apart")
        }
    }

    /** The smallest distance between two spots of [layout] on [board]. */
    private fun closestGap(board: Board, layout: KnotLayout): Double {
        val spots = layout.spots.map { board.rows.holdOf(it)!! }
        return spots.flatMapIndexed { i, a -> spots.drop(i + 1).map { b -> hypot(a.x - b.x, a.y - b.y) * board.metresPerUnit } }.min()
    }

    @Test
    fun aDrawNeverPutsSpotsCloserTogetherThanTheNearestHolds() {
        for ((board, climbers) in playable) {
            val grades = kotlin.random.Random(climbers).let { random -> board.rows.holds.associate { it.placementId to 10.0 + random.nextDouble() * 20 } }
            for (spacing in KnotSpacing.entries) {
                // Where the nearest holds keep the spacing, a draw keeps it too; where they can't, a draw comes no closer.
                val nearest = minOf(closestGap(board, layout(board, climbers, spacing)!!), Knot.SPOT_SPACING_M - 0.02)
                for (seed in 0L until 4L) {
                    // A first game, and the next one, which keeps away from the first one's holds.
                    val name = "${board.name}, $climbers, $spacing, seed $seed"
                    val first = assertNotNull(layout(board, climbers, spacing, KnotDraw(seed, grades)), name)
                    val next = assertNotNull(layout(board, climbers, spacing, KnotDraw(seed + 100, grades, avoid = first.spots)), name)
                    for (drawn in listOf(first, next)) {
                        val gap = closestGap(board, drawn)
                        assertTrue(gap >= nearest - 1e-9, "$name: spots ${(gap * 100).toInt()} cm apart, the nearest holds ${(nearest * 100).toInt()} cm")
                    }
                }
            }
        }
    }

    @Test
    fun handGradesAverageTheClimbsThatUseAHoldAsAHandHold() {
        val grades = KnotHandGrades(minClimbs = 2)
        // Hold 1 as a hand in grades 10 and 20, hold 2 as a foot (15) and a hand once, hold 3 in a hole-numbered frame.
        grades.add("p1r12p2r15", 10.0)
        grades.add("p1r13p2r13", 20.0)
        grades.add("h3p13", 25.0)
        assertEquals(mapOf(1 to 15.0), grades.grades())
        // Aurora's own role numbers and the MoonBoard's count the same way.
        val other = KnotHandGrades(minClimbs = 1)
        other.add("p5r2p6r4", 12.0)
        other.add("p7r43", 14.0)
        assertEquals(mapOf(5 to 12.0, 7 to 14.0), other.grades())
    }

    // ── MoonBoard ─────────────────────────────────────────────────────

    @Test
    fun moonBoardFieldFitsOneFrameOfEveryBoxInEveryLedMode() {
        for (climbers in 1..2) {
            val field = layout(moonBoard, climbers)!!.field(Knot.colourOrder(moon, 1))
            val frames = Knot.moonBoardFrames(field.spots)
            for (variant in MoonBoardVariant.entries.filter { it.gridRows == 18 }) {
                for (mode in MoonBoardLedMode.entries) {
                    assertTrue(MoonBoardFrameEncoder.fits(frames, variant, mode), "$climbers on $variant, $mode")
                }
            }
            val mini = layout(moonBoardMini, climbers)!!.field(moon)
            for (variant in MoonBoardVariant.entries.filter { it.gridRows == 12 }) {
                for (mode in MoonBoardLedMode.entries) {
                    assertTrue(MoonBoardFrameEncoder.fits(Knot.moonBoardFrames(mini.spots), variant, mode), "$climbers on $variant, $mode")
                }
            }
        }
    }

    @Test
    fun moonBoardFramesShowEachColourAsItsRole() {
        assertEquals("p1r42p2r43p3r44", Knot.moonBoardFrames(mapOf(3 to KnotColour.RED, 1 to KnotColour.GREEN, 2 to KnotColour.BLUE)))
    }

    // ── Calls ─────────────────────────────────────────────────────────

    @Test
    fun callsGoRoundTheClimbersInAFixedOrder() {
        val caller = KnotCaller(seed = 5, places = 3, colours = aurora)
        val all = setOf(0, 1, 2)
        assertEquals(listOf(0, 1, 2, 0, 1, 2), (1..6).map { caller.next(all)!!.climber })
        // Climber 1 is out: the order skips them and keeps going round.
        assertEquals(listOf(0, 2, 0, 2), (1..4).map { caller.next(setOf(0, 2))!!.climber })
        assertEquals(11, caller.next(setOf(2))!!.number)
        assertNull(caller.next(emptySet()))
    }

    @Test
    fun callsFollowThePartsWhenTheNextInLineStepsIn() {
        val caller = KnotCaller(seed = 5, places = 2, colours = aurora)
        assertEquals(listOf(0, 1, 0), (1..3).map { caller.next(mapOf(0 to 0, 1 to 1))!!.climber })
        // Climber 0 came off the left part and climber 2 took it: the order goes on with the right part, then 2.
        assertEquals(listOf(1, 2, 1), (1..3).map { caller.next(mapOf(0 to 2, 1 to 1))!!.climber })
        // Until climber 3 is in position on the right, the calls stay with 2.
        assertEquals(listOf(2, 2), (1..2).map { caller.next(mapOf(0 to 2))!!.climber })
        assertEquals(3, caller.next(mapOf(0 to 2, 1 to 3))!!.climber)
    }

    @Test
    fun aClimberNeverGetsTheSameLimbAndColourTwiceInARow() {
        for (seed in 0L until 50L) {
            for (climbers in Knot.PLAYERS) {
                val caller = KnotCaller(seed, climbers, moon)
                val calls = (1..300).map { caller.next((0 until climbers).toSet())!! }
                calls.groupBy { it.climber }.values.forEach { own ->
                    own.zipWithNext().forEach { (a, b) -> assertNotEquals(a.limb to a.colour, b.limb to b.colour) }
                }
            }
        }
    }

    @Test
    fun callsComeFromTheSeedAndUseEveryLimbAndColour() {
        fun calls(seed: Long) = KnotCaller(seed, 2, aurora).let { c -> (1..200).map { c.next(setOf(0, 1))!! } }
        assertEquals(calls(42), calls(42))
        assertNotEquals(calls(42), calls(43))
        val many = calls(42)
        assertEquals(KnotLimb.entries.toSet(), many.map { it.limb }.toSet())
        assertEquals(aurora.toSet(), many.map { it.colour }.toSet())
        assertEquals((1..200).toList(), many.map { it.number })
    }
}
