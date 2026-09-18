package com.cruxcoach.android.ui.onboarding

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserTourTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    @Before fun clean() { context.getSharedPreferences("browser_tour_v1", 0).edit().clear().commit() }
    @Test fun `existing installations do not automatically start a tour`() {
        assertEquals(TourStep.INACTIVE, BrowserTour(context).step())
    }
    @Test fun `deferred Bluetooth is skipped on handoff and dismissal survives recreation`() {
        BrowserTour(context).apply { deferBle(); start() }
        assertEquals(TourStep.ANGLE, BrowserTour(context).step())
        BrowserTour(context).move(TourStep.DONE)
        assertEquals(TourStep.DONE, BrowserTour(context).step())
        BrowserTour(context).start(replay = true)
        assertEquals(TourStep.CONNECT, BrowserTour(context).step())
    }
    @Test fun `successful quicklog persists its exact entry and replay clears it`() {
        val tour = BrowserTour(context)
        tour.move(TourStep.LOG)
        assertNull(tour.loggedEntry())
        tour.logged("saved-attempt-uuid")
        val restored = BrowserTour(context)
        assertEquals(TourStep.LOGBOOK, restored.step())
        assertEquals("saved-attempt-uuid", restored.loggedEntry())
        restored.start(replay = true)
        assertNull(restored.loggedEntry())
        assertEquals(TourStep.CONNECT, restored.step())
    }
    @Test fun `unknown Aurora names and relays never preselect Kilter`() {
        val unknown = DiscoveredBoard("Unknown Board", "", 2, "test", -45)
        assertNull(onboardingBoardSuggestion(unknown))
        assertNull(onboardingBoardSuggestion(unknown.copy(displayName = "Kilter Board", isCruxRelay = true)))
        assertEquals(BoardBrand.TENSION, onboardingBoardSuggestion(unknown.copy(displayName = "Tension Board 2")))
        assertEquals(BoardBrand.KILTER, onboardingBoardSuggestion(unknown.copy(displayName = "Kilter Board")))
    }
}
