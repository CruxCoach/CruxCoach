package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.cruxcoach.android.athlete.ComebackService
import com.cruxcoach.android.ui.training.athlete.comeback.ComebackScreen
import com.cruxcoach.android.ui.training.athlete.comeback.ComebackViewModel
import com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel
import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.model.ComebackEntry
import com.cruxcoach.athlete.model.ComebackSessionKind
import com.cruxcoach.athlete.model.ComebackSessionLog
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryFinding
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.PlanSource
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.WarningSign
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner 2026-10-20: "trainingsprogramm zur regeneration von verletzungen … immer mit dem hinweis, dass dies kein
 * ersatz für arztbesuch ist und nur in absprache mit arzt genutzt werden sollte". The questions, the notice, the
 * stage agreed with a doctor, the sessions for the injured hand and the diary – and nothing changes the stage by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ComebackScreenTest : AthleteScreenTest() {

    private fun injury(startedDaysAgo: Int = 6) = Injury("i1", InjuryRegion.FINGER, InjurySide.LEFT, "Ring A2", 3,
        startedOn = service.today().minus(DatePeriod(days = startedDaysAgo)).toString(), updatedAt = 1)

    private fun screen(): ComebackViewModel {
        val vm = loaded(ComebackViewModel(service, SavedStateHandle(mapOf(ComebackViewModel.ARG_INJURY to "i1"))))
        render { ComebackScreen(onBack = {}, onOpenPlayer = {}, viewModel = vm) }
        return vm
    }

    @Test
    fun `questions, warning signs, the notice and the start of a plan agreed with a doctor`() {
        repo.saveInjury(injury())
        screen()
        // The notice heads the screen before anything else.
        waitForTag("comeback_notice")
        waitForText("Not a substitute for seeing a doctor")

        scrollTo("comeback_list", "comeback_open_questions")
        click("comeback_open_questions")
        scrollTo("comeback_check_list", "comeback_sign_pop_with_swelling")
        click("comeback_sign_pop_with_swelling")
        scrollTo("comeback_check_list", "comeback_checked_imaging")
        click("comeback_checked_imaging")
        scrollTo("comeback_check_list", "comeback_finding_pulley_strain")
        click("comeback_finding_pulley_strain")
        scrollTo("comeback_check_list", "comeback_check_save")
        click("comeback_check_save")
        compose.waitUntil(WAIT_MS) { repo.injury("i1")?.check?.finding == InjuryFinding.PULLEY_STRAIN }
        assertEquals(setOf(WarningSign.POP_WITH_SWELLING), repo.injury("i1")!!.check!!.signs)
        // A pop with swelling: best checked within days – the alert says so and never blocks.
        waitForTag("comeback_signs_alert_days")
        click("comeback_signs_checked")
        compose.waitUntil(WAIT_MS) { repo.injury("i1")?.check?.signsAcknowledgedOn != null }
        waitForTag("comeback_signs_banner")

        scrollTo("comeback_list", "comeback_stage_1")
        click("comeback_stage_1")
        scrollTo("comeback_list", "comeback_ref_half")
        compose.onNodeWithTag("comeback_ref_half").performTextInput("30")
        scrollTo("comeback_list", "comeback_agreed")
        click("comeback_agreed")
        scrollTo("comeback_list", "comeback_start")
        click("comeback_start")
        // "Bevor du startest": confirmed with "in agreement with my doctor or physio".
        waitForTag("comeback_before_dialog")
        waitForText("not a substitute for seeing a doctor")
        click("comeback_understood")
        compose.waitUntil(WAIT_MS) { repo.injury("i1")?.activePlan != null }
        val plan = repo.injury("i1")!!.activePlan!!
        assertEquals(1, plan.stage)
        assertTrue(plan.agreedWithProfessional)
        assertEquals(30.0, plan.referenceKg[Grip.HALF_CRIMP.name])
        assertEquals(service.today().toString(), plan.acknowledgedOn)
        // Stage 1 has no climbing: climbing pauses on the injury, so the coach plans around it.
        assertTrue(repo.injury("i1")!!.climbingPaused)
        scrollTo("comeback_list", "comeback_stage_card")
        waitForText("Gentle loading")
        // The notice stays at the top of the running plan.
        scrollTo("comeback_list", "comeback_notice")
    }

    @Test
    fun `a session trains the injured hand with the plan's load and its pain goes into the diary`() {
        repo.saveInjury(Comeback.start(injury(), PlanSource.CRUXCOACH, 1, service.today(), true, false, emptyMap(), 1))
        screen()
        scrollTo("comeback_list", "comeback_session_start_0")
        click("comeback_session_start_0")
        compose.waitUntil(WAIT_MS) { repo.openWorkout() != null }
        val workout = repo.openWorkout()!!
        assertEquals(Comeback.routineId("i1", 1, 0), workout.routineId)
        val sets = repo.setsFor(workout.id)
        val holds = sets.filter { it.exerciseSlug == "finger.open_hand_block_lift" }
        // The injured hand on purpose, five holds at the careful start load, no warm-up ramp in front.
        assertEquals(5, holds.size)
        assertTrue(holds.all { it.side == Side.LEFT && it.setType == SetType.WORK })
        assertTrue(holds.all { it.loadKg == Comeback.START_KG || it.targetLoadKg == Comeback.START_KG })
        assertTrue(sets.none { it.setType == SetType.WARMUP && it.exerciseSlug.startsWith("finger.") })

        // Finishing asks for the highest pain; it lands in today's diary.
        holds.forEach { repo.saveSet(it.copy(durationS = 30.0, loadKg = 6.0, completedAt = System.currentTimeMillis())) }
        val player = WorkoutPlayerViewModel(service).tracked()
        compose.waitUntil(WAIT_MS) { player.state.value.workout?.id == workout.id }
        player.finish(null, null, comebackPain = 1)
        compose.waitUntil(WAIT_MS) { repo.comebackEntry("i1", service.today().toString())?.sessions?.isNotEmpty() == true }
        val log = repo.comebackEntry("i1", service.today().toString())!!.sessions.single()
        assertEquals(ComebackSessionKind.TENDON, log.kind)
        assertEquals(1, log.painMax)
        // The next session starts from the load the athlete used.
        assertEquals(6.0, ComebackService(service).lastLoad("i1", "finger.open_hand_block_lift", Grip.OPEN_HAND, Side.LEFT))
    }

    @Test
    fun `a week above the rule proposes a step back, and only the tap changes the stage`() {
        val start = service.today().minus(DatePeriod(days = 9))
        repo.saveInjury(Comeback.start(injury(20), PlanSource.CRUXCOACH, 2, start, true, false, emptyMap(), 1))
        (0..6).forEach { d ->
            val day = service.today().minus(DatePeriod(days = d)).toString()
            repo.saveComebackEntry(ComebackEntry("i1", day, morningPain = 1,
                sessions = listOf(ComebackSessionLog(ComebackSessionKind.TENDON, if (d == 1 || d == 3) 5 else 1, at = 1)), updatedAt = 1))
        }
        screen()
        scrollTo("comeback_list", "comeback_prompt_review")
        waitForText("Your pain has gone up")
        assertEquals(2, repo.injury("i1")!!.plan!!.stage)
        click("comeback_prompt_review_primary")
        compose.waitUntil(WAIT_MS) { repo.injury("i1")?.plan?.stage == 1 }
        // The prompt was answered; climbing pauses again with the stage.
        assertTrue(repo.injury("i1")!!.climbingPaused)
    }

    @Test
    fun `the diary saves today's values and the summary for the appointment carries them`() {
        repo.saveInjury(Comeback.start(injury(), PlanSource.CRUXCOACH, 1, service.today(), false, false, emptyMap(), 1))
        val vm = screen()
        // Not agreed yet: the plan asks to talk the stage through.
        scrollTo("comeback_list", "comeback_not_agreed")
        scrollTo("comeback_list", "comeback_morning_2")
        click("comeback_morning_2")
        scrollTo("comeback_list", "comeback_pressure_3")
        click("comeback_pressure_3")
        compose.waitUntil(WAIT_MS) { repo.comebackEntry("i1", service.today().toString())?.pressurePain == 3 }
        assertEquals(2, repo.comebackEntry("i1", service.today().toString())!!.morningPain)
        val text = ComebackService(service).summary("i1", "en", ComebackService.SummaryLabels("Summary", "Injury", "Finger", "Left",
            "Since", "Told", "Signs", "Plan", "Stage", "Reference", "Day", "yes"))
        assertTrue(text.contains("Not a substitute for seeing a doctor"))
        assertTrue(text.lines().any { it.startsWith(service.today().toString()) && it.contains("2\t3") })
        assertNotNull(vm.state.value.todayEntry)
    }

    @Test
    fun `a reference value can be entered later and the strength targets follow it`() {
        repo.saveInjury(Comeback.start(injury(), PlanSource.CRUXCOACH, 3, service.today(), true, false, emptyMap(), 1))
        screen()
        scrollTo("comeback_list", "comeback_reference")
        waitForText("None yet")
        click("comeback_reference_edit")
        waitForTag("comeback_reference_dialog")
        compose.onNodeWithTag("comeback_ref_half").performTextInput("28")
        click("comeback_reference_save")
        compose.waitUntil(WAIT_MS) { repo.injury("i1")?.plan?.referenceKg?.get(Grip.HALF_CRIMP.name) == 28.0 }
        // Stage 3 strength: half crimp at 40–60 % of 28 kg.
        scrollTo("comeback_list", "comeback_session_1")
        waitForText("11.0–17.0 kg")
    }
}
