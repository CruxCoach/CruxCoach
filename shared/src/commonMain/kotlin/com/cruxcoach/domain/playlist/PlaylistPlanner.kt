package com.cruxcoach.domain.playlist

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Which structural section of the session a slot belongs to. */
enum class PlanSection { WARM_UP, MAIN, PEAK, DESCENT }

/** Abstract slot plan — grades first, concrete climbs later (the
 *  BoardSesh-proven two-phase split: plan the curve, then fill it). */
sealed interface PlanSlot {
    val section: PlanSection

    /**
     * One climb at a target difficulty band.
     *
     * @param repeatKey slots sharing a non-null key are filled with the
     *  SAME climb (power-endurance laps repeat their problems each set).
     */
    data class ClimbSlot(
        val minDifficulty: Double,
        val maxDifficulty: Double,
        override val section: PlanSection,
        val repeatKey: Int? = null,
    ) : PlanSlot

    /** An explicit rest block — lands in the playlist as a rest entry. */
    data class RestSlot(
        val seconds: Int,
        override val section: PlanSection,
    ) : PlanSlot
}

/**
 * The planned session skeleton.
 *
 * @param downgradedFromType non-null when END_OF_SESSION forced a
 *  max-effort type down to VOLUME — the UI should say so.
 * @param usedDefaultProfile true when the logbook was too thin and the
 *  ~V5 default drove the grades — the UI must flag this.
 */
data class PlaylistPlan(
    val slots: List<PlanSlot>,
    val effectiveType: GeneratorType,
    val downgradedFromType: GeneratorType? = null,
    val usedDefaultProfile: Boolean = false,
    /**
     * Nothing in this session may be harder than this, whatever the filler has
     * to do to find climbs.
     *
     * The planner clamps every band to max + 1 V, but the filler widens a slot
     * that finds no candidates — symmetrically, and by up to four points. That
     * let the safety ceiling be walked straight past after the fact: a limit
     * slot at V10–V11 could be served a V15. The ceiling travels with the plan
     * so the widening has something to stop at.
     */
    val hardCeiling: Double = TrainingRanges.MAX_DIFFICULTY,
)

/**
 * Pure planner: (params, profile) → ordered slot plan with explicit rest
 * blocks. No I/O, no randomness — fully deterministic and unit-testable.
 * Grade math happens in Aurora difficulty points (+2 ≈ 1 V-grade); all
 * bands are clamped to [V0, max + 1 V].
 */
object PlaylistPlanner {

