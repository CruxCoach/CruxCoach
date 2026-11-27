package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import com.cruxcoach.android.ui.training.player.PlayerPhase
import com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen
import com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel
import com.cruxcoach.athlete.logic.NextTargets
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Device walk 2026-11-22, second look: the reserve of a long hold is asked in steps of five seconds (0, 5, 10, 15+),
 * but the row of the done set said "2 s in reserve" after "10", and its correction offered 0, 1, 2, 3+.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LongHoldReserveTest : AthleteScreenTest() {

    @Before
    fun bodyWeight() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
    }

    private fun player(routine: Routine): Pair<String, WorkoutPlayerViewModel> {
        val id = service.startWorkout(routine, null)
        val vm = WorkoutPlayerViewModel(service).tracked()
        until { vm.state.value.phase == PlayerPhase.SET }
        render { WorkoutPlayerScreen({}, {}, {}, {}, viewModel = vm, learningBadge = { _, _ -> }) }
        return id to vm
    }

    /** Waits with the main looper running: the player's writes come back on Main. */
    private fun until(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun work(id: String) = repo.setsFor(id).filter { it.setType == SetType.WORK }.sortedBy { it.setIndex }

    /** The seconds the first set was planned for: what decides the scale of its reserve question. */
    private fun plannedSeconds(id: String): Double = work(id).first().let { it.targetDurationS ?: it.durationS ?: 0.0 }

    /** Rows below the fold are not composed in the lazy page: scroll to them first. */
    private fun tap(tag: String) {
        scrollTo("player_set_view", tag)
        click(tag)
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The chip [tag] has the label [text], whether the chip carries the text itself or a child of it does. */
    private fun chipSays(tag: String, text: String): Boolean {
        val self = hasTestTag(tag) and hasText(text)
        val child = hasText(text) and hasAnyAncestor(hasTestTag(tag))
        return compose.onAllNodes(self).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodes(self, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodes(child, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `a long hold keeps its scale on the row and in the correction`() {
        val (id, vm) = player(Routine("r", "Core", items = listOf(RoutineItem("core.front_plank", sets = 2, durationS = 30, restS = 60))))
        click("player_done")
        until { vm.state.value.phase == PlayerPhase.REST && work(id).first().isCompleted }
        assertTrue(plannedSeconds(id) >= NextTargets.LONG_HOLD_S, "planned ${plannedSeconds(id)} s")
        // Asked in steps of five seconds: the third answer is "10".
        tap("player_rir_2")
        until { work(id).first().rir == 2 }
        // The row says what was answered, not the level it is stored as.
        waitForText("10\u00A0s in reserve")
        assertTrue(!shows("2\u00A0s in reserve"))
        // The correction asks on the same scale: its last answer is "15+", not "3+".
        tap("player_row_${work(id).first().id}")
        waitForTag("player_row_editing")
        scrollTo("player_set_view", "player_review_rir_3")
        compose.waitUntil(WAIT_MS) { chipSays("player_review_rir_3", "15+") }
        assertTrue(chipSays("player_review_rir_2", "10") && chipSays("player_review_rir_1", "5"))
        assertTrue(!chipSays("player_review_rir_3", "3+"))
    }

    @Test
    fun `a short hold keeps its seconds`() {
        val (id, vm) = player(Routine("r", "Core", items = listOf(RoutineItem("core.front_plank", sets = 2, durationS = 10, restS = 60))))
        click("player_done")
        until { vm.state.value.phase == PlayerPhase.REST && work(id).first().isCompleted }
        assertTrue(plannedSeconds(id) < NextTargets.LONG_HOLD_S, "planned ${plannedSeconds(id)} s")
        tap("player_rir_2")
        until { work(id).first().rir == 2 }
        waitForText("2\u00A0s in reserve")
        assertTrue(!shows("10\u00A0s in reserve"))
        tap("player_row_${work(id).first().id}")
        waitForTag("player_row_editing")
        scrollTo("player_set_view", "player_review_rir_3")
        compose.waitUntil(WAIT_MS) { chipSays("player_review_rir_3", "3+") }
    }
}
