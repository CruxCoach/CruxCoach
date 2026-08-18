package com.cruxcoach.android.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Hard-disables the in-app updater when CruxCoach was installed via a
 * recognized app store (currently only Zapstore — §6.6). Detection runs
 * on every check, not cached: a user can uninstall Zapstore and reinstall
 * by direct sideload later, and the gate must reflect the current install
 * source immediately.
 */
class InstallSourceGate(private val context: Context) {

    fun selfUpdateAllowed(): Boolean {
        val installer = currentInstallerId() ?: return true
        val gated = installer in STORE_INSTALLER_IDS
        if (gated) Log.i(TAG, "Self-updater disabled — installed by $installer")
        return !gated
    }

    /** Returns the package id that installed us, or null if unknown. */
    fun currentInstallerId(): String? {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "PackageManager could not resolve self — assuming user install", e)
            null
        }
    }

    companion object {
        private const val TAG = "InstallSourceGate"

        /**
         * Set of installer IDs that ship CruxCoach as a managed install
         * with their own update mechanism. Must be updated explicitly
         * when adding a new store-based distribution channel.
         */
        val STORE_INSTALLER_IDS: Set<String> = setOf(
            "dev.zapstore.app",
        )
    }
}
