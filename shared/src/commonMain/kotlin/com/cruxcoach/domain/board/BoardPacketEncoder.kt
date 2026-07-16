package com.cruxcoach.domain.board

/**
 * Encodes hold data into BLE packets for Aurora Climbing boards (Kilter, Tension, etc.).
 *
 * Protocol and encoder derivation:
 * - BoardLib, Copyright (c) 2023 Luke Emery-Fertitta, MIT License:
 *   https://github.com/lemeryfertitta/BoardLib
 * - Kilter.jl, Copyright (c) 2023 Frederik Schnack and contributors, MIT License:
 *   https://github.com/FrederikSchnack/Kilter.jl
 * - The API-level-2 power/packing path is ported from BoardSesh's `aurora.ts`,
 *   Apache-2.0, pinned for this attribution at commit
 *   12f6b7855a99cd4c7543d078415ec35dd78c192f:
 *   https://github.com/boardsesh/boardsesh/blob/12f6b7855a99cd4c7543d078415ec35dd78c192f/packages/shared/ble-protocol/src/aurora.ts
 *
 * The corresponding license texts ship in `LICENSES/` and in the APK's legal
 * assets. CruxCoach modifications and the combined file are distributed under
 * GPL-3.0-only.
 *
 * Packet format:
 * [0x01][dataLen][checksum][0x02][type][...holdData...][0x03]
 *
 * Hold encoding (API Level 3, modern boards): 3 bytes per hold
 *   Byte 0: position & 0xFF
 *   Byte 1: (position >> 8) & 0xFF
 *   Byte 2: color byte (r3<<5 | g3<<2 | b2)
 *
 * API Level 2 ("@2") legacy hardware uses a different wire format: 2 bytes per
 * hold (10-bit position + 2-bit-per-channel colour) with an 18 W power-budget
 * brightness scale applied across the whole send — see [encodeClimbV2]. It is a
 * faithful port of the BoardSesh aurora.ts spec (computeV2Scale /
 * scaledColorV2 / encodePositionAndColorV2) and is fully isolated from the @3
 * path so an error here can never alter @3 output.
 *
 * DEVICE-VERIFICATION OWED: the @2 path has NOT been verified on real @2
 * hardware — only against the BoardSesh spec via unit tests. A wrong @2
 * transformation can brown-out the board or mis-colour holds, so it is kept
 * conservative and @3 stays the default for any board that does not explicitly
 * advertise a lower API level.
 */
