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

    fun decompress(compressed: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
}
