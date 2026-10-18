package com.cruxcoach.app.localshare

import kotlin.time.TimeSource

/** Receiver policy (Android `LocalTransferLimits`), not a wire contract. */
object LocalTransferLimits {
    const val MAX_DB_BYTES = 1024L * 1024 * 1024
    const val MAX_APK_BYTES = MAX_DB_BYTES

    /** Gzip can be slightly larger than an incompressible input. */
    const val MAX_COMPRESSED_DB_BYTES = MAX_DB_BYTES + 16L * 1024 * 1024
    const val RESERVE_BYTES = 64L * 1024 * 1024
    const val MAX_DURATION_NANOS = 2L * 60 * 60 * 1_000_000_000

    fun requireSize(bytes: Long, maximum: Long) {
        if (bytes !in 1..maximum) throw LimitExceeded("Shared artifact exceeds receiver size limit")
    }

    /** [freeBytes] as the platform reports it; an unknown (0) answer fails closed, as on Android. */
    fun requireSpace(freeBytes: Long, additionalBytes: Long) {
        if (additionalBytes < 0 || additionalBytes > freeBytes - RESERVE_BYTES) {
            throw InsufficientSpace("Insufficient space for shared artifact and storage reserve")
        }
    }

    fun requireTime(startedNanos: Long, nowNanos: Long) {
        if (nowNanos - startedNanos > MAX_DURATION_NANOS) throw LimitExceeded("Shared artifact transfer exceeded two hours")
    }
}

/** Monotonic nanoseconds for the transfer deadline; replaceable in tests. */
fun interface NanoClock {
    fun nanos(): Long

    companion object {
        private val origin = TimeSource.Monotonic.markNow()
        val MONOTONIC = NanoClock { origin.elapsedNow().inWholeNanoseconds }
    }
}

/** A receiver limit was hit; never retried. */
open class LimitExceeded(message: String) : Exception(message)

class InsufficientSpace(message: String) : LimitExceeded(message)

/** A transport failure that a resumed request may overcome (Android's IOException). */
open class LocalShareIoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The sender restarted or another peer answered after consent; retrying would cross that boundary. */
class ShareSessionChangedException : LocalShareIoException("Offline-share session changed")

class HttpStatusException(val code: Int) : LocalShareIoException("HTTP $code")

class InvalidManifestException(cause: Throwable) : LocalShareIoException("Invalid share manifest", cause)

class LegacyManifestEndpointException(cause: Throwable) : LocalShareIoException("Peer does not expose a v2 manifest", cause)

/** The verified artifact did not match the manifest's hash or length. */
class VerificationFailed(message: String) : Exception(message)

class LocalHttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/**
 * Cleartext HTTP to a validated private-IPv4 origin, nothing else (Android's socket client).
 * Header names in responses are lower-case. Transport failures throw [LocalShareIoException].
 */
interface LocalShareTransport {
    /** A small response read whole; a body over [maxBodyBytes] fails. */
    suspend fun fetch(
        method: String,
        url: String,
        headers: Map<String, String>,
        maxBodyBytes: Int,
        timeoutSeconds: Int,
    ): LocalHttpResponse

    /**
     * Streams a response body into [path]. [onHead] sees status and headers first and returns
     * the byte offset to write from (the file is cut there), or throws to refuse the body.
     * At most [maxBodyBytes] are accepted. [onProgress] gets the file length after each block
     * and may throw to abort. Returns the final file length.
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        path: String,
        maxBodyBytes: Long,
        timeoutSeconds: Int,
        onHead: (status: Int, headers: Map<String, String>) -> Long,
        onProgress: (fileLength: Long) -> Unit,
    ): Long
}

/** Streaming gzip inflation file to file, bounded; the platform owns the zlib. */
fun interface FileGunzip {
    /** Returns the bytes written; throws when the input is corrupt or the output would pass [maxOutputBytes]. */
    fun gunzip(sourcePath: String, destinationPath: String, maxOutputBytes: Long, onProgress: (Long) -> Unit): Long
}
