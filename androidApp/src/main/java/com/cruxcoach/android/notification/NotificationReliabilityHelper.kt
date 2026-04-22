package com.cruxcoach.android.notification

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helpers to detect and recover from the two main reasons that FCM-less
 * push notifications silently stop arriving on Android:
 *
 *  1. Battery optimization restricting network + WorkManager scheduling
 *     once the app falls into the `rare` / `restricted` standby bucket.
 *  2. OEM-specific autostart / background killers (Xiaomi MIUI, Huawei EMUI,
 *     OnePlus, Samsung) that stop both persistent Relay sockets and periodic
 *     workers regardless of the AOSP battery whitelist state.
 *
 * Keep this file side-effect free: the UI queries state and opens intents
 * only when the user acts.
 */
object NotificationReliabilityHelper {

    /**
     * True when the app is exempted from battery optimizations. On SDK < M
     * the concept does not exist, so we treat it as "no restriction".
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent to open the system's "Ignore battery optimizations" settings
     * list. Prefer this over ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * because the latter requires the SYSTEM_ALERT_WINDOW-like permission
     * that Play rejects — and F-Droid/Zapstore reviewers have flagged it
     * as invasive. The settings-list path is safe for any distribution.
     */
    fun ignoreBatteryOptimizationsSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * OEMs with hardcoded background killers that ignore the AOSP
     * battery whitelist. For these we surface a second CTA that
     * deeplinks into the vendor's autostart/battery UI.
     *
     * Source: dontkillmyapp.com (Urbandroid Team) — CC BY-SA 4.0.
     * Community-maintained list of affected manufacturers and the settings
     * screens that unlock reliable background work per vendor.
     * See THIRD_PARTY_LICENSES.md for the full attribution.
     */
    enum class OemKillerSeverity {
        NONE,       // Pixel, Sony, Nokia (Android One), GrapheneOS, CalyxOS, /e/OS, LineageOS
        MODERATE,   // Samsung, OnePlus — user-reachable settings, behave when whitelisted
        SEVERE      // Xiaomi, Huawei, Oppo/Realme/Vivo, Meizu — hardcoded killers, may still break
    }

    fun detectOem(): OemKillerSeverity {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return when {
            manufacturer == "xiaomi" || brand == "xiaomi" || brand == "redmi" || brand == "poco" -> OemKillerSeverity.SEVERE
            manufacturer == "huawei" || manufacturer == "honor" || brand == "huawei" || brand == "honor" -> OemKillerSeverity.SEVERE
            manufacturer == "oppo" || manufacturer == "realme" || manufacturer == "vivo" || manufacturer == "iqoo" -> OemKillerSeverity.SEVERE
            manufacturer == "meizu" -> OemKillerSeverity.SEVERE
            manufacturer == "samsung" -> OemKillerSeverity.MODERATE
            manufacturer == "oneplus" -> OemKillerSeverity.MODERATE
            else -> OemKillerSeverity.NONE
        }
    }

    /**
     * Returns an intent that opens the OEM-specific autostart / background
     * activity settings for this device, or null if no known deeplink
     * applies or the target Activity is not exported on this ROM version.
     *
     * Always validate resolvability before launching — the Activity paths
     * change across ROM versions and vendors ship inconsistent builds.
     */
    fun oemAutostartSettingsIntent(context: Context): Intent? {
        val pm = context.packageManager
        val candidates = when (detectOem()) {
            OemKillerSeverity.NONE -> emptyList()
            OemKillerSeverity.MODERATE -> moderateOemCandidates()
            OemKillerSeverity.SEVERE -> severeOemCandidates()
        }
        for (component in candidates) {
            val intent = Intent().apply {
                this.component = component
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(pm) != null) return intent
        }
        return null
    }

    private fun moderateOemCandidates(): List<ComponentName> {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when (manufacturer) {
            "samsung" -> listOf(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                ),
                ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            )
            "oneplus" -> listOf(
                ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            )
            else -> emptyList()
        }
    }

    private fun severeOemCandidates(): List<ComponentName> {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return when {
            manufacturer == "xiaomi" || brand in listOf("xiaomi", "redmi", "poco") -> listOf(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            )
            manufacturer == "huawei" || manufacturer == "honor" -> listOf(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ),
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            )
            manufacturer == "oppo" || manufacturer == "realme" -> listOf(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                ),
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                ),
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            )
            manufacturer == "vivo" || manufacturer == "iqoo" -> listOf(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                ),
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            )
            manufacturer == "meizu" -> listOf(
                ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.security.SHOW_APPSEC"
                )
            )
            else -> emptyList()
        }
    }

    /**
     * Fallback: always works — open this app's system settings page, from
     * where the user can navigate to the permission / battery sub-screens.
     */
    fun appInfoIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w("NotificationReliability", "Failed to start intent ${intent.action ?: intent.component}", e)
            false
        }
    }
}
