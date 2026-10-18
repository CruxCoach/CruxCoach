package com.cruxcoach.app.ui

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.health.HealthSettingsPresenter
import com.cruxcoach.app.health.HealthSettingsState
import com.cruxcoach.app.health.HealthSync
import com.cruxcoach.app.health.SleepHint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HealthScreenState(
    val loading: Boolean,
    /** HealthKit exists on this device and the host attached it. */
    val available: Boolean,
    /** HealthKit refused the access request itself: this build has no HealthKit entitlement, or the device is restricted. */
    val accessFailed: Boolean,
    val readEnabled: Boolean,
    val exportEnabled: Boolean,
    /** The athlete was asked for read access; HealthKit never says whether reading was allowed. */
    val readAsked: Boolean,
    /** authorized | denied | notDetermined (meals and water). */
    val writeStatus: String,
    /** A switched-on direction was never asked for access: show "Allow access". */
    val needsAccess: Boolean,
    /** Sending is on but denied in the Health app; only the athlete can change that there. */
    val writeDenied: Boolean,
    /** "Sync now" can run (a direction is on and usable). */
    val canSync: Boolean,
    val syncing: Boolean,
    val hasLastSync: Boolean,
    val lastSyncFailed: Boolean,
    val lastSyncAtMs: Long,
    val added: Int,
    val updated: Int,
    val skippedManual: Int,
    val boardOverlaps: Int,
    val hasSleep: Boolean,
    val sleepHours: Double,
    /** done | unchanged | noAccess | failed of the last send from this screen; "" before one. */
    val lastExport: String,
)

/**
 * The Apple Health link in the training settings (Android `HealthConnectSettingsCard`): one switch per
 * direction — the coach reads sleep and climbing workouts, nutrition writes meals and water — each with
 * its own access request, a manual sync with the last result, and the privacy line (Swift's text).
 */
class HealthScreenModel internal constructor(private val presenter: HealthSettingsPresenter) {
    val currentState: HealthScreenState get() = map(presenter.state.value)

    fun watch(onState: (HealthScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    /** On appear and when the app returns to the foreground (access may have changed in the Health app). */
    fun refresh() { presenter.refresh() }
    fun setReadEnabled(on: Boolean) { presenter.setReadEnabled(on) }
    fun setExportEnabled(on: Boolean) { presenter.setExportEnabled(on) }
    fun permit() { presenter.permit() }
    fun syncNow() { presenter.syncNow() }
    fun close() = presenter.close()

    private fun map(s: HealthSettingsState): HealthScreenState {
        val usable = s.available && !s.accessFailed
        val last = s.lastSync
        val sleep = s.sleepHours
        return HealthScreenState(
            loading = s.loading,
            available = s.available,
            accessFailed = s.accessFailed,
            readEnabled = s.readEnabled,
            exportEnabled = s.exportEnabled,
            readAsked = s.readAsked,
            writeStatus = s.writeStatus,
            needsAccess = usable && ((s.readEnabled && !s.readAsked) || (s.exportEnabled && s.writeStatus == HealthSync.NOT_DETERMINED)),
            writeDenied = usable && s.exportEnabled && s.writeStatus == HealthSync.DENIED,
            canSync = usable && ((s.readEnabled && s.readAsked) || (s.exportEnabled && s.writeStatus == HealthSync.AUTHORIZED)),
            syncing = s.syncing,
            hasLastSync = s.readEnabled && last != null,
            lastSyncFailed = last?.failed ?: false,
            lastSyncAtMs = last?.atMillis ?: 0L,
            added = last?.added ?: 0,
            updated = last?.updated ?: 0,
            skippedManual = last?.skippedManual ?: 0,
            boardOverlaps = last?.boardOverlaps ?: 0,
            hasSleep = s.readEnabled && sleep != null,
            sleepHours = sleep ?: 0.0,
            lastExport = s.lastExport,
        )
    }
}

/** Last night's sleep as a check-in suggestion: "From Apple Health: 6.8 h → 3". [hasHint] false = show nothing. */
class HealthSleepHintUi(val hasHint: Boolean, val hours: Double, val score: Int)

/**
 * What Today and nutrition call (Android: `rememberSleepHint` in the check-in, `syncClimbing` when Today
 * opens, the nutrition screen's `exportDay`). Each one is a no-op while its switch is off; nothing throws.
 * Callbacks arrive on the main thread.
 */
class HealthActions internal constructor(
    private val sync: HealthSync,
    private val service: AthleteService,
    private val io: CoroutineDispatcher,
) {
    private val scope: CoroutineScope = MainScope()

    /**
     * Loads last night's sleep once; the hint is never saved by itself — a tap fills the unanswered sleep
     * score with [HealthSleepHintUi.score] and saving stays the athlete's.
     */
    fun loadSleepHint(onHint: (HealthSleepHintUi) -> Unit) {
        scope.launch {
            val hours = runCatching { sync.lastNightSleepHours(withContext(io) { service.today() }) }.getOrNull()
            onHint(if (hours == null) HealthSleepHintUi(false, 0.0, 0) else HealthSleepHintUi(true, hours, SleepHint.scoreFor(hours)))
        }
    }

    /** Climbing workouts of the last 14 days become climbing days; call when Today opens. */
    fun syncClimbing() {
        scope.launch { runCatching { sync.syncClimbing() } }
    }

    /** Mirrors [dayIso]'s meals and water to Apple Health when the export is on; call when nutrition shows or changes a day. */
    fun exportDay(dayIso: String) {
        scope.launch { runCatching { sync.exportDayIfEnabled(dayIso) } }
    }
}
