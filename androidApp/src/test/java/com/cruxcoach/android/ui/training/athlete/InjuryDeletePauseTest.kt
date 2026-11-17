package com.cruxcoach.android.ui.training.athlete

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.PauseReason
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reporting an injury opens an injury pause. Deleting the injury left that pause running, so the
 * streak stayed paused with no injury open (RC1 walk on the test phone, 2026-11-17). Deleting now
 * ends it as healing does, and Undo brings it back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InjuryDeletePauseTest : AthleteScreenTest() {

    private fun injury(id: String, startedOn: String) =
        repo.saveInjury(Injury(id, InjuryRegion.ELBOW, severity = 4, startedOn = startedOn, updatedAt = 1))

    private fun show() {
        val vm = loaded(InjuriesViewModel(service))
        render { InjuriesScreen(onBack = {}, onOpenRoutines = {}, viewModel = vm) }
    }

    private fun deleteAndWait(id: String) {
        waitForTag("injury_delete_$id")
        compose.onNodeWithTag("injury_delete_$id", useUnmergedTree = true).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { repo.injury(id) == null }
    }

    @Test
    fun `deleting an injury entered today removes its pause of today, and Undo brings both back`() {
        val today = service.today().toString()
        injury("i1", today)
        repo.savePause(PausePeriod("p1", PauseReason.INJURY, today))

        show()
        deleteAndWait("i1")
        compose.waitUntil(WAIT_MS) { repo.pauses().isEmpty() }
        assertNull(repo.openPause())

        waitForText("Undo")
        clickText("Undo")
        compose.waitUntil(WAIT_MS) { repo.injury("i1") != null && repo.openPause() != null }
        assertEquals(PausePeriod("p1", PauseReason.INJURY, today), repo.openPause())
    }

    @Test
    fun `deleting an older injury ends its pause today and keeps the paused days`() {
        val today = service.today()
        val started = today.minus(10, DateTimeUnit.DAY).toString()
        injury("i1", started)
        repo.savePause(PausePeriod("p1", PauseReason.INJURY, started))

        show()
        deleteAndWait("i1")
        compose.waitUntil(WAIT_MS) { repo.openPause() == null }
        assertEquals(listOf(PausePeriod("p1", PauseReason.INJURY, started, endDay = today.toString())), repo.pauses())
    }

    @Test
    fun `the pause stays while another injury is open, and a holiday is never touched`() {
        val today = service.today().toString()
        injury("i1", today)
        injury("i2", today)
        repo.savePause(PausePeriod("p1", PauseReason.INJURY, today))

        show()
        deleteAndWait("i1")
        assertEquals(PausePeriod("p1", PauseReason.INJURY, today), repo.openPause())

        repo.deletePause("p1")
        repo.savePause(PausePeriod("h1", PauseReason.HOLIDAY, today))
        deleteAndWait("i2")
        assertEquals(PausePeriod("h1", PauseReason.HOLIDAY, today), repo.openPause())
    }
}
