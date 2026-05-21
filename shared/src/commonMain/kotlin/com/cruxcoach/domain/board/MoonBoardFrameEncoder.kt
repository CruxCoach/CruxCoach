package com.cruxcoach.domain.board

/**
 * Encodes a MoonBoard climb into the BLE wire frame the board's LED
 * controller expects (FEAT-027). Pure logic — JVM-testable, no Android
 * BLE dependency. The transport (scan / connect / chunked write) lives
 * in the androidApp BLE layer; this object owns only the byte format.
 *
 * Wire format — verified against BoardSesh's `bluetooth-moonboard.ts`
 * encoder and the `moonboard-protocol` C++ decoder
 * (see ~/moonboard-re/analysis/06-boardsesh-comparison.md §2):
 *
 *     l#<token><serialPos>,<token><serialPos>,...#
 *
 * `l#` is the lights-only prefix. The `~D…` "lights above holds"
 * config preamble is decoder-supported but never emitted by a client,
 * so the encoder only produces the simple `l#…#` shape. Holds are
 * comma-separated; the frame is terminated by `#`.
 *
 * Roles: the catalogue carries only start / hand / finish, so the
 * encoder emits 3 tokens (S / P / E). The board's decoder accepts a
 * wider set (S/R/P/L/M/F/E) but the extra roles never appear in a
 * MoonBoard climb's `frames`.
 */
object MoonBoardFrameEncoder {

    // Aurora-aligned role codes as stored in the `frames` column
    // (p{holdId}r{roleCode}). Match HOLD_STATE_CODES in the
    // cruxcoach-blossom-sync MoonBoard importer.
    private const val ROLE_START = 42
    private const val ROLE_HAND = 43
    private const val ROLE_FINISH = 44

    private const val FRAME_PREFIX = "l#"
    private const val FRAME_SUFFIX = "#"

    private val MAX_HOLD_ID = MoonBoardVariant.GRID_COLUMNS * MoonBoardVariant.GRID_ROWS

    /** Wire token for a role code, or null for roles the board can't show. */
    private fun roleToken(roleCode: Int): Char? = when (roleCode) {
        ROLE_START -> 'S'
        ROLE_HAND -> 'P'
        ROLE_FINISH -> 'E'
        else -> null
    }

    /**
     * Convert a 1-based grid hold id — `(row-1)*11 + colIndex + 1`,
     * column A-K, row 1 at the bottom — to the 0-indexed serpentine
     * LED-strip position. The strip snakes column by column: even
     * columns run bottom-to-top, odd columns top-to-bottom.
     *
     * Inverse of the board-protocol decoder's serial→hold mapping;
     * ported from BoardSesh's `getMoonboardSerialPosition`.
     *
     * @throws IllegalArgumentException if [holdId] is outside 1..198.
     */
    fun serialPosition(holdId: Int): Int {
        require(holdId in 1..MAX_HOLD_ID) {
            "MoonBoard hold id out of range (1..$MAX_HOLD_ID): $holdId"
        }
        val zeroBased = holdId - 1
        val column = zeroBased % MoonBoardVariant.GRID_COLUMNS
        val row = zeroBased / MoonBoardVariant.GRID_COLUMNS
        return if (column % 2 == 0) {
            column * MoonBoardVariant.GRID_ROWS + row
        } else {
            column * MoonBoardVariant.GRID_ROWS + (MoonBoardVariant.GRID_ROWS - 1 - row)
        }
    }

    /**
     * Encode a climb's `frames` string (`p{holdId}r{roleCode}` pairs,
     * no separator) into the MoonBoard BLE wire frame.
     *
     * Holds with an out-of-range id or a role the board can't render
     * are skipped — the wire frame can only carry what the hardware
     * understands, so a malformed entry must not abort the whole send.
     */
    fun encode(frames: String): ByteArray = encodeToString(frames).encodeToByteArray()

    /** [encode] without the UTF-8 step — exposed for testing + logging. */
    fun encodeToString(frames: String): String {
        val tokens = parseHolds(frames).mapNotNull { (holdId, roleCode) ->
            val token = roleToken(roleCode) ?: return@mapNotNull null
            if (holdId !in 1..MAX_HOLD_ID) return@mapNotNull null
            "$token${serialPosition(holdId)}"
        }
        return FRAME_PREFIX + tokens.joinToString(",") + FRAME_SUFFIX
    }

    /**
     * Parse `p{holdId}r{roleCode}` pairs out of a `frames` string.
     * Tolerant of trailing separators / whitespace: each id/role is
     * read as its leading run of digits.
     *
     * Public: also consumed by the androidApp MoonBoard renderer to
     * resolve a climb's start / hand / finish holds for display.
     */
    fun parseHolds(frames: String): List<Pair<Int, Int>> =
        frames.split('p')
            .filter { it.isNotBlank() }
            .mapNotNull { segment ->
                val seg = segment.trim()
                val rIndex = seg.indexOf('r')
                if (rIndex <= 0) return@mapNotNull null
                val holdId = seg.substring(0, rIndex)
                    .takeWhile { it.isDigit() }
                    .toIntOrNull() ?: return@mapNotNull null
                val roleCode = seg.substring(rIndex + 1)
                    .takeWhile { it.isDigit() }
                    .toIntOrNull() ?: return@mapNotNull null
                holdId to roleCode
            }
}
