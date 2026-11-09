package com.cruxcoach.domain.yearreview

/**
 * The climber type of the year in review (#64): a fun, rule-based type, so that
 * everyone with a full review gets one. The maintainer chose the ten types, their
 * names and the strongest match on 2026-11-06. The order is the tie-break.
 */
enum class ClimberType {
    FLASH_MACHINE,
    PROJECTOR,
    MILEAGE_MONSTER,
    STEEP_SPECIALIST,
    BOARD_HOPPER,
    CLOCKWORK,
    RISING_STAR,
    STRENGTH_LAB,
    COMEBACK_KID,

    /** When no other rule fits. */
    ALL_ROUNDER,
}

/**
 * How well one type's rule fits: the user's [value] against the rule's [threshold],
 * and [score], their ratio; a type needs a score of at least 1. [rule] tells which
 * of a type's rules it is, where it has two (0 = the first in [ClimberTypeRules]).
 * [count] is the sample the value comes from, e.g. the first sends a flash share is
 * taken of.
 */
data class TypeMatch(
    val type: ClimberType,
    val score: Double,
    val value: Double,
    val threshold: Double,
    val rule: Int = 0,
    val count: Int = 0,
)

data class ClimberTypeResult(
    val type: ClimberType,
    /** The winning rule with the user's number. For [ClimberType.ALL_ROUNDER] the closest type below its threshold, or null. */
    val match: TypeMatch?,
    /** The next type that fits too, or null. */
    val also: TypeMatch?,
    /** Every type's best rule, strongest first. */
    val scores: List<TypeMatch>,
    /** What the rules read. */
    val facts: ClimberTypeFacts,
)

/**
 * The thresholds of the types. The defaults were calibrated on synthetic logbooks
 * (ClimberTypeCalibrationTest) so that no type takes more than about a quarter of
 * climbers; tests may try others.
 */
data class ClimberTypeRules(
    /** Flash Machine: this share of the first sends were flashes, out of at least [flashMinFirstSends]. */
    val flashShare: Double = 0.70,
    val flashMinFirstSends: Int = 20,
    /**
     * Projector: [projectShare] of the first sends took [projectTries] tries or more, or they took
     * [projectAverageTries] on average; out of at least [projectMinFirstSends].
     */
    val projectTries: Int = 10,
    val projectShare: Double = 0.08,
    val projectAverageTries: Double = 3.5,
    val projectMinFirstSends: Int = 15,
    /** Mileage Monster: sends per board day, over at least [mileageMinDays] board days. */
    val mileageSendsPerDay: Double = 14.0,
    val mileageMinDays: Int = 20,
    /** Steep Specialist: this share of the entries at [steepAngle] or steeper ([steepAngleMoonBoard] on a MoonBoard). */
    val steepShare: Double = 0.75,
    val steepAngle: Int = 45,
    val steepAngleMoonBoard: Int = 40,
    val steepMinEntries: Int = 50,
    /** Board Hopper: [hopperBoards] boards with at least [hopperShare] of the entries each; the score is the smallest of their shares. */
    val hopperBoards: Int = 3,
    val hopperShare: Double = 0.10,
    /** Clockwork: a climbing day in this share of the weeks since the first one of the year, or a run of [clockworkStreak] weeks. */
    val clockworkWeekShare: Double = 0.85,
    val clockworkMinWeeks: Int = 12,
    val clockworkStreak: Int = 16,
    /** Rising Star: grades gained on the main board over last year, or from the first to the last three months. */
    val risingSteps: Int = 2,
    /** Strength Lab: this many workouts, on at least [strengthShare] of the training days. */
    val strengthWorkouts: Int = 20,
    val strengthShare: Double = 0.45,
    /**
     * Comeback Kid: a finished comeback plan, or a break of [comebackBreakDays] days or more followed
     * by [comebackDaysAfter] climbing days. Both count as [comebackScore]: a longer break is no
     * stronger match, or a long holiday would outweigh every other type.
     */
    val comebackBreakDays: Int = 56,
    val comebackDaysAfter: Int = 8,
    val comebackScore: Double = 1.2,
)

/** What the type rules read, taken from the year's review and logbook. */
data class ClimberTypeFacts(
    val firstSends: Int = 0,
    val flashes: Int = 0,
    /** The tries of each first send this year. */
    val firstSendTries: List<Int> = emptyList(),
    val sends: Int = 0,
    val boardDays: Int = 0,
    /** Board entries, and those at the steep angle of their board. */
    val entries: Int = 0,
    val steepEntries: Int = 0,
    /** Entries per board. */
    val boardEntries: List<Int> = emptyList(),
    /** Weeks with a climbing day, and the weeks since the one with the first climbing day of the year. */
    val activeWeeks: Int = 0,
    val weeksSinceFirst: Int = 0,
    val longestWeekStreak: Int = 0,
    /** Grades on the main board over last year, and from the first to the last three months; null where not known. */
    val stepsOverLastYear: Int? = null,
    val stepsWithinYear: Int? = null,
    val workouts: Int = 0,
    val workoutDays: Int = 0,
    /** Days with a workout or a climbing day. */
    val trainingDays: Int = 0,
    val comebacksCompleted: Int = 0,
    /** The longest break that ended this year, in days, and the climbing days after it. */
    val breakDays: Int = 0,
    val daysAfterBreak: Int = 0,
)

object ClimberTypes {

