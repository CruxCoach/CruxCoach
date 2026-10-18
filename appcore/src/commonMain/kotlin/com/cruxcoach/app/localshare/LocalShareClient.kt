package com.cruxcoach.app.localshare

import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.util.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Receiver-side HTTP client, the port of Android's `LocalShareClient`. */
class LocalShareClient(
    private val transport: LocalShareTransport,
    private val files: FileSystem,
    private val hashing: Hashing,
    private val clock: NanoClock = NanoClock.MONOTONIC,
    private val retryDelayMs: Long = RETRY_MS,
    private val pollDelayMs: Long = MANIFEST_POLL_MS,
) {
    suspend fun awaitReadyManifest(
        baseUrl: String,
        timeoutMs: Long,
        protocolVersion: Int,
        expectedSessionId: String?,
        onPreparing: () -> Unit = {},
    ): LocalShareProtocol.Manifest {
        val deadline = clock.nanos() + timeoutMs * 1_000_000
        var failures = 0
        while (true) {
            try {
                val manifest = fetchManifest(baseUrl, protocolVersion = protocolVersion, expectedSessionId = expectedSessionId)
                when (manifest.boardStatus) {
                    "ready", "unavailable" -> return manifest
                    else -> {
                        failures = 0
                        onPreparing()
                        if (clock.nanos() >= deadline) throw LocalShareIoException("Timed out waiting for board snapshot")
                        delay(pollDelayMs)
                    }
                }
            } catch (error: ShareSessionChangedException) {
                throw error
            } catch (error: LocalShareIoException) {
                if (++failures > MAX_CONNECT_FAILURES || clock.nanos() >= deadline) throw error
                delay(retryDelayMs)
            }
        }
    }

    /**
     * Prefers v2. Only a 404, or the HTML landing page a pre-v2 sender serves for unknown
     * paths, counts as "this peer is older"; any other failure must not silently downgrade.
     */
    suspend fun fetchManifest(
        baseUrl: String,
        protocolVersion: Int? = null,
        expectedSessionId: String? = null,
        timeoutSeconds: Int = READ_TIMEOUT_SECONDS,
    ): LocalShareProtocol.Manifest {
        if (protocolVersion == null) {
            return try {
                fetchManifest(baseUrl, LocalShareProtocol.VERSION_V2, expectedSessionId, timeoutSeconds)
            } catch (error: HttpStatusException) {
                if (error.code != HTTP_NOT_FOUND) throw error
                fetchManifest(baseUrl, LocalShareProtocol.VERSION, expectedSessionId, timeoutSeconds)
            } catch (error: LegacyManifestEndpointException) {
                fetchManifest(baseUrl, LocalShareProtocol.VERSION, expectedSessionId, timeoutSeconds)
            }
        }
        require(protocolVersion == LocalShareProtocol.VERSION || protocolVersion == LocalShareProtocol.VERSION_V2) {
            "Unsupported share protocol"
        }
        val path = if (protocolVersion == LocalShareProtocol.VERSION_V2) LocalShareProtocol.V2_MANIFEST_PATH else LocalShareProtocol.MANIFEST_PATH
        val response = transport.fetch(
            method = "GET",
            url = LocalShareProtocol.artifactUrl(baseUrl, path),
            headers = buildMap {
                put("Accept", "application/json")
                expectedSessionId?.let { put(LocalShareProtocol.SESSION_HEADER, it) }
            },
            maxBodyBytes = MAX_MANIFEST_BYTES,
            timeoutSeconds = timeoutSeconds,
        )
        if (response.status == HTTP_CONFLICT) throw ShareSessionChangedException()
        if (response.status != HTTP_OK) throw HttpStatusException(response.status)
        val body = response.body.decodeToString()
        val manifest = try {
            LocalShareProtocol.parseManifest(body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val trimmed = body.trimStart().lowercase()
            if (protocolVersion == LocalShareProtocol.VERSION_V2 &&
                (trimmed.startsWith("<!doctype html") || trimmed.startsWith("<html"))
            ) {
                throw LegacyManifestEndpointException(error)
            }
            if (error is LimitExceeded) throw error
            throw InvalidManifestException(error)
        }
        if (manifest.protocolVersion != protocolVersion) {
            throw InvalidManifestException(IllegalArgumentException("Manifest protocol does not match request"))
        }
        if (expectedSessionId != null && manifest.sessionId != expectedSessionId) throw ShareSessionChangedException()
        return manifest
    }

    /** Arms the sender's snapshot preparation without downloading its body. */
    suspend fun requestSnapshotBuild(baseUrl: String, protocolVersion: Int, expectedSessionId: String?) {
        val path = if (protocolVersion == LocalShareProtocol.VERSION_V2) LocalShareProtocol.V2_BOARD_PATH else LocalShareProtocol.BOARD_PATH
        val response = transport.fetch(
            "HEAD", LocalShareProtocol.artifactUrl(baseUrl, path), sessionHeaders(expectedSessionId), 0, CONNECT_TIMEOUT_SECONDS,
        )
        if (response.status == HTTP_CONFLICT) throw ShareSessionChangedException()
        if (response.status != HTTP_OK && response.status != HTTP_SERVICE_UNAVAILABLE) {
            throw LocalShareIoException("Snapshot request HTTP ${response.status}")
        }
    }

    /** Best-effort: lets the sender tear its hotspot down. */
    suspend fun notifyDownloadComplete(baseUrl: String, expectedSessionId: String?) {
        val response = transport.fetch(
            "POST", LocalShareProtocol.artifactUrl(baseUrl, LocalShareProtocol.COMPLETE_PATH),
            sessionHeaders(expectedSessionId), MAX_MANIFEST_BYTES, CONNECT_TIMEOUT_SECONDS,
        )
        if (response.status == HTTP_CONFLICT) throw ShareSessionChangedException()
        if (response.status != HTTP_NO_CONTENT) throw LocalShareIoException("Completion HTTP ${response.status}")
    }

    /**
     * Downloads [artifact] to [targetPath], keeping `targetPath.part` across transport failures
     * and asking for the remaining byte range on a retry. The file appears at [targetPath] only
     * after its exact length and SHA-256 were verified.
     */
    suspend fun downloadResumable(
        baseUrl: String,
        artifact: LocalShareProtocol.Artifact,
        targetPath: String,
        protocolVersion: Int,
        expectedSessionId: String?,
        onVerifying: () -> Unit = {},
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): String {
        val partial = "$targetPath.part"
        val started = clock.nanos()
        val maximum = if (artifact.path == LocalShareProtocol.APK_PATH) LocalTransferLimits.MAX_APK_BYTES else LocalTransferLimits.MAX_COMPRESSED_DB_BYTES
        try {
            LocalTransferLimits.requireSize(artifact.sizeBytes, maximum)
        } catch (error: LimitExceeded) {
            files.delete(partial)
            throw error
        }
        if (files.exists(targetPath) && files.size(targetPath) == artifact.sizeBytes) {
            onVerifying()
            if (sha256(targetPath) == artifact.sha256) {
                onProgress(artifact.sizeBytes, artifact.sizeBytes)
                return targetPath
            }
        }
        files.delete(targetPath)
        if (length(partial) > artifact.sizeBytes) files.delete(partial)
        LocalTransferLimits.requireSpace(files.freeSpaceBytes(), artifact.sizeBytes - length(partial))
        var failures = 0
        while (length(partial) < artifact.sizeBytes) {
            try {
                downloadRemaining(baseUrl, artifact, partial, started, protocolVersion, expectedSessionId, onProgress)
                failures = 0
            } catch (error: LimitExceeded) {
                throw error
            } catch (error: ShareSessionChangedException) {
                throw error
            } catch (error: LocalShareIoException) {
                if (++failures > MAX_TRANSFER_FAILURES) throw error
                delay(retryDelayMs)
            }
        }
        onVerifying()
        if (length(partial) != artifact.sizeBytes || sha256(partial) != artifact.sha256) {
            files.delete(partial)
            throw VerificationFailed("Downloaded artifact failed SHA-256 verification")
        }
        if (!files.move(partial, targetPath)) {
            files.delete(partial)
            throw LocalShareIoException("Could not keep the verified artifact")
        }
        return targetPath
    }

    private suspend fun downloadRemaining(
        baseUrl: String,
        artifact: LocalShareProtocol.Artifact,
        partial: String,
        started: Long,
        protocolVersion: Int,
        expectedSessionId: String?,
        onProgress: (Long, Long) -> Unit,
    ) {
        LocalTransferLimits.requireTime(started, clock.nanos())
        val offset = length(partial)
        val headers = buildMap {
            if (offset > 0L) put("Range", "bytes=$offset-")
            if (artifact.path == LocalShareProtocol.APK_PATH) put(LocalShareProtocol.PROTOCOL_HEADER, protocolVersion.toString())
            expectedSessionId?.let { put(LocalShareProtocol.SESSION_HEADER, it) }
        }
        var lastSpaceCheck = offset
        val finalLength = transport.download(
            url = LocalShareProtocol.artifactUrl(baseUrl, artifact.path),
            headers = headers,
            path = partial,
            maxBodyBytes = artifact.sizeBytes - offset,
            timeoutSeconds = READ_TIMEOUT_SECONDS,
            onHead = { status, responseHeaders ->
                if (status == HTTP_CONFLICT) throw ShareSessionChangedException()
                val writeFrom = when {
                    offset > 0L && status == HTTP_PARTIAL -> {
                        val expectedRange = "bytes $offset-${artifact.sizeBytes - 1}/${artifact.sizeBytes}"
                        if (responseHeaders["content-range"].orEmpty() != expectedRange) {
                            throw LocalShareIoException("Server resumed at the wrong offset")
                        }
                        offset
                    }
                    status == HTTP_OK -> 0L
                    else -> throw LocalShareIoException("Artifact HTTP $status")
                }
                val contentLength = responseHeaders["content-length"]?.toLongOrNull()
                    ?: throw LocalShareIoException("Artifact response has no Content-Length")
                if (contentLength != artifact.sizeBytes - writeFrom) throw LocalShareIoException("Artifact response length mismatch")
                writeFrom
            },
            onProgress = { downloaded ->
                LocalTransferLimits.requireTime(started, clock.nanos())
                if (downloaded - lastSpaceCheck >= SPACE_CHECK_BYTES) {
                    lastSpaceCheck = downloaded
                    LocalTransferLimits.requireSpace(files.freeSpaceBytes(), artifact.sizeBytes - downloaded)
                }
                onProgress(downloaded, artifact.sizeBytes)
            },
        )
        if (finalLength < artifact.sizeBytes) {
            throw LocalShareIoException("Artifact ended at $finalLength/${artifact.sizeBytes} bytes")
        }
    }

    private fun sessionHeaders(sessionId: String?): Map<String, String> =
        sessionId?.let { mapOf(LocalShareProtocol.SESSION_HEADER to it) }.orEmpty()

    private fun length(path: String): Long = if (files.exists(path)) files.size(path) else 0L

    private fun sha256(path: String): String? = hashing.sha256OfFile(path)?.toHex()

    companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10
        const val READ_TIMEOUT_SECONDS = 60
        const val MANIFEST_POLL_MS = 3_000L
        const val RETRY_MS = 2_000L
        const val MAX_CONNECT_FAILURES = 5
        const val MAX_TRANSFER_FAILURES = 5
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val SPACE_CHECK_BYTES = 4L * 1024 * 1024
        const val HTTP_OK = 200
        const val HTTP_NO_CONTENT = 204
        const val HTTP_PARTIAL = 206
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}
