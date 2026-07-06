package com.cruxcoach.domain.board

import com.cruxcoach.domain.board.CruxBoardFrameEncoder.AckInfo
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.Opcode
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.RouteHold
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.StateInfo
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.Status
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Connection lifecycle of a [CruxBoardSession].
 *
 * DISCONNECTED → CONNECTING → HANDSHAKING → CONNECTED | AUTH_REQUIRED;
 * AUTH_REQUIRED → CONNECTED after the board acks [Opcode.AUTH] with
 * [Status.OK]. Any close/failure resets to DISCONNECTED.
 */
enum class CruxBoardConnectionState {
    DISCONNECTED,
    CONNECTING,

    /** Socket open, waiting for the board's unsolicited STATE frame. */
    HANDSHAKING,
    CONNECTED,

    /** STATE arrived with `authRequired == 1`; send [CruxBoardSession.buildAuth]. */
    AUTH_REQUIRED,
}

/**
 * Pure, transport-agnostic session state machine for the CruxCoach Board's
 * native WS protocol — the testable "brain" behind the androidApp WebSocket
 * shell. It owns NO I/O: the shell reports transport lifecycle via the
 * `on*` hooks, feeds every received binary message to [onFrame], and writes
 * the `build*` byte arrays to the socket.
 *
 * Wire format is delegated entirely to [CruxBoardFrameEncoder] (the Kotlin
 * mirror of the firmware's `protocol.h`). Malformed frames and unknown
 * opcodes are ignored — this class never throws on board input.
 */
class CruxBoardSession {

    private val _state = MutableStateFlow(CruxBoardConnectionState.DISCONNECTED)

    /** Current connection lifecycle state. */
    val state: StateFlow<CruxBoardConnectionState> = _state.asStateFlow()

    private val _boardState = MutableStateFlow<StateInfo?>(null)

    /** Last decoded STATE frame (board identity/capabilities), null until handshake. */
    val boardState: StateFlow<StateInfo?> = _boardState.asStateFlow()

    private val _angleDeciDeg = MutableStateFlow<Int?>(null)

    /**
     * Latest board angle in deci-degrees from vertical (405 = 40.5° overhang),
     * fed by both STATE and EVENT_ANGLE frames. Null while unknown (no IMU /
     * the `kAngleUnknown` sentinel / not yet reported).
     */
    val angleDeciDeg: StateFlow<Int?> = _angleDeciDeg.asStateFlow()

    private val _lastAck = MutableStateFlow<AckInfo?>(null)

    /** Last ACK received (echoed opcode + status), null until the first one. */
    val lastAck: StateFlow<AckInfo?> = _lastAck.asStateFlow()

