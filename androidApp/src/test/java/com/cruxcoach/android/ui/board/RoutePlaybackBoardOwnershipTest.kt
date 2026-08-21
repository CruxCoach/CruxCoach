package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.boardcell.BoardCellWriteGateway
import com.cruxcoach.android.boardcell.BoardProjection
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.fakes.TestClimb
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route playback versus a board that a group is on.
 *
 * A per-frame send does not fail loudly inside a BoardCell — it succeeds at
 * the wrong granularity. The mesh wire model carries a `BoardProjection`
 * (climb + angle) and no frame index, so the controller re-resolves the climb
 * and lights its first frame again for every frame this device animates
 * through: the counter runs, the wall does not move, and the group's own
 * canonical selection is overwritten at animation rate.
 *
 * Until that protocol carries a frame, playback stays a local preview. These
 * pin both halves of that: nothing leaves the device, and the preview still
 * works.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlaybackBoardOwnershipTest {

    private val routeClimb = TestClimb.stats(
        uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        name = "Three-frame circuit",
        setterUsername = "setter",
        difficulty = 20.0,
        quality = 3.0,
        ascensionists = 12,
        frames = "p10r12",
    ).copy(boardBrand = BoardBrand.KILTER.wireValue, framesCount = 3)

    private val frames = listOf(
        listOf(BoardHold(10, 12)),
        listOf(BoardHold(20, 13)),
        listOf(BoardHold(30, 14)),
    )

    private fun routeState() = MutableStateFlow(
        ClimbDetailState(
            isLoading = false,
            climb = routeClimb,
            holds = frames[0],
            angle = 40,
            ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
            playback = PlaybackState(
                allFrames = frames,
                totalFrames = frames.size,
                currentFrameIndex = 0,
                isRoute = true,
            ),
        )
    )

    private class Rig(
        val state: MutableStateFlow<ClimbDetailState>,
        val ble: BoardBleConnection,
        val sender: BoardSendController,
        val playback: RoutePlaybackController,
        val projections: MutableList<BoardProjection>,
    )

    private fun rig(scope: CoroutineScope, testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler, cellOwnsBoard: Boolean): Rig {
        val state = routeState()
        val projections = mutableListOf<BoardProjection>()
        val ble = mockk<BoardBleConnection>(relaxed = true) {
            every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
            every { connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
            coEvery { sendClimb(any(), any(), any(), any(), any(), any()) } returns true
        }
        val repository = mockk<BoardRepository>(relaxed = true) {
            every { getPlacementLedMap(any(), BoardBrand.KILTER.wireValue) } returns
                mapOf(10 to 100, 20 to 200, 30 to 300)
            every { getRoleColorMapForBrand(BoardBrand.KILTER.wireValue) } returns mapOf(12 to 1, 13 to 2, 14 to 3)
        }
        val preferences = mockk<UserPreferences>(relaxed = true) {
            every { boardBrand } returns flowOf(BoardBrand.KILTER.wireValue)
            every { boardProductSizeId } returns flowOf(10)
            every { routeCountdown } returns flowOf(false)
        }
        val queue = mockk<SessionQueueManager>(relaxed = true)
        every { queue.state } returns MutableStateFlow(SessionQueueState())
        val sender = BoardSendController(
            scope = scope,
            state = state,
            boardRepository = repository,
            personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true),
            bleConnection = ble,
            userPreferences = preferences,
            climbAdvertiser = mockk<ClimbBleAdvertiser>(relaxed = true),
            sessionQueueManager = queue,
            isSharingEnabled = { false },
            boardLayerManager = mockk<BoardLayerManager>(relaxed = true),
            boardCellWriteGateway = BoardCellWriteGateway { projection, write ->
                projections += projection
                write()
            },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            boardCellOwnsBoard = { cellOwnsBoard },
        )
        // Exactly the wiring BoardClimbDetailViewModel gives the playback
        // controller: a frame only goes to the wall when a send could land.
        val playback = RoutePlaybackController(
            scope = scope,
            state = state,
            userPreferences = preferences,
            onFrameChanged = { if (sender.canSendToBoard()) sender.sendToBoard(automaticLayer = true) },
        )
        return Rig(state, ble, sender, playback, projections)
    }

    @Test
    fun `a group on the board stops playback from commanding the wall`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig(this, testScheduler, cellOwnsBoard = true)

            rig.playback.nextFrame()
            rig.playback.nextFrame()
            advanceUntilIdle()

            assertFalse(
                "a frame cannot land on a board the group's list owns",
                rig.sender.canSendToBoard(),
            )
            coVerify(exactly = 0) { rig.ble.sendClimb(any(), any(), any(), any(), any(), any()) }
            assertEquals(
                "no BoardProjection may be committed per animation frame",
                emptyList<BoardProjection>(), rig.projections,
            )
        }

    @Test
    fun `the local preview still runs through the route`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig(this, testScheduler, cellOwnsBoard = true)

            rig.playback.nextFrame()
            rig.playback.nextFrame()
            advanceUntilIdle()

            assertEquals(2, rig.state.value.playback.currentFrameIndex)
            assertEquals(
                "the screen still shows the frame the user navigated to",
                frames[2], rig.state.value.holds,
            )
        }

    @Test
    fun `without a group the same frame reaches the board`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig(this, testScheduler, cellOwnsBoard = false)

            assertTrue(rig.sender.canSendToBoard())
            rig.playback.nextFrame()
            advanceUntilIdle()

            assertEquals(1, rig.state.value.playback.currentFrameIndex)
            coVerify(exactly = 1) {
                rig.ble.sendClimb(frames[1], any(), any(), any(), any(), any())
            }
        }
}
