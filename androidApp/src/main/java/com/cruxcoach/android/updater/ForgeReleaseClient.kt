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
 * Fetches releases from a Forgejo/Gitea *or* GitHub release API and parses
 * them into [ForgeRelease]s. Honors `If-None-Match` (§6.1) — on a 304 the
 * call returns [Result.NotModified] without touching the body.
 *
 * One client covers both forge families because their release JSON is
 * field-for-field compatible and both expose
 * `{apiBase}/repos/{owner}/{repo}/releases`; only the API root differs
 * (`https://<host>/api/v1` vs `https://api.github.com`). The repo
 * coordinates arrive per call from an [UpdateSource] rather than from
 * BuildConfig, because since FEAT-050 there can be more than one forge in
 * the list — typically the new canonical one plus the one being retired.
 *
 * No retry / backoff loop here: the throttle in [UpdateChecker] is the
 * single coalescing point for every trigger source. Network failures
 * surface as [Result.Error] and the next opportunistic trigger will retry.
 */
class ForgeReleaseClient(
    private val httpClient: OkHttpClient,
) {

    suspend fun fetchReleases(
        source: UpdateSource,
        etag: String?,
        limit: Int = 10,
    ): Result = withContext(Dispatchers.IO) {
        val apiBase = source.url.trimEnd('/')
        // Both forges page this endpoint, under different names: Forgejo/Gitea
        // read `limit`, GitHub reads `per_page` and ignores `limit` entirely —
        // which would silently fetch its default 30 releases instead of 10.
        // Harmless in itself, but it is mobile data on every check, and the
        // parameter is free to send. Sending both is simpler and safer than
        // sniffing the host.
        val url = "$apiBase/repos/${source.owner}/${source.repo}/releases" +
            "?limit=$limit&per_page=$limit"
        val request = Request.Builder()
            .url(url)
            // GitHub's documented media type; Forgejo answers JSON regardless.
            .header("Accept", "application/vnd.github+json, application/json")
            .header("User-Agent", "CruxCoach-Updater/${BuildConfig.VERSION_NAME}")
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .build()
        try {
            httpClient.newCall(request).execute().use { resp ->
                when (resp.code) {
                    304 -> Result.NotModified
                    in 200..299 -> {
                        val body = resp.body?.string().orEmpty()
                        val newEtag = resp.header("ETag")
                        val parsed = JSON.decodeFromString<List<ForgeRelease>>(body)
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
            .header("User-Agent", "CruxCoach-Updater/${BuildConfig.VERSION_NAME}")
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
        data class Success(val releases: List<ForgeRelease>, val etag: String?) : Result
        data object NotModified : Result
        data class Error(val message: String) : Result
    }

    companion object {
        private const val TAG = "ForgeReleaseClient"
        internal val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}

@Serializable
data class ForgeRelease(
    val id: Long = 0,
    @kotlinx.serialization.SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val body: String? = null,
    @kotlinx.serialization.SerialName("html_url") val htmlUrl: String? = null,
    @kotlinx.serialization.SerialName("published_at") val publishedAt: String? = null,
    val assets: List<ForgeAsset> = emptyList(),
)

@Serializable
data class ForgeAsset(
    val id: Long = 0,
    val name: String,
    @kotlinx.serialization.SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
)
