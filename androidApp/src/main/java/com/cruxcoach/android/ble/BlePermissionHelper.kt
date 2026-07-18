package com.cruxcoach.android.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Utility for checking BLE permissions.
 * Android 12+ (API 31): BLUETOOTH_SCAN + BLUETOOTH_CONNECT (no location with neverForLocation)
 * Android 10-11 (API 29-30): ACCESS_FINE_LOCATION
 * Android 8-9 (API 26-28): ACCESS_COARSE_LOCATION
 */
object BlePermissionHelper {

    fun getScanPermissions(apiLevel: Int = Build.VERSION.SDK_INT): Array<String> =
        when {
            apiLevel >= Build.VERSION_CODES.S -> arrayOf(Manifest.permission.BLUETOOTH_SCAN)
            apiLevel >= Build.VERSION_CODES.Q -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            apiLevel >= Build.VERSION_CODES.M -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> emptyArray()
        }

    fun getConnectionPermissions(apiLevel: Int = Build.VERSION.SDK_INT): Array<String> =
        if (apiLevel >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    fun getRequiredPermissions(apiLevel: Int = Build.VERSION.SDK_INT): Array<String> =
        getScanPermissions(apiLevel) + getConnectionPermissions(apiLevel)

    fun hasPermissions(context: Context): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasConnectionPermission(context: Context): Boolean {
        return getConnectionPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getAdvertisingPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }
    }

    fun hasAdvertisingPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true // Pre-S doesn't need BLUETOOTH_ADVERTISE
        }
    }

    /**
     * Whether the system-wide location-services switch is on. Raw check, no
     * API-level policy — feed the result into [isLocationRequired] to decide
     * whether a BLE flow is actually blocked. LocationManagerCompat reads the
     * master switch on API 28+, which is what the OS BLE stack gates scan
     * results on; the previous hand-rolled GPS||NETWORK provider check
     * disagreed with it on API 28-30 devices without a network-location
     * provider (e.g. de-Googled ROMs), claiming "location off" while scan
     * results kept flowing.
     */
    fun isLocationServicesEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /**
     * Pure decision: does this BLE flow require location services to be on?
     *
     * Location only ever gates BLE *scanning*, and only on API 23-30:
     *  - API 31+: BLUETOOTH_SCAN is declared with neverForLocation, so scan
     *    results are delivered regardless of the location toggle — never
     *    require (let alone prompt for) location there.
     *  - API 23-30: the OS suppresses scan results while location
     *    services are off, so a discovery scan needs them — but a direct GATT
     *    connect to an already-known device works without location, so flows
     *    that don't scan must not be gated.
     *
     * Kept free of Context/Build.VERSION.SDK_INT reads so the decision table
     * is plain-JVM unit-testable.
     */
    fun isLocationRequired(apiLevel: Int, flowNeedsScan: Boolean, locationEnabled: Boolean): Boolean =
        flowNeedsScan &&
            apiLevel in Build.VERSION_CODES.M until Build.VERSION_CODES.S &&
            !locationEnabled
}
