package com.cruxcoach.android.ble

import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import com.cruxcoach.android.data.BoardStateManager
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.CruxRelayState
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.ui.paintbox.PaintBoard
import com.cruxcoach.android.ui.paintbox.PaintDocument
import com.cruxcoach.android.ui.paintbox.PaintPalette
import com.cruxcoach.android.ui.paintbox.PaintProjector
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.QuantumActivePlayer
import com.cruxcoach.domain.board.QuantumBoardModel
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Quantum play, answer and removal path against a controller behind a fake
 * GATT. Like the real XL, the fake leaves its answer to every command in fff4
 * and never notifies on fff1. It can leave played routes out of its route
 * list, and refuse a route with a controller error code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class QuantumPlayPathTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun clearIdentity() {
        context.getSharedPreferences("board_layer_identity", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class FakeController(context: Context) {
        val layers = BoardLayerManager(context)
        val connection = BoardBleConnection(context, ownsQuantumUserId = layers::ownsIdentity)
        val board = DiscoveredBoard("QB_A1B2C3D4E5F6", "A1B2C3D4E5F6", 1, "AA:BB:CC:DD:EE:FF", -40, BoardBrand.QUANTUM)
        val identity = BoardLayerBoardIdentity(PhysicalBoardIdentity.resolve(board).value, QuantumBoardModel.XL.productSizeId)

        /** Every GATT operation in order: "write 44", "read fff4". */
        val operations = mutableListOf<String>()
        val writes = mutableListOf<ByteArray>()
        val players = mutableListOf<QuantumActivePlayer>()
        /** Users whose routes the route list leaves out. */
        val unlisted = mutableSetOf<String>()
        var listsPlayedRoutes = true
        var refusePlayWithCode: Int? = null
        /** After a played route, route list requests get no answer. */
        var silentAfterPlay = false
        private var played = false
        /** fff4 reads that still return the value from before a write. */
        var staleReadsAfterWrite = 0
        private var staleReads = 0
        private var previousFff4 = byteArrayOf(1, 0x47, 0, 0)
        /** TURN_OFF_USER is acknowledged but the route stays on. */
        var ignoresTurnOff = false
        /** TURN_OFF_USER for a user without a route is refused (code 3). */
        var refusesTurnOffWithoutRoute = false
        private var fff4 = byteArrayOf(1, 0x47, 0, 0)

        private val gatt = mockk<BluetoothGatt>(relaxed = true)
        private val write = BluetoothGattCharacteristic(BoardBleUuids.QUANTUM_WRITE_CHAR, 8, 16)
        private val state = BluetoothGattCharacteristic(BoardBleUuids.QUANTUM_STATE_CHAR, 2, 1)
        private val callback: BluetoothGattCallback

        init {
            get<CoroutineScope>("scope").cancel()
            set("currentBoard", board)
            get<MutableStateFlow<DiscoveredBoard?>>("_connectedBoardDescriptor").value = board
            set("gatt", gatt)
            set("writeCharacteristic", write)
            set("negotiatedMtu", 512)
            set("quantumPlayMinutes", 15)
            get<MutableStateFlow<ConnectionState>>("_connectionState").value = ConnectionState.CONNECTED
            get<MutableStateFlow<BoardBrand?>>("_connectedBoardBrand").value = BoardBrand.QUANTUM
            get<MutableStateFlow<QuantumBoardModel?>>("_connectedQuantumModel").value = QuantumBoardModel.XL
            callback = get("gattCallback")
            val service = mockk<BluetoothGattService> {
                every { getCharacteristic(BoardBleUuids.QUANTUM_STATE_CHAR) } returns state
                every { getCharacteristic(BoardBleUuids.QUANTUM_WRITE_CHAR) } returns write
            }
            every { gatt.getService(BoardBleUuids.QUANTUM_SERVICE) } returns service
            every { gatt.writeCharacteristic(any()) } answers {
                val frame = write.value.copyOf()
                writes += frame
                operations += "write %02x".format(frame[1].toInt() and 0xff)
                answer(frame)
                callback.onCharacteristicWrite(gatt, write, BluetoothGatt.GATT_SUCCESS)
                true
            }
            every { gatt.readCharacteristic(state) } answers {
                operations += "read fff4"
                state.value = if (staleReads > 0) previousFff4.copyOf().also { staleReads-- } else fff4.copyOf()
                @Suppress("DEPRECATION")
                callback.onCharacteristicRead(gatt, state, BluetoothGatt.GATT_SUCCESS)
                true
            }
            layers.bindBoard(identity)
        }

        private fun answer(frame: ByteArray) {
            val command = frame[1].toInt() and 0xff
            if (command == 0x47 && silentAfterPlay && played) return
            if (command == 0x44) played = true
            previousFff4 = fff4
            staleReads = staleReadsAfterWrite
            fff4 = when (command) {
                0x47 -> routeList(0x47)
                0x44 -> refusePlayWithCode?.let { byteArrayOf(1, 0xc4.toByte(), it.toByte()) } ?: run {
                    val user = uuid(frame, 18)
                    players.removeAll { it.userId == user }
                    val color = ((frame[34].toInt() and 0xff) shl 16) or ((frame[35].toInt() and 0xff) shl 8) or (frame[36].toInt() and 0xff)
                    val minutes = ((frame[37].toInt() and 0xff) shl 8) or (frame[38].toInt() and 0xff)
                    players += QuantumActivePlayer(uuid(frame, 2), user, minutes * 60, color)
                    if (!listsPlayedRoutes) unlisted += user
                    routeList(0x44)
                }
                0x43 -> if (refusesTurnOffWithoutRoute && players.none { it.userId == uuid(frame, 2) }) {
                    byteArrayOf(1, 0xc3.toByte(), 3)
                } else {
                    val user = uuid(frame, 2)
                    if (!ignoresTurnOff) {
                        players.removeAll { it.userId == user }
                        unlisted -= user
                    }
                    frame.copyOf(21)
                }
                else -> byteArrayOf(1, (0x80 or command).toByte(), 1)
            }
        }

        private fun routeList(command: Int): ByteArray {
            val listed = players.filterNot { it.userId in unlisted }
            return byteArrayOf(1, command.toByte(), listed.size.toByte(), 0) + listed.flatMap { player ->
                (QuantumBoardPacketEncoder.uuidBytes(player.routeId) + QuantumBoardPacketEncoder.uuidBytes(player.userId) +
                    byteArrayOf((player.remainingSeconds ushr 8).toByte(), player.remainingSeconds.toByte()) +
                    byteArrayOf((player.color ushr 16).toByte(), (player.color ushr 8).toByte(), player.color.toByte())).toList()
            }
        }

        private fun uuid(bytes: ByteArray, offset: Int): String {
            val hex = (offset until offset + 16).joinToString("") { "%02x".format(bytes[it].toInt() and 0xff) }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }

        fun commands(): List<Int> = writes.map { it[1].toInt() and 0xff }

        suspend fun play(slot: Int, route: String, color: Int = 0x00ff00, diodes: List<Int> = listOf(2010, 3012, 1046)): Boolean =
            connection.sendClimb(
                holds = diodes.map { BoardHold(it, HoldRole.HAND) },
                placementToLed = diodes.associateWith { it },
                routeId = route,
                quantumUserId = layers.identityForSlot(slot),
                quantumColor = color,
                expectedQuantumPlayers = connection.quantumControllerState.value.players,
                expectedQuantumBoard = identity,
                expectedBrand = BoardBrand.QUANTUM,
            )

        private fun set(name: String, value: Any) {
            BoardBleConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.set(connection, value)
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> get(name: String): T =
            BoardBleConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.get(connection) as T
    }

    private val route = "d44b3cc3-ab86-4ed5-8509-640b252a17c2"

    @Test fun `a climb is played as one BOARD_SWIPE for the board's play time`() = runTest {
        val board = FakeController(context)
        assertTrue(board.play(slot = 0, route = route))

        // Route list before (the conflict check), the play frame, its answer, then the list.
        assertEquals(listOf(0x47, 0x44, 0x47), board.commands())
        val play = board.writes[1]
        assertArrayEquals(
            QuantumBoardPacketEncoder.play(route, board.layers.identityForSlot(0), listOf(2010, 3012, 1046), 0x00ff00, minutes = 15),
            play,
        )
        assertArrayEquals(byteArrayOf(0, 15), play.copyOfRange(37, 39))
        assertNull("the board lists it", board.connection.quantumUnlistedUntil(route, board.layers.identityForSlot(0), 0x00ff00))
    }

    @Test fun `a route the board accepts but does not list counts as sent`() = runTest {
        val board = FakeController(context).apply { listsPlayedRoutes = false }
        assertTrue(board.play(slot = 0, route = route))
        assertEquals(listOf(0x47, 0x44, 0x47), board.commands())
        assertTrue(board.connection.quantumControllerState.value.players.isEmpty())
        val until = board.connection.quantumUnlistedUntil(route, board.layers.identityForSlot(0), 0x00ff00, nowElapsedMs = 1_000)
        assertEquals("lit for the play time", 1_000 + 15 * 60_000L, until)
        assertEquals(ConnectionState.CONNECTED, board.connection.connectionState.value)
        assertNotNull("another route is not this one", board.connection.quantumUnlistedUntil(route, board.layers.identityForSlot(1), 0x00ff00))
    }

    @Test fun `a second climb on the same layer replaces the first without TURN_OFF_USER`() = runTest {
        val board = FakeController(context)
        assertTrue(board.play(slot = 1, route = route))
        assertTrue(board.play(slot = 1, route = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", diodes = listOf(4013, 4031)))
        assertFalse(0x43 in board.commands())
        assertFalse(0x41 in board.commands())
        assertEquals(listOf("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"), board.connection.quantumControllerState.value.players.map { it.routeId })
    }

    @Test fun `the board's refusal is read before the route list and reaches the user`() = runTest {
        val board = FakeController(context).apply { refusePlayWithCode = 7 }
        assertFalse(board.play(slot = 0, route = route))
        assertEquals(QuantumCommandFailure.COLOR_TAKEN, board.connection.quantumControllerState.value.lastFailure)
        // The answer to 0x44 is read right after it; no route list request overwrites it.
        assertEquals(listOf(0x47, 0x44), board.commands())
        val afterPlay = board.operations.drop(board.operations.indexOf("write 44") + 1)
        assertEquals(listOf("read fff4"), afterPlay)
    }

    @Test fun `removing a layer always sends TURN_OFF_USER, also for a route the list leaves out`() = runTest {
        val board = FakeController(context).apply { listsPlayedRoutes = false }
        val user = board.layers.identityForSlot(2)
        assertTrue(board.play(slot = 2, route = route))
        assertTrue(board.connection.quantumControllerState.value.players.none { it.userId == user })

        assertTrue(board.connection.removeQuantumLayer(user, board.identity))
        val off = board.writes.last { (it[1].toInt() and 0xff) == 0x43 }
        assertArrayEquals(QuantumBoardPacketEncoder.turnOffUser(user), off)
        assertTrue("the route is off on the board", board.players.none { it.userId == user })
    }

    @Test fun `removal refuses anyone else's identity`() = runTest {
        val board = FakeController(context)
        assertFalse(board.connection.removeQuantumLayer("11111111-2222-4333-8444-555555555555", board.identity))
        assertFalse(board.connection.removeQuantumLayer(QuantumBoardPacketEncoder.ZERO_UUID, board.identity))
        assertTrue(board.writes.isEmpty())
    }

    @Test fun `a route list that stays silent after a send does not fail the send`() = runTest {
        // After the play the board stops answering: fff4 keeps the play answer, never a route list.
        val board = FakeController(context).apply { silentAfterPlay = true }
        assertTrue(board.play(slot = 0, route = route))
        assertEquals(listOf(0x47, 0x44, 0x47), board.commands())
        assertTrue("fff4 was read again while waiting", board.operations.count { it == "read fff4" } > 3)
        // The play answer named the route, but no route list did.
        assertNotNull(board.connection.quantumUnlistedUntil(route, board.layers.identityForSlot(0), 0x00ff00))
    }

    @Test fun `a refusal that reaches fff4 late is still a refusal`() = runTest {
        val board = FakeController(context).apply {
            refusePlayWithCode = 9
            staleReadsAfterWrite = 2
        }
        assertFalse(board.play(slot = 0, route = route))
        assertEquals(QuantumCommandFailure.BOARD_FULL, board.connection.quantumControllerState.value.lastFailure)
        assertEquals(listOf(0x47, 0x44), board.commands())
        val afterPlay = board.operations.drop(board.operations.indexOf("write 44") + 1)
        assertEquals(listOf("read fff4", "read fff4", "read fff4"), afterPlay)
    }

    @Test fun `a refusal that reaches fff4 only while the route list is read still fails the send`() = runTest {
        val board = FakeController(context).apply {
            refusePlayWithCode = 7
            staleReadsAfterWrite = 5
        }
        assertFalse(board.play(slot = 0, route = route))
        assertEquals(QuantumCommandFailure.COLOR_TAKEN, board.connection.quantumControllerState.value.lastFailure)
        assertEquals(listOf(0x47, 0x44, 0x47), board.commands())
    }

    @Test fun `PaintBox colours past their play time do not block the next climb`() =
        paintPastPlayTime(staleReads = 0)

    @Test fun `PaintBox colours past their play time do not block the next climb when fff4 updates late`() =
        paintPastPlayTime(staleReads = 2)

    private fun paintPastPlayTime(staleReads: Int) = runTest {
        val board = FakeController(context).apply {
            listsPlayedRoutes = false
            refusesTurnOffWithoutRoute = true
            staleReadsAfterWrite = staleReads
        }
        val paint = (0..2).map { board.layers.paintIdentity(it) }
        paint.forEachIndexed { index, user ->
            board.layers.markPaintLit(user)
            assertTrue(board.connection.sendClimb(
                holds = listOf(BoardHold(1001 + index, HoldRole.HAND)), placementToLed = mapOf(1001 + index to 1001 + index),
                routeId = "abababab-cdcd-4fef-8101-23232323232$index", quantumUserId = user, quantumColor = 0x00ff00 shl index,
                expectedQuantumPlayers = emptyList(), expectedQuantumBoard = board.identity, expectedBrand = BoardBrand.QUANTUM,
            ))
        }
        // A colour marked lit whose write never happened.
        board.layers.markPaintLit(board.layers.paintIdentity(3))
        // The board turned the picture off by itself; TURN_OFF_USER for those users is now refused.
        board.players.clear()

        assertEquals(4, board.connection.removePaintBoxLayers(board.layers, board.identity))
        assertTrue(board.layers.state.value.litPaintUsers.isEmpty())
        assertEquals(4, board.writes.count { (it[1].toInt() and 0xff) == 0x43 })
        assertTrue(board.play(slot = 0, route = route))
    }

    @Test fun `an answer that never comes is no refusal`() = runTest {
        val board = FakeController(context).apply { staleReadsAfterWrite = 100 }
        assertTrue(board.play(slot = 0, route = route))
    }

    @Test fun `a removal the route list contradicts is not done`() = runTest {
        val board = FakeController(context).apply { ignoresTurnOff = true }
        val user = board.layers.identityForSlot(0)
        assertTrue(board.play(slot = 0, route = route))
        assertFalse(board.connection.removeQuantumLayer(user, board.identity))
        assertTrue(0x43 in board.commands())
    }

    @Test fun `PaintBox lights every colour, also on a board that lists none of them`() = runTest {
        val board = FakeController(context).apply { listsPlayedRoutes = false }
        val sessions = mockk<SessionQueueManager> { every { state } returns MutableStateFlow(SessionQueueState()) }
        val relay = mockk<CruxRelayManager> { every { state } returns MutableStateFlow(CruxRelayState()) }
        val boardState = mockk<BoardStateManager>(relaxed = true) { every { lastClimb } returns MutableStateFlow(null) }
        val projector = PaintProjector(board.connection, board.layers, mockk<UserPreferences>(relaxed = true), sessions, relay,
            mockk<ClimbBleAdvertiser>(relaxed = true), boardState)
        val colors = PaintPalette.quantum.take(3)
        val picture = PaintDocument(
            board = PaintBoard("quantum", QuantumBoardModel.XL.layoutId.toInt(), QuantumBoardModel.XL.productSizeId.toInt()),
            colors = mapOf(1001 to colors[0], 1002 to colors[0], 2001 to colors[1], 3001 to colors[2]),
        )
        val ledMap = picture.colors.keys.associateWith { it }

        assertTrue(projector.send(picture, ledMap) { true })
        val plays = board.writes.filter { (it[1].toInt() and 0xff) == 0x44 }
        assertEquals(3, plays.size)
        assertEquals(3, board.players.size)
        assertEquals(colors.map(PaintPalette::rgb).toSet(), board.players.map { it.color }.toSet())
        assertTrue(board.players.all { board.layers.ownsPaintIdentity(it.userId) })

        // The next picture first turns off every colour this phone lit, though the list names none.
        board.writes.clear()
        assertTrue(projector.send(picture.copy(colors = mapOf(1001 to colors[1])), mapOf(1001 to 1001)) { true })
        val offs = board.writes.filter { (it[1].toInt() and 0xff) == 0x43 }
        assertEquals(3, offs.size)
        assertTrue(board.writes.indexOfFirst { (it[1].toInt() and 0xff) == 0x44 } > board.writes.indexOfLast { (it[1].toInt() and 0xff) == 0x43 })
        assertEquals(listOf(PaintPalette.rgb(colors[1])), board.players.map { it.color })
    }

    @Test fun `a climb takes down a picture the list does not name`() = runTest {
        val board = FakeController(context).apply { listsPlayedRoutes = false }
        val paintUser = board.layers.paintIdentity(0)
        assertTrue(board.connection.sendClimb(
            holds = listOf(BoardHold(1001, HoldRole.HAND)), placementToLed = mapOf(1001 to 1001),
            routeId = "abababab-cdcd-4fef-8101-232323232323", quantumUserId = paintUser, quantumColor = 0x00ff00,
            expectedQuantumPlayers = emptyList(), expectedQuantumBoard = board.identity, expectedBrand = BoardBrand.QUANTUM,
        ))
        board.layers.markPaintLit(paintUser)
        assertEquals(1, board.connection.removePaintBoxLayers(board.layers, board.identity))
        assertTrue(board.players.isEmpty())
        assertTrue(board.layers.state.value.litPaintUsers.isEmpty())
    }
}
