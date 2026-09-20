package com.cruxcoach.app.backup

import com.cruxcoach.app.nostr.Base64
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.UrlValidation
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.app.util.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Signs an event with the active identity. Local-only: NIP-55 is Android IPC. */
fun interface EventSigner {
    fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String): NostrEvent?
}

/**
 * Blossom (BUD-01 / BUD-02 / BUD-06) client for the backup blob, ported from
 * Android's `BlossomUploader`.
 *
 * The kind-24242 auth event commits to `(action, sha256, expiration)` only —
 * it is not bound to a host, so one signature is reused across servers, the
 * same way Android does it.
 */
class BlossomClient(
    private val http: HttpTransport,
    private val hashing: Hashing,
    private val clock: WallClock,
    private val signer: EventSigner,
) {
    class UploadResult(val server: String, val accepted: Boolean, val httpStatus: Int, val error: String? = null)

    class DeleteOutcome(val attempted: Int, val succeeded: Int, val authFailed: Boolean) {
        fun fullySucceeded(): Boolean = !authFailed && attempted > 0 && succeeded == attempted
        fun partiallySucceeded(): Boolean = !authFailed && succeeded in 1 until attempted
        fun fullyFailed(): Boolean = authFailed || (attempted > 0 && succeeded == 0)
    }

    /** PUT /upload to every server, with the BUD-06 headers Android sends. */
    suspend fun upload(blob: ByteArray, servers: List<String>): List<UploadResult> {
        val sha256 = hashing.sha256(blob).toHex()
        val auth = authHeader("upload", sha256)
            ?: return servers.map { UploadResult(it, false, 0, "auth") }
        return servers.map { server ->
            val first = attemptUpload(server, blob, sha256, auth, CONTENT_TYPE_OCTET)
            // Only 415 is fixable by swapping the MIME type; auth and network
            // failures are not.
            if (first.accepted || first.httpStatus != 415) first
            else attemptUpload(server, blob, sha256, auth, CONTENT_TYPE_ALT)
        }
    }

    private suspend fun attemptUpload(
        server: String,
        blob: ByteArray,
        sha256: String,
        auth: String,
        contentType: String,
    ): UploadResult {
        if (!UrlValidation.isValidBlossom(server)) return UploadResult(server, false, 0, "url")
        val result = http.request(
            method = "PUT",
            url = server.trimEnd('/') + "/upload",
            headers = mapOf(
                "Authorization" to auth,
                "Content-Type" to contentType,
                // BUD-06: some servers reject /upload without these three.
                "X-SHA-256" to sha256,
                "X-Content-Length" to blob.size.toString(),
                "X-Content-Type" to contentType,
            ),
            body = blob,
            maxResponseBytes = MAX_RESPONSE_BYTES,
            timeoutSeconds = UPLOAD_TIMEOUT_SECONDS,
        )
        return when (result) {
            is HttpResult.Ok ->
                if (result.response.status in 200..299) {
                    UploadResult(server, true, result.response.status)
                } else {
                    // The error body is untrusted and may be unbounded or echo
                    // authorization material: status only.
                    UploadResult(server, false, result.response.status, "HTTP ${result.response.status}")
                }
            is HttpResult.Failed -> UploadResult(server, false, 0, result.reason.name)
        }
    }

    /**
     * Uploads an image and returns the URL of the first server that took it.
     *
     * Separate from [upload] because a profile picture is public, is served
     * to other clients by URL, and needs its real MIME type — the backup blob
     * is opaque ciphertext and deliberately uploaded as octet-stream.
     */
    suspend fun uploadImage(
        blob: ByteArray,
        contentType: String = CONTENT_TYPE_JPEG,
        servers: List<String> = DEFAULT_SERVERS,
    ): String? {
        if (servers.isEmpty()) return null
        val sha256 = hashing.sha256(blob).toHex()
        val auth = authHeader("upload", sha256, "CruxCoach profile image upload") ?: return null
        for (server in servers) {
            val attempt = attemptUpload(server, blob, sha256, auth, contentType)
            // Same-bytes idempotency: re-uploading one image is a no-op that
            // returns the same hash, so a retry costs nothing.
            if (attempt.accepted) return server.trimEnd('/') + "/" + sha256
        }
        return null
    }

    /** HEAD /<sha256>: true as soon as one server has the blob. */
    suspend fun verifyExists(sha256Hex: String, servers: List<String>): Boolean {
        for (server in servers) {
            if (!UrlValidation.isValidBlossom(server)) continue
            val result = http.request(
                method = "HEAD",
                url = server.trimEnd('/') + "/$sha256Hex",
                headers = emptyMap(),
                body = null,
                maxResponseBytes = MAX_RESPONSE_BYTES,
                timeoutSeconds = SHORT_TIMEOUT_SECONDS,
            )
            if (result is HttpResult.Ok && result.response.status in 200..299) return true
        }
        return false
    }

    /**
     * GET /<sha256> from the first server that answers. The transport caps the
     * body at [maxBytes] (the signed pointer's size plus framing slack), and
     * the hash is checked before the bytes are handed back, so a hostile
     * server can neither exhaust memory nor swap the blob.
     */
    suspend fun download(sha256Hex: String, servers: List<String>, maxBytes: Long): ByteArray? {
        if (maxBytes <= 0) return null
        for (server in servers) {
            if (!UrlValidation.isValidBlossom(server)) continue
            val result = http.request(
                method = "GET",
                url = server.trimEnd('/') + "/$sha256Hex",
                headers = emptyMap(),
                body = null,
                maxResponseBytes = maxBytes,
                timeoutSeconds = DOWNLOAD_TIMEOUT_SECONDS,
            )
            if (result !is HttpResult.Ok || result.response.status !in 200..299) continue
            val body = result.response.body
            if (body.size.toLong() > maxBytes) continue
            if (hashing.sha256(body).toHex() != sha256Hex) continue
            return body
        }
        return null
    }

    /** DELETE /<sha256> everywhere. 404/410 count as success: the blob is gone. */
    suspend fun delete(sha256Hex: String, servers: List<String>): DeleteOutcome {
        val auth = authHeader("delete", sha256Hex)
            ?: return DeleteOutcome(attempted = 0, succeeded = 0, authFailed = true)
        var succeeded = 0
        for (server in servers) {
            if (!UrlValidation.isValidBlossom(server)) continue
            val result = http.request(
                method = "DELETE",
                url = server.trimEnd('/') + "/$sha256Hex",
                headers = mapOf("Authorization" to auth),
                body = null,
                maxResponseBytes = MAX_RESPONSE_BYTES,
                timeoutSeconds = SHORT_TIMEOUT_SECONDS,
            )
            if (result is HttpResult.Ok &&
                (result.response.status in 200..299 || result.response.status == 404 || result.response.status == 410)
            ) {
                succeeded++
            }
        }
        return DeleteOutcome(attempted = servers.size, succeeded = succeeded, authFailed = false)
    }

    /** `Authorization: Nostr <base64 kind-24242 event>` (BUD-01). */
    internal fun authHeader(action: String, sha256: String, content: String = "CruxCoach backup $action"): String? {
        val now = clock.epochSeconds()
        val event = signer.sign(
            createdAt = now,
            kind = KIND_BLOSSOM_AUTH,
            tags = listOf(
                listOf("t", action),
                listOf("x", sha256),
                listOf("expiration", (now + AUTH_EXPIRATION_SECONDS).toString()),
            ),
            content = content,
        ) ?: return null
        return "Nostr " + Base64.encode(JSON.encodeToString(JsonObject.serializer(), event.toJson()).encodeToByteArray())
    }

    companion object {
        const val KIND_BLOSSOM_AUTH = 24242
        const val KIND_BLOSSOM_SERVER_LIST = 10063
        private const val AUTH_EXPIRATION_SECONDS = 5L * 60L
        const val CONTENT_TYPE_JPEG = "image/jpeg"
        private const val CONTENT_TYPE_OCTET = "application/octet-stream"
        private const val CONTENT_TYPE_ALT = "application/x-cruxcoach-backup"
        private const val MAX_RESPONSE_BYTES = 64L * 1024
        private const val UPLOAD_TIMEOUT_SECONDS = 60
        private const val DOWNLOAD_TIMEOUT_SECONDS = 120
        private const val SHORT_TIMEOUT_SECONDS = 20

        /** FEAT-002 §5.3 defaults, merged with the user's kind-10063 list. */
        val DEFAULT_SERVERS: List<String> = listOf("https://nostr.download", "https://cdn.hzrd149.com")

        private val JSON = Json { encodeDefaults = true }

        /** `["server", url]` entries of a kind-10063 event, gated and capped. */
        fun serversFromEvent(event: NostrEvent): List<String> =
            event.tags.asSequence()
                .filter { it.size >= 2 && it[0] == "server" }
                .map { it[1].trimEnd('/') }
                .filter { UrlValidation.isValidBlossom(it) }
                .distinct()
                .take(16)
                .toList()
    }
}
