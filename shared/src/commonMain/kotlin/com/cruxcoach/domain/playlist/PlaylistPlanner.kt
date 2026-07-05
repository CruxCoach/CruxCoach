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

        val maxDiff = profile.effectiveMax - shift
        val flashDiff = min(profile.effectiveFlash, maxDiff) - shift

        // Warm-up ladder only when starting cold; its minutes come off the
        // main-set budget.
        val warmUp = if (params.position == SessionPosition.START_COLD) {
            buildWarmUpLadder(maxDiff, firstWorkGrade(effectiveType, maxDiff, flashDiff))
        } else emptyList()
        val mainMinutes = if (warmUp.isEmpty()) duration else max(duration - TrainingRanges.WARMUP_MINUTES, 10)

        val main = when (effectiveType) {
            GeneratorType.VOLUME -> planVolume(mainMinutes, flashDiff, maxDiff)
            GeneratorType.LIMIT -> planLimit(mainMinutes, maxDiff)
            GeneratorType.PROJECTING -> planProjecting(mainMinutes, maxDiff)
            GeneratorType.POWER_ENDURANCE -> planPowerEndurance(mainMinutes, maxDiff)
            GeneratorType.PYRAMID -> planPyramid(mainMinutes, maxDiff)
        }

        return PlaylistPlan(
            slots = (warmUp + main).normalizeRests(),
            effectiveType = effectiveType,
            downgradedFromType = if (downgraded) params.type else null,
            usedDefaultProfile = !profile.isPersonalized,
        )
    }

    // ── Sections ────────────────────────────────────────────────

    /** Ladder from max − 5 V up to just below the first working grade,
     *  1-V steps, 2 problems per tier, capped at 6 problems — short rests
     *  between the ladder problems, then a 3–5 min transition rest. */
    private fun buildWarmUpLadder(maxDiff: Double, firstWorkDiff: Double): List<PlanSlot> {
        val climbs = mutableListOf<PlanSlot.ClimbSlot>()
        var tier = clampLow(maxDiff - TrainingRanges.WARMUP_START_BELOW_MAX)
        val ceiling = firstWorkDiff - TrainingRanges.DIFF_PER_V_GRADE
        while (tier <= ceiling && climbs.size < TrainingRanges.WARMUP_MAX_PROBLEMS) {
            repeat(TrainingRanges.WARMUP_PROBLEMS_PER_TIER) {
                if (climbs.size < TrainingRanges.WARMUP_MAX_PROBLEMS) {
                    climbs.add(climbSlot(tier, PlanSection.WARM_UP))
                }
            }
            tier += TrainingRanges.DIFF_PER_V_GRADE
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

    private fun planVolume(minutes: Int, flashDiff: Double, maxDiff: Double): List<PlanSlot> {
        val count = (minutes * 60 / TrainingRanges.VOLUME_CYCLE_SECONDS)
            .coerceIn(TrainingRanges.VOLUME_COUNT)
        val high = clamp(flashDiff, maxDiff)
        val low = clampLow(high - TrainingRanges.VOLUME_BAND_BELOW_FLASH)
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
            slots.add(PlanSlot.ClimbSlot(low, high, PlanSection.MAIN))
        }
        return slots
    }

    /** Limit bouldering with EXPLICIT attempt structure: each problem
     *  appears [TrainingRanges.ATTEMPTS_PER_LIMIT_PROBLEM] times (same
     *  climb via repeatKey) with between-attempt rests, long rests between
     *  problems — the rests ARE the training, so they live in the list. */
    private fun planLimit(minutes: Int, maxDiff: Double): List<PlanSlot> {
        val count = (minutes / TrainingRanges.LIMIT_SLOT_MINUTES)
            .coerceIn(TrainingRanges.LIMIT_COUNT)
        val low = maxDiff
        val high = clamp(maxDiff + TrainingRanges.LIMIT_BAND_ABOVE_MAX, maxDiff)
        return workBlocks(
            problems = count,
            attemptsPerProblem = TrainingRanges.ATTEMPTS_PER_LIMIT_PROBLEM,
            attemptRest = TrainingRanges.REST_LIMIT_BETWEEN_ATTEMPTS,
            problemRest = TrainingRanges.REST_LIMIT_BETWEEN_PROBLEMS,
            low = low, high = high,
        )
    }

    /** Projecting: burns on each project, explicit like limit attempts. */
    private fun planProjecting(minutes: Int, maxDiff: Double): List<PlanSlot> {
        val count = (minutes / TrainingRanges.PROJECT_SLOT_MINUTES)
            .coerceIn(TrainingRanges.PROJECT_COUNT)
        val low = maxDiff
        val high = clamp(maxDiff + TrainingRanges.LIMIT_BAND_ABOVE_MAX, maxDiff)
        return workBlocks(
            problems = count,
            attemptsPerProblem = TrainingRanges.BURNS_PER_PROJECT,
            attemptRest = TrainingRanges.REST_PROJECT_BETWEEN_BURNS,
            problemRest = TrainingRanges.REST_PROJECT_BETWEEN_PROJECTS,
            low = low, high = high,
        )
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
    private fun planPowerEndurance(minutes: Int, maxDiff: Double): List<PlanSlot> {
        val sets = (minutes / TrainingRanges.PE_SET_MINUTES).coerceIn(TrainingRanges.PE_SETS)
        val low = clampLow(maxDiff - TrainingRanges.PE_BAND_LOW_BELOW_MAX)
        val high = clampLow(maxDiff - TrainingRanges.PE_BAND_HIGH_BELOW_MAX)
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
    private fun planPyramid(minutes: Int, maxDiff: Double): List<PlanSlot> {
        val apex = maxDiff
        val tiers = if (minutes < 45) 3 else 4
        val base = clampLow(apex - (tiers - 1) * TrainingRanges.DIFF_PER_V_GRADE)
        val withDescent = minutes >= 90

        data class Tier(val diff: Double, val count: Int, val section: PlanSection)

        val ascent = (0 until tiers).map { i ->
            Tier(
                diff = min(base + i * TrainingRanges.DIFF_PER_V_GRADE, apex),
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
            // mid tier breathes, apex ± 1 V gets near-full recovery.
            val gradesBelowApex = (apex - tier.diff) / TrainingRanges.DIFF_PER_V_GRADE
            val rest = when {
                gradesBelowApex <= 1.0 -> TrainingRanges.REST_PYRAMID_HIGH_TIER
                gradesBelowApex <= 2.0 -> TrainingRanges.REST_PYRAMID_MID_TIER
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

    private fun firstWorkGrade(type: GeneratorType, maxDiff: Double, flashDiff: Double): Double =
        when (type) {
            GeneratorType.VOLUME -> clampLow(flashDiff - TrainingRanges.VOLUME_BAND_BELOW_FLASH)
            GeneratorType.POWER_ENDURANCE -> clampLow(maxDiff - TrainingRanges.PE_BAND_LOW_BELOW_MAX)
            GeneratorType.PYRAMID -> clampLow(maxDiff - TrainingRanges.PYRAMID_BASE_BELOW_APEX)
            GeneratorType.LIMIT, GeneratorType.PROJECTING -> maxDiff
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
            min(diff, userMax + TrainingRanges.CEILING_ABOVE_MAX),
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
