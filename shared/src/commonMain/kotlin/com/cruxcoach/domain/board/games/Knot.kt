package com.cruxcoach.domain.board.games

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Knot (#154): a party game on a lit field of colour stripes. The app calls a
 * hand or a foot and a colour, climber by climber ("Player 2: right hand on
 * blue"), and the climbers have to get there without coming off. The field
 * grows with the number of climbers, so they always get in each other's way,
 * and stays low, so falls are short. The last climber on the wall wins; solo,
 * the score is the number of calls held.
 *
 * Everything here is pure. Lengths are in metres; a board's own units come
 * in through [metresPerUnit].
 */

/** A hand or a foot. */
enum class KnotLimb(val isHand: Boolean) {
    LEFT_HAND(true), RIGHT_HAND(true), LEFT_FOOT(false), RIGHT_FOOT(false),
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

/** The lit field of one round: stripes from left to right. */
data class KnotField(val climbers: Int, val stripes: List<KnotStripe>) {
    /** Every spot with its colour. */
    val spots: Map<Int, KnotColour> get() = stripes.flatMap { s -> s.spots.map { it to s.colour } }.toMap()

    val colours: List<KnotColour> get() = stripes.map { it.colour }

    /** The field without the spots [call] goes to, [colour]'s hand or foot spots: the dark half of its blink. */
    fun without(colour: KnotColour, hands: Boolean): Map<Int, KnotColour> {
        val called = stripes.filter { it.colour == colour }.flatMapTo(mutableSetOf()) { if (hands) it.hands else it.feet }
        return spots.filterKeys { it !in called }
    }
}

/**
 * Where a field's spots sit, before the round gives the stripes their
 * colours: stripes from left to right, each with hand and foot spots.
 */
data class KnotLayout(val climbers: Int, val stripes: List<Pair<List<Int>, List<Int>>>) {
    /** This layout lit with [order], one colour per stripe from the left. */
    fun field(order: List<KnotColour>): KnotField {
        require(order.size == stripes.size) { "${order.size} colours for ${stripes.size} stripes" }
        return KnotField(climbers, stripes.zip(order) { (hands, feet), colour -> KnotStripe(colour, hands, feet) })
    }
}

/**
 * How far apart the spots are, and so how far the climbers reach: about 30, 45 or 60 cm. The field keeps its width,
 * so a wider spacing fits fewer spots. Foot spots stay at most 45 cm apart: the foot zone is only about 55 cm tall.
 */
enum class KnotSpacing(val handsM: Double) {
    CLOSE(0.30), MEDIUM(0.45), WIDE(0.60);

    val feetM: Double get() = minOf(handsM, MEDIUM.handsM)
}

/** One call: [number] counts from 1; [climber] counts from 0 in the fixed order. */
data class KnotCall(val number: Int, val climber: Int, val limb: KnotLimb, val colour: KnotColour)

object Knot {
    /** At most this many at the wall at once: with more, someone always hangs below someone else. */
    const val MAX_CLIMBERS = 3
    val CLIMBERS: IntRange = 1..MAX_CLIMBERS

    /** Field width per climber, plus [FIELD_EXTRA_M]: 1.2 m solo, 2.0 m for two, 2.8 m for three. */
    const val FIELD_PER_CLIMBER_M = 0.8
    const val FIELD_EXTRA_M = 0.4

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

    /** Spots of one colour are kept about this far apart when the board allows it. */
    const val SPOT_SPACING_M = 0.3

    /** Above this angle the game suggests level 1 or a flatter wall. */
    const val STEEP_DEGREES = 35

    /** Seconds per call by level 1..5. */
    private val CALL_MS = longArrayOf(10_000, 8_000, 6_000, 4_000, 3_000)

    fun callMs(level: Int): Long = CALL_MS[level.coerceIn(GAME_LEVELS) - 1]

    fun fieldWidthM(climbers: Int): Double = FIELD_PER_CLIMBER_M * climbers + FIELD_EXTRA_M

    /** Spots per colour: one more hand spot than climbers, one foot spot per climber (2 + 1, 3 + 2, 4 + 3). */
    fun handSpots(climbers: Int): Int = climbers + 1
    fun footSpots(climbers: Int): Int = climbers

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

    private const val INCH_M = 0.0254
    private const val CENTIMETRE_M = 0.01
    /** MoonBoard holds sit on a grid of about 20 cm. */
    private const val MOON_CELL_M = 0.2

    /**
     * How many climbers fit at once: 3 where the holds span at least
     * 2.4 m (0.8 m each), down to 1; 0 on a wall without holds.
     */
    fun maxClimbers(rows: WallRows, metresPerUnit: Double): Int {
        if (rows.isEmpty()) return 0
        val span = (rows.holds.maxOf { it.x } - rows.holds.minOf { it.x }) * metresPerUnit
        return CLIMBERS.lastOrNull { span >= FIELD_PER_CLIMBER_M * it - SPAN_SLACK_M } ?: 1
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
     * The field for [climbers] on [rows]: [stripes] stripes side by side,
     * centred on the wall and [fieldWidthM] wide in all (the whole wall when
     * it is narrower), each with up to [handSpots] hand spots from
     * [HANDS_FROM_M] to [HANDS_TO_M] and up to [footSpots] foot spots up to
     * [FEET_TO_M].
     *
     * The spots zigzag up each stripe at evenly spread heights; each target
     * takes the nearest free hold of its zone, a big hold ([bigHolds], from
     * the hold types once a board's are confirmed) when one is about as near.
     * [KnotSpacing.CLOSE] keeps [SPOT_SPACING_M] from every other spot where
     * it can. A wider [spacing] keeps its distance between any two spots
     * strictly, so fewer spots fit in the same field: the stripes take their
     * spots in turn, each stripe its lowest first, and a spot that doesn't
     * fit is left out. The same wall, climbers and spacing always give the
     * same layout.
     *
     * Null when [climbers] don't fit ([maxClimbers]), a stripe gets no hand
     * or no foot spot, or the field has fewer than [MIN_SPOTS_PER_LIMB] spots
     * per hand or per foot on the wall.
     */
    fun layout(
        rows: WallRows,
        climbers: Int,
        stripes: Int,
        metresPerUnit: Double,
        bigHolds: Set<Int> = emptySet(),
        spacing: KnotSpacing = KnotSpacing.CLOSE,
    ): KnotLayout? {
        if (climbers !in CLIMBERS || stripes < 1 || climbers > maxClimbers(rows, metresPerUnit)) return null
        val holds = rows.holds
        val lowest = holds.minOf { it.y }
        fun heightM(hold: WallHold) = LOWEST_ROW_M + (hold.y - lowest) * metresPerUnit
        val left = holds.minOf { it.x } * metresPerUnit
        val right = holds.maxOf { it.x } * metresPerUnit
        val centre = (left + right) / 2
        // A field wider than the wall takes the whole wall; the outer holds sit half a grid inside its edges.
        val half = minOf(fieldWidthM(climbers), right - left + EDGE_M) / 2
        val stripeWidth = 2 * half / stripes
        val stripeOf = { hold: WallHold ->
            ((hold.x * metresPerUnit - (centre - half)) / stripeWidth).toInt()
                .takeIf { hold.x * metresPerUnit >= centre - half && hold.x * metresPerUnit <= centre + half }
                ?.coerceAtMost(stripes - 1)
        }
        val byStripe = holds.groupBy(stripeOf)
        // Spacing counts across stripes too: neighbouring colours don't end up on adjacent holds.
        val taken = mutableListOf<WallHold>()
        fun apart(hold: WallHold, distanceM: Double) = taken.all {
            hypot((hold.x - it.x) * metresPerUnit, (hold.y - it.y) * metresPerUnit) >= distanceM - SPACING_SLACK_M
        }

        /** Target [i] of [count] in stripe [index]: zigzag across the stripe, evenly up the zone. */
        fun place(index: Int, i: Int, count: Int, from: Double, to: Double, firstLeft: Boolean, shift: Double, strictM: Double?): Int? {
            val middle = centre - half + stripeWidth * (index + 0.5)
            val targetX = middle + stripeWidth / 4 * if ((i % 2 == 0) == firstLeft) -1 else 1
            val targetH = from + (to - from) * ((i + 0.5 + shift) / count).coerceIn(0.0, 1.0)
            fun distance(hold: WallHold) = hypot(hold.x * metresPerUnit - targetX, heightM(hold) - targetH)
            val free = byStripe[index].orEmpty().filter { heightM(it) in from..to && it !in taken }
            val pool = if (strictM != null) free.filter { apart(it, strictM) }
            else free.filter { apart(it, SPOT_SPACING_M) }.ifEmpty { free }
            return pool.minWithOrNull(
                compareBy<WallHold> { distance(it) - if (it.placementId in bigHolds) BIG_HOLD_BONUS_M else 0.0 }
                    .thenBy { it.placementId },
            )?.also { taken += it }?.placementId
        }

        val handCount = handSpots(climbers)
        val footCount = footSpots(climbers)
        fun close(): List<Pair<List<Int>, List<Int>>> = (0 until stripes).map { index ->
            val hands = (0 until handCount).mapNotNull { place(index, it, handCount, HANDS_FROM_M, HANDS_TO_M, true, 0.0, null) }
            val feet = (0 until footCount).mapNotNull { place(index, it, footCount, 0.0, FEET_TO_M, false, 0.0, null) }
            hands to feet
        }

        /**
         * The stripes take their spots in turn, so none takes the room its neighbours need; neighbours sit half a step
         * apart in height. [mirrored] zigzags neighbouring stripes the opposite way.
         */
        fun spaced(mirrored: Boolean): List<Pair<List<Int>, List<Int>>> {
            taken.clear()
            val hands = List(stripes) { mutableListOf<Int>() }
            val feet = List(stripes) { mutableListOf<Int>() }
            fun shift(index: Int) = if (index % 2 == 0) -STRICT_SHIFT else STRICT_SHIFT
            fun firstLeft(index: Int, hand: Boolean) = !mirrored || (index % 2 == 0) == hand
            for (i in 0 until handCount) for (index in 0 until stripes) {
                place(index, i, handCount, HANDS_FROM_M, HANDS_TO_M, firstLeft(index, true), shift(index), spacing.handsM)
                    ?.let { hands[index] += it }
            }
            for (i in 0 until footCount) for (index in 0 until stripes) {
                place(index, i, footCount, 0.0, FEET_TO_M, firstLeft(index, false), shift(index), spacing.feetM)
                    ?.let { feet[index] += it }
            }
            return hands.zip(feet)
        }

        val limbs = 2 * climbers
        fun playable(layout: List<Pair<List<Int>, List<Int>>>) =
            layout.none { (hands, feet) -> hands.isEmpty() || feet.isEmpty() } &&
                layout.sumOf { it.first.size } >= MIN_SPOTS_PER_LIMB * limbs &&
                layout.sumOf { it.second.size } >= MIN_SPOTS_PER_LIMB * limbs
        val candidates = if (spacing == KnotSpacing.CLOSE) listOf(close()) else listOf(spaced(mirrored = false), spaced(mirrored = true))
        // Of the playable ones, the fullest; the first on a tie.
        val best = candidates.filter(::playable).maxByOrNull { layout -> layout.sumOf { it.first.size + it.second.size } } ?: return null
        return KnotLayout(climbers, best)
    }

    /** At least this many spots per hand and per foot on the wall, so a call always has somewhere to go. */
    const val MIN_SPOTS_PER_LIMB = 1.5

    /** Neighbouring stripes' targets sit this share of a step lower and higher. */
    private const val STRICT_SHIFT = 0.25

    /** Room beyond the outer holds when a field takes the whole wall. */
    private const val EDGE_M = 0.2
    /** A big hold this much farther from its target still wins. */
    private const val BIG_HOLD_BONUS_M = 0.1
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
 * The calls of one game from a [seed]: climbers in a fixed order (the ones
 * still in), each call a random hand or foot and colour, never the same hand
 * or foot and colour twice in a row for one climber.
 */
class KnotCaller(seed: Long, private val climbers: Int, private val colours: List<KnotColour>) {
    init {
        require(climbers in Knot.CLIMBERS) { "$climbers climbers" }
        require(colours.isNotEmpty())
    }

    private val random = Random(seed)
    private val last = arrayOfNulls<Pair<KnotLimb, KnotColour>>(climbers)
    private var turn = -1
    private var number = 0

    /** The next call among [stillIn] (climber numbers from 0), or null when nobody is in. */
    fun next(stillIn: Set<Int>): KnotCall? {
        val climber = (1..climbers).map { (turn + it) % climbers }.firstOrNull { it in stillIn } ?: return null
        val options = KnotLimb.entries.flatMap { limb -> colours.map { limb to it } }.filter { it != last[climber] }
        val (limb, colour) = options[random.nextInt(options.size)]
        last[climber] = limb to colour
        turn = climber
        number++
        return KnotCall(number, climber, limb, colour)
    }
}
