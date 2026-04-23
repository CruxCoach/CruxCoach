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
        servers.map { server ->
            async(Dispatchers.IO) { uploadToSingle(server, blob, sha256) }
        }.awaitAll()
    }

    private suspend fun uploadToSingle(server: String, blob: ByteArray, sha256Hex: String): UploadResult {
        val probe = preferences.getContentTypeProbe(server) ?: run {
            val fresh = probeContentType(server)
            preferences.setContentTypeProbe(server, fresh)
            Log.d(TAG, "event=content_type_probe server=${server.shortHost()} result=${fresh.name}")
            fresh
        }
        if (probe == BackupPreferences.ContentTypeProbe.INCOMPATIBLE) {
            return UploadResult(server, accepted = false, httpStatus = 0, error = "incompatible")
        }

        val authHeader = try {
            blossomAuthHeader(action = "upload", sha256 = sha256Hex)
        } catch (e: Exception) {
            return UploadResult(server, accepted = false, httpStatus = 0, error = "auth: ${e.message}")
        }

        val contentType = when (probe) {
            BackupPreferences.ContentTypeProbe.REJECTED_OCTET -> CONTENT_TYPE_ALT
            else -> CONTENT_TYPE_OCTET
        }
        val request = Request.Builder()
            .url(server.trimEnd('/') + "/upload")
            .put(blob.toRequestBody(contentType.toMediaType()))
            .header("Authorization", authHeader)
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
        // 415 → may be a content-type incompatibility we can retry later
        val error = response.body?.string()?.take(200) ?: response.message
        return UploadResult(server, accepted = false, httpStatus = response.code, error = error)
    }

    /**
     * BUD-06 preflight: HEAD /upload with a content-type hint. If the server
     * accepts `application/octet-stream`, remember that. If it rejects with
     * 415, retry with the CruxCoach-specific Content-Type next upload. If
     * it rejects both, mark the server incompatible and skip.
     */
    private suspend fun probeContentType(server: String): BackupPreferences.ContentTypeProbe = withContext(Dispatchers.IO) {
        val urlBase = server.trimEnd('/') + "/upload"

        val octetOk = headProbe(urlBase, CONTENT_TYPE_OCTET)
        if (octetOk) return@withContext BackupPreferences.ContentTypeProbe.ACCEPTED
        val altOk = headProbe(urlBase, CONTENT_TYPE_ALT)
        if (altOk) return@withContext BackupPreferences.ContentTypeProbe.REJECTED_OCTET
        return@withContext BackupPreferences.ContentTypeProbe.INCOMPATIBLE
    }

    private fun headProbe(url: String, contentType: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("X-Content-Type", contentType)
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { resp ->
                // Servers that support BUD-06 return 2xx for an accepted type
                // and 415 for a rejected one. Treat 404 / 405 as "server
                // does not support preflight" → optimistically assume the
                // default octet type works.
                resp.code in 200..299 || resp.code == 404 || resp.code == 405
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Downloads the blob with the given [sha256Hex] from the first server in
     * [servers] that answers. Throws [IOException] only if every server
     * fails. Does NOT verify the SHA-256 — the caller must hash and check
     * against the pointer to match FEAT-002's integrity contract.
     */
    suspend fun download(sha256Hex: String, servers: List<String>): ByteArray = withContext(Dispatchers.IO) {
        require(servers.isNotEmpty()) { "No Blossom servers configured" }
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
                    val body = resp.body?.bytes() ?: run {
                        lastError = IOException("Empty body from ${server.shortHost()}")
                        return@use
                    }
                    return@withContext body
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
     * DELETE /{sha256} on every server. Best-effort — failures are logged,
     * never thrown. Used for blob cleanup after pointer publish (§7.3 step 8)
     * and for active opt-out (§20.2).
     */
    suspend fun delete(sha256Hex: String, servers: List<String>): Unit = withContext(Dispatchers.IO) {
        val authHeader = try {
            blossomAuthHeader(action = "delete", sha256 = sha256Hex)
        } catch (e: Exception) {
            Log.w(TAG, "event=delete_auth_failed", e)
            return@withContext
        }
        for (server in servers) {
            try {
                val request = Request.Builder()
                    .url(server.trimEnd('/') + "/$sha256Hex")
                    .delete()
                    .header("Authorization", authHeader)
                    .build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.d(
                            TAG,
                            "event=delete_non2xx server=${server.shortHost()} code=${resp.code}",
                        )
                    }
                }
            } catch (_: Exception) {
                // best-effort; ignore
            }
        }
    }

    /**
     * Build a Kind-24242 authorization event and wrap it into the
     * `Authorization: Nostr base64(event_json)` header Blossom expects
     * (BUD-01 §1).
     */
    private suspend fun blossomAuthHeader(action: String, sha256: String): String {
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
            content = "CruxCoach backup $action",
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

        /** FEAT-002 §5.3 — hardcoded defaults, merged with the user's Kind 10063 list. */
        val DEFAULT_SERVERS: List<String> = listOf(
            "https://blossom.nostr.build",
            "https://blossom.primal.net",
        )

        private val JSON = Json { encodeDefaults = true; prettyPrint = false }
    }
}
