package com.cruxcoach.android

import android.app.Application
import android.util.Log
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
    lateinit var nostrRelayPool: dagger.Lazy<com.cruxcoach.android.nostr.NostrRelayPool>

    @Inject
    lateinit var pushCoordinator: dagger.Lazy<NostrPushCoordinator>

    @Inject
    lateinit var updaterCoordinator: dagger.Lazy<com.cruxcoach.android.updater.UpdaterCoordinator>

    @Inject
    lateinit var relayListResolver: dagger.Lazy<com.cruxcoach.android.nostr.relaydiscovery.RelayListResolver>

    @Inject
    lateinit var backupPreferences: dagger.Lazy<com.cruxcoach.android.nostr.backup.BackupPreferences>

    @Inject
    lateinit var communityClimbSubscriber: dagger.Lazy<com.cruxcoach.android.community.CommunityClimbSubscriber>

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

                // Kick the Nostr relay pool back to life on every
                // foreground. Pre-fix, the pool only auto-reconnected on
                // OS network-availability events (NostrRelayConnectivityObserver):
                // a stable Wi-Fi connection that didn't generate an
                // onAvailable event between two foregrounds left a stalled
                // sub silent until the next network-state change. Concretely:
                // user backgrounds CruxCoach for 5 min, a friend publishes a
                // climb, user foregrounds — the new climb wouldn't appear
                // until WLAN flapped or the app restarted. reconnectAll()
                // is idempotent (no-op if every relay is already healthy)
                // and clears the per-relay reconnectExhausted flag so the
                // pool tries again even after 5 prior failures.
                runCatching { nostrRelayPool.get().reconnectAll() }
                    .onFailure { Log.w("CruxCoachApp", "foreground reconnectAll failed", it) }

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
            // Each step is independently fenced: a transient failure in
            // syncIfStale or kilterSyncEngine must NOT skip the
            // BoardSyncWorker / BackupSyncWorker reconciliation that
            // follows. Pre-fix, this was a single chain — one upstream
            // throw silently abandoned every later step, leaving the
            // user with stale schedules until the next app start that
            // happened not to throw on the way through.
            runCatching {
                // Recover from a partial-import state left by a previous run
                // that was killed mid-sync (restartApp during identity-switch,
                // OOM, force-stop). Must run before syncIfStale because the
                // partial state still reports isImported=true, so syncIfStale
                // would not otherwise touch it.
                syncManager.get().recoverPartialImportIfNeeded()
            }.onFailure { PerfLogger.logCoroutine("appScope", "recoverPartialImport failed: ${it.message}") }
            runCatching {
                // One-shot consumer of the 7.sqm post-migration marker:
                // wipes chunk hashes + lastSyncTimestamp and triggers a
                // background sync so the user doesn't land on an empty
                // browser after the Kilter-side wipe. No-op when the
                // marker is absent (i.e. on every subsequent start).
                syncManager.get().handlePostMigrationResync()
            }.onFailure { PerfLogger.logCoroutine("appScope", "handlePostMigrationResync failed: ${it.message}") }
            runCatching {
                // Note: no startup probe of the WiFi-Direct-share endpoint. The
                // legitimate receive flow is deep-link driven (cruxcoach://import-board-db
                // from the hotspot's landing page), gated by a user-visible consent
                // dialog in BoardSyncScreen. A bare "server exists on 192.168.49.1:4949"
                // is not a trustworthy import signal — any attacker-controlled AP
                // can synthesise it.
                syncManager.get().syncIfStale()
            }.onFailure { PerfLogger.logCoroutine("appScope", "syncIfStale failed: ${it.message}") }
            runCatching {
                // Kilter account: sync (download + upload unsynced) if persistent sync is enabled
                kilterSyncEngine.get().syncOnAppStartIfEnabled()
            }.onFailure { PerfLogger.logCoroutine("appScope", "kilterSync failed: ${it.message}") }

            // Reading the interval is the only step that can plausibly
            // fail before the schedule calls (DataStore I/O); fall back
            // to MANUAL so reconciliation still runs.
            val interval = runCatching { userPreferences.syncInterval.first() }
                .getOrDefault(com.cruxcoach.android.data.SyncInterval.MANUAL)

            runCatching {
                BoardSyncWorker.schedule(this@CruxCoachApp, interval)
            }.onFailure { PerfLogger.logCoroutine("appScope", "BoardSyncWorker.schedule failed: ${it.message}") }

            // Kilter publish retry — drains rows where the direct push
            // failed (network blip, server hiccup, token expiry). Idempotent;
            // safe to schedule unconditionally, the worker self-skips when
            // the user has no Kilter token or disabled climb publishing.
            runCatching {
                com.cruxcoach.android.data.kilter.KilterPublishRetryWorker.schedule(this@CruxCoachApp)
            }.onFailure { PerfLogger.logCoroutine("appScope", "KilterPublishRetryWorker.schedule failed: ${it.message}") }

            // Sibling retry worker for the Nostr-side of community publish.
            // Drains rows the editor sent to relays where zero relays
            // accepted (transient failure, captive portal, etc.). Same
            // 6h cadence; idempotent; self-skips when no failed rows.
            runCatching {
                com.cruxcoach.android.community.CommunityPublishRetryWorker.schedule(this@CruxCoachApp)
            }.onFailure { PerfLogger.logCoroutine("appScope", "CommunityPublishRetryWorker.schedule failed: ${it.message}") }

            // One-shot drain on every cold start: covers the crash window
            // between the publisher's pre-send `markClimbPublishInFlight`
            // and the post-send `markClimbPublishedNostr`. Without this,
            // a 'failed' row left by such a crash would have to wait up
            // to 6h for the next periodic tick. WorkManager's
            // APPEND_OR_REPLACE under the same WORK_NAME dedupes against
            // the periodic, so this is safe to call unconditionally.
            runCatching {
                com.cruxcoach.android.community.CommunityPublishRetryWorker.runOnce(this@CruxCoachApp)
            }.onFailure { PerfLogger.logCoroutine("appScope", "CommunityPublishRetryWorker.runOnce failed: ${it.message}") }

            // Channel B: live Nostr subscription for community climbs.
            // Closes the latency gap between two daily Blossom snapshots —
            // climbs other users publish appear in the local DB within
            // seconds of being relayed, not on the next blob refresh.
            // Idempotent; the subscriber re-uses the existing relay pool
            // and self-restarts on connection drops.
            runCatching {
                communityClimbSubscriber.get().start(appScope)
            }.onFailure { PerfLogger.logCoroutine("appScope", "CommunityClimbSubscriber.start failed: ${it.message}") }

            runCatching {
                // FEAT-002: reconcile the backup worker with persisted prefs on
                // every app start — catches cases where the user flipped the
                // toggle + killed the app before WorkManager committed the
                // schedule change.
                //
                // FEAT-021: read the BACKUP-specific interval, not the
                // board-sync `interval` from above. Pre-fix the same
                // board-sync value drove both schedules — users with
                // board-sync=MANUAL silently had their periodic backup
                // cancelled on every cold start. The two prefs are
                // wholly unrelated user-facing options that happen to
                // share the SyncInterval enum type.
                val backupPrefs = backupPreferences.get()
                val backupEnabled = backupPrefs.isBackupEnabled() && backupPrefs.isBackupFeatureEnabled()
                val backupInterval = runCatching { backupPrefs.backupInterval.first() }
                    .getOrDefault(com.cruxcoach.android.data.SyncInterval.DAILY)
                com.cruxcoach.android.nostr.backup.BackupSyncWorker.schedule(
                    this@CruxCoachApp,
                    enabled = backupEnabled,
                    interval = backupInterval,
                )
            }.onFailure { PerfLogger.logCoroutine("appScope", "BackupSyncWorker.schedule failed: ${it.message}") }
            PerfLogger.logCoroutine("appScope", "singleton-init + sync DONE")
        }

        PerfLogger.logMemory("app-create-end")
        PerfLogger.milestone("CruxCoachApp.onCreate END")
    }
}
