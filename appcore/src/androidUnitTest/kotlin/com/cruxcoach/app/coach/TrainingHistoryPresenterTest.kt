package com.cruxcoach.app.coach

import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.TrainingHistoryScreenModel
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Workout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The training history on iOS runs Android's TrainingHistoryViewModel (ported) against a real database. */
class TrainingHistoryPresenterTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun screen(): Pair<TrainingHistoryPresenter, TrainingHistoryScreenModel> {
        val p = TrainingHistoryPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { !p.state.value.loading }
        return p to TrainingHistoryScreenModel(p)
    }

    @Test
    fun `no finished training shows the empty history`() {
        kit.db { repo.saveWorkout(Workout("open", 1_000, null, "2026-10-12", null, null, updatedAt = 1_000)) }
        val (_, model) = screen()
        assertTrue(model.currentState.isEmpty)
        assertTrue(model.currentState.months.isEmpty())
    }

    @Test
    fun `finished trainings by month, newest first, with their completed sets`() {
        kit.db {
            workout("sep", "2026-09-20", at("2026-09-20"), title = "Gym")
            workout("oct5", "2026-10-05", at("2026-10-05"), title = null)
            workout("oct12", "2026-10-12", at("2026-10-12"), title = null, routineId = "builtin:baseline_tests")
            repo.saveSet(set("a", "oct12", 0, done = true))
            repo.saveSet(set("b", "oct12", 1, done = true))
            repo.saveSet(set("c", "oct12", 2, done = false))
        }
        val (p, model) = screen()
        val s = model.currentState
        assertFalse(s.isEmpty)
        assertEquals(listOf("2026-10", "2026-09"), s.months.map { it.month })
        assertEquals(listOf("oct12", "oct5"), s.months[0].rows.map { it.workoutId })
        assertEquals(listOf("sep"), s.months[1].rows.map { it.workoutId })
        assertEquals(2, p.state.value.months[0].second.first().sets)

        val newest = s.months[0].rows[0]
        // A built-in routine by its localized name; no title: "Training"; an own title as it is.
        assertEquals("tr_routine_baseline_tests", newest.title.key)
        assertEquals("trw_workout_default", s.months[0].rows[1].title.key)
        assertEquals("Gym", s.months[1].rows[0].title.args.single().text)
        // "Oct 12, 2026 · 45 min · 2 sets".
        val parts = newest.details.args.map { it.nested!! }
        assertEquals("date", parts[0].args.single().kind)
        assertEquals("2026-10-12", parts[0].args.single().text)
        assertEquals("trw_minutes", parts[1].key)
        assertEquals(45, parts[1].pluralCount)
        assertEquals("trw_sets_done", parts[2].key)
        assertEquals(2, parts[2].pluralCount)
        TrTextCheck.checkAll(s)

        // A training finished later appears at once.
        kit.db { workout("oct14", "2026-10-14", at("2026-10-14"), title = null) }
        kit.until("new training") { p.state.value.months.firstOrNull()?.second?.firstOrNull()?.workout?.id == "oct14" }
        assertEquals(3, model.currentState.months[0].rows.size)
        assertEquals(0, model.mapFailures)
    }

    /** A fixed noon of [day] in UTC: only the order of the start times matters here. */
    private fun at(day: String): Long = kotlinx.datetime.LocalDate.parse(day).toEpochDays().toLong() * 86_400_000L + 43_200_000L

    private fun workout(id: String, day: String, startedAt: Long, title: String?, routineId: String? = null) =
        repo.saveWorkout(Workout(id, startedAt, startedAt + 45 * 60_000L, day, title, routineId, updatedAt = startedAt))

    private fun set(id: String, workoutId: String, index: Int, done: Boolean) = ExerciseSet(
        id = id, workoutId = workoutId, exerciseSlug = "pull.pull_up", blockIndex = 0, setIndex = index,
        reps = 6, completedAt = if (done) 1_000L + index else null,
    )
}
