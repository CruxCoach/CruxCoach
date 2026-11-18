package com.cruxcoach.android.ui.board.games

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardCleaningState
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.ShinyWallVariant
import com.cruxcoach.domain.board.games.HoldsThief
import com.cruxcoach.domain.board.games.Knot
import com.cruxcoach.domain.board.games.KnotColour
import com.cruxcoach.domain.board.games.KnotDraw
import com.cruxcoach.domain.board.games.KnotHolds
import com.cruxcoach.domain.board.games.KnotLayout
import com.cruxcoach.domain.board.games.KnotSpacing
import com.cruxcoach.domain.board.games.WallHold
import com.cruxcoach.domain.board.games.WallRows
import com.cruxcoach.android.led.LedAnimationEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
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
    private lateinit var voice: KnotVoice

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
        voice = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        args: Map<String, Any?> = emptyMap(),
        loader: WallGameBoardLoader = mockk { coEvery { load() } returns WallGameBoardLoad.Ready(board) },
    ): WallGamesViewModel {
        val personal = mockk<PersonalBoardRepository>(relaxed = true) {
            every { observeClimbHistory() } returns flowOf(emptyList())
        }
        val queue = mockk<SessionQueueManager>(relaxed = true) { every { state } returns session }
        val ledEffects = mockk<LedAnimationEngine>(relaxed = true)
        val prefs = mockk<UserPreferences>(relaxed = true) { every { knotSafetySeen } returns flowOf(true) }
        val vm = WallGamesViewModel(SavedStateHandle(args), loader, repository, personal, ble, queue, ledEffects, prefs, voice)
        runBlocking { withTimeout(5_000) { vm.state.first { !it.loading } } }
        return vm
    }

    private fun awaitState(vm: WallGamesViewModel, predicate: (WallGamesState) -> Boolean): WallGamesState =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    @Test
    fun backFromTheCataloguesTheBoardLoadsAgain() {
        // No hold positions yet; the user downloads the catalogue and comes back.
        val loader = mockk<WallGameBoardLoader> {
            coEvery { load() } returnsMany listOf(WallGameBoardLoad.NoHolds, WallGameBoardLoad.Ready(board))
        }
        val vm = viewModel(loader = loader)
        assertEquals(WallGameBoardLoad.NoHolds, awaitState(vm) { !it.loading }.unavailable)

        vm.reloadIfMissing()
        val loaded = awaitState(vm) { it.board != null }
        assertEquals(null, loaded.unavailable)
        assertTrue(loaded.thiefRoute.isNotEmpty())

        // With the board there, coming back loads nothing again.
        vm.reloadIfMissing()
        coVerify(exactly = 2) { loader.load() }
    }

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

    /** Seven rows of a MoonBoard 2016: at the default share more holds than one frame carries. */
    private val moon = MoonBoardVariant.MOONBOARD_2016
    private val moonBoard = WallGameBoard(
        brand = BoardBrand.MOONBOARD,
        layoutId = moon.layoutId,
        rows = WallRows.of(WallGameHolds.moonBoard(moon, emptySet()).filter { it.y < 7 }),
        moonVariant = moon,
    )

    private fun moonViewModel(): WallGamesViewModel {
        connectedBrand.value = BoardBrand.MOONBOARD
        return viewModel(loader = mockk { coEvery { load() } returns WallGameBoardLoad.Ready(moonBoard) })
    }

    @Test
    fun theHoldThiefOnAMoonBoardIsRefusedWholeWhenTheBoxCannotShowIt() {
        coEvery { ble.sendMoonBoardClimb(any(), any(), any()) } returns true
        val vm = moonViewModel()
        vm.selectGame(WallGame.THIEF)
        // The first share is the highest whose climb fits, so the first climb lights whole.
        val first = vm.state.value
        assertTrue(first.thiefDensity < HoldsThief.DEFAULT_DENSITY)
        vm.start()
        assertNull(vm.state.value.sendProblem)
        coVerify(exactly = 1) { ble.sendMoonBoardClimb(WallGameHolds.moonBoardFrames(first.thiefRoute), moon, MoonBoardLedMode.BELOW) }

        // More than 52 holds: nothing goes out, not even the part that would fit (without its start holds).
        vm.setThiefDensity(HoldsThief.DEFAULT_DENSITY)
        assertTrue(vm.state.value.thiefRoute.size > MoonBoardFrameEncoder.MAX_FRAME_LEDS)
        assertEquals(WallSendProblem.TOO_MANY_HOLDS, vm.state.value.sendProblem)
        coVerify(exactly = 1) { ble.sendMoonBoardClimb(any(), any(), any()) }

        // A lower share fits: the whole climb goes up, start holds included, and the message goes.
        vm.setThiefDensity(10)
        val fewer = vm.state.value.thiefRoute
        assertEquals(2, fewer.count { it.roleId == HoldRole.START })
        assertNull(vm.state.value.sendProblem)
        coVerify(exactly = 1) { ble.sendMoonBoardClimb(WallGameHolds.moonBoardFrames(fewer), moon, MoonBoardLedMode.BELOW) }
    }

    @Test
    fun aWallStateWrittenLateKeepsTheRulesOfItsGame() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { ble.sendMoonBoardClimb(any(), any(), any()) } coAnswers { if (++calls == 1) gate.await(); true }
        val vm = moonViewModel()
        vm.selectGame(WallGame.THIEF)
        vm.start()
        // While the first climb is still being written, a too-big one waits and the user switches to the lava.
        vm.setThiefDensity(HoldsThief.DEFAULT_DENSITY)
        vm.selectGame(WallGame.LAVA)
        gate.complete(Unit)
        // The waiting hold-thief climb is refused whole, not cut down like lava; the lava screen shows no message.
        coVerify(exactly = 1) { ble.sendMoonBoardClimb(any(), any(), any()) }
        assertNull(awaitState(vm) { it.game == WallGame.LAVA }.sendProblem)
    }

    // ── Knot ───────────────────────────────────────────────────────────

    /** A Kilter-like 12x12 in inches: 18 columns 8" apart, rows 8" apart from 4" up; LED position = placement id. */
    private val kilter = WallGameBoard(
        brand = BoardBrand.KILTER,
        layoutId = 1,
        rows = WallRows.of((0 until 18 * 19).map { WallHold(it + 1, 4.0 + (it % 18) * 8.0, 4.0 + (it / 18) * 8.0) }),
        ledMap = (1..18 * 19).associateWith { it },
        angle = 40,
    )

    private fun kilterViewModel(handGrades: Map<Int, Double> = emptyMap()): WallGamesViewModel {
        connectedBrand.value = BoardBrand.KILTER
        every { ble.connectedBoard } returns mockk(relaxed = true) { every { apiLevel } returns 3 }
        coEvery { ble.sendRawLeds(any(), any(), any()) } returns true
        return viewModel(
            loader = mockk(relaxed = true) {
                coEvery { load() } returns WallGameBoardLoad.Ready(kilter)
                every { knotBigHolds(any()) } returns emptySet()
                every { knotHandGrades(any()) } returns handGrades
            },
        )
    }

    /** Knot's field, worked out off the main thread, for [climbers] at the wall. */
    private fun awaitKnot(vm: WallGamesViewModel, climbers: Int = 1): KnotState =
        awaitState(vm) { !it.knot.pending && it.knot.layout?.climbers == climbers }.knot

    @Test
    fun knotLightsTheFieldInItsColoursThenCallsAndTheLastOneWins() {
        val vm = kilterViewModel()
        vm.selectGame(WallGame.KNOT)
        val setup = awaitKnot(vm)
        assertEquals(4, setup.maxClimbers)
        assertEquals(1, setup.players)
        assertTrue(vm.state.value.canStart)
        // A 40° wall gets the steep hint.
        assertEquals(40, setup.steepAngle(vm.state.value.board))

        vm.setKnotPlayers(2)
        vm.setKnotName(0, "Anna")
        val preview = awaitKnot(vm, 2).previewSpots()!!
        vm.start()
        val lit = awaitState(vm) { it.knot.run.phase == KnotPhase.POSITIONS }
        val field = lit.knot.run.field!!
        // What lights is what the preview showed.
        assertEquals(preview.keys, field.spots.keys)
        assertTrue(lit.running)
        // Each spot goes up in its own colour.
        val sent = field.spots.entries.sortedBy { it.key }.map { (id, colour) -> id to colour.rgb332 }
        coVerify(timeout = 5_000) { ble.sendRawLeds(sent, BoardBrand.KILTER, any()) }

        vm.startKnotCalls()
        awaitState(vm) { it.knot.run.phase == KnotPhase.COUNTDOWN }
        vm.knotOut(0)
        val over = awaitState(vm) { it.knot.run.phase == KnotPhase.FINISHED }
        assertEquals(1, over.knot.run.winner)
        assertTrue(!over.running)

        // Done clears the wall.
        vm.reset()
        assertEquals(KnotPhase.IDLE, vm.state.value.knot.run.phase)
        coVerify(timeout = 5_000) { ble.clearBoard(BoardBrand.KILTER) }
    }

    @Test
    fun knotByTapHasNoTimerAndTheSoundSwitchSilencesTheCalls() {
        val vm = kilterViewModel()
        vm.selectGame(WallGame.KNOT)
        vm.setKnotByTap(true)
        awaitKnot(vm)
        vm.start()
        awaitState(vm) { it.knot.run.phase == KnotPhase.POSITIONS }
        vm.startKnotCalls()
        val run = awaitState(vm) { it.knot.run.phase == KnotPhase.COUNTDOWN }.knot.run
        assertTrue(run.byTap)
        // While a round runs, the switch stays as it was.
        vm.setKnotByTap(false)
        assertTrue(vm.state.value.knot.byTap)
        vm.setSoundOn(false)
        verify { voice.stop() }
    }

    @Test
    fun knotSpacingChangesTheFieldAndFallsBackWhenTheWallCannotKeepIt() {
        val vm = kilterViewModel()
        vm.selectGame(WallGame.KNOT)
        // Solo the 1.2 m field reaches no further at medium or wide: those are greyed out, and wide falls back to close.
        val solo = awaitKnot(vm)
        assertEquals(setOf(KnotSpacing.CLOSE), solo.spacings)
        assertEquals(setOf(KnotSpacing.MEDIUM, KnotSpacing.WIDE), solo.sameReach)
        vm.setKnotSpacing(KnotSpacing.WIDE)
        assertEquals(KnotSpacing.CLOSE, awaitState(vm) { !it.knot.pending }.knot.spacing)
        // Two climbers: three spacings, each reaching further.
        vm.setKnotPlayers(2)
        val close = awaitKnot(vm, 2)
        assertEquals(KnotSpacing.CLOSE, close.spacing)
        assertEquals(KnotSpacing.entries.toSet(), close.spacings)
        vm.setKnotSpacing(KnotSpacing.WIDE)
        val wide = awaitState(vm) { !it.knot.pending && it.knot.spacing == KnotSpacing.WIDE }.knot
        assertTrue(wide.reachM!! > close.reachM!!)
        // Fewer, wider stripes; the holds per colour and climber stay, so the climbers share more of them.
        assertTrue(wide.layout!!.stripes.size < close.layout!!.stripes.size)
        assertTrue(wide.layout!!.spots.size <= close.layout!!.spots.size)
        // The field that lights is the one of the chosen spacing.
        vm.start()
        val lit = awaitState(vm) { it.knot.run.phase == KnotPhase.POSITIONS }
        assertEquals(wide.layout!!.stripes.flatMap { it.first + it.second }.toSet(), lit.knot.run.field!!.spots.keys)
    }

    @Test
    fun knotWithACatalogueDrawsAFreshFieldEveryGameAwayFromTheLastOne() {
        // Every hold rated, the easier the lower.
        val vm = kilterViewModel(handGrades = kilter.rows.holds.associate { it.placementId to 10.0 + it.y / 10 })
        vm.selectGame(WallGame.KNOT)
        // The grades come in off the main thread; the player count changes once they're there, not at the same time.
        awaitState(vm) { it.knot.drawn && !it.knot.pending }
        vm.setKnotPlayers(2)
        // Once the grades are there, the field is drawn. On this even grid a draw can come out as the nearest holds (the
        // seed is random), so the flag tells, not the holds.
        val shown = awaitState(vm) { !it.knot.pending && it.knot.drawn && it.knot.layout?.climbers == 2 }.knot.layout!!
        vm.start()
        // What lights is what the preview showed.
        assertEquals(shown.spots, awaitState(vm) { it.knot.run.phase == KnotPhase.POSITIONS }.knot.run.field!!.spots.keys)
        // The next game's field is drawn once the calls begin, away from this one's holds. How far away depends on the
        // wall: KnotTest checks that on real boards.
        vm.startKnotCalls()
        awaitState(vm) { it.knot.run.phase == KnotPhase.COUNTDOWN }
        assertEquals(shown.spots, vm.knotLastSpots)
        assertTrue(awaitState(vm) { !it.knot.pending }.knot.drawn)
    }

    @Test
    fun knotHoldsPerColourChangeTheFieldAndTheWallFollowsWhenASliderIsLetGo() {
        val vm = kilterViewModel()
        vm.selectGame(WallGame.KNOT)
        vm.setKnotPlayers(2)
        val normal = awaitKnot(vm, 2)
        assertEquals(KnotHolds.NORMAL, normal.holds)
        vm.setKnotHolds(KnotHolds.MANY)
        val many = awaitState(vm) { !it.knot.pending && it.knot.holds == KnotHolds.MANY }.knot
        assertTrue(many.layout!!.spots.size > normal.layout!!.spots.size)
        vm.setKnotHolds(KnotHolds.FEW)
        val few = awaitState(vm) { !it.knot.pending && it.knot.holds == KnotHolds.FEW }.knot
        assertTrue(few.layout!!.spots.size < normal.layout!!.spots.size)
        // With the field on, moving a slider changes the preview; letting go puts it on the wall.
        vm.start()
        val lit = awaitState(vm) { it.knot.run.phase == KnotPhase.POSITIONS }.knot.run.field!!
        assertEquals(few.layout!!.spots, lit.spots.keys)
        vm.setKnotHolds(KnotHolds.NORMAL)
        awaitState(vm) { !it.knot.pending && it.knot.holds == KnotHolds.NORMAL }
        assertEquals(lit, vm.state.value.knot.run.field)
        vm.knotSettingsSettled()
        val relit = awaitState(vm) { it.knot.run.field != lit }.knot.run.field!!
        assertEquals(vm.state.value.knot.layout!!.spots, relit.spots.keys)
        assertEquals(lit.colours, relit.colours)
    }

    @Test
    fun knotStartPositionsShowTheLitFieldAndTheNextGameKeepsAwayFromTheOneThatLit() {
        val vm = kilterViewModel(handGrades = kilter.rows.holds.associate { it.placementId to 10.0 + it.y / 10 })
        vm.selectGame(WallGame.KNOT)
        // The grades come in off the main thread; the player count changes once they're there, not at the same time.
        awaitState(vm) { it.knot.drawn && !it.knot.pending }
        vm.setKnotPlayers(2)
        awaitState(vm) { !it.knot.pending && it.knot.drawn && it.knot.layout?.climbers == 2 }
        vm.start()
        val lit = awaitState(vm) { it.knot.run.phase == KnotPhase.POSITIONS }.knot.run.field!!
        // At the start positions the preview is the field on the wall, not already the next game's.
        assertEquals(lit.spots.keys, awaitState(vm) { !it.knot.pending }.knot.layout!!.spots)
        // Letting go of a slider without a change leaves the wall as it is.
        vm.knotSettingsSettled()
        assertEquals(lit, vm.state.value.knot.run.field)
        // A setting changed there goes on the wall, and the next game keeps away from that field, not from the first.
        vm.setKnotHolds(KnotHolds.FEW)
        vm.knotSettingsSettled()
        val relit = awaitState(vm) { it.knot.run.field != lit }.knot.run.field!!
        vm.startKnotCalls()
        awaitState(vm) { it.knot.run.phase == KnotPhase.COUNTDOWN }
        assertEquals(relit.spots.keys, vm.knotLastSpots)
    }

    @Test
    fun knotGoRightAfterASliderStartsOnTheFieldOfThatSlider() {
        val vm = kilterViewModel()
        vm.selectGame(WallGame.KNOT)
        vm.setKnotPlayers(2)
        awaitKnot(vm, 2)
        vm.start()
        val lit = awaitState(vm) { it.knot.run.phase == KnotPhase.POSITIONS }.knot.run.field!!
        // A tap on a stop and Go at once, before the field for that stop is worked out.
        vm.setKnotHolds(KnotHolds.FEW)
        vm.knotSettingsSettled()
        vm.startKnotCalls()
        val started = awaitState(vm) { it.knot.run.phase == KnotPhase.COUNTDOWN }.knot.run.field!!
        val few = Knot.layout(kilter.rows, 2, Knot.colours(BoardBrand.KILTER)!!, 0.0254, holds = KnotHolds.FEW)!!
        assertTrue(few.spots != lit.spots.keys)
        assertEquals(few.spots, started.spots.keys)
        assertEquals(lit.colours, started.colours)
    }

    @Test
    fun knotSpeedCanChangeAtTheStartPositionsButNotOnceTheCallsRun() {
        val vm = kilterViewModel()
        vm.selectGame(WallGame.KNOT)
        awaitKnot(vm)
        vm.setLevel(1)
        vm.start()
        awaitState(vm) { it.knot.run.phase == KnotPhase.POSITIONS }
        // The speed slider shows at the start positions: it has to work there.
        vm.setLevel(4)
        assertEquals(4, vm.state.value.level)
        vm.startKnotCalls()
        assertEquals(Knot.callMs(4), awaitState(vm) { it.knot.run.phase == KnotPhase.COUNTDOWN }.knot.run.callMs)
        vm.setLevel(2)
        assertEquals(4, vm.state.value.level)
    }

    @Test
    fun knotLightsADrawnFieldOnlyWhenTheBoardTakesItElseTheNearestHolds() {
        val colours = Knot.colours(BoardBrand.KILTER)!!
        val grades = kilter.rows.holds.associate { it.placementId to 10.0 + it.y / 10 }
        val nearest = KnotSpacing.entries.flatMap { spacing ->
            KnotHolds.entries.mapNotNull { holds -> Knot.layout(kilter.rows, 2, colours, 0.0254, spacing = spacing, holds = holds) }
        }.toSet()
        // A draw that isn't one of the nearest-hold fields: on this even grid some fall back to them.
        val draw = (1L..64L).map { KnotDraw(it, grades) }.first { Knot.layout(kilter.rows, 2, colours, 0.0254, draw = it) !in nearest }
        fun field(fits: (KnotLayout) -> Boolean) =
            knotField(KnotInput(kilter.rows, 2, colours, 0.0254, KnotSpacing.CLOSE, KnotHolds.NORMAL, emptySet(), draw, fits)).layout!!
        // A board that takes any field lights the drawn one.
        assertEquals(Knot.layout(kilter.rows, 2, colours, 0.0254, draw = draw), field { true })
        // One that would refuse the drawn field (more LEDs than it takes) gets the nearest holds, which it takes.
        assertEquals(Knot.layout(kilter.rows, 2, colours, 0.0254), field { it in nearest })
    }

    @Test
    fun knotIsNotOfferedOnAShinyWallAndPutsMorePlayersThanFitInLine() {
        val vm = viewModel()
        vm.selectGame(WallGame.KNOT)
        val shiny = vm.state.value
        assertNull(shiny.knot.colours)
        assertNull(shiny.knot.layout)
        assertTrue(!shiny.canStart)

        val kilterVm = kilterViewModel()
        kilterVm.selectGame(WallGame.KNOT)
        kilterVm.setKnotPlayers(6)
        val six = awaitKnot(kilterVm, 4)
        assertEquals(6, six.players)
        assertEquals(4, six.atWall)
        // Four at the wall on a 12 ft wide field: the colours again and again.
        assertEquals(4, six.layout!!.climbers)
        assertTrue(six.layout!!.stripes.size > 4)
        assertEquals(six.layout!!.spots.size, six.previewSpots()!!.size)
        kilterVm.setKnotPlayers(12)
        assertEquals(Knot.MAX_PLAYERS, kilterVm.state.value.knot.players)
    }

    @Test
    fun knotCallsTheNextInLineToTheWallWhenSomeoneComesOff() {
        val vm = kilterViewModel()
        vm.selectGame(WallGame.KNOT)
        vm.setKnotPlayers(5)
        awaitKnot(vm, 4)
        vm.start()
        val lit = awaitState(vm) { it.knot.run.phase == KnotPhase.POSITIONS }.knot.run
        assertEquals(4, lit.field!!.climbers)
        assertEquals(listOf(4), lit.line)
        vm.startKnotCalls()
        vm.knotOut(2)
        // The screen's state follows the round's through a collector: wait for it rather than read it at once (the
        // release build after #155 once read it before it caught up).
        val run = awaitState(vm) { it.knot.run.stepIn != null }.knot.run
        assertEquals(4, run.stepIn)
        assertEquals(listOf(0, 1, 4, 3), run.onParts)
        verify { voice.sayToWall(4, any(), any()) }
        // The second tap of a double tap on that row doesn't put the one stepping in out.
        vm.knotOut(4)
        assertTrue(vm.state.value.knot.run.climbers[4].atWall)
        // Nobody left on the wall or in line: the most calls held win, here nobody held one.
        listOf(0, 1, 3).forEach(vm::knotOut)
        SystemClock.sleep(1_100)
        vm.knotOut(4)
        val over = awaitState(vm) { it.knot.run.phase == KnotPhase.FINISHED }.knot.run
        assertNull(over.winner)
        assertTrue(!over.stopped)
    }

    @Test
    fun knotOnAMoonBoardSendsItsThreeColoursAsRoles() {
        coEvery { ble.sendMoonBoardClimb(any(), any(), any()) } returns true
        connectedBrand.value = BoardBrand.MOONBOARD
        val full = moonBoard.copy(rows = WallRows.of(WallGameHolds.moonBoard(moon, emptySet())))
        val vm = viewModel(loader = mockk(relaxed = true) { coEvery { load() } returns WallGameBoardLoad.Ready(full) })
        vm.selectGame(WallGame.KNOT)
        assertEquals(2, vm.state.value.knot.maxClimbers)
        awaitKnot(vm)
        vm.start()
        val field = awaitState(vm) { it.knot.run.phase == KnotPhase.POSITIONS }.knot.run.field!!
        assertEquals(KnotColour.BLUE, field.colours[1])
        coVerify(timeout = 5_000) { ble.sendMoonBoardClimb(Knot.moonBoardFrames(field.spots), moon, MoonBoardLedMode.BELOW) }
    }

    @Test
    fun afterAReconnectTheKnotFieldGoesUpAgain() {
        val vm = kilterViewModel()
        vm.selectGame(WallGame.KNOT)
        awaitKnot(vm)
        vm.start()
        val field = awaitState(vm) { it.knot.run.phase == KnotPhase.POSITIONS }.knot.run.field!!
        val sent = field.spots.entries.sortedBy { it.key }.map { (id, colour) -> id to colour.rgb332 }
        coVerify(timeout = 5_000, exactly = 1) { ble.sendRawLeds(sent, BoardBrand.KILTER, any()) }
        connection.value = ConnectionState.CONNECTING
        connection.value = ConnectionState.CONNECTED
        coVerify(timeout = 5_000, exactly = 2) { ble.sendRawLeds(sent, BoardBrand.KILTER, any()) }
    }

    private fun climb(uuid: String, layoutId: Long, frames: String): ClimbWithStats = mockk(relaxed = true) {
        every { this@mockk.uuid } returns uuid
        every { this@mockk.layoutId } returns layoutId
        every { this@mockk.frames } returns frames
        every { name } returns uuid
        every { boardBrand } returns BoardBrand.SHINYWALL.wireValue
    }
}
