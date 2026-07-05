package com.cruxcoach.domain.board

/**
 * Encoder / decoder for the CruxCoach Board's native L1 wire protocol
 * (the [BoardBrand.CRUXCOACH] board). Pure Kotlin — JVM/Android testable,
 * no transport dependency. The L0 transport (a WebSocket `/ws` binary
 * channel, one WS message == one frame) lives in the androidApp layer;
 * this object owns only the byte format.
 *
 * This is the Kotlin counterpart of the firmware's `protocol.h` and must
 * stay byte-identical with it. Every integer is **little-endian**; the
 * frame header is 4 bytes:
 *
 *     offset 0  u8   protocol version (== [PROTOCOL_VERSION])
 *     offset 1  u8   opcode ([Opcode])
 *     offset 2  u16  payload length in bytes (little-endian)
 *     offset 4  ...  payload
 *
 * L2 note: [Opcode.SET_ROUTE] addresses HOLDS (holdIndex), never LEDs — the
 * board resolves holdIndex → ledIndex via its own hold map, and role → colour
 * via a lookup table. Unlike the Aurora family this board has no CruxCoach-side
 * placement→LED map, which is why [BoardBrand.usesAuroraPlacements] is false.
 */
object CruxBoardFrameEncoder {

    /** L1 protocol version (`crux::kProtocolVersion`). */
    const val PROTOCOL_VERSION: Int = 1

    /** Frame header size in bytes (`crux::kHeaderSize`). */
    const val HEADER_SIZE: Int = 4

    /** Upper bound the board accepts for a single frame's payload
     *  (`crux::kMaxPayload`), sized for a 4 KiB OTA chunk plus slack. */
    const val MAX_PAYLOAD: Int = 4200

    /** Bytes per [Opcode.SET_ROUTE] entry — `{ u16 holdIndex, u8 role }`. */
    const val ROUTE_ENTRY_SIZE: Int = 3

    /** STATE payload size in bytes (`crux::kStatePayloadSize`). */
    const val STATE_PAYLOAD_SIZE: Int = 17

    /** ACK payload size in bytes — `{ u8 echoedOpcode, u8 status }`. */
    const val ACK_PAYLOAD_SIZE: Int = 2

    // ── Opcodes (crux::Opcode) ───────────────────────────────────────────
    object Opcode {
        const val SET_ROUTE: Int = 0x01
        const val RAW_FRAME: Int = 0x02
        const val EFFECT: Int = 0x03
        const val BRIGHTNESS: Int = 0x04
        const val CLEAR: Int = 0x05
        const val PING: Int = 0x06
        const val AUTH: Int = 0x07
        const val OTA_BEGIN: Int = 0x10
        const val OTA_CHUNK: Int = 0x11
        const val OTA_END: Int = 0x12
        const val STATE: Int = 0x80
        const val ACK: Int = 0x81
        const val EVENT_TOUCH: Int = 0x82
        const val EVENT_ANGLE: Int = 0x83
        const val OTA_STATUS: Int = 0x84
    }

    // ── ACK / OTA_STATUS status codes (crux::Status) ─────────────────────
    object Status {
        const val OK: Int = 0
        const val ERR_UNSUPPORTED: Int = 1
        const val ERR_MALFORMED: Int = 2
        const val ERR_UNAUTHORIZED: Int = 3
        const val ERR_BUSY: Int = 4
        const val ERR_RANGE: Int = 5
        const val ERR_INTERNAL: Int = 6
    }

    // ── Built-in effect ids (crux::Effect) ───────────────────────────────
    object Effect {
        const val NONE: Int = 0
        const val RAINBOW: Int = 1
        const val PULSE: Int = 2
    }

    // ── WiFi mode reported in STATE (crux::WifiMode) ─────────────────────
    object WifiMode {
        const val AP_ONLY: Int = 0
        const val STA_ONLY: Int = 1
        const val AP_STA: Int = 2
    }

    /** Sentinel for "angle unknown" (IMU absent / not settled), `crux::kAngleUnknown`. */
    const val ANGLE_UNKNOWN: Int = Short.MIN_VALUE.toInt()

