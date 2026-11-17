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

    private class Board(val name: String, val rows: WallRows, val metresPerUnit: Double, val colours: Int)

    private val kilter12x12 = Board("Kilter 12x12", KnotBoardFixtures.kilter12x12, INCH_M, 4)
    private val kilter7x10 = Board("Kilter 7x10", KnotBoardFixtures.kilter7x10, INCH_M, 4)
    private val moonBoard = Board("MoonBoard", KnotBoardFixtures.moonBoard, MOON_CELL_M, 3)
    private val moonBoardMini = Board("Mini MoonBoard", KnotBoardFixtures.moonBoardMini, MOON_CELL_M, 3)
    private val kilter16x12 = Board("Kilter 16x12", KnotBoardFixtures.kilter16x12, INCH_M, 4)

    private fun layout(board: Board, climbers: Int): KnotLayout? =
        Knot.layout(board.rows, climbers, board.colours, board.metresPerUnit)

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
        // The four colours come once up to three climbers, twice for four and three times for five.
        assertEquals(listOf(1, 1, 1, 2, 3), (1..5).map { Knot.periods(Knot.fieldWidthM(it), 4) })
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
        assertNull(Knot.layout(kilter12x12.rows, 0, 4, INCH_M))
    }

    // ── Field ─────────────────────────────────────────────────────────

    private val playable = listOf(
        kilter12x12 to 1, kilter12x12 to 2, kilter12x12 to 3,
        kilter7x10 to 1, kilter7x10 to 2,
        moonBoard to 1, moonBoard to 2,
        moonBoardMini to 1, moonBoardMini to 2,
    )

    /** Four and five at the wall: the colours come two or three times. */
    private val wide = listOf(kilter12x12 to 4, kilter16x12 to 4, kilter16x12 to 5)

    @Test
    fun everyColourGetsItsFullSpotsAndNoBoardLightsMoreThanTheTable() {
        // The table of #154, and the same 8 per climber on the wider fields.
        val table = mapOf(1 to 12, 2 to 20, 3 to 28, 4 to 36, 5 to 44)
        for ((board, climbers) in playable + wide) {
            val layout = assertNotNull(layout(board, climbers), "${board.name}, $climbers")
            assertEquals(board.colours * layout.periods, layout.stripes.size)
            layout.stripes.withIndex().groupBy { it.index % board.colours }.values.forEach { colour ->
                assertEquals(Knot.handSpots(climbers), colour.sumOf { it.value.first.size }, "${board.name}, $climbers: hands")
                assertEquals(Knot.footSpots(climbers), colour.sumOf { it.value.second.size }, "${board.name}, $climbers: feet")
                // Shared out evenly: no stripe of a colour has more than one spot over another.
                assertTrue(colour.maxOf { it.value.first.size } - colour.minOf { it.value.first.size } <= 1)
                assertTrue(colour.maxOf { it.value.second.size } - colour.minOf { it.value.second.size } <= 1)
            }
            val all = layout.stripes.flatMap { it.first + it.second }
            assertEquals(all.size, all.toSet().size, "${board.name}, $climbers: a hold used twice")
            assertTrue(all.size <= table.getValue(climbers), "${board.name}, $climbers: ${all.size} lit")
            all.forEach { assertNotNull(board.rows.holdOf(it)) }
        }
    }

    @Test
    fun handsSitHighFeetLowAndEverythingStaysUnderTwoMetresTwenty() {
        for ((board, climbers) in playable + wide) {
            val layout = layout(board, climbers)!!
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
        for (board in listOf(kilter12x12, kilter7x10, moonBoard, kilter16x12)) {
            val left = board.rows.holds.minOf { it.x } * board.metresPerUnit
            val right = board.rows.holds.maxOf { it.x } * board.metresPerUnit
            val centre = (left + right) / 2
            var previous = 0.0
            for (climbers in 1..Knot.maxClimbers(board.rows, board.metresPerUnit)) {
                val layout = layout(board, climbers)!!
                val xs = layout.stripes.flatMap { it.first + it.second }.map { xM(board, it) }
                val width = xs.max() - xs.min()
                assertTrue(width <= Knot.fieldWidthM(climbers) + 0.01, "${board.name}, $climbers: $width m wide")
                assertTrue(width > previous, "${board.name}, $climbers: not wider than for ${climbers - 1}")
                // Centred within one stripe.
                val stripe = Knot.fieldWidthM(climbers) / layout.stripes.size
                assertTrue(kotlin.math.abs((xs.max() + xs.min()) / 2 - centre) < stripe, "${board.name}, $climbers: off centre")
                previous = width
            }
        }
        // Solo on a big wall is about 1.2 m; three on a 12x12 about 2.8 m.
        val solo = layout(kilter12x12, 1)!!.stripes.flatMap { it.first + it.second }.map { xM(kilter12x12, it) }
        assertTrue(solo.max() - solo.min() in 0.6..1.2)
        val three = layout(kilter12x12, 3)!!.stripes.flatMap { it.first + it.second }.map { xM(kilter12x12, it) }
        assertTrue(three.max() - three.min() in 2.2..2.8)
    }

    @Test
    fun stripesRunLeftToRightAndSpotsKeepTheirDistance() {
        for ((board, climbers) in playable + wide) {
            val layout = layout(board, climbers)!!
            val centres = layout.stripes.map { (hands, feet) -> (hands + feet).map { xM(board, it) }.average() }
            assertEquals(centres.sorted(), centres, "${board.name}, $climbers: stripes out of order")
            val spots = layout.stripes.flatMap { it.first + it.second }.map { board.rows.holdOf(it)!! }
            val closest = spots.flatMapIndexed { i, a ->
                spots.drop(i + 1).map { b -> hypot(a.x - b.x, a.y - b.y) * board.metresPerUnit }
            }.min()
            assertTrue(closest >= Knot.SPOT_SPACING_M - 0.03, "${board.name}, $climbers: spots $closest m apart")
        }
    }

    @Test
    fun theSameWallGivesTheSameLayoutAndBigHoldsWinWhenAboutAsNear() {
        assertEquals(layout(kilter12x12, 2), layout(kilter12x12, 2))
        val plain = layout(kilter12x12, 1)!!
        val chosen = plain.stripes.flatMap { it.first + it.second }.toSet()
        // Every other hold is big: wherever one is about as near as the chosen hold, it takes the spot.
        val big = kilter12x12.rows.holds.map { it.placementId }.filter { it !in chosen }.toSet()
        val withTypes = Knot.layout(kilter12x12.rows, 1, 4, INCH_M, bigHolds = big)!!
        val spots = withTypes.stripes.flatMap { it.first + it.second }
        assertTrue(spots.count { it in big } > spots.size / 2, "${spots.count { it in big }} of ${spots.size} big")
    }

    @Test
    fun aWallWithoutRoomForHandsHasNoField() {
        // Two low rows only: feet, but nowhere for the hands.
        val low = WallRows.of((0 until 40).map { WallHold(it, (it % 20) * 8.0, (it / 20) * 8.0) })
        assertEquals(4, Knot.maxClimbers(low, INCH_M))
        assertNull(Knot.layout(low, 1, 4, INCH_M))
        val tall = WallRows.of(low.holds + (100 until 200).map { WallHold(it, (it % 20) * 8.0, 16.0 + ((it - 100) / 20) * 16.0) })
        assertNotNull(Knot.layout(tall, 1, 4, INCH_M))
    }

    @Test
    fun layoutTakesColoursFromTheLeft() {
        val layout = layout(kilter7x10, 2)!!
        val order = Knot.colourOrder(aurora, 3)
        val field = layout.field(order)
        assertEquals(order, field.colours)
        assertEquals(20, field.spots.size)
        field.stripes.forEachIndexed { i, stripe -> stripe.spots.forEach { assertEquals(order[i], field.spots[it]) } }
        // A hand call blinks only that colour's hand spots, a foot call only its foot spots.
        val hands = field.without(order[0], hands = true)
        assertEquals(17, hands.size)
        assertEquals(field.stripes[0].feet.toSet(), hands.filterValues { it == order[0] }.keys)
        val feet = field.without(order[0], hands = false)
        assertEquals(18, feet.size)
        assertEquals(field.stripes[0].hands.toSet(), feet.filterValues { it == order[0] }.keys)
    }

    @Test
    fun startPartsSplitTheFieldFromTheLeft() {
        val solo = layout(kilter12x12, 1)!!.field(aurora)
        assertEquals(listOf(0..3), Knot.startStripes(solo))
        val two = layout(kilter12x12, 2)!!.field(aurora)
        assertEquals(listOf(0..1, 2..3), Knot.startStripes(two))
        val three = layout(kilter12x12, 3)!!.field(aurora)
        assertEquals(listOf(0..1, 1..2, 2..3), Knot.startStripes(three))
        val moonTwo = layout(moonBoard, 2)!!.field(moon)
        assertEquals(listOf(0..1, 1..2), Knot.startStripes(moonTwo))
        // Four on eight stripes: two each; five on twelve share a stripe with a neighbour.
        val four = layout(kilter12x12, 4)!!.field(aurora)
        assertEquals(listOf(0..1, 2..3, 4..5, 6..7), Knot.startStripes(four))
        val five = layout(kilter16x12, 5)!!.field(aurora)
        assertEquals(listOf(0..2, 2..4, 4..7, 7..9, 9..11), Knot.startStripes(five))
    }

    @Test
    fun wideFieldsRepeatTheColoursSoEveryClimberHasEachWithinAboutAMetre() {
        for ((board, climbers) in wide) {
            val name = "${board.name}, $climbers"
            val layout = layout(board, climbers)!!
            assertEquals(if (climbers == 4) 2 else 3, layout.periods, name)
            val order = Knot.colourOrder(aurora, 11, layout.periods)
            val field = layout.field(order)
            // The same order again and again from the left; a call names each colour once.
            assertEquals(List(layout.periods) { order }.flatten(), field.stripes.map { it.colour }, name)
            assertEquals(order, field.colours, name)
            // From the middle of each climber's part, every colour has a hand and a foot spot within about a metre.
            val xs = field.spots.keys.map { xM(board, it) }
            val partWidth = (xs.max() - xs.min()) / climbers
            var farthest = 0.0
            for (part in 0 until climbers) {
                val middle = xs.min() + partWidth * (part + 0.5)
                for (colour in order) {
                    val stripes = field.stripes.filter { it.colour == colour }
                    val hand = stripes.flatMap { it.hands }.minOf { kotlin.math.abs(xM(board, it) - middle) }
                    val foot = stripes.flatMap { it.feet }.minOf { kotlin.math.abs(xM(board, it) - middle) }
                    farthest = maxOf(farthest, hand, foot)
                    assertTrue(hand <= 1.05 && foot <= 1.05, "$name, part $part, $colour: hand $hand m, foot $foot m")
                }
            }
            println("Knot $name: every colour within ${(farthest * 100).toInt()} cm")
        }
    }

    @Test
    fun repeatedColoursKeepRedAwayFromGreenWhereTheSetsMeet() {
        val four = listOf(KnotColour.RED, KnotColour.BLUE, KnotColour.GREEN, KnotColour.YELLOW)
        val apart = setOf(KnotColour.RED, KnotColour.GREEN)
        (0L until 100L).map { Knot.colourOrder(four, it, periods = 2) }.forEach { order ->
            val sides = (order + order).zipWithNext()
            assertTrue(sides.none { (a, b) -> setOf(a, b) == apart }, "$order")
        }
        // One set: only its own neighbours count, as before.
        assertEquals(Knot.colourOrder(aurora, 7), Knot.colourOrder(aurora, 7, periods = 1))
    }

    // ── Spacing ───────────────────────────────────────────────────────

    private fun layout(board: Board, climbers: Int, spacing: KnotSpacing): KnotLayout? =
        Knot.layout(board.rows, climbers, board.colours, board.metresPerUnit, spacing = spacing)

    private fun apartM(board: Board, a: Int, b: Int): Double {
        val p = board.rows.holdOf(a)!!
        val q = board.rows.holdOf(b)!!
        return hypot(p.x - q.x, p.y - q.y) * board.metresPerUnit
    }

    @Test
    fun closeIsTheFieldAsBefore() {
        for ((board, climbers) in playable) {
            assertEquals(layout(board, climbers), layout(board, climbers, KnotSpacing.CLOSE), "${board.name}, $climbers")
        }
    }

    @Test
    fun widerSpacingKeepsItsDistanceWithFewerSpotsInTheSameField() {
        for ((board, climbers) in playable) {
            val close = layout(board, climbers, KnotSpacing.CLOSE)!!
            val closeXs = close.stripes.flatMap { it.first + it.second }.map { xM(board, it) }
            var previous = close.stripes.sumOf { it.first.size + it.second.size }
            for (spacing in listOf(KnotSpacing.MEDIUM, KnotSpacing.WIDE)) {
                val name = "${board.name}, $climbers, $spacing"
                val wider = assertNotNull(layout(board, climbers, spacing), name)
                val hands = wider.stripes.flatMap { it.first }
                val feet = wider.stripes.flatMap { it.second }
                // Hands keep the spacing from every spot; feet keep at most 45 cm from every spot.
                hands.forEachIndexed { i, a -> hands.drop(i + 1).forEach { b -> assertTrue(apartM(board, a, b) >= spacing.handsM - 0.03, "$name: hands") } }
                feet.forEach { a -> (hands + feet).filter { it != a }.forEach { b -> assertTrue(apartM(board, a, b) >= spacing.feetM - 0.03, "$name: feet") } }
                // At least 1.5 spots per hand and per foot, and every colour keeps a hand and a foot spot.
                assertTrue(hands.size >= 3 * climbers, "$name: ${hands.size} hand spots")
                assertTrue(feet.size >= 3 * climbers, "$name: ${feet.size} foot spots")
                wider.stripes.forEach { (h, f) -> assertTrue(h.isNotEmpty() && f.isNotEmpty(), "$name: empty colour") }
                // Never more spots than the closer spacing, in the same field and heights.
                val count = hands.size + feet.size
                assertTrue(count <= previous, "$name: $count spots after $previous")
                previous = count
                val xs = (hands + feet).map { xM(board, it) }
                assertTrue(xs.min() >= closeXs.min() - Knot.fieldWidthM(climbers) / board.colours, "$name: wider field")
                assertTrue(xs.max() <= closeXs.max() + Knot.fieldWidthM(climbers) / board.colours, "$name: wider field")
                hands.forEach { assertTrue(heightM(board, it) in Knot.HANDS_FROM_M..Knot.HANDS_TO_M, "$name: hand height") }
                feet.forEach { assertTrue(heightM(board, it) <= Knot.FEET_TO_M, "$name: foot height") }
            }
        }
        // On the big Kilter the wide field is clearly sparser than the close one.
        for (climbers in 1..3) {
            val close = layout(kilter12x12, climbers, KnotSpacing.CLOSE)!!.stripes.sumOf { it.first.size + it.second.size }
            val wide = layout(kilter12x12, climbers, KnotSpacing.WIDE)!!.stripes.sumOf { it.first.size + it.second.size }
            assertTrue(wide < close, "$climbers: wide $wide, close $close")
        }
    }

    @Test
    fun wideFieldsKeepTheSpacingWhereTheyOfferIt() {
        for ((board, climbers) in wide) {
            var previous = Int.MAX_VALUE
            for (spacing in KnotSpacing.entries) {
                val name = "${board.name}, $climbers, $spacing"
                // Close always fits; a wider spacing may not keep 1.5 spots per hand and foot on the narrower stripes.
                val layout = layout(board, climbers, spacing) ?: continue.also { assertNotEquals(KnotSpacing.CLOSE, spacing, name) }
                val hands = layout.stripes.flatMap { it.first }
                val feet = layout.stripes.flatMap { it.second }
                println("Knot $name: ${hands.size} + ${feet.size} = ${hands.size + feet.size} lit")
                if (spacing != KnotSpacing.CLOSE) {
                    hands.forEachIndexed { i, a -> hands.drop(i + 1).forEach { b -> assertTrue(apartM(board, a, b) >= spacing.handsM - 0.03, "$name: hands") } }
                    feet.forEach { a -> (hands + feet).filter { it != a }.forEach { b -> assertTrue(apartM(board, a, b) >= spacing.feetM - 0.03, "$name: feet") } }
                }
                assertTrue(hands.size >= 3 * climbers && feet.size >= 3 * climbers, "$name: ${hands.size} + ${feet.size}")
                layout.stripes.forEach { (h, f) -> assertTrue(h.isNotEmpty() && f.isNotEmpty(), "$name: empty stripe") }
                assertTrue(hands.size + feet.size <= previous, "$name: more spots than a closer spacing")
                previous = hands.size + feet.size
            }
        }
    }

    @Test
    fun aSpacingThatLeavesTooFewSpotsIsRefused() {
        // Hands all over, but feet only along one low row 20 cm apart: close fits a foot spot per colour, wide not.
        val holds = (0 until 18).flatMap { col -> (5 until 12).map { row -> WallHold(row * 100 + col, col * 8.0, row * 8.0) } } +
            (0 until 18).map { col -> WallHold(col, col * 8.0, 0.0) }
        val wall = WallRows.of(holds)
        assertNotNull(Knot.layout(wall, 1, 4, INCH_M, spacing = KnotSpacing.CLOSE))
        assertNull(Knot.layout(wall, 1, 4, INCH_M, spacing = KnotSpacing.WIDE))
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
