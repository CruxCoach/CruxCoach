package com.cruxcoach.android.ui.board

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.community.OwnKilterClimbPublisher
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.BleShareUiState
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.CruxRelayState
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.RestTimerState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.IntensityZones
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Climb-detail surface of the own-Kilter-climb publish:
 *  - `canPublishAsMine` is true ONLY when the authorship gate opens
 *    (publisher predicate) — a logged-but-foreign or curated climb never
 *    shows the action;
 *  - `publishOwnClimb` routes through [OwnKilterClimbPublisher] and maps
 *    the outcome to one-shot feedback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardClimbDetailOwnPublishTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val boardRepository = mockk<BoardRepository>(relaxed = true)
    private val personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val bleConnection = mockk<BoardBleConnection>(relaxed = true)
    private val sessionManager = mockk<BoardSessionManager>(relaxed = true)
    private val zoneManager = mockk<IntensityZoneManager>(relaxed = true)
    private val climbAdvertiser = mockk<ClimbBleAdvertiser>(relaxed = true)
    private val cruxRelayManager = mockk<CruxRelayManager>(relaxed = true)
    private val bleShareManager = mockk<BleShareManager>(relaxed = true)
    private val ownClimbPublisher = mockk<OwnKilterClimbPublisher>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val climbUuid = "11111111-aaaa-bbbb-cccc-000000000001"
    private val angle = 40

    private fun kilterClimb() = ClimbWithStats(
        uuid = climbUuid,
        layoutId = 1L,
        setterUsername = "me",
        name = "Mein Boulder",
        frames = "p1100r12p1200r13p1300r14",
        framesCount = 1L,
        difficultyAverage = 17.0,
        qualityAverage = 2.5,
        ascensionistCount = 3L,
        origin = "kilter",
        source = "kilter",
        syncStatus = "synced",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        every { bleConnection.connectionState } returns
            MutableStateFlow(ConnectionState.DISCONNECTED)
        every { sessionManager.restTimer } returns MutableStateFlow(RestTimerState())
        every { bleShareManager.uiState } returns MutableStateFlow(BleShareUiState())
        every { cruxRelayManager.state } returns MutableStateFlow(CruxRelayState())
        every { userPreferences.gradeScale } returns flowOf(GradeScale.V_SCALE)
        every { userPreferences.ledHoldColors } returns flowOf(LedHoldColors())
        every { zoneManager.zones } returns
            MutableStateFlow(IntensityZones(warmUpCeiling = 10.0, optimalCeiling = 20.0, isPersonalized = false))
        // Flow surfaces the found-climb load path reads.
        every { userPreferences.routeFrameSpeed } returns flowOf(5f)
        every { userPreferences.routeAutoLoop } returns flowOf(false)
        every { userPreferences.routeUseSetterSpeed } returns flowOf(false)
        every { userPreferences.restTimerDurationSeconds } returns flowOf(180)
        every { userPreferences.restTimerAutoStart } returns flowOf(false)
        every { userPreferences.boardProductSizeId } returns flowOf(1)
        every { userPreferences.boardLayoutId } returns flowOf(1)

        every { boardRepository.getClimbByUuid(climbUuid, angle) } returns kilterClimb()
    }

    @After
    fun tearDown() {
        // The VM's load coroutine ends with a withContext(Dispatchers.IO)
        // tail that resumes onto Main AFTER the last observed state
        // emission. Give that inline resumption a moment to land while
        // Main is still installed — resetting first turns the tail into
        // an uncaught "Main dispatcher missing" failure on the NEXT test.
        Thread.sleep(200)
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): BoardClimbDetailViewModel {
        val savedState = SavedStateHandle(
            mapOf("climbUuid" to climbUuid, "angle" to angle.toString())
        )
        return BoardClimbDetailViewModel(
            savedStateHandle = savedState,
            boardRepository = boardRepository,
            personalBoardRepo = personalBoardRepo,
            userPreferences = userPreferences,
            bleConnection = bleConnection,
            sessionManager = sessionManager,
            zoneManager = zoneManager,
            climbAdvertiser = climbAdvertiser,
            sessionQueueManager = mockk(relaxed = true),
            cruxRelayManager = cruxRelayManager,
            bleShareManager = bleShareManager,
            kilterSyncEngine = mockk(relaxed = true),
            nostrSigner = mockk(relaxed = true),
            nostrProfileManager = mockk(relaxed = true),
            communityClimbDeleter = mockk(relaxed = true),
            ownClimbPublisher = ownClimbPublisher,
            climbNavState = mockk(relaxed = true),
            context = context,
        )
    }

    @Test
    fun ownAuthoredUnpublishedClimb_exposesPublishAction() = runTest {
        every { ownClimbPublisher.isPublishableAsMine(climbUuid) } returns true

        val vm = buildViewModel()

        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()
            assertTrue("own-authored climb must expose the publish action", s.canPublishAsMine)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** COMPLIANCE: a climb the user logged but did NOT author never shows
     *  the publish action on the detail surface. */
    @Test
    fun foreignOrCuratedClimb_hidesPublishAction() = runTest {
        every { ownClimbPublisher.isPublishableAsMine(climbUuid) } returns false

        val vm = buildViewModel()

        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()
            assertFalse(
                "logged-but-not-authored / curated climbs must never be publishable",
                s.canPublishAsMine,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun publishOwnClimb_routesThroughPublisher_andEmitsFeedback() = runTest {
        every { ownClimbPublisher.isPublishableAsMine(climbUuid) } returns true
        coEvery { ownClimbPublisher.publish(climbUuid) } returns
            OwnKilterClimbPublisher.Outcome.Published("ev1")

        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()

            vm.publishOwnClimb()

            while (s.ownPublishFeedback == null) s = awaitItem()
            assertEquals(OwnPublishFeedback.Published, s.ownPublishFeedback)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { ownClimbPublisher.publish(climbUuid) }
    }

    @Test
    fun publishOwnClimb_notAuthorOutcome_surfacesBackstopFeedback() = runTest {
        every { ownClimbPublisher.isPublishableAsMine(climbUuid) } returns true
        coEvery { ownClimbPublisher.publish(climbUuid) } returns
            OwnKilterClimbPublisher.Outcome.NotAuthor

        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()

            vm.publishOwnClimb()

            while (s.ownPublishFeedback == null) s = awaitItem()
            assertEquals(OwnPublishFeedback.NotAuthor, s.ownPublishFeedback)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun publishOwnClimb_noNostrIdentity_mapsToKeySetupNudge() = runTest {
        every { ownClimbPublisher.isPublishableAsMine(climbUuid) } returns true
        coEvery { ownClimbPublisher.publish(climbUuid) } returns
            OwnKilterClimbPublisher.Outcome.NoNostrIdentity

        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()

            vm.publishOwnClimb()

            while (s.ownPublishFeedback == null) s = awaitItem()
            assertEquals(OwnPublishFeedback.NoNostrIdentity, s.ownPublishFeedback)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