    fun plan(params: PlaylistGeneratorParams, profile: LogbookProfile): PlaylistPlan {
        val duration = params.durationMinutes.coerceIn(
            TrainingRanges.MIN_DURATION_MINUTES,
            TrainingRanges.MAX_DURATION_MINUTES,
        )

        // END_OF_SESSION forbids max-effort work (fatigued limit attempts
        // are an injury risk): LIMIT/PROJECTING downgrade to VOLUME and
        // every band shifts one V-grade easier.
        val fatigued = params.position == SessionPosition.END_OF_SESSION
        val downgraded = fatigued &&
            (params.type == GeneratorType.LIMIT || params.type == GeneratorType.PROJECTING)
        val effectiveType = if (downgraded) GeneratorType.VOLUME else params.type
        val shift = if (fatigued) TrainingRanges.END_OF_SESSION_SHIFT else 0.0

        // Two anchors: the PEAK (hardest-ever send — hard ceiling only)
        // and the outlier-robust work ANCHOR (repeatable/second-best max).
        // One lucky 7b against a 7a/7a+ background must plan a session
        // that CONSOLIDATES the 7b, not one that assumes 7b+ is in reach.
        val peak = profile.effectiveMax - shift
        val anchor = min(profile.effectiveRepeatableMax, profile.effectiveMax) - shift
        // Volume anchor: outlier-robust flash, never above the work anchor
        // (a flash "above" what you can repeatedly send is itself a fluke).
        val flashDiff = min(profile.effectiveRepeatableFlash - shift, anchor)

        // Warm-up ladder only when starting cold; its ACTUAL minutes come
        // off the main-set budget (a 2-problem easy-session warm-up must
        // not eat a flat 18-minute block).
        // Circular by nature: the pyramid's base tier depends on the main-set
        // budget, the budget depends on how long the ladder is, and the ladder
        // length depends on the base. Settled in two passes — the first buys a
        // ladder cost, the second the grade that cost actually implies.
        // The size the climber chose, clamped to what the type can carry.
        // Null means a playlist saved before the slider selected structure
        // directly, so the old division from the duration still applies.
        val size = params.structureSize?.coerceIn(effectiveType.structureRange())

        fun ladderFor(mainMinutes: Int) =
            if (params.position == SessionPosition.START_COLD) {
                buildWarmUpLadder(
                    anchor,
                    firstWorkGrade(effectiveType, anchor, flashDiff, mainMinutes, size),
                )
            } else emptyList()

        // With an explicit size the pyramid's tiers no longer depend on the
        // budget, so the second pass has nothing left to discover.
        val warmUp = if (size != null) {
            ladderFor(duration)
        } else {
            ladderFor(max(duration - warmUpMinutes(ladderFor(duration)), 10))
        }
        val mainMinutes = max(duration - warmUpMinutes(warmUp), 10)

        val main = when (effectiveType) {
            GeneratorType.VOLUME -> planVolume(mainMinutes, flashDiff, peak, size)
            GeneratorType.LIMIT -> planLimit(mainMinutes, anchor, peak, size)
            GeneratorType.PROJECTING -> planProjecting(mainMinutes, anchor, peak, size)
            GeneratorType.POWER_ENDURANCE -> planPowerEndurance(mainMinutes, flashDiff, size)
            GeneratorType.PYRAMID -> planPyramid(mainMinutes, anchor, params.pyramidShape, size)
        }

        return PlaylistPlan(
            slots = (warmUp + main).normalizeRests(),
            effectiveType = effectiveType,
            downgradedFromType = if (downgraded) params.type else null,
            usedDefaultProfile = !profile.isPersonalized,
            hardCeiling = min(
                TrainingRanges.MAX_DIFFICULTY,
                profile.effectiveMax + TrainingRanges.CEILING_ABOVE_MAX_STEPS,
            ),
        )
    }

    // ── Sections ────────────────────────────────────────────────

    /**
     * Progressive ladder up to ONE V-grade below the first working grade
     * (Hörst: "boulder up" to near working intensity — the old ladder
     * stopped 3 V short of a limit set, an intensity jump). Start is
     * 5 V below max, pulled DOWN to firstWork − 3 V for easy sessions
     * (a volume block should not warm up at volume grade). Easy tiers
     * carry 2 problems; tiers within taper distance of the working grade
     * carry 1 (activation, not fatigue). Short rests between problems,
     * then the long transition rest.
     */
    private fun buildWarmUpLadder(maxDiff: Double, firstWorkDiff: Double): List<PlanSlot> {
        val climbs = mutableListOf<PlanSlot.ClimbSlot>()
        var tier = clampLow(firstWorkDiff - TrainingRanges.WARMUP_START_BELOW_FIRST_WORK)
        val ceiling = firstWorkDiff - TrainingRanges.WARMUP_END_BELOW_FIRST_WORK
        while (tier <= ceiling && climbs.size < TrainingRanges.WARMUP_MAX_PROBLEMS) {
            val nearWork = firstWorkDiff - tier < TrainingRanges.WARMUP_TAPER_DISTANCE
            val perTier = if (nearWork) 1 else TrainingRanges.WARMUP_PROBLEMS_PER_TIER
            repeat(perTier) {
                if (climbs.size < TrainingRanges.WARMUP_MAX_PROBLEMS) {
                    climbs.add(climbSlot(tier, PlanSection.WARM_UP))
                }
            }
            tier += TrainingRanges.WARMUP_STEP
        }
        if (climbs.isEmpty()) {
            // Even a V0 climber warms up on something: one tier at the floor.
            repeat(TrainingRanges.WARMUP_PROBLEMS_PER_TIER) {
                climbs.add(climbSlot(TrainingRanges.MIN_DIFFICULTY, PlanSection.WARM_UP))
            }
        }
        val slots = mutableListOf<PlanSlot>()
        climbs.forEachIndexed { i, climb ->
            if (i > 0) {
                slots.add(
                    PlanSlot.RestSlot(TrainingRanges.REST_WARMUP_BETWEEN_PROBLEMS, PlanSection.WARM_UP)
                )
            }
            slots.add(climb)
        }
        slots.add(PlanSlot.RestSlot(TrainingRanges.REST_AFTER_WARMUP, PlanSection.WARM_UP))
        return slots
    }

