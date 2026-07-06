package com.cruxcoach.board

import com.cruxcoach.domain.board.CruxBoardConnectionState
import com.cruxcoach.domain.board.CruxBoardFrameEncoder
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.Capability
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.Opcode
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.Role
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.RouteHold
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.Status
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.WifiMode
import com.cruxcoach.domain.board.CruxBoardSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Drives the pure [CruxBoardSession] state machine with hand-built frames
 * matching the firmware's `protocol.h` wire format (little-endian, 4-byte
 * header) and asserts the exposed flows — no transport involved.
 */
class CruxBoardSessionTest {

    private fun ub(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /** Wrap [payload] in a v1 header for [opcode] (LE length). */
    private fun frame(opcode: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = 1
        out[1] = opcode.toByte()
        out[2] = (payload.size and 0xFF).toByte()
        out[3] = ((payload.size ushr 8) and 0xFF).toByte()
        payload.copyInto(out, 4)
        return out
    }

    /** STATE frame mirroring the firmware's test vector (fw 1.4.2, angle 405). */
    private fun stateFrame(authRequired: Int): ByteArray = frame(
        Opcode.STATE,
        ub(
            1,             // protocolVersion
            1, 4, 2,       // fwMajor/Minor/Patch
            25, 0, 0, 0,   // capabilities u32 LE = SET_ROUTE|OTA|IMU
            0x95, 0x01,    // angleDeciDeg i16 LE = 405
            200,           // brightness
            0xC8, 0x00,    // ledCount u16 LE = 200
            0xB4, 0x00,    // holdCount u16 LE = 180
            WifiMode.AP_STA,
            authRequired,
        ),
    )

    /** A session that has completed onConnecting + onOpen (HANDSHAKING). */
    private fun openSession(): CruxBoardSession = CruxBoardSession().apply {
        onConnecting()
        onOpen()
    }

    // ── Lifecycle hooks ──────────────────────────────────────────────────

    @Test
    fun lifecycleHooksAdvanceState() {
        val session = CruxBoardSession()
        assertEquals(CruxBoardConnectionState.DISCONNECTED, session.state.value)
        session.onConnecting()
        assertEquals(CruxBoardConnectionState.CONNECTING, session.state.value)
        session.onOpen()
        assertEquals(CruxBoardConnectionState.HANDSHAKING, session.state.value)
    }

    // ── STATE handshake ──────────────────────────────────────────────────

    @Test
    fun stateFrameWithoutAuthConnects() {
        val session = openSession()
        session.onFrame(stateFrame(authRequired = 0))

        assertEquals(CruxBoardConnectionState.CONNECTED, session.state.value)
        val info = session.boardState.value!!
        assertEquals(1, info.fwMajor)
        assertEquals(4, info.fwMinor)
        assertEquals(180, info.holdCount)
        assertEquals(WifiMode.AP_STA, info.wifiMode)
        assertTrue(info.hasCapability(Capability.IMU))
        // STATE also seeds the angle flow.
        assertEquals(405, session.angleDeciDeg.value)
    }

    @Test
    fun stateFrameWithAuthRequiredParksInAuthRequired() {
        val session = openSession()
        session.onFrame(stateFrame(authRequired = 1))

        assertEquals(CruxBoardConnectionState.AUTH_REQUIRED, session.state.value)
        assertTrue(session.boardState.value!!.authRequired)
    }

    @Test
    fun authOkAckPromotesToConnected() {
        val session = openSession()
        session.onFrame(stateFrame(authRequired = 1))
        // Board acks OP_AUTH with ST_OK (ws_protocol.cpp) → authenticated.
        session.onFrame(frame(Opcode.ACK, ub(Opcode.AUTH, Status.OK)))

        assertEquals(CruxBoardConnectionState.CONNECTED, session.state.value)
        assertEquals(Opcode.AUTH, session.lastAck.value!!.echoedOpcode)
        assertEquals(Status.OK, session.lastAck.value!!.status)
    }

    @Test
    fun laterStateFrameDoesNotDemoteConnectedSession() {
        val session = openSession()
        session.onFrame(stateFrame(authRequired = 1))
        session.onFrame(frame(Opcode.ACK, ub(Opcode.AUTH, Status.OK)))
        // A PING reply still reports authRequired=1 (board config, not
        // per-connection status) — must stay CONNECTED.
        session.onFrame(stateFrame(authRequired = 1))
        assertEquals(CruxBoardConnectionState.CONNECTED, session.state.value)
    }

    // ── EVENT_ANGLE ──────────────────────────────────────────────────────

    @Test
    fun angleEventUpdatesAngleFlow() {
        val session = openSession()
        session.onFrame(frame(Opcode.EVENT_ANGLE, ub(0x95, 0x01))) // 405
        assertEquals(405, session.angleDeciDeg.value)
    }

    @Test
    fun angleUnknownSentinelMapsToNull() {
        val session = openSession()
        session.onFrame(frame(Opcode.EVENT_ANGLE, ub(0x95, 0x01)))
        session.onFrame(frame(Opcode.EVENT_ANGLE, ub(0x00, 0x80))) // kAngleUnknown
        assertNull(session.angleDeciDeg.value)
    }

    // ── EVENT_TOUCH ──────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun touchEventIsEmittedWithDecodedFields() = runTest {
        val session = openSession()
        val events = mutableListOf<CruxBoardSession.TouchEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            session.touchEvents.collect { events.add(it) }
        }

