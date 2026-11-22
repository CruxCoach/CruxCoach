package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.test.onAllNodesWithTag
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.athlete.logic.BlockPhase
import com.cruxcoach.athlete.logic.LoadStatus
import com.cruxcoach.athlete.logic.LoadStructure
import com.cruxcoach.athlete.logic.LoadTrend
import com.cruxcoach.athlete.logic.StructureLoad
import com.cruxcoach.athlete.logic.TrainingBlocks
import com.cruxcoach.athlete.logic.WeeklyVolume
import com.cruxcoach.athlete.model.Checkin
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lighter week is the athlete's decision (owner 2026-11-22): a rising load no longer starts it, the load card
 * offers it – read once a week, with a way back, and not while the week already is a lighter one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LighterWeekOfferTest : AthleteScreenTest() {

    /** Waits with the main looper running: the ViewModel's writes come back on Main. */
    private fun until(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }


    @Test
    fun `a rising finger load no longer starts the lighter week by itself`() {
        val today = service.today()
        // The third week of the block is its second build week: from here tired days can bring the lighter week forward.
        val start = today.minus(DatePeriod(days = 18))
        repo.updateProfile { it.copy(coach = it.coach.copy(blockStartDay = start.toString())) }
        val spike = LoadStatus(mapOf(LoadStructure.FINGER to StructureLoad(30.0, 15.0, 2.0, LoadTrend.SPIKE)), emptyMap())
        val coach = service.todayCoach()
        assertEquals(TrainingBlocks.state(start, null, today), coach.blockState(repo.profile(), spike, today))
        assertEquals(BlockPhase.BUILD, coach.blockState(repo.profile(), spike, today)!!.phase)
        // Two tired days and the spike were four signals before, enough for the lighter week: now they are two.
        fun tired(daysAgo: Int) = repo.saveCheckin(Checkin(today.minus(DatePeriod(days = daysAgo)).toString(), 1, fingers = 2))
        tired(0); tired(1)
        assertEquals(BlockPhase.BUILD, coach.blockState(repo.profile(), spike, today)!!.phase)
        // Three tired days still bring it forward, with or without a spike.
        tired(2)
        assertEquals(BlockPhase.DELOAD, coach.blockState(repo.profile(), spike, today)!!.phase)
        assertEquals(BlockPhase.DELOAD, coach.blockState(repo.profile(), null, today)!!.phase)
    }

    /** Three climbing days in the last week after three weeks without: a load jump. */
    private fun loadJump(today: LocalDate) = (1..3).forEach { n ->
        val day = today.minus(DatePeriod(days = n)).toString()
        service.saveClimbingDay(ClimbingDayEntry("c-$day", day, ClimbingDayKind.GYM_BOULDER, 90, ClimbIntensity.VOLUME))
    }

    @Test
    fun `the load card is read for the week and the lighter week it offers can be undone`() {
        val today = service.today()
        val week = WeeklyVolume.weekStartOf(today)
        loadJump(today)
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.loadSpikes.isNotEmpty() }
        // "Verstanden": quiet for the rest of the week.
        vm.dismissLoadSpike()
        until { vm.state.value.loadSpikes.isEmpty() }
        assertEquals(week.toString(), repo.setting(TodayViewModel.LOAD_SPIKE_SEEN))
        // A new week tells again: the mark is last week's.
        repo.putSetting(TodayViewModel.LOAD_SPIKE_SEEN, week.minus(DatePeriod(days = 7)).toString())
        val next = loaded(TodayViewModel(service))
        until { next.state.value.loadSpikes.isNotEmpty() && next.state.value.block != null }
        // "Leichte Woche starten": the block is in its lighter week and the card is read …
        val before = repo.profile().coach.blockStartDay
        next.startLighterWeek()
        until { next.state.value.lighterWeekUndo != null && next.state.value.block?.phase == BlockPhase.DELOAD && next.state.value.loadSpikes.isEmpty() }
        assertEquals(before.orEmpty(), next.state.value.lighterWeekUndo)
        assertEquals(week.toString(), repo.setting(TodayViewModel.LOAD_SPIKE_SEEN))
        // … and "Rückgängig" brings the block and the card back.
        next.undoLighterWeek(next.state.value.lighterWeekUndo!!)
        until { repo.profile().coach.blockStartDay == before && next.state.value.block?.phase != BlockPhase.DELOAD && next.state.value.loadSpikes.isNotEmpty() }
    }

    @Test
    fun `the load card offers the lighter week only outside one`() {
        val today = service.today()
        loadJump(today)
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.loadSpikes.isNotEmpty() && vm.state.value.block != null }
        assertTrue(vm.state.value.block!!.phase != BlockPhase.DELOAD, "${vm.state.value.block}")
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        scrollTo("today_list", "today_load_spike")
        waitForTag("today_load_spike_lighter")
        waitForTag("today_load_spike_seen")
        // In the lighter week itself the card informs and can be read; a second start would begin its seven days again.
        repo.updateProfile { it.copy(coach = it.coach.copy(blockStartDay = today.minus(DatePeriod(days = 28)).toString())) }
        until { vm.state.value.block?.phase == BlockPhase.DELOAD }
        scrollTo("today_list", "today_load_spike")
        waitForTag("today_load_spike_seen")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("today_load_spike_lighter", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
}
