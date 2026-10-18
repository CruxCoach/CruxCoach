package com.cruxcoach.app.zip

/**
 * Raw DEFLATE (RFC 1951, no zlib or gzip wrapper): ZIP compression method 8.
 * The JVM actual is `java.util.zip` with `nowrap`, the iOS actual the system
 * zlib with windowBits -15 — the same codec Android's `ZipInputStream` and
 * `ZipOutputStream` use.
 */
internal expect object RawDeflate {
    /**
     * Inflates one complete raw DEFLATE stream. Returns null when the stream
     * is corrupt or truncated, when bytes follow its final block, or as soon as
     * the output would pass [maxOutputBytes] — so a decompression bomb costs at
     * most that limit plus one 64 KiB block of memory.
     */
    fun inflateRaw(data: ByteArray, maxOutputBytes: Int): ByteArray?

    /** Compresses [data] into one raw DEFLATE stream; [level] is zlib's 0..9. */
    fun deflateRaw(data: ByteArray, level: Int): ByteArray
}

/** Joins inflater/deflater output blocks into one array. */
internal fun joinBlocks(blocks: List<ByteArray>): ByteArray {
    val out = ByteArray(blocks.sumOf { it.size })
    var offset = 0
    for (block in blocks) {
        block.copyInto(out, offset)
        offset += block.size
    }
    return out
}

internal const val DEFLATE_BLOCK_BYTES = 64 * 1024
