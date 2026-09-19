package com.cruxcoach.app.platform

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
import platform.zlib.Z_DEFAULT_COMPRESSION
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
import platform.zlib.inflateReset
import platform.zlib.z_stream

/**
 * [Gzip] on the system zlib. windowBits 15+16 selects the gzip container.
 * Output is produced in 64 KiB blocks, and inflation stops the moment the total
 * would pass the caller's limit, so a decompression bomb costs at most
 * `maxOutputBytes` + one block of memory. Any zlib error yields null.
 */
@OptIn(ExperimentalForeignApi::class)
class IosGzip : Gzip {

    override fun compress(data: ByteArray): ByteArray? = memScoped {
        val stream = alloc<z_stream>()
        memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
        val initialised = deflateInit2_(
            stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, GZIP_WINDOW_BITS, MEM_LEVEL, Z_DEFAULT_STRATEGY,
            ZLIB_VERSION, sizeOf<z_stream>().toInt(),
        )
        if (initialised != Z_OK) return@memScoped null
        try {
            // A one-byte stand-in keeps the pointer valid for empty input; avail_in stays 0.
            val input = if (data.isEmpty()) ByteArray(1) else data
            val chunks = ArrayList<ByteArray>()
            val block = ByteArray(BLOCK_BYTES)
            input.usePinned { pinnedInput ->
                block.usePinned { pinnedBlock ->
                    stream.next_in = pinnedInput.addressOf(0).reinterpret<UByteVar>()
                    stream.avail_in = data.size.toUInt()
                    while (true) {
                        stream.next_out = pinnedBlock.addressOf(0).reinterpret<UByteVar>()
                        stream.avail_out = BLOCK_BYTES.toUInt()
                        val code = deflate(stream.ptr, Z_FINISH)
                        if (code != Z_OK && code != Z_STREAM_END) return@memScoped null
                        val produced = BLOCK_BYTES - stream.avail_out.toInt()
                        if (produced > 0) chunks.add(block.copyOf(produced))
                        if (code == Z_STREAM_END) break
                    }
                }
            }
            join(chunks)
        } finally {
            deflateEnd(stream.ptr)
        }
    }

    override fun decompress(data: ByteArray, maxOutputBytes: Long): ByteArray? {
        if (data.isEmpty() || maxOutputBytes < 0L) return null
        return memScoped {
            val stream = alloc<z_stream>()
            memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
            val initialised = inflateInit2_(stream.ptr, GZIP_WINDOW_BITS, ZLIB_VERSION, sizeOf<z_stream>().toInt())
            if (initialised != Z_OK) return@memScoped null
            try {
                val chunks = ArrayList<ByteArray>()
                var total = 0L
                val block = ByteArray(BLOCK_BYTES)
                data.usePinned { pinnedInput ->
                    block.usePinned { pinnedBlock ->
                        stream.next_in = pinnedInput.addressOf(0).reinterpret<UByteVar>()
                        stream.avail_in = data.size.toUInt()
                        while (true) {
                            stream.next_out = pinnedBlock.addressOf(0).reinterpret<UByteVar>()
                            stream.avail_out = BLOCK_BYTES.toUInt()
                            val code = inflate(stream.ptr, Z_NO_FLUSH)
                            // Z_BUF_ERROR (truncated input), Z_DATA_ERROR, Z_NEED_DICT, Z_MEM_ERROR: all fatal.
                            if (code != Z_OK && code != Z_STREAM_END) return@memScoped null
                            val produced = BLOCK_BYTES - stream.avail_out.toInt()
                            total += produced
                            if (total > maxOutputBytes || total > Int.MAX_VALUE) return@memScoped null
                            if (produced > 0) chunks.add(block.copyOf(produced))
                            if (code == Z_STREAM_END) {
                                if (stream.avail_in == 0u) break
                                // Concatenated gzip members are one logical stream (as on the JVM);
                                // trailing bytes that are not a member fail on the next inflate.
                                if (inflateReset(stream.ptr) != Z_OK) return@memScoped null
                            } else if (produced == 0 && stream.avail_in == 0u) {
                                // Input exhausted before the trailer: truncated stream.
                                return@memScoped null
                            }
                        }
                    }
                }
                join(chunks)
            } finally {
                inflateEnd(stream.ptr)
            }
        }
    }

    private fun join(chunks: List<ByteArray>): ByteArray {
        val out = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    private companion object {
        const val BLOCK_BYTES = 64 * 1024
        const val GZIP_WINDOW_BITS = 15 + 16
        const val MEM_LEVEL = 8
    }
}
