package com.cruxcoach.android.util

import android.net.Network
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.delay

/** Receiver-side HTTP client. Every connection is opened through [Network]. */
class LocalShareClient {

    suspend fun awaitReadyManifest(
        network: Network,
        baseUrl: String,
        timeoutMs: Long,
        onPreparing: () -> Unit = {},
        protocolVersion: Int = LocalShareProtocol.VERSION_V2,
        expectedSessionId: String? = null,
    ): LocalShareProtocol.Manifest {
        val deadline = System.currentTimeMillis() + timeoutMs
        var failures = 0
        while (true) {
            try {
                val manifest = fetchManifest(
                    network,
                    baseUrl,
                    protocolVersion = protocolVersion,
                    expectedSessionId = expectedSessionId,
                )
                when (manifest.boardStatus) {
                    "ready", "unavailable" -> return manifest
                    else -> {
                        failures = 0
                        onPreparing()
                        if (System.currentTimeMillis() >= deadline) {
                            throw IOException("Timed out waiting for board snapshot")
                        }
                        delay(MANIFEST_POLL_MS)
                    }
                }
            } catch (error: ShareSessionChangedException) {
                throw error
            } catch (error: IOException) {
                if (++failures > MAX_CONNECT_FAILURES || System.currentTimeMillis() >= deadline) {
                    throw error
                }
                Log.w(TAG, "Manifest request failed; retrying $failures/$MAX_CONNECT_FAILURES", error)
                delay(RETRY_MS)
            }
        }
    }

