package com.cruxcoach.domain.board.games

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.HoldRole
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.ceil
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
 * the first …): the colours repeat across the field.
 */
data class KnotLayout(
    val climbers: Int,
    val stripes: List<Pair<List<Int>, List<Int>>>,
    val pattern: List<Int> = stripes.indices.toList(),
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

    /**
     * Spots per colour, over all its stripes: one more hand spot than climbers, one foot spot per climber (2 + 1,
     * 3 + 2, 4 + 3 …). A wide field shares them out among the colour's stripes.
     */
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
     * How many climbers fit at once: the span of the holds at 0.8 m each (2 on a 7x10 or a MoonBoard, 4 on a 12 ft
     * wide board, 5 on a 16x12), at least 1, at most [MAX_PLAYERS]; 0 on a wall without holds.
     */
    fun maxClimbers(rows: WallRows, metresPerUnit: Double): Int {
        if (rows.isEmpty()) return 0
        val span = (rows.holds.maxOf { it.x } - rows.holds.minOf { it.x }) * metresPerUnit
        return PLAYERS.lastOrNull { span >= FIELD_PER_CLIMBER_M * it - SPAN_SLACK_M } ?: 1
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
     * The field for [climbers] on [rows] at [spacing]: stripes side by side in [colours], centred on the wall and
     * [fieldWidthM] wide in all (the whole wall when it is narrower), as many as [stripeCount] says. Each colour has
     * [handSpots] hand spots from [HANDS_FROM_M] to [HANDS_TO_M] and [footSpots] foot spots up to [FEET_TO_M], shared
     * out among its stripes at evenly spread heights, in every spacing. Spots keep [SPOT_SPACING_M] from each other
     * where the wall allows.
     *
     * Without a [draw], each spot takes the nearest free hold to its place in the stripe, a big hold ([bigHolds],
     * from the hold types once a board's are confirmed) when one is about as near: the same wall, climbers and
     * spacing always give the same field. With a [draw], each spot is drawn among the stripe's free holds at about its
     * height, a big hold more often, not one of the hardest fifth of the rated hand holds and away from last game's
     * spots where the wall allows; the spacing goes before all of that, and where no hold keeps it the spot takes the
     * hold farthest from the others. Of [DRAWS] such fields the one with the fewest spots too close to another wins,
     * then the one whose colours and climbers' parts come closest to equally good.
     *
     * Null when [climbers] don't fit ([maxClimbers]), when from the middle of a climber's part a colour has no hand or
     * no foot spot within the spacing's reach ([REACH_SLACK_M] beyond it at most; a stripe without free holds leaves
     * its colour only farther away), or the field has fewer than [MIN_SPOTS_PER_LIMB] spots per hand or per foot.
     */
    fun layout(
        rows: WallRows,
        climbers: Int,
        colours: List<KnotColour>,
        metresPerUnit: Double,
        bigHolds: Set<Int> = emptySet(),
        spacing: KnotSpacing = KnotSpacing.CLOSE,
        draw: KnotDraw? = null,
    ): KnotLayout? {
        if (climbers !in PLAYERS || colours.isEmpty() || climbers > maxClimbers(rows, metresPerUnit)) return null
        val holds = rows.holds
        val lowest = holds.minOf { it.y }
        fun heightM(hold: WallHold) = LOWEST_ROW_M + (hold.y - lowest) * metresPerUnit
        fun xM(hold: WallHold) = hold.x * metresPerUnit
        val left = holds.minOf { it.x } * metresPerUnit
        val right = holds.maxOf { it.x } * metresPerUnit
        // A field wider than the wall takes the whole wall; the outer holds sit half a grid inside its edges.
        val width = minOf(fieldWidthM(climbers), right - left + EDGE_M)
        val start = (left + right) / 2 - width / 2
        val count = stripeCount(width, climbers, colours, spacing)
        val pattern = pattern(colours, count)
        val stripeWidth = width / count
        val byStripe = holds.groupBy { hold ->
            ((xM(hold) - start) / stripeWidth).toInt().takeIf { xM(hold) >= start && xM(hold) <= start + width }?.coerceAtMost(count - 1)
        }

        /** Spot [index] of a colour's [hand] or foot spots, in [stripe], at [heightM] in a band [bandM] tall. */
        class Target(val stripe: Int, val index: Int, val hand: Boolean, val heightM: Double, val bandM: Double)
        fun targets(hand: Boolean): List<Target> {
            val total = if (hand) handSpots(climbers) else footSpots(climbers)
            val (from, to) = if (hand) HANDS_FROM_M to HANDS_TO_M else 0.0 to FEET_TO_M
            return colours.indices.flatMap { colour ->
                val own = pattern.indices.filter { pattern[it] == colour }
                (0 until total).map { i -> Target(own[i % own.size], i, hand, from + (to - from) * (i + 0.5) / total, (to - from) / total) }
            }.sortedWith(compareBy({ it.index }, { it.stripe }))
        }
        // The stripes take turns, so none takes the room its neighbours need; hands first, then feet.
        val targets = targets(hand = true) + targets(hand = false)
        val handZones = List(count) { stripe -> byStripe[stripe].orEmpty().filter { heightM(it) in HANDS_FROM_M..HANDS_TO_M } }
        val footZones = List(count) { stripe -> byStripe[stripe].orEmpty().filter { heightM(it) <= FEET_TO_M } }
        fun zone(target: Target) = (if (target.hand) handZones else footZones)[target.stripe]

        val taken = mutableListOf<WallHold>()
        val takenIds = mutableSetOf<Int>()
        fun apart(hold: WallHold) = taken.all {
            hypot((hold.x - it.x) * metresPerUnit, (hold.y - it.y) * metresPerUnit) >= SPOT_SPACING_M - SPACING_SLACK_M
        }

        /** The nearest free hold to the target's place: left or right in the stripe by turns, at its height. */
        fun nearest(target: Target): WallHold? {
            val x = start + stripeWidth * (target.stripe + if ((target.index + target.stripe) % 2 == 0) 0.25 else 0.75)
            val free = zone(target).filter { it.placementId !in takenIds }
            return free.filter(::apart).ifEmpty { free }.minWithOrNull(
                compareBy<WallHold> { hypot(xM(it) - x, heightM(it) - target.heightM) - if (it.placementId in bigHolds) BIG_HOLD_BONUS_M else 0.0 }
                    .thenBy { it.placementId },
            )
        }

        // The hardest fifth of the rated hand holds in the field's reach stays dark.
        val grades = draw?.handGrades.orEmpty()
        val rated = holds.filter { it.placementId in grades && heightM(it) in HANDS_FROM_M..HANDS_TO_M && xM(it) in start..start + width }
            .sortedByDescending { grades.getValue(it.placementId) }
        val hardest = rated.take(ceil(rated.size * HARDEST_SHARE).toInt()).mapTo(mutableSetOf()) { it.placementId }
        val usual = rated.map { grades.getValue(it.placementId) }.let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

        /** How far [hold] is from the nearest spot placed so far. */
        fun gap(hold: WallHold) = taken.minOfOrNull { hypot((hold.x - it.x) * metresPerUnit, (hold.y - it.y) * metresPerUnit) } ?: Double.MAX_VALUE

        /**
         * A free hold about at the target's height, drawn with [random]; each condition gives way when nothing meets it,
         * the spacing last. When no free hold keeps the spacing, the one farthest from the spots so far.
         */
        fun drawn(target: Target, random: Random, avoid: Set<Int>): WallHold? {
            val free = zone(target).filter { it.placementId !in takenIds }
            if (free.isEmpty()) return null
            val spaced = free.filter(::apart)
            val good = spaced.filter { !target.hand || it.placementId !in hardest }
            fun near(hold: WallHold) = abs(heightM(hold) - target.heightM) <= target.bandM / 2 + HEIGHT_SLACK_M
            val pool = listOf(
                good.filter { near(it) && it.placementId !in avoid },
                good.filter { it.placementId !in avoid },
                good.filter(::near),
                good,
                // Rather a hard hold than two spots side by side.
                spaced,
            ).firstOrNull { it.isNotEmpty() } ?: return free.maxWith(compareBy<WallHold> { gap(it) }.thenBy { -it.placementId })
            // A big hold counts as several tickets.
            val tickets = pool.flatMap { hold -> List(if (hold.placementId in bigHolds) BIG_HOLD_TICKETS else 1) { hold } }
            return tickets[random.nextInt(tickets.size)]
        }

        /** Every spot's distance to the nearest other spot. */
        fun gaps(stripes: List<Pair<List<Int>, List<Int>>>): List<Double> {
            val spots = stripes.flatMap { it.first + it.second }.map { rows.holdOf(it)!! }
            return spots.map { a ->
                spots.filter { it !== a }.minOfOrNull { b -> hypot((a.x - b.x) * metresPerUnit, (a.y - b.y) * metresPerUnit) } ?: Double.MAX_VALUE
            }
        }

        /** Spots closer than [SPOT_SPACING_M] to another spot. */
        fun crowded(stripes: List<Pair<List<Int>, List<Int>>>): Int = gaps(stripes).count { it < SPOT_SPACING_M - SPACING_SLACK_M }

        fun build(choose: (Target) -> WallHold?): List<Pair<List<Int>, List<Int>>> {
            taken.clear()
            takenIds.clear()
            val hands = List(count) { mutableListOf<Int>() }
            val feet = List(count) { mutableListOf<Int>() }
            for (target in targets) {
                val hold = choose(target) ?: continue
                taken += hold
                takenIds += hold.placementId
                (if (target.hand) hands else feet)[target.stripe] += hold.placementId
            }
            return hands.zip(feet)
        }

        val limbs = 2 * climbers
        /** From the middle of every climber's part, each colour's nearest hand or foot spot is within the spacing's reach. */
        fun inReach(stripes: List<Pair<List<Int>, List<Int>>>, hands: Boolean): Boolean {
            val spots = stripes.indices.flatMap { i -> (if (hands) stripes[i].first else stripes[i].second).map { xM(rows.holdOf(it)!!) to pattern[i] } }
            return reach(start, width, climbers, spots, colours.size).first <= spacing.reachM + REACH_SLACK_M
        }
        fun playable(stripes: List<Pair<List<Int>, List<Int>>>) =
            inReach(stripes, hands = true) && inReach(stripes, hands = false) &&
                stripes.sumOf { it.first.size } >= MIN_SPOTS_PER_LIMB * limbs &&
                stripes.sumOf { it.second.size } >= MIN_SPOTS_PER_LIMB * limbs

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
            // The spacing comes first: no draw may put two spots closer together than the nearest holds do (where those
            // keep the spacing, it keeps it too). Of the rest, those with the fewest spots too close to another, then
            // the fairest. Should no draw keep it, the nearest holds light instead.
            val floor = minOf(gaps(plain).min(), SPOT_SPACING_M - SPACING_SLACK_M)
            List(DRAWS) { build { drawn(it, random, draw.avoid) } }
                .filter { playable(it) && gaps(it).min() >= floor }
                .minWithOrNull(compareBy<List<Pair<List<Int>, List<Int>>>>({ crowded(it) }, { unfairness(it) }))
                ?: plain
        }
        return chosen?.let { KnotLayout(climbers, it, pattern) }
    }

    /**
     * How far the colours really are in [layout] on [rows]: from the middle of each climber's part, the farthest of
     * the colours' nearest hand spots ([reach] over the field as [layout] lays it out).
     */
    fun handReach(rows: WallRows, layout: KnotLayout, metresPerUnit: Double): Double {
        val left = rows.holds.minOf { it.x } * metresPerUnit
        val right = rows.holds.maxOf { it.x } * metresPerUnit
        val width = minOf(fieldWidthM(layout.climbers), right - left + EDGE_M)
        val spots = layout.stripes.indices.flatMap { i -> layout.stripes[i].first.map { rows.holdOf(it)!!.x * metresPerUnit to layout.pattern[i] } }
        return reach((left + right) / 2 - width / 2, width, layout.climbers, spots, layout.pattern.maxOrNull()?.plus(1) ?: 0).first
    }

    /** At least this many spots per hand and per foot on the wall, so a call always has somewhere to go. */
    const val MIN_SPOTS_PER_LIMB = 1.5

    /**
     * How far beyond its spacing's reach a colour may sit from the middle of a climber's part before the field doesn't
     * count as playable: the spots lie anywhere in their stripes, not at the stripes' middles.
     */
    const val REACH_SLACK_M = 0.15

    /** Fields drawn for one game, of which the fairest lights. */
    const val DRAWS = 24
    /** The hardest share of the rated hand holds that never becomes a spot. */
    const val HARDEST_SHARE = 0.2

    /** Room beyond the outer holds when a field takes the whole wall. */
    private const val EDGE_M = 0.2
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
class KnotCaller(seed: Long, private val places: Int, private val colours: List<KnotColour>) {
    init {
        require(places in Knot.PLAYERS) { "$places places" }
        require(colours.isNotEmpty())
    }

    private val random = Random(seed)
    private val last = mutableMapOf<Int, Pair<KnotLimb, KnotColour>>()
    private var turn = -1
    private var number = 0

    /** The next call among [stillIn] (climber numbers from 0, each on the part of the same number), or null when nobody is in. */
    fun next(stillIn: Set<Int>): KnotCall? = next(stillIn.associateWith { it })

    /** The next call to the climbers on [ready] (part → climber), or null when none is ready. */
    fun next(ready: Map<Int, Int>): KnotCall? {
        val place = (1..places).map { (turn + it) % places }.firstOrNull { it in ready } ?: return null
        val climber = ready.getValue(place)
        val options = KnotLimb.entries.flatMap { limb -> colours.map { limb to it } }.filter { it != last[climber] }
        val (limb, colour) = options[random.nextInt(options.size)]
        last[climber] = limb to colour
        turn = place
        number++
        return KnotCall(number, climber, limb, colour)
    }
}

/**
 * Rates hand holds for a [KnotDraw]: the average grade of the climbs that use a hold as a hand hold (start, hand or
 * finish), for holds in at least [minClimbs] of them. Feed it the board's graded climbs at its angle with [add].
 */
class KnotHandGrades(private val minClimbs: Int = MIN_CLIMBS) {
    private val sums = HashMap<Int, Double>()
    private val counts = HashMap<Int, Int>()

    fun add(frames: String, grade: Double) {
        // Frames that name mounting holes instead of placements say nothing about the holds.
        if (frames.trimStart().startsWith('h')) return
        BoardClimbParser.forEachHold(frames) { placement, role ->
            if (HoldRole.roleClass(role) != HoldRole.FOOT) {
                sums[placement] = (sums[placement] ?: 0.0) + grade
                counts[placement] = (counts[placement] ?: 0) + 1
            }
        }
    }

    fun grades(): Map<Int, Double> = counts.filterValues { it >= minClimbs }.mapValues { (placement, count) -> sums.getValue(placement) / count }

    companion object {
        /** Fewer climbs than this say too little about a hold. */
        const val MIN_CLIMBS = 20
    }
}
