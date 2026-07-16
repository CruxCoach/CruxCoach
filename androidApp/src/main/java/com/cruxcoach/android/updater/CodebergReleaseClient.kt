package com.cruxcoach.android.updater

import android.util.Log
import com.cruxcoach.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the most recent Codeberg releases for this repo and parses them
 * into [CodebergRelease]s. Honors `If-None-Match` (§6.1) — on a 304 the
 * call returns [Result.NotModified] without touching the body.
 *
 * No retry / backoff loop here: the throttle in [UpdateChecker] is the
 * single coalescing point for every trigger source. Network failures
 * surface as [Result.Error] and the next opportunistic trigger will retry.
 */
class CodebergReleaseClient(
    private val httpClient: OkHttpClient,
    /** Override only for tests against a fork. */
    private val apiBase: String = BuildConfig.UPDATER_API_BASE,
    private val repoOwner: String = BuildConfig.UPDATER_REPO_OWNER,
    private val repoName: String = BuildConfig.UPDATER_REPO_NAME,
) {

    suspend fun fetchReleases(etag: String?, limit: Int = 10): Result = withContext(Dispatchers.IO) {
        val url = "$apiBase/repos/$repoOwner/$repoName/releases?limit=$limit"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header(
                "User-Agent",
                "${BuildConfig.USER_AGENT_PRODUCT}-Updater/${BuildConfig.VERSION_NAME}",
            )
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .build()
        try {
            httpClient.newCall(request).execute().use { resp ->
                when (resp.code) {
                    304 -> Result.NotModified
                    in 200..299 -> {
                        val body = resp.body?.string().orEmpty()
                        val newEtag = resp.header("ETag")
                        val parsed = JSON.decodeFromString<List<CodebergRelease>>(body)
                        Result.Success(parsed, newEtag)
                    }
                    else -> {
                        Log.w(TAG, "event=fetchReleases_http_error code=${resp.code} url=$url")
                        Result.Error("HTTP ${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "event=fetchReleases_failed url=$url", e)
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Downloads the SHA-256 sidecar asset and extracts the first 64-hex
     * token (coreutils `sha256sum > file.apk.sha256` format). Lower-cased
     * for constant-time compare against [java.security.MessageDigest] output.
     */
    suspend fun fetchSha256(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "${BuildConfig.USER_AGENT_PRODUCT}-Updater/${BuildConfig.VERSION_NAME}",
            )
            .build()
        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "event=fetchSha256_http_error code=${resp.code} url=$url")
                    return@withContext null
                }
                val body = resp.body?.string().orEmpty().trim()
                Regex("[0-9a-fA-F]{64}").find(body)?.value?.lowercase()
            }
        } catch (e: Exception) {
            Log.w(TAG, "event=fetchSha256_failed url=$url", e)
            null
        }
    }

    sealed interface Result {
        data class Success(val releases: List<CodebergRelease>, val etag: String?) : Result
        data object NotModified : Result
        data class Error(val message: String) : Result
    }

    companion object {
        private const val TAG = "CodebergReleaseClient"
        internal val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}

@Serializable
data class CodebergRelease(
    val id: Long = 0,
    @kotlinx.serialization.SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val body: String? = null,
    @kotlinx.serialization.SerialName("html_url") val htmlUrl: String? = null,
    @kotlinx.serialization.SerialName("published_at") val publishedAt: String? = null,
    val assets: List<CodebergAsset> = emptyList(),
)

@Serializable
data class CodebergAsset(
    val id: Long = 0,
    val name: String,
    @kotlinx.serialization.SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
)
