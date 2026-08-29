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
 * One release, normalised across every kind of discovery source.
 *
 * [apkSha256] may be empty when a source announces a release without
 * carrying its hash inline (a forge points at a `.apk.sha256` sidecar
 * instead); [UpdateChecker] resolves it before the release is usable. No
 * download ever starts without a hash, because the whole fallback chain
 * relies on content-addressing the same bytes.
 */
data class DiscoveredRelease(
    val tagName: String,
    val version: SemVer,
    /** The announcing source's own APK URL. Other sources are derived. */
    val apkUrl: String,
    val apkSha256: String,
    /** Sidecar to resolve [apkSha256] from, when it is not inline. */
    val apkSha256Url: String,
    val apkSizeBytes: Long,
    val releaseNotesMarkdown: String,
    val releasePageUrl: String,
    val publishedAtEpochSeconds: Long,
)

/**
 * A place the updater can ask "is there something newer than what I have?".
 *
 * Implementations are stateless and own no ordering policy — the order and
 * membership of the list is [UpdateSourceRegistry]'s job, driven by data.
 * Each returns at most the single best candidate so the caller never has to
 * re-rank across heterogeneous sources.
 */
interface ReleaseSource {

    val source: UpdateSource

    /** Stable label, also used for per-source ETag storage and metrics. */
    val id: String get() = source.id

    suspend fun fetchNewerThan(installed: SemVer, etag: String?): Result

    sealed interface Result {
        /** [release] is null when the source answered but has nothing newer. */
        data class Success(val release: DiscoveredRelease?, val etag: String? = null) : Result

        /** ETag matched — this source has not changed since the last check. */
        data object NotModified : Result

        /** Transient or permanent failure. The caller moves to the next source. */
        data class Error(val message: String) : Result
    }
}

/**
 * Forgejo/Gitea/GitHub discovery. Applies both the forge's own
 * prerelease/draft flags and the strict tag shape via [VersionChecker], then
 * resolves the SHA-256 sidecar so downstream content-addressed fallbacks
 * have something to address.
 */
class ForgeDiscoverySource(
    override val source: UpdateSource,
    private val client: ForgeReleaseClient,
) : ReleaseSource {

    override suspend fun fetchNewerThan(installed: SemVer, etag: String?): ReleaseSource.Result {
        return when (val response = client.fetchReleases(source, etag)) {
            is ForgeReleaseClient.Result.NotModified -> ReleaseSource.Result.NotModified
            is ForgeReleaseClient.Result.Error -> ReleaseSource.Result.Error(response.message)
            is ForgeReleaseClient.Result.Success -> {
                val chosen = VersionChecker.pickNewerStable(response.releases, installed)
                    ?: return ReleaseSource.Result.Success(release = null, etag = response.etag)
                val version = SemVer.parseOrNull(chosen.tagName)
                    ?: return ReleaseSource.Result.Error("tag_unparseable")
                val apkAsset = chosen.assets
                    .firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    ?: return ReleaseSource.Result.Error("release_malformed")
                val shaAsset = chosen.assets
                    .firstOrNull { it.name.endsWith(".apk.sha256", ignoreCase = true) }
                    ?: return ReleaseSource.Result.Error("release_malformed")
                val pageUrl = chosen.htmlUrl ?: return ReleaseSource.Result.Error("release_malformed")

                val sha = client.fetchSha256(shaAsset.browserDownloadUrl)
                    ?: return ReleaseSource.Result.Error("sha256_asset_fetch_failed")

                ReleaseSource.Result.Success(
                    release = DiscoveredRelease(
                        tagName = chosen.tagName,
                        version = version,
                        apkUrl = apkAsset.browserDownloadUrl,
                        apkSha256 = sha,
                        apkSha256Url = shaAsset.browserDownloadUrl,
                        apkSizeBytes = apkAsset.size,
                        releaseNotesMarkdown = chosen.body.orEmpty(),
                        releasePageUrl = pageUrl,
                        publishedAtEpochSeconds = 0L,
                    ),
                    etag = response.etag,
                )
            }
        }
    }
}

/**
 * Publisher-signed NIP-82 discovery. Forge-independent by construction: the
 * relay and CDN are untrusted transports, and [ZapstoreReleaseClient] only
 * surfaces an asset after its event id, Schnorr signature, publisher,
 * package id, content hash and installed signer certificate all agree.
 */
