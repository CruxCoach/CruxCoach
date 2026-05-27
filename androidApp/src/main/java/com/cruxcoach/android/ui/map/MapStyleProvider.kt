package com.cruxcoach.android.ui.map

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * OpenFreeMap vector-tile style URLs. Provider-agnostic: swap to Stadia /
 * MapTiler / self-hosted by changing only these constants. No API key.
 */
object MapStyleProvider {
    /** General-purpose light style. */
    const val LIGHT = "https://tiles.openfreemap.org/styles/liberty"

    /** Greyscale minimal style for dark mode. */
    const val DARK = "https://tiles.openfreemap.org/styles/positron"

    /** 4 s HEAD-ping budget — short enough to never delay first render
     *  meaningfully, long enough to clear plausible 3G round-trips. */
    private const val PROBE_TIMEOUT_MS = 4_000L

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    fun forDarkMode(isDark: Boolean): String = if (isDark) DARK else LIGHT

    /**
     * Reachability probe for the active style URL. Returns `true` if the
     * tile-style endpoint answers with a successful HTTP code within
     * [PROBE_TIMEOUT_MS]; `false` on timeout, IO failure, or non-2xx.
     *
     * Single-shot, fire-and-forget — used by [MapViewModel] to flip a
     * UI state flag and show a "tile provider unreachable" snackbar
     * instead of letting the user stare at a grey canvas with markers.
     * We do NOT auto-rotate to a different provider here (the previous
     * Option A design carried an unbounded-retry risk on flaky links);
     * the user can retry on the next app start or open Settings.
     */
    suspend fun isReachable(styleUrl: String): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching {
                val request = Request.Builder().url(styleUrl).head().build()
                probeClient.newCall(request).execute().use { it.isSuccessful }
            }.getOrElse { e ->
                Log.w("MapStyleProvider", "tile-style HEAD probe failed for $styleUrl", e)
                false
            }
        } ?: false.also {
            Log.w("MapStyleProvider", "tile-style HEAD probe timed out for $styleUrl")
        }
    }
}
