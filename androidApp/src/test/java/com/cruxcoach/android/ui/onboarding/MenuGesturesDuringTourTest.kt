package com.cruxcoach.android.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main menu's gestures were off for the whole tour, so a menu opened during it (the tour's
 * logbook step asks for exactly that) did not close with a tap beside it or a swipe.
 */
class MenuGesturesDuringTourTest {
    private val outsideTheTour = setOf(TourStep.INACTIVE, TourStep.DONE)

    @Test fun `an open menu closes by gesture at every step of the tour`() {
        TourStep.entries.forEach { step ->
            assertTrue("open menu at $step", menuGesturesEnabled(menuOpen = true, step = step))
        }
    }

    @Test fun `a closed menu opens by swipe only outside the tour`() {
        TourStep.entries.forEach { step ->
            assertEquals("closed menu at $step", step in outsideTheTour, menuGesturesEnabled(menuOpen = false, step = step))
        }
    }
}
