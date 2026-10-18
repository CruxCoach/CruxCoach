package com.cruxcoach.app.zip

import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** The JVM (unit-test host) side: `java.util.zip` in `nowrap` mode, as `ZipInputStream` uses it. */
internal actual object RawDeflate {
    actual fun inflateRaw(data: ByteArray, maxOutputBytes: Int): ByteArray? {
        if (data.isEmpty() || maxOutputBytes < 0) return null
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data)
            val blocks = ArrayList<ByteArray>()
            val block = ByteArray(DEFLATE_BLOCK_BYTES)
            var total = 0L
            while (!inflater.finished()) {
                val produced = inflater.inflate(block)
                if (produced == 0) {
                    if (inflater.finished()) break
                    // No progress into an empty 64 KiB block: the input ended before the
                    // final block, or a dictionary (never valid in a raw stream) is asked for.
                    return null
                }
                total += produced
                if (total > maxOutputBytes) return null
                blocks.add(block.copyOf(produced))
            }
            if (inflater.remaining > 0) return null
            joinBlocks(blocks)
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    actual fun deflateRaw(data: ByteArray, level: Int): ByteArray {
        require(level in 0..9) { "Invalid deflate level" }
        val deflater = Deflater(level, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val blocks = ArrayList<ByteArray>()
            val block = ByteArray(DEFLATE_BLOCK_BYTES)
            while (!deflater.finished()) {
                val produced = deflater.deflate(block)
                if (produced > 0) blocks.add(block.copyOf(produced))
            }
            return joinBlocks(blocks)
        } finally {
            deflater.end()
        }
    }
}
