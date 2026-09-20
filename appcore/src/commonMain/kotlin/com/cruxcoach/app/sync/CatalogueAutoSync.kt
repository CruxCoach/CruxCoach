package com.cruxcoach.app.sync

import com.cruxcoach.app.platform.ConnectivityMonitor
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.app.settings.BoardDownloadSelection
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.app.settings.SyncInterval
import com.cruxcoach.app.settings.safeGet
import com.cruxcoach.app.settings.safePut
import com.cruxcoach.domain.board.BoardBrand

/**
 * The `sync_interval` preference, as far as iOS can honour it.
 *
 * Android runs a periodic `BoardSyncWorker` that can wake the app; iOS has no
 * equivalent for a multi-hundred-megabyte catalogue download, so this only runs
 * when the app is in the foreground: [refreshIfDue] is called when the app
 * becomes active and starts a normal catalogue sync if the chosen cadence has
 * elapsed. The settings screen says so rather than implying background sync.
 *
 * The due marker is iOS-only bookkeeping ([KEY_LAST_AUTO_SYNC]) and is
 * deliberately not Android's `last_sync_timestamp`: that one is an ISO instant
 * written by a worker this app does not run, and mis-reading it would either
 * suppress every refresh or trigger one on every launch.
 */
class CatalogueAutoSync(
    private val controller: CatalogueSyncController,
    private val settings: SettingsStore,
    private val keyValues: KeyValueStore,
    private val connectivity: ConnectivityMonitor,
    private val clock: WallClock,
) {
    /**
     * Returns the brands a refresh was started for, empty when nothing was due.
     * Never throws: a failed refresh is a value, and the sync screen shows the
     * per-brand failure anyway.
     */
    fun refreshIfDue(): List<String> {
        val interval = settings.snapshot.syncInterval
        if (interval == SyncInterval.MANUAL) return emptyList()
        if (controller.state.value.running) return emptyList()
        if (!connectivity.isOnline.value) return emptyList()
        // Nothing installed yet is the catalogue screen's job, not a refresh.
        val installed = controller.installedBrands
        if (installed.isEmpty()) return emptyList()
        if (!isDue(interval)) return emptyList()

        val wanted: List<BoardBrand> = BoardDownloadSelection.selected(keyValues).filter { it in installed }
        if (wanted.isEmpty()) return emptyList()
        markRun()
        controller.start(wanted)
        return wanted.map { it.wireValue }
    }

    /** Seconds until the next refresh may run; 0 when one is due, -1 when the cadence is manual. */
    fun secondsUntilDue(): Long {
        val interval = settings.snapshot.syncInterval
        if (interval == SyncInterval.MANUAL) return -1L
        val last = lastRun() ?: return 0L
        val elapsed = clock.epochSeconds() - last
        return (cycleSeconds(interval) - elapsed).coerceAtLeast(0L)
    }

    private fun isDue(interval: SyncInterval): Boolean {
        val last = lastRun() ?: return true
        val elapsed = clock.epochSeconds() - last
        // A marker in the future means the device clock moved backwards; re-arm
        // rather than block refreshes until the old timestamp is reached again.
        if (elapsed < 0) return true
        return elapsed >= cycleSeconds(interval)
    }

    private fun lastRun(): Long? = keyValues.safeGet(KEY_LAST_AUTO_SYNC)?.toLongOrNull()

    private fun markRun() {
        keyValues.safePut(KEY_LAST_AUTO_SYNC, clock.epochSeconds().toString())
    }

    private fun cycleSeconds(interval: SyncInterval): Long =
        if (interval == SyncInterval.WEEKLY) WEEK_SECONDS else DAY_SECONDS

    companion object {
        const val KEY_LAST_AUTO_SYNC = "catalogue_auto_sync_last_epoch"
        const val DAY_SECONDS = 86_400L
        const val WEEK_SECONDS = 7 * DAY_SECONDS
    }
}