    /** The strongest match, the table order breaking ties; All-Rounder when nothing reaches its threshold. */
    fun pick(facts: ClimberTypeFacts, rules: ClimberTypeRules = ClimberTypeRules()): ClimberTypeResult {
        val scores = scores(facts, rules)
            .sortedWith(compareByDescending<TypeMatch> { it.score }.thenBy { it.type.ordinal })
        val fitting = scores.filter { it.score >= 1.0 }
        val best = fitting.firstOrNull()
        return ClimberTypeResult(
            type = best?.type ?: ClimberType.ALL_ROUNDER,
            match = best ?: scores.firstOrNull { it.score > 0.0 },
            also = fitting.getOrNull(1),
            scores = scores,
            facts = facts,
        )
    }

    /** Each type's best rule; a type whose rule cannot be applied scores 0. */
    fun scores(f: ClimberTypeFacts, r: ClimberTypeRules = ClimberTypeRules()): List<TypeMatch> = listOf(
        flash(f, r),
        projector(f, r),
        match(ClimberType.MILEAGE_MONSTER, ratio(f.sends, f.boardDays), r.mileageSendsPerDay, count = f.boardDays)
            .unless(f.boardDays < r.mileageMinDays),
        match(ClimberType.STEEP_SPECIALIST, ratio(f.steepEntries, f.entries), r.steepShare, count = f.entries)
            .unless(f.entries < r.steepMinEntries),
        match(ClimberType.BOARD_HOPPER, nthBoardShare(f, r.hopperBoards), r.hopperShare, count = f.boardEntries.size),
        clockwork(f, r),
        rising(f, r),
        strength(f, r),
        comeback(f, r),
    )

    private fun flash(f: ClimberTypeFacts, r: ClimberTypeRules) =
        match(ClimberType.FLASH_MACHINE, ratio(f.flashes, f.firstSends), r.flashShare, count = f.firstSends)
            .unless(f.firstSends < r.flashMinFirstSends)

    private fun projector(f: ClimberTypeFacts, r: ClimberTypeRules): TypeMatch {
        val hardWon = ratio(f.firstSendTries.count { it >= r.projectTries }, f.firstSends)
        val byShare = match(ClimberType.PROJECTOR, hardWon, r.projectShare, rule = 0, count = f.firstSends)
        val byAverage = match(ClimberType.PROJECTOR, ratio(f.firstSendTries.sum(), f.firstSends), r.projectAverageTries, rule = 1, count = f.firstSends)
        return strongest(byShare, byAverage).unless(f.firstSends < r.projectMinFirstSends)
    }

    private fun clockwork(f: ClimberTypeFacts, r: ClimberTypeRules): TypeMatch {
        val share = match(ClimberType.CLOCKWORK, ratio(f.activeWeeks, f.weeksSinceFirst), r.clockworkWeekShare, rule = 0, count = f.weeksSinceFirst)
            .unless(f.weeksSinceFirst < r.clockworkMinWeeks)
        val streak = match(ClimberType.CLOCKWORK, f.longestWeekStreak.toDouble(), r.clockworkStreak.toDouble(), rule = 1)
        return strongest(share, streak)
    }

    private fun rising(f: ClimberTypeFacts, r: ClimberTypeRules): TypeMatch {
        val steps = f.stepsOverLastYear ?: f.stepsWithinYear
        return match(ClimberType.RISING_STAR, (steps ?: 0).toDouble(), r.risingSteps.toDouble(), rule = if (f.stepsOverLastYear != null) 0 else 1)
    }

    private fun strength(f: ClimberTypeFacts, r: ClimberTypeRules): TypeMatch {
        // Both parts must hold, so the weaker one decides.
        val share = ratio(f.workoutDays, f.trainingDays)
        val score = minOf(f.workouts / r.strengthWorkouts.toDouble(), share / r.strengthShare)
        return TypeMatch(ClimberType.STRENGTH_LAB, score, share, r.strengthShare, count = f.workouts)
    }

    private fun comeback(f: ClimberTypeFacts, r: ClimberTypeRules): TypeMatch {
        val plan = TypeMatch(ClimberType.COMEBACK_KID, if (f.comebacksCompleted > 0) r.comebackScore else 0.0, f.comebacksCompleted.toDouble(), 1.0, rule = 0)
        val back = f.breakDays >= r.comebackBreakDays && f.daysAfterBreak >= r.comebackDaysAfter
        val afterBreak = TypeMatch(
            ClimberType.COMEBACK_KID, if (back) r.comebackScore else 0.0, f.breakDays.toDouble(), r.comebackBreakDays.toDouble(),
            rule = 1, count = f.daysAfterBreak,
        )
        return strongest(plan, afterBreak)
    }

    /** The share of the entries on the [n]th busiest board, 0 with fewer boards. */
    private fun nthBoardShare(f: ClimberTypeFacts, n: Int): Double {
        val total = f.boardEntries.sum()
        val nth = f.boardEntries.sortedDescending().getOrNull(n - 1) ?: return 0.0
        return ratio(nth, total)
    }

    private fun match(type: ClimberType, value: Double, threshold: Double, rule: Int = 0, count: Int = 0) =
        TypeMatch(type, if (threshold > 0) value / threshold else 0.0, value, threshold, rule, count)

    private fun TypeMatch.unless(notApplicable: Boolean) = if (notApplicable) copy(score = 0.0) else this

    private fun strongest(a: TypeMatch, b: TypeMatch) = if (b.score > a.score) b else a

    private fun ratio(part: Int, whole: Int): Double = if (whole > 0) part.toDouble() / whole else 0.0
}