    fun fetchManifest(
        network: Network,
        baseUrl: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        protocolVersion: Int? = null,
        expectedSessionId: String? = null,
    ): LocalShareProtocol.Manifest {
        if (protocolVersion == null) {
            try {
                return fetchManifest(
                    network, baseUrl, connectTimeoutMs, readTimeoutMs,
                    LocalShareProtocol.VERSION_V2,
                    expectedSessionId,
                )
            } catch (error: HttpStatusException) {
                if (error.code != HTTP_NOT_FOUND) throw error
                return fetchManifest(
                    network, baseUrl, connectTimeoutMs, readTimeoutMs,
                    LocalShareProtocol.VERSION,
                    expectedSessionId,
                )
            } catch (error: LegacyManifestEndpointException) {
                // Pre-v2 servers return their HTML landing page (HTTP 200)
                // for unknown paths. Treat only that recognizable HTML as
                // evidence that this is a legacy peer; transport and artifact
                // failures must not silently downgrade the protocol.
                return fetchManifest(
                    network, baseUrl, connectTimeoutMs, readTimeoutMs,
                    LocalShareProtocol.VERSION,
                    expectedSessionId,
                )
            }
        }
        require(
            protocolVersion == LocalShareProtocol.VERSION ||
                protocolVersion == LocalShareProtocol.VERSION_V2,
        ) { "Unsupported share protocol" }
        val manifestPath = if (protocolVersion == LocalShareProtocol.VERSION_V2) {
            LocalShareProtocol.V2_MANIFEST_PATH
        } else {
            LocalShareProtocol.MANIFEST_PATH
        }
        val url = URL(LocalShareProtocol.artifactUrl(baseUrl, manifestPath))
        return openResponse(
            network = network,
            url = url,
            headers = buildMap {
                put("Accept", "application/json")
                expectedSessionId?.let { put(LocalShareProtocol.SESSION_HEADER, it) }
            },
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        ).use { response ->
            if (response.code == HTTP_CONFLICT) throw ShareSessionChangedException()
            if (response.code != HTTP_OK) throw HttpStatusException(response.code)
            val body = response.readBody(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
            val manifest = runCatching { LocalShareProtocol.parseManifest(body) }
                .getOrElse { error ->
                    if (protocolVersion == LocalShareProtocol.VERSION_V2 &&
                        body.trimStart().lowercase().let {
                            it.startsWith("<!doctype html") || it.startsWith("<html")
                        }
                    ) {
                        throw LegacyManifestEndpointException(error)
                    }
                    throw InvalidManifestException(error)
                }
            if (manifest.protocolVersion != protocolVersion) {
                throw InvalidManifestException(
                    IllegalArgumentException("Manifest protocol does not match request"),
                )
            }
            if (expectedSessionId != null && manifest.sessionId != expectedSessionId) {
                throw ShareSessionChangedException()
            }
            manifest
        }
    }

    /** Arms sender-side snapshot preparation without downloading its body. */
    fun requestSnapshotBuild(
        network: Network,
        baseUrl: String,
        protocolVersion: Int = LocalShareProtocol.VERSION_V2,
        expectedSessionId: String? = null,
    ) {
        require(
            protocolVersion == LocalShareProtocol.VERSION ||
                protocolVersion == LocalShareProtocol.VERSION_V2,
        ) { "Unsupported share protocol" }
        val path = if (protocolVersion == LocalShareProtocol.VERSION_V2) {
            LocalShareProtocol.V2_BOARD_PATH
        } else LocalShareProtocol.BOARD_PATH
        val url = URL(LocalShareProtocol.artifactUrl(baseUrl, path))
        val headers = expectedSessionId?.let {
            mapOf(LocalShareProtocol.SESSION_HEADER to it)
        }.orEmpty()
        openResponse(network, url, headers = headers, method = "HEAD").use { response ->
            if (response.code == HTTP_CONFLICT) throw ShareSessionChangedException()
            if (response.code !in setOf(HTTP_OK, HTTP_SERVICE_UNAVAILABLE)) {
                throw IOException("Snapshot request HTTP ${response.code}")
            }
        }
    }

    /** Best-effort end-of-download signal. The sender can now tear down its
     * hotspot, which also returns manually connected fresh installs to their
     * previous Wi-Fi — something the newly installed app cannot request
     * directly because it never received the hotspot password. */
    fun notifyDownloadComplete(
        network: Network,
        baseUrl: String,
        expectedSessionId: String? = null,
    ) {
        val url = URL(LocalShareProtocol.artifactUrl(baseUrl, LocalShareProtocol.COMPLETE_PATH))
        val headers = expectedSessionId?.let {
            mapOf(LocalShareProtocol.SESSION_HEADER to it)
        }.orEmpty()
        openResponse(network, url, headers = headers, method = "POST").use { response ->
            if (response.code == HTTP_CONFLICT) throw ShareSessionChangedException()
            if (response.code != HTTP_NO_CONTENT) {
                throw IOException("Completion HTTP ${response.code}")
            }
        }
    }

    /**
     * Downloads [artifact] into [target] and keeps `target.part` on transport
     * failures. A retry asks for the remaining byte range; the completed file
     * is exposed only after exact length and SHA-256 verification.
     */
    suspend fun downloadResumable(
        network: Network,
        baseUrl: String,
        artifact: LocalShareProtocol.Artifact,
        target: File,
        protocolVersion: Int = LocalShareProtocol.VERSION,
        expectedSessionId: String? = null,
        onVerifying: () -> Unit = {},
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File {
        val partial = File(target.path + ".part")
        target.parentFile?.mkdirs()
        if (target.exists() && target.length() == artifact.sizeBytes) {
            onVerifying()
            if (LocalShareProtocol.sha256(target) == artifact.sha256) {
                onProgress(artifact.sizeBytes, artifact.sizeBytes)
                return target
            }
        }
        target.delete()
        if (partial.length() > artifact.sizeBytes) partial.delete()

        var failures = 0
        while (partial.length() < artifact.sizeBytes) {
            try {
                downloadRemaining(
                    network,
                    baseUrl,
                    artifact,
                    partial,
                    protocolVersion,
                    expectedSessionId,
                    onProgress,
                )
                failures = 0
            } catch (error: ShareSessionChangedException) {
                throw error
            } catch (error: IOException) {
                if (++failures > MAX_TRANSFER_FAILURES) throw error
                Log.w(
                    TAG,
                    "Transfer interrupted at ${partial.length()}/${artifact.sizeBytes}; " +
                        "resuming $failures/$MAX_TRANSFER_FAILURES",
                    error,
                )
                delay(RETRY_MS)
            }
        }
        onVerifying()
        if (partial.length() != artifact.sizeBytes ||
            LocalShareProtocol.sha256(partial) != artifact.sha256
        ) {
            partial.delete()
            throw IOException("Downloaded artifact failed SHA-256 verification")
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        return target
    }

    private fun downloadRemaining(
        network: Network,
        baseUrl: String,
        artifact: LocalShareProtocol.Artifact,
        partial: File,
        protocolVersion: Int,
        expectedSessionId: String?,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        var offset = partial.length()
        val url = URL(LocalShareProtocol.artifactUrl(baseUrl, artifact.path))
        require(protocolVersion == LocalShareProtocol.VERSION ||
            protocolVersion == LocalShareProtocol.VERSION_V2
        ) { "Unsupported share protocol" }
        val requestHeaders = buildMap {
            if (offset > 0L) put("Range", "bytes=$offset-")
            if (artifact.path == LocalShareProtocol.APK_PATH) {
                put(LocalShareProtocol.PROTOCOL_HEADER, protocolVersion.toString())
            }
            expectedSessionId?.let { put(LocalShareProtocol.SESSION_HEADER, it) }
        }
        openResponse(network, url, requestHeaders).use { response ->
            val code = response.code
            if (code == HTTP_CONFLICT) throw ShareSessionChangedException()
            val append = when {
                offset > 0L && code == HTTP_PARTIAL -> {
                    val range = response.headers["content-range"].orEmpty()
                    val expectedRange =
                        "bytes $offset-${artifact.sizeBytes - 1}/${artifact.sizeBytes}"
                    if (range != expectedRange) {
                        throw IOException("Server resumed at the wrong offset")
                    }
                    true
                }
                code == HTTP_OK -> {
                    offset = 0L
                    false
                }
                else -> throw IOException("Artifact HTTP $code")
            }
            val expectedBodyBytes = artifact.sizeBytes - offset
            val contentLength = response.headers["content-length"]?.toLongOrNull()
                ?: throw IOException("Artifact response has no Content-Length")
            if (contentLength != expectedBodyBytes) {
                throw IOException("Artifact response length mismatch")
            }
            FileOutputStream(partial, append).use { output ->
                val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                var downloaded = offset
                while (downloaded < artifact.sizeBytes) {
                    val wanted = minOf(buffer.size.toLong(), artifact.sizeBytes - downloaded).toInt()
                    val read = response.input.read(buffer, 0, wanted)
                    if (read < 0) {
                        throw IOException(
                            "Artifact ended at $downloaded/${artifact.sizeBytes} bytes",
                        )
                    }
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, artifact.sizeBytes)
                }
                output.fd.sync()
            }
        }
    }

    /**
     * Tiny HTTP/1.1 client over [Network.socketFactory]. Besides guaranteeing
     * per-request routing, this deliberately keeps local cleartext confined to
     * validated private IPv4 literals. Android's XML network-security config
     * cannot express RFC1918 CIDR ranges, while LocalOnlyHotspot may choose a
     * gateway other than the two historical fixed addresses.
     */
    private fun openResponse(
        network: Network,
        url: URL,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): HttpResponse {
        if (url.protocol != "http" || !LocalShareProtocol.isPrivateIpv4(url.host)) {
            throw IOException("Local-share URL must use private IPv4 HTTP")
        }
        val port = url.port.takeIf { it > 0 } ?: throw IOException("Missing local-share port")
        if (method != "GET" && method != "HEAD" && method != "POST") {
            throw IOException("Unsupported HTTP method")
        }
        val socket = network.socketFactory.createSocket()
        try {
            socket.receiveBufferSize = SOCKET_BUFFER_SIZE
            socket.sendBufferSize = SOCKET_BUFFER_SIZE
            socket.tcpNoDelay = true
            socket.soTimeout = readTimeoutMs
            socket.connect(InetSocketAddress(url.host, port), connectTimeoutMs)
            val requestTarget = url.file.takeIf { it.isNotEmpty() } ?: "/"
            val output = socket.getOutputStream().buffered()
            output.write("$method $requestTarget HTTP/1.1\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Host: ${url.host}:$port\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Connection: close\r\n".toByteArray(Charsets.US_ASCII))
            headers.forEach { (name, value) ->
                if (!HEADER_NAME.matches(name) || value.any { it == '\r' || it == '\n' }) {
                    throw IOException("Invalid HTTP request header")
                }
                output.write("$name: $value\r\n".toByteArray(Charsets.US_ASCII))
            }
            output.write("\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()

            val input = socket.getInputStream()
            val status = readAsciiLine(input)
            val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("Invalid HTTP status line")
            val responseHeaders = buildMap {
                while (true) {
                    val line = readAsciiLine(input)
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator <= 0) throw IOException("Invalid HTTP response header")
                    put(
                        line.substring(0, separator).trim().lowercase(),
                        line.substring(separator + 1).trim(),
                    )
                }
            }
            return HttpResponse(socket, input, code, responseHeaders)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun readAsciiLine(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value < 0) throw IOException("Unexpected EOF in HTTP headers")
            if (value == '\n'.code) {
                val raw = bytes.toByteArray()
                val length = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) {
                    raw.size - 1
                } else {
                    raw.size
                }
                return String(raw, 0, length, Charsets.US_ASCII)
            }
            bytes.write(value)
        }
        throw IOException("HTTP header line too long")
    }

    private class HttpResponse(
        private val socket: Socket,
        val input: InputStream,
        val code: Int,
        val headers: Map<String, String>,
    ) : Closeable {
        fun readBody(maxBytes: Int): ByteArray {
            val declared = headers["content-length"]?.toLongOrNull()
                ?: throw IOException("HTTP response has no Content-Length")
            if (declared !in 0..maxBytes.toLong()) throw IOException("HTTP response is too large")
            val result = ByteArray(declared.toInt())
            var offset = 0
            while (offset < result.size) {
                val read = input.read(result, offset, result.size - offset)
                if (read < 0) throw IOException("HTTP response ended early")
                offset += read
            }
            return result
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private class HttpStatusException(val code: Int) : IOException("Manifest HTTP $code")

    private class InvalidManifestException(cause: Throwable) :
        IOException("Invalid share manifest", cause)

    private class LegacyManifestEndpointException(cause: Throwable) :
        IOException("Peer does not expose a v2 manifest", cause)

    private companion object {
        const val TRANSFER_BUFFER_SIZE = 512 * 1024
        const val SOCKET_BUFFER_SIZE = 1024 * 1024
        const val TAG = "LocalShareClient"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
        const val MANIFEST_POLL_MS = 3_000L
        const val RETRY_MS = 2_000L
        const val MAX_CONNECT_FAILURES = 5
        const val MAX_TRANSFER_FAILURES = 5
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val HTTP_NO_CONTENT = 204
        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val HTTP_CONFLICT = 409
        const val HTTP_NOT_FOUND = 404
        const val MAX_MANIFEST_BYTES = 1 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 8 * 1024
        val HEADER_NAME = Regex("[A-Za-z0-9-]{1,64}")
    }
}

/** The sender restarted or a different peer answered after the user accepted
 *  a specific manifest. Retrying would silently cross that consent boundary. */
class ShareSessionChangedException : IOException("Offline-share session changed")
