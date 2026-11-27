package com.cruxcoach.android.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main menu's gestures were off for the whole tour. The logo opens the menu whenever no
 * spotlight covers it (back on the list at a climb-page step, or with a tour left unfinished), and
 * that menu did not close with a tap beside it or a swipe.
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