    /**
     * L1 hold-role bytes (`crux::Role`) and the translation from CruxCoach
     * stored-frame role ids. NOTE the L1 numbering is START/HAND/**FOOT**/FINISH,
     * so FOOT (2) precedes FINISH (3) — the stored ids (…FINISH, FOOT) are NOT
     * in the same order and must be mapped explicitly.
     */
    object Role {
        const val START: Int = 0
        const val HAND: Int = 1
        const val FOOT: Int = 2
        const val FINISH: Int = 3

        /**
         * Translate a CruxCoach stored-frame role id — boulder 12/13/14/15 or
         * the route variants 42/43/44/45 (see [HoldRole]) — to the L1 role byte.
         * Route ids are normalised to their boulder equivalents first via
         * [HoldRole.normalize] (single source of truth), then mapped.
         *
         * @throws IllegalArgumentException for a role id outside the known set.
         */
        fun fromStoredRoleId(roleId: Int): Int = when (HoldRole.normalize(roleId)) {
            HoldRole.START -> START
            HoldRole.HAND -> HAND
            HoldRole.FINISH -> FINISH
            HoldRole.FOOT -> FOOT
            else -> throw IllegalArgumentException("Unmapped CruxCoach roleId: $roleId")
        }
    }

    /** Capability flags (`crux::Capability`, a u32 bitfield in STATE). */
    object Capability {
        const val SET_ROUTE: Int = 1 shl 0
        const val RAW_FRAME: Int = 1 shl 1
        const val EFFECTS: Int = 1 shl 2
        const val OTA: Int = 1 shl 3
        const val IMU: Int = 1 shl 4
        const val TOUCH: Int = 1 shl 5
        const val AUTH: Int = 1 shl 6
    }

    /** One `{ u16 holdIndex, u8 role }` entry of a [Opcode.SET_ROUTE] payload. */
    data class RouteHold(val holdIndex: Int, val role: Int)

    /** Parsed 4-byte frame header. */
    data class FrameHeader(val version: Int, val opcode: Int, val payloadLength: Int)

    /** Decoded [Opcode.STATE] payload (`crux::StateInfo` + protocol version). */
    data class StateInfo(
        val protocolVersion: Int,
        val fwMajor: Int,
        val fwMinor: Int,
        val fwPatch: Int,
        val capabilities: Long,
        val angleDeciDeg: Int,
        val brightness: Int,
        val ledCount: Int,
        val holdCount: Int,
        val wifiMode: Int,
        val authRequired: Boolean,
    ) {
        /** True if the given [Capability] flag is set in [capabilities]. */
        fun hasCapability(cap: Int): Boolean = (capabilities and cap.toLong()) != 0L
    }

    /** Decoded [Opcode.ACK] payload (`crux::` ACK: echoedOpcode + status). */
    data class AckInfo(val echoedOpcode: Int, val status: Int)

    // ── Client → board encoders ──────────────────────────────────────────

    /**
     * SET_ROUTE: replace the shown route. Payload is N × `{ u16 holdIndex,
     * u8 role }`. An empty list encodes to an empty payload (== clear the
     * route), matching the firmware's `routePayloadValid(0) == true`.
     */
    fun encodeSetRoute(holds: List<RouteHold>): ByteArray {
        val payload = ByteArray(holds.size * ROUTE_ENTRY_SIZE)
        var pos = 0
        for (hold in holds) {
            require(hold.holdIndex in 0..0xFFFF) { "holdIndex out of u16 range: ${hold.holdIndex}" }
            require(hold.role in 0..0xFF) { "role out of u8 range: ${hold.role}" }
            writeU16LE(payload, pos, hold.holdIndex)
            payload[pos + 2] = hold.role.toByte()
            pos += ROUTE_ENTRY_SIZE
        }
        return frame(Opcode.SET_ROUTE, payload)
    }

    /** BRIGHTNESS: global brightness 0..255. */
    fun encodeBrightness(level: Int): ByteArray {
        require(level in 0..0xFF) { "brightness out of u8 range: $level" }
        return frame(Opcode.BRIGHTNESS, byteArrayOf(level.toByte()))
    }

    /** CLEAR: all LEDs off, effect stopped. Empty payload. */
    fun encodeClear(): ByteArray = frame(Opcode.CLEAR, EMPTY_PAYLOAD)

