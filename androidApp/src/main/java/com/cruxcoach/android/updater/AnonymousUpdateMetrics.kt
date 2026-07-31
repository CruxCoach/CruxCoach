package com.cruxcoach.android.updater

import android.util.Log
import com.cruxcoach.android.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A deliberately tiny metric surface: one verified updater download event. */
interface VerifiedUpdateMetrics {
    /** False for ordinary/fork builds whose endpoint is intentionally empty. */
    val isConfigured: Boolean

    fun recordVerifiedUpdate(versionName: String, source: String)

    companion object {
        val NONE = object : VerifiedUpdateMetrics {
            override val isConfigured = false
            override fun recordVerifiedUpdate(versionName: String, source: String) = Unit
        }
    }
}

/**
 * Sends a one-way aggregate increment after an update APK passed payload and
 * signing-certificate verification.
 *
 * There is no retry queue, cookie jar, identifier, current app version, device
 * data, account/Nostr key, or success dependency. The fixed User-Agent carries
 * no Android/app/device version. Network or server failure is logged locally,
 * never retried, and can never alter updater state.
 */
class AnonymousUpdateMetricsClient internal constructor(
    endpoint: String,
    allowInsecureLoopbackForTests: Boolean,
) : VerifiedUpdateMetrics {

    private val endpointUrl = endpoint.trim().toHttpUrlOrNull()?.takeIf {
        val permittedScheme = it.isHttps ||
            (allowInsecureLoopbackForTests && it.host in LOOPBACK_HOSTS)
        permittedScheme && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
    }
    override val isConfigured: Boolean = endpointUrl != null

    /** Production entry point: configured endpoints must always use HTTPS. */
    constructor(
        endpoint: String = BuildConfig.ANONYMOUS_METRICS_ENDPOINT,
    ) : this(
        endpoint = endpoint,
        allowInsecureLoopbackForTests = false,
    )

    // Do not derive this client from any app HTTP client. That prevents a
    // future auth, cookie, analytics, or logging interceptor elsewhere in the
    // app from silently expanding this deliberately closed request surface.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun recordVerifiedUpdate(versionName: String, source: String) {
        val url = endpointUrl ?: return
        if (!STABLE_VERSION.matches(versionName) || !SOURCE_ID.matches(source)) return
        val body = buildJsonObject {
            put("metric", "app_update_verified")
            put("version", versionName)
            put("source", source)
        }.toString()
        val request = try {
            Request.Builder()
                .url(url)
                .header("User-Agent", "CruxCoach-Metrics/1")
                .header("Cache-Control", "no-store")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Anonymous update count request could not be built", error)
            return
        }

        try {
            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.d(TAG, "Anonymous update count was not delivered")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        Log.d(TAG, "Anonymous update count returned HTTP ${response.code}")
                    }
                    response.close()
                }
            })
        } catch (error: RuntimeException) {
            Log.w(TAG, "Anonymous update count could not be enqueued", error)
        }
    }

    companion object {
        private const val TAG = "UpdateMetrics"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val STABLE_VERSION = Regex("^[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$")
        /**
         * Source ids are no longer a fixed allowlist — the runtime source
         * list decides them, so a new host can appear without an app update.
         * What stays fixed is the *shape*, so a malformed or hostile manifest
         * cannot push arbitrary text into the counter's dimension. The
         * receiving end (`anonymous_analytics.py`) applies the same rule and
         * buckets unknown-but-well-formed ids rather than rejecting them.
         */
        private val SOURCE_ID = Regex("^[a-z0-9][a-z0-9-]{0,23}$")
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "localhost")
    }
}

/**
 * Maps a download URL back to the [UpdateSource.id] that served it, for the
 * aggregate counter.
 *
 * Resolution is done against the *live* source list rather than against
 * BuildConfig constants, because since FEAT-050 the list is data and can
 * name hosts this build has never heard of. An unrecognised URL yields null
 * and is not counted at all — mislabelling would silently corrupt the
 * per-source series in cruxcoach-dlstats, which is the one thing that tells
 * us when a retiring host has gone quiet enough to drop.
 */
internal suspend fun anonymousUpdateSource(
    downloadUrl: String,
    registry: UpdateSourceRegistry,
): String? = registry.sourceIdForUrl(downloadUrl)

internal fun HttpUrl.isBelow(base: HttpUrl): Boolean {
    val pathPrefix = base.encodedPath.trimEnd('/') + "/"
    return sameOrigin(base) && encodedPath.startsWith(pathPrefix)
}

internal fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port
