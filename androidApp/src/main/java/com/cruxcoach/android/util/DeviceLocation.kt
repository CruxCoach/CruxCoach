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
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
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
     * permission, location off, no provider with a fix). A recent cached fix
     * answers immediately; otherwise one fresh fix is requested per provider,
     * best first.
     */
    @SuppressLint("MissingPermission") // checked by hasPermission() first
    suspend fun current(context: Context, timeoutMs: Long = 15_000L): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = providers(manager, hasFine(context))
        if (providers.isEmpty()) return null

        val cached = providers
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
        if (cached != null && ageMs(cached) < FRESH_MS) return cached

        val fresh = withTimeoutOrNull(timeoutMs) {
            for (provider in providers) {
                val fix = requestOne(context, manager, provider)
                if (fix != null) return@withTimeoutOrNull fix
            }
            null
        }
        // An older cached fix still beats nothing: the gym list only needs the town.
        return fresh ?: cached
    }

    private fun providers(manager: LocationManager, fine: Boolean): List<String> {
        val enabled = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        val order = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            // GPS needs the precise permission; with approximate only it throws.
            if (fine) add(LocationManager.GPS_PROVIDER)
        }
        return order.filter { it in enabled }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestOne(context: Context, manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, signal, ContextCompat.getMainExecutor(context),
                ) { location -> if (cont.isActive) cont.resume(location) }
            } catch (e: SecurityException) {
                Log.w(TAG, "no permission for $provider", e)
                if (cont.isActive) cont.resume(null)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "provider $provider unavailable", e)
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
}
