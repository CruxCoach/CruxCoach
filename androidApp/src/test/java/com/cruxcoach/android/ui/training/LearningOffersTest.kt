package com.cruxcoach.android.ui.training

import android.app.Application
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.android.ui.training.workout.WorkoutSummaryScreen
import com.cruxcoach.android.ui.training.workout.WorkoutSummaryViewModel
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.SuggestionEventKind
import androidx.compose.ui.test.performClick
import kotlinx.datetime.plus
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The coach asks before it keeps a change (comparison review #3, #10): "suggest it like this from now on?" after a
 * training whose suggestion was changed, with Undo and a place on "What the coach learned".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LearningOffersTest : AthleteScreenTest() {

    @Before
    fun athlete() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.updateProfile { it.copy(equipmentConfigured = true, equipment = setOf(
            com.cruxcoach.athlete.catalog.EquipmentV2.NONE, com.cruxcoach.athlete.catalog.EquipmentV2.MAT,
            com.cruxcoach.athlete.catalog.EquipmentV2.HANGBOARD, com.cruxcoach.athlete.catalog.EquipmentV2.PULL_UP_BAR,
            com.cruxcoach.athlete.catalog.EquipmentV2.DUMBBELL, com.cruxcoach.athlete.catalog.EquipmentV2.BANDS)) }
    }

    /** Waits with the main looper running: writes come back on Main. */
    private fun until(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private val day get() = service.today().toString()
    private fun keptEvents() = repo.suggestionEventsForDay(day).filter { it.focus == PreferenceLearning.KEEP_CHOICE }

    @Test
    fun `a training with a swapped exercise asks once whether to keep it, with undo`() {
        val vm = loaded(TodayViewModel(service))
        vm.saveQuickCheckin(null, emptySet())
        until { vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        val first = vm.state.value.suggestion!!.routine.items.first { !it.warmup }.slug
        val to = vm.alternatives(first).first().def.slug
        vm.swapExercise(first, to)
        until { repo.suggestionEventsForDay(day).any { it.kind == SuggestionEventKind.SWAPPED } &&
            vm.state.value.suggestion!!.routine.items.any { it.slug == to } }
        vm.startSuggestion("Today")
        until { repo.openWorkout() != null && repo.suggestionEventsForDay(day).any { it.kind == SuggestionEventKind.STARTED } }
        val id = repo.openWorkout()!!.id
        service.finishWorkout(id, null, null)

        val summary = WorkoutSummaryViewModel(service).tracked()
        summary.load(id)
        until { !summary.state.value.loading }
        assertEquals(listOf(PreferenceLearning.Change(first, to)), summary.state.value.keep)
        render { WorkoutSummaryScreen(id, {}, {}, viewModel = summary) }
        click("summary_keep_yes")
        until { keptEvents().size == 1 && summary.state.value.keep.isEmpty() }
        assertEquals(listOf(first, to), keptEvents().single().slugs)
        val effect = PreferenceLearning.keptEffect(PreferenceLearning.kept(repo.suggestionEventsSince(0), service.today()))
        assertEquals(listOf(to), effect.preferred)
        assertTrue(effect.resting.getValue(first) >= service.today().plus(kotlinx.datetime.DatePeriod(days = PreferenceLearning.KEEP_MIN_DAYS)))

        // "Rückgängig" in the snackbar: nothing kept, the question is back.
        val undo = context.getString(com.cruxcoach.android.R.string.trpg_undo)
        waitForText(undo)
        compose.onNode(androidx.compose.ui.test.hasText(undo)).performClick()
        until { keptEvents().isEmpty() && summary.state.value.keep.isNotEmpty() }
        // "Nur heute": answered, nothing kept – and a later look at the summary does not ask again.
        summary.answerKeep(false)
        until { repo.suggestionEventsForDay(day).any { it.focus == PreferenceLearning.KEEP_TODAY_ONLY } }
        assertTrue(keptEvents().isEmpty())
        val again = WorkoutSummaryViewModel(service).tracked()
        again.load(id)
        until { !again.state.value.loading }
        assertEquals(emptyList(), again.state.value.keep)
    }

    @Test
    fun `a kept choice shows on the learned page and can be forgotten`() {
        val until = service.today().plus(kotlinx.datetime.DatePeriod(days = 21))
        repo.logSuggestionEvent(com.cruxcoach.athlete.model.SuggestionEvent("keep1", day, System.currentTimeMillis() - 1000,
            SuggestionEventKind.FEEDBACK, PreferenceLearning.KEEP_CHOICE, listOf("pull.pull_up", "pull.inverted_row"), workoutId = "w",
            value = until.toEpochDays().toDouble()))
        val settings = com.cruxcoach.android.ui.training.athlete.AthleteSettingsViewModel(service).tracked()
        until { settings.state.value.learned.kept.isNotEmpty() }
        // The replaced exercise rests until then.
        assertEquals(until, settings.state.value.learned.resting["pull.pull_up"])
        render { com.cruxcoach.android.ui.training.athlete.AthleteSettingsScreen({}, viewModel = settings,
            section = com.cruxcoach.android.ui.training.athlete.SettingsSection.EXCLUDED) }
        click("learned_kept_pull.pull_up_forget")
        until { settings.state.value.learned.kept.isEmpty() }
        assertTrue("pull.pull_up" !in settings.state.value.learned.resting)
    }
}
