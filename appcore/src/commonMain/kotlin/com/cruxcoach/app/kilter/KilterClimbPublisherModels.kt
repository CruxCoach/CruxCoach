package com.cruxcoach.app.kilter

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.ui.Subscription
import com.cruxcoach.data.repository.BoardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android's global `auto_note_enabled` (DataStore, not per identity), on the same key. */
object KilterClimbPublisherPrefs {
    const val AUTO_NOTE_ENABLED = "auto_note_enabled"

    fun autoNoteEnabled(store: KeyValueStore): Boolean = store.getString(AUTO_NOTE_ENABLED) == "true"

    fun setAutoNoteEnabled(store: KeyValueStore, enabled: Boolean) =
        store.putString(AUTO_NOTE_ENABLED, enabled.toString())
}

/**
 * The Kilter account card's publish part (Android `KilterAccountSection`):
 * the "Publish own climbs to Kilter" switch and the publish queue with
 * "Retry now". [lastRetry] is the last manual pass: none | running | done |
 * retrySoon | authLost | noLogin | optedOut | noIdentity | failed.
 */
class KilterPublishSettingsState(
    val publishEnabled: Boolean,
    val signedIn: Boolean,
    val pendingCount: Int,
    val failedCount: Int,
    /** Epoch millis of the newest attempt, 0 when none was ever recorded. */
    val lastAttemptAtMs: Long,
    val retryRunning: Boolean,
    val lastRetry: String,
)

class KilterPublishSettingsModel internal constructor(
    private val account: KilterClimbAccount,
    private val boardRepository: BoardRepository,
    private val retry: KilterClimbPublishRetry,
    private val readEnabled: () -> Boolean,
    private val writeEnabled: (Boolean) -> Unit,
    private val runScope: CoroutineScope,
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val state = MutableStateFlow(
        KilterPublishSettingsState(readEnabled(), account.isSignedIn(), 0, 0, 0L, false, "none"),
    )

    val currentState: KilterPublishSettingsState get() = state.value

    fun watch(onState: (KilterPublishSettingsState) -> Unit): Subscription {
        val job = scope.launch { state.collect { onState(it) } }
        return Subscription { job.cancel() }
    }

    /** Re-reads the switch, the login and the queue counters. */
    fun refresh() {
        scope.launch {
            val stats = withContext(io) { runCatching { boardRepository.getKilterPublishQueueStats() }.getOrNull() }
            state.update {
                KilterPublishSettingsState(
                    publishEnabled = readEnabled(),
                    signedIn = account.isSignedIn(),
                    pendingCount = stats?.pendingCount?.toInt() ?: 0,
                    failedCount = stats?.failedCount?.toInt() ?: 0,
                    lastAttemptAtMs = stats?.lastAttemptAtMs ?: 0L,
                    retryRunning = it.retryRunning,
                    lastRetry = it.lastRetry,
                )
            }
        }
    }

    fun setPublishEnabled(enabled: Boolean) {
        writeEnabled(enabled)
        state.update { copyOf(it, publishEnabled = enabled) }
    }

    /**
     * Android's "Retry now" (`KilterPublishRetryWorker.runOnce`). The pass runs
     * in the app-lifetime scope, so leaving the screen never cuts a request off.
     */
    fun retryNow() {
        if (state.value.retryRunning) return
        state.update { copyOf(it, retryRunning = true, lastRetry = "running") }
        runScope.launch {
            val code = try {
                val report = withContext(io) { retry.run() }
                when {
                    report.skipped == "user-opted-out" -> "optedOut"
                    report.skipped == "no-kilter-login" -> "noLogin"
                    report.skipped == "no-nostr-pubkey" -> "noIdentity"
                    report.skipped == "auth-lost" -> "authLost"
                    report.skipped == "queue-unreadable" -> "failed"
                    report.retrySoon -> "retrySoon"
                    else -> "done"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "failed"
            }
            state.update { copyOf(it, retryRunning = false, lastRetry = code) }
            refresh()
        }
    }

    fun close() = scope.cancel()

    private fun copyOf(
        s: KilterPublishSettingsState,
        publishEnabled: Boolean = s.publishEnabled,
        retryRunning: Boolean = s.retryRunning,
        lastRetry: String = s.lastRetry,
    ) = KilterPublishSettingsState(
        publishEnabled, s.signedIn, s.pendingCount, s.failedCount, s.lastAttemptAtMs, retryRunning, lastRetry,
    )
}

/**
 * The profile's Kilter and community extras (Android `NostrProfileScreen`):
 * the global "Add a Nostr post when publishing" default and "Use Kilter
 * username". [kilterUsername] is "" until a signed-in account's name is known.
 */
class KilterProfileExtrasState(
    val autoNoteEnabled: Boolean,
    val kilterSignedIn: Boolean,
    val kilterUsername: String,
    val loadingUsername: Boolean,
)

class KilterProfileExtrasModel internal constructor(
    private val account: KilterClimbAccount,
    private val keyValues: KeyValueStore,
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val state = MutableStateFlow(
        KilterProfileExtrasState(
            autoNoteEnabled = KilterClimbPublisherPrefs.autoNoteEnabled(keyValues),
            kilterSignedIn = account.isSignedIn(),
            kilterUsername = if (account.isSignedIn()) account.cachedUsername() else "",
            loadingUsername = false,
        ),
    )

    val currentState: KilterProfileExtrasState get() = state.value

    fun watch(onState: (KilterProfileExtrasState) -> Unit): Subscription {
        val job = scope.launch { state.collect { onState(it) } }
        return Subscription { job.cancel() }
    }

    fun setAutoNoteEnabled(enabled: Boolean) {
        KilterClimbPublisherPrefs.setAutoNoteEnabled(keyValues, enabled)
        state.update { KilterProfileExtrasState(enabled, it.kilterSignedIn, it.kilterUsername, it.loadingUsername) }
    }

    /**
     * Android keeps the name from the sign-in; the iOS sign-in predates it, so a
     * signed-in account without a cached name asks the portal once (one GET of
     * the user's own record).
     */
    fun loadKilterUsername() {
        val s = state.value
        if (!account.isSignedIn() || s.kilterUsername.isNotEmpty() || s.loadingUsername) return
        state.update { KilterProfileExtrasState(it.autoNoteEnabled, true, it.kilterUsername, true) }
        scope.launch {
            val name = try {
                withContext(io) { account.refreshDisplayUsername() }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            state.update { KilterProfileExtrasState(it.autoNoteEnabled, true, name, false) }
        }
    }

    fun close() = scope.cancel()
}
