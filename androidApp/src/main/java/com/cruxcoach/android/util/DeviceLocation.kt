package com.cruxcoach.android.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot "where am I" for the board map and the gym picker. Uses the
 * platform [LocationManager] only (no Play Services, so it works on
 * de-Googled phones too). Nothing is stored or sent anywhere: callers use the
 * position on the device to sort the bundled gym list.
 *
 * Asking for the permission is the caller's job and happens only when the
 * user taps a "my location" action; see `rememberLocateMe`.
 */
object DeviceLocation {
    private const val TAG = "DeviceLocation"

    /** A fix this young is good enough to answer at once. */
    private const val FRESH_MS = 2 * 60_000L

    /** A fix this young answers when no live fix arrives within [RECENT_WAIT_MS]. */
    private const val RECENT_MS = 30 * 60_000L
    private const val RECENT_WAIT_MS = 8_000L

    /** How long to wait for a live fix: a cold GPS start outdoors without
     *  network help often takes 30 s or more. */
    const val TIMEOUT_MS = 45_000L

    /** `LocationManager.FUSED_PROVIDER` (named in API 31; older releases may have it too). */
    private const val FUSED = "fused"

    /** The permissions to request together: Android 12+ then lets the user
     *  choose between precise and approximate, and either is enough here. */
    val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * What one lookup tried, for the detail line under "could not be found"
     * (the owner's phone was not on ADB, 2026-10-24). Provider names and
     * times only, never a position.
     */
    data class Attempt(
        /** The precise permission was granted (else approximate only). */
        val precise: Boolean,
        /** The providers Android listed as switched on. */
        val enabled: List<String>,
        /** The providers that took the request. */
        val asked: List<String>,
        /** The providers that refused it: missing on this phone or not allowed. */
        val refused: List<String>,
        val waitedMs: Long,
    )

    /** A position (or none) together with what was tried to get it. */
    data class Lookup(val location: Location?, val attempt: Attempt)

    fun hasPermission(context: Context): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasFine(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** False when location is switched off in the system settings. */
    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /** The current position, or null; see [locate]. */
    suspend fun current(context: Context, timeoutMs: Long = TIMEOUT_MS): Location? =
        locate(context, timeoutMs).location

    /**
     * The current position, or null when none arrives within [timeoutMs] (no
     * permission, location off, no provider with a fix), with what was tried.
     * A fix from the last two minutes answers at once. Otherwise every
     * provider the permission allows is asked at the same time and the first
     * fix wins, so one silent provider (fused without a network location
     * service, GPS indoors) never keeps the others from answering. A cached
     * fix from the last half hour answers after a short wait; an older one
     * only when no live fix arrived at all.
     */
    @SuppressLint("MissingPermission") // checked by hasPermission() first
    suspend fun locate(context: Context, timeoutMs: Long = TIMEOUT_MS): Lookup {
        val precise = hasFine(context)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (!hasPermission(context) || manager == null) {
            return Lookup(null, Attempt(precise, emptyList(), emptyList(), emptyList(), 0L))
        }
        val enabled = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        val known = runCatching { manager.allProviders }.getOrDefault(emptyList())

        // Every provider, "passive" too: it holds the newest fix any app received.
        val cached = (enabled + known + LocationManager.PASSIVE_PROVIDER).distinct()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
        val cachedAge = cached?.let(::ageMs) ?: Long.MAX_VALUE
        if (cachedAge < FRESH_MS) {
            Log.i(TAG, "cached fix (${cached?.provider}, ${cachedAge / 1000} s old)")
            return Lookup(cached, Attempt(precise, enabled, emptyList(), emptyList(), 0L))
        }

        val wait = if (cachedAge < RECENT_MS) minOf(RECENT_WAIT_MS, timeoutMs) else timeoutMs
        val asked = mutableListOf<String>()
        val refused = mutableListOf<String>()
        val started = SystemClock.elapsedRealtime()
        val fresh = withTimeoutOrNull(wait) {
            firstFix(context, manager, candidates(precise), wait, asked, refused)
        }
        val attempt = Attempt(precise, enabled, asked.toList(), refused.toList(), SystemClock.elapsedRealtime() - started)
        // Never the coordinates: the position stays out of the log too.
        if (fresh != null) {
            Log.i(TAG, "live fix from ${fresh.provider} (±${fresh.accuracy.toInt()} m): $attempt")
        } else {
            Log.i(TAG, "no live fix; cached: ${cached != null}; $attempt")
        }
        // An older cached fix still beats nothing: the gym list only needs the town.
        return Lookup(fresh ?: cached, attempt)
    }

    /**
     * The providers to ask, whether or not Android lists them as switched
     * on: a registration on a provider that is off is accepted and starts
     * once it comes on, and a missing one refuses with an exception, which is
     * recorded. GPS needs the precise permission before Android 12; from
     * Android 12 on, approximate is enough and Android coarsens the fix
     * itself. This matters: with approximate only, Android runs fused in
     * low-power mode, which never turns on GPS while a network location
     * service is installed, and such a service may have no answer. "passive"
     * costs nothing and catches a fix another app causes meanwhile.
     */
    private fun candidates(precise: Boolean): List<String> = buildList {
        add(FUSED)
        add(LocationManager.NETWORK_PROVIDER)
        // Before Android 12, gps and passive need the precise permission.
        if (precise || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
    }

    /**
     * Listens on all [providers] at once and returns the first fix; every
     * request ends with it. Fills [asked] and [refused] before it waits.
     */
    @SuppressLint("MissingPermission")
    private suspend fun firstFix(
        context: Context,
        manager: LocationManager,
        providers: List<String>,
        durationMs: Long,
        asked: MutableList<String>,
        refused: MutableList<String>,
    ): Location? = suspendCancellableCoroutine { cont ->
        val request = LocationRequestCompat.Builder(1_000L)
            // Lets fused use GPS with the precise permission; Android lowers it for approximate.
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            // Ends on the platform side too, should the cleanup below never run.
            .setDurationMillis(durationMs)
            .build()
        // One listener per provider: a listener belongs to one provider at a time.
        lateinit var listeners: List<LocationListenerCompat>
        fun stopAll() = listeners.forEach { runCatching { LocationManagerCompat.removeUpdates(manager, it) } }
        listeners = providers.map {
            LocationListenerCompat { location ->
                stopAll()
                if (cont.isActive) cont.resume(location)
            }
        }
        val executor = ContextCompat.getMainExecutor(context)
        providers.zip(listeners).forEach { (provider, listener) ->
            try {
                LocationManagerCompat.requestLocationUpdates(manager, provider, request, executor, listener)
                asked += provider
            } catch (e: SecurityException) {
                Log.i(TAG, "$provider: not allowed (${e.message})")
                refused += provider
            } catch (e: IllegalArgumentException) {
                Log.i(TAG, "$provider: not available (${e.message})")
                refused += provider
            }
        }
        when {
            asked.isEmpty() -> cont.resume(null)
            // A fix that came in while later providers were still being asked.
            !cont.isActive -> stopAll()
            else -> cont.invokeOnCancellation { stopAll() }
        }
    }

    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
}
