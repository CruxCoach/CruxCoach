package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.domain.playlist.GeneratorType

/**
 * Energy before hard finger work (holistic audit P2-17).
 *
 * When the energy guard sees too little energy over the last logged days ([RedsSignal.HIGH_DEFICIT]) or
 * repeatedly too little carbohydrate on training days ([RedsSignal.LOW_CARBS_ON_TRAINING_DAYS]), and today's
 * suggestion goes to the finger limit – maximal hangs or lifts, or a limit or project board session – Today's
 * card says so in one supportive line with the numbers and offers "lighter" as one tap. It is a hint only:
 * nothing changes by itself and nothing is locked (owner 2026-10-09: warn with numbers, never lock).
 */
object EnergyBeforeTraining {

    enum class Basis {
        /** Logged energy clearly under the estimated need over the compared days. */
        DEFICIT,
        /** Far too little logged carbohydrate on several training days. */
        LOW_CARBS,
    }

    /** What the line is based on; [report] carries the numbers it names. */
    data class Hint(val basis: Basis, val report: EnergyReport)

    /** Board sessions that go to the finger limit: whole problems at or above the working max. */
    private val HARD_BOARD = setOf(GeneratorType.LIMIT, GeneratorType.PROJECTING)

    /** Today's suggestion asks for maximal finger load: strength hangs or lifts, or a limit or project board session. */
    fun isHardOnFingers(s: SessionSuggestion, catalog: ExerciseCatalog): Boolean =
        s.boardPlan?.type in HARD_BOARD ||
            s.routine.items.any { item -> !item.warmup && catalog[item.slug]?.let { ReadinessModifiers.isMaxFinger(it) } == true }

    /** The hint for today's card, or null when the energy is fine or today's training is not hard on the fingers. */
    fun hint(report: EnergyReport, suggestion: SessionSuggestion?, catalog: ExerciseCatalog): Hint? {
        val basis = when {
            RedsSignal.HIGH_DEFICIT in report.signals -> Basis.DEFICIT
            RedsSignal.LOW_CARBS_ON_TRAINING_DAYS in report.signals -> Basis.LOW_CARBS
            else -> return null
        }
        if (suggestion == null || !isHardOnFingers(suggestion, catalog)) return null
        return Hint(basis, report)
    }
}
