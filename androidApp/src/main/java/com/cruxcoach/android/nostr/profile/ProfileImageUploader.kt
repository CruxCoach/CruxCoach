package com.cruxcoach.android.nostr.profile

import android.util.Log
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.nostr.backup.BlossomUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Public-image uploader for FEAT-010 profile editor (banner + picture).
 *
 * Uses BUD-02 NIP-98 auth (same protocol as the encrypted-backup pipeline)
 * but with `image/jpeg` content-type instead of `application/octet-stream`,
 * and a single-shot PUT (no OCTET-vs-CruxCoach-alt fallback retry — image
 * MIME types are universally accepted by the configured Blossom servers).
 *
 * The auth-header signing logic lives in [BlossomUploader.blossomAuthHeader]
 * (made internal for cross-module reuse) — same auth event shape, just
 * different content-type on the wire.
 *
 * Returns the canonical Blossom URL `<server>/<sha256>` on first
 * accepted server, or null if every configured server rejected.
 */
@Singleton
class ProfileImageUploader @Inject constructor(
    @Named("nostr") private val okHttpClient: OkHttpClient,
    private val blossomUploader: BlossomUploader,
) {

    sealed class Result {
        data class Success(val url: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * Uploads [blob] (assumed already JPEG-compressed by [ImageProcessor])
     * to the first server in [servers] that accepts it. Same-bytes
     * idempotency: if the user re-uploads the identical image, every
     * Blossom server happily returns the same hash.
     *
     * Default [servers] are the same two we use for backup blobs;
     * `image/jpeg` flies on both per BUD-01 conformance.
     */
    suspend fun upload(
        blob: ByteArray,
        servers: List<String> = BlossomUploader.DEFAULT_SERVERS,
    ): Result {
        require(servers.isNotEmpty()) { "No Blossom servers configured" }
        val sha256 = sha256Hex(blob)
        val authHeader = try {
            blossomUploader.blossomAuthHeader(
                action = "upload",
                sha256 = sha256,
                content = "${BuildConfig.APP_DISPLAY_NAME} profile image upload",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Auth-event signing failed", e)
            return Result.Failure("auth: ${e.message ?: e.javaClass.simpleName}")
        }

        // Try servers in order until one accepts. Early-return on first
        // success keeps total upload time low (typical case = first server).
        var lastError = "no servers tried"
        for (server in servers) {
            val attempt = withContext(Dispatchers.IO) {
                attemptUpload(server, blob, sha256, authHeader)
            }
            when (attempt) {
                is Result.Success -> return attempt
                is Result.Failure -> {
                    lastError = attempt.message
                    Log.w(TAG, "event=image_upload_server_failed server=${server.shortHost()} reason=${attempt.message}")
                }
            }
        }
        return Result.Failure(lastError)
    }

    private fun attemptUpload(
        server: String,
        blob: ByteArray,
        sha256: String,
        authHeader: String,
    ): Result {
        val cleanServer = server.trimEnd('/')
        val request = Request.Builder()
            .url("$cleanServer/upload")
            .header("Authorization", authHeader)
            // BUD-06 hint headers — the same set BlossomUploader sends.
            .header("X-SHA-256", sha256)
            .header("X-Content-Length", blob.size.toString())
            .header("X-Content-Type", CONTENT_TYPE_JPEG)
            .put(blob.toRequestBody(CONTENT_TYPE_JPEG.toMediaType()))
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.Success("$cleanServer/$sha256")
                } else {
                    val errBody = runCatching { response.body?.string()?.take(MAX_ERR_BODY) }.getOrNull()
                    Result.Failure("HTTP ${response.code}${if (errBody != null) " — $errBody" else ""}")
                }
            }
        } catch (e: Exception) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "ProfileImageUploader"
        private const val CONTENT_TYPE_JPEG = "image/jpeg"
        private const val MAX_ERR_BODY = 200

        internal fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { "%02x".format(it) }

        private fun String.shortHost(): String = substringAfter("://").substringBefore('/')
    }
}
