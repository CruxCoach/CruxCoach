package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Planner pieces of the training view and circuits: "+ Satz" and planned circuit sets. */
class WorkoutPlannerCircuitTest {

    private fun ex(slug: String, kind: ExerciseKind, load: LoadMode, unilateral: Boolean = false, restS: Int = 90) =
        ExerciseDefinition(slug, ExerciseCategoryV2.PULL, kind, load, unilateral,
            defaults = Prescription(sets = 3, repsMin = 6, repsMax = 8, durationS = 10, restS = restS), i18n = mapOf("en" to ExerciseText(slug)))

    private val catalog = ExerciseCatalog(1, listOf(
        ex("pull.pull_up", ExerciseKind.REPS, LoadMode.BODYWEIGHT, restS = 120),
        ex("finger.max_hang", ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS),
        ex("pull.dumbbell_row", ExerciseKind.LOAD_REPS, LoadMode.EXTERNAL, unilateral = true),
    ))

    private var n = 0
    private val newId = { "id${n++}" }

    @Test
    fun anExtraSetAfterAMissedSetPrefillsTheTarget() {
        val missed = ExerciseSet("a", "w", "pull.pull_up", 0, 1, targetReps = 8, reps = 6, rir = 0, completedAt = 5, note = "tired",
            restS = 15, circuit = 1, roundRestS = 120)
        val block = listOf(missed.copy(id = "z", setIndex = 0, reps = 8), missed)
        val extra = WorkoutPlanner.extraSet(block, newId).single()
        assertEquals(2, extra.setIndex)
        assertEquals(8, extra.reps); assertEquals(8, extra.targetReps)
        assertNull(extra.completedAt); assertNull(extra.rir); assertNull(extra.note)
        // The circuit and its rests stay: one more round for this exercise.
        assertEquals(1, extra.circuit); assertEquals(15, extra.restS); assertEquals(120, extra.roundRestS)
        // A hold let go early: the next one is planned for the full time again; a set without target keeps its value.
        val hang = ExerciseSet("h", "w", "finger.max_hang", 0, 0, targetDurationS = 10.0, durationS = 7.0, loadKg = 5.0, targetLoadKg = 5.0, completedAt = 5)
        assertEquals(10.0, WorkoutPlanner.extraSet(listOf(hang), newId).single().durationS)
        val free = ExerciseSet("f", "w", "pull.pull_up", 0, 0, reps = 11, completedAt = 5)
        assertEquals(11, WorkoutPlanner.extraSet(listOf(free), newId).single().reps)
        // Both hands of a one-sided exercise get the extra set.
        val sides = Side.entries.map { ExerciseSet("r$it", "w", "pull.dumbbell_row", 0, 0, side = it, targetReps = 10, reps = 9, completedAt = 5) }
        assertEquals(listOf(Side.LEFT to 10, Side.RIGHT to 10), WorkoutPlanner.extraSet(sides, newId).map { it.side to it.reps })
    }

    private fun plan(item: RoutineItem) = WorkoutPlanner.plan("w", 0, item, catalog, emptyList(), emptyList(), 70.0, newId = newId).sets

    @Test
    fun circuitMembersArePlannedWithTheCircuitsRests() {
        val sets = plan(RoutineItem("pull.pull_up", sets = 3, restS = 150, circuit = 2, circuitRestS = 0, roundRestS = 200))
        assertEquals(listOf(2, 2, 2), sets.map { it.circuit })
        assertEquals(listOf(0, 0, 0), sets.map { it.restS })
        assertEquals(listOf(200, 200, 200), sets.map { it.roundRestS })
        // Rests not set: 15 s between exercises, the round rest from the exercise's own rest.
        val defaults = plan(RoutineItem("pull.pull_up", sets = 2, restS = 150, circuit = 1))
        assertEquals(listOf(15, 15), defaults.map { it.restS })
        assertEquals(listOf(150, 150), defaults.map { it.roundRestS })
        // Outside a circuit, and for an item that cannot be in one, nothing changes.
        val straight = plan(RoutineItem("pull.pull_up", sets = 2, restS = 150))
        assertEquals(listOf(150, 150), straight.map { it.restS }); assertEquals(listOf(null, null), straight.map { it.circuit })
        val warmup = plan(RoutineItem("pull.pull_up", sets = 1, restS = 30, warmup = true, circuit = 1, circuitRestS = 0))
        assertEquals(30, warmup.single().restS); assertNull(warmup.single().circuit); assertNull(warmup.single().roundRestS)
    }
}

/** The training view's ledger entries are recorded, but teach the coach nothing it would act on yet. */
class TrainingViewLedgerTest {

    private val today = LocalDate.parse("2026-10-05") // a Monday
    private val dayMs = 86_400_000L
    private val todayMs = today.toEpochDays().toLong() * dayMs + dayMs / 2

    private fun feedback(daysAgo: Int, focus: String, slugs: List<String> = emptyList(), value: Double? = null, at: Long = 0) =
        SuggestionEvent("f$daysAgo$focus$at", today.minus(DatePeriod(days = daysAgo)).toString(), todayMs - daysAgo * dayMs + at,
            SuggestionEventKind.FEEDBACK, focus = focus, slugs = slugs, value = value)

    private fun started(daysAgo: Int) = SuggestionEvent("s$daysAgo", today.minus(DatePeriod(days = daysAgo)).toString(),
        todayMs - daysAgo * dayMs + 500, SuggestionEventKind.STARTED, focus = "PULL_PUSH", slugs = listOf("pull.pull_up"))

    @Test
    fun formatEndedEarlyAndExtraSetsAreNeutral() {
        val base = listOf(started(7), started(14))
        val noise = listOf(7, 14).flatMap { d ->
            listOf(
                feedback(d, PreferenceLearning.CHOSEN_FORMAT + "circuit"),
                feedback(d, PreferenceLearning.ENDED_EARLY, listOf("pull.pull_up"), value = 0.5),
                feedback(d, PreferenceLearning.EXTRA_SET, listOf("push.push_up"), value = 4.0),
            )
        }
        assertEquals(PreferenceLearning.affinity(base, emptyList(), today), PreferenceLearning.affinity(base + noise, emptyList(), today))
        assertEquals(emptyMap(), PreferenceLearning.usualChoices(base + noise, today))
    }

    @Test
    fun aFormatChoiceIsNoKindChoice() {
        // Two Mondays with pull & push, each time the circuit chip tapped afterwards: the kind stays usual.
        val events = listOf(7, 14).flatMap { d ->
            listOf(feedback(d, PreferenceLearning.CHOSEN_KIND + "PULL_PUSH"), feedback(d, PreferenceLearning.CHOSEN_FORMAT + "circuit", at = 100), started(d))
        }
        val usual = PreferenceLearning.usualChoices(events, today)[1]
        assertEquals(SuggestionFocus.PULL_PUSH, usual?.focus)
        assertNull(usual?.minutes)
        assertEquals("chosen_format:", PreferenceLearning.CHOSEN_FORMAT)
        assertEquals("ended_early", PreferenceLearning.ENDED_EARLY)
        assertEquals("extra_set", PreferenceLearning.EXTRA_SET)
    }
}
