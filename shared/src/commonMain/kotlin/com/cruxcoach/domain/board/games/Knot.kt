package com.cruxcoach.domain.board.games

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.HoldRole
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Knot (#154): a party game on a lit field of colour stripes. The app calls a
 * hand or a foot and a colour, climber by climber ("Player 2: right hand on
 * blue"), and the climbers have to get there without coming off. The field
 * grows with the number of climbers, so they always get in each other's way,
 * and stays low, so falls are short. The last climber on the wall wins; solo,
 * the score is the number of calls held. More players than the wall takes
 * wait in line and step in for whoever comes off; then the most calls held
 * wins.
 *
 * Everything here is pure. Lengths are in metres; a board's own units come
 * in through [metresPerUnit].
 */

/** A hand or a foot. */
enum class KnotLimb(val isHand: Boolean) {
    LEFT_HAND(true), RIGHT_HAND(true), LEFT_FOOT(false), RIGHT_FOOT(false);

    /** The other foot, for a foot. */
    val otherFoot: KnotLimb get() = if (this == LEFT_FOOT) RIGHT_FOOT else LEFT_FOOT
}

/**
 * The named colours Knot lights; a board uses those of [Knot.colours].
 * [rgb332] is the Aurora LED byte, [moonRole] the MoonBoard role that shows
 * the colour (null where the MoonBoard has none).
 */
enum class KnotColour(val rgb332: Int, val moonRole: Int?) {
    BLUE(0x03, HoldRole.ROUTE_HAND),
    YELLOW(0xFC, null),
    MAGENTA(0xE3, null),
    WHITE(0xFF, null),
    GREEN(0x1C, HoldRole.ROUTE_START),
    RED(0xE0, HoldRole.ROUTE_FINISH),
}

/** One stripe of the field: its colour and its spots (placement ids), hands from low to high, then feet. */
data class KnotStripe(val colour: KnotColour, val hands: List<Int>, val feet: List<Int>) {
    val spots: List<Int> get() = hands + feet
}

/** The lit field of one round for [climbers] at the wall: stripes from left to right. */
data class KnotField(val climbers: Int, val stripes: List<KnotStripe>) {
    /** Every spot with its colour. */
    val spots: Map<Int, KnotColour> get() = stripes.flatMap { s -> s.spots.map { it to s.colour } }.toMap()

    /** The colours from the left, each once: a wide field repeats them. */
    val colours: List<KnotColour> get() = stripes.map { it.colour }.distinct()

    /** The field without the spots [call] goes to, [colour]'s hand or foot spots: the dark half of its blink. */
    fun without(colour: KnotColour, hands: Boolean): Map<Int, KnotColour> {
        val called = stripes.filter { it.colour == colour }.flatMapTo(mutableSetOf()) { if (hands) it.hands else it.feet }
        return spots.filterKeys { it !in called }
    }
}

/**
 * Where a field's spots sit, before the round gives the stripes their
 * colours: stripes from left to right, each with hand and foot spots.
 * [pattern] says which colour of a round's order each stripe takes (0 for
 * the first …): the colours repeat across the field. [offsetM]: how far the
 * field sits right of the wall's middle (left when negative), see
 * [Knot.FIELD_SHIFT_M].
 */
data class KnotLayout(
    val climbers: Int,
    val stripes: List<Pair<List<Int>, List<Int>>>,
    val pattern: List<Int> = stripes.indices.toList(),
    val offsetM: Double = 0.0,
) {
    /** This layout lit with [order]: each stripe in the colour its [pattern] picks. */
    fun field(order: List<KnotColour>): KnotField {
        require(pattern.all { it in order.indices }) { "${order.size} colours for the stripes $pattern" }
        return KnotField(climbers, stripes.mapIndexed { i, (hands, feet) -> KnotStripe(order[pattern[i]], hands, feet) })
    }

    /** Every spot, hand or foot. */
    val spots: Set<Int> get() = stripes.flatMapTo(mutableSetOf()) { it.first + it.second }
}

/**
 * How far the climbers reach for the colours: from the middle of a climber's part the farthest colour is about
 * [reachM] away, and hands on the two farthest colours are at most about [spanM] apart. Every spacing lights the same
 * number of spots; a wider one spreads them, in wider stripes that repeat less often.
 */
enum class KnotSpacing(val reachM: Double, val spanM: Double) {
    CLOSE(0.5, 0.9), MEDIUM(0.75, 1.2), WIDE(1.0, 1.5),
}

/**
 * How many spots of each colour every climber has within reach: [hands] hand and [feet] foot spots. Counted per
 * climber, so a step feels the same for 2 or 5 players. Easiest first: harder to the right.
 */
enum class KnotHolds(val hands: Int, val feet: Int) {
    MANY(4, 3), NORMAL(3, 2), FEW(2, 1),
}

/**
 * A fresh field for one game: the spots are drawn from [seed] instead of taking the nearest holds, away from [avoid]
 * (the last game's spots) where the wall allows. [handGrades] rates hand holds by how hard the catalogue's climbs are
 * that use them as a hand hold (a grade number, higher is harder), for holds with enough such climbs.
 */
class KnotDraw(val seed: Long, val handGrades: Map<Int, Double>, val avoid: Set<Int> = emptySet())

/** One call: [number] counts from 1; [climber] counts from 0 in the fixed order. */
data class KnotCall(val number: Int, val climber: Int, val limb: KnotLimb, val colour: KnotColour)

object Knot {
    /** Players in a game: as many as the wall takes climb ([maxClimbers]), the others wait in line. */
    const val MAX_PLAYERS = 8
    val PLAYERS: IntRange = 1..MAX_PLAYERS

    /** Field width per climber, at least [FIELD_MIN_M]: 1.2 m for one or two, 1.8 m for three, 2.4 m for four. */
    const val FIELD_PER_CLIMBER_M = 0.6
    const val FIELD_MIN_M = 1.2

    /** Wall a climber needs to climb at once with others ([maxClimbers]): more than their part of the field. */
    const val WALL_PER_CLIMBER_M = 0.8

    /**
     * Height above the floor of the board's lowest hold row. Heights are
     * measured along the board from there, which on an overhanging board is
     * a little more than the true height, so the field stays on the low side.
     */
    const val LOWEST_ROW_M = 0.15
    /** Hand spots between these heights above the floor. */
    const val HANDS_FROM_M = 1.0
    const val HANDS_TO_M = 2.2
    /** Foot spots up to this height: the kickboard and the lowest rows. */
    const val FEET_TO_M = 0.7

    /** Spots of one colour stay this far apart. */
    const val SPOT_SPACING_M = 0.3
    /** Any two lit spots stay this far apart, so no two lit holds touch. */
    const val MIN_GAP_M = 0.2

    /** Above this angle the game suggests level 1 or a flatter wall. */
    const val STEEP_DEGREES = 35

    /** Seconds per call by level 1..5. */
    private val CALL_MS = longArrayOf(10_000, 8_000, 6_000, 4_000, 3_000)

    fun callMs(level: Int): Long = CALL_MS[level.coerceIn(GAME_LEVELS) - 1]

    fun fieldWidthM(climbers: Int): Double = maxOf(FIELD_MIN_M, FIELD_PER_CLIMBER_M * climbers)


    /**
     * The colours [brand] lights for Knot, or null when Knot can't name what
     * the board shows. Aurora boards take any colour: four that are easy to
     * tell apart on the LEDs and with red-green colour blindness. MoonBoard
     * has its three. Shiny Wall shows codes in colours of its own controller
     * that the app doesn't know, and Quantum shares its lights.
     */
    fun colours(brand: BoardBrand): List<KnotColour>? = when {
        brand.usesAuroraProtocol -> listOf(KnotColour.BLUE, KnotColour.YELLOW, KnotColour.MAGENTA, KnotColour.WHITE)
        brand == BoardBrand.MOONBOARD -> listOf(KnotColour.GREEN, KnotColour.BLUE, KnotColour.RED)
        else -> null
    }

    /** Pairs that never stand side by side: red next to green is one colour to many eyes. */
    private val APART: Set<Set<KnotColour>> = setOf(setOf(KnotColour.RED, KnotColour.GREEN))

    /** Metres per unit of a board's hold positions: Aurora inches, Shiny Wall centimetres, MoonBoard grid cells. */
    fun metresPerUnit(brand: BoardBrand): Double? = when {
        brand.usesAuroraProtocol -> INCH_M
        brand == BoardBrand.MOONBOARD -> MOON_CELL_M
        brand == BoardBrand.SHINYWALL -> CENTIMETRE_M
        else -> null
    }

    /**
     * The hold sets (a placement's set id) of [brand] that never take a hand spot, catalogue or not: Kilter's
     * screw-ons, the small holds between the bolt-ons that the climbs use as feet.
     */
    fun footSets(brand: BoardBrand): Set<Long> = if (brand == BoardBrand.KILTER) setOf(KILTER_SCREW_ONS) else emptySet()

    private const val KILTER_SCREW_ONS = 20L

    private const val INCH_M = 0.0254
    private const val CENTIMETRE_M = 0.01
    /** MoonBoard holds sit on a grid of about 20 cm. */
    private const val MOON_CELL_M = 0.2

    /**
     * How many climbers fit at once: the span of the holds at 0.8 m each (2 on a 7x10 or a MoonBoard, 4 on a 12 ft
     * wide board, 5 on a 16x12), at least 1, at most [MAX_PLAYERS]; 0 on a wall without holds.
     */
    fun maxClimbers(rows: WallRows, metresPerUnit: Double): Int {
        if (rows.isEmpty()) return 0
        val span = (rows.holds.maxOf { it.x } - rows.holds.minOf { it.x }) * metresPerUnit
        return PLAYERS.lastOrNull { span >= WALL_PER_CLIMBER_M * it - SPAN_SLACK_M } ?: 1
    }

    /** Rounding in a board's grid: a 2.0 m span counts as room for two. */
    private const val SPAN_SLACK_M = 0.05

    /** [colours] shuffled for one round, with the [APART] pairs never side by side. */
    fun colourOrder(colours: List<KnotColour>, seed: Long): List<KnotColour> {
        val random = Random(seed)
        repeat(SHUFFLE_TRIES) {
            val order = colours.shuffled(random)
            if (order.zipWithNext().none { (a, b) -> setOf(a, b) in APART }) return order
        }
        // Only reachable when no order keeps them apart (two colours that must not touch).
        return colours
    }

    private const val SHUFFLE_TRIES = 64

    /**
     * Which colour of a round's order (0 for the first …) each of [stripes] stripes takes: the colours from the left,
     * again and again. Where two of [colours] must never touch ([APART]), the order runs there and back (0 1 2 1 0 1 2
     * …), so the colours [colourOrder] keeps apart never meet where it repeats.
     */
    fun pattern(colours: List<KnotColour>, stripes: Int): List<Int> {
        val count = colours.size
        val apart = APART.any { pair -> pair.all { it in colours } }
        val period = if (apart && count > 2 && stripes > count) (0 until count) + (count - 2 downTo 1) else (0 until count).toList()
        return List(stripes) { period[it % period.size] }
    }

    /**
     * How far the climbers reach on a field from [left] that is [widthM] wide, split into equal parts for
     * [climbers]: from the middle of each part to the nearest of [spots] (position in metres → colour 0 until
     * [colours]) in every colour. The farthest of those over all parts, and the largest distance between the nearest
     * spots of a part's two farthest colours. Infinite when a colour has no spot.
     */
    fun reach(left: Double, widthM: Double, climbers: Int, spots: List<Pair<Double, Int>>, colours: Int): Pair<Double, Double> {
        var farthest = 0.0
        var span = 0.0
        for (part in 0 until climbers) {
            val middle = left + widthM / climbers * (part + 0.5)
            val nearest = (0 until colours).map { colour ->
                spots.filter { it.second == colour }.minByOrNull { abs(it.first - middle) }?.first ?: return INFINITE to INFINITE
            }.sortedByDescending { abs(it - middle) }
            farthest = maxOf(farthest, abs(nearest[0] - middle))
            if (nearest.size > 1) span = maxOf(span, abs(nearest[0] - nearest[1]))
        }
        return farthest to span
    }

    private val INFINITE = Double.POSITIVE_INFINITY

    /**
     * How many stripes a field [widthM] wide gets for [climbers] at [spacing]: the fewest, so the widest, with which
     * from the middle of every part the farthest colour is at most [KnotSpacing.reachM] away and the two farthest at
     * most [KnotSpacing.spanM] apart, measured at the stripes' middles. At least one stripe per colour.
     */
    fun stripeCount(widthM: Double, climbers: Int, colours: List<KnotColour>, spacing: KnotSpacing): Int {
        for (count in colours.size..MAX_STRIPES) {
            val middles = pattern(colours, count).mapIndexed { i, colour -> widthM / count * (i + 0.5) to colour }
            val (farthest, span) = reach(0.0, widthM, climbers, middles, colours.size)
            if (farthest <= spacing.reachM + ROUNDING && span <= spacing.spanM + ROUNDING) return count
        }
        return MAX_STRIPES
    }

    private const val MAX_STRIPES = 64
    private const val ROUNDING = 1e-9

    /**
     * The field for [climbers] on [rows] at [spacing]: stripes side by side in [colours], centred on the wall (up to
     * [FIELD_SHIFT_M] to one side where it doesn't fit there) and [fieldWidthM] wide in all (the whole wall when it is
     * narrower), as many as [stripeCount] says. Every climber gets
     * [holds] hand spots (from [HANDS_FROM_M] to [HANDS_TO_M]) and foot spots (up to [FEET_TO_M]) of each colour within
     * the spacing's reach of the middle of their part; neighbours share the stripes between them. The spots of a stripe
     * sit at evenly spread heights. Spots of one colour keep [SPOT_SPACING_M] from each other where the wall allows,
     * and no two lit spots come closer than [MIN_GAP_M]: rather no spot than one touching another. Hand spots go on
     * hand holds only: never on one of [feetOnly] (a foot hold by its set, [footSets], or by the catalogue's climbs,
     * [KnotHandGrades.feetOnly]); foot spots on any hold.
     *
     * Without a [draw], each spot takes the nearest free hold to its place in the stripe, a big hold ([bigHolds],
     * from the hold types once a board's are confirmed) when one is about as near: the same wall, climbers and
     * spacing always give the same field. With a [draw], each spot is drawn among the stripe's free holds at about its
     * height, a big hold more often, not one of the hardest fifth of the rated hand holds in the field (hand holds
     * only; where no draw keeps the spacing without them, as few of them as can be) and away from last game's
     * spots where the wall allows; the spacing goes before all of that, and where no hold keeps it the spot takes the
     * hold farthest from the others. Of [DRAWS] such fields the one with the fewest spots too close to another wins,
     * then the one whose colours and climbers' parts come closest to equally good.
     *
     * Null when [climbers] don't fit ([maxClimbers]) or when, wherever the field sits within [FIELD_SHIFT_M] of the
     * middle, from the middle of a climber's part a colour has fewer than [holds] hand or foot spots within the
     * spacing's reach ([reachLimitM]: half a stripe or [REACH_SLACK_M] beyond it, whichever is more): the wall can't fit
     * that step.
     */
    fun layout(
        rows: WallRows,
        climbers: Int,
        colours: List<KnotColour>,
        metresPerUnit: Double,
        bigHolds: Set<Int> = emptySet(),
        spacing: KnotSpacing = KnotSpacing.CLOSE,
        draw: KnotDraw? = null,
        holds: KnotHolds = KnotHolds.NORMAL,
        feetOnly: Set<Int> = emptySet(),
    ): KnotLayout? {
        if (climbers !in PLAYERS || colours.isEmpty() || climbers > maxClimbers(rows, metresPerUnit)) return null
        // In the middle of the wall where it fits; else a little to one side, where the stripe edges fall otherwise
        // between the columns of holds.
        return fieldOffsets(rows, climbers, metresPerUnit).firstNotNullOfOrNull { offset ->
            layoutAt(rows, climbers, colours, metresPerUnit, bigHolds, spacing, draw, holds, feetOnly, offset)
        }
    }

    /** [layout] with the field [offset] right of the wall's middle. */
    private fun layoutAt(
        rows: WallRows,
        climbers: Int,
        colours: List<KnotColour>,
        metresPerUnit: Double,
        bigHolds: Set<Int>,
        spacing: KnotSpacing,
        draw: KnotDraw?,
        holds: KnotHolds,
        feetOnly: Set<Int>,
        offset: Double,
    ): KnotLayout? {
        val wall = rows.holds
        val lowest = wall.minOf { it.y }
        fun heightM(hold: WallHold) = LOWEST_ROW_M + (hold.y - lowest) * metresPerUnit
        fun xM(hold: WallHold) = hold.x * metresPerUnit
        val (start, width) = fieldSpan(rows, climbers, metresPerUnit, offset)
        val count = stripeCount(width, climbers, colours, spacing)
        val pattern = pattern(colours, count)
        val stripeWidth = width / count
        val byStripe = wall.groupBy { hold ->
            ((xM(hold) - start) / stripeWidth).toInt().takeIf { xM(hold) >= start && xM(hold) <= start + width }?.coerceAtMost(count - 1)
        }

        val middles = List(climbers) { start + width / climbers * (it + 0.5) }
        fun stripeMiddle(stripe: Int) = start + stripeWidth * (stripe + 0.5)
        /** A colour's stripes within reach of a climber's [middle], by the stripes' middles ([stripeCount] keeps one there). */
        fun window(colour: Int, middle: Double): List<Int> {
            val own = pattern.indices.filter { pattern[it] == colour }
            return own.filter { abs(stripeMiddle(it) - middle) <= spacing.reachM + ROUNDING }.ifEmpty { listOf(own.minBy { abs(stripeMiddle(it) - middle) }) }
        }
        // A spot anywhere in a stripe within reach counts: half a stripe, or the slack, beyond the reach.
        val reachLimit = reachLimitM(spacing, stripeWidth)

        /**
         * Spots per stripe so that every climber has [per] of each colour within reach: a climber short of them gets them in
         * the colour's stripe within reach that has the fewest so far; neighbours share what lies between them.
         */
        fun counts(per: Int): IntArray {
            val counts = IntArray(count)
            for (middle in middles) for (colour in colours.indices) {
                val window = window(colour, middle)
                repeat(per - window.sumOf { counts[it] }) {
                    counts[window.minWith(compareBy({ counts[it] }, { abs(stripeMiddle(it) - middle) }))]++
                }
            }
            return counts
        }

        /** Spot [index] of [hand] or foot spots in [stripe], at [heightM] in a band [bandM] tall. */
        class Target(val stripe: Int, val index: Int, val hand: Boolean, val heightM: Double, val bandM: Double)
        fun targets(hand: Boolean): List<Target> {
            val perStripe = counts(if (hand) holds.hands else holds.feet)
            val (from, to) = if (hand) HANDS_FROM_M to HANDS_TO_M else 0.0 to FEET_TO_M
            return (0 until count).flatMap { stripe ->
                val n = perStripe[stripe]
                (0 until n).map { i -> Target(stripe, i, hand, from + (to - from) * (i + 0.5) / n, (to - from) / n) }
            }.sortedWith(compareBy({ it.index }, { it.stripe }))
        }
        // The stripes take turns, so none takes the room its neighbours need; hands first, then feet.
        val targets = targets(hand = true) + targets(hand = false)
        fun handHold(hold: WallHold) = heightM(hold) in HANDS_FROM_M..HANDS_TO_M && hold.placementId !in feetOnly
        val handZones = List(count) { stripe -> byStripe[stripe].orEmpty().filter(::handHold) }
        val footZones = List(count) { stripe -> byStripe[stripe].orEmpty().filter { heightM(it) <= FEET_TO_M } }
        fun zone(target: Target) = (if (target.hand) handZones else footZones)[target.stripe]

        val taken = mutableListOf<WallHold>()
        val takenColours = mutableListOf<Int>()
        val takenIds = mutableSetOf<Int>()
        fun distance(a: WallHold, b: WallHold) = hypot((a.x - b.x) * metresPerUnit, (a.y - b.y) * metresPerUnit)
        /** [hold] for [target] keeps 30 cm from its own colour's spots and 20 cm from any. */
        fun apart(hold: WallHold, target: Target) = taken.indices.all { i ->
            distance(hold, taken[i]) >= (if (takenColours[i] == pattern[target.stripe]) SPOT_SPACING_M else MIN_GAP_M) - SPACING_SLACK_M
        }
        /** [hold] doesn't touch any spot: at least [MIN_GAP_M] from all. */
        fun clear(hold: WallHold) = taken.all { distance(hold, it) >= MIN_GAP_M - SPACING_SLACK_M }

        /** Where a target's spot goes across its stripe: left or right by turns. */
        fun targetX(target: Target) = start + stripeWidth * (target.stripe + if ((target.index + target.stripe) % 2 == 0) 0.25 else 0.75)

        /** The nearest free hold to the target's place, at its height. */
        fun nearest(target: Target): WallHold? {
            val x = targetX(target)
            // Lit holds never touch: rather no spot than one next to another.
            val free = zone(target).filter { it.placementId !in takenIds && clear(it) }
            return free.filter { apart(it, target) }.ifEmpty { free }.minWithOrNull(
                compareBy<WallHold> { hypot(xM(it) - x, heightM(it) - target.heightM) - if (it.placementId in bigHolds) BIG_HOLD_BONUS_M else 0.0 }
                    .thenBy { it.placementId },
            )
        }

        // The hardest fifth of the rated hand holds in the field stays dark.
        val grades = draw?.handGrades.orEmpty()
        val rated = wall.filter { it.placementId in grades && handHold(it) && xM(it) in start..start + width }
            .sortedByDescending { grades.getValue(it.placementId) }
        val hardest = rated.take(ceil(rated.size * HARDEST_SHARE).toInt()).mapTo(mutableSetOf()) { it.placementId }
        val usual = rated.map { grades.getValue(it.placementId) }.let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

        /** How far [hold] is from the nearest spot placed so far. */
        fun gap(hold: WallHold) = taken.minOfOrNull { distance(hold, it) } ?: Double.MAX_VALUE

        /**
         * A free hold about at the target's height, drawn with [random]; each condition gives way when nothing meets it,
         * the spacing last. When no free hold keeps the spacing, the one farthest from the spots so far. [hardOut]: none
         * of the hardest hand holds where another keeps the spacing.
         */
        fun drawn(target: Target, random: Random, avoid: Set<Int>, choices: Int, hardOut: Boolean): WallHold? {
            val free = zone(target).filter { it.placementId !in takenIds && clear(it) }
            if (free.isEmpty()) return null
            val spaced = free.filter { apart(it, target) }
            val good = spaced.filter { !hardOut || !target.hand || it.placementId !in hardest }
            fun near(hold: WallHold) = abs(heightM(hold) - target.heightM) <= target.bandM / 2 + HEIGHT_SLACK_M
            val pool = listOf(
                good.filter { near(it) && it.placementId !in avoid },
                good.filter { it.placementId !in avoid },
                good.filter(::near),
                good,
                // Rather a hard hold than two spots side by side.
                spaced,
            ).firstOrNull { it.isNotEmpty() } ?: return free.maxWith(compareBy<WallHold> { gap(it) }.thenBy { -it.placementId })
            // Among the few nearest to the spot's place, so a dense field still packs like the nearest holds do.
            val x = targetX(target)
            val nearby = pool.sortedWith(compareBy<WallHold> { hypot(xM(it) - x, heightM(it) - target.heightM) }.thenBy { it.placementId }).take(choices)
            // A big hold counts as several tickets.
            val tickets = nearby.flatMap { hold -> List(if (hold.placementId in bigHolds) BIG_HOLD_TICKETS else 1) { hold } }
            return tickets[random.nextInt(tickets.size)]
        }

        /** The smallest distance between two spots, of one colour ([sameColour]) or of any. */
        fun closest(stripes: List<Pair<List<Int>, List<Int>>>, sameColour: Boolean): Double {
            val spots = stripes.flatMapIndexed { i, (hands, feet) -> (hands + feet).map { rows.holdOf(it)!! to pattern[i] } }
            return spots.flatMapIndexed { i, (a, colour) ->
                spots.drop(i + 1).filter { !sameColour || it.second == colour }.map { (b, _) -> distance(a, b) }
            }.minOrNull() ?: Double.MAX_VALUE
        }

        /** Spots closer than [SPOT_SPACING_M] to one of their own colour, or than [MIN_GAP_M] to any. */
        fun crowded(stripes: List<Pair<List<Int>, List<Int>>>): Int {
            val spots = stripes.flatMapIndexed { i, (hands, feet) -> (hands + feet).map { rows.holdOf(it)!! to pattern[i] } }
            return spots.count { (a, colour) ->
                spots.any { (b, other) ->
                    b !== a && distance(a, b) < (if (other == colour) SPOT_SPACING_M else MIN_GAP_M) - SPACING_SLACK_M
                }
            }
        }

        fun build(choose: (Target) -> WallHold?): List<Pair<List<Int>, List<Int>>> {
            taken.clear()
            takenColours.clear()
            takenIds.clear()
            val hands = List(count) { mutableListOf<Int>() }
            val feet = List(count) { mutableListOf<Int>() }
            for (target in targets) {
                val hold = choose(target) ?: continue
                taken += hold
                takenColours += pattern[target.stripe]
                takenIds += hold.placementId
                (if (target.hand) hands else feet)[target.stripe] += hold.placementId
            }
            return hands.zip(feet)
        }

        /**
         * From the middle of every climber's part, each colour has [holds] hand and foot spots within the spacing's reach
         * (up to half a stripe or [REACH_SLACK_M] beyond it). A stripe without free holds can't leave a colour out of
         * reach that way.
         */
        fun inReach(stripes: List<Pair<List<Int>, List<Int>>>, hands: Boolean): Boolean {
            val need = if (hands) holds.hands else holds.feet
            val spots = stripes.indices.flatMap { i -> (if (hands) stripes[i].first else stripes[i].second).map { xM(rows.holdOf(it)!!) to pattern[i] } }
            return middles.all { middle ->
                colours.indices.all { colour -> spots.count { (x, c) -> c == colour && abs(x - middle) <= reachLimit + ROUNDING } >= need }
            }
        }
        fun playable(stripes: List<Pair<List<Int>, List<Int>>>) = inReach(stripes, hands = true) && inReach(stripes, hands = false)

        /** How far apart the easiest and hardest colour, and the easiest and hardest climber's part, are on average. */
        fun unfairness(stripes: List<Pair<List<Int>, List<Int>>>): Double {
            val spots = stripes.flatMapIndexed { i, (hands, _) -> hands.map { id -> Triple(pattern[i], rows.holdOf(id)!!, grades[id] ?: usual) } }
            fun spread(groups: Collection<List<Double>>) = groups.filter { it.isNotEmpty() }.map { it.average() }.let { if (it.isEmpty()) 0.0 else it.max() - it.min() }
            val byColour = spots.groupBy({ it.first }, { it.third }).values
            val byPart = spots.groupBy({ ((xM(it.second) - start) / (width / climbers)).toInt().coerceIn(0, climbers - 1) }, { it.third }).values
            return spread(byColour) + spread(byPart)
        }

        val plain = build(::nearest).takeIf(::playable)
        val chosen = if (draw == null || plain == null) {
            plain
        } else {
            val random = Random(draw.seed)
            // The spacing comes first: no draw may put two spots of a colour, or any two spots, closer together than the
            // nearest holds do (where those keep 30 and 20 cm, it keeps them too). Of the rest, those with the fewest
            // spots too close to another, then the fewest of the hardest holds, then the fairest. Should no draw keep it,
            // the nearest holds light.
            val sameFloor = minOf(closest(plain, sameColour = true), SPOT_SPACING_M - SPACING_SLACK_M)
            val anyFloor = minOf(closest(plain, sameColour = false), MIN_GAP_M - SPACING_SLACK_M)
            // A dense field may not pack with free choice: then with less of it. Where few hand holds are left, leaving out
            // the hardest one by one can corner a later spot: then the hardest may light too, as few as can be.
            val tries = listOf(true, false).filter { it || hardest.isNotEmpty() }.flatMap { hardOut -> DRAW_CHOICES.map { hardOut to it } }
            tries.asSequence().map { (hardOut, choices) ->
                // With one choice there is nothing to draw: the nearest holds that weren't in the last game.
                List(if (choices == 1) 1 else DRAWS) { build { drawn(it, random, draw.avoid, choices, hardOut) } }
                    .filter { playable(it) && closest(it, sameColour = true) >= sameFloor && closest(it, sameColour = false) >= anyFloor }
            }.firstOrNull { it.isNotEmpty() }.orEmpty()
                .minWithOrNull(
                    compareBy<List<Pair<List<Int>, List<Int>>>>(
                        { crowded(it) },
                        // Rather an easy hold than the spacing broken, but the fewer of the hardest the better.
                        { stripes -> stripes.sumOf { (hands, _) -> hands.count { it in hardest } } },
                        { unfairness(it) },
                    ),
                )
                ?: plain
        }
        return chosen?.let { KnotLayout(climbers, it, pattern, offset) }
    }

    /**
     * Where the field for [climbers] on [rows] may sit, right of the wall's middle: there first, then up to
     * [FIELD_SHIFT_M] to either side in steps of [SHIFT_STEP_M], as long as it stays on the wall. A field that takes the
     * whole wall stays where it is.
     */
    private fun fieldOffsets(rows: WallRows, climbers: Int, metresPerUnit: Double): List<Double> {
        val left = rows.holds.minOf { it.x } * metresPerUnit
        val right = rows.holds.maxOf { it.x } * metresPerUnit
        val room = (right - left + EDGE_M - fieldWidthM(climbers)) / 2
        val steps = (1..(FIELD_SHIFT_M / SHIFT_STEP_M).roundToInt()).map { it * SHIFT_STEP_M }.filter { it <= room + ROUNDING }
        return listOf(0.0) + steps.flatMap { listOf(it, -it) }
    }

    /**
     * How far the colours really are in [layout] on [rows]: from the middle of each climber's part, the farthest of
     * the colours' nearest hand spots ([reach] over the field as [layout] lays it out).
     */
    fun handReach(rows: WallRows, layout: KnotLayout, metresPerUnit: Double): Double {
        val (start, width) = fieldSpan(rows, layout.climbers, metresPerUnit, layout.offsetM)
        val spots = layout.stripes.indices.flatMap { i -> layout.stripes[i].first.map { rows.holdOf(it)!!.x * metresPerUnit to layout.pattern[i] } }
        return reach(start, width, layout.climbers, spots, layout.pattern.maxOrNull()?.plus(1) ?: 0).first
    }

    /**
     * Where the field for [climbers] on [rows] begins and how wide it is, in metres, as [layout] lays it out: centred on
     * the wall, moved by [offsetM] ([KnotLayout.offsetM]). A field wider than the wall takes the whole wall; the outer
     * holds sit half a grid inside its edges.
     */
    fun fieldSpan(rows: WallRows, climbers: Int, metresPerUnit: Double, offsetM: Double = 0.0): Pair<Double, Double> {
        val left = rows.holds.minOf { it.x } * metresPerUnit
        val right = rows.holds.maxOf { it.x } * metresPerUnit
        val width = minOf(fieldWidthM(climbers), right - left + EDGE_M)
        return (left + right) / 2 - width / 2 + offsetM to width
    }

    /**
     * For each climber's part of [layout] on [rows] at [spacing]: the colours (as [layout]'s pattern numbers) that have
     * only one foot spot within reach, by the same [reachLimitM] that [layout] counts a climber's spots with. Both of a
     * climber's feet are never called to such a colour.
     */
    fun singleFootColours(rows: WallRows, layout: KnotLayout, metresPerUnit: Double, spacing: KnotSpacing): List<Set<Int>> {
        val (start, width) = fieldSpan(rows, layout.climbers, metresPerUnit, layout.offsetM)
        val limit = reachLimitM(spacing, width / layout.stripes.size.coerceAtLeast(1))
        val feet = layout.stripes.indices.flatMap { i -> layout.stripes[i].second.map { rows.holdOf(it)!!.x * metresPerUnit to layout.pattern[i] } }
        return List(layout.climbers) { part ->
            val middle = start + width / layout.climbers * (part + 0.5)
            feet.filter { abs(it.first - middle) <= limit + ROUNDING }.groupingBy { it.second }.eachCount()
                .filterValues { it == 1 }.keys
        }
    }

    /**
     * How far from the middle of a climber's part a spot still counts as within [spacing]'s reach, in stripes
     * [stripeWidthM] wide: the reach is measured at the stripes' middles, and a spot lies anywhere in its stripe, so half
     * a stripe beyond it, at least [REACH_SLACK_M].
     */
    fun reachLimitM(spacing: KnotSpacing, stripeWidthM: Double): Double = spacing.reachM + maxOf(REACH_SLACK_M, stripeWidthM / 2)

    /**
     * How far beyond its spacing's reach a colour may sit from the middle of a climber's part, at least, before the field
     * doesn't count as playable (see [reachLimitM]).
     */
    const val REACH_SLACK_M = 0.15

    /** Fields drawn for one game, of which the fairest lights. */
    const val DRAWS = 24
    /** A drawn spot takes one of this many free holds nearest to its place; fewer where a dense field doesn't pack. */
    private val DRAW_CHOICES = listOf(4, 2, 1)
    /** The hardest share of the rated hand holds that never becomes a spot. */
    const val HARDEST_SHARE = 0.2

    /** Room beyond the outer holds when a field takes the whole wall. */
    private const val EDGE_M = 0.2
    /**
     * How far a field may move from the wall's middle when it doesn't fit there. With stripes about as wide as two columns
     * of holds, the centred field can put a column on a stripe's edge and leave the stripe next to it a single column.
     */
    const val FIELD_SHIFT_M = 0.1
    private const val SHIFT_STEP_M = 0.0125
    /** A big hold this much farther from its target still wins. */
    private const val BIG_HOLD_BONUS_M = 0.1
    /** In a draw, a big hold is this many times as likely as another. */
    private const val BIG_HOLD_TICKETS = 3
    /** A drawn spot may sit this much above or below its share of the height. */
    private const val HEIGHT_SLACK_M = 0.1
    /** Grid rounding: holds 29 cm apart on a 30 cm spacing still count as apart. */
    private const val SPACING_SLACK_M = 0.02

    /** MoonBoard `frames` for [spots]: each colour as the role the board lights in it. */
    fun moonBoardFrames(spots: Map<Int, KnotColour>): String = spots.entries.sortedBy { it.key }.joinToString("") { (id, colour) ->
        "p${id}r${requireNotNull(colour.moonRole) { "The MoonBoard has no $colour" }}"
    }

    /**
     * Where each climber starts: the field split into [KnotField.climbers]
     * equal parts from the left, as stripe ranges (a part may share a stripe
     * with its neighbour).
     */
    fun startStripes(field: KnotField): List<IntRange> {
        val n = field.climbers
        val count = field.stripes.size
        return (0 until n).map { part ->
            val from = (part * count) / n
            val to = ((part + 1) * count + n - 1) / n - 1
            from..maxOf(from, to)
        }
    }
}

/**
 * The calls of one game from a [seed]: the parts of the field ([places] of
 * them, from the left) in a fixed order, skipping those without a climber
 * ready, each call a random hand or foot and colour, never the same hand or
 * foot and colour twice in a row for one climber.
 */
class KnotCaller(
    seed: Long,
    private val places: Int,
    private val colours: List<KnotColour>,
    /** Per part, the colours with only one foot spot within reach: never both feet of a climber there. */
    private val singleFoot: List<Set<KnotColour>> = emptyList(),
) {
    init {
        require(places in Knot.PLAYERS) { "$places places" }
        require(colours.isNotEmpty())
    }

    private val random = Random(seed)
    private val last = mutableMapOf<Int, Pair<KnotLimb, KnotColour>>()
    /** Where each climber's feet were called last. */
    private val feet = mutableMapOf<Int, MutableMap<KnotLimb, KnotColour>>()
    private var turn = -1
    private var number = 0

    /** The next call among [stillIn] (climber numbers from 0, each on the part of the same number), or null when nobody is in. */
    fun next(stillIn: Set<Int>): KnotCall? = next(stillIn.associateWith { it })

    /** The next call to the climbers on [ready] (part → climber), or null when none is ready. */
    fun next(ready: Map<Int, Int>): KnotCall? {
        val place = (1..places).map { (turn + it) % places }.firstOrNull { it in ready } ?: return null
        val climber = ready.getValue(place)
        val lone = singleFoot.getOrNull(place).orEmpty()
        val otherFoot = feet[climber].orEmpty()
        val options = KnotLimb.entries.flatMap { limb -> colours.map { limb to it } }
            .filter { it != last[climber] }
            // The one foot spot of a colour already holds this climber's other foot.
            .filterNot { (limb, colour) -> !limb.isHand && colour in lone && otherFoot[limb.otherFoot] == colour }
        val (limb, colour) = options[random.nextInt(options.size)]
        last[climber] = limb to colour
        if (!limb.isHand) feet.getOrPut(climber) { mutableMapOf() }[limb] = colour
        turn = place
        number++
        return KnotCall(number, climber, limb, colour)
    }
}

/**
 * Tells hand holds from foot holds by the board's graded climbs at its angle, fed in with [add], and rates the hand
 * holds for a [KnotDraw]. A hold is a hand hold when at least [minClimbs] climbs use it as a hand (start, hand or
 * finish) and no more use it as a foot. Its rating is the average grade of the climbs that use it as a hand.
 */
class KnotHandGrades(private val minClimbs: Int = MIN_CLIMBS) {
    private val sums = HashMap<Int, Double>()
    private val hands = HashMap<Int, Int>()
    private val feet = HashMap<Int, Int>()

    fun add(frames: String, grade: Double) {
        // Frames that name mounting holes instead of placements say nothing about the holds.
        if (frames.trimStart().startsWith('h')) return
        BoardClimbParser.forEachHold(frames) { placement, role ->
            if (HoldRole.roleClass(role) == HoldRole.FOOT) {
                feet[placement] = (feet[placement] ?: 0) + 1
            } else {
                sums[placement] = (sums[placement] ?: 0.0) + grade
                hands[placement] = (hands[placement] ?: 0) + 1
            }
        }
    }

    private fun isHand(placement: Int): Boolean {
        val asHand = hands[placement] ?: 0
        return asHand >= minClimbs && (feet[placement] ?: 0) <= asHand
    }

    /** The hand holds, each with its rating. */
    fun grades(): Map<Int, Double> = hands.keys.filter(::isHand).associateWith { sums.getValue(it) / hands.getValue(it) }

    /**
     * Holds in at least [minClimbs] climbs that aren't hand holds: mostly a foot, or rarely a hand. They never take a
     * hand spot. A hold in fewer climbs says too little and stays open, so a board with few climbs at its angle keeps
     * its field.
     */
    fun feetOnly(): Set<Int> = (hands.keys + feet.keys).filterTo(mutableSetOf()) { placement ->
        (hands[placement] ?: 0) + (feet[placement] ?: 0) >= minClimbs && !isHand(placement)
    }

    companion object {
        /** Fewer climbs than this say too little about a hold. */
        const val MIN_CLIMBS = 20
    }
}
