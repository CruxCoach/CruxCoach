package com.cruxcoach.android.nostr.backup

import android.util.Log
import com.cruxcoach.android.nostr.NostrSigner
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Blossom (BUD-02 / BUD-06) client for FEAT-002 backup blobs.
 *
 * Handles:
 *   - Kind-24242 auth event signing
 *   - PUT /upload (with per-server content-type preflight per BUD-06)
 *   - GET  /{sha256} (public, no auth)
 *   - HEAD /{sha256} (public, verify existence after upload)
 *   - DELETE /{sha256} (best-effort cleanup)
 *
 * Default Blossom servers live in [DEFAULT_SERVERS] (FEAT-002 §5.3). The
 * caller (typically [BackupRepository]) resolves the full list via Kind
 * 10063 + defaults and passes it in.
 */
@Singleton
class BlossomUploader @Inject constructor(
    @Named("nostr") private val okHttpClient: OkHttpClient,
    private val nostrSigner: NostrSigner,
    private val preferences: BackupPreferences,
) {

    data class UploadResult(
        val server: String,
        val accepted: Boolean,
        val httpStatus: Int,
        val error: String? = null,
    )

    /**
     * Uploads [blob] to each of [servers] in parallel. Runs per-server
     * BUD-06 preflight on first use and caches the result (§6.5).
     *
     * Returns one [UploadResult] per server. Pipeline does not throw if at
     * least one server accepts — the caller checks [UploadResult.accepted].
     */
    suspend fun upload(blob: ByteArray, servers: List<String>): List<UploadResult> = coroutineScope {
        require(servers.isNotEmpty()) { "No Blossom servers configured" }
        val sha256 = sha256Hex(blob)
        // Sign the BUD-02 upload auth event exactly once and reuse it
        // across every server. The auth is bound to the blob's sha256, not
        // to a specific host, so every server accepts the same signature.
        // Doing this serially — and before the parallel fan-out — keeps
        // Amber from receiving N concurrent approval requests for the same
        // operation; Amber is a user-driven single-dialog signer, and a
        // second request enqueued 1 ms behind the first almost always
        // times out waiting for the user to catch up.
        val sharedAuthHeader = try {
            blossomAuthHeader(action = "upload", sha256 = sha256)
        } catch (e: Exception) {
            return@coroutineScope servers.map { server ->
                UploadResult(server, accepted = false, httpStatus = 0, error = "auth: ${e.message}")
            }.also { list ->
                list.forEach { r ->
                    Log.w(
                        TAG,
                        "event=upload_server_failed server=${r.server.shortHost()}" +
                            " httpStatus=${r.httpStatus} error=${r.error ?: "unknown"}",
                    )
                }
            }
        }
        val results = servers.map { server ->
            async(Dispatchers.IO) { uploadToSingle(server, blob, sha256, sharedAuthHeader) }
        }.awaitAll()
        // Per-server failure logging — otherwise the only thing that reaches
        // logcat is the aggregated "upload failed on all N servers" from the
        // BackupRepository, which makes remote debugging (415 vs 401 vs I/O
        // timeout) essentially impossible. Successful uploads stay silent.
        results.forEach { r ->
            if (!r.accepted) {
                Log.w(
                    TAG,
                    "event=upload_server_failed server=${r.server.shortHost()}" +
                        " httpStatus=${r.httpStatus} error=${r.error ?: "unknown"}",
                )
            }
        }
        results
    }

    /**
     * Attempts the actual PUT /upload. Discovers content-type compatibility
     * in-band instead of with a separate HEAD preflight — the previous
     * BUD-06 HEAD probe is effectively a stripped-down upload request with
     * no Nostr Authorization header, which both major Blossom servers
     * (blossom.nostr.build, blossom.primal.net) reject with 401 → the old
     * code then cached every well-behaved server as INCOMPATIBLE and
     * refused to upload there ever again. Doing the content-type fallback
     * against the real authorised PUT removes the false-positive path
     * entirely: if octet-stream is rejected with 415, retry once with the
     * CruxCoach alt MIME; remember which type worked so the next upload
     * picks it first.
     */
    private suspend fun uploadToSingle(
        server: String,
        blob: ByteArray,
        sha256Hex: String,
        authHeader: String,
    ): UploadResult {
        val hint = preferences.getContentTypeProbe(server)
        val firstType = when (hint) {
            BackupPreferences.ContentTypeProbe.REJECTED_OCTET -> CONTENT_TYPE_ALT
            else -> CONTENT_TYPE_OCTET
        }
        val first = attemptUpload(server, blob, sha256Hex, authHeader, firstType)
        if (first.accepted) {
            if (hint != BackupPreferences.ContentTypeProbe.ACCEPTED && firstType == CONTENT_TYPE_OCTET) {
                preferences.setContentTypeProbe(server, BackupPreferences.ContentTypeProbe.ACCEPTED)
                Log.d(TAG, "event=content_type_probe server=${server.shortHost()} result=accepted contentType=$firstType")
            }
            return first
        }
        // Only 415 (Unsupported Media Type) warrants a content-type retry.
        // Other failures (auth, network, 5xx) are not fixable by swapping MIME.
        if (first.httpStatus != 415 || firstType != CONTENT_TYPE_OCTET) return first

        val second = attemptUpload(server, blob, sha256Hex, authHeader, CONTENT_TYPE_ALT)
        if (second.accepted) {
            preferences.setContentTypeProbe(server, BackupPreferences.ContentTypeProbe.REJECTED_OCTET)
            Log.d(TAG, "event=content_type_probe server=${server.shortHost()} result=rejected_octet fallbackAccepted=$CONTENT_TYPE_ALT")
        }
        return second
    }

    private fun attemptUpload(
        server: String,
        blob: ByteArray,
        sha256Hex: String,
        authHeader: String,
        contentType: String,
    ): UploadResult {
        val request = Request.Builder()
            .url(server.trimEnd('/') + "/upload")
            .put(blob.toRequestBody(contentType.toMediaType()))
            .header("Authorization", authHeader)
            // BUD-06 (Upload Requirements): some Blossom servers gate
            // /upload behind these three headers and reject otherwise.
            // Verified empirically: nostr.download answers 400 "Missing
            // X-SHA-256 header" without them and 201 with them. Servers
            // that don't read these headers (primal, etc.) ignore them
            // — sending is purely additive. Hex must be lowercase per
            // spec.
            .header("X-SHA-256", sha256Hex)
            .header("X-Content-Length", blob.size.toString())
            .header("X-Content-Type", contentType)
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { resp -> parseUploadResponse(server, resp) }
        } catch (e: IOException) {
            UploadResult(server, accepted = false, httpStatus = 0, error = e.message ?: "io")
        }
    }

    private fun parseUploadResponse(server: String, response: Response): UploadResult {
        if (response.isSuccessful) {
            return UploadResult(server, accepted = true, httpStatus = response.code)
        }
        // Server response bodies are attacker-controlled text (a hostile or
        // compromised Blossom host can put phishing copy in its 5xx body).
        // Compose's Text doesn't render HTML so it's not XSS-exploitable,
        // but it would still render inside CruxCoach's own app chrome and
        // enable convincing social engineering. Log the body preview for
        // dev debugging; surface only a canonical "HTTP <code>" to the
        // UploadResult.error string that later reaches the user-visible
        // snackbar.
        val bodyPreview = runCatching { response.body?.string()?.take(200) }.getOrNull()
        Log.w(
            TAG,
            "event=upload_http_rejected server=${server.shortHost()}" +
                " code=${response.code} body=${bodyPreview ?: "<none>"}",
        )
        return UploadResult(
            server = server,
            accepted = false,
            httpStatus = response.code,
            error = "HTTP ${response.code}",
        )
    }

    /**
     * Download the blob with the given [sha256Hex] from the first server in
     * [servers] that answers, streaming into memory with two defenses
     * against a hostile / misconfigured host:
     *
     *  1. A hard byte cap of [maxBytes] — derived by the caller from the
     *     signed pointer's declared size plus a small framing slack.
     *     Without it, `resp.body.bytes()` would buffer an arbitrarily
     *     large response, so a hostile server could OOM the app before
     *     the SHA-256 integrity check even ran.
     *  2. SHA-256 is hashed while the stream drains and compared to the
     *     expected [sha256Hex] at EOF. Stream aborts on mismatch or
     *     over-cap with the partially-read buffer discarded.
     *
     * Throws [IOException] if every server fails, or if a single server
     * violated the size / hash contract.
     */
    suspend fun download(
        sha256Hex: String,
        servers: List<String>,
        maxBytes: Long,
    ): ByteArray = withContext(Dispatchers.IO) {
        require(servers.isNotEmpty()) { "No Blossom servers configured" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        var lastError: Throwable? = null
        for (server in servers) {
            try {
                val url = server.trimEnd('/') + "/$sha256Hex"
                val request = Request.Builder().url(url).get().build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = IOException("HTTP ${resp.code} from ${server.shortHost()}")
                        return@use
                    }
                    val body = resp.body ?: run {
                        lastError = IOException("Empty body from ${server.shortHost()}")
                        return@use
                    }
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0L
                    try {
                        body.byteStream().use { input ->
                            while (true) {
                                val read = input.read(buf)
                                if (read == -1) break
                                total += read
                                if (total > maxBytes) {
                                    throw IOException(
                                        "blob exceeded declared size ($total > $maxBytes) from ${server.shortHost()}",
                                    )
                                }
                                digest.update(buf, 0, read)
                                out.write(buf, 0, read)
                            }
                        }
                    } catch (e: IOException) {
                        lastError = e
                        Log.w(TAG, "event=download_server_failed server=${server.shortHost()}", e)
                        return@use
                    }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actualHash != sha256Hex) {
                        lastError = IOException(
                            "sha256 mismatch from ${server.shortHost()}: expected ${sha256Hex.take(8)}…, got ${actualHash.take(8)}…",
                        )
                        Log.w(TAG, "event=download_server_failed server=${server.shortHost()} reason=sha_mismatch")
                        return@use
                    }
                    return@withContext out.toByteArray()
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "event=download_server_failed server=${server.shortHost()}", e)
            }
        }
        throw IOException(
            "Blossom download failed on all ${servers.size} servers",
            lastError,
        )
    }

    /** HEAD /{sha256} on each server in parallel. Returns `true` if any respond 2xx. */
    suspend fun verifyExists(sha256Hex: String, servers: List<String>): Boolean = coroutineScope {
        if (servers.isEmpty()) return@coroutineScope false
        val checks = servers.map { server ->
            async(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(server.trimEnd('/') + "/$sha256Hex")
                    .head()
                    .build()
                try {
                    okHttpClient.newCall(request).execute().use { it.isSuccessful }
                } catch (_: Exception) {
                    false
                }
            }
        }
        checks.awaitAll().any { it }
    }

    /**
     * DELETE /{sha256} on every server. Best-effort on network errors —
     * individual server failures are logged, never thrown. Returns a
     * [DeleteOutcome] so callers can distinguish "auth failed entirely"
     * (no server was even attempted) from "some servers accepted"
     * (partial success) from "every server rejected" (full failure);
     * used by the active opt-out flow to fail-closed instead of silently
     * claiming success when no remote copy was actually deleted.
     */
    suspend fun delete(sha256Hex: String, servers: List<String>): DeleteOutcome =
        withContext(Dispatchers.IO) {
            val authHeader = try {
                blossomAuthHeader(action = "delete", sha256 = sha256Hex)
            } catch (e: Exception) {
                Log.w(TAG, "event=delete_auth_failed", e)
                return@withContext DeleteOutcome(
                    attempted = 0,
                    succeeded = 0,
                    authFailed = true,
                )
            }
            var ok = 0
            for (server in servers) {
                try {
                    val request = Request.Builder()
                        .url(server.trimEnd('/') + "/$sha256Hex")
                        .delete()
                        .header("Authorization", authHeader)
                        .build()
                    okHttpClient.newCall(request).execute().use { resp ->
                        // 404 + 410 count as success: the goal of the delete
                        // is "this blob is not on the server". A server that
                        // never accepted the upload (e.g. nostr.build returning
                        // 415 Unsupported on the original POST) responds 404
                        // here, and HTTP semantically calls that a failure —
                        // but for the user's privacy intent the objective is
                        // already met. Pre-fix the user saw "1 of 2 servers
                        // acknowledged the deletion" when both were
                        // effectively clean, just because one had nothing
                        // to delete. The dev-facing log keeps the distinction
                        // (`delete_already_absent` vs the actual 2xx success)
                        // so logcat still surfaces the real-vs-vacuous case.
                        val effectivelyAbsent = resp.code == 404 || resp.code == 410
                        if (resp.isSuccessful || effectivelyAbsent) {
                            ok += 1
                            if (effectivelyAbsent) {
                                Log.d(
                                    TAG,
                                    "event=delete_already_absent " +
                                        "server=${server.shortHost()} code=${resp.code}",
                                )
                            }
                        } else {
                            Log.d(
                                TAG,
                                "event=delete_non2xx server=${server.shortHost()} code=${resp.code}",
                            )
                        }
                    }
                } catch (e: Exception) {
                    // best-effort: a per-server I/O failure shouldn't propagate
                    // (the active opt-out flow is best-effort by design — see
                    // FEAT-002 §20.2). Log explicitly so a 0-success outcome
                    // can be distinguished in logcat between "server returned
                    // a non-2xx body" (logged via delete_non2xx above) and
                    // "connection threw before we got a response". Pre-fix
                    // this catch was silent which made delete-remote
                    // diagnostics noticeably worse than upload diagnostics.
                    Log.d(
                        TAG,
                        "event=delete_io_exception server=${server.shortHost()} " +
                            "reason=${e.javaClass.simpleName}: ${e.message}",
                    )
                }
            }
            DeleteOutcome(attempted = servers.size, succeeded = ok, authFailed = false)
        }

    data class DeleteOutcome(
        val attempted: Int,
        val succeeded: Int,
        val authFailed: Boolean,
    ) {
        fun fullySucceeded(): Boolean = !authFailed && attempted > 0 && succeeded == attempted
        fun partiallySucceeded(): Boolean = !authFailed && succeeded in 1 until attempted
        fun fullyFailed(): Boolean = authFailed || (attempted > 0 && succeeded == 0)
    }

    /**
     * Build a Kind-24242 authorization event and wrap it into the
     * `Authorization: Nostr base64(event_json)` header Blossom expects
     * (BUD-01 §1).
     */
    /**
     * Sign a BUD-02 auth header (Kind-24242 Nostr event, base64-encoded
     * inside `Authorization: Nostr <b64>`). Reused by [ProfileImageUploader]
     * for FEAT-010 profile-image uploads. The auth event commits to
     * `(action, sha256, expiration)` and is bound only to the blob's
     * content hash — same signature works on any Blossom server.
     */
    internal suspend fun blossomAuthHeader(
        action: String,
        sha256: String,
        content: String = "CruxCoach backup $action",
    ): String {
        val now = System.currentTimeMillis() / 1000
        val expiration = now + AUTH_EXPIRATION_SECONDS
        val tags = arrayOf(
            arrayOf("t", action),
            arrayOf("x", sha256),
            arrayOf("expiration", expiration.toString()),
        )
        val event: Event = nostrSigner.signer.sign<Event>(
            createdAt = now,
            kind = KIND_BLOSSOM_AUTH,
            tags = tags,
            content = content,
        )
        val eventJson = buildEventJson(event)
        val b64 = Base64.getEncoder().encodeToString(eventJson.encodeToByteArray())
        return "Nostr $b64"
    }

    private fun buildEventJson(event: Event): String {
        val obj: JsonObject = buildJsonObject {
            put("id", JsonPrimitive(event.id))
            put("pubkey", JsonPrimitive(event.pubKey))
            put("created_at", JsonPrimitive(event.createdAt))
            put("kind", JsonPrimitive(event.kind))
            put("tags", buildTagsArray(event.tags))
            put("content", JsonPrimitive(event.content))
            put("sig", JsonPrimitive(event.sig))
        }
        return JSON.encodeToString(obj)
    }

    private fun buildTagsArray(tags: Array<Array<String>>): JsonArray = buildJsonArray {
        tags.forEach { tag ->
            add(buildJsonArray {
                tag.forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun String.shortHost(): String = substringAfter("://").substringBefore('/')

    companion object {
        private const val TAG = "BlossomUploader"
        private const val KIND_BLOSSOM_AUTH = 24242
        private const val AUTH_EXPIRATION_SECONDS = 5L * 60L   // 5 minutes
        private const val CONTENT_TYPE_OCTET = "application/octet-stream"
        private const val CONTENT_TYPE_ALT = "application/x-cruxcoach-backup"

        /**
         * FEAT-002 §5.3 — hardcoded defaults, merged with the user's
         * Kind 10063 list.
         *
         * Server selection is driven by empirical end-to-end testing
         * (fresh test identity, BUD-01 + BUD-06 conformant upload of
         * an `application/octet-stream` blob, GET-verify, DELETE-cleanup):
         *
         * - **blossom.primal.net**: free, accepts arbitrary blobs, fast.
         * - **nostr.download**: free, requires the BUD-06 headers we
         *   now send (X-SHA-256, X-Content-Length, X-Content-Type)
         *   — answered 400 "Missing X-SHA-256" before the headers
         *   patch, now answers 201.
         *
         * Removed (deterministic 415 "File type not allowed" — these
         * servers are media CDNs that whitelist image/video/audio
         * MIME types only, so neither `application/octet-stream` nor
         * the CruxCoach-alt `application/x-cruxcoach-backup` will
         * ever be accepted, regardless of retries):
         *
         * - blossom.nostr.build
         * - blossom.band (same infrastructure)
         */
        val DEFAULT_SERVERS: List<String> = listOf(
            "https://blossom.primal.net",
            "https://nostr.download",
        )

        private val JSON = Json { encodeDefaults = true; prettyPrint = false }
    }
}
