package com.cruxcoach.app.zip

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.memset
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/**
 * The system zlib; windowBits -15 selects a raw stream (no zlib header or
 * Adler-32 trailer), exactly what a ZIP entry holds. Same block loop as
 * [com.cruxcoach.app.platform.IosGzip].
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object RawDeflate {
    private const val RAW_WINDOW_BITS = -15
    private const val MEM_LEVEL = 8

    actual fun inflateRaw(data: ByteArray, maxOutputBytes: Int): ByteArray? {
        // An empty array cannot be pinned, and no valid stream is empty.
        if (data.isEmpty() || maxOutputBytes < 0) return null
        return memScoped {
            val stream = alloc<z_stream>()
            memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
            val initialised = inflateInit2_(stream.ptr, RAW_WINDOW_BITS, ZLIB_VERSION, sizeOf<z_stream>().toInt())
            if (initialised != Z_OK) return@memScoped null
            try {
                val blocks = ArrayList<ByteArray>()
                var total = 0L
                val block = ByteArray(DEFLATE_BLOCK_BYTES)
                data.usePinned { pinnedInput ->
                    block.usePinned { pinnedBlock ->
                        stream.next_in = pinnedInput.addressOf(0).reinterpret<UByteVar>()
                        stream.avail_in = data.size.toUInt()
                        while (true) {
                            stream.next_out = pinnedBlock.addressOf(0).reinterpret<UByteVar>()
                            stream.avail_out = DEFLATE_BLOCK_BYTES.toUInt()
                            val code = inflate(stream.ptr, Z_NO_FLUSH)
                            // Z_BUF_ERROR (truncated input), Z_DATA_ERROR, Z_NEED_DICT, Z_MEM_ERROR: all fatal.
                            if (code != Z_OK && code != Z_STREAM_END) return@memScoped null
                            val produced = DEFLATE_BLOCK_BYTES - stream.avail_out.toInt()
                            total += produced
                            if (total > maxOutputBytes) return@memScoped null
                            if (produced > 0) blocks.add(block.copyOf(produced))
                            if (code == Z_STREAM_END) {
                                // A ZIP entry is exactly one stream: trailing bytes are corruption.
                                if (stream.avail_in != 0u) return@memScoped null
                                break
                            }
                            if (produced == 0 && stream.avail_in == 0u) {
                                // Input exhausted before the final block: truncated stream.
                                return@memScoped null
                            }
                        }
                    }
                }
                joinBlocks(blocks)
            } finally {
                inflateEnd(stream.ptr)
            }
        }
    }

    actual fun deflateRaw(data: ByteArray, level: Int): ByteArray {
        require(level in 0..9) { "Invalid deflate level" }
        val result = memScoped {
            val stream = alloc<z_stream>()
            memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
            val initialised = deflateInit2_(
                stream.ptr, level, Z_DEFLATED, RAW_WINDOW_BITS, MEM_LEVEL, Z_DEFAULT_STRATEGY,
                ZLIB_VERSION, sizeOf<z_stream>().toInt(),
            )
            if (initialised != Z_OK) return@memScoped null
            try {
                // A one-byte stand-in keeps the pointer valid for empty input; avail_in stays 0.
                val input = if (data.isEmpty()) ByteArray(1) else data
                val blocks = ArrayList<ByteArray>()
                val block = ByteArray(DEFLATE_BLOCK_BYTES)
                input.usePinned { pinnedInput ->
                    block.usePinned { pinnedBlock ->
                        stream.next_in = pinnedInput.addressOf(0).reinterpret<UByteVar>()
                        stream.avail_in = data.size.toUInt()
                        while (true) {
                            stream.next_out = pinnedBlock.addressOf(0).reinterpret<UByteVar>()
                            stream.avail_out = DEFLATE_BLOCK_BYTES.toUInt()
                            val code = deflate(stream.ptr, Z_FINISH)
                            if (code != Z_OK && code != Z_STREAM_END) return@memScoped null
                            val produced = DEFLATE_BLOCK_BYTES - stream.avail_out.toInt()
                            if (produced > 0) blocks.add(block.copyOf(produced))
                            if (code == Z_STREAM_END) break
                        }
                    }
                }
                joinBlocks(blocks)
            } finally {
                deflateEnd(stream.ptr)
            }
        }
        return result ?: error("zlib deflate failed")
    }
}