class BoardPacketEncoder(
    private val apiLevel: Int = 3,
    /** Physical LEDs per hold (Kilter = 2, every other Aurora board = 1).
     *  Only affects the v2 power-budget calculation; see [ledsPerHoldFor]. */
    private val ledsPerHold: Int = DEFAULT_LEDS_PER_HOLD,
) {

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

        // Last-resort role→colour fallback (Kilter palette) used only when no
        // per-board placement_roles colour is available. Folds every board's
        // role codes to a class via [HoldRole.roleClass] so Aurora-family holds
        // (codes 1-4) light a real colour instead of the 0xFF white that an
        // exact Kilter-code (12-15) match produced before. The primary BLE path
        // still keys placement_roles by the raw id, so exact per-board colours
        // win when present.
        fun roleToColor(roleId: Int): Int = when (HoldRole.roleClass(roleId)) {
            HoldRole.START -> COLOR_START
            HoldRole.HAND -> COLOR_HAND
            HoldRole.FINISH -> COLOR_FINISH
            HoldRole.FOOT -> COLOR_FOOT
            else -> 0xFF // White fallback for genuinely unknown codes
        }

        fun encodeColor(r: Int, g: Int, b: Int): Int {
            val r3 = (r / 32).coerceIn(0, 7)
            val g3 = (g / 32).coerceIn(0, 7)
            val b2 = (b / 64).coerceIn(0, 3)
            return (r3 shl 5) or (g3 shl 2) or b2
        }

        /**
         * Parse a hex colour ("RRGGBB" or "#RRGGBB" — the FEAT-031
         * placement_roles.led_color form) into the RGB332 board colour byte,
         * or null if absent / malformed (caller falls back to a default).
         */
        fun hexToColorByte(hex: String?): Int? {
            val h = hex?.trim()?.removePrefix("#") ?: return null
            if (h.length < 6) return null
            return try {
                encodeColor(
                    h.substring(0, 2).toInt(16),
                    h.substring(2, 4).toInt(16),
                    h.substring(4, 6).toInt(16),
                )
            } catch (e: NumberFormatException) {
                null
            }
        }

        // ── API Level 2 ("@2") power-budget scaling ─────────────────────────
        // Faithful port of the BoardSesh aurora.ts spec. DEVICE-VERIFICATION
        // OWED — @2 hardware not real-tested; kept conservative + isolated.

        /** Brightness scales tried in order until the board's power budget
         *  fits (BoardSesh V2_POWER_SCALES). 0.0 = budget unmet, all LEDs off. */
        private val V2_POWER_SCALES = doubleArrayOf(1.0, 0.8, 0.6, 0.4, 0.2, 0.1, 0.05)
        private const val V2_MAX_BOARD_POWER = 18.0
        /** v2 packs the position in a 10-bit field; higher positions are skipped. */
        const val V2_MAX_POSITION = 1023
        /** v2 = 2 bytes/LED → 254 payload bytes / 2 = 127 LEDs per protocol packet. */
        private const val MAX_HOLDS_PER_PACKET_V2 = 127

        const val KILTER_LEDS_PER_HOLD = 2
        const val DEFAULT_LEDS_PER_HOLD = 1

        /** Physical LEDs per hold position: Kilter has 2, every other Aurora
         *  board 1. Only affects the v2 power budget (BoardSesh getLedsPerHold). */
        fun ledsPerHoldFor(brand: BoardBrand): Int =
            if (brand == BoardBrand.KILTER) KILTER_LEDS_PER_HOLD else DEFAULT_LEDS_PER_HOLD

        /** Decode an RGB332 colour byte back to 8-bit channels (bit-replication)
         *  so the v2 scaling formula — defined over 8-bit RGB in the spec —
         *  sees equivalent magnitudes. Palette colours round-trip exactly:
         *  encodeColor(0,255,0)=0x1C → (0,255,0). */
        internal fun rgb332ToRgb888(color: Int): Triple<Int, Int, Int> {
            val r3 = (color shr 5) and 0x07
            val g3 = (color shr 2) and 0x07
            val b2 = color and 0x03
            val r8 = (r3 shl 5) or (r3 shl 2) or (r3 shr 1)
            val g8 = (g3 shl 5) or (g3 shl 2) or (g3 shr 1)
            val b8 = (b2 shl 6) or (b2 shl 4) or (b2 shl 2) or b2
            return Triple(r8, g8, b8)
        }

        /** floor(value8bit * scale) >> 6 → 0..3 (BoardSesh scaledColorV2). */
        internal fun scaledColorV2(value8bit: Int, scale: Double): Int =
            kotlin.math.floor(value8bit * scale).toInt() shr 6

        /** Largest brightness scale fitting the 18 W v2 budget (BoardSesh
         *  computeV2Scale). [colors] are RGB332 bytes; tries each scale until
         *  ledsPerHold * Σ(r+g+b)/30 ≤ 18, else 0.0 (board can't be driven). */
        internal fun computeV2Scale(colors: List<Int>, ledsPerHold: Int): Double {
            for (scale in V2_POWER_SCALES) {
                var totalPower = 0.0
                for (color in colors) {
                    val (r8, g8, b8) = rgb332ToRgb888(color)
                    totalPower += (scaledColorV2(r8, scale) + scaledColorV2(g8, scale) +
                        scaledColorV2(b8, scale)) / 30.0
                }
                if (ledsPerHold * totalPower <= V2_MAX_BOARD_POWER) return scale
            }
            return 0.0
        }

        /** Encode one v2 LED as 2 bytes: [posLo, (r2<<6)|(g2<<4)|(b2<<2)|posHi].
         *  Returns null when the position exceeds the 10-bit field so the caller
         *  skips it (never happens on real Aurora boards, max ~641). */
        internal fun encodePositionAndColorV2(position: Int, color: Int, scale: Double): Pair<Byte, Byte>? {
            if (position > V2_MAX_POSITION) return null
            val posLo = position and 0xFF
            val posHi = (position shr 8) and 0x03
            val (r8, g8, b8) = rgb332ToRgb888(color)
            val colorByte = (scaledColorV2(r8, scale) shl 6) or
                (scaledColorV2(g8, scale) shl 4) or
                (scaledColorV2(b8, scale) shl 2) or posHi
            return posLo.toByte() to colorByte.toByte()
        }
    }

    /**
     * Encode a list of holds into BLE-ready packet chunks.
     * @param holds List of (ledPosition, colorByte) pairs
     * @return List of ByteArrays, each <= BLE_MTU bytes
     */
    fun encodeClimb(holds: List<Pair<Int, Int>>): List<ByteArray> {
        // "@2" legacy hardware uses a different wire format (2 bytes/LED +
        // power scaling) — fully isolated in [encodeClimbV2] so the @3 path
        // below stays byte-for-byte unchanged.
        if (apiLevel < 3) return encodeClimbV2(holds)

        val holdBytes = mutableListOf<Byte>()
        for ((pos, color) in holds) {
            holdBytes.add((pos and 0xFF).toByte())
            holdBytes.add(((pos shr 8) and 0xFF).toByte())
            holdBytes.add(color.toByte())
        }

        // Split into protocol-level packets if too many holds for one packet
        if (holds.size <= MAX_HOLDS_PER_PACKET) {
            val payload = byteArrayOf(API3_ONLY) + holdBytes.toByteArray()
            return buildPacket(payload)
        }

        // Multi-packet: split hold data across FIRST / MIDDLE / LAST packets
        val holdByteArray = holdBytes.toByteArray()
        val chunkSize = MAX_HOLDS_PER_PACKET * 3  // bytes per protocol packet
        val chunks = holdByteArray.toList().chunked(chunkSize).map { it.toByteArray() }
        val result = mutableListOf<ByteArray>()

        for ((i, holdChunk) in chunks.withIndex()) {
            val type = when {
                i == 0 -> API3_FIRST
                i == chunks.lastIndex -> API3_LAST
                else -> API3_MIDDLE
            }
            result.addAll(buildPacket(byteArrayOf(type) + holdChunk))
        }
        return result
    }

    /**
     * Encode for "@2" legacy hardware: 2 bytes per LED with the 18 W
     * power-budget brightness scale applied once across the whole send
     * (faithful port of the BoardSesh aurora.ts spec). Fully isolated from the
     * @3 path in [encodeClimb].
     *
     * DEVICE-VERIFICATION OWED — not tested on real @2 hardware.
     */
    private fun encodeClimbV2(holds: List<Pair<Int, Int>>): List<ByteArray> {
        // Empty = clear-all: a valid ONLY packet carrying just the type byte.
        if (holds.isEmpty()) return buildPacket(byteArrayOf(API2_ONLY))

        val scale = computeV2Scale(holds.map { it.second }, ledsPerHold)
        val ledBytes = mutableListOf<Byte>()
        for ((pos, color) in holds) {
            val (lo, colorByte) = encodePositionAndColorV2(pos, color, scale) ?: continue
            ledBytes.add(lo)
            ledBytes.add(colorByte)
        }
        // Every LED skipped (all positions exceeded the 10-bit field) → emit a
        // clear-all packet, never a zero-length send.
        if (ledBytes.isEmpty()) return buildPacket(byteArrayOf(API2_ONLY))

        val ledByteArray = ledBytes.toByteArray()
        val chunkSize = MAX_HOLDS_PER_PACKET_V2 * 2  // 2 bytes per LED
        if (ledByteArray.size <= chunkSize) {
            return buildPacket(byteArrayOf(API2_ONLY) + ledByteArray)
        }

        val chunks = ledByteArray.toList().chunked(chunkSize).map { it.toByteArray() }
        val result = mutableListOf<ByteArray>()
        for ((i, ledChunk) in chunks.withIndex()) {
            val type = when {
                i == 0 -> API2_FIRST
                i == chunks.lastIndex -> API2_LAST
                else -> API2_MIDDLE
            }
            result.addAll(buildPacket(byteArrayOf(type) + ledChunk))
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
