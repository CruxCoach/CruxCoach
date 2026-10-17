package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Target against result of a done set, for the set rows of the training view. */
class SetOutcomesTest {

    private fun def(kind: ExerciseKind, load: LoadMode) =
        ExerciseDefinition("x.$kind$load", ExerciseCategoryV2.PULL, kind, load, i18n = mapOf("en" to ExerciseText("x")))

    private val pullUp = def(ExerciseKind.REPS, LoadMode.BODYWEIGHT)
    private val weighted = def(ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS)
    private val row = def(ExerciseKind.LOAD_REPS, LoadMode.EXTERNAL)
    private val hang = def(ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS)
    private val plank = def(ExerciseKind.TIME, LoadMode.BODYWEIGHT)

    private fun done(
        reps: Int? = null, targetReps: Int? = null, duration: Double? = null, targetDuration: Double? = null,
        load: Double? = null, targetLoad: Double? = null, type: SetType = SetType.WORK, completed: Boolean = true,
    ) = ExerciseSet("s", "w", "x", 0, 0, type, targetReps = targetReps, targetDurationS = targetDuration, targetLoadKg = targetLoad,
        reps = reps, durationS = duration, loadKg = load, completedAt = if (completed) 1L else null)

    private fun main(def: ExerciseDefinition, set: ExerciseSet) = SetOutcomes.of(def, set).main
    private fun load(def: ExerciseDefinition, set: ExerciseSet) = SetOutcomes.of(def, set).load

    @Test
    fun repsAgainstTheTarget() {
        assertEquals(TargetOutcome.MET, main(pullUp, done(reps = 8, targetReps = 8)))
        assertEquals(TargetOutcome.UNDER, main(pullUp, done(reps = 6, targetReps = 8)))
        assertEquals(TargetOutcome.OVER, main(pullUp, done(reps = 9, targetReps = 8)))
        // Body weight only: no load result.
        assertEquals(null, load(pullUp, done(reps = 8, targetReps = 8, load = 5.0, targetLoad = 0.0)))
    }

    @Test
    fun holdsWithinHalfASecondAreMet() {
        assertEquals(TargetOutcome.MET, main(hang, done(duration = 10.0, targetDuration = 10.0)))
        assertEquals(TargetOutcome.MET, main(hang, done(duration = 9.5, targetDuration = 10.0)))
        assertEquals(TargetOutcome.MET, main(plank, done(duration = 30.5, targetDuration = 30.0)))
        assertEquals(TargetOutcome.UNDER, main(hang, done(duration = 7.0, targetDuration = 10.0)))
        assertEquals(TargetOutcome.UNDER, main(hang, done(duration = 9.4, targetDuration = 10.0)))
        assertEquals(TargetOutcome.OVER, main(plank, done(duration = 31.0, targetDuration = 30.0)))
    }

    @Test
    fun lighterIsUnderAlsoWithAssistance() {
        assertEquals(TargetOutcome.MET, load(weighted, done(reps = 5, targetReps = 5, load = 20.0, targetLoad = 20.0)))
        assertEquals(TargetOutcome.UNDER, load(weighted, done(reps = 5, targetReps = 5, load = 17.5, targetLoad = 20.0)))
        assertEquals(TargetOutcome.OVER, load(weighted, done(reps = 5, targetReps = 5, load = 22.5, targetLoad = 20.0)))
        // More assistance (−10 kg instead of −5 kg) is lighter.
        assertEquals(TargetOutcome.UNDER, load(weighted, done(reps = 5, targetReps = 5, load = -10.0, targetLoad = -5.0)))
        assertEquals(TargetOutcome.OVER, load(weighted, done(reps = 5, targetReps = 5, load = 0.0, targetLoad = -5.0)))
        // No load entered counts as 0; rounding noise of added-up steps is no difference.
        assertEquals(TargetOutcome.UNDER, load(hang, done(duration = 10.0, targetDuration = 10.0, targetLoad = 5.0)))
        assertEquals(TargetOutcome.MET, load(row, done(reps = 10, targetReps = 10, load = 0.1 + 0.2, targetLoad = 0.3)))
        assertEquals(TargetOutcome.UNDER, load(row, done(reps = 10, targetReps = 10, load = 12.0, targetLoad = 14.0)))
        // Reps and load are judged apart: all reps with less weight.
        val lighter = SetOutcomes.of(row, done(reps = 10, targetReps = 10, load = 12.0, targetLoad = 14.0))
        assertEquals(SetOutcomes.Result(TargetOutcome.MET, TargetOutcome.UNDER), lighter)
    }

    @Test
    fun noResultWithoutATargetOrForOtherSets() {
        val none = SetOutcomes.Result(null, null)
        assertEquals(none, SetOutcomes.of(pullUp, done(reps = 8, targetReps = 8, completed = false)))
        assertEquals(none, SetOutcomes.of(weighted, done(reps = 8, targetReps = 8, load = 10.0, targetLoad = 10.0, type = SetType.WARMUP)))
        assertEquals(none, SetOutcomes.of(weighted, done(reps = 3, targetReps = 5, load = 30.0, targetLoad = 20.0, type = SetType.TEST)))
        assertEquals(none, SetOutcomes.of(def(ExerciseKind.INTERVAL, LoadMode.BODYWEIGHT_PLUS), done(duration = 7.0, targetDuration = 7.0, load = 0.0, targetLoad = 0.0)))
        assertEquals(none, SetOutcomes.of(def(ExerciseKind.CLIMB, LoadMode.NONE), done(reps = 4, targetReps = 4, duration = 60.0, targetDuration = 60.0)))
        assertEquals(none, SetOutcomes.of(pullUp, done(reps = 8)))
        assertTrue(SetOutcomes.of(hang, done(duration = 10.0)).isEmpty)
        // A target load without a target time still says something about the load.
        assertEquals(SetOutcomes.Result(null, TargetOutcome.MET), SetOutcomes.of(hang, done(duration = 10.0, load = 5.0, targetLoad = 5.0)))
    }
}