        // holdIndex=300 (2C 01), touched=1, boardMillis=123456 (40 E2 01 00).
        session.onFrame(frame(Opcode.EVENT_TOUCH, ub(0x2C, 0x01, 1, 0x40, 0xE2, 0x01, 0x00)))
        // Release of hold 2 at boardMillis=5.
        session.onFrame(frame(Opcode.EVENT_TOUCH, ub(0x02, 0x00, 0, 0x05, 0x00, 0x00, 0x00)))

        assertEquals(
            listOf(
                CruxBoardSession.TouchEvent(holdIndex = 300, touched = true, boardMillis = 123456L),
                CruxBoardSession.TouchEvent(holdIndex = 2, touched = false, boardMillis = 5L),
            ),
            events,
        )
        collector.cancel()
    }

    // ── Disconnect / reset ───────────────────────────────────────────────

    @Test
    fun onClosedResetsAllSessionState() {
        val session = openSession()
        session.onFrame(stateFrame(authRequired = 0))
        session.onFrame(frame(Opcode.EVENT_ANGLE, ub(0x95, 0x01)))

        session.onClosed()

        assertEquals(CruxBoardConnectionState.DISCONNECTED, session.state.value)
        assertNull(session.boardState.value)
        assertNull(session.angleDeciDeg.value)
        assertNull(session.lastAck.value)
    }

    @Test
    fun framesAfterCloseAreIgnored() {
        val session = openSession()
        session.onClosed()
        session.onFrame(stateFrame(authRequired = 0))
        assertEquals(CruxBoardConnectionState.DISCONNECTED, session.state.value)
        assertNull(session.boardState.value)
    }

    // ── Robustness: garbage in, no crash ─────────────────────────────────

    @Test
    fun malformedAndUnknownFramesAreIgnored() {
        val session = openSession()
        session.onFrame(ByteArray(0))                    // empty
        session.onFrame(ub(1, Opcode.STATE))             // short buffer
        session.onFrame(ub(99, Opcode.STATE, 0, 0))      // wrong protocol version
        session.onFrame(frame(0x7F, ub(1, 2, 3)))        // unknown opcode
        session.onFrame(frame(Opcode.STATE, ub(1, 2)))   // STATE with bad payload size
        session.onFrame(frame(Opcode.EVENT_ANGLE, ub(1))) // truncated angle
        session.onFrame(frame(Opcode.ACK, ub(1)))        // truncated ack

        assertEquals(CruxBoardConnectionState.HANDSHAKING, session.state.value)
        assertNull(session.boardState.value)
        assertNull(session.angleDeciDeg.value)
        assertNull(session.lastAck.value)
    }

    // ── Outbound builders delegate to the encoder ────────────────────────

    @Test
    fun buildersAreByteIdenticalToEncoder() {
        val session = CruxBoardSession()
        val holds = listOf(
            RouteHold(2, Role.START),
            RouteHold(300, Role.HAND),
            RouteHold(77, Role.FINISH),
        )
        assertContentEquals(CruxBoardFrameEncoder.encodeSetRoute(holds), session.buildRoute(holds))
        assertContentEquals(CruxBoardFrameEncoder.encodeBrightness(180), session.buildBrightness(180))
        assertContentEquals(CruxBoardFrameEncoder.encodeClear(), session.buildClear())
        assertContentEquals(CruxBoardFrameEncoder.encodePing(), session.buildPing())
        assertContentEquals(CruxBoardFrameEncoder.encodeAuth("secret"), session.buildAuth("secret"))
    }
}
