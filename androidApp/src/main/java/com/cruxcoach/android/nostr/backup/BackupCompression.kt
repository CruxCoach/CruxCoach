package com.cruxcoach.android.nostr.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * gzip wrap/unwrap for the backup payload.
 *
 * Target ratio on realistic JSON (UUIDs, ISO timestamps, numeric fields) is
 * ~5:1 — a 2-year power user's 483 KB JSON compresses to ~94 KB. The output
 * is handed directly to [BackupCrypto] as raw bytes; no Base64 round-trip.
 *
 * No new dependencies — `java.util.zip.GZIPOutputStream` is built in.
 */
internal object BackupCompression {

    fun compress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size.coerceAtLeast(256) / 4)
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /**
     * Decompress gzip back to plaintext, hard-capped at [maxBytes] to block
     * decompression bombs (small gzip → gigabytes of plaintext → OOM).
     * The restore path calls this with a generous cap (~64 MB, many times
     * any realistic backup's plaintext size) so legitimate backups still
     * round-trip while a crafted bomb is refused before it blows the heap.
     *
     * Throws [BackupException] as soon as the running byte count crosses
     * the cap — the partially-written buffer is discarded, not returned.
     */
    fun decompress(compressed: ByteArray, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    throw BackupException(BackupErrorReason.PlaintextSizeCap(maxBytes = maxBytes))
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }
}