    /** PING: the board replies with a STATE frame. Empty payload. */
    fun encodePing(): ByteArray = frame(Opcode.PING, EMPTY_PAYLOAD)

    /** AUTH: authenticate the connection. Payload is the UTF-8 token bytes. */
    fun encodeAuth(token: String): ByteArray = frame(Opcode.AUTH, token.encodeToByteArray())

    /** EFFECT: run a built-in effect. Payload `{ u8 effectId, u8 speed, u8 intensity }`. */
    fun encodeEffect(effectId: Int, speed: Int, intensity: Int): ByteArray {
        require(effectId in 0..0xFF) { "effectId out of u8 range: $effectId" }
        require(speed in 0..0xFF) { "speed out of u8 range: $speed" }
        require(intensity in 0..0xFF) { "intensity out of u8 range: $intensity" }
        return frame(Opcode.EFFECT, byteArrayOf(effectId.toByte(), speed.toByte(), intensity.toByte()))
    }

    // ── Board → client decoders ──────────────────────────────────────────

    /**
     * Parse and validate the 4-byte header of a COMPLETE frame buffer
     * (mirror of `crux::decodeFrame`). Returns null on: a buffer shorter than
     * the header, a wrong protocol version, a payload length beyond
     * [MAX_PAYLOAD], or a length field inconsistent with the buffer size
     * (truncated / trailing junk).
     */
    fun parseHeader(frame: ByteArray): FrameHeader? {
        if (frame.size < HEADER_SIZE) return null
        val version = frame[0].toInt() and 0xFF
        val opcode = frame[1].toInt() and 0xFF
        val payloadLength = readU16LE(frame, 2)
        if (version != PROTOCOL_VERSION) return null
        if (payloadLength > MAX_PAYLOAD) return null
        if (HEADER_SIZE + payloadLength != frame.size) return null
        return FrameHeader(version, opcode, payloadLength)
    }

    /**
     * Decode a STATE payload (the 17 bytes AFTER the header — see
     * [STATE_PAYLOAD_SIZE]). Mirror of `crux::decodeStatePayload`.
     *
     * @throws IllegalArgumentException if the payload is not exactly
     *   [STATE_PAYLOAD_SIZE] bytes or reports a foreign protocol version.
     */
    fun decodeState(payload: ByteArray): StateInfo {
        require(payload.size == STATE_PAYLOAD_SIZE) {
            "STATE payload must be $STATE_PAYLOAD_SIZE bytes, got ${payload.size}"
        }
        val protocolVersion = payload[0].toInt() and 0xFF
        require(protocolVersion == PROTOCOL_VERSION) {
            "STATE protocol version mismatch: $protocolVersion"
        }
        return StateInfo(
            protocolVersion = protocolVersion,
            fwMajor = payload[1].toInt() and 0xFF,
            fwMinor = payload[2].toInt() and 0xFF,
            fwPatch = payload[3].toInt() and 0xFF,
            capabilities = readU32LE(payload, 4),
            angleDeciDeg = readI16LE(payload, 8),
            brightness = payload[10].toInt() and 0xFF,
            ledCount = readU16LE(payload, 11),
            holdCount = readU16LE(payload, 13),
            wifiMode = payload[15].toInt() and 0xFF,
            authRequired = payload[16].toInt() != 0,
        )
    }

    /**
     * Decode an ACK payload (`{ u8 echoedOpcode, u8 status }`).
     *
     * @throws IllegalArgumentException if the payload is not exactly
     *   [ACK_PAYLOAD_SIZE] bytes.
     */
    fun decodeAck(payload: ByteArray): AckInfo {
        require(payload.size == ACK_PAYLOAD_SIZE) {
            "ACK payload must be $ACK_PAYLOAD_SIZE bytes, got ${payload.size}"
        }
        return AckInfo(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF)
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private val EMPTY_PAYLOAD = ByteArray(0)

    /** Prepend the 4-byte header to [payload] and return the full frame. */
    private fun frame(opcode: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload exceeds MAX_PAYLOAD: ${payload.size}" }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = PROTOCOL_VERSION.toByte()
        out[1] = opcode.toByte()
        writeU16LE(out, 2, payload.size)
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    private fun writeU16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
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
}
