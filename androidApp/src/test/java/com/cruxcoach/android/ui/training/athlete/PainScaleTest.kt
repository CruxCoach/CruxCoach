package com.cruxcoach.android.ui.training.athlete

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.android.ui.training.player.PainSheet
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Pain 0–10 everywhere (maintainer, review of #93): the injury card and the player's pain sheet went up to 9 while the
 * comeback diary goes to 10. Values saved on the old scale stay as they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PainScaleTest : AthleteScreenTest() {

    @Test
    fun `an injury saved at 9 shows 9 of 10 and its slider reaches 10`() {
        repo.saveInjury(Injury("i9", InjuryRegion.FINGER, severity = 9, startedOn = service.today().toString(), updatedAt = 1))
        val vm = loaded(InjuriesViewModel(service))
        render { InjuriesScreen(onBack = {}, onOpenRoutines = {}, viewModel = vm) }
        waitForTag("injury_pain_i9")
        // Kept, not rescaled.
        waitForText("Pain: 9 of 10")
        assertEquals(9, repo.injury("i9")!!.severity)

        compose.onNodeWithTag("injury_pain_i9").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
        compose.waitUntil(WAIT_MS) { repo.injury("i9")!!.severity == 10 }
        waitForText("Pain: 10 of 10")
    }

    @Test
    fun `the player's pain sheet goes to 10`() {
        render { PainSheet("Pull-up", null, onDismiss = {}, onConfirm = { _, _, _, _, _ -> }) }
        waitForTag("player_pain_level")
        compose.onNodeWithTag("player_pain_level").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
        waitForText("Pain: 10 of 10")
    }

    /**
     * A tap snaps the slider to a mark, but the snapped value can come out a hair below it (3.9999…). Cut off with
     * toInt(), the card then said and saved 3 where the thumb sat on 4 (RC1 walk on the test phone, 2026-11-17).
     */
    @Test
    fun `a tap on each mark of the card's slider shows and saves that mark`() {
        repo.saveInjury(Injury("i1", InjuryRegion.FINGER, severity = 0, startedOn = service.today().toString(), updatedAt = 1))
        val vm = loaded(InjuriesViewModel(service))
        render { InjuriesScreen(onBack = {}, onOpenRoutines = {}, viewModel = vm) }
        waitForTag("injury_pain_i1")
        val slider = compose.onNodeWithTag("injury_pain_i1").performScrollTo()
        for (mark in listOf(4, 7, 3, 9, 1, 6, 2, 8, 5)) {
            // The track is inset a little at both ends; the nearest mark wins, so a share of the width is close enough.
            // The ends are covered above; a tap on the very edge does not reach the slider in this test.
            slider.performTouchInput { click(Offset((width - 1) * mark / 10f, centerY)) }
            compose.waitUntil(WAIT_MS) { repo.injury("i1")!!.severity == mark }
            waitForText("Pain: $mark of 10")
        }
    }
}
