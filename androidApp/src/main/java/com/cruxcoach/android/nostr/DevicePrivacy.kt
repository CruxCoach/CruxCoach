package com.cruxcoach.android.nostr

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Generalize device info to prevent fingerprinting.
 * Returns a device tier (high-end/mid-range/low-end) based on RAM
 * instead of the exact model name.
 */
object DevicePrivacy {

    fun generalizedDeviceTier(context: Context): String {
        val ramGb = try {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.totalMem / (1024 * 1024 * 1024)
        } catch (_: Exception) { 0L }

        return when {
            ramGb >= 8 -> "high-end"
            ramGb >= 4 -> "mid-range"
            else -> "low-end"
        }
    }

    fun generalizedDeviceInfoLine(context: Context): String {
        return "Android API ${Build.VERSION.SDK_INT} | ${generalizedDeviceTier(context)} | ${java.util.Locale.getDefault().language}"
    }
}
