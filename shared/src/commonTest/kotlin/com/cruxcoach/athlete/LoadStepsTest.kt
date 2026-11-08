package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** Owner 2026-11-06 (#59): two steps after an easy training for strength work; small loads one step at a time. */
class LoadStepsTest {

    private fun def(slug: String, kind: ExerciseKind, load: LoadMode, equipment: List<EquipmentV2>, domains: List<LoadDomain> = emptyList(),
                    defaults: Prescription = Prescription(sets = 3, repsMin = 8, repsMax = 10)) =
        ExerciseDefinition(slug, ExerciseCategoryV2.PUSH, kind, load, false, equipment, domains, defaults = defaults,
            i18n = mapOf("en" to ExerciseText(slug)))

    private val press = def("push.press", ExerciseKind.LOAD_REPS, LoadMode.EXTERNAL, listOf(EquipmentV2.DUMBBELL), implements = 2)
    private val wrist = def("antagonist.wrist", ExerciseKind.LOAD_REPS, LoadMode.EXTERNAL, listOf(EquipmentV2.DUMBBELL))
    private val pickup = def("finger.pickup", ExerciseKind.HANG, LoadMode.EXTERNAL, listOf(EquipmentV2.PICKUP_BLOCK), listOf(LoadDomain.FINGER))

    private fun def(slug: String, kind: ExerciseKind, load: LoadMode, equipment: List<EquipmentV2>, implements: Int) =
        def(slug, kind, load, equipment).copy(implements = implements)

    private fun sets(d: ExerciseDefinition, load: Double, rir: Int, workout: String = "w1", at: Long = 1_000L) = (0 until 3).map {
        ExerciseSet("$workout-$it", workout, d.slug, 0, it, SetType.WORK, reps = 10, targetReps = 10, loadKg = load, rir = rir, completedAt = at + it)
    }

    @Test
    fun anEasyTrainingStepsUpTwiceForStrengthWorkOnly() {
        assertEquals(22.0, LoadSteps.adjust(press, 21.0, sets(press, 20.0, rir = 3), 1.0, mayStepUp = true))
        // Not all "3+": the value decides as before.
        assertEquals(21.0, LoadSteps.adjust(press, 21.0, sets(press, 20.0, rir = 2), 1.0, mayStepUp = true))
        // Something keeps loads careful (injury, return ramp, comeback, youth, first year).
        assertEquals(21.0, LoadSteps.adjust(press, 21.0, sets(press, 20.0, rir = 3), 1.0, mayStepUp = false))
        // Finger work never takes the bigger step.
        assertEquals(30.0, LoadSteps.adjust(pickup, 30.0, sets(pickup, 30.0, rir = 3), 1.0, mayStepUp = true))
    }

    @Test
    fun theBiggerStepIsAllowedOnlyWhenNothingKeepsLoadsCareful() {
        val adult = CoachProfile(ageBand = AgeBand.Y18_39, experience = ExperienceBand.Y3_5)
        val noRamp: () -> Result<ReturnState?> = { Result.success(null) }
        assertEquals(true, LoadSteps.mayStepUp(comeback = false, openInjury = false, coach = adult, returnState = noRamp))
        assertEquals(false, LoadSteps.mayStepUp(comeback = true, openInjury = false, coach = adult, returnState = noRamp))
        assertEquals(false, LoadSteps.mayStepUp(comeback = false, openInjury = true, coach = adult, returnState = noRamp))
        assertEquals(false, LoadSteps.mayStepUp(false, false, adult.copy(ageBand = AgeBand.UNDER_16), noRamp))
        assertEquals(false, LoadSteps.mayStepUp(false, false, adult.copy(ageBand = AgeBand.Y16_17), noRamp))
        assertEquals(false, LoadSteps.mayStepUp(false, false, adult.copy(experience = ExperienceBand.UNDER_1), noRamp))
        val ramp = ReturnState(daysOff = 20, week = 1, setsFactor = 0.6, noMaxFinger = true, fingerOnly = false, reason = ReturnReason.entries.first())
        assertEquals(false, LoadSteps.mayStepUp(false, false, adult) { Result.success(ramp) })
        // A return ramp that could not be worked out keeps loads careful (fail closed, owner 2026-11-06).
        assertEquals(false, LoadSteps.mayStepUp(false, false, adult) { Result.failure(IllegalStateException("no data")) })
    }

    @Test
    fun smallLoadsMoveOneStepAtATimeAndThatWins() {
        // Simulation: radial/ulnar deviation went from 3 to 5 kg between two trainings.
        assertEquals(4.0, LoadSteps.adjust(wrist, 5.0, sets(wrist, 3.0, rir = 1), 1.0, mayStepUp = true))
        assertEquals(4.0, LoadSteps.adjust(wrist, 3.5, sets(wrist, 3.0, rir = 3), 1.0, mayStepUp = true))
        // Going down is never held back.
        assertEquals(2.0, LoadSteps.adjust(wrist, 2.0, sets(wrist, 3.0, rir = 0), 1.0, mayStepUp = true))
        // From 10 kg on the bigger step applies.
        assertEquals(12.0, LoadSteps.adjust(wrist, 11.0, sets(wrist, 10.0, rir = 3), 1.0, mayStepUp = true))
    }

    @Test
    fun planningUsesTheStepsButNeverOverridesTheAthletesLoad() {
        val catalog = ExerciseCatalog(1, listOf(wrist))
        val history = sets(wrist, 3.0, rir = 1)
        var n = 0
        fun plan(item: RoutineItem, capacity: Capacity?) = WorkoutPlanner.plan("w2", 0, item, catalog, history, emptyList(), 70.0,
            capacityFor = { capacity }, incrementKg = 1.0, newId = { "id${n++}" }, stepUp = true).sets
        // A value that would plan 6 kg plans 4 kg: one step from the 3 kg of last time.
        val strong = Capacity(CapacityKind.E1RM_TOTAL, 6.0 * (1 + 12 / 30.0), 70.0)
        assertEquals(listOf(4.0, 4.0, 4.0), plan(RoutineItem(wrist.slug, repsMax = 10), strong).map { it.loadKg })
        assertEquals(listOf(4.0, 4.0, 4.0), plan(RoutineItem(wrist.slug, repsMax = 10), strong).map { it.targetLoadKg })
        // A load the athlete wrote into the workout stays.
        assertEquals(listOf(7.0, 7.0, 7.0), plan(RoutineItem(wrist.slug, repsMax = 10, loadKg = 7.0), strong).map { it.loadKg })
    }
}
