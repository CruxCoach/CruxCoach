package com.cruxcoach.domain.board

/** Stable app-side identities; deliberately outside every vendor id range. */
enum class QuantumBoardModel(
    val wireValue: String,
    val displayName: String,
    /** eWalls 2.0.14 forcedType used by routes-delta. */
    val forcedType: String,
    val layoutId: Long,
    val productSizeId: Long,
    val columns: Int,
    val rows: Int,
) {
    XL("xl", "XL", "big", 9101, 9201, 15, 15),
    L("l", "L", "medium", 9102, 9202, 15, 12),
    M("m", "M", "small", 9103, 9203, 12, 12),
    S("s", "S Fitness", "xsmall", 9104, 9204, 8, 12),
    BELAY("belay", "Belay Board", "belay", 9105, 9205, 8, 12);

    companion object {
        fun fromWire(value: String?): QuantumBoardModel? =
            entries.firstOrNull { it.wireValue == value }

        fun fromLayoutId(value: Long): QuantumBoardModel? =
            entries.firstOrNull { it.layoutId == value }

        fun fromProductSizeId(value: Long): QuantumBoardModel? =
            entries.firstOrNull { it.productSizeId == value }
    }
}

enum class QuantumCommand(val byte: Int) {
    ACTIVATE_WALL(0x41),
    TURN_OFF_BY_ROUTE(0x42),
    TURN_OFF_BY_USER(0x43),
    BOARD_SWIPE(0x44),
    TURN_OFF_ALL(0x45),
    REQUEST_USER_ROUTE_LIST(0x47),
    TURN_ON_ALL(0x64),
}

data class QuantumActivePlayer(
    val routeId: String,
    val userId: String,
    val remainingSeconds: Int,
    val color: Int,
)

sealed interface QuantumBroadcast {
    val command: QuantumCommand?

    data class RouteList(
        override val command: QuantumCommand,
        val players: List<QuantumActivePlayer>,
    ) : QuantumBroadcast

    data class UserTurnedOff(val userId: String) : QuantumBroadcast {
        override val command = QuantumCommand.TURN_OFF_BY_USER
    }

    data object BoardCleared : QuantumBroadcast {
        override val command = QuantumCommand.TURN_OFF_ALL
    }

    data class BoardLit(val color: Int?) : QuantumBroadcast {
        override val command = QuantumCommand.TURN_ON_ALL
    }

    data class Exception(val failedCommand: QuantumCommand?, val code: Int) : QuantumBroadcast {
        override val command = failedCommand
    }
}

/** Side-effect-free decoder for Quantum controller broadcasts.
 *
 * Commands written to fff2 carry CRC16/MODBUS. Broadcasts received through
 * fff1/fff4 do not, so structure is the integrity boundary on this
 * receive-only channel: the frame marker, a known command, a player count
 * the controller can hold and the zero reserved byte. Length is a minimum, not
 * an exact size: firmware that appends bytes (a CRC, padding) must not make
 * every answer unreadable.
 */
object QuantumBoardBroadcastParser {
    const val PLAYER_BYTES = 37

    /** Most players one route list can name: the controller holds four. */
    const val MAX_PLAYERS = 4

    /** Smallest complete frame once enough header bytes are available. */
    fun expectedFrameSize(bytes: ByteArray): Int? {
        if (bytes.size < 2 || bytes[0].toInt() and 0xff != 1) return null
        val rawCommand = bytes[1].toInt() and 0xff
        if (rawCommand and 0x80 != 0) return 3
        return when (rawCommand) {
            0x41, 0x44, 0x47 -> if (bytes.size >= 4) {
                4 + (bytes[2].toInt() and 0xff) * PLAYER_BYTES
            } else null
            0x43 -> 21
            0x45 -> 6
            0x64 -> 3
            else -> null
        }
    }

