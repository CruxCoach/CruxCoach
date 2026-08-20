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

/** Clean-room encoder for the Quantum binary controller protocol. */
object QuantumBoardPacketEncoder {
    const val ACTIVATE_CHUNK_LIMIT = 92
    const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"

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
        duration: Int = 0,
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

    /** Complete eWalls route transition, ordered for sequential transport. */
    fun replaceUserRoute(
        routeId: String,
        userId: String,
        diodes: List<Int>,
        color: Int = 0x00ffff,
        duration: Int = 0,
        animation: Int = 0,
    ): List<ByteArray> = listOf(turnOffUser(userId)) + activate(
        routeId = routeId,
        userId = userId,
        diodes = diodes,
        color = color,
        duration = duration,
        animation = animation,
    )

    fun turnOffAll(): ByteArray = frame(
        QuantumCommand.TURN_OFF_ALL,
        byteArrayOf(0, 1, 0, 0),
    )

    fun turnOffRoute(routeId: String): ByteArray =
        frame(QuantumCommand.TURN_OFF_BY_ROUTE, uuidBytes(routeId) + byteArrayOf(0))

    /**
     * Release the route currently owned by [userId]. eWalls performs this
     * before every new activation; without it the controller accepts the
     * first climb and rejects later climbs as "user already on route".
     */
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
