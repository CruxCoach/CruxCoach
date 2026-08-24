package com.cruxcoach.domain.board

/**
 * Binary codec for climb-frame strings (delta-format wire shape).
 *
 * Encodes the TEXT frame format into a compact BLOB (3 bytes per hold entry)
 * and decodes back to the original TEXT format for transparent use by parsers.
 *
 * **Single-frame (boulder)**: `"p1091r15p1096r15p1163r12"`
 *   → `[low][high][roleId]` per hold, no header.
 *
 * **Multi-frame (route)**: `"p100r42p200r45,x100p300r43,x200p400r44"`
 *   → `[0xFF][frameCount][len_lo][len_hi][entries...][len_lo][len_hi][entries...]...`
 *   Removal entries (`x{id}`) use `0xFE` as the third byte.
 *
 * Magic byte `0xFF` distinguishes multi-frame for placement IDs that fit the
 * legacy unsigned 16-bit representation. Boards such as Quantum use larger
 * placement IDs; those strings are stored as UTF-8 text BLOBs so no bits are
 * discarded. [decode] already accepts that backwards-compatible shape.
 * Removal marker `0xFE` is outside valid roleId range (12-15, 42-45).
 */
object FramesBinaryCodec {

    private const val MULTI_FRAME_MAGIC: Byte = 0xFF.toByte()
    private const val REMOVAL_MARKER: Byte = 0xFE.toByte()
    private const val BYTES_PER_ENTRY = 3

    // Three disjoint entry shapes in delta- and range-format frame strings:
    //   p{id}r{role}  — delta-format hold
    //   x{id}         — delta-format removal (no role)
    //   h{id}p{role}  — Kilter climbConcat hold
    // A single `[pxh](\d+)[rp]?(\d*)` collapses them, but greedy matching
    // then swallows the following entry's prefix (e.g. `x100p300r43` parses
    // as a single `type=x,id=100,role=300` and drops `r43`). Alternation
    // forces each branch to consume exactly its own shape.
    private val ENTRY_REGEX = Regex("p(\\d+)r(\\d+)|x(\\d+)|h(\\d+)p(\\d+)")

    fun encode(framesText: String): ByteArray {
        if (framesText.isEmpty()) return ByteArray(0)

        val frames = framesText.split(",")
        // The compact legacy shape only allocates two bytes to placement IDs.
        // Falling back to the already-supported text BLOB representation is
        // essential for Quantum IDs (currently in the tens of millions):
        // masking those IDs into two bytes silently changes the selected hold.
        if (frames.any { frame ->
                parseEntries(frame).any { entry ->
                    entry.id !in 0..0xFFFF ||
                        (entry.type != 'x' && entry.role !in 0 until (REMOVAL_MARKER.toInt() and 0xFF))
                }
            }
        ) {
            return framesText.encodeToByteArray()
        }
        if (frames.size == 1) {
            return encodeSingleFrame(frames[0])
        }
        return encodeMultiFrame(frames)
    }

    fun decode(blob: ByteArray): String {
        if (blob.isEmpty()) return ""

        if (blob[0] == MULTI_FRAME_MAGIC && isWellFormedMultiFrame(blob)) {
            return decodeMultiFrame(blob)
        }
        // Detect raw UTF-8 text BLOBs (from CAST migration, not yet binary-encoded).
        // Text format chars: p, x, r, 0-9, comma. Binary format always has roleId
        // bytes (12-15, 42-45, 254) by position 2 which are outside this set.
        if (looksLikeText(blob)) {
            return blob.decodeToString()
        }
        return decodeSingleFrame(blob)
    }

