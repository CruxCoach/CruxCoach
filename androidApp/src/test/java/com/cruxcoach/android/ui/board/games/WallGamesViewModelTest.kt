package com.cruxcoach.android.ui.board.games

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardCleaningState
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.ShinyWallVariant
import com.cruxcoach.domain.board.games.HoldsThief
import com.cruxcoach.domain.board.games.WallHold
import com.cruxcoach.domain.board.games.WallRows
import com.cruxcoach.android.led.LedAnimationEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WallGamesViewModelTest {

    private val variant = ShinyWallVariant.ST1
    private val board = WallGameBoard(
        brand = BoardBrand.SHINYWALL,
        layoutId = variant.layoutId,
        rows = WallRows.of((0 until 220).map { WallHold(variant.placementId(it), (it % 11) * 20.0, (it / 11) * 20.0) }),
        shinyVariant = variant,
    )

    private val connection = MutableStateFlow(ConnectionState.CONNECTED)
    private val connectedBrand = MutableStateFlow<BoardBrand?>(BoardBrand.SHINYWALL)
    private val cleaning = MutableStateFlow(BoardCleaningState())
    private val session = MutableStateFlow(SessionQueueState())
    private lateinit var ble: BoardBleConnection
    private lateinit var repository: BoardRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ble = mockk(relaxed = true) {
            every { connectionState } returns connection
            every { connectedBoardBrand } returns connectedBrand
            every { cleaningState } returns cleaning
            coEvery { updateShinyWallHolds(any(), any()) } returns true
            coEvery { clearBoard(any()) } returns true
        }
        repository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(args: Map<String, Any?> = emptyMap()): WallGamesViewModel {
        val loader = mockk<WallGameBoardLoader> { coEvery { load() } returns WallGameBoardLoad.Ready(board) }
        val personal = mockk<PersonalBoardRepository>(relaxed = true) {
            every { observeClimbHistory() } returns flowOf(emptyList())
        }
        val queue = mockk<SessionQueueManager>(relaxed = true) { every { state } returns session }
        val ledEffects = mockk<LedAnimationEngine>(relaxed = true)
        val vm = WallGamesViewModel(SavedStateHandle(args), loader, repository, personal, ble, queue, ledEffects)
        runBlocking { withTimeout(5_000) { vm.state.first { !it.loading } } }
        return vm
    }

    private fun awaitState(vm: WallGamesViewModel, predicate: (WallGamesState) -> Boolean): WallGamesState =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    @Test
    fun theHoldThiefClimbGoesUpOnlyWhenAskedAndThenFollowsChanges() {
        val vm = viewModel()
        vm.selectGame(WallGame.THIEF)
        val route = vm.state.value.thiefRoute
        assertEquals(2, route.count { it.roleId == HoldRole.START })
        coVerify(exactly = 0) { ble.updateShinyWallHolds(any(), any()) }

        vm.start()
        coVerify(exactly = 1) { ble.updateShinyWallHolds(route, variant) }
        assertTrue(vm.state.value.thiefLive)

        vm.setStealCount(4)
        vm.stealHolds()
        val after = vm.state.value.thiefRoute
        assertEquals(HoldsThief.handCount(route) - 4, HoldsThief.handCount(after))
        coVerify(exactly = 1) { ble.updateShinyWallHolds(after, variant) }
    }

    @Test
    fun withoutAConnectionTheGameStaysOnTheScreen() {
        connection.value = ConnectionState.DISCONNECTED
        val vm = viewModel()
        vm.selectGame(WallGame.THIEF)
        vm.start()
        assertEquals(WallSendProblem.NOT_CONNECTED, awaitState(vm) { it.sendProblem != null }.sendProblem)
        coVerify(exactly = 0) { ble.updateShinyWallHolds(any(), any()) }
    }

    @Test
    fun aSharedSessionOrAnotherBoardKeepsTheWallUntouched() {
        session.value = SessionQueueState(role = SessionRole.HOST)
        val vm = viewModel()
        vm.selectGame(WallGame.THIEF)
        vm.start()
        assertEquals(WallSendProblem.SESSION, vm.state.value.sendProblem)

        session.value = SessionQueueState()
        connectedBrand.value = BoardBrand.KILTER
        vm.lightThiefClimb()
        assertEquals(WallSendProblem.OTHER_BOARD, vm.state.value.sendProblem)
        coVerify(exactly = 0) { ble.updateShinyWallHolds(any(), any()) }
        coVerify(exactly = 0) { ble.sendClimb(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun aTimedGameClearsTheWallForTheCountdownAndStopClearsItAgain() {
        val vm = viewModel()
        vm.selectGame(WallGame.LAVA)
        vm.setSoundOn(false)
        assertTrue(vm.state.value.canStart)
        vm.start()
        assertEquals(GamePhase.COUNTDOWN, vm.state.value.run.phase)
        coVerify(exactly = 1) { ble.clearBoard(BoardBrand.SHINYWALL) }
        vm.stop()
        assertEquals(GamePhase.IDLE, vm.state.value.run.phase)
        assertTrue(!vm.state.value.running)
    }

    @Test
    fun aClimbFromTheDetailPageOpensEnduranceAndAForeignClimbIsRefused() {
        val own = climb("own", variant.layoutId, "p${variant.placementId(3)}r12p${variant.placementId(150)}r13")
        val foreign = climb("foreign", ShinyWallVariant.ST2.layoutId, "p${ShinyWallVariant.ST2.placementId(3)}r12")
        every { repository.getClimbByUuid("own", 30) } returns own
        every { repository.getClimbByUuid("foreign", 30) } returns foreign

        val vm = viewModel(mapOf("climbUuid" to "own", "angle" to 30))
        val state = awaitState(vm) { it.climb != null || it.climbProblem != null }
        assertEquals(WallGame.ENDURANCE, state.game)
        assertEquals("own", state.climb?.uuid)
        assertEquals(0, state.climb?.offBoard)
        assertNull(state.climbProblem)
        assertTrue(state.canStart)

        vm.chooseClimb("foreign", 30)
        val refused = awaitState(vm) { it.climbProblem != null }
        assertEquals(ClimbProblem.OTHER_BOARD, refused.climbProblem)
        assertNull(refused.climb)
        assertTrue(!refused.canStart)
    }

    private fun climb(uuid: String, layoutId: Long, frames: String): ClimbWithStats = mockk(relaxed = true) {
        every { this@mockk.uuid } returns uuid
        every { this@mockk.layoutId } returns layoutId
        every { this@mockk.frames } returns frames
        every { name } returns uuid
        every { boardBrand } returns BoardBrand.SHINYWALL.wireValue
    }
}