    /** Actual wall-clock cost of the warm-up (1.5 min per problem, the
     *  same figure estimatedMinutes uses, plus its explicit rests). */
    private fun warmUpMinutes(warmUp: List<PlanSlot>): Int {
        if (warmUp.isEmpty()) return 0
        val restSeconds = warmUp.filterIsInstance<PlanSlot.RestSlot>().sumOf { it.seconds }
        val climbCount = warmUp.count { it is PlanSlot.ClimbSlot }
        return ceil(climbCount * 1.5 + restSeconds / 60.0).toInt()
    }

    private fun planVolume(
        minutes: Int,
        flashDiff: Double,
        maxDiff: Double,
        size: Int?,
    ): List<PlanSlot> {
        // Pay for the mid-block break before dividing the rest into problems.
        val breakCost =
            if (minutes >= 60) TrainingRanges.VOLUME_MID_BREAK_EXTRA_SECONDS else 0
        val count = size
            ?: ((minutes * 60 - breakCost) / TrainingRanges.VOLUME_CYCLE_SECONDS)
                .coerceIn(TrainingRanges.VOLUME_COUNT)
        val high = clamp(flashDiff, maxDiff)
        val low = clampLow(high - TrainingRanges.VOLUME_BAND_BELOW_FLASH)
        val mid = (low + high) / 2
        val slots = mutableListOf<PlanSlot>()
        for (i in 0 until count) {
            if (i > 0) {
                // Quality rest between every problem — volume without rests
                // degrades into accidental power-endurance and sloppy movement.
                // The mid-block break replaces (not stacks on) the short rest.
                val midBreak = minutes >= 60 && i == count / 2
                slots.add(
                    PlanSlot.RestSlot(
                        if (midBreak) TrainingRanges.REST_VOLUME_MID_BREAK
                        else TrainingRanges.REST_VOLUME_BETWEEN_PROBLEMS,
                        PlanSection.MAIN,
                    )
                )
            }
            // Progressive loading: the session ramps through the band —
            // first third eases in (lower half), last third finishes at
            // flash level (upper half), middle roams the full band.
            val (slotLow, slotHigh) = when {
                i < count / 3 -> low to mid
                i >= count - count / 3 -> mid to high
                else -> low to high
            }
            slots.add(PlanSlot.ClimbSlot(slotLow, slotHigh, PlanSection.MAIN))
        }
        return slots
    }

