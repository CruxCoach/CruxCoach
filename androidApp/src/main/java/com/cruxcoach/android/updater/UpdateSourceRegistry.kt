package com.cruxcoach.android.updater

import android.util.Log
import com.cruxcoach.android.BuildConfig
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves the ordered list of [UpdateSource]s the updater should try.
 *
 * Precedence, highest first:
 *
 *  1. The runtime manifest, fetched from the first of
 *     [BuildConfig.UPDATE_SOURCES_URLS] that answers, cached in
 *     [UpdaterPreferences] for [CACHE_TTL_MS].
 *  2. The last manifest we successfully fetched, if every host fails.
 *  3. [EMBEDDED] — compiled into the APK.
 *
 * Step 3 is why [EMBEDDED] must never be empty: a device that can't reach
 * the manifest host still has to be able to update, and the integrity
 * argument in [UpdateSource]'s KDoc is what makes shipping a non-empty
 * default list safe here (unlike `sw.js`, which cannot verify what a mirror
 * returns and therefore ships an empty default).
 *
 * A manifest that parses but yields no usable source falls back to
 * [EMBEDDED] rather than bricking the updater. Intentional removal of a
 * source is still possible — set `enabled: false`, or omit it while leaving
 * at least one other usable entry.
 */
@Singleton
class UpdateSourceRegistry @Inject constructor(
    @param:Named("updater") private val okHttpClient: OkHttpClient,
    private val preferences: UpdaterPreferences,
    private val manifestUrls: List<String> = parseUrlList(BuildConfig.UPDATE_SOURCES_URLS),
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {

    private val memoMutex = Mutex()
    private var memo: List<UpdateSource>? = null
    private var memoAtMs: Long = 0L

    /**
     * Ordered, validated, de-duplicated sources. Refreshes the cached
     * manifest at most once per [CACHE_TTL_MS]; a refresh failure is not an
     * error, it just leaves the previous answer in place.
     *
     * Memoised in-process on top of the DataStore cache because one check
     * round asks for the list more than once (discovery, then download-URL
     * assembly, then metrics attribution) and none of those should cost a
     * separate disk read.
     */
    suspend fun sources(): List<UpdateSource> = memoMutex.withLock {
        val now = nowMsProvider()
        memo?.let { if (now - memoAtMs < CACHE_TTL_MS) return@withLock it }

        val snapshot = preferences.snapshot()
        val cachedRaw = snapshot.updateSourcesManifestJson
        val fetchedAt = snapshot.updateSourcesFetchedAtEpochMs ?: 0L
        val stale = now - fetchedAt >= CACHE_TTL_MS

        if (stale && manifestUrls.isNotEmpty()) {
            val fresh = fetchManifest()
            // Stamp the attempt whether or not it succeeded. Without this a
            // manifest host that is down would be re-fetched on every single
            // update check instead of once a day — and the embedded list
            // already covers the gap, so there is nothing to gain from
            // hammering it.
            preferences.update {
                it.copy(
                    updateSourcesManifestJson = fresh ?: it.updateSourcesManifestJson,
                    updateSourcesFetchedAtEpochMs = now,
                )
            }
            if (fresh != null) {
                parse(fresh)?.let { return@withLock it.also { p -> remember(p, now) } }
                Log.w(TAG, "event=sources_manifest_unusable — falling back")
            }
        }

        cachedRaw?.let { raw ->
            parse(raw)?.let { return@withLock it.also { p -> remember(p, now) } }
        }
        EMBEDDED.also { remember(it, now) }
    }

    private fun remember(sources: List<UpdateSource>, atMs: Long) {
        memo = sources
        memoAtMs = atMs
    }

    /** Sources that can answer "what is newest?", in order. */
    suspend fun discoverySources(): List<UpdateSource> = sources().filter { it.supportsDiscovery }

    /**
     * Every URL that could serve the bytes for one release, in order, with
     * the discovering source first so the common case is a single hop.
     *
     * Content-addressed sources ([UpdateSource.Kind.BLOSSOM],
     * [UpdateSource.Kind.NOSTR]) contribute a URL for *any* release whose
     * SHA-256 we know, even one they never announced — which is exactly what
     * makes them useful once a forge stops answering.
     */
    suspend fun downloadUrlsFor(
        tagName: String,
        apkSha256: String,
        primaryUrl: String?,
    ): List<String> = buildDownloadUrls(tagName, apkSha256, primaryUrl, sources())

    /**
     * Stable label for the aggregate counter: which configured source served
     * this URL. Returns null for anything unrecognised, so an unexpected
     * host is never counted rather than being mislabelled.
     */
    suspend fun sourceIdForUrl(downloadUrl: String): String? =
        resolveSourceId(downloadUrl, sources())

    /**
     * First host that answers with a usable body wins; the rest are not asked.
     *
     * Sequential rather than parallel on purpose. The common case is that the
     * first host answers, so a race would spend a second request every day on
     * every install to save nothing, and the ordering in the list is a
     * preference we want honoured rather than a tie broken by latency.
     */
    private suspend fun fetchManifest(): String? = withContext(Dispatchers.IO) {
        for (url in manifestUrls) {
            fetchManifestFrom(url)?.let { return@withContext it }
        }
        null
    }

    private fun fetchManifestFrom(manifestUrl: String): String? {
        val request = Request.Builder()
            .url(manifestUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "CruxCoach-Updater/${BuildConfig.VERSION_NAME}")
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "event=sources_fetch_http_error code=${resp.code} url=$manifestUrl")
                    return null
                }
                // Bound the body: this is a small config file, and an
                // unbounded read from a host we do not control is a trivial
                // memory-pressure lever.
                val body = resp.body?.source()?.let { source ->
                    source.request(MAX_MANIFEST_BYTES + 1)
                    val buffered = source.buffer
                    if (buffered.size > MAX_MANIFEST_BYTES) {
                        Log.w(TAG, "event=sources_fetch_oversize size=${buffered.size} url=$manifestUrl")
                        return null
                    }
                    buffered.readUtf8()
                }
                body?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "event=sources_fetch_failed url=$manifestUrl", e)
            null
        }
    }

    private fun parse(raw: String): List<UpdateSource>? {
        val manifest = runCatching { JSON.decodeFromString<UpdateSourceManifest>(raw) }
            .getOrElse {
                Log.w(TAG, "event=sources_parse_failed", it)
                return null
            }
        if (manifest.version != SUPPORTED_MANIFEST_VERSION) {
            Log.w(TAG, "event=sources_version_unsupported version=${manifest.version}")
            return null
        }
        val usable = manifest.sources
            .filter { it.isUsable() }
            .distinctBy { it.id }
            .take(MAX_SOURCES)
        if (usable.isEmpty()) return null
        val dropped = manifest.sources.size - usable.size
        if (dropped > 0) Log.i(TAG, "event=sources_filtered dropped=$dropped kept=${usable.size}")
        return usable
    }

    companion object {
        private const val TAG = "UpdateSourceRegistry"
        private const val SUPPORTED_MANIFEST_VERSION = 1
        private const val MAX_SOURCES = 12
        private const val MAX_MANIFEST_BYTES = 32L * 1024L
        /** One day. The list changes on the order of releases, not minutes. */
        internal const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

        internal val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        /**
         * Split a comma-separated BuildConfig list into ordered, usable URLs.
         *
         * Non-https entries are dropped rather than rejected wholesale: a fork
         * that overrides one of these in `local.properties` and fat-fingers a
         * single entry should lose that entry, not the whole fallback chain.
         * Duplicates are collapsed so a copy-paste in `local.properties`
         * cannot turn one outage into two identical timeouts.
         */
        internal fun parseUrlList(raw: String): List<String> = raw
            .split(',')
            .map { it.trim() }
            .filter { it.startsWith("https://") }
            .distinct()

        /**
         * Compiled-in fallback, used when the manifest is unreachable or
         * unusable. Order matters: most trustworthy and most likely to be
         * current first.
         *
         * The forge entry is driven by the same `UPDATER_*` fields a fork
         * overrides in `local.properties`, so a fork gets its own forge here
         * without touching this list.
         */
        val EMBEDDED: List<UpdateSource> = listOfNotNull(
            UpdateSource(
                id = "forge",
                kind = UpdateSource.Kind.FORGE,
                url = BuildConfig.UPDATER_API_BASE,
                owner = BuildConfig.UPDATER_REPO_OWNER,
                repo = BuildConfig.UPDATER_REPO_NAME,
            ),
            UpdateSource(
                id = "zapstore",
                kind = UpdateSource.Kind.NOSTR,
                url = BuildConfig.ZAPSTORE_RELAY_URL,
                cdn = BuildConfig.ZAPSTORE_CDN_BASE_URL,
            ),
            // One entry per manifest host, so the *embedded* fallback survives
            // an apex outage too — the runtime list is exactly what a device
            // does not have when it needs this list.
            *parseUrlList(BuildConfig.UPDATER_MANIFEST_URLS)
                .mapIndexed { index, url ->
                    UpdateSource(
                        id = if (index == 0) "website" else "website-$index",
                        kind = UpdateSource.Kind.MANIFEST,
                        url = url,
                    )
                }
                .toTypedArray(),
            // Content-addressed, download-only. These are the servers
            // cruxcoach-blossom-sync already publishes board-DB chunks to, so
            // they are known-good hosts for us and cost nothing to keep as a
            // last resort. Note the project's own blossom-server is NOT here:
            // it binds 127.0.0.1 with no publicDomain and is unreachable from
            // devices.
            *BuildConfig.UPDATER_BLOSSOM_SERVERS
                .split(',')
                .map { it.trim() }
                .filter { it.startsWith("https://") }
                .mapIndexed { index, server ->
                    UpdateSource(
                        id = if (index == 0) "blossom" else "blossom-$index",
                        kind = UpdateSource.Kind.BLOSSOM,
                        url = server,
                    )
                }
                .toTypedArray(),
        ).filter { it.isUsable() }
    }
}

