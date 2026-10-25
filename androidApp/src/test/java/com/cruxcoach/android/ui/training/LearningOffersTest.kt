package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.android.ui.training.workout.WorkoutSummaryScreen
import com.cruxcoach.android.ui.training.workout.WorkoutSummaryViewModel
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.SuggestionEventKind
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The coach asks before it keeps a change (comparison review #3, #10) or takes over a logbook grade: "suggest it like this from now on?" after a
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

    /**
     * Clicks the snackbar's [action] once it alone is shown. Waiting for any text containing it can return before the
     * snackbar is there, and Material's snackbar host keeps a fading one next to the new one for a moment (feature CI
     * run 40220048169 failed on this click). A semantics click needs no settled layout.
     */
    private fun clickSnackbarAction(action: String) {
        val matcher = androidx.compose.ui.test.hasText(action) and androidx.compose.ui.test.hasClickAction()
        until { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        compose.onNode(matcher).performSemanticsAction(SemanticsActions.OnClick)
    }
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
        clickSnackbarAction(undo)
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

    @Test
    fun `lighter chosen twice for one kind is offered for good, with undo, and a no is kept`() {
        val vm = loaded(TodayViewModel(service))
        vm.saveQuickCheckin(null, emptySet())
        until { vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        val focus = vm.state.value.suggestion!!.focus
        // "Lighter" for this kind of training eight days ago, and again today.
        repo.logSuggestionEvent(com.cruxcoach.athlete.model.SuggestionEvent("old", service.today().minus(kotlinx.datetime.DatePeriod(days = 8)).toString(),
            System.currentTimeMillis() - 8 * 86_400_000L, SuggestionEventKind.NEXT, focus.name,
            feedback = com.cruxcoach.athlete.model.SuggestionFeedback.TOO_HARD))
        vm.chooseLevel(-1)
        until { vm.state.value.levelOffer == PreferenceLearning.LevelOffer(focus, -1) }
        render { com.cruxcoach.android.ui.training.today.TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        scrollTo("today_list", "today_level_offer")
        click("today_level_offer_yes")
        until { repo.profile().keptLevels[focus.name] == -1 && vm.state.value.levelShift == 0 && vm.state.value.levelOffer == null }
        until { SuggestionReason.LEVEL_EASIER_KEPT in vm.state.value.suggestion!!.reasons }
        // "Rückgängig": the step goes, today's "lighter" is back, and so is the question.
        val undo = context.getString(com.cruxcoach.android.R.string.trpg_undo)
        clickSnackbarAction(undo)
        until { repo.profile().keptLevels.isEmpty() && vm.state.value.levelShift == -1 && vm.state.value.levelOffer != null }
        // "Nein": not asked again.
        vm.answerLevelOffer(false)
        until { repo.suggestionEventsForDay(day).any { it.focus == PreferenceLearning.LEVEL_ANSWER + focus.name && it.value == 0.0 } }
        vm.chooseLevel(0)
        vm.chooseLevel(-1)
        until { repo.suggestionEventsForDay(day).count { it.feedback == com.cruxcoach.athlete.model.SuggestionFeedback.TOO_HARD } >= 2 }
        compose.waitForIdle()
        assertEquals(null, vm.state.value.levelOffer)
        assertTrue(repo.profile().keptLevels.isEmpty())
    }

    @Test
    fun `a kept level shows on the learned page and can be forgotten`() {
        repo.updateProfile { it.copy(keptLevels = mapOf("PULL_PUSH" to 1)) }
        val settings = com.cruxcoach.android.ui.training.athlete.AthleteSettingsViewModel(service).tracked()
        until { settings.state.value.learned.levels.isNotEmpty() }
        render { com.cruxcoach.android.ui.training.athlete.AthleteSettingsScreen({}, viewModel = settings,
            section = com.cruxcoach.android.ui.training.athlete.SettingsSection.EXCLUDED) }
        click("learned_level_PULL_PUSH_forget")
        until { repo.profile().keptLevels.isEmpty() && settings.state.value.learned.levels.isEmpty() }
    }

    @Test
    fun `a clearly higher logbook grade is offered once, taken over with undo, and a no holds`() {
        val board = com.cruxcoach.data.repository.PersonalBoardRepositoryImpl(com.cruxcoach.db.secure.SecureDatabase(secureDriver))
        // Twelve 7a sends on the board over the last weeks; the saved grade is 6c.
        (10 until 22).forEach { ago ->
            val d = service.today().minus(kotlinx.datetime.DatePeriod(days = ago)).toString()
            board.insertAscent(uuid = "a$ago", climbUuid = "c$ago", angle = 40L, isMirror = false, attemptId = 0L, bidCount = 1L,
                quality = 3L, difficulty = 22L, isBenchmark = false, comment = null, climbedAt = "${d}T18:00:00", synced = false,
                gymUuid = null, wallUuid = null, productLayoutUuid = null, climbName = "c$ago", difficultyAverage = 22.0,
                climbFrames = "p100r12", framesCount = 1L, boardBrand = "kilter", layoutId = 1L, externalId = null)
        }
        service.invalidateLogbook()
        repo.updateProfile { it.copy(coach = it.coach.copy(currentGrade = "6c")) }
        assertEquals("7a", service.gradeOffer())
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.gradeOffer == "7a" }
        render { com.cruxcoach.android.ui.training.today.TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        scrollTo("today_list", "today_grade_offer")
        click("today_grade_offer_yes")
        until { repo.profile().coach.currentGrade == "7a" && vm.state.value.gradeOffer == null }
        // "Rückgängig": the grade before is back, and so is the question.
        val undo = context.getString(com.cruxcoach.android.R.string.trpg_undo)
        clickSnackbarAction(undo)
        until { repo.profile().coach.currentGrade == "6c" && vm.state.value.gradeOffer == "7a" }
        // "Nein": not asked again for 7a.
        vm.answerGradeOffer(false)
        until { repo.setting(com.cruxcoach.android.athlete.AthleteService.GRADE_DECLINED_KEY) == "7a" && vm.state.value.gradeOffer == null }
        assertEquals(null, service.gradeOffer())
        assertEquals("6c", repo.profile().coach.currentGrade)
    }
}