    /** Limit bouldering with EXPLICIT attempt structure: each problem
     *  appears [TrainingRanges.ATTEMPTS_PER_LIMIT_PROBLEM] times (same
     *  climb via repeatKey) with between-attempt rests, long rests between
     *  problems — the rests ARE the training, so they live in the list. */
    private fun planLimit(
        minutes: Int,
        anchor: Double,
        peak: Double,
        size: Int?,
    ): List<PlanSlot> {
        // A 21-minute block per problem with a floor of two problems meant the
        // shortest session the slider offers produced three times the time
        // asked for. One hard problem with full rests IS a session; below that
        // there is nothing honest to plan, so the attempt count gives way
        // first — Hörst's 3-5, not a fixed 5.
        val budget = minutes.coerceAtLeast(TrainingRanges.LIMIT_SLOT_MINUTES / 2)
        val count = size
            ?: (budget / TrainingRanges.LIMIT_SLOT_MINUTES).coerceIn(TrainingRanges.LIMIT_COUNT)
        var attempts = TrainingRanges.ATTEMPTS_PER_LIMIT_PROBLEM
        while (count == TrainingRanges.LIMIT_COUNT.first &&
            attempts > TrainingRanges.MIN_ATTEMPTS_PER_LIMIT_PROBLEM &&
            limitBlockMinutes(count, attempts) > budget
        ) {
            attempts--
        }
        // anchor…anchor+1, additionally capped one step past the PEAK:
        // a consolidated climber (anchor == peak) trains max…max+1; an
        // outlier peak keeps the band at the repeatable level so the
        // session consolidates the peak instead of assuming it's the norm.
        val low = anchor
        val high = clamp(
            min(anchor + TrainingRanges.LIMIT_BAND_ABOVE_MAX, peak + TrainingRanges.LIMIT_BAND_ABOVE_MAX),
            peak,
        )
        return workBlocks(
            problems = count,
            attemptsPerProblem = attempts,
            attemptRest = TrainingRanges.REST_LIMIT_BETWEEN_ATTEMPTS,
            problemRest = TrainingRanges.REST_LIMIT_BETWEEN_PROBLEMS,
            low = low, high = high,
        )
    }

    /** Projecting: burns on each project, explicit like limit attempts.
     *  Band sits ABOVE the limit band (max+1…max+2 Font steps) — projects
     *  are multi-session difficulty, limit problems are session-sendable. */
    private fun planProjecting(
        minutes: Int,
        anchor: Double,
        peak: Double,
        size: Int?,
    ): List<PlanSlot> {
        val count = size
            ?: (minutes / TrainingRanges.PROJECT_SLOT_MINUTES)
                .coerceIn(TrainingRanges.PROJECT_COUNT)
        // One step above the limit band, off the same robust anchor — for
        // the outlier climber (one 7b, background 7a+) the project IS the
        // 7b…7b+ range, not 7c. The peak-based clamp() ceiling still holds.
        val low = clamp(anchor + TrainingRanges.PROJECT_BAND_LOW_ABOVE_MAX, peak)
        val high = clamp(anchor + TrainingRanges.PROJECT_BAND_TOP_ABOVE_MAX, peak)
        return workBlocks(
            problems = count,
            attemptsPerProblem = TrainingRanges.BURNS_PER_PROJECT,
            attemptRest = TrainingRanges.REST_PROJECT_BETWEEN_BURNS,
            problemRest = TrainingRanges.REST_PROJECT_BETWEEN_PROJECTS,
            low = low, high = high,
        )
    }

    /** Rough wall-clock for a limit block, in minutes — rests dominate. */
    private fun limitBlockMinutes(problems: Int, attempts: Int): Int {
        val perProblem = attempts * TrainingRanges.CLIMB_SECONDS +
            (attempts - 1) * TrainingRanges.REST_LIMIT_BETWEEN_ATTEMPTS
        val between = (problems - 1) * TrainingRanges.REST_LIMIT_BETWEEN_PROBLEMS
        return (problems * perProblem + between) / 60
    }

    private fun workBlocks(
        problems: Int,
        attemptsPerProblem: Int,
        attemptRest: Int,
        problemRest: Int,
        low: Double,
        high: Double,
    ): List<PlanSlot> {
        val slots = mutableListOf<PlanSlot>()
        for (p in 0 until problems) {
            if (p > 0) slots.add(PlanSlot.RestSlot(problemRest, PlanSection.PEAK))
            for (attempt in 0 until attemptsPerProblem) {
                if (attempt > 0) slots.add(PlanSlot.RestSlot(attemptRest, PlanSection.PEAK))
                slots.add(PlanSlot.ClimbSlot(low, high, PlanSection.PEAK, repeatKey = p))
            }
        }
        return slots
    }

