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

    /**
     * The current position, or null when none arrives within [timeoutMs] (no
     * permission, location off, no provider with a fix). A fix from the last
     * two minutes answers at once. Otherwise every allowed provider is asked
     * at the same time and the first fix wins, so one silent provider (fused
     * without a network location service, GPS indoors) never keeps the others
     * from answering. A cached fix from the last half hour answers after a
     * short wait; an older one only when no live fix arrived at all.
     */
    @SuppressLint("MissingPermission") // checked by hasPermission() first
    suspend fun current(context: Context, timeoutMs: Long = TIMEOUT_MS): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val enabled = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())

        // Every enabled provider, "passive" too: it holds the newest fix any app received.
        val cached = enabled
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
        val cachedAge = cached?.let(::ageMs) ?: Long.MAX_VALUE
        if (cachedAge < FRESH_MS) {
            Log.i(TAG, "cached fix (${cached?.provider}, ${cachedAge / 1000} s old)")
            return cached
        }

        val live = liveProviders(enabled, hasFine(context))
        val wait = if (cachedAge < RECENT_MS) minOf(RECENT_WAIT_MS, timeoutMs) else timeoutMs
        val started = SystemClock.elapsedRealtime()
        val fresh = if (live.isEmpty()) null else withTimeoutOrNull(wait) { firstFix(context, manager, live, wait) }
        val tookMs = SystemClock.elapsedRealtime() - started
        // Never the coordinates: the position stays out of the log too.
        if (fresh != null) {
            Log.i(TAG, "live fix from ${fresh.provider} after $tookMs ms (±${fresh.accuracy.toInt()} m), asked $live")
        } else {
            Log.i(TAG, "no live fix within $wait ms from $live (enabled $enabled); cached: ${cached != null}")
        }
        // An older cached fix still beats nothing: the gym list only needs the town.
        return fresh ?: cached
    }

    /**
     * The providers to ask for a live fix. GPS needs the precise permission
     * before Android 12; from Android 12 on, approximate is enough and Android
     * coarsens the fix itself. This matters: with approximate only, Android
     * runs fused in low-power mode, which never turns on GPS while a network
     * location service is installed, and such a service may have no answer.
     */
    private fun liveProviders(enabled: List<String>, fine: Boolean): List<String> {
        val wanted = buildList {
            add(FUSED)
            add(LocationManager.NETWORK_PROVIDER)
            if (fine || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.GPS_PROVIDER)
        }
        return wanted.filter { it in enabled }
    }

    /** Listens on all [providers] at once and returns the first fix; every request ends with it. */
    @SuppressLint("MissingPermission")
    private suspend fun firstFix(
        context: Context,
        manager: LocationManager,
        providers: List<String>,
        durationMs: Long,
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
        var asked = 0
        providers.zip(listeners).forEach { (provider, listener) ->
            try {
                LocationManagerCompat.requestLocationUpdates(manager, provider, request, executor, listener)
                asked++
            } catch (e: SecurityException) {
                Log.w(TAG, "no permission for $provider", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "provider $provider unavailable", e)
            }
        }
        when {
            asked == 0 -> cont.resume(null)
            // A fix that came in while later providers were still being asked.
            !cont.isActive -> stopAll()
            else -> cont.invokeOnCancellation { stopAll() }
        }
    }

    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
}
