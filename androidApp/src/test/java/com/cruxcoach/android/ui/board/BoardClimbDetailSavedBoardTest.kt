package com.cruxcoach.android.ui.board

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QuantumControllerState
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.BleShareUiState
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.CruxRelayState
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.RestTimerState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.domain.board.IntensityZones
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Before
import org.junit.Test

/**
 * A board size saved while the climb is open (the size check in the ⋯ menu)
 * redraws the wall right away, without reloading the climb.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardClimbDetailSavedBoardTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val boardRepository = mockk<BoardRepository>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val bleConnection = mockk<BoardBleConnection>(relaxed = true)
    private val boardLayerManager = mockk<BoardLayerManager>(relaxed = true)
    private val sessionManager = mockk<BoardSessionManager>(relaxed = true)
    private val zoneManager = mockk<IntensityZoneManager>(relaxed = true)
    private val cruxRelayManager = mockk<CruxRelayManager>(relaxed = true)
    private val bleShareManager = mockk<BleShareManager>(relaxed = true)

    private val climbUuid = "11111111-aaaa-bbbb-cccc-000000000002"
    private val angle = 40
    private val savedSize = MutableStateFlow(10)

    private val size10 = BoardSize(10, 1, "12 x 12 with kickboard", 0, 144, 0, 156, null)
    private val size8 = BoardSize(8, 1, "12 x 8", 0, 144, 0, 108, null)
    private val image10 = BoardImage(1, 10, 1, 1, "10.png")
    private val image8 = BoardImage(2, 8, 1, 1, "8.png")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { bleConnection.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { bleConnection.connectedBoardDescriptor } returns MutableStateFlow(null)
        every { bleConnection.quantumControllerState } returns MutableStateFlow(QuantumControllerState())
        every { boardLayerManager.state } returns MutableStateFlow(BoardLayerState())
        every { sessionManager.restTimer } returns MutableStateFlow(RestTimerState())
        every { bleShareManager.uiState } returns MutableStateFlow(BleShareUiState())
        every { cruxRelayManager.state } returns MutableStateFlow(CruxRelayState())
        every { userPreferences.gradeScale } returns flowOf(GradeScale.V_SCALE)
        every { userPreferences.ledHoldColors } returns flowOf(LedHoldColors())
        every { userPreferences.singleConnectionBoardSendMode } returns flowOf(BoardSendMode.AUTOMATIC)
        every { userPreferences.multiConnectionBoardSendMode } returns flowOf(BoardSendMode.AUTOMATIC)
        every { zoneManager.zones } returns
            MutableStateFlow(IntensityZones(warmUpCeiling = 10.0, optimalCeiling = 20.0, isPersonalized = false))
        every { userPreferences.routeFrameSpeed } returns flowOf(5f)
        every { userPreferences.routeAutoLoop } returns flowOf(false)
        every { userPreferences.routeUseSetterSpeed } returns flowOf(false)
        every { userPreferences.restTimerDurationSeconds } returns flowOf(180)
        every { userPreferences.restTimerAutoStart } returns flowOf(false)
        every { userPreferences.boardProductSizeId } returns savedSize
        every { userPreferences.boardLayoutId } returns MutableStateFlow(1)

        every { boardRepository.getClimbByUuid(climbUuid, angle) } returns ClimbWithStats(
            uuid = climbUuid,
            layoutId = 1L,
            setterUsername = "setter",
            name = "Boulder",
            frames = "p1100r12p1200r13p1300r14",
            framesCount = 1L,
            difficultyAverage = 17.0,
            qualityAverage = 2.5,
            ascensionistCount = 3L,
            origin = "kilter",
            source = "kilter",
            syncStatus = "synced",
        )
        every { boardRepository.canRenderClimbOnSize(climbUuid, any(), "kilter") } returns true
        every { boardRepository.getProductSize(10, "kilter") } returns size10
        every { boardRepository.getProductSize(8, "kilter") } returns size8
        every { boardRepository.getBoardImages(10, 1, "kilter") } returns listOf(image10)
        every { boardRepository.getBoardImages(8, 1, "kilter") } returns listOf(image8)
    }

    @After
    fun tearDown() {
        // Let the IO tail of the load resume while Main is still installed.
        Thread.sleep(200)
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = BoardClimbDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("climbUuid" to climbUuid, "angle" to angle.toString())),
        boardRepository = boardRepository,
        personalBoardRepo = mockk(relaxed = true),
        userPreferences = userPreferences,
        bleConnection = bleConnection,
        boardLayerManager = boardLayerManager,
        sessionManager = sessionManager,
        zoneManager = zoneManager,
        climbAdvertiser = mockk(relaxed = true),
        sessionQueueManager = mockk(relaxed = true),
        cruxRelayManager = cruxRelayManager,
        bleShareManager = bleShareManager,
        kilterSyncEngine = mockk(relaxed = true),
        nostrSigner = mockk(relaxed = true),
        nostrProfileManager = mockk(relaxed = true),
        communityClimbDeleter = mockk(relaxed = true),
        ownClimbPublisher = mockk(relaxed = true),
        climbNavState = mockk(relaxed = true),
        ledAnimationEngine = mockk(relaxed = true),
        ledEffectsController = mockk(relaxed = true),
        context = mockk<Context>(relaxed = true),
    )

    @Test
    fun savedSizeChange_redrawsTheWallWithoutReloadingTheClimb() = runTest {
        val vm = buildViewModel()

        vm.state.test {
            var s = awaitItem()
            while (s.isLoading || s.boardSize == null) s = awaitItem()
            assertEquals(size10, s.boardSize)
            assertEquals(listOf(image10), s.boardImages)

            savedSize.value = 8
            while (s.boardSize != size8) s = awaitItem()
            assertEquals(listOf(image8), s.boardImages)
            assertEquals(climbUuid, s.climb?.uuid)
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 1) { boardRepository.getClimbByUuid(climbUuid, angle) }
    }
}
