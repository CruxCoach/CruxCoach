package com.cruxcoach.android.ui.training.athlete

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.athlete.model.ComebackEntry
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A past injury is the athlete's own health data: it can be opened again when
 * it comes back, and deleted with its comeback diary – with Undo (device test
 * 2026-10-25: a past injury had no action at all).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PastInjuriesTest : AthleteScreenTest() {

    private fun pastInjury() {
        repo.saveInjury(Injury("old", InjuryRegion.FINGER, InjurySide.LEFT, "ring finger A2", 3, startedOn = "2026-08-01",
            resolvedOn = "2026-09-15", updatedAt = 1))
        repo.saveComebackEntry(ComebackEntry("old", "2026-08-20", morningPain = 2, updatedAt = 1))
    }

    private fun show(): InjuriesViewModel {
        val vm = loaded(InjuriesViewModel(service))
        render { InjuriesScreen(onBack = {}, onOpenRoutines = {}, viewModel = vm) }
        waitForTag("injury_past_old")
        return vm
    }

    @Test
    fun `a past injury opens again with its diary`() {
        pastInjury()
        show()

        compose.onNodeWithTag("injury_reopen_old", useUnmergedTree = true).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

        compose.waitUntil(WAIT_MS) { repo.injury("old")?.isActive == true }
        waitForTag("injury_old")
        assertNull(repo.injury("old")!!.resolvedOn)
        assertEquals(1, repo.comebackEntries("old").size)
    }

    @Test
    fun `deleting a past injury takes its diary along, and Undo brings both back`() {
        pastInjury()
        show()

        compose.onNodeWithTag("injury_delete_old", useUnmergedTree = true).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { repo.injury("old") == null }
        assertEquals(0, repo.comebackEntries("old").size)

        waitForText("Undo")
        clickText("Undo")
        compose.waitUntil(WAIT_MS) { repo.injury("old") != null }
        assertEquals("2026-09-15", repo.injury("old")!!.resolvedOn)
        compose.waitUntil(WAIT_MS) { repo.comebackEntries("old").size == 1 }
    }
}
