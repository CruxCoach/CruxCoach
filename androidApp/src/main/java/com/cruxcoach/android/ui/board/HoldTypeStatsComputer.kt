package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.holdtypes.HoldMix
import com.cruxcoach.domain.holdtypes.HoldType
import com.cruxcoach.domain.holdtypes.HoldTypeDataset
import com.cruxcoach.domain.holdtypes.HoldTypeStats
import com.cruxcoach.domain.holdtypes.TypedClimb

/**
 * The hold-type card of the statistics (#63): stats 1 and 2 of [HoldTypeStats] for one board, from the logbook
 * entries of the chosen period.
 *
 * Boards are not compared with each other until each board's blind round is done (owner, 2026-11-07), so the card
 * always shows one board: the board filter, or else the labelled board with the most problems in the period. The
 * board's own problems are limited to the grade range of your problems on each layout ([gradeRanges]), so the
 * comparison is about which holds you choose, not about how hard you climb.
 */
object HoldTypeStatsComputer {
    /** The board's problems are compared within your grades there, this much wider on each side (one grade step). */
    const val GRADE_MARGIN = 1.0

    /** Layout id of problems whose layout is unknown: they count for you, but not in the board comparison. */
    const val UNKNOWN_LAYOUT = 0

    /** The board the card shows: [boardFilter] when it has labels, else the labelled board with the most problems. */
    fun cardBoard(ascents: List<AscentWithClimb>, boardFilter: String?, labelled: Set<String>): String? {
        if (boardFilter != null) return boardFilter.takeIf { it in labelled }
        return ascents.filter { it.boardBrand in labelled }
            .groupBy { it.boardBrand }
            .mapValues { (_, entries) -> entries.distinctBy { it.climbUuid }.size }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { BoardBrand.fromWire(it.key).ordinal })
            .firstOrNull()?.key
    }

    /**
     * One entry per problem on [board], as the other stats count problems: per climb, across angles. A problem is
     * sent when any entry is a send; its grade is that of its hardest send, or of its hardest entry when not sent.
     */
    fun problems(ascents: List<AscentWithClimb>, board: String): List<TypedClimb> =
        ascents.filter { it.boardBrand == board }
            .groupBy { it.climbUuid }
            .mapNotNull { (_, entries) ->
                val layoutId = entries.firstNotNullOfOrNull { heatmapLoggedBoardKey(it.boardBrand, it.layoutId)?.second }
                    // MoonBoard hold ids repeat across generations: without the generation the holds are unknown.
                    ?: if (board == BoardBrand.MOONBOARD.wireValue) return@mapNotNull null else UNKNOWN_LAYOUT
                val sends = entries.filter { it.isSend }
                val graded = sends.ifEmpty { entries }
                typedClimb(
                    board = board,
                    layoutId = layoutId,
                    frames = entries.firstOrNull { it.climbFrames.isNotBlank() }?.climbFrames.orEmpty(),
                    sent = sends.isNotEmpty(),
                    grade = graded.mapNotNull { it.difficultyAverage }.maxOrNull(),
                )
            }

    /**
     * A climb's hand holds: start, middle and finish holds, never foot holds. On a mirrored layout a mirrored
     * ascent uses the mirror-image holds, which have the same type, so the frames as logged count. Frames in the
     * `h<hole>p<role>` form name mounting holes, not placements, so their holds stay unknown.
     */
    fun typedClimb(board: String, layoutId: Int, frames: String, sent: Boolean = true, grade: Double? = null) = TypedClimb(
        board = board,
        layoutId = layoutId,
        handHolds = if (BoardClimbParser.isClimbConcat(frames)) emptyList() else BoardClimbParser.parseFrames(frames)
            .filter { HoldRole.roleClass(it.roleId) != HoldRole.FOOT }
            .map { it.placementId }
            .distinct(),
        sent = sent,
        grade = grade,
    )

    /**
     * Per known layout of [problems], the grades to compare against: from your easiest to your hardest problem
     * there, [GRADE_MARGIN] wider on each side. Every grade when none of your problems there has one.
     */
    fun gradeRanges(problems: List<TypedClimb>): Map<Int, ClosedFloatingPointRange<Double>> =
        problems.filter { it.layoutId != UNKNOWN_LAYOUT }
            .groupBy { it.layoutId }
            .mapValues { (_, onLayout) ->
                val grades = onLayout.mapNotNull { it.grade }
                if (grades.isEmpty()) 0.0..Double.MAX_VALUE
                else (grades.min() - GRADE_MARGIN).coerceAtLeast(0.0)..(grades.max() + GRADE_MARGIN)
            }

    /**
     * The card for [board]: your mix and the board's per type, and how you do where each type dominates.
     * [boardMixes] holds the mix of the board's problems per layout (see [gradeRanges]); layouts are weighted by
     * how many of your problems are on them. Null when none of your problems has a counted hold.
     */
    fun compute(
        board: String,
        dataset: HoldTypeDataset,
        yours: List<TypedClimb>,
        boardMixes: Map<Int, HoldMix>,
    ): HoldTypeCardStats? {
        val datasets = mapOf(board to dataset)
        val mix = HoldTypeStats.holdMix(yours, datasets)
        if (mix.problems == 0) return null
        val weights = yours.groupBy { it.layoutId }
            .mapValues { (_, onLayout) -> HoldTypeStats.holdMix(onLayout, datasets).problems }
            .filter { (layoutId, problems) -> problems > 0 && (boardMixes[layoutId]?.problems ?: 0) > 0 }
        val weightSum = weights.values.sum()
        val boardShares = if (weightSum == 0) null else HoldTypeStats.HAND_TYPES.associateWith { type ->
            weights.entries.sumOf { (layoutId, w) -> boardMixes.getValue(layoutId).share(type) * w } / weightSum
        }
        val strengths = HoldTypeStats.typeStrengths(yours, dataset)
        val levels = strengths.types.associateBy { it.type }
        return HoldTypeCardStats(
            board = board,
            rows = HoldTypeStats.HAND_TYPES.map { type ->
                val level = levels.getValue(type)
                HoldTypeRow(type, mix.share(type), boardShares?.get(type), level.level, level.strength)
            },
            problems = mix.problems,
            countedHolds = mix.countedHolds,
            handHolds = mix.handHolds,
            boardProblems = weights.keys.sumOf { boardMixes.getValue(it).problems },
            overall = strengths.overall,
            datasetVersion = dataset.version,
        )
    }
}

/** The hold-type card of the statistics for one board (see [HoldTypeStatsComputer]). */
data class HoldTypeCardStats(
    /** The board's wire name. */
    val board: String,
    /** One row per hand type, in [HoldTypeStats.HAND_TYPES] order. */
    val rows: List<HoldTypeRow>,
    /** Your problems with at least one counted hold. */
    val problems: Int,
    /** Hand holds of those problems with a known, confident type. */
    val countedHolds: Int,
    /** All hand holds of your problems, counted or not. */
    val handHolds: Int,
    /** The board's problems the comparison rests on; 0 without a comparison. */
    val boardProblems: Int,
    /** Your level on all your problems of the board in the period. */
    val overall: HoldTypeStats.Level,
    val datasetVersion: String,
)

data class HoldTypeRow(
    val type: HoldType,
    /** Your share of this type among the counted hand holds, 0..1, each problem weighted equally. */
    val yours: Double,
    /** The same share on the board's problems in your grades; null without board problems to compare with. */
    val board: Double?,
    /** How you do on the problems where this type dominates. */
    val level: HoldTypeStats.Level,
    val strength: HoldTypeStats.Strength,
)
