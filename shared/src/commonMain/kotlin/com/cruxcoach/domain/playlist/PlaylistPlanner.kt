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
     *  1-V steps, 2 problems per tier, capped at 6 problems, then a
     *  3–5 min transition rest. */
    private fun buildWarmUpLadder(maxDiff: Double, firstWorkDiff: Double): List<PlanSlot> {
        val slots = mutableListOf<PlanSlot>()
        var tier = clampLow(maxDiff - TrainingRanges.WARMUP_START_BELOW_MAX)
        val ceiling = firstWorkDiff - TrainingRanges.DIFF_PER_V_GRADE
        while (tier <= ceiling && slots.size < TrainingRanges.WARMUP_MAX_PROBLEMS) {
            repeat(TrainingRanges.WARMUP_PROBLEMS_PER_TIER) {
                if (slots.size < TrainingRanges.WARMUP_MAX_PROBLEMS) {
                    slots.add(climbSlot(tier, PlanSection.WARM_UP))
                }
            }
            tier += TrainingRanges.DIFF_PER_V_GRADE
        }
        if (slots.isEmpty()) {
            // Even a V0 climber warms up on something: one tier at the floor.
            repeat(TrainingRanges.WARMUP_PROBLEMS_PER_TIER) {
                slots.add(climbSlot(TrainingRanges.MIN_DIFFICULTY, PlanSection.WARM_UP))
            }
        }
        slots.add(PlanSlot.RestSlot(TrainingRanges.REST_AFTER_WARMUP, PlanSection.WARM_UP))
        return slots
    }

    private fun planVolume(minutes: Int, flashDiff: Double, maxDiff: Double): List<PlanSlot> {
        val count = (minutes / TrainingRanges.VOLUME_SLOT_MINUTES)
            .coerceIn(TrainingRanges.VOLUME_COUNT)
        val high = clamp(flashDiff, maxDiff)
        val low = clampLow(high - TrainingRanges.VOLUME_BAND_BELOW_FLASH)
        val slots = mutableListOf<PlanSlot>()
        for (i in 0 until count) {
            slots.add(PlanSlot.ClimbSlot(low, high, PlanSection.MAIN))
            // Lattice: a long mid-block break keeps the second half honest.
            if (minutes >= 60 && i == count / 2 - 1) {
                slots.add(PlanSlot.RestSlot(TrainingRanges.REST_VOLUME_MID_BREAK, PlanSection.MAIN))
            }
        }
        return slots
    }

    private fun planLimit(minutes: Int, maxDiff: Double): List<PlanSlot> {
        val count = (minutes / TrainingRanges.LIMIT_SLOT_MINUTES)
            .coerceIn(TrainingRanges.LIMIT_COUNT)
        val low = maxDiff
        val high = clamp(maxDiff + TrainingRanges.LIMIT_BAND_ABOVE_MAX, maxDiff)
        return interleaveRests(
            List(count) { PlanSlot.ClimbSlot(low, high, PlanSection.PEAK) },
            TrainingRanges.REST_LIMIT_BETWEEN_PROBLEMS,
        )
    }

    private fun planProjecting(minutes: Int, maxDiff: Double): List<PlanSlot> {
        val count = (minutes / TrainingRanges.PROJECT_SLOT_MINUTES)
            .coerceIn(TrainingRanges.PROJECT_COUNT)
        val low = maxDiff
        val high = clamp(maxDiff + TrainingRanges.LIMIT_BAND_ABOVE_MAX, maxDiff)
        return interleaveRests(
            List(count) { PlanSlot.ClimbSlot(low, high, PlanSection.PEAK) },
            TrainingRanges.REST_PROJECT_BETWEEN_PROJECTS,
        )
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
            ascent.dropLast(1).reversed().map { it.copy(section = PlanSection.DESCENT) }
        } else emptyList()

        val slots = mutableListOf<PlanSlot>()
        (ascent + descent).forEach { tier ->
            // Upper-half tiers earn the long rests; the base flows.
            val rest = if (apex - tier.diff <= TrainingRanges.DIFF_PER_V_GRADE) {
                TrainingRanges.REST_PYRAMID_HIGH_TIER
            } else {
                TrainingRanges.REST_PYRAMID_LOW_TIER
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

    private fun interleaveRests(climbs: List<PlanSlot.ClimbSlot>, restSeconds: Int): List<PlanSlot> {
        val slots = mutableListOf<PlanSlot>()
        climbs.forEachIndexed { i, climb ->
            if (i > 0) slots.add(PlanSlot.RestSlot(restSeconds, climb.section))
            slots.add(climb)
        }
        return slots
    }

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

/** Rough wall-clock estimate for a plan (UI preview): climbing + rests. */
fun PlaylistPlan.estimatedMinutes(): Int {
    val restSeconds = slots.filterIsInstance<PlanSlot.RestSlot>().sumOf { it.seconds }
    val climbCount = slots.count { it is PlanSlot.ClimbSlot }
    // ~2.5 min per problem slot (attempts + short implicit rests).
    return ceil(climbCount * 2.5 + restSeconds / 60.0).toInt()
}