    /**
     * A single-frame placement may also start with 0xFF when its ID's low byte
     * is 255. Treat the marker as a multi-frame header only when every declared
     * frame has a complete, three-byte-aligned body and consumes the full BLOB.
     */
    private fun isWellFormedMultiFrame(blob: ByteArray): Boolean {
        if (blob.size < 8) return false // two frames need header + at least one entry each
        val frameCount = blob[1].toInt() and 0xFF
        if (frameCount < 2) return false

        var pos = 2
        repeat(frameCount) {
            if (pos + 2 > blob.size) return false
            val frameLen =
                (blob[pos].toInt() and 0xFF) or ((blob[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            if (frameLen % BYTES_PER_ENTRY != 0 || pos + frameLen > blob.size) return false
            pos += frameLen
        }
        return pos == blob.size
    }

    private fun looksLikeText(blob: ByteArray): Boolean {
        if (blob.size < 4) return false
        val checkLen = minOf(20, blob.size)
        for (i in 0 until checkLen) {
            val b = blob[i].toInt() and 0xFF
            // Valid text chars: p, x, r, comma, digits (delta) + h (range climbConcat)
            if (b != 0x70 && b != 0x78 && b != 0x72 && b != 0x68 && b != 0x2C &&
                !(b in 0x30..0x39)
            ) return false
        }
        return true
    }

    // ── Single-frame encoding ─────────────────────────────────────

    private fun encodeSingleFrame(frame: String): ByteArray {
        val entries = parseEntries(frame)
        val buf = ByteArray(entries.size * BYTES_PER_ENTRY)
        var pos = 0
        for ((type, id, role) in entries) {
            buf[pos++] = (id and 0xFF).toByte()
            buf[pos++] = ((id shr 8) and 0xFF).toByte()
            buf[pos++] = if (type == 'x') REMOVAL_MARKER else role.toByte()
        }
        return buf
    }

    private fun decodeSingleFrame(blob: ByteArray): String {
        val sb = StringBuilder()
        var pos = 0
        while (pos + BYTES_PER_ENTRY <= blob.size) {
            val low = blob[pos++].toInt() and 0xFF
            val high = blob[pos++].toInt() and 0xFF
            val roleByte = blob[pos++]
            val id = low or (high shl 8)
            if (roleByte == REMOVAL_MARKER) {
                sb.append("x${id}")
            } else {
                sb.append("p${id}r${roleByte.toInt() and 0xFF}")
            }
        }
        return sb.toString()
    }

    // ── Multi-frame encoding ──────────────────────────────────────

    private fun encodeMultiFrame(frames: List<String>): ByteArray {
        val frameBlobs = frames.map { encodeSingleFrame(it) }
        // header: 1 (magic) + 1 (count) + 2 * frameCount (lengths)
        val totalSize = 2 + frames.size * 2 + frameBlobs.sumOf { it.size }
        val buf = ByteArray(totalSize)
        var pos = 0
        buf[pos++] = MULTI_FRAME_MAGIC
        buf[pos++] = frames.size.toByte()
        for (fb in frameBlobs) {
            buf[pos++] = (fb.size and 0xFF).toByte()
            buf[pos++] = ((fb.size shr 8) and 0xFF).toByte()
            fb.copyInto(buf, pos)
            pos += fb.size
        }
        return buf
    }

    private fun decodeMultiFrame(blob: ByteArray): String {
        var pos = 1 // skip magic
        val frameCount = blob[pos++].toInt() and 0xFF
        val sb = StringBuilder()
        for (i in 0 until frameCount) {
            val lenLow = blob[pos++].toInt() and 0xFF
            val lenHigh = blob[pos++].toInt() and 0xFF
            val frameLen = lenLow or (lenHigh shl 8)
            if (i > 0) sb.append(',')
            val frameBlob = blob.copyOfRange(pos, pos + frameLen)
            sb.append(decodeSingleFrame(frameBlob))
            pos += frameLen
        }
        return sb.toString()
    }

    // ── Text parsing ──────────────────────────────────────────────

    private data class Entry(val type: Char, val id: Int, val role: Int)

    private fun parseEntries(frame: String): List<Entry> {
        return ENTRY_REGEX.findAll(frame).map { match ->
            val g = match.groupValues
            when {
                g[1].isNotEmpty() -> Entry('p', g[1].toInt(), g[2].toInt()) // p{id}r{role}
                g[3].isNotEmpty() -> Entry('x', g[3].toInt(), 0)            // x{id}
                else -> Entry('h', g[4].toInt(), g[5].toInt())              // h{id}p{role}
            }
        }.toList()
    }
}