    /** Sets of 4 problems (same problems every set, via repeatKey), short
     *  lap rests inside a set, long rests between sets. */
    private fun planPowerEndurance(
        minutes: Int,
        flashAnchor: Double,
        size: Int?,
    ): List<PlanSlot> {
        val sets = size
            ?: (minutes / TrainingRanges.PE_SET_MINUTES).coerceIn(TrainingRanges.PE_SETS)
        val low = clampLow(flashAnchor - TrainingRanges.PE_BAND_LOW_BELOW_FLASH)
        val high = clampLow(flashAnchor - TrainingRanges.PE_BAND_HIGH_BELOW_FLASH)
        val slots = mutableListOf<PlanSlot>()
        for (set in 0 until sets) {
            for (problem in 0 until TrainingRanges.PE_PROBLEMS_PER_SET) {
                if (problem > 0) {
                    slots.add(PlanSlot.RestSlot(TrainingRanges.REST_PE_BETWEEN_LAPS, PlanSection.MAIN))
                }
                slots.add(PlanSlot.ClimbSlot(low, high, PlanSection.MAIN, repeatKey = problem))
            }
            if (set < sets - 1) {
                slots.add(PlanSlot.RestSlot(TrainingRanges.REST_PE_BETWEEN_SETS, PlanSection.MAIN))
            }
        }
        return slots
    }

    /** Classic ascending pyramid (…4×, 3×, 2×, 1× apex), 1-V steps; long
     *  sessions add the mirrored descent. */
    private fun planPyramid(
        minutes: Int,
        anchor: Double,
        shape: PyramidShape,
        size: Int? = null,
    ): List<PlanSlot> {
        // Apex 1 V below the REPEATABLE max: every tier should top.
        val apex = clampLow(anchor - TrainingRanges.PYRAMID_APEX_BELOW_MAX)
        val tiers = size ?: pyramidTiers(minutes)
        val base = pyramidBaseFor(tiers, anchor)
        // The climber's choice, not a side effect of the duration. Anything
        // under 90 minutes used to be a half pyramid called a whole one.
        val withDescent = shape == PyramidShape.UP_AND_DOWN

        data class Tier(val diff: Double, val count: Int, val section: PlanSection)

        val ascent = (0 until tiers).map { i ->
            Tier(
                diff = min(base + i * TrainingRanges.PYRAMID_STEP, apex),
                count = tiers - i,
                section = if (i == tiers - 1) PlanSection.PEAK else PlanSection.MAIN,
            )
        }
        val descent = if (withDescent) {
            // asReversed(), not reversed(): under a JDK-21 toolchain the
            // latter can resolve to java.util.List's SequencedCollection
            // member — NoSuchMethodError on Android API 26-34.
            ascent.dropLast(1).asReversed().map { it.copy(section = PlanSection.DESCENT) }
        } else emptyList()

        val slots = mutableListOf<PlanSlot>()
        (ascent + descent).forEach { tier ->
            // Rests scale with proximity to the apex: the base flows, the
            // mid tier breathes, the top Font step gets near-full recovery.
            val stepsBelowApex = (apex - tier.diff) / TrainingRanges.PYRAMID_STEP
            val rest = when {
                stepsBelowApex <= 1.0 -> TrainingRanges.REST_PYRAMID_HIGH_TIER
                stepsBelowApex <= 2.0 -> TrainingRanges.REST_PYRAMID_MID_TIER
                else -> TrainingRanges.REST_PYRAMID_LOW_TIER
            }
            repeat(tier.count) {
                if (slots.isNotEmpty()) slots.add(PlanSlot.RestSlot(rest, tier.section))
                slots.add(climbSlot(tier.diff, tier.section))
            }
        }
        return slots
    }

    // ── Helpers ─────────────────────────────────────────────────

    /** Short sessions climb three tiers, longer ones four. */
    private fun pyramidTiers(minutes: Int): Int = if (minutes < 45) 3 else 4

    /** The lowest tier of the pyramid — where the first working problem sits. */
    private fun pyramidBaseFor(tiers: Int, anchor: Double): Double {
        val apex = clampLow(anchor - TrainingRanges.PYRAMID_APEX_BELOW_MAX)
        return clampLow(apex - (tiers - 1) * TrainingRanges.PYRAMID_STEP)
    }

