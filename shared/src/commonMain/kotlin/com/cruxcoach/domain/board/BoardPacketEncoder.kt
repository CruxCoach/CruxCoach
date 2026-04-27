package com.cruxcoach.domain.board

/**
 * Encodes hold data into BLE packets for Aurora Climbing boards (Kilter, Tension, etc.).
 * Based on reverse-engineered protocol from BoardLib / Kilter.jl.
 *
 * Packet format:
 * [0x01][dataLen][checksum][0x02][type][...holdData...][0x03]
 *
 * Hold encoding (API Level 3): 3 bytes per hold
 *   Byte 0: position & 0xFF
 *   Byte 1: (position >> 8) & 0xFF
 *   Byte 2: color byte (r3<<5 | g3<<2 | b2)
 */
class BoardPacketEncoder(private val apiLevel: Int = 3) {

    companion object {
        const val BLE_MTU = 20

        // Max hold data bytes per packet payload (254 = 255 max - 1 type byte)
        // 254 / 3 bytes per hold = 84 holds per packet
        private const val MAX_HOLDS_PER_PACKET = 84

        // API Level 3 packet types
        const val API3_FIRST: Byte = 0x52  // 'R'
        const val API3_MIDDLE: Byte = 0x51 // 'Q'
        const val API3_LAST: Byte = 0x53   // 'S'
        const val API3_ONLY: Byte = 0x54   // 'T'

        // API Level 2 packet types
        const val API2_FIRST: Byte = 0x4E  // 'N'
        const val API2_MIDDLE: Byte = 0x4D // 'M'
        const val API2_LAST: Byte = 0x4F   // 'O'
        const val API2_ONLY: Byte = 0x50   // 'P'

        // Standard Kilter Board colors (from placement_roles.led_color DB)
        const val COLOR_START: Int = 0x1C   // Green (00FF00)
        const val COLOR_HAND: Int = 0x1F    // Cyan (00FFFF)
        const val COLOR_FINISH: Int = 0xE3  // Magenta (FF00FF)
        const val COLOR_FOOT: Int = 0xF4    // Orange (FFA500)

        fun roleToColor(roleId: Int): Int = when (roleId) {
            HoldRole.START -> COLOR_START
            HoldRole.HAND -> COLOR_HAND
            HoldRole.FINISH -> COLOR_FINISH
            HoldRole.FOOT -> COLOR_FOOT
            else -> 0xFF // White fallback
        }

        fun encodeColor(r: Int, g: Int, b: Int): Int {
            val r3 = (r / 32).coerceIn(0, 7)
            val g3 = (g / 32).coerceIn(0, 7)
            val b2 = (b / 64).coerceIn(0, 3)
            return (r3 shl 5) or (g3 shl 2) or b2
        }
    }

    /**
     * Encode a list of holds into BLE-ready packet chunks.
     * @param holds List of (ledPosition, colorByte) pairs
     * @return List of ByteArrays, each <= BLE_MTU bytes
     */
    fun encodeClimb(holds: List<Pair<Int, Int>>): List<ByteArray> {
        val holdBytes = mutableListOf<Byte>()
        for ((pos, color) in holds) {
            holdBytes.add((pos and 0xFF).toByte())
            holdBytes.add(((pos shr 8) and 0xFF).toByte())
            holdBytes.add(color.toByte())
        }

        // Split into protocol-level packets if too many holds for one packet
        if (holds.size <= MAX_HOLDS_PER_PACKET) {
            // Single packet
            val typeOnly = if (apiLevel >= 3) API3_ONLY else API2_ONLY
            val payload = byteArrayOf(typeOnly) + holdBytes.toByteArray()
            return buildPacket(payload)
        }

        // Multi-packet: split hold data across FIRST / MIDDLE / LAST packets
        val first = if (apiLevel >= 3) API3_FIRST else API2_FIRST
        val middle = if (apiLevel >= 3) API3_MIDDLE else API2_MIDDLE
        val last = if (apiLevel >= 3) API3_LAST else API2_LAST

        val holdByteArray = holdBytes.toByteArray()
        val chunkSize = MAX_HOLDS_PER_PACKET * 3  // bytes per protocol packet
        val chunks = holdByteArray.toList().chunked(chunkSize).map { it.toByteArray() }
        val result = mutableListOf<ByteArray>()

        for ((i, holdChunk) in chunks.withIndex()) {
            val type = when {
                i == 0 -> first
                i == chunks.lastIndex -> last
                else -> middle
            }
            val payload = byteArrayOf(type) + holdChunk
            result.addAll(buildPacket(payload))
        }
        return result
    }

    /**
     * Encode holds from BoardHold objects with automatic role-to-color mapping.
     * Requires a mapping from placementId to ledPosition.
     */
    fun encodeClimbFromHolds(
        holds: List<BoardHold>,
        placementToLed: Map<Int, Int>
    ): List<ByteArray> {
        val holdPairs = holds.mapNotNull { hold ->
            val led = placementToLed[hold.placementId] ?: return@mapNotNull null
            led to roleToColor(hold.roleId)
        }
        return encodeClimb(holdPairs)
    }

    /**
     * Encode a clear-board command (no holds = empty packet).
     */
    fun encodeClear(): List<ByteArray> {
        return encodeClimb(emptyList())
    }

    /**
     * Build a complete packet with header, checksum, and BLE chunking.
     * Uses unsigned byte encoding for payload length to support sizes up to 255.
     */
    private fun buildPacket(payload: ByteArray): List<ByteArray> {
        val cs = checksum(payload)
        val packet = byteArrayOf(
            0x01,
            (payload.size and 0xFF).toByte(),
            cs,
            0x02
        ) + payload + byteArrayOf(0x03)

        return chunk(packet)
    }

    /**
     * Calculate checksum: one's complement of byte sum, masked to 8 bits.
     */
    fun checksum(data: ByteArray): Byte {
        val sum = data.sumOf { it.toInt() and 0xFF }
        return ((sum.inv()) and 0xFF).toByte()
    }

    /**
     * Split packet into BLE MTU-sized chunks.
     */
    private fun chunk(packet: ByteArray): List<ByteArray> {
        return packet.toList()
            .chunked(BLE_MTU)
            .map { it.toByteArray() }
    }
}
