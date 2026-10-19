package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.Workout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner 2026-10-19: "erkennt der Vorschlagsengine, wenn ein User immer das gleiche Training macht und eigentlich nur die
 * Progression der einzelnen Übungen haben will?" – the usual workout becomes today's suggestion and can go into the week.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HabitOfferTest : AthleteScreenTest() {

    private fun usualOnThisWeekday(): Routine {
        val routine = Routine("push-day", "Push-Tag", items = listOf(
            RoutineItem("push.push_up", sets = 4, repsMin = 12, repsMax = 12),
            RoutineItem("antagonist.face_pull", sets = 3, repsMin = 15, repsMax = 15),
        ))
        repo.saveRoutine(routine)
        repo.updateProfile { it.copy(fuelIntroAccepted = true, checkinEnabled = false) }
        val today = service.today()
        listOf(7, 14).forEach { ago ->
            val day = today.minus(DatePeriod(days = ago))
            val start = System.currentTimeMillis() - ago * 86_400_000L
            repo.saveWorkout(Workout("w$ago", start, start + 3_600_000L, day.toString(), "Push-Tag", routine.id, updatedAt = start))
        }
        return routine
    }

    @Test
    fun `the usual workout is today's suggestion and goes into the standard week with Undo`() {
        val routine = usualOnThisWeekday()
        val iso = service.today().dayOfWeek.isoDayNumber
        val vm = loaded(TodayViewModel(service))
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        compose.waitUntil(WAIT_MS) { vm.state.value.suggestion != null }
        val s = vm.state.value.suggestion!!
        assertEquals(SuggestionFocus.PLANNED, s.focus)
        assertEquals(SuggestionReason.USUAL_ROUTINE_WEEKDAY, s.reasons.first())
        assertEquals(routine.id, s.plannedEntry)
        scrollTo("today_list", hasTestTag("today_suggestion_title"))
        waitForText("Push-Tag")
        waitForText("As usual on")
        scrollTo("today_list", hasTestTag("today_habit_offer"))
        click("today_habit_offer_yes")
        compose.waitUntil(WAIT_MS) { repo.profile().weekPlan[iso] == routine.id }
        waitForText("In your standard week")
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(WAIT_MS) { repo.profile().weekPlan[iso] == null }
        compose.waitUntil(WAIT_MS) { vm.state.value.suggestion?.reasons?.firstOrNull() == SuggestionReason.USUAL_ROUTINE_WEEKDAY }
        // "Another suggestion" is always possible: the coach builds something else today.
        vm.nextSuggestion()
        compose.waitUntil(WAIT_MS) { vm.state.value.suggestion?.plannedEntry != routine.id }
        assertTrue(vm.state.value.suggestion!!.reasons.none { it == SuggestionReason.USUAL_ROUTINE_WEEKDAY })
    }

    @Test
    fun `no thanks is not asked again for weeks, and the card's start keeps the workout's name`() {
        val routine = usualOnThisWeekday()
        val iso = service.today().dayOfWeek.isoDayNumber
        val vm = loaded(TodayViewModel(service))
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        compose.waitUntil(WAIT_MS) { vm.state.value.habitOffer != null }
        scrollTo("today_list", hasTestTag("today_habit_offer"))
        click("today_habit_offer_no")
        compose.waitUntil(WAIT_MS) { repo.setting(PreferenceLearning.habitOfferKey(iso, routine.id)) != null }
        compose.waitUntil(WAIT_MS) { vm.state.value.habitOffer == null }
        assertTrue(repo.profile().weekPlan.isEmpty())
        // Started from the card, the training still says which workout it was, so the habit holds.
        vm.startSuggestion("Push-Tag")
        compose.waitUntil(WAIT_MS) { repo.openWorkout() != null }
        val open = repo.openWorkout()!!
        assertEquals("suggestion:${service.today()}@${routine.id}", open.routineId)
    }
}
