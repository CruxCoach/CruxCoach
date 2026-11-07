package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.CoachProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side

/**
 * How far a planned load may move from one training to the next (owner 2026-11-06, issue #59). The
 * training simulation found two things the performance value alone did:
 *
 * - **Too slow with a lot in reserve.** "3+" is the largest reserve answer and a value rises at most 10 %
 *   per set, so a start that was too light stayed too light for weeks. After a training in which every work
 *   set of a strength exercise was answered "3+", the next one plans at least two weight steps more. Not for
 *   finger work (hangs, pick-ups, campus) and not while an injury, the return ramp, a comeback plan or the
 *   youth and first-year rules keep loads careful ([mayStepUp]).
 * - **Too fast at small loads.** Below [SMALL_LOAD_KG] (one dumbbell for the wrists, 3 → 5 kg) a load moves
 *   at most one step per training. This wins over the step up.
 *
 * A load the athlete wrote into the workout is never changed here.
 */
object LoadSteps {

    const val SMALL_LOAD_KG = 10.0
    const val EASY_STEPS = 2
    /** The reserve answer "3+". */
    private const val EASY_RIR = 3

    /**
     * Whether loads may take the bigger step at all: not during an open injury or a comeback session, not for a
     * climber under 18 or in the first year, and not in the return ramp after a break. [returnState] is asked last;
     * a return ramp that could not be worked out counts as one (fail closed, owner 2026-11-06).
     */
    fun mayStepUp(comeback: Boolean, openInjury: Boolean, coach: CoachProfile, returnState: () -> Result<ReturnState?>): Boolean {
        if (comeback || openInjury) return false
        if (coach.ageBand == AgeBand.UNDER_16 || coach.ageBand == AgeBand.Y16_17 || coach.experience == ExperienceBand.UNDER_1) return false
        return returnState().fold(onSuccess = { it == null }, onFailure = { false })
    }

    /** Strength work that may step up after an easy training: loaded, not finger work. */
    fun strengthExercise(def: ExerciseDefinition): Boolean =
        (def.load == LoadMode.EXTERNAL || def.load == LoadMode.BODYWEIGHT_PLUS) &&
            def.kind == ExerciseKind.LOAD_REPS && LoadDomain.FINGER !in def.domains && EquipmentV2.CAMPUS_BOARD !in def.equipment

    /** The work sets of the last training with [def] on [side], from [history] (newest first, any order). */
    fun lastSession(history: List<ExerciseSet>, side: Side?): List<ExerciseSet> {
        val done = history.filter { it.isCompleted && it.setType == SetType.WORK && it.side == side && it.loadKg != null }
        val last = done.maxByOrNull { it.completedAt ?: 0L }?.workoutId ?: return emptyList()
        return done.filter { it.workoutId == last }
    }

    /**
     * The load to plan instead of [planned] (the logged number: one dumbbell, added weight), given the last
     * training's work sets [last]. [incrementKg] is the athlete's smallest step.
     */
    fun adjust(def: ExerciseDefinition, planned: Double?, last: List<ExerciseSet>, incrementKg: Double, mayStepUp: Boolean): Double? {
        val lastLoad = last.mapNotNull { it.loadKg }.maxOrNull() ?: return planned
        if (def.load != LoadMode.EXTERNAL && def.load != LoadMode.BODYWEIGHT_PLUS) return planned
        val step = ProgressionAdvisor.increment(def, incrementKg)
        var load = planned ?: lastLoad
        val easy = last.isNotEmpty() && last.all { (it.rir ?: 0) >= EASY_RIR }
        if (mayStepUp && easy && strengthExercise(def)) load = maxOf(load, lastLoad + EASY_STEPS * step)
        // Small loads: one step per training at most, whatever asked for more.
        if (def.load == LoadMode.EXTERNAL && lastLoad < SMALL_LOAD_KG && load > lastLoad + step + 1e-9) load = lastLoad + step
        return load
    }
}
