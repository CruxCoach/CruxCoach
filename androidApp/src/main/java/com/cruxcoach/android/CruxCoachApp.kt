package com.cruxcoach.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.notification.BoardSyncWorker
import com.cruxcoach.android.notification.NostrPushCoordinator
import com.cruxcoach.android.notification.NotificationPollWorker
import com.cruxcoach.android.notification.TrainingReminderWorker
import com.cruxcoach.android.nostr.NostrRelayConnectivityObserver
import com.cruxcoach.android.crash.CruxCoachCrashHandler
import com.cruxcoach.android.util.ApkShareHelper
import com.cruxcoach.android.util.PerfLogger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CruxCoachApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var userPreferences: UserPreferences

    @Inject
    lateinit var syncManager: dagger.Lazy<BoardSyncManager>

    @Inject
    lateinit var kilterSyncEngine: dagger.Lazy<com.cruxcoach.android.data.kilter.KilterSyncEngine>

    @Inject
    lateinit var connectivityObserver: dagger.Lazy<NostrRelayConnectivityObserver>

    @Inject
    lateinit var pushCoordinator: dagger.Lazy<NostrPushCoordinator>

    @Inject
    lateinit var updaterCoordinator: dagger.Lazy<com.cruxcoach.android.updater.UpdaterCoordinator>

    @Inject
    lateinit var relayListResolver: dagger.Lazy<com.cruxcoach.android.nostr.relaydiscovery.RelayListResolver>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /** Re-evaluate locale when user preference is "system". */
    private fun applySystemLocaleIfNeeded() {
        val prefs = getSharedPreferences(
            com.cruxcoach.android.ui.settings.LOCALE_PREFS, MODE_PRIVATE
        )
        val choice = prefs.getString(
            com.cruxcoach.android.ui.settings.KEY_USER_CHOICE, "system"
        ) ?: "system"
        if (choice == "system") {
            com.cruxcoach.android.ui.settings.applyLocaleChoice(this, choice)
        }
    }

    override fun onCreate() {
        PerfLogger.milestone("CruxCoachApp.onCreate START")
        PerfLogger.logMemory("app-create-start")

        // Preload SQLCipher native lib in background — overlaps with app init.
        // When SecureDatabase is eventually needed (lazy), the lib is already loaded.
        Thread({ try { System.loadLibrary("sqlcipher") } catch (_: UnsatisfiedLinkError) {} }, "sqlcipher-preload").start()

        PerfLogger.trace("super.onCreate") { super.onCreate() }

        // Per-app locale: apply on cold start
        applySystemLocaleIfNeeded()

        PerfLogger.trace("CrashHandler setup") {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CruxCoachCrashHandler(this, defaultHandler))
        }
        PerfLogger.trace("ApkShareHelper.cleanupCache") { ApkShareHelper.cleanupCache(this) }
        PerfLogger.trace("TrainingReminderWorker.schedule") { TrainingReminderWorker.schedule(this) }
        PerfLogger.trace("NotificationPollWorker.schedule") { NotificationPollWorker.schedule(this) }

        // FEAT-001: NIP-65 relay discovery — opportunistic refresh on app
        // start so the next sendEvent/subscribe can see the resolved pool.
        // Never blocks: refreshAsync returns immediately and the background
        // coroutine populates the pool once the cache read / bootstrap fetch
        // completes. This must run BEFORE NostrPushCoordinator.start so the
        // initial subscription lands on the resolved relays when discovery
        // wins the race, or DEFAULT_RELAYS otherwise.
        PerfLogger.trace("RelayListResolver.refreshAsync") {
            relayListResolver.get().refreshAsync()
        }

        // Persistent relay subscription (app-scoped, no foreground service).
        // Delivers gift-wrapped DMs with sub-3-second latency while the
        // process is alive; NotificationPollWorker (15 min) remains the
        // backstop for when the process gets killed.
        PerfLogger.trace("NostrPushCoordinator.start") { pushCoordinator.get().start() }

        // Auto-recovery from "reconnect attempts exhausted" after long
        // offline periods — re-triggers reconnect on every new Network.
        PerfLogger.trace("NostrRelayConnectivityObserver.start") {
            connectivityObserver.get().start()
        }

        // FEAT-004: in-app updater. Opportunistic checks on app start,
        // network regain, and a 24 h WorkManager backstop. Hard-disabled
        // when installed via Zapstore (§6.6).
        PerfLogger.trace("UpdaterCoordinator.start") { updaterCoordinator.get().start() }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var lastForegroundPoll = 0L

            override fun onStart(owner: LifecycleOwner) {
                // Re-evaluate "system" locale on every foreground — picks up
                // changes to the device's primary language without a full restart.
                applySystemLocaleIfNeeded()

                val now = System.currentTimeMillis()
                if (now - lastForegroundPoll > 30_000) {
                    lastForegroundPoll = now
                    WorkManager.getInstance(this@CruxCoachApp)
                        .enqueueUniqueWork(
                            "notification_poll_foreground",
                            ExistingWorkPolicy.KEEP,
                            OneTimeWorkRequestBuilder<NotificationPollWorker>()
                                .setConstraints(
                                    Constraints.Builder()
                                        .setRequiredNetworkType(NetworkType.CONNECTED)
                                        .build()
                                )
                                .build()
                        )
                }
            }
        })

        // Pre-warm board bitmap cache in background — ready before first navigation
        appScope.launch {
            PerfLogger.trace("BoardImageCache.prewarm") {
                // Pre-warm with default 12×12 board image; replaced when user's
                // preferred board type is loaded from preferences
                com.cruxcoach.android.ui.board.BoardImageCache.getOrDecode(10L, assets)
            }
        }

        appScope.launch {
            PerfLogger.logCoroutine("appScope", "singleton-init + sync START")
            // Create singletons EARLY — resolves BoardDatabase (+ any pending SQLite
            // migrations) on the IO thread BEFORE the main thread needs them.
            // This must run before the DataStore read (~350 ms cold) to minimize
            // the window where the main thread's CompositionLocalProvider could
            // contend on DoubleCheck locks.
            //
            // Note: no startup probe of the WiFi-Direct-share endpoint. The
            // legitimate receive flow is deep-link driven (cruxcoach://import-board-db
            // from the hotspot's landing page), gated by a user-visible consent
            // dialog in BoardSyncScreen. A bare "server exists on 192.168.49.1:4949"
            // is not a trustworthy import signal — any attacker-controlled AP
            // can synthesise it.
            syncManager.get().syncIfStale()
            // Kilter account: sync (download + upload unsynced) if persistent sync is enabled
            kilterSyncEngine.get().syncOnAppStartIfEnabled()
            // Schedule periodic sync worker (needs DataStore read — fine to be last)
            val interval = userPreferences.syncInterval.first()
            BoardSyncWorker.schedule(this@CruxCoachApp, interval)
            PerfLogger.logCoroutine("appScope", "singleton-init + sync DONE")
        }

        PerfLogger.logMemory("app-create-end")
        PerfLogger.milestone("CruxCoachApp.onCreate END")
    }
}