class NostrDiscoverySource(
    override val source: UpdateSource,
    private val client: ZapstoreReleaseClient,
) : ReleaseSource {

    override suspend fun fetchNewerThan(installed: SemVer, etag: String?): ReleaseSource.Result {
        return when (val response = client.fetchReleases(
            relayUrl = source.url,
            cdnBaseUrl = source.cdn.orEmpty(),
        )) {
            is ZapstoreReleaseClient.Result.Error -> ReleaseSource.Result.Error(response.message)
            is ZapstoreReleaseClient.Result.Success -> {
                val chosen = response.releases
                    .mapNotNull { release ->
                        val version = SemVer.parseOrNull(release.versionName) ?: return@mapNotNull null
                        if (version > installed) version to release else null
                    }
                    .maxByOrNull { it.first }
                    ?: return ReleaseSource.Result.Success(release = null)
                ReleaseSource.Result.Success(
                    release = DiscoveredRelease(
                        tagName = "v${chosen.first}",
                        version = chosen.first,
                        apkUrl = chosen.second.apkUrl,
                        apkSha256 = chosen.second.apkSha256,
                        apkSha256Url = "",
                        apkSizeBytes = chosen.second.apkSizeBytes,
                        releaseNotesMarkdown = chosen.second.releaseNotesMarkdown,
                        releasePageUrl = BuildConfig.ZAPSTORE_APP_URL,
                        publishedAtEpochSeconds = chosen.second.publishedAtEpochSeconds,
                    ),
                )
            }
        }
    }
}

/**
 * Reads a plain release pointer — the website's `apk-target.json`, written
 * nightly by `tools/update-download-link.mjs`.
 *
 * This is the cheapest possible discovery source and it survives the forge
 * entirely, which is the point: it is a static file on the same host that
 * already serves the runtime source list. It carries the hash inline, so a
 * release found here is immediately downloadable from every
 * content-addressed source in the list.
 */
class ManifestDiscoverySource(
    override val source: UpdateSource,
    private val httpClient: OkHttpClient,
) : ReleaseSource {

    override suspend fun fetchNewerThan(
        installed: SemVer,
        etag: String?,
    ): ReleaseSource.Result = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(source.url)
            .header("Accept", "application/json")
            .header("User-Agent", "CruxCoach-Updater/${BuildConfig.VERSION_NAME}")
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .build()
        try {
            httpClient.newCall(request).execute().use { resp ->
                if (resp.code == 304) return@withContext ReleaseSource.Result.NotModified
                if (!resp.isSuccessful) {
                    return@withContext ReleaseSource.Result.Error("HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                val target = runCatching { JSON.decodeFromString<ApkTarget>(body) }
                    .getOrElse { return@withContext ReleaseSource.Result.Error("manifest_unparseable") }

                val version = SemVer.parseOrNull(target.version)
                    ?: return@withContext ReleaseSource.Result.Error("manifest_version_unparseable")
                if (version <= installed) {
                    return@withContext ReleaseSource.Result.Success(
                        release = null,
                        etag = resp.header("ETag"),
                    )
                }
                val sha = target.sha256.lowercase()
                if (!HEX_64.matches(sha)) {
                    return@withContext ReleaseSource.Result.Error("manifest_sha_invalid")
                }
                // The manifest's own URLs are advisory. Everything the
                // download chain actually uses is derived from the hash by
                // UpdateSourceRegistry, so a manifest cannot inject a host
                // that isn't already in the source list.
                ReleaseSource.Result.Success(
                    release = DiscoveredRelease(
                        tagName = "v$version",
                        version = version,
                        apkUrl = "",
                        apkSha256 = sha,
                        apkSha256Url = "",
                        apkSizeBytes = target.size,
                        releaseNotesMarkdown = "",
                        releasePageUrl = BuildConfig.UPDATER_RELEASE_PAGE_URL,
                        publishedAtEpochSeconds = 0L,
                    ),
                    etag = resp.header("ETag"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "event=manifest_fetch_failed url=${source.url}", e)
            ReleaseSource.Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    @Serializable
    private data class ApkTarget(
        val schema: Int = 1,
        val version: String = "",
        val sha256: String = "",
        val size: Long = 0,
    )

    companion object {
        private const val TAG = "ManifestDiscoverySource"
        private val HEX_64 = Regex("^[0-9a-f]{64}$")
        private val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}
