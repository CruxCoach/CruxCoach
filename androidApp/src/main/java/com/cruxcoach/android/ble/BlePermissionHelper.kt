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
 * Android 12+ (API 31): BLUETOOTH_SCAN + BLUETOOTH_CONNECT + BLUETOOTH_ADVERTISE
 * (no location with neverForLocation)
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

    /**
     * True when `BluetoothAdapter.ACTION_REQUEST_ENABLE` may be launched.
     *
     * Asking the platform to turn Bluetooth on is itself a BLUETOOTH_CONNECT
     * protected operation from API 31 on: without the permission the system
     * refuses the activity start with a SecurityException, which crashes the
     * app rather than returning a result. Below API 31 the intent needs no
     * runtime permission at all.
     */
    fun canRequestBluetoothEnable(
        hasConnectionPermission: Boolean,
        apiLevel: Int = Build.VERSION.SDK_INT,
    ): Boolean = hasConnectionPermission || getConnectionPermissions(apiLevel).isEmpty()

    /**
     * Permissions for the normal Board flow.
     *
     * Every supported Board connection owns a BoardCell and its controller
     * automatically fronts the physical wall through CruxRelay. Advertising
     * is therefore part of connecting to a Board, not a later optional
     * "sharing" action. Keeping it in this one request also avoids showing a
     * second Android permission dialog just after the connection succeeds.
     */
    fun getRequiredPermissions(apiLevel: Int = Build.VERSION.SDK_INT): Array<String> =
        (getScanPermissions(apiLevel) + getConnectionPermissions(apiLevel) +
            getAdvertisingPermissions(apiLevel)).distinct().toTypedArray()

    fun hasPermissions(context: Context): Boolean {
        // Advertising is requested in the same Android dialog, but denying it
        // may only degrade FIPS/CruxRelay — it must not hide a physical Board
        // that can still be scanned and connected directly.
        return (getScanPermissions() + getConnectionPermissions()).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasConnectionPermission(context: Context): Boolean {
        return getConnectionPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasScanPermission(context: Context): Boolean {
        return getScanPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Permissions to request when reconnecting to an already-known controller.
     *
     * A reconnect goes straight to the address and never scans. On Android 12+
     * it also asks for advertising so this device can take over the automatic
     * Board relay immediately if it becomes the canonical controller.
     */
    fun getReconnectPermissions(apiLevel: Int = Build.VERSION.SDK_INT): Array<String> =
        (getConnectionPermissions(apiLevel) +
            if (apiLevel >= Build.VERSION_CODES.Q) getAdvertisingPermissions(apiLevel)
            else emptyArray()).distinct().toTypedArray()

    fun hasReconnectPermissions(context: Context): Boolean =
        getReconnectPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Whether the post-connect capacity probe can run on this connection.
     *
     * It runs after EVERY connect whose capacity is not established yet — that
     * is the only moment the evidence exists. The two conditions are purely
     * about whether scanning would work at all: the permission has to be in
     * hand already (the probe never justifies asking for one), and on API 23-30
     * the system location switch has to be on, because the platform withholds
     * scan results otherwise. Neither is an API-level policy — where scanning
     * is possible, the probe runs.
     */
    fun wantsCapacityProbe(
        capacityKnown: Boolean,
        hasScanPermission: Boolean,
        locationEnabled: Boolean = true,
        apiLevel: Int = Build.VERSION.SDK_INT,
    ): Boolean = !capacityKnown &&
        hasScanPermission &&
        !isLocationRequired(apiLevel, flowNeedsScan = true, locationEnabled = locationEnabled)

    fun getAdvertisingPermissions(apiLevel: Int = Build.VERSION.SDK_INT): Array<String> {
        return if (apiLevel >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }
    }

    /** Minimum permissions for publishing a connectable GATT session.
     * Hosting does not scan, so legacy location and BLUETOOTH_SCAN must not
     * be requested here. */
    fun getSessionHostingPermissions(apiLevel: Int = Build.VERSION.SDK_INT): Array<String> =
        getAdvertisingPermissions(apiLevel) + getConnectionPermissions(apiLevel)

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
