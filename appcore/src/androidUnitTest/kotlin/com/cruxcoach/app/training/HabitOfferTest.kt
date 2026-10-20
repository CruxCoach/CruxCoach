package com.cruxcoach.app.training

import com.cruxcoach.app.ui.TodayScreenModel
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.Workout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of Android's HabitOfferTest (105f0aa99) against the iOS Today presenter and its facade. Owner 2026-10-19:
 * "erkennt der Vorschlagsengine, wenn ein User immer das gleiche Training macht und eigentlich nur die Progression
 * der einzelnen Übungen haben will?" – the usual workout becomes today's suggestion and can go into the week.
 */
class HabitOfferTest {
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

    private val dayMs = 86_400_000L

    /** "Push-Tag" done on this weekday one and two weeks ago (Android `usualOnThisWeekday`). */
    private fun usualOnThisWeekday(): Routine = kit.db {
        val routine = Routine("push-day", "Push-Tag", items = listOf(
            RoutineItem("push.push_up", sets = 4, repsMin = 12, repsMax = 12),
            RoutineItem("antagonist.face_pull", sets = 3, repsMin = 15, repsMax = 15),
        ))
        repo.saveRoutine(routine)
        repo.updateProfile { it.copy(fuelIntroAccepted = true, checkinEnabled = false) }
        val today = service.today()
        listOf(7, 14).forEach { ago ->
            val day = today.minus(DatePeriod(days = ago))
            val start = System.currentTimeMillis() - ago * dayMs
            repo.saveWorkout(Workout("w$ago", start, start + 3_600_000L, day.toString(), "Push-Tag", routine.id, updatedAt = start))
        }
        routine
    }

    private fun iso() = kit.db { service.today().dayOfWeek.isoDayNumber }

    private fun today() = TodayPresenter(service, kit.scope(), kit.dispatcher)

    private fun TodayPresenter.reasonFirst() = state.value.suggestion?.reasons?.firstOrNull()

    @Test
    fun `the usual workout is today's suggestion and goes into the standard week with Undo`() {
        val routine = usualOnThisWeekday()
        val iso = iso()
        val vm = today()
        val model = TodayScreenModel(vm, "en")
        kit.until("suggestion") { vm.state.value.suggestion != null }
        val s = vm.state.value.suggestion!!
        assertEquals(SuggestionFocus.PLANNED, s.focus)
        assertEquals(SuggestionReason.USUAL_ROUTINE_WEEKDAY, s.reasons.first())
        assertEquals(routine.id, s.plannedEntry)
        kit.until("offer") { vm.state.value.habitOffer != null }
        assertEquals(HabitOffer(iso, routine.id), vm.state.value.habitOffer)

        // The card: its own name, "As usual on <weekday>: …" and the question, every text a real string.
        val ui = model.currentState
        assertEquals("Push-Tag", ui.suggestionTitle.args.single().text)
        assertEquals("trhb_reason_weekday", ui.firstReason.key)
        assertEquals("weekday", ui.firstReason.args[0].kind)
        assertEquals(iso.toDouble(), ui.firstReason.args[0].number)
        assertEquals("Push-Tag", ui.firstReason.args[1].nested!!.args.single().text)
        assertEquals("trhb_offer", ui.habitOffer.key)
        assertEquals(iso.toDouble(), ui.habitOffer.args[0].number)
        assertTrue(TrTextCheck.checkAll(ui) > 20)
        assertEquals(0, model.mapFailures)

        model.acceptHabitOffer()
        kit.until("in the standard week") { repo.profile().weekPlan[iso] == routine.id }
        kit.until("taken") { model.currentState.hasHabitTaken }
        val taken = model.currentState
        assertEquals("trhb_taken", taken.habitTakenText.key)
        TrTextCheck.check(taken.habitTakenText, "habitTakenText")
        // The week plans the day now: no question (a refresh still running from the start may show it until the profile's own).
        kit.until("no offer with the day planned") { model.currentState.habitOffer.isEmpty }
        val undo = assertNotNull(model.takeHabitTakenUndo())
        // Taken once: the line is not offered twice.
        assertNull(model.takeHabitTakenUndo())
        assertTrue(!model.currentState.hasHabitTaken)
        // In the week now, it is today's planned workout.
        kit.until("week plan") { vm.reasonFirst() == SuggestionReason.WEEK_PLAN }

        undo.restore()
        kit.until("undone") { repo.profile().weekPlan[iso] == null }
        kit.until("usual again") { vm.reasonFirst() == SuggestionReason.USUAL_ROUTINE_WEEKDAY }

        // "Another suggestion" is always possible: the coach builds something else today.
        vm.nextSuggestion()
        kit.until("something else") { vm.state.value.suggestion.let { it != null && it.plannedEntry != routine.id } }
        assertTrue(vm.state.value.suggestion!!.reasons.none { it == SuggestionReason.USUAL_ROUTINE_WEEKDAY })
        assertNull(vm.state.value.habitOffer)
        // ... for the rest of the day, whatever else changes.
        kit.db { repo.updateProfile { it.copy(sessionMinutes = it.sessionMinutes + 5) } }
        kit.until("refreshed") { vm.state.value.profile.sessionMinutes == kit.db { repo.profile().sessionMinutes } }
        assertNotEquals(routine.id, vm.state.value.suggestion?.plannedEntry)
        // Started, it is a plain suggestion: no workout's name in its id.
        vm.startSuggestion("Other")
        kit.until("started") { repo.openWorkout() != null }
        assertEquals("suggestion:${kit.db { service.today() }}", kit.db { repo.openWorkout()!!.routineId })
    }