    /**
     * The grade the warm-up ladder has to reach up to.
     *
     * Every branch derives this from the same constants its plan* function
     * uses — except the pyramid, which used to rebuild the shape from a
     * constant `planPyramid` does not consult. That copy assumed four tiers,
     * so any pyramid short enough to build three ended the ladder 1.5 V below
     * the first working problem instead of 1 V: exactly the intensity jump
     * the ladder exists to remove.
     */
    private fun firstWorkGrade(
        type: GeneratorType,
        anchor: Double,
        flashDiff: Double,
        mainMinutes: Int,
        size: Int?,
    ): Double =
        when (type) {
            GeneratorType.VOLUME -> clampLow(flashDiff - TrainingRanges.VOLUME_BAND_BELOW_FLASH)
            GeneratorType.POWER_ENDURANCE ->
                clampLow(flashDiff - TrainingRanges.PE_BAND_LOW_BELOW_FLASH)
            GeneratorType.PYRAMID -> pyramidBaseFor(
                size ?: pyramidTiers(mainMinutes), anchor,
            )
            GeneratorType.LIMIT, GeneratorType.PROJECTING -> anchor
        }

    private fun climbSlot(center: Double, section: PlanSection, repeatKey: Int? = null) =
        PlanSlot.ClimbSlot(
            minDifficulty = clampLow(center - TrainingRanges.SLOT_TOLERANCE),
            maxDifficulty = center + TrainingRanges.SLOT_TOLERANCE,
            section = section,
            repeatKey = repeatKey,
        )

    private fun clampLow(diff: Double): Double = max(diff, TrainingRanges.MIN_DIFFICULTY)

    /** Clamp into [V0, min(scale max, user max + 1 V)] — the hard safety
     *  ceiling that no mode may plan past. */
    private fun clamp(diff: Double, userMax: Double): Double =
        min(
            min(diff, userMax + TrainingRanges.CEILING_ABOVE_MAX_STEPS),
            TrainingRanges.MAX_DIFFICULTY,
        ).let(::clampLow)

    /** Strip leading/trailing rests and collapse accidental doubles. */
    private fun List<PlanSlot>.normalizeRests(): List<PlanSlot> {
        val trimmed = dropWhile { it is PlanSlot.RestSlot }
            .dropLastWhile { it is PlanSlot.RestSlot }
        val out = mutableListOf<PlanSlot>()
        trimmed.forEach { slot ->
            if (slot is PlanSlot.RestSlot && out.lastOrNull() is PlanSlot.RestSlot) {
                val prev = out.removeAt(out.size - 1) as PlanSlot.RestSlot
                out.add(if (slot.seconds > prev.seconds) slot else prev)
            } else {
                out.add(slot)
            }
        }
        return out
    }
}

/**
 * Wall-clock estimate for a plan (UI preview): explicit rests + type-aware
 * time-on-the-wall per climb. Every mode now carries its rests as explicit
 * entries, so the per-climb figures are pure climb + reset time: volume/PE
 * problems are short flowing climbs, limit attempts and pyramid tries add
 * brushing/reading, project burns are the longest time-on-wall.
 */
fun PlaylistPlan.estimatedMinutes(): Int {
    val restSeconds = slots.filterIsInstance<PlanSlot.RestSlot>().sumOf { it.seconds }
    val climbMinutes = slots.filterIsInstance<PlanSlot.ClimbSlot>().sumOf { slot ->
        if (slot.section == PlanSection.WARM_UP) 1.5
        else when (effectiveType) {
            GeneratorType.VOLUME -> 1.0
            GeneratorType.POWER_ENDURANCE -> 1.0
            GeneratorType.LIMIT -> 1.0
            GeneratorType.PROJECTING -> 1.5
            GeneratorType.PYRAMID -> 1.5
        }
    }
    return ceil(climbMinutes + restSeconds / 60.0).toInt()
}