/**
 * Every URL that can serve one release, announcing source first.
 *
 * Pure so the ordering and https rules are testable without a registry or a
 * network. Non-https entries are dropped rather than rejected wholesale: one
 * misconfigured source must not disable the others.
 */
internal fun buildDownloadUrls(
    tagName: String,
    apkSha256: String,
    primaryUrl: String?,
    sources: List<UpdateSource>,
): List<String> {
    val derived = sources.mapNotNull { it.downloadUrlFor(tagName, apkSha256) }
    return (listOfNotNull(primaryUrl) + derived)
        .filter { it.startsWith("https://") }
        .distinct()
}

/**
 * Maps a download URL back to the source that can serve it.
 *
 * Matching is origin-aware rather than string-prefix based: `sameOrigin`
 * compares scheme/host/port, so a look-alike host like
 * `codeberg.org.evil.example` or `cdn.zapstore.dev.evil.example` cannot be
 * mistaken for the real one, and a cleartext URL never matches at all.
 */
internal fun resolveSourceId(downloadUrl: String, sources: List<UpdateSource>): String? {
    val candidate = downloadUrl.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
    return sources.firstOrNull { source ->
        when (source.kind) {
            UpdateSource.Kind.BLOSSOM ->
                source.url.toHttpUrlOrNull()?.let { candidate.isBelow(it) } == true

            UpdateSource.Kind.NOSTR ->
                source.cdn?.toHttpUrlOrNull()?.let { candidate.isBelow(it) } == true

            UpdateSource.Kind.FORGE -> {
                val base = source.webHost().toHttpUrlOrNull()
                val prefix = "/${source.owner}/${source.repo}/releases/download/"
                base != null &&
                    candidate.sameOrigin(base) &&
                    candidate.encodedPath.startsWith(prefix)
            }

            // The manifest points at other sources; it never serves bytes.
            UpdateSource.Kind.MANIFEST -> false
        }
    }?.id
}
