package com.cruxcoach.app.background

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.app.ui.BackupScreenModel
import com.cruxcoach.app.ui.BackupScreenState
import com.cruxcoach.app.ui.Subscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/** What the background settings page shows. [backupInterval] is "DAILY", "WEEKLY" or "MANUAL". */
class BackgroundSettingsState(
    val announcementsEnabled: Boolean,
    val releases: Boolean,
    val issues: Boolean,
    val tips: Boolean,
    val general: Boolean,
    val backupEnabled: Boolean,
    val backupInterval: String,
)

/**
 * The switches the background refresh obeys, on Android's keys and defaults: Android's
 * "Background announcements" with its four categories (`announcements_enabled`,
 * `announcement_cat_*`, all on by default) and the backup cadence (`backup_interval`, MANUAL by
 * default). Swift-facing; call from the main thread.
 */
class BackgroundSettingsModel internal constructor(
    private val settings: SettingsStore,
    private val keyValues: KeyValueStore,
) {
    private val listeners = LinkedHashMap<Int, (BackgroundSettingsState) -> Unit>()
    private var nextListener = 0

    val currentState: BackgroundSettingsState get() = read()

    fun watch(onState: (BackgroundSettingsState) -> Unit): Subscription {
        val id = nextListener++
        listeners[id] = onState
        onState(read())
        return Subscription { listeners.remove(id) }
    }

    fun setAnnouncementsEnabled(value: Boolean) = change { settings.setAnnouncementsEnabled(value) }
    fun setReleases(value: Boolean) = change { settings.setAnnouncementReleases(value) }
    fun setIssues(value: Boolean) = change { settings.setAnnouncementIssues(value) }
    fun setTips(value: Boolean) = change { settings.setAnnouncementTips(value) }
    fun setGeneral(value: Boolean) = change { settings.setAnnouncementGeneral(value) }

    /** "DAILY", "WEEKLY" or "MANUAL"; anything else is ignored. */
    fun setBackupInterval(value: String) {
        if (value != BackupStep.INTERVAL_DAILY && value != BackupStep.INTERVAL_WEEKLY && value != BackupStep.INTERVAL_MANUAL) return
        change { keyValues.putString(BackupStep.KEY_BACKUP_INTERVAL, value) }
    }

    private fun change(write: () -> Unit) {
        write()
        val state = read()
        for (listener in listeners.values.toList()) listener(state)
    }

    private fun read(): BackgroundSettingsState {
        val s = BackgroundWiring.announcementSwitches(settings)
        val interval = when (val v = keyValues.getString(BackupStep.KEY_BACKUP_INTERVAL)) {
            BackupStep.INTERVAL_DAILY, BackupStep.INTERVAL_WEEKLY -> v
            else -> BackupStep.INTERVAL_MANUAL
        }
        return BackgroundSettingsState(
            announcementsEnabled = s.enabled,
            releases = s.release,
            issues = s.issue,
            tips = s.tip,
            general = s.general,
            backupEnabled = keyValues.getString(KEY_BACKUP_ENABLED) == "true",
            backupInterval = interval,
        )
    }

    private companion object {
        /** `BackupState`'s switch, shown so the page can say a cadence needs backups on. */
        const val KEY_BACKUP_ENABLED = "backup_enabled"
    }
}

/** Glue the composition root uses; not for Swift. */
internal object BackgroundWiring {
    fun announcementSwitches(settings: SettingsStore): AnnouncementSwitches {
        val s = settings.snapshot
        return AnnouncementSwitches(
            s.announcementsEnabled, s.announcementReleases, s.announcementIssues, s.announcementTips, s.announcementGeneral,
        )
    }

    /** Runs [block] in [scope] and waits for it; cancelling the waiter leaves the run going. */
    suspend fun detached(scope: CoroutineScope, block: suspend () -> Unit) {
        scope.async { block() }.await()
    }

    /**
     * A backup through the screen's model, so a periodic and a manual one never run side by
     * side; false when one is already under way or the backup failed. Main thread only.
     */
    suspend fun awaitBackup(model: BackupScreenModel, exportedAt: String): Boolean {
        if (model.currentState.busy) return false
        model.backUpNow(exportedAt)
        val done = CompletableDeferred<Boolean>()
        val subscription = model.watch { state ->
            if (!state.busy) done.complete(state.phase == BackupScreenState.PHASE_BACKED_UP)
        }
        return try {
            done.await()
        } finally {
            subscription.cancel()
        }
    }
}
