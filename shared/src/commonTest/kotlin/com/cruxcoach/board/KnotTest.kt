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
import com.cruxcoach.domain.board.games.KnotHolds
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

    /** A board as the app plays it: Kilter's screw-ons ([feetOnly]) never take a hand spot. */
    private class Board(val name: String, val rows: WallRows, val metresPerUnit: Double, val colours: List<KnotColour>, val feetOnly: Set<Int> = emptySet())

    private val screwOns = KnotBoardFixtures.kilterScrewOns
    private val kilter12x12 = Board("Kilter 12x12", KnotBoardFixtures.kilter12x12, INCH_M, aurora, screwOns)
    private val kilter7x10 = Board("Kilter 7x10", KnotBoardFixtures.kilter7x10, INCH_M, aurora, screwOns)
    private val moonBoard = Board("MoonBoard", KnotBoardFixtures.moonBoard, MOON_CELL_M, moon)
    private val moonBoardMini = Board("Mini MoonBoard", KnotBoardFixtures.moonBoardMini, MOON_CELL_M, moon)
    private val kilter16x12 = Board("Kilter 16x12", KnotBoardFixtures.kilter16x12, INCH_M, aurora, screwOns)
    private val boards = listOf(kilter7x10, kilter12x12, kilter16x12, moonBoard, moonBoardMini)

    private fun layout(
        board: Board,
        climbers: Int,
        spacing: KnotSpacing = KnotSpacing.CLOSE,
        draw: KnotDraw? = null,
        holds: KnotHolds = KnotHolds.NORMAL,
    ): KnotLayout? = Knot.layout(board.rows, climbers, board.colours, board.metresPerUnit, spacing = spacing, draw = draw, holds = holds, feetOnly = board.feetOnly)

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
    fun theFieldIsSixtyCentimetresPerClimberAndAtLeastOnePointTwoMetres() {
        assertEquals(listOf(1.2, 1.2, 1.8, 2.4, 3.0), (1..5).map { kotlin.math.round(Knot.fieldWidthM(it) * 10) / 10.0 })
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

    /**
     * The fewest hand and foot spots of one colour that a climber of [layout] on [board] has within reach, over every
     * climber and colour; hand spots only on holds that aren't [Board.feetOnly].
     */
    private fun coverage(board: Board, layout: KnotLayout, spacing: KnotSpacing): Pair<Int, Int> {
        val (start, width) = fieldOf(board, layout.climbers, layout.offsetM)
        fun least(hands: Boolean): Int = (0 until layout.climbers).minOf { part ->
            val middle = start + width / layout.climbers * (part + 0.5)
            board.colours.indices.minOf { colour ->
                layout.stripes.indices.filter { layout.pattern[it] == colour }
                    .flatMap { if (hands) layout.stripes[it].first.filter { id -> id !in board.feetOnly } else layout.stripes[it].second }
                    .count { kotlin.math.abs(xM(board, it) - middle) <= spacing.reachM + maxOf(Knot.REACH_SLACK_M, width / layout.stripes.size / 2) + 1e-9 }
            }
        }
        return least(true) to least(false)
    }

    @Test
    fun everyClimberHasTheStepsSpotsOfEachColourWithinReach() {
        var lit = 0
        for ((board, climbers) in playable) for (spacing in KnotSpacing.entries) for (holds in KnotHolds.entries) {
            val name = "${board.name}, $climbers, $spacing, $holds"
            val layout = layout(board, climbers, spacing, holds = holds) ?: continue
            lit++
            assertEquals(layout.stripes.size, layout.pattern.size, name)
            val (hands, feet) = coverage(board, layout, spacing)
            assertTrue(hands >= holds.hands && feet >= holds.feet, "$name: $hands + $feet within reach")
            val all = layout.stripes.flatMap { it.first + it.second }
            assertEquals(all.size, all.toSet().size, "$name: a hold used twice")
            all.forEach { assertNotNull(board.rows.holdOf(it)) }
        }
        assertTrue(lit > 0)
        // Normal fits every Kilter board and number of climbers at every spacing; more holds mean more lit.
        for ((board, climbers) in playable.filter { it.first.colours == aurora }) for (spacing in KnotSpacing.entries) {
            val name = "${board.name}, $climbers, $spacing"
            val normal = assertNotNull(layout(board, climbers, spacing, holds = KnotHolds.NORMAL), name).spots.size
            layout(board, climbers, spacing, holds = KnotHolds.FEW)?.let { assertTrue(it.spots.size < normal, "$name: Few") }
            layout(board, climbers, spacing, holds = KnotHolds.MANY)?.let { assertTrue(it.spots.size > normal, "$name: Many") }
        }
    }

    @Test
    fun handsSitHighFeetLowAndEverythingStaysUnderTwoMetresTwenty() {
        for ((board, climbers) in playable) for (spacing in KnotSpacing.entries) {
            val layout = layout(board, climbers, spacing) ?: continue
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
                val xs = (layout(board, climbers, KnotSpacing.WIDE) ?: layout(board, climbers, KnotSpacing.MEDIUM)!!).spots.map { xM(board, it) }
                val width = xs.max() - xs.min()
                assertTrue(width <= Knot.fieldWidthM(climbers) + 0.01, "${board.name}, $climbers: $width m wide")
                // One and two climbers share the narrowest field.
                if (climbers > 2) assertTrue(width > previous, "${board.name}, $climbers: not wider than for ${climbers - 1}")
                assertTrue(kotlin.math.abs((xs.max() + xs.min()) / 2 - centre) < 0.3, "${board.name}, $climbers: off centre")
                previous = width
            }
        }
        // Solo and two on a big wall are about 1.2 m; three on a 12x12 about 1.8 m.
        val solo = layout(kilter12x12, 1)!!.spots.map { xM(kilter12x12, it) }
        assertTrue(solo.max() - solo.min() in 0.6..1.2)
        val three = layout(kilter12x12, 3)!!.spots.map { xM(kilter12x12, it) }
        assertTrue(three.max() - three.min() in 1.3..1.8)
    }

    @Test
    fun aFieldThatDoesNotFitInTheMiddleMovesALittleToTheSide() {
        // Close for two: six stripes of 20 cm. Centred on the 12x12 and the 7x10, a column of holds falls on the middle
        // stripes' edge, and one of them gets a single column with one foot hold.
        for (board in listOf(kilter12x12, kilter7x10)) {
            val moved = assertNotNull(layout(board, 2, KnotSpacing.CLOSE), board.name)
            assertTrue(moved.offsetM != 0.0 && kotlin.math.abs(moved.offsetM) <= Knot.FIELD_SHIFT_M + 1e-9, "${board.name}: ${moved.offsetM} m")
            val (hands, feet) = coverage(board, moved, KnotSpacing.CLOSE)
            assertTrue(hands >= KnotHolds.NORMAL.hands && feet >= KnotHolds.NORMAL.feet, "${board.name}: $hands + $feet within reach")
            // The spots and the climbers' parts move with it.
            val (start, width) = Knot.fieldSpan(board.rows, 2, board.metresPerUnit, moved.offsetM)
            moved.spots.forEach { assertTrue(xM(board, it) in start - 1e-9..start + width + 1e-9, "${board.name}: spot outside the field") }
        }
        // Where the middle fits, the field stays there.
        assertEquals(0.0, layout(kilter12x12, 2, KnotSpacing.MEDIUM)!!.offsetM)
        assertEquals(0.0, layout(moonBoard, 2, KnotSpacing.CLOSE)!!.offsetM)
    }

    @Test
    fun stripesRunLeftToRightAndSpotsKeepTheirDistance() {
        for ((board, climbers) in playable) for (spacing in KnotSpacing.entries) {
            val name = "${board.name}, $climbers, $spacing"
            val layout = layout(board, climbers, spacing) ?: continue
            val centres = layout.stripes.map { (hands, feet) -> (hands + feet).map { xM(board, it) }.average() }.filter { !it.isNaN() }
            assertEquals(centres.sorted(), centres, "$name: stripes out of order")
            // No two lit holds touch; a colour's hand spots keep 30 cm from each other.
            val spots = layout.stripes.flatMapIndexed { i, (hands, feet) -> hands.map { Triple(board.rows.holdOf(it)!!, layout.pattern[i], true) } + feet.map { Triple(board.rows.holdOf(it)!!, layout.pattern[i], false) } }
            for ((a, colour, hand) in spots) for ((b, other, otherHand) in spots) {
                if (a === b) continue
                val d = hypot(a.x - b.x, a.y - b.y) * board.metresPerUnit
                assertTrue(d >= Knot.MIN_GAP_M - 0.03, "$name: two spots $d m apart")
                if (hand && otherHand && colour == other) assertTrue(d >= Knot.SPOT_SPACING_M - 0.03, "$name: hands of one colour $d m apart")
            }
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
        val layout = layout(kilter7x10, 2, KnotSpacing.MEDIUM)!!
        val order = Knot.colourOrder(aurora, 3)
        val field = layout.field(order)
        assertEquals(order, field.colours)
        assertEquals(List(layout.stripes.size) { order[it % 4] }, field.stripes.map { it.colour })
        assertEquals(layout.spots.size, field.spots.size)
        // A hand call blinks only that colour's hand spots, a foot call only its foot spots.
        val hands = field.without(order[0], hands = true)
        assertEquals(field.spots.size - layout.handSpots(0).size, hands.size)
        assertEquals(layout.footSpots(0).toSet(), hands.filterValues { it == order[0] }.keys)
        val feet = field.without(order[0], hands = false)
        assertEquals(field.spots.size - layout.footSpots(0).size, feet.size)
        assertEquals(layout.handSpots(0).toSet(), feet.filterValues { it == order[0] }.keys)
    }

    @Test
    fun whereRedMustNotTouchGreenTheColoursRunThereAndBack() {
        assertEquals(listOf(0, 1, 2), Knot.pattern(moon, 3))
        assertEquals(listOf(0, 1, 2, 1, 0, 1, 2), Knot.pattern(moon, 7))
        assertEquals(listOf(0, 1, 2, 3, 0, 1), Knot.pattern(aurora, 6))
        for (seed in 0L until 20L) {
            val field = layout(moonBoard, 2, KnotSpacing.MEDIUM)!!.field(Knot.colourOrder(moon, seed))
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
        return Knot.reach((left + right) / 2 - width / 2 + layout.offsetM, width, climbers, spots, board.colours.size)
    }

    @Test
    fun spacingSetsTheReachForEveryNumberOfClimbersOnEveryBoard() {
        for ((board, climbers) in playable) {
            val reaches = KnotSpacing.entries.mapNotNull { spacing ->
                val name = "${board.name}, $climbers, $spacing"
                val (farthest, span) = reachOf(board, climbers, layout(board, climbers, spacing) ?: return@mapNotNull null)
                println("Knot $name: farthest colour ${(farthest * 100).toInt()} cm, two farthest ${(span * 100).toInt()} cm apart")
                // About the spacing's reach: never far beyond it.
                assertTrue(farthest <= spacing.reachM + 0.15, "$name: farthest colour $farthest m")
                assertTrue(span <= spacing.spanM + 0.2, "$name: two farthest $span m apart")
                farthest
            }
            // Wider reaches further wherever the field has room for it. Solo the 1.2 m field keeps every colour close;
            // the MoonBoard's three colours, with blue twice between red and green, come out the same in every spacing.
            if (climbers >= 2 && board.colours == aurora && reaches.size == KnotSpacing.entries.size) {
                assertTrue(reaches[0] < reaches[2], "${board.name}, $climbers: close ${reaches[0]}, wide ${reaches[2]}")
            }
        }
    }

    @Test
    fun aStripeWithoutHoldsMustNotLeaveAColourOutOfReach() {
        // Three climbers at Wide on the 12x12: five stripes of 36 cm from about 37 inches on, the colours 0 1 2 3 0. The
        // last stripe loses its hand holds, so the right climber's nearest hand spot of colour 0 lies in the first
        // stripe, more than a metre away.
        val gap = WallRows.of(kilter12x12.rows.holds.filterNot { it.x in 90.0..112.0 && it.y in 37.0..92.0 })
        val board = Board("12x12 without hand holds in the last stripe", gap, INCH_M, aurora, screwOns)
        assertNotNull(layout(kilter12x12, 3, KnotSpacing.WIDE))
        assertNull(layout(board, 3, KnotSpacing.WIDE))
        assertNull(layout(board, 3, KnotSpacing.WIDE, KnotDraw(1, randomGrades)))
        // Every field that does light keeps each colour's hand and foot spots within reach of every climber.
        for ((b, climbers) in playable + (board to 1)) for (spacing in KnotSpacing.entries) {
            val field = layout(b, climbers, spacing) ?: continue
            val left = b.rows.holds.minOf { it.x } * b.metresPerUnit
            val right = b.rows.holds.maxOf { it.x } * b.metresPerUnit
            val width = minOf(Knot.fieldWidthM(climbers), right - left + 0.2)
            for (hands in listOf(true, false)) {
                val spots = field.stripes.indices.flatMap { i -> (if (hands) field.stripes[i].first else field.stripes[i].second).map { xM(b, it) to field.pattern[i] } }
                val (farthest, _) = Knot.reach((left + right) / 2 - width / 2 + field.offsetM, width, climbers, spots, b.colours.size)
                val slack = maxOf(Knot.REACH_SLACK_M, width / field.stripes.size / 2)
                assertTrue(farthest <= spacing.reachM + slack + 1e-9, "${b.name}, $climbers, $spacing: ${if (hands) "hand" else "foot"} $farthest m")
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

    /** Where the field of [climbers] on [board] begins, [offsetM] right of the middle, and how wide it is. */
    private fun fieldOf(board: Board, climbers: Int, offsetM: Double): Pair<Double, Double> {
        val left = board.rows.holds.minOf { it.x } * board.metresPerUnit
        val right = board.rows.holds.maxOf { it.x } * board.metresPerUnit
        val width = minOf(Knot.fieldWidthM(climbers), right - left + 0.2)
        return (left + right) / 2 - width / 2 + offsetM to width
    }

    /** How far apart the easiest and hardest colour, and the easiest and hardest climber's part, are on average. */
    private fun spreads(layout: KnotLayout, climbers: Int, grades: Map<Int, Double>): Pair<Double, Double> {
        val (start, width) = fieldOf(kilter12x12, climbers, layout.offsetM)
        val colours = aurora.indices.map { colour -> layout.handSpots(colour).map { grades.getValue(it) }.average() }
        val parts = layout.stripes.flatMap { it.first }
            .groupBy({ ((xM(kilter12x12, it) - start) / (width / climbers)).toInt().coerceIn(0, climbers - 1) }, { grades.getValue(it) })
            .values.map { it.average() }
        return (colours.max() - colours.min()) to (parts.max() - parts.min())
    }

    @Test
    fun aDrawGivesAFreshFieldPerSeedAwayFromTheLastOne() {
        val medium = KnotSpacing.MEDIUM
        val first = layout(kilter12x12, 2, medium, KnotDraw(1, randomGrades))!!
        assertEquals(first, layout(kilter12x12, 2, medium, KnotDraw(1, randomGrades)))
        assertNotEquals(first.spots, layout(kilter12x12, 2, medium, KnotDraw(2, randomGrades))!!.spots)
        assertNotEquals(layout(kilter12x12, 2, medium)!!.spots, first.spots)
        // The next game keeps away from this one's holds, as far as the bolt-ons in the 1.2 m field allow.
        val next = layout(kilter12x12, 2, medium, KnotDraw(2, randomGrades, avoid = first.spots))!!
        assertTrue((next.spots intersect first.spots).size <= first.spots.size / 5, "${(next.spots intersect first.spots).size} again")
        // A drawn field keeps the rules of every field.
        val (hands, feet) = coverage(kilter12x12, next, medium)
        assertTrue(hands >= KnotHolds.NORMAL.hands && feet >= KnotHolds.NORMAL.feet)
    }

    @Test
    fun twoGamesInARowGetDifferentFieldsInEveryStep() {
        for ((board, climbers) in playable.filter { it.first == kilter12x12 || it.first == kilter16x12 }) {
            val grades = kotlin.random.Random(climbers).let { random -> board.rows.holds.associate { it.placementId to 10.0 + random.nextDouble() * 20 } }
            for (spacing in KnotSpacing.entries) for (holds in KnotHolds.entries) {
                val name = "${board.name}, $climbers, $spacing, $holds"
                val first = layout(board, climbers, spacing, KnotDraw(1, grades), holds) ?: continue
                val next = layout(board, climbers, spacing, KnotDraw(2, grades, avoid = first.spots), holds)!!
                val shared = (first.spots intersect next.spots).size
                println("Knot next game $name: $shared of ${first.spots.size} again")
                // A different field. Many lights 20 holds per metre of field and more: the wall leaves little choice
                // there, and the next game may share most holds, or all of them.
                if (holds == KnotHolds.MANY) continue
                assertNotEquals(first.spots, next.spots, name)
                assertTrue(shared * 4 <= first.spots.size * 3, "$name: $shared of ${first.spots.size} again")
            }
        }
    }

    @Test
    fun theHardestFifthOfTheRatedHandHoldsStaysDarkAndTheColoursAndPartsComeOutAboutEven() {
        // The screw-ons rated hardest of all: counted with the hand holds, they would fill the hardest fifth.
        val grades = randomGrades.mapValues { (id, grade) -> if (id in screwOns) 40.0 else grade }
        for (climbers in 1..4) for (seed in 0L until 5L) {
            val name = "$climbers, seed $seed"
            val drawn = layout(kilter12x12, climbers, KnotSpacing.MEDIUM, KnotDraw(seed, grades))!!
            val (start, width) = fieldOf(kilter12x12, climbers, drawn.offsetM)
            val inField = grades.keys.filter { id ->
                id !in screwOns && heightM(kilter12x12, id) in Knot.HANDS_FROM_M..Knot.HANDS_TO_M && xM(kilter12x12, id) in start..start + width
            }.sortedByDescending { grades.getValue(it) }
            val hardest = inField.take(inField.size / 5).toSet()
            // Only where nothing else keeps the spacing: at most one.
            assertTrue(drawn.stripes.flatMap { it.first }.count { it in hardest } <= 1, "$name: the hardest fifth")
            // About even: the colours, and with several climbers the parts. On the 1.2 m field for one or two, with
            // bolt-ons only, few hand holds are left to choose from.
            val (colours, parts) = spreads(drawn, climbers, grades)
            val (plainColours, plainParts) = spreads(layout(kilter12x12, climbers, KnotSpacing.MEDIUM)!!, climbers, grades)
            println("Knot draw $name: colours ${(colours * 10).toInt() / 10.0} (nearest ${(plainColours * 10).toInt() / 10.0}), parts ${(parts * 10).toInt() / 10.0} (nearest ${(plainParts * 10).toInt() / 10.0})")
            assertTrue(colours <= 6.5 && parts <= 5.0, "$name: colours $colours, parts $parts apart")
        }
    }

    @Test
    fun handSpotsGoOnHandHoldsOnlyInEveryGame() {
        // The 12x12 with its catalogue at 40°: neither the screw-ons nor the bolt-ons that are mostly a foot take a hand
        // spot, and the catalogue rates only the hand holds.
        val feet = screwOns + KnotBoardFixtures.kilter12x12FeetAt40
        val board = Board("Kilter 12x12 at 40°", KnotBoardFixtures.kilter12x12, INCH_M, aurora, feet)
        val grades = randomGrades.filterKeys { it !in feet }
        var games = 0
        for (climbers in 1..Knot.maxClimbers(board.rows, INCH_M)) for (spacing in KnotSpacing.entries) for (holds in KnotHolds.entries) {
            val name = "$climbers, $spacing, $holds"
            // Without a catalogue the screw-ons stay out all the same.
            val fields = listOfNotNull(layout(kilter12x12, climbers, spacing, holds = holds)?.let { kilter12x12 to it }).toMutableList()
            val plain = layout(board, climbers, spacing, holds = holds)
            if (plain != null) {
                fields += board to plain
                // Game after game, each keeping away from the last one's holds.
                var last = emptySet<Int>()
                for (seed in 0L until 12L) {
                    val drawn = assertNotNull(layout(board, climbers, spacing, KnotDraw(seed, grades, last), holds), "$name, seed $seed")
                    fields += board to drawn
                    last = drawn.spots
                }
            }
            for ((b, field) in fields) {
                val hands = field.stripes.flatMap { it.first }
                assertTrue(hands.none { it in b.feetOnly }, "${b.name}, $name: a hand spot on ${hands.filter { it in b.feetOnly }}")
                // Every climber has the step's hand holds of each colour within reach.
                val (h, f) = coverage(b, field, spacing)
                assertTrue(h >= holds.hands && f >= holds.feet, "${b.name}, $name: $h + $f within reach")
                games++
            }
        }
        assertTrue(games > 300, "$games fields")
    }

    @Test
    fun aHandHoldIsOftenAHandAndNotMostlyAFoot() {
        val grades = KnotHandGrades(minClimbs = 20)
        // Hand and foot uses of holds 1 to 6.
        val uses = mapOf(1 to (25 to 5), 2 to (25 to 26), 3 to (25 to 25), 4 to (10 to 15), 5 to (5 to 3), 6 to (0 to 30))
        for (climb in 0 until 60) {
            val frames = uses.entries.joinToString("") { (id, use) ->
                when {
                    climb < use.first -> "p${id}r13"
                    climb < use.first + use.second -> "p${id}r15"
                    else -> ""
                }
            }
            grades.add(frames, 10.0 + climb % 2)
        }
        // A hand in at least 20 climbs and not more often a foot.
        assertEquals(setOf(1, 3), grades.grades().keys)
        // Mostly a foot, rarely a hand, or only a foot; hold 5 is in too few climbs to tell.
        assertEquals(setOf(2, 4, 6), grades.feetOnly())
        // Kilter's screw-ons never take a hand spot, catalogue or not.
        assertEquals(setOf(20L), Knot.footSets(BoardBrand.KILTER))
        listOf(BoardBrand.TENSION, BoardBrand.MOONBOARD, BoardBrand.SHINYWALL).forEach { assertTrue(Knot.footSets(it).isEmpty()) }
    }

    /** The smallest distance between two spots of [layout] on [board] of one colour ([sameColour]) or of any. */
    private fun closestGap(board: Board, layout: KnotLayout, sameColour: Boolean = false): Double {
        val spots = layout.stripes.flatMapIndexed { i, (hands, feet) -> (hands + feet).map { board.rows.holdOf(it)!! to layout.pattern[i] } }
        return spots.flatMapIndexed { i, (a, colour) ->
            spots.drop(i + 1).filter { !sameColour || it.second == colour }.map { (b, _) -> hypot(a.x - b.x, a.y - b.y) * board.metresPerUnit }
        }.minOrNull() ?: Double.MAX_VALUE
    }

    @Test
    fun aDrawNeverPutsSpotsCloserTogetherThanTheNearestHolds() {
        for ((board, climbers) in playable) {
            val grades = kotlin.random.Random(climbers).let { random -> board.rows.holds.associate { it.placementId to 10.0 + random.nextDouble() * 20 } }
            for (spacing in KnotSpacing.entries) for (holds in KnotHolds.entries) {
                // Where the nearest holds keep 30 cm within a colour and 20 cm between any two, a draw keeps them too;
                // where they can't, a draw comes no closer.
                val plain = layout(board, climbers, spacing, holds = holds) ?: continue
                val sameFloor = minOf(closestGap(board, plain, sameColour = true), Knot.SPOT_SPACING_M - 0.02)
                val anyFloor = minOf(closestGap(board, plain), Knot.MIN_GAP_M - 0.02)
                for (seed in 0L until 2L) {
                    // A first game, and the next one, which keeps away from the first one's holds.
                    val name = "${board.name}, $climbers, $spacing, $holds, seed $seed"
                    val first = assertNotNull(layout(board, climbers, spacing, KnotDraw(seed, grades), holds), name)
                    val next = assertNotNull(layout(board, climbers, spacing, KnotDraw(seed + 100, grades, avoid = first.spots), holds), name)
                    for (drawn in listOf(first, next)) {
                        val same = closestGap(board, drawn, sameColour = true)
                        val any = closestGap(board, drawn)
                        assertTrue(same >= sameFloor - 1e-9, "$name: one colour ${(same * 100).toInt()} cm apart, the nearest holds ${(sameFloor * 100).toInt()} cm")
                        assertTrue(any >= anyFloor - 1e-9, "$name: two spots ${(any * 100).toInt()} cm apart, the nearest holds ${(anyFloor * 100).toInt()} cm")
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
        // Hold 2, a hand once and a foot once, is too rarely a hand to count as a hand hold.
        assertEquals(setOf(2), grades.feetOnly())
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
            // Two climbers don't fit the MoonBoard at close spacing any more: the next spacing that does.
            val field = (layout(moonBoard, climbers) ?: layout(moonBoard, climbers, KnotSpacing.MEDIUM)!!).field(Knot.colourOrder(moon, 1))
            val frames = Knot.moonBoardFrames(field.spots)
            for (variant in MoonBoardVariant.entries.filter { it.gridRows == 18 }) {
                for (mode in MoonBoardLedMode.entries) {
                    assertTrue(MoonBoardFrameEncoder.fits(frames, variant, mode), "$climbers on $variant, $mode")
                }
            }
            val mini = (layout(moonBoardMini, climbers) ?: layout(moonBoardMini, climbers, KnotSpacing.MEDIUM)!!).field(moon)
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
    fun bothFeetNeverGoToAColoursOnlyFootSpot() {
        // With Few, every climber has one foot spot of each colour within reach.
        val few = layout(kilter12x12, 2, KnotSpacing.MEDIUM, holds = KnotHolds.FEW)!!
        val single = Knot.singleFootColours(kilter12x12.rows, few, INCH_M, KnotSpacing.MEDIUM)
        assertTrue(single.all { it.isNotEmpty() }, "$single")
        val many = layout(kilter12x12, 2, KnotSpacing.MEDIUM, holds = KnotHolds.MANY)!!
        assertTrue(Knot.singleFootColours(kilter12x12.rows, many, INCH_M, KnotSpacing.MEDIUM).all { it.isEmpty() })

        val lone = setOf(KnotColour.BLUE, KnotColour.WHITE)
        val caller = KnotCaller(seed = 9, places = 1, colours = aurora, singleFoot = listOf(lone))
        val feet = mutableMapOf<KnotLimb, KnotColour>()
        var footCalls = 0
        repeat(2_000) {
            val call = caller.next(setOf(0))!!
            if (call.limb.isHand) return@repeat
            footCalls++
            feet[call.limb] = call.colour
            val other = feet[call.limb.otherFoot]
            assertTrue(call.colour !in lone || other != call.colour, "both feet on $other")
        }
        assertTrue(footCalls > 500)
    }

    @Test
    fun aColoursOnlyFootSpotWithinReachIsKnownAsSuch() {
        // A climber's foot spots count as within reach as far as the field counts them, half a stripe beyond the reach
        // included: a colour with one foot spot there is one for both feet, on the nearest holds and drawn fields alike.
        var single = 0
        for ((board, climbers) in playable) for (spacing in KnotSpacing.entries) for (holds in KnotHolds.entries) {
            val grades = kotlin.random.Random(climbers).let { random -> board.rows.holds.associate { it.placementId to 10.0 + random.nextDouble() * 20 } }
            val fields = listOfNotNull(layout(board, climbers, spacing, holds = holds), layout(board, climbers, spacing, KnotDraw(3, grades), holds))
            for (field in fields) {
                val name = "${board.name}, $climbers, $spacing, $holds"
                val flagged = Knot.singleFootColours(board.rows, field, board.metresPerUnit, spacing)
                val (start, width) = fieldOf(board, climbers, field.offsetM)
                val limit = spacing.reachM + maxOf(Knot.REACH_SLACK_M, width / field.stripes.size / 2) + 1e-9
                for (part in 0 until climbers) {
                    val middle = start + width / climbers * (part + 0.5)
                    for (colour in board.colours.indices) {
                        val feet = field.footSpots(colour).count { kotlin.math.abs(xM(board, it) - middle) <= limit }
                        if (feet == 1) single++
                        assertEquals(feet == 1, colour in flagged[part], "$name, part $part: colour $colour has $feet foot spots within reach")
                    }
                }
            }
        }
        assertTrue(single > 0)
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