    @Test
    fun `no thanks is not asked again for weeks and the card's start keeps the workout's name`() {
        val routine = usualOnThisWeekday()
        val iso = iso()
        val vm = today()
        kit.until("offer") { vm.state.value.habitOffer != null }
        TodayScreenModel(vm, "en").declineHabitOffer()
        kit.until("declined") { repo.setting(PreferenceLearning.habitOfferKey(iso, routine.id)) != null }
        kit.until("offer gone") { vm.state.value.habitOffer == null }
        assertTrue(kit.db { repo.profile().weekPlan }.isEmpty())
        // Not asked again, also after a restart; the usual workout stays today's suggestion.
        val again = today()
        kit.until("restarted") { again.state.value.suggestion != null }
        assertEquals(SuggestionReason.USUAL_ROUTINE_WEEKDAY, again.reasonFirst())
        assertNull(again.state.value.habitOffer)
        again.close()
        // Started from the card, the training still says which workout it was, so the habit holds.
        vm.startSuggestion("Push-Tag")
        kit.until("started") { repo.openWorkout() != null }
        val open = kit.db { repo.openWorkout()!! }
        assertEquals("suggestion:${kit.db { service.today() }}@${routine.id}", open.routineId)
        assertEquals(routine.id, PreferenceLearning.routineOf(open) { it == routine.id })
    }

    @Test
    fun `a no thanks older than six weeks asks again`() {
        val routine = usualOnThisWeekday()
        val iso = iso()
        kit.db {
            repo.putSetting(PreferenceLearning.habitOfferKey(iso, routine.id),
                (System.currentTimeMillis() - (PreferenceLearning.WINDOW_DAYS + 1) * dayMs).toString())
        }
        val vm = today()
        kit.until("offer") { vm.state.value.habitOffer == HabitOffer(iso, routine.id) }
    }

    @Test
    fun `no offer when the standard week plans the day or the date has its own plan`() {
        val routine = usualOnThisWeekday()
        val iso = iso()
        // The standard week has a rest day there: the plan wins, nothing to offer.
        kit.db { repo.updateProfile { it.copy(weekPlan = mapOf(iso to com.cruxcoach.athlete.model.PLAN_REST)) } }
        val vm = today()
        kit.until("rest day") { vm.state.value.suggestion?.plannedEntry == com.cruxcoach.athlete.model.PLAN_REST }
        assertNull(vm.state.value.habitOffer)
        assertTrue(vm.state.value.suggestion!!.reasons.none { it == SuggestionReason.USUAL_ROUTINE_WEEKDAY })
        // "Rest day today" on a free standard week: the date's own plan, no question either.
        kit.db { repo.updateProfile { it.copy(weekPlan = emptyMap()) } }
        kit.until("usual") { vm.reasonFirst() == SuggestionReason.USUAL_ROUTINE_WEEKDAY && vm.state.value.habitOffer != null }
        vm.restToday()
        kit.until("rest today") { vm.state.value.suggestion?.plannedEntry == com.cruxcoach.athlete.model.PLAN_REST }
        assertNull(vm.state.value.habitOffer)
        assertNotEquals(routine.id, vm.state.value.suggestion?.plannedEntry)
    }

    @Test
    fun `the coach decides leaves the usual workout out today`() {
        val routine = usualOnThisWeekday()
        val vm = today()
        kit.until("usual") { vm.reasonFirst() == SuggestionReason.USUAL_ROUTINE_WEEKDAY }
        vm.chooseFocus(null)
        kit.until("coach's choice") { vm.state.value.suggestion.let { it != null && it.plannedEntry != routine.id } }
        assertTrue(vm.state.value.suggestion!!.reasons.none { it == SuggestionReason.USUAL_ROUTINE_WEEKDAY || it == SuggestionReason.USUAL_ROUTINE })
        assertNull(vm.state.value.habitOffer)
    }
}
