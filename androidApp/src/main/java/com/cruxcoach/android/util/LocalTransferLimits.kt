package com.cruxcoach.android.util

import java.io.File
import java.io.IOException

/** Receiver policy, not a new wire contract. DB ceiling matches the existing inflater. */
internal object LocalTransferLimits {
    const val MAX_DB_BYTES = 1024L * 1024 * 1024
    const val MAX_APK_BYTES = MAX_DB_BYTES
    // Gzip can be slightly larger than an incompressible input.
    const val MAX_COMPRESSED_DB_BYTES = MAX_DB_BYTES + 16L * 1024 * 1024
    const val RESERVE_BYTES = 64L * 1024 * 1024
    const val MAX_DURATION_NANOS = 2L * 60 * 60 * 1_000_000_000

    class LimitExceeded(message: String) : IOException(message)

    fun requireSize(bytes: Long, maximum: Long) {
        if (bytes !in 1..maximum) throw LimitExceeded("Shared artifact exceeds receiver size limit")
    }

    fun requireSpace(directory: File, additionalBytes: Long) {
        if (additionalBytes < 0 || additionalBytes > directory.usableSpace - RESERVE_BYTES) {
            throw LimitExceeded("Insufficient space for shared artifact and storage reserve")
        }
    }

    fun requireTime(started: Long) {
        if (System.nanoTime() - started > MAX_DURATION_NANOS) {
            throw LimitExceeded("Shared artifact transfer exceeded two hours")
        }
    }
}
