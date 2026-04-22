package com.cruxcoach.android.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Wraps [PackageInstaller] session commit (§5.5). Produces the
 * `PendingIntent` that [ApkInstallStatusReceiver] listens on, so the
 * whole pipeline hangs off one receiver class and one intent action.
 *
 * This class deliberately does NOT invoke the system consent dialog —
 * that happens when [ApkInstallStatusReceiver] gets
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] and forwards the
 * embedded intent. Splitting it keeps the install flow survivable
 * across process death: the receiver can fire even if our Activity /
 * ViewModel no longer exists.
 */
class ApkInstaller(private val context: Context) {

    fun install(apkFile: File): InstallResult {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return InstallResult.Error("APK file missing or empty")
        }
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = try {
            pi.createSession(params)
        } catch (e: Exception) {
            Log.w(TAG, "PackageInstaller.createSession failed", e)
            return InstallResult.Error(e.message ?: e.javaClass.simpleName)
        }

        return try {
            pi.openSession(sessionId).use { session ->
                FileInputStream(apkFile).use { input ->
                    session.openWrite("cruxcoach.apk", 0, apkFile.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val statusIntent = Intent(ACTION_INSTALL_STATUS).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
                val pendingFlags =
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    pendingFlags,
                )
                session.commit(pending.intentSender)
            }
            InstallResult.Committed(sessionId = sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "PackageInstaller.commit failed", e)
            runCatching { pi.abandonSession(sessionId) }
            InstallResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    sealed interface InstallResult {
        data class Committed(val sessionId: Int) : InstallResult
        data class Error(val message: String) : InstallResult
    }

    companion object {
        private const val TAG = "ApkInstaller"
        const val ACTION_INSTALL_STATUS = "com.cruxcoach.android.updater.INSTALL_STATUS"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
