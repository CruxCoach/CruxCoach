package com.cruxcoach.domain.holdtypes

/**
 * The first hold-type stats (#63, owner's choice of 2026-11-07: options 1 and 2 of the all-boards report).
 *
 * 1. **Hold mix:** the share of each type among the hand holds of your sends, next to the same share for the board's
 *    problems ([holdMix], [compareMix]).
 * 2. **Strong and weak types:** for each type, your send rate and hardest send on problems where that type dominates,
 *    against your level on all problems of the board ([typeStrengths]).
 *
 * Only hand holds count (start, middle and finish; never foot holds or foot-only holds), and only labels of at least
 * [MIN_CONFIDENCE] type confidence. Every result says how many holds or problems it rests on. Pure: the app layer reads
 * the logbook and the catalogue and passes [TypedClimb]s in.
 */
object HoldTypeStats {
    /**
     * Every typed hold counts until the blind rounds calibrate the confidences. The first-pass values are lower for
     * the types that are harder to see in a picture: every Kilter sloper and all but two Kilter pinches are below
     * medium (0.4), so a threshold there would leave only edges and jugs in the stats.
     */
    const val MIN_CONFIDENCE = 0.0

    /** A type dominates a problem when at least this share of its typed hand holds has it. */
    const val DOMINANCE = 0.4

    /** Fewer problems than this for a type give no strong/weak verdict. */
    const val MIN_PROBLEMS = 5

    /** The hand-hold types counted in the stats: foot chips are left out like foot-only holds. */
    val HAND_TYPES: List<HoldType> = listOf(
        HoldType.JUG, HoldType.EDGE, HoldType.SLOPER, HoldType.PINCH, HoldType.POCKET, HoldType.VOLUME,
    )

    /** Placement ids (MoonBoard: hold ids) and roles of a `frames` string: `p<id>r<role>` pairs, in order. */
    fun framesHolds(frames: String): List<Pair<Int, Int>> =
        FRAME_TOKEN.findAll(frames).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()

    /** The hand holds of a climb: every hold whose role is not one of [footRoles]. */
    fun handHolds(frames: String, footRoles: Set<Int>): List<Int> =
        framesHolds(frames).filter { (_, role) -> role !in footRoles }.map { it.first }.distinct()

    /** The typed hand holds of [climb] that count: known type, confident enough, not foot only, not a foot chip. */
    fun countedTypes(climb: TypedClimb, dataset: HoldTypeDataset): List<HoldType> = climb.handHolds.mapNotNull { id ->
        val entry = dataset.holdOnClimb(climb.layoutId, id) ?: return@mapNotNull null
        val type = entry.holdType ?: return@mapNotNull null
        type.takeIf { !entry.footOnly && it in HAND_TYPES && entry.confidence.type >= MIN_CONFIDENCE }
    }

    /**
     * Option 1: the share of each type among the counted hand holds of [climbs], each problem weighted equally (a
     * problem with eight holds does not count twice as much as one with four).
     */
    fun holdMix(climbs: List<TypedClimb>, datasets: Map<String, HoldTypeDataset>): HoldMix {
        val weights = HAND_TYPES.associateWith { 0.0 }.toMutableMap()
        var problems = 0
        var holds = 0
        var handHolds = 0
        for (climb in climbs) {
            val dataset = datasets[climb.board] ?: continue
            handHolds += climb.handHolds.size
            val types = countedTypes(climb, dataset)
            if (types.isEmpty()) continue
            problems++
            holds += types.size
            types.forEach { weights[it] = weights.getValue(it) + 1.0 / types.size }
        }
        val shares = if (problems == 0) HAND_TYPES.associateWith { 0.0 } else weights.mapValues { it.value / problems }
        return HoldMix(shares = shares, problems = problems, countedHolds = holds, handHolds = handHolds)
    }

    /** Option 1, side by side: your share minus the board's, per type, in percentage points. */
    fun compareMix(yours: HoldMix, board: HoldMix): List<MixDifference> = HAND_TYPES.map { type ->
        val a = yours.share(type)
        val b = board.share(type)
        MixDifference(type, yours = a, board = b, points = (a - b) * 100)
    }

    /**
     * Option 2: per type, how you do on the problems of one board where it dominates, against all your problems on
     * that board. [tried] holds one entry per problem you logged (sent or not); the grade is on one scale per board.
     */
    fun typeStrengths(tried: List<TypedClimb>, dataset: HoldTypeDataset): TypeStrengths {
        val overall = level(tried)
        val perType = HAND_TYPES.map { type ->
            val dominated = tried.filter { climb ->
                val types = countedTypes(climb, dataset)
                types.isNotEmpty() && types.count { it == type }.toDouble() / types.size >= DOMINANCE
            }
            val level = level(dominated)
            val strength = when {
                level.problems < MIN_PROBLEMS || overall.sendRate == null || level.sendRate == null -> Strength.TOO_FEW
                level.sendRate - overall.sendRate >= STRONG_MARGIN -> Strength.STRONG
                overall.sendRate - level.sendRate >= STRONG_MARGIN -> Strength.WEAK
                else -> Strength.AS_USUAL
            }
            TypeLevel(type, level, strength)
        }
        return TypeStrengths(overall, perType)
    }

    private fun level(climbs: List<TypedClimb>): Level {
        val sends = climbs.filter { it.sent }
        return Level(
            problems = climbs.size,
            sends = sends.size,
            sendRate = if (climbs.isEmpty()) null else sends.size.toDouble() / climbs.size,
            hardestSend = sends.mapNotNull { it.grade }.maxOrNull(),
        )
    }

    /** Send rates this far apart (15 points) mark a type as strong or weak. */
    private const val STRONG_MARGIN = 0.15
    private val FRAME_TOKEN = Regex("""p(\d+)r(\d+)""")

    enum class Strength { STRONG, WEAK, AS_USUAL, TOO_FEW }

    data class Level(val problems: Int, val sends: Int, val sendRate: Double?, val hardestSend: Double?)

    data class TypeLevel(val type: HoldType, val level: Level, val strength: Strength)

    data class TypeStrengths(val overall: Level, val types: List<TypeLevel>)
}

/** One logged or catalogue problem as the hold-type stats see it. */
data class TypedClimb(
    /** The board's wire name, as the hold-type files are named ("kilter", "moonboard", …). */
    val board: String,
    val layoutId: Int,
    /** Placement ids of the hand holds (MoonBoard: hold ids of that generation). */
    val handHolds: List<Int>,
    /** True for a send; false for a problem only tried. */
    val sent: Boolean = true,
    /** The grade on the board's own numeric scale, for the hardest send; null when unknown. */
    val grade: Double? = null,
)

data class HoldMix(
    /** Share of each hand type, summing to 1 when [problems] > 0. */
    val shares: Map<HoldType, Double>,
    /** Problems with at least one counted hold. */
    val problems: Int,
    /** Hand holds with a known, confident type. */
    val countedHolds: Int,
    /** All hand holds of the problems looked at, counted or not. */
    val handHolds: Int,
) {
    fun share(type: HoldType): Double = shares[type] ?: 0.0
}

data class MixDifference(val type: HoldType, val yours: Double, val board: Double, val points: Double)

/** A hold on a climb of [layoutId]: by placement id on Aurora boards, by layout and hold id on MoonBoard. */
fun HoldTypeDataset.holdOnClimb(layoutId: Int, placement: Int): HoldTypeEntry? =
    if (board == "moonboard") get(layoutId, placement) else get(placement)
