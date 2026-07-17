package com.cruxcoach.android.ui.board

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
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
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.IntensityZones
import io.mockk.coEvery
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Covers the logbook-only detail fallback (Point 2 of the
 * "Climb nicht gefunden" fix).
 *
 * A climb the user has a local Kilter ascent for but which is absent from
 * the board DB (Kilter new-PowerSync-world uuid, never mirrored) must NOT
 * dead-end on [ClimbDetailState.error]. Instead the VM resolves the user's
 * local history and exposes a [LogbookOnlyState]. When there is no history,
 * the raw error path is preserved.
 *
 * Only the not-found code path of `loadClimb` is exercised, which runs
 * before any [UserPreferences] read — so the heavy Android collaborators are
 * relaxed mockks with just their flow surfaces stubbed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardClimbDetailLogbookFallbackTest {

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
    private val context = mockk<Context>(relaxed = true)

    private val missingUuid = "a30d8042-aeea-42ce-8015-239016c87769"
    private val angle = 25

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        // Flow surfaces read during VM init (collect blocks + .value reads).
        every { bleConnection.connectionState } returns
            MutableStateFlow(ConnectionState.DISCONNECTED)
        every { sessionManager.restTimer } returns MutableStateFlow(RestTimerState())
        every { bleShareManager.uiState } returns MutableStateFlow(BleShareUiState())
        every { cruxRelayManager.state } returns MutableStateFlow(CruxRelayState())
        every { userPreferences.gradeScale } returns flowOf(GradeScale.V_SCALE)
        every { userPreferences.ledHoldColors } returns flowOf(LedHoldColors())
        every { zoneManager.zones } returns
            MutableStateFlow(IntensityZones(warmUpCeiling = 10.0, optimalCeiling = 20.0, isPersonalized = false))

        // The climb is absent from the board DB under every uuid form,
        // including the normalized fallback.
        every { boardRepository.getClimbByUuid(any(), any()) } returns null
        every { boardRepository.getClimbByUuidNormalized(any(), any()) } returns null

        every { context.getString(any(), any(), any()) } returns "Climb not found"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): BoardClimbDetailViewModel {
        val savedState = SavedStateHandle(
            mapOf("climbUuid" to missingUuid, "angle" to angle.toString())
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
            ownClimbPublisher = mockk(relaxed = true),
            climbNavState = mockk(relaxed = true),
            context = context,
        )
    }

    private fun ascent(uuid: String) = AscentWithClimb(
        uuid = "ascent-1",
        climbUuid = uuid,
        angle = angle.toLong(),
        isMirror = false,
        bidCount = 3,
        quality = null,
        difficulty = null,
        comment = "fun problem",
        climbedAt = "2026-06-01",
        climbName = "",
        climbFrames = "",
        difficultyAverage = null,
    )

    @Test
    fun missingClimb_withLogbookHistory_rendersLogbookOnlyFallback() = runTest {
        coEvery { personalBoardRepo.getUserHistoryForClimb(missingUuid) } returns
            listOf(ascent(missingUuid))

        val vm = buildViewModel()

        vm.state.test {
            // Skip intermediate emissions until we settle on a terminal state.
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()

            assertNull("error must not be set when history exists", s.error)
            assertNull("no board-DB climb is resolved", s.climb)
            val logbookOnly = s.logbookOnly
            assertNotNull("logbook-only fallback must be populated", logbookOnly)
            assertEquals(missingUuid, logbookOnly!!.uuid)
            assertEquals(1, logbookOnly.ascents.size)
            assertEquals(3L, logbookOnly.ascents.first().bidCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun missingClimb_withNoHistory_keepsRawErrorState() = runTest {
        coEvery { personalBoardRepo.getUserHistoryForClimb(missingUuid) } returns emptyList()

        val vm = buildViewModel()

        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()

            assertNull("no logbook fallback without history", s.logbookOnly)
            assertNotNull("raw error path preserved for truly-unknown climb", s.error)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
