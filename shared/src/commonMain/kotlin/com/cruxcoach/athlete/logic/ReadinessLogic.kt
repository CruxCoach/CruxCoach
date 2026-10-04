package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.model.Checkin
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.Side

enum class ReadinessLevel { GO, ADAPT, REST }

/** Why today's recommendation is what it is; the first entry is the deciding factor. */
enum class ReadinessReason {
    SICK,
    INJURY_CLIMBING_PAUSED,
    INJURY_ACTIVE,
    FINGERS_TIRED,
    SKIN_LOW,
    LOW_SLEEP,
    LOW_ENERGY,
    LOW_MOTIVATION,
    ALL_GOOD,
}

data class Readiness(val level: ReadinessLevel, val reasons: List<ReadinessReason>) {
    val decidingFactor: ReadinessReason get() = reasons.first()
    /** True when board/wall climbing should not be today's main content. */
    val avoidClimbing: Boolean get() = reasons.any {
        it == ReadinessReason.SICK || it == ReadinessReason.INJURY_CLIMBING_PAUSED || it == ReadinessReason.SKIN_LOW
    }
    val avoidMaxFingerLoad: Boolean get() = reasons.any {
        it == ReadinessReason.FINGERS_TIRED || it == ReadinessReason.SICK
    }
}

/**
 * Turns the optional morning check-in plus open injuries into one
 * recommendation. Each answer can only make the day gentler, never harder,
 * and skin or fingers redirect the load instead of cancelling the session:
 * bad skin is a hangboard day, not a rest day.
 */
object ReadinessEvaluator {

    fun evaluate(checkin: Checkin?, activeInjuries: List<Injury>): Readiness {
        val reasons = mutableListOf<ReadinessReason>()
        var level = ReadinessLevel.GO
        fun adapt(reason: ReadinessReason) { reasons += reason; if (level == ReadinessLevel.GO) level = ReadinessLevel.ADAPT }

        if (checkin?.sick == true) { reasons += ReadinessReason.SICK; level = ReadinessLevel.REST }
        if (activeInjuries.any { it.climbingPaused }) adapt(ReadinessReason.INJURY_CLIMBING_PAUSED)
        else if (activeInjuries.isNotEmpty()) adapt(ReadinessReason.INJURY_ACTIVE)
        if (checkin != null) {
            if ((checkin.fingers ?: 5) <= 2) adapt(ReadinessReason.FINGERS_TIRED)
            if ((checkin.skin ?: 5) <= 2) adapt(ReadinessReason.SKIN_LOW)
            if ((checkin.sleep ?: 5) <= 2) adapt(ReadinessReason.LOW_SLEEP)
            if ((checkin.energy ?: 5) <= 2) adapt(ReadinessReason.LOW_ENERGY)
            if ((checkin.motivation ?: 5) <= 1) adapt(ReadinessReason.LOW_MOTIVATION)
            val veryLow = listOfNotNull(checkin.sleep, checkin.energy).count { it <= 1 }
            if (veryLow == 2 && level == ReadinessLevel.ADAPT) level = ReadinessLevel.REST
        }
        if (reasons.isEmpty()) reasons += ReadinessReason.ALL_GOOD
        return Readiness(level, reasons)
    }
}

enum class InjuryVerdict { OK, CAUTION, AVOID, ONE_SIDE_ONLY }

data class InjuryAdvice(val verdict: InjuryVerdict, val allowedSide: Side? = null, val injury: Injury? = null)

/**
 * Which exercises still fit while something hurts. This is a filter, not a
 * treatment plan: it hides or flags exercises that load the injured
 * structure, and for one-sided work on a limb injury it keeps the healthy
 * side available — e.g. one-arm pick-ups on the right hand while the left
 * ring finger heals, plus bar pull-ups, without any wall climbing.
 */
object InjuryAdvisor {

    private const val AVOID_SEVERITY = 4

    fun assess(def: ExerciseDefinition, activeInjuries: List<Injury>): InjuryAdvice {
        val advices = activeInjuries.filter { it.isActive }.map { assessOne(def, it) }
        advices.firstOrNull { it.verdict == InjuryVerdict.AVOID }?.let { return it }
        val oneSided = advices.filter { it.verdict == InjuryVerdict.ONE_SIDE_ONLY }
        if (oneSided.isNotEmpty()) {
            // Injuries on both sides leave no side to train.
            return if (oneSided.map { it.allowedSide }.distinct().size > 1)
                InjuryAdvice(InjuryVerdict.AVOID, injury = oneSided.first().injury)
            else oneSided.first()
        }
        return advices.firstOrNull { it.verdict == InjuryVerdict.CAUTION } ?: InjuryAdvice(InjuryVerdict.OK)
    }

    private fun assessOne(def: ExerciseDefinition, injury: Injury): InjuryAdvice {
        if (injury.climbingPaused && def.needsClimbingWall) return InjuryAdvice(InjuryVerdict.AVOID, injury = injury)
        val region = injury.region
        val hits = def.contraindications.any { it in region.bodyRegions } || def.domains.any { it in region.domains }
        if (!hits) return InjuryAdvice(InjuryVerdict.OK)
        val oneSided = injury.side == InjurySide.LEFT || injury.side == InjurySide.RIGHT
        if (def.unilateral && region.limb && oneSided) {
            val healthy = if (injury.side == InjurySide.LEFT) Side.RIGHT else Side.LEFT
            return InjuryAdvice(InjuryVerdict.ONE_SIDE_ONLY, allowedSide = healthy, injury = injury)
        }
        return if (injury.severity >= AVOID_SEVERITY) InjuryAdvice(InjuryVerdict.AVOID, injury = injury)
        else InjuryAdvice(InjuryVerdict.CAUTION, injury = injury)
    }
}
