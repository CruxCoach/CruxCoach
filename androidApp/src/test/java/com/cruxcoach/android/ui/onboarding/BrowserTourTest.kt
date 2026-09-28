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
    @Before fun clean() {
        context.getSharedPreferences("browser_tour_v1", 0).edit().clear().commit()
        context.getSharedPreferences("app_cache", 0).edit().clear().commit()
    }
    @Test fun `existing installations do not automatically start a tour`() {
        assertEquals(TourStep.INACTIVE, BrowserTour(context).step())
    }
    @Test fun `only fresh setup starts automatically and repeating setup preserves dismissal`() {
        val tour = BrowserTour(context)
        tour.startForNewUser(alreadyOnboarded = true)
        assertEquals(TourStep.INACTIVE, tour.step())
        tour.startForNewUser(alreadyOnboarded = false)
        // The board picker is now the first stop: it decides the catalogue everything else uses.
        assertEquals(TourStep.BOARD, tour.step())
        assertEquals(TourStep.CONNECT, tour.afterBoardStep())
        tour.move(TourStep.FILTER)
        tour.startForNewUser(alreadyOnboarded = false)
        assertEquals(TourStep.FILTER, tour.step())
        tour.move(TourStep.DONE)
        BrowserTour(context).startForNewUser(alreadyOnboarded = false)
        assertEquals(TourStep.DONE, tour.step())
    }
    @Test fun `installation evidence blocks auto start even after identity scoped setup resets`() {
        context.getSharedPreferences("app_cache", 0).edit().putBoolean("has_user_profile", true).commit()
        val tour = BrowserTour(context)
        tour.startForNewUser(alreadyOnboarded = false)
        assertEquals(TourStep.INACTIVE, tour.step())
        tour.start(replay = true)
        assertEquals(TourStep.BOARD, tour.step())
    }
    @Test fun `deferred Bluetooth is skipped on handoff and dismissal survives recreation`() {
        BrowserTour(context).apply { deferBle(); start() }
        // The board step always runs; only the Bluetooth step is skipped after a setup handoff.
        assertEquals(TourStep.BOARD, BrowserTour(context).step())
        assertEquals(TourStep.ANGLE, BrowserTour(context).afterBoardStep())
        BrowserTour(context).move(TourStep.DONE)
        assertEquals(TourStep.DONE, BrowserTour(context).step())
        BrowserTour(context).start(replay = true)
        assertEquals(TourStep.BOARD, BrowserTour(context).step())
        assertEquals(TourStep.CONNECT, BrowserTour(context).afterBoardStep())
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
        assertEquals(TourStep.BOARD, restored.step())
    }
    @Test fun `unknown Aurora names and relays never preselect Kilter`() {
        val unknown = DiscoveredBoard("Unknown Board", "", 2, "test", -45)
        assertNull(onboardingBoardSuggestion(unknown))
        assertNull(onboardingBoardSuggestion(unknown.copy(displayName = "Kilter Board", isCruxRelay = true)))
        assertEquals(BoardBrand.TENSION, onboardingBoardSuggestion(unknown.copy(displayName = "Tension Board 2")))
        assertEquals(BoardBrand.KILTER, onboardingBoardSuggestion(unknown.copy(displayName = "Kilter Board")))
    }
}