    private val _touchEvents = MutableSharedFlow<TouchEvent>(
        extraBufferCapacity = TOUCH_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Hold touch/release broadcasts (EVENT_TOUCH). Hot; no replay. */
    val touchEvents: SharedFlow<TouchEvent> = _touchEvents.asSharedFlow()

    /** One EVENT_TOUCH broadcast: a hold was touched ([touched]) or released. */
    data class TouchEvent(val holdIndex: Int, val touched: Boolean, val boardMillis: Long)

    // ── Transport lifecycle hooks (called by the WS shell) ───────────────

    /** The shell started opening the socket. */
    fun onConnecting() {
        _state.update { CruxBoardConnectionState.CONNECTING }
    }

    /** The socket is open; the board sends an unsolicited STATE next. */
    fun onOpen() {
        _state.update { CruxBoardConnectionState.HANDSHAKING }
    }

    /** The socket closed (either side). Resets all session state. */
    fun onClosed() = reset()

    /** The transport failed. Resets all session state. */
    fun onFailure() = reset()

    // ── Inbound: board → client frames ───────────────────────────────────

    /**
     * Feed one complete binary WS message (header + payload). Dispatches by
     * opcode; anything unparseable or unknown is silently dropped, and frames
     * arriving while DISCONNECTED (post-close races) are ignored.
     */
    fun onFrame(bytes: ByteArray) {
        if (_state.value == CruxBoardConnectionState.DISCONNECTED) return
        val header = CruxBoardFrameEncoder.parseHeader(bytes) ?: return
        val payload = bytes.copyOfRange(CruxBoardFrameEncoder.HEADER_SIZE, bytes.size)
        when (header.opcode) {
            Opcode.STATE -> onStateFrame(payload)
            Opcode.ACK -> onAckFrame(payload)
            Opcode.EVENT_ANGLE -> onAngleFrame(payload)
            Opcode.EVENT_TOUCH -> onTouchFrame(payload)
            else -> Unit // unknown / unhandled opcode: ignore gracefully
        }
    }

    private fun onStateFrame(payload: ByteArray) {
        if (payload.size != CruxBoardFrameEncoder.STATE_PAYLOAD_SIZE) return
        val info = CruxBoardFrameEncoder.decodeState(payload)
        _boardState.update { info }
        _angleDeciDeg.update { info.angleDeciDeg.takeUnless { it == CruxBoardFrameEncoder.ANGLE_UNKNOWN } }
        // Only the handshake STATE decides the target state: later STATEs
        // (PING replies) must not demote an already-authenticated session.
        if (_state.value == CruxBoardConnectionState.HANDSHAKING) {
            _state.update {
                if (info.authRequired) {
                    CruxBoardConnectionState.AUTH_REQUIRED
                } else {
                    CruxBoardConnectionState.CONNECTED
                }
            }
        }
    }

    private fun onAckFrame(payload: ByteArray) {
        if (payload.size != CruxBoardFrameEncoder.ACK_PAYLOAD_SIZE) return
        val ack = CruxBoardFrameEncoder.decodeAck(payload)
        _lastAck.update { ack }
        val authOk = ack.echoedOpcode == Opcode.AUTH && ack.status == Status.OK
        if (authOk && _state.value == CruxBoardConnectionState.AUTH_REQUIRED) {
            _state.update { CruxBoardConnectionState.CONNECTED }
        }
    }

    private fun onAngleFrame(payload: ByteArray) {
        if (payload.size != ANGLE_PAYLOAD_SIZE) return
        val angle = readI16LE(payload, 0)
        _angleDeciDeg.update { angle.takeUnless { it == CruxBoardFrameEncoder.ANGLE_UNKNOWN } }
    }

    private fun onTouchFrame(payload: ByteArray) {
        if (payload.size != TOUCH_PAYLOAD_SIZE) return
        _touchEvents.tryEmit(
            TouchEvent(
                holdIndex = readU16LE(payload, 0),
                touched = payload[2].toInt() != 0,
                boardMillis = readU32LE(payload, 3),
            ),
        )
    }

    // ── Outbound: frame builders for the shell to write ──────────────────

    /** SET_ROUTE frame; an empty list clears the shown route. */
    fun buildRoute(holds: List<RouteHold>): ByteArray = CruxBoardFrameEncoder.encodeSetRoute(holds)

    /** BRIGHTNESS frame, [level] 0..255. */
    fun buildBrightness(level: Int): ByteArray = CruxBoardFrameEncoder.encodeBrightness(level)

    /** CLEAR frame: all LEDs off. */
    fun buildClear(): ByteArray = CruxBoardFrameEncoder.encodeClear()

    /** PING frame; the board answers with STATE. */
    fun buildPing(): ByteArray = CruxBoardFrameEncoder.encodePing()

    /** AUTH frame carrying the UTF-8 [token]. */
    fun buildAuth(token: String): ByteArray = CruxBoardFrameEncoder.encodeAuth(token)

    // ── Internal ─────────────────────────────────────────────────────────

    private fun reset() {
        _state.update { CruxBoardConnectionState.DISCONNECTED }
        _boardState.update { null }
        _angleDeciDeg.update { null }
        _lastAck.update { null }
    }

    private fun readU16LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun readI16LE(buf: ByteArray, offset: Int): Int =
        readU16LE(buf, offset).toShort().toInt()

    private fun readU32LE(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFF) or
            ((buf[offset + 1].toLong() and 0xFF) shl 8) or
            ((buf[offset + 2].toLong() and 0xFF) shl 16) or
            ((buf[offset + 3].toLong() and 0xFF) shl 24)

    private companion object {
        /** EVENT_ANGLE payload: `{ i16 angleDeciDeg }`. */
        const val ANGLE_PAYLOAD_SIZE = 2

        /** EVENT_TOUCH payload: `{ u16 holdIndex, u8 state, u32 boardMillis }`. */
        const val TOUCH_PAYLOAD_SIZE = 7

        /** Touch events buffered for slow collectors before drop-oldest kicks in. */
        const val TOUCH_BUFFER = 64
    }
}
