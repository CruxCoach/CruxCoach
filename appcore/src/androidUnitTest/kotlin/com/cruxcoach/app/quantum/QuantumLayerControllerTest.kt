package com.cruxcoach.app.quantum

import com.cruxcoach.app.ble.BoardConnectionUiState
import com.cruxcoach.app.ble.BoardLayerBoardIdentity
import com.cruxcoach.app.ble.BoardSendResult
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.ble.DiscoveredBoard
import com.cruxcoach.app.ble.QuantumCommandFailure
import com.cruxcoach.app.ble.QuantumControllerState
import com.cruxcoach.app.ble.QuantumControllerSyncStatus
import com.cruxcoach.app.sync.FakeKeyValueStore
import com.cruxcoach.app.sync.ImportResult
import com.cruxcoach.app.sync.ImporterFixture
import com.cruxcoach.app.sync.QuantumSnapshots
import com.cruxcoach.app.ui.QuantumLayersScreenModel
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.QuantumActivePlayer
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The layer lamp, rack and removal (Android BoardSendController's Quantum half)
 * against an imported Quantum snapshot and a simulated controller roster.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuantumLayerControllerTest {
    private val fx = ImporterFixture().also {
        assertIs<ImportResult.Imported>(it.importer.importQuantumSnapshot(it.source("q.sqlite3", *QuantumSnapshots.MINIMAL)))
    }
    private val repository = BoardRepositoryImpl(fx.handle.database)
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private val climbUuid = "04d652a3-0099-5af3-9961-183a8b69d376"
    private val vendorRoute = "00112233-4455-6677-8899-aabbccddeeff"
    private val foreignUser = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"
    private val climb = QuantumClimbSnapshot(
        uuid = climbUuid, name = "Clean Room", angle = 40, layoutId = 9103, difficultyAverage = 15.0,
        holds = listOf(BoardHold(3000001, 12), BoardHold(3000002, 13), BoardHold(3000003, 14)),
    )

    /** A controller that applies scoped writes to its roster, like the simulator in QuantumLinkTest. */
    private class FakeLink(model: QuantumBoardModel) : QuantumLink {
        val board = DiscoveredBoard("QB_020000000001", "020000000001", 1, "id-q", -40, BoardBrand.QUANTUM)
        override val connection = MutableStateFlow(
            BoardConnectionUiState(connection = ConnectionState.CONNECTED, connectedBoard = board, quantumModel = model),
        )
        override val controller = MutableStateFlow(
            QuantumControllerState(authoritative = true, syncStatus = QuantumControllerSyncStatus.LIVE, revision = 1),
        )
        val sends = mutableListOf<List<Any>>()
        val removals = mutableListOf<Pair<String, String>>()
        var refuseWith: BoardSendResult? = null

        fun roster(players: List<QuantumActivePlayer>) {
            controller.value = controller.value.copy(players = players, revision = controller.value.revision + 1)
        }

        override suspend fun refresh(): Boolean {
            controller.value = controller.value.copy(revision = controller.value.revision + 1)
            return true
        }

        override suspend fun sendClimb(
            holds: List<BoardHold>, placementToLed: Map<Int, Int>, routeId: String, userId: String, color: Int,
            expectedPlayers: List<QuantumActivePlayer>, expectedBoard: BoardLayerBoardIdentity,
        ): BoardSendResult {
            sends += listOf(routeId, userId, color and 0xffffff, holds.map { placementToLed.getValue(it.placementId) }, expectedBoard)
            refuseWith?.let { return it }
            roster(controller.value.players.filterNot { it.userId == userId } + QuantumActivePlayer(routeId, userId, 0xffff, color and 0xffffff))
            return BoardSendResult.OK
        }

        override suspend fun removeLayer(userId: String, expectedRouteId: String, expectedBoard: BoardLayerBoardIdentity): BoardSendResult {
            removals += userId to expectedRouteId
            roster(controller.value.players.filterNot { it.userId == userId })
            return BoardSendResult.OK
        }
    }

    private val recorded = mutableListOf<QuantumClimbSnapshot>()
    private var activeBoard: Pair<String, Int>? = "quantum" to 9103
    private var allowed = true

    // Not backgroundScope: advanceUntilIdle() stops once only background work is left.
    private val controllerScopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cancelControllers() = controllerScopes.forEach { it.cancel() }

    private fun controller(link: FakeLink) = QuantumLayerController(
        layers = BoardLayerManager(FakeKeyValueStore()),
        link = link,
        boardRepository = repository,
        activeBoard = { activeBoard },
        localManagementAllowed = { allowed },
        recordSent = { recorded += it },
        scope = CoroutineScope(SupervisorJob() + dispatcher).also { controllerScopes += it },
        io = dispatcher,
    )

    @Test
    fun `the lamp lights the climb in the first free layer with its vendor route and confirms it`() = scope.runTest {
        val link = FakeLink(QuantumBoardModel.M)
        val c = controller(link)
        advanceUntilIdle()
        assertEquals(BoardLayerBoardIdentity("quantum:serial:020000000001", 9203), c.layers.state.value.board)

        c.light(climb); advanceUntilIdle()

        val layers = c.layers
        val send = link.sends.single()
        assertEquals(vendorRoute, send[0], "the controller gets the vendor route, not the app uuid")
        assertEquals(layers.identityForSlot(0), send[1])
        assertEquals(0x00ff00, send[2])
        assertEquals(listOf(101, 102, 103), send[3])
        val lit = layers.state.value.layers.single()
        assertEquals(BoardLayerStatus.CONFIRMED, lit.status)
        assertEquals(vendorRoute, lit.confirmedRouteUuid)
        assertEquals(QuantumSendState(success = true), c.send.value)
        assertEquals(listOf(climb), recorded)

        // A second tap keeps the climb in its own layer.
        c.light(climb); advanceUntilIdle()
        assertEquals(2, link.sends.size)
        assertEquals(layers.identityForSlot(0), link.sends[1][1])
        assertEquals(1, layers.state.value.layers.size)
    }

    @Test
    fun `an unknown route from another app blocks the lamp before any write`() = scope.runTest {
        val link = FakeLink(QuantumBoardModel.M)
        link.roster(listOf(QuantumActivePlayer("99999999-0000-4000-8000-000000000000", foreignUser, 100, 0x123456)))
        val c = controller(link)
        advanceUntilIdle()

        c.light(climb); advanceUntilIdle()

        assertTrue(link.sends.isEmpty())
        assertEquals("board_layer_error_external_unknown", c.send.value.error)
        assertFalse(c.send.value.sending)
    }

    @Test
    fun `another app's known route on the same holds is a conflict, resolved through the vendor bridge`() = scope.runTest {
        val link = FakeLink(QuantumBoardModel.M)
        link.roster(listOf(QuantumActivePlayer(vendorRoute, foreignUser, 100, 0x123456)))
        val c = controller(link)
        advanceUntilIdle()
        assertEquals(climb.holds, c.layers.state.value.externalLayers.single().holds)

        c.light(climb); advanceUntilIdle()

        assertTrue(link.sends.isEmpty())
        assertEquals("board_layer_error_shared_hold", c.send.value.error)
    }

    @Test
    fun `a lit layer is removed only through a scoped removal of exactly its route`() = scope.runTest {
        val link = FakeLink(QuantumBoardModel.M)
        val c = controller(link)
        advanceUntilIdle()
        c.light(climb); advanceUntilIdle()

        c.remove(0); advanceUntilIdle()

        assertEquals(listOf(c.layers.identityForSlot(0) to vendorRoute), link.removals)
        assertTrue(c.layers.state.value.layers.isEmpty())
        assertTrue(c.send.value.success)
    }

    @Test
    fun `with no colour picked the lamp uses the layer's own default colour as on Android`() = scope.runTest {
        val link = FakeLink(QuantumBoardModel.M)
        val c = controller(link)
        advanceUntilIdle()
        // Layer 1 holds another climb, staged in magenta (layer 3's default): green is the first free
        // colour (the rack's suggestion), but Android lights layer 2 in its own cyan.
        val other = climb.copy(uuid = "11111111-2222-4333-8444-555555555555", holds = listOf(BoardHold(3000009, 12)))
        c.assign(other, slot = 0, color = 0xFFFF00FF.toInt()); advanceUntilIdle()

        c.light(climb); advanceUntilIdle()

        val send = link.sends.single()
        assertEquals(c.layers.identityForSlot(1), send[1])
        assertEquals(0x00ffff, send[2], "Android buildQuantumLayer: selected ?: existing ?: defaultColor(slot)")
        assertEquals(0xFF00FFFF.toInt(), c.layers.state.value.layers.first { it.slot == 1 }.color)
    }

    @Test
    fun `a staged plan is dropped locally without a board command`() = scope.runTest {
        val link = FakeLink(QuantumBoardModel.M)
        val c = controller(link)
        advanceUntilIdle()
        c.assign(climb, slot = 2, color = null); advanceUntilIdle()
        assertEquals(BoardLayerStatus.PREVIEW, c.layers.state.value.layers.single().status)
        assertEquals(2, c.layers.state.value.layers.single().slot)

        c.remove(2); advanceUntilIdle()

        assertTrue(c.layers.state.value.layers.isEmpty())
        assertTrue(link.removals.isEmpty() && link.sends.isEmpty())
    }

    @Test
    fun `board selection and controller model mismatches refuse before any write`() = scope.runTest {
        activeBoard = "kilter" to 1
        val link = FakeLink(QuantumBoardModel.M)
        val c = controller(link)
        advanceUntilIdle()
        c.light(climb); advanceUntilIdle()
        assertEquals("board_send_error_brand_mismatch", c.send.value.error)

        activeBoard = "quantum" to 9103
        val other = FakeLink(QuantumBoardModel.L)
        val d = controller(other)
        advanceUntilIdle()
        d.light(climb); advanceUntilIdle()
        assertEquals("board_send_error_connected_board_mismatch", d.send.value.error)
        assertTrue(link.sends.isEmpty() && other.sends.isEmpty())
        assertTrue(d.layers.state.value.layers.isEmpty())
    }

    @Test
    fun `a refused activation fails the layer with the controller's reason`() = scope.runTest {
        val link = FakeLink(QuantumBoardModel.M)
        link.refuseWith = BoardSendResult.QUANTUM_NOT_CONFIRMED
        val c = controller(link)
        advanceUntilIdle()
        link.controller.value = link.controller.value.copy(lastFailure = QuantumCommandFailure.BOARD_FULL)

        c.light(climb); advanceUntilIdle()

        assertEquals(BoardLayerStatus.FAILED, c.layers.state.value.layers.single().status)
        assertEquals("board_layer_error_board_full", c.send.value.error)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a joined shared session owns the board and the lamp does nothing`() = scope.runTest {
        allowed = false
        val link = FakeLink(QuantumBoardModel.M)
        val c = controller(link)
        advanceUntilIdle()
        c.light(climb); advanceUntilIdle()
        assertTrue(link.sends.isEmpty())
        assertEquals(QuantumSendState(), c.send.value)
    }

    @Test
    fun `the rack screen loads the climb, recommends layer 1 and shows it lit`() = scope.runTest {
        val link = FakeLink(QuantumBoardModel.M)
        val c = controller(link)
        val screen = QuantumLayersScreenModel(c, repository, { allowed }, dispatcher, dispatcher)
        val watch = screen.watch { }
        screen.setClimb(climbUuid, 40); advanceUntilIdle()
        var s = screen.currentState
        assertTrue(s.hasClimb && s.connected)
        assertEquals("live", s.wallCode)
        assertEquals(0, s.suggestedSlot)
        assertEquals(0xFF00FF00L, s.suggestedArgb)
        assertEquals("", s.suggestionBlockKey)
        assertEquals(listOf("free", "free", "free", "free"), s.rows.map { it.stateCode })
        assertEquals("board_images/quantum/board_9203.png", s.boardImagePaths.first())
        assertEquals(listOf(3000001, 3000002, 3000003), s.climbHolds.map { it.placementId })
        val d1 = com.cruxcoach.app.render.QuantumRender.normalized(QuantumBoardModel.M, 1_000, 1_000)
        assertEquals(d1.x, s.climbHolds.first().x, 1e-6f)
        assertTrue(s.layerHolds.isEmpty())

        screen.light(); advanceUntilIdle()
        s = screen.currentState
        assertEquals("onBoard", s.rows[0].stateCode)
        assertTrue(s.rows[0].isCurrentClimb && s.rows[0].removeIsLive)
        assertEquals("Clean Room", s.rows[0].climbName)
        assertEquals(1, s.activeCount)
        assertTrue(s.success)
        assertTrue(s.suggestionUsesExistingSlot)
        // Lit as layer 1: Android hides the climb's own rings and draws the layer's colour instead.
        assertTrue(s.climbHolds.isEmpty())
        assertEquals(listOf(3000001, 3000002, 3000003), s.layerHolds.map { it.placementId })
        assertTrue(s.layerHolds.all { it.argbs == listOf(0xFF00FF00L) })
        watch.cancel()
        screen.close()
    }

    @Test
    fun `a non quantum climb leaves the rack empty`() = scope.runTest {
        fx.execTarget("INSERT INTO climbs(uuid,layout_id,name,frames,board_brand) VALUES('k1',1,'Kilter',x'01020c','kilter')")
        val c = controller(FakeLink(QuantumBoardModel.M))
        val screen = QuantumLayersScreenModel(c, repository, { true }, dispatcher, dispatcher)
        screen.setClimb("k1", 40); advanceUntilIdle()
        assertFalse(screen.currentState.hasClimb)
        assertEquals("board_send_error_no_led_data", screen.currentState.suggestionBlockKey)
        screen.light(); advanceUntilIdle()
        assertNull(c.send.value.error)
        screen.close()
    }

    // ── Colour picker (Android BoardLayerRack + BoardLayerColorPicker) ──

    private val green = 0xFF00FF00L
    private val cyan = 0xFF00FFFFL
    private val magenta = 0xFFFF00FFL

    @Test
    fun `picking a layer and a colour stages the climb there unsent in that colour`() = scope.runTest {
        val link = FakeLink(QuantumBoardModel.M)
        val c = controller(link)
        val screen = QuantumLayersScreenModel(c, repository, { allowed }, dispatcher, dispatcher)
        screen.setClimb(climbUuid, 40); advanceUntilIdle()
        assertEquals(-1, screen.currentState.selectedSlot)
        assertTrue(screen.currentState.colorChoices.isEmpty())
        assertEquals("", screen.currentState.assignKey)

        screen.selectSlot(2)
        var s = screen.currentState
        assertEquals(2, s.selectedSlot)
        // Android selectBoardLayer: an empty layer starts with its own palette colour.
        assertEquals(magenta, s.selectedArgb)
        assertEquals(listOf(green, cyan, magenta, 0xFFFFFF00L), s.colorChoices.map { it.argb })
        assertEquals(listOf(false, false, true, false), s.colorChoices.map { it.isChosen })
        assertTrue(s.colorChoices.all { it.isAvailable })
        assertEquals("board_layer_add_current", s.assignKey)
        assertTrue(s.canAssign)

        screen.selectColor(cyan)
        assertEquals(cyan, screen.currentState.selectedArgb)
        screen.assignCurrent(); advanceUntilIdle()

        val staged = c.layers.state.value.layers.single()
        assertEquals(2, staged.slot)
        assertEquals(cyan.toInt(), staged.color)
        assertEquals(BoardLayerStatus.PREVIEW, staged.status)
        assertTrue(link.sends.isEmpty(), "assigning is local until a lamp is tapped")
        s = screen.currentState
        assertEquals("planned", s.rows[2].stateCode)
        assertEquals("board_layer_update_current", s.assignKey)

        // Another layer while this climb sits on layer 3: Android refuses to move the identity.
        screen.selectSlot(0)
        s = screen.currentState
        assertEquals(3, s.alreadyAssignedNumber)
        assertFalse(s.canAssign)
        // Cyan now belongs to layer 3, so layer 1 cannot take it.
        assertEquals(listOf(true, false, true, true), s.colorChoices.map { it.isAvailable })
        screen.selectColor(cyan)
        assertEquals(green, screen.currentState.selectedArgb)
        screen.close()
    }

    @Test
    fun `another app's route is read only and its colour cannot be picked`() = scope.runTest {
        val link = FakeLink(QuantumBoardModel.M)
        link.roster(listOf(QuantumActivePlayer("99999999-0000-4000-8000-000000000000", foreignUser, 100, 0x00ffff)))
        val c = controller(link)
        val screen = QuantumLayersScreenModel(c, repository, { allowed }, dispatcher, dispatcher)
        screen.setClimb(climbUuid, 40); advanceUntilIdle()
        assertEquals("foreign", screen.currentState.rows[1].stateCode)

        screen.selectSlot(1)
        assertEquals(-1, screen.currentState.selectedSlot)

        screen.selectSlot(3)
        screen.selectColor(cyan)
        val s = screen.currentState
        assertEquals(0xFFFFFF00L, s.selectedArgb)
        assertFalse(s.colorChoices[1].isAvailable)
        assertFalse(s.selectedColorTaken)
        screen.close()
    }

    @Test
    fun `the recommendation becomes the selection and a picked colour reaches the lamp`() = scope.runTest {
        val link = FakeLink(QuantumBoardModel.M)
        val c = controller(link)
        val screen = QuantumLayersScreenModel(c, repository, { allowed }, dispatcher, dispatcher)
        screen.setClimb(climbUuid, 40); advanceUntilIdle()
        assertFalse(screen.currentState.suggestionSelected)

        screen.selectSuggestion()
        var s = screen.currentState
        assertEquals(0, s.selectedSlot)
        assertEquals(green, s.selectedArgb)
        assertTrue(s.suggestionSelected)
        assertTrue(c.layers.state.value.layers.isEmpty(), "selecting the recommendation stages nothing")

        screen.selectColor(magenta)
        assertFalse(screen.currentState.suggestionSelected)
        screen.light(); advanceUntilIdle()
        assertEquals(0xff00ff, link.sends.single()[2])
        s = screen.currentState
        assertEquals("onBoard", s.rows[0].stateCode)
        assertEquals(magenta, s.rows[0].plannedArgb)
        screen.close()
    }

    @Test
    fun `a joined shared session cannot pick layers or colours`() = scope.runTest {
        allowed = false
        val c = controller(FakeLink(QuantumBoardModel.M))
        val screen = QuantumLayersScreenModel(c, repository, { allowed }, dispatcher, dispatcher)
        screen.setClimb(climbUuid, 40); advanceUntilIdle()
        screen.selectSlot(1)
        screen.selectSuggestion()
        assertEquals(-1, screen.currentState.selectedSlot)
        assertFalse(screen.currentState.canAssign)
        screen.close()
    }

    // ── Playlist projection (Android SessionQueueManager.sendQuantumPlaylistLayer) ──

    private fun playlist(layers: BoardLayerManager, link: FakeLink) =
        QuantumPlaylistProjection(layers, link, repository, dispatcher)

    @Test
    fun `a playlist climb is lit in the first free layer and keeps that layer when sent again`() = scope.runTest {
        val link = FakeLink(QuantumBoardModel.M)
        val layers = BoardLayerManager(FakeKeyValueStore())
        val projection = playlist(layers, link)
        assertTrue(projection.send(climbUuid, "Clean Room", 9103, 40, climb.holds))
        assertEquals(listOf<Any>(vendorRoute, layers.identityForSlot(0), 0x00ff00, listOf(101, 102, 103)), link.sends.single().take(4))
        assertEquals(BoardLayerStatus.CONFIRMED, layers.state.value.layers.single().status)

        assertTrue(projection.send(climbUuid, "Clean Room", 9103, 40, climb.holds))
        assertEquals(layers.identityForSlot(0), link.sends[1][1])
        assertEquals(1, layers.state.value.layers.size)
    }

    @Test
    fun `a playlist never lights a climb of another model or next to an unknown route`() = scope.runTest {
        val other = FakeLink(QuantumBoardModel.L)
        assertFalse(playlist(BoardLayerManager(FakeKeyValueStore()), other).send(climbUuid, "Clean Room", 9103, 40, climb.holds))

        val busy = FakeLink(QuantumBoardModel.M)
        busy.roster(listOf(QuantumActivePlayer("99999999-0000-4000-8000-000000000000", foreignUser, 100, 0x123456)))
        assertFalse(playlist(BoardLayerManager(FakeKeyValueStore()), busy).send(climbUuid, "Clean Room", 9103, 40, climb.holds))
        assertTrue(other.sends.isEmpty() && busy.sends.isEmpty())
    }
}
