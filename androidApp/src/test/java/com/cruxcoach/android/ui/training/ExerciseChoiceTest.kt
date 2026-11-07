package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import com.cruxcoach.android.ui.training.athlete.AthleteSettingsScreen
import com.cruxcoach.android.ui.training.athlete.AthleteSettingsViewModel
import com.cruxcoach.android.ui.training.athlete.SettingsSection
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.Familiarity
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.ExerciseChoice
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.SetupState
import com.cruxcoach.athlete.model.SuggestionEventKind
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.plus

/**
 * Well-known exercises first (issue #68): an exercise new to the athlete is marked on Today's card with the way to
 * its steps, "Don't know it" swaps it for a known one without counting against it, and "Exercise choice" is saved
 * from the training settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExerciseChoiceTest : AthleteScreenTest() {

    @Before
    fun athlete() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.updateProfile { it.copy(equipmentConfigured = true, equipment = setOf(EquipmentV2.NONE, EquipmentV2.MAT,
            EquipmentV2.PULL_UP_BAR, EquipmentV2.DUMBBELL, EquipmentV2.BANDS),
            coach = it.coach.copy(experience = ExperienceBand.Y3_5, setupState = SetupState.DONE)) }
    }

    private fun until(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    @Test
    fun `an exercise new to the athlete is marked, and don't know it swaps it for a known one`() {
        val vm = loaded(TodayViewModel(service))
        vm.saveQuickCheckin(null, emptySet())
        until { vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        fun items() = vm.state.value.suggestion!!.routine.items
        val main = items().filter { !it.warmup }
        // Nothing is known yet: at most one new exercise, and it is marked on the card.
        val new = main.filter { it.slug in vm.state.value.newToYou }
        assertTrue(new.size <= 1, "${new.map { it.slug }}")
        assertTrue(main.any { service.catalog.fallbackFor(it.slug).familiarity == Familiarity.BASIC }, "${main.map { it.slug }}")
        // All Basic today: one swapped in by hand is just as new to the athlete.
        val slug = new.singleOrNull()?.slug ?: run {
            val first = main.first()
            val alt = vm.alternatives(first.slug).first { it.def.familiarity != Familiarity.BASIC }.def.slug
            vm.swapExercise(first.slug, alt)
            until { alt in vm.state.value.newToYou }
            alt
        }
        val to = assertNotNull(vm.familiarSwap(slug))
        assertTrue(to.familiarity == Familiarity.BASIC, to.slug)
        val count = items().size
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        scrollTo("today_list", hasTestTag("today_item_$slug"))
        waitForTag("today_new_$slug")
        click("today_item_$slug")
        waitForTag("today_swap_steps")
        click("today_swap_dont_know")
        until { items().any { it.slug == to.slug } && items().none { it.slug == slug } }
        // The slot stays: one exercise for another, never a gap (also when the replacement is the coach's own pick).
        assertEquals(count, items().size)
        // Recorded as "don't know it", not as a swap: nothing counts against the exercise or for its replacement.
        val day = service.today().toString()
        val events = repo.suggestionEventsForDay(day)
        assertTrue(events.any { it.kind == SuggestionEventKind.FEEDBACK && it.focus == PreferenceLearning.DONT_KNOW && it.slugs == listOf(slug, to.slug) })
        assertTrue(events.none { it.kind == SuggestionEventKind.SWAPPED && it.slugs.firstOrNull() == slug })
        val affinity = PreferenceLearning.affinity(events, emptyList(), service.today())
        assertNull(affinity[slug])
        assertNull(affinity[to.slug])
        // It holds for the day, also after the app was closed.
        val again = loaded(TodayViewModel(service))
        until { again.state.value.suggestion?.routine?.items?.any { it.slug == to.slug } == true }
        assertTrue(again.state.value.suggestion!!.routine.items.none { it.slug == slug })
    }

    @Test
    fun `don't know it after a hand swap asks nothing afterwards and takes the swap back`() {
        val vm = loaded(TodayViewModel(service))
        vm.saveQuickCheckin(null, emptySet())
        until { vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        fun items() = vm.state.value.suggestion!!.routine.items
        // A Basic pick swapped by hand for one the athlete has never done …
        val first = items().first { !it.warmup && service.catalog.fallbackFor(it.slug).familiarity == Familiarity.BASIC }
        val x = vm.alternatives(first.slug).first { it.def.familiarity != Familiarity.BASIC }.def.slug
        vm.swapExercise(first.slug, x)
        until { x in vm.state.value.newToYou }
        // … then "Kenne ich nicht" on it.
        val to = assertNotNull(vm.familiarSwap(x))
        vm.dontKnowExercise(x, to.slug)
        until { items().any { it.slug == to.slug } && items().none { it.slug == x } }
        // The training from the card asks no "from now on like this?" for that slot: the athlete never trained the swap.
        vm.startSuggestion("Training")
        until { repo.openWorkout() != null }
        val workout = repo.openWorkout()!!
        assertTrue(service.keepQuestion(workout).none { it.replaced == first.slug || it.chosen == x }, "${service.keepQuestion(workout)}")
        // The swap teaches nothing: no point for the unknown pick, none against the swapped exercise, and it does not rest.
        val today = service.today()
        val events = repo.suggestionEventsForDay(today.toString())
        val affinity = PreferenceLearning.affinity(events.filter { it.kind != SuggestionEventKind.STARTED }, emptyList(), today)
        assertNull(affinity[x])
        assertNull(affinity[first.slug])
        assertTrue(first.slug !in PreferenceLearning.resting(events, today.plus(kotlinx.datetime.DatePeriod(days = 1))))
    }

    @Test
    fun `exercise choice is chosen in the training settings and changes the suggestion`() {
        val vm = loaded(AthleteSettingsViewModel(service))
        render { AthleteSettingsScreen({}, viewModel = vm, section = SettingsSection.PROFILE) }
        waitForTag("exercise_choice_balanced")
        click("exercise_choice_open")
        until { repo.profile().coach.exerciseChoice == ExerciseChoice.OPEN }
        click("exercise_choice_classics")
        until { repo.profile().coach.exerciseChoice == ExerciseChoice.CLASSICS }
        // "Mostly classics": nothing Special the athlete does not know.
        val today = loaded(TodayViewModel(service))
        today.saveQuickCheckin(null, emptySet())
        until { today.state.value.suggestion != null && !today.state.value.asksBeforeSuggestion }
        val items = today.state.value.suggestion!!.routine.items.filter { !it.warmup }
        assertEquals(emptyList(), items.filter { service.catalog.fallbackFor(it.slug).familiarity == Familiarity.SPECIAL }.map { it.slug })
        assertTrue(today.state.value.newToYou.size <= 1, "${today.state.value.newToYou}")
    }
}
