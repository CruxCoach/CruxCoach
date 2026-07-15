package com.cruxcoach.android.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives [PackageInstaller] status callbacks (§5.5). Two duties:
 *
 *  1. On [PackageInstaller.STATUS_PENDING_USER_ACTION] — launch the
 *     system consent dialog (the only dialog in the whole feature).
 *  2. On every terminal STATUS_* — hand off to [UpdateNotifier] /
 *     [UpdaterRepository] to clear state, post the outcome notification,
 *     and clean up the cached APK.
 *
 * Registered in the manifest (not dynamically) because the callback may
 * fire minutes after the last Activity died — we need the process to be
 * re-spun just to receive it.
 */
@AndroidEntryPoint
class ApkInstallStatusReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: UpdaterRepository

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(ApkInstaller.EXTRA_SESSION_ID, -1)
        // The handoffs below run on the repository's coroutine scope after
        // onReceive returns. goAsync() holds the process alive until that async
        // work signals completion (onDone -> pending.finish()); otherwise the
        // OS can reap the process first — most likely on STATUS_SUCCESS, where
        // the app was just replaced and runs as a fresh idle process — dropping
        // the state reset / APK cleanup / notification cancel.
        val pending = goAsync()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val userAction = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (userAction != null) {
                    // Do NOT startActivity from here: a background receiver's
                    // start can be silently dropped (API 29+, stricter 34/35).
                    // The repository surfaces the consent via a tappable
                    // notification whose tap carries a fresh BAL grant.
                    repository.onConsentRequired(userAction) { pending.finish() }
                } else {
                    Log.w(TAG, "STATUS_PENDING_USER_ACTION without consent intent")
                    repository.onInstallOutcome(
                        InstallOutcome.Failed(status = status, message = "no_consent_intent"),
                    ) { pending.finish() }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                repository.onInstallOutcome(InstallOutcome.Success) { pending.finish() }
            }
            else -> {
                Log.w(TAG, "Install status $status ($message) for session $sessionId")
                repository.onInstallOutcome(
                    InstallOutcome.Failed(status = status, message = message),
                ) { pending.finish() }
            }
        }
    }

    companion object {
        private const val TAG = "ApkInstallStatusReceiver"
    }
}

sealed interface InstallOutcome {
    data object Success : InstallOutcome
    data class Failed(val status: Int, val message: String?) : InstallOutcome
}
