package com.cruxcoach.android.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Utility for checking BLE permissions.
 * Android 12+ (API 31): BLUETOOTH_SCAN + BLUETOOTH_CONNECT (no location needed with neverForLocation)
 * Android 11 and below: ACCESS_FINE_LOCATION
 */
object BlePermissionHelper {

    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    fun hasPermissions(context: Context): Boolean {
        return getRequiredPermissions().all {
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
     * On Android 11 and below, BLE scanning requires Location Services to be enabled.
     * Returns true if location is not needed (Android 12+) or if it's enabled.
     */
    fun isLocationEnabledForBle(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}
