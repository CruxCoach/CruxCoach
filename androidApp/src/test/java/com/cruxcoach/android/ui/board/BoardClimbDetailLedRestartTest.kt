package com.cruxcoach.android.ui.board

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QuantumControllerState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.BleShareUiState
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.CruxRelayState
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.RestTimerState
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.data.SessionVisibility
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.led.LedEffectStatus
import com.cruxcoach.android.led.LedEffectsController
import com.cruxcoach.android.led.LedEffectsState
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.IntensityZones
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The climb page's Restart for an ended LED effect: in a shared session it puts
 * the session's current climb back on the wall instead of queueing another copy,
 * it is offered only on the climb the effect ended on, and with a board of its
 * own it lights that climb again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardClimbDetailLedRestartTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val boardRepository = mockk<BoardRepository>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val bleConnection = mockk<BoardBleConnection>(relaxed = true)
    private val boardLayerManager = mockk<BoardLayerManager>(relaxed = true)
    private val sessionManager = mockk<BoardSessionManager>(relaxed = true)
    private val zoneManager = mockk<IntensityZoneManager>(relaxed = true)
    private val cruxRelayManager = mockk<CruxRelayManager>(relaxed = true)
    private val bleShareManager = mockk<BleShareManager>(relaxed = true)
    private val sessionQueueManager = mockk<SessionQueueManager>(relaxed = true)
    private val ledEffectsController = mockk<LedEffectsController>(relaxed = true)
    private val playbackCoordinator = mockk<PlaylistPlaybackCoordinator>(relaxed = true)

    private val climbUuid = "11111111-aaaa-bbbb-cccc-000000000001"
    private val otherUuid = "11111111-aaaa-bbbb-cccc-000000000002"
    private val angle = 40

    private val queue = MutableStateFlow(SessionQueueState())
    private val connection = MutableStateFlow(ConnectionState.DISCONNECTED)

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

        every { bleConnection.connectionState } returns connection
        every { bleConnection.connectedBoardDescriptor } returns MutableStateFlow(null)
        every { bleConnection.quantumControllerState } returns MutableStateFlow(QuantumControllerState())
        every { boardLayerManager.state } returns MutableStateFlow(BoardLayerState())
        every { sessionManager.restTimer } returns MutableStateFlow(RestTimerState())
        every { bleShareManager.uiState } returns MutableStateFlow(BleShareUiState())
        every { cruxRelayManager.state } returns MutableStateFlow(CruxRelayState())
        every { sessionQueueManager.state } returns queue
        every { userPreferences.gradeScale } returns flowOf(GradeScale.V_SCALE)
        every { userPreferences.ledHoldColors } returns flowOf(LedHoldColors())
        every { userPreferences.singleConnectionBoardSendMode } returns flowOf(BoardSendMode.EXPLICIT)
        every { userPreferences.multiConnectionBoardSendMode } returns flowOf(BoardSendMode.EXPLICIT)
        every { zoneManager.zones } returns
            MutableStateFlow(IntensityZones(warmUpCeiling = 10.0, optimalCeiling = 20.0, isPersonalized = false))
        every { userPreferences.routeFrameSpeed } returns flowOf(5f)
        every { userPreferences.routeAutoLoop } returns flowOf(false)
        every { userPreferences.routeUseSetterSpeed } returns flowOf(false)
        every { userPreferences.restTimerDurationSeconds } returns flowOf(180)
        every { userPreferences.restTimerAutoStart } returns flowOf(false)
        every { userPreferences.boardProductSizeId } returns flowOf(1)
        every { userPreferences.boardLayoutId } returns flowOf(1)
        every { userPreferences.boardBrand } returns flowOf(BoardBrand.KILTER.wireValue)
        every { bleConnection.connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
        every { bleConnection.connectedQuantumModel } returns MutableStateFlow(null)

        every { boardRepository.getClimbByUuid(climbUuid, angle) } returns kilterClimb()
        every { boardRepository.getPlacementLedMap(any(), BoardBrand.KILTER.wireValue) } returns
            mapOf(1100 to 1, 1200 to 2, 1300 to 3)
        every { boardRepository.getRoleColorMapForBrand(BoardBrand.KILTER.wireValue) } returns mapOf(12 to 1)
    }

    @After
    fun tearDown() {
        // Let the load's IO tail resume onto Main before Main goes away
        // (see BoardClimbDetailOwnPublishTest).
        Thread.sleep(200)
        Dispatchers.resetMain()
    }

    private fun effectEndedOn(uuid: String) {
        every { ledEffectsController.state } returns flowOf(
            LedEffectsState(effectId = "pulse", status = LedEffectStatus.ENDED, litClimbUuid = uuid),
        )
    }

    /** A joinable session on [current]; its ids are spelled as they come over the session link. */
    private fun inSession(current: String, role: SessionRole = SessionRole.HOST) {
        val linked = { uuid: String -> uuid.replace("-", "").uppercase() }
        queue.value = SessionQueueState(
            role = role,
            queue = listOf(QueueItem(linked(otherUuid), angle), QueueItem(linked(climbUuid), angle)),
            currentIndex = if (current == otherUuid) 0 else 1,
            visibility = SessionVisibility.JOINABLE,
        )
    }

    private suspend fun loadedViewModel(): BoardClimbDetailViewModel {
        val vm = BoardClimbDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("climbUuid" to climbUuid, "angle" to angle.toString())),
            boardRepository = boardRepository,
            personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true),
            userPreferences = userPreferences,
            bleConnection = bleConnection,
            boardLayerManager = boardLayerManager,
            sessionManager = sessionManager,
            zoneManager = zoneManager,
            climbAdvertiser = mockk<ClimbBleAdvertiser>(relaxed = true),
            sessionQueueManager = sessionQueueManager,
            cruxRelayManager = cruxRelayManager,
            bleShareManager = bleShareManager,
            kilterSyncEngine = mockk(relaxed = true),
            nostrSigner = mockk(relaxed = true),
            nostrProfileManager = mockk(relaxed = true),
            communityClimbDeleter = mockk(relaxed = true),
            ownClimbPublisher = mockk(relaxed = true),
            climbNavState = mockk(relaxed = true),
            ledAnimationEngine = mockk(relaxed = true),
            ledEffectsController = ledEffectsController,
            playbackCoordinator = playbackCoordinator,
            context = mockk<Context>(relaxed = true),
        )
        vm.state.first { !it.isLoading && it.climb != null }
        return vm
    }

    @Test
    fun inASharedSessionRestartResendsTheCurrentClimbAndQueuesNothing() = runTest {
        inSession(current = climbUuid)
        effectEndedOn(climbUuid)
        val vm = loadedViewModel()

        assertEquals(LedEffectRestart.SESSION_RESEND, vm.ledEffectRestart.first())
        vm.restartLedEffect()
        vm.restartLedEffect()

        verify(exactly = 2) { playbackCoordinator.resendCurrentClimb() }
        verify(exactly = 0) { sessionQueueManager.addClimb(any(), any()) }
    }

    @Test
    fun inASharedSessionOnAnotherClimbThereIsNoRestart() = runTest {
        inSession(current = otherUuid)
        effectEndedOn(climbUuid)
        val vm = loadedViewModel()

        assertEquals(LedEffectRestart.NONE, vm.ledEffectRestart.first())
        vm.restartLedEffect()

        verify(exactly = 0) { playbackCoordinator.resendCurrentClimb() }
        verify(exactly = 0) { sessionQueueManager.addClimb(any(), any()) }
    }

    @Test
    fun aParticipantGetsNoRestart() = runTest {
        inSession(current = climbUuid, role = SessionRole.PARTICIPANT)
        effectEndedOn(climbUuid)
        val vm = loadedViewModel()

        assertEquals(LedEffectRestart.NONE, vm.ledEffectRestart.first())
        vm.restartLedEffect()

        verify(exactly = 0) { playbackCoordinator.resendCurrentClimb() }
        verify(exactly = 0) { sessionQueueManager.addClimb(any(), any()) }
    }

    @Test
    fun anEffectThatEndedOnAnotherClimbOffersNoRestartHere() = runTest {
        connection.value = ConnectionState.CONNECTED
        effectEndedOn(otherUuid)
        val vm = loadedViewModel()

        assertEquals(LedEffectRestart.NONE, vm.ledEffectRestart.first())
    }

    @Test
    fun onTheClimbItEndedOnADirectBoardLightsItAgain() = runTest {
        connection.value = ConnectionState.CONNECTED
        coEvery {
            bleConnection.sendClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns true
        effectEndedOn(climbUuid)
        val vm = loadedViewModel()

        assertEquals(LedEffectRestart.LIGHT, vm.ledEffectRestart.first())
        vm.restartLedEffect()

        coVerify(exactly = 1, timeout = 5_000) {
            bleConnection.sendClimb(
                any(), any(), any(), any(), any(), any(), any(), any(), BoardBrand.KILTER, climbUuid,
            )
        }
        verify(exactly = 0) { sessionQueueManager.addClimb(any(), any()) }
    }
}
