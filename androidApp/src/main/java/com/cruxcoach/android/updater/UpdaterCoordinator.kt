package com.cruxcoach.android.updater

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped observer that funnels every opportunistic trigger
 * (§5.1, §6.12) into [UpdaterRepository.checkNow]:
 *
 *  - `ProcessLifecycleOwner` `ON_START` — user opened the app
 *  - `ConnectivityManager` `onAvailable` on a validated internet
 *    capability — network came back
 *  - `UpdateCheckWorker` — 24 h backstop (enqueued once in [start])
 *
 * Mirrors [com.cruxcoach.android.notification.NostrPushCoordinator]'s
 * lifetime: `start()` from `CruxCoachApp.onCreate`, one observer per
 * process, no teardown.
 */
@Singleton
class UpdaterCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: UpdaterRepository,
    private val pinStore: UpdaterPinStore,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "Uncaught exception in updater coordinator", e)
        },
    )
    private var started = false

    fun start() {
        if (started) return
        started = true

        if (!repository.selfUpdateAllowed()) {
            Log.i(TAG, "Self-updater hard-disabled by install source gate")
            UpdateCheckWorker.cancel(context)
            return
        }

        scope.launch {
            runCatching { pinStore.getOrTofu() }.onFailure {
                Log.w(TAG, "TOFU pin bootstrap failed", it)
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        registerConnectivityCallback()
        UpdateCheckWorker.enqueue(context)
        // Re-attach to a download the OS may have killed us mid-way through, so
        // the pipeline can't strand in DOWNLOADING forever on killer-OEM devices.
        repository.resumePendingDownloadIfAny()
        // INSTALLING has no callback-free way out either, and unlike the other
        // two nothing used to catch it — a dropped PackageInstaller result
        // left the updater dead for good.
        repository.recoverInterruptedInstall()
        // A verified APK may already be ready after process death or after the
        // one-time package-install permission was granted outside the app.
        repository.resumeAutomaticInstallIfReady()
    }

    override fun onStart(owner: LifecycleOwner) {
        fire(UpdateChecker.Trigger.APP_FOREGROUND)
    }

    private fun registerConnectivityCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                fire(UpdateChecker.Trigger.NETWORK_AVAILABLE)
            }
        }
        try {
            cm.registerNetworkCallback(req, cb)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register connectivity callback", e)
        }
    }

    private fun fire(trigger: UpdateChecker.Trigger) {
        scope.launch {
            runCatching { repository.checkNow(trigger) }
                .onFailure { Log.w(TAG, "Check on $trigger failed", it) }
        }
    }

    companion object {
        private const val TAG = "UpdaterCoordinator"
    }
}
