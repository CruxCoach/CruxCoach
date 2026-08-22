package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a climb can reach a board, and what to say when it cannot.
 *
 * The middle of the dock used to ask "does this phone hold a BLE link", which
 * is the wrong question: a member on somebody else's controller has a perfectly
 * good path and no link of their own, and telling them to turn Bluetooth on was
 * telling them to fix something that was not broken.
 */
class BoardReachabilityPolicyTest {

    private fun resolve(
        connectionState: ConnectionState = ConnectionState.DISCONNECTED,
        mesh: Boolean = false,
        relay: Boolean = false,
        bluetooth: Boolean = true,
        permission: Boolean = true,
        seenBoard: Boolean = false,
    ) = BoardReachabilityPolicy.resolve(
        connectionState = connectionState,
        connectedViaMesh = mesh,
        connectedViaRelay = relay,
        bluetoothEnabled = bluetooth,
        hasBluetoothPermission = permission,
        hasEverSeenBoard = seenBoard,
    )

    @Test
    fun `a direct link is the plain case`() {
        assertEquals(BoardReachability.DIRECT, resolve(connectionState = ConnectionState.CONNECTED))
        assertTrue(BoardReachability.DIRECT.canReachBoard)
        assertFalse("nothing worth badging", BoardReachability.DIRECT.carriesBadge)
    }

    @Test
    fun `a send in flight is still a reachable board`() {
        assertEquals(BoardReachability.DIRECT, resolve(connectionState = ConnectionState.SENDING))
    }

    /** The finding this whole model exists for. */
    @Test
    fun `no local Bluetooth is not a problem when the mesh has the board`() {
        val reachability = resolve(
            connectionState = ConnectionState.DISCONNECTED,
            mesh = true,
            bluetooth = false,
            permission = false,
        )

        assertEquals(BoardReachability.MESH, reachability)
        assertTrue(reachability.canReachBoard)
        assertTrue("worth naming, so the lamp carries a badge", reachability.carriesBadge)
    }

    @Test
    fun `a relay path is a path, and says so`() {
        val reachability = resolve(connectionState = ConnectionState.CONNECTED, relay = true)

        assertEquals(BoardReachability.RELAY, reachability)
        assertTrue(reachability.canReachBoard)
        assertTrue(reachability.carriesBadge)
    }

    @Test
    fun `connecting is its own answer, not a failure`() {
        assertEquals(
            BoardReachability.CONNECTING,
            resolve(connectionState = ConnectionState.CONNECTING),
        )
        assertFalse(BoardReachability.CONNECTING.canReachBoard)
    }

    @Test
    fun `the blockers are told apart so the button can name them`() {
        assertEquals(BoardReachability.PERMISSION_MISSING, resolve(permission = false))
        assertEquals(BoardReachability.BLUETOOTH_OFF, resolve(bluetooth = false))
        assertEquals(BoardReachability.NO_BOARD, resolve(seenBoard = false))
        assertEquals(BoardReachability.UNREACHABLE, resolve(seenBoard = true))
    }

    /** Permission first: it is the one the user cannot discover on their own. */
    @Test
    fun `a missing permission outranks a disabled adapter`() {
        assertEquals(
            BoardReachability.PERMISSION_MISSING,
            resolve(permission = false, bluetooth = false),
        )
    }

    @Test
    fun `every blocker reports itself as unreachable`() {
        listOf(
            BoardReachability.CONNECTING,
            BoardReachability.BLUETOOTH_OFF,
            BoardReachability.PERMISSION_MISSING,
            BoardReachability.NO_BOARD,
            BoardReachability.UNREACHABLE,
        ).forEach { assertFalse(it.name, it.canReachBoard) }
    }

    @Test
    fun `a board action without a projection path uses connect not a lamp`() {
        assertEquals(
            BoardActionVisual.CONNECT,
            BoardActionVisualPolicy.resolve(sendCapable = false),
        )
    }

    @Test
    fun `a board action in progress uses connecting until it is send capable`() {
        assertEquals(
            BoardActionVisual.CONNECTING,
            BoardActionVisualPolicy.resolve(sendCapable = false, connecting = true),
        )
        assertEquals(
            BoardActionVisual.LAMP,
            BoardActionVisualPolicy.resolve(sendCapable = true, connecting = true),
        )
    }
}
