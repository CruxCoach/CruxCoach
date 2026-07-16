package com.cruxcoach.android.nostr

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.cruxcoach.android.BuildConfig

internal fun formatGeneralizedDeviceInfoLine(
    apiLevel: Int,
    deviceTier: String,
    language: String,
    versionName: String,
    versionCode: Int,
    memoryPressure: String,
    appDisplayName: String = BuildConfig.APP_DISPLAY_NAME,
): String =
    "$appDisplayName $versionName ($versionCode) | Android API $apiLevel | $deviceTier | " +
        "$language | memory-pressure=$memoryPressure"

internal fun memoryPressureBucket(count: Int): String = when {
    count <= 0 -> "none"
    count <= 3 -> "occasional"
    else -> "frequent"
}

/**
 * Generalize device info to prevent fingerprinting.
 * Returns a device tier (high-end/mid-range/low-end) based on RAM
 * instead of the exact model name.
 */
object DevicePrivacy {
    private const val HEALTH_PREFS = "local_health"
    private const val MEMORY_PRESSURE_COUNT = "memory_pressure_count"

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
        val pressureCount = context.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE)
            .getInt(MEMORY_PRESSURE_COUNT, 0)
        return formatGeneralizedDeviceInfoLine(
            apiLevel = Build.VERSION.SDK_INT,
            deviceTier = generalizedDeviceTier(context),
            language = java.util.Locale.getDefault().language,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            memoryPressure = memoryPressureBucket(pressureCount),
        )
    }

    /** Local-only coarse health signal, exported only in a user-consented report. */
    fun recordMemoryPressure(context: Context) {
        val prefs = context.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE)
        val next = (prefs.getInt(MEMORY_PRESSURE_COUNT, 0) + 1).coerceAtMost(1_000)
        // The process may be reclaimed immediately after this callback.
        prefs.edit().putInt(MEMORY_PRESSURE_COUNT, next).commit()
    }
}