    fun parse(frame: ByteArray): QuantumBroadcast? {
        val expected = expectedFrameSize(frame) ?: return null
        if (frame.size < expected) return null
        val rawCommand = frame[1].toInt() and 0xff
        if (rawCommand and 0x80 != 0) {
            val failed = QuantumCommand.entries.firstOrNull { it.byte == rawCommand and 0x7f }
            return QuantumBroadcast.Exception(failed, frame[2].toInt() and 0xff)
        }
        val command = QuantumCommand.entries.firstOrNull { it.byte == rawCommand } ?: return null
        return when (command) {
            QuantumCommand.ACTIVATE_WALL,
            QuantumCommand.BOARD_SWIPE,
            QuantumCommand.REQUEST_USER_ROUTE_LIST -> {
                val count = frame[2].toInt() and 0xff
                // The fourth header byte is reserved as zero. It is the one
                // fixed invariant beyond length on this CRC-less channel, and
                // it keeps an echo of our own 0x47 request (01 47 00 f0 13)
                // from reading as an empty board.
                if (count > MAX_PLAYERS || frame[3].toInt() != 0) return null
                val players = List(count) { index ->
                    val offset = 4 + index * PLAYER_BYTES
                    QuantumActivePlayer(
                        routeId = uuid(frame, offset),
                        userId = uuid(frame, offset + 16),
                        remainingSeconds = u16(frame, offset + 32),
                        color = ((frame[offset + 34].toInt() and 0xff) shl 16) or
                            ((frame[offset + 35].toInt() and 0xff) shl 8) or
                            (frame[offset + 36].toInt() and 0xff),
                    )
                }
                QuantumBroadcast.RouteList(command, players)
            }
            QuantumCommand.TURN_OFF_BY_USER ->
                QuantumBroadcast.UserTurnedOff(uuid(frame, 2))
            QuantumCommand.TURN_OFF_ALL -> QuantumBroadcast.BoardCleared
            QuantumCommand.TURN_ON_ALL -> QuantumBroadcast.BoardLit(
                if ((frame[2].toInt() and 0xff) == 0xff) 0xffffff else null,
            )
            QuantumCommand.TURN_OFF_BY_ROUTE -> null
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun uuid(bytes: ByteArray, offset: Int): String {
        val hex = (offset until offset + 16).joinToString("") { index ->
            (bytes[index].toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}

/** Clean-room encoder for the Quantum binary controller protocol. */
object QuantumBoardPacketEncoder {
    const val ACTIVATE_CHUNK_LIMIT = 92
    /** Minutes a played route stays lit when the controller names no
     * auto-off time of its own. */
    const val DEFAULT_PLAY_MINUTES = 31
    /** ACTIVATE_WALL's "until removed" duration. Playing never uses it: the
     * controller treats such an activation as a route being built, and the
     * real XL left ACTIVATE_WALL routes out of its route list. */
    const val UNTIL_REMOVED = 0xffff
    const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"

    /** How long a played route stays lit: the controller's auto-off time
     * (seconds, from its fff5 record) in whole minutes, rounded up, or
     * [DEFAULT_PLAY_MINUTES] when it names none. */
    fun playMinutes(autoOffSeconds: Int?): Int =
        if (autoOffSeconds != null && autoOffSeconds > 0) (autoOffSeconds + 59) / 60
        else DEFAULT_PLAY_MINUTES

    fun crc16Modbus(bytes: ByteArray): Int {
        var crc = 0xffff
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xff)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xa001 else crc ushr 1
            }
        }
        return crc and 0xffff
    }

    fun activate(
        routeId: String,
        userId: String,
        diodes: List<Int>,
        color: Int = 0x00ffff,
        duration: Int = UNTIL_REMOVED,
        animation: Int = 0,
        swipe: Boolean = false,
    ): List<ByteArray> {
        require(diodes.isNotEmpty()) { "Quantum activation requires at least one diode" }
        require(diodes.all { it in 0..0xffff }) { "Quantum diode address out of u16 range" }
        require(color in 0..0xffffff && duration in 0..0xffff && animation in 0..0xff)
        return diodes.chunked(ACTIVATE_CHUNK_LIMIT).map { part ->
            val payload = mutableListOf<Byte>()
            payload += id16(routeId).toList()
            payload += id16(userId).toList()
            payload += rgb(color).toList()
            payload += be16(duration).toList()
            payload += animation.toByte()
            payload += (part.size * 2).toByte()
            part.forEach { payload += be16(it).toList() }
            frame(if (swipe) QuantumCommand.BOARD_SWIPE else QuantumCommand.ACTIVATE_WALL, payload.toByteArray())
        }
    }

    /**
     * Play one route for [userId]: a single BOARD_SWIPE in one colour that
     * stays lit for [minutes]. It needs no TURN_OFF_USER first; a new route
     * for the same user takes the place of the old one. One frame holds at
     * most [ACTIVATE_CHUNK_LIMIT] diodes.
     */
    fun play(
        routeId: String,
        userId: String,
        diodes: List<Int>,
        color: Int,
        minutes: Int = DEFAULT_PLAY_MINUTES,
    ): ByteArray {
        require(diodes.size <= ACTIVATE_CHUNK_LIMIT) { "One Quantum play frame holds at most $ACTIVATE_CHUNK_LIMIT diodes" }
        return activate(routeId, userId, diodes, color, minutes, swipe = true).single()
    }

    fun turnOffAll(): ByteArray = frame(
        QuantumCommand.TURN_OFF_ALL,
        byteArrayOf(0, 1, 0, 0),
    )

    fun turnOffRoute(routeId: String): ByteArray =
        frame(QuantumCommand.TURN_OFF_BY_ROUTE, uuidBytes(routeId) + byteArrayOf(0))

    /** Turn off the route [userId] lights. Only removing a route sends it. */
    fun turnOffUser(userId: String): ByteArray =
        frame(QuantumCommand.TURN_OFF_BY_USER, uuidBytes(userId) + byteArrayOf(0))

    fun requestRouteList(row: Int = 0): ByteArray {
        require(row in 0..255)
        return frame(QuantumCommand.REQUEST_USER_ROUTE_LIST, byteArrayOf(row.toByte()))
    }

    fun turnOnAll(color: Int, duration: Int = 0): ByteArray {
        require(color in 0..0xffffff && duration in 0..0xffff)
        return frame(QuantumCommand.TURN_ON_ALL, rgb(color) + be16(duration))
    }

    private fun frame(command: QuantumCommand, payload: ByteArray): ByteArray {
        val body = byteArrayOf(1, command.byte.toByte()) + payload
        val crc = crc16Modbus(body)
        return body + byteArrayOf(((crc ushr 8) and 0xff).toByte(), (crc and 0xff).toByte())
    }

    private fun id16(value: String): ByteArray = uuidBytes(value)

    fun uuidBytes(value: String): ByteArray {
        val hex = value.replace("-", "")
        require(hex.length == 32 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Quantum IDs must be 32 hexadecimal digits"
        }
        return ByteArray(16) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun rgb(color: Int) = byteArrayOf((color ushr 16).toByte(), (color ushr 8).toByte(), color.toByte())
    private fun be16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
}
