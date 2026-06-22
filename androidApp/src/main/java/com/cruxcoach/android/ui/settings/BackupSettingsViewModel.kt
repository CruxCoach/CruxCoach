package com.cruxcoach.android.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.SyncInterval
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.SignerMode
import com.cruxcoach.android.nostr.backup.BackupErrorReason
import com.cruxcoach.android.nostr.backup.BackupException
import com.cruxcoach.android.nostr.backup.BackupInfo
import com.cruxcoach.android.nostr.backup.BackupPreferences
import com.cruxcoach.android.nostr.backup.BackupRepository
import com.cruxcoach.android.nostr.backup.BackupSyncWorker
import com.cruxcoach.android.nostr.backup.DeleteRemoteNote
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class BackupSettingsState(
    val featureEnabled: Boolean = true,
    val backupEnabled: Boolean = false,
    val interval: SyncInterval = SyncInterval.DAILY,
    val lastBackupIso: String? = null,
    val hasNostrKey: Boolean = false,
    /** Mirrors [com.cruxcoach.android.data.UserPreferences.keyBackedUp]. The
     *  Settings cloud-backup section surfaces a persistent
     *  [com.cruxcoach.android.ui.common.BackupKeyWarningCard] until the user
     *  acknowledges they have stored their key somewhere outside this device. */
    val keyBackedUp: Boolean = false,
    /** [SignerMode.LOCAL] = nsec lives in this device's keystore →
     *  recommend a password manager; [SignerMode.AMBER] = key delegated
     *  to Amber → recommend Amber's own backup. */
    val signerMode: SignerMode = SignerMode.LOCAL,
    val isRunningOneShot: Boolean = false,
    val isCheckingForBackup: Boolean = false,
    /** True while the board catalogue is still importing on a fresh install
     *  (or during any active board-sync). The restore button is disabled
     *  meanwhile: restoring against a half-imported board DB raced the
     *  bulk-import for the SQLite writer lock and silently rolled the whole
     *  restore back to zero. */
    val boardImportInProgress: Boolean = false,
    val pendingRestore: BackupInfo? = null,
    val snackbar: Snackbar? = null,
    val showDeleteRemoteConfirm: Boolean = false,
    val isDeletingRemote: Boolean = false,
) {
    sealed interface Snackbar {
        /** checkForBackup returned NotFound — relays answered or timed out with no matching events. */
        data object NoBackupFound : Snackbar
        /** checkForBackup returned DecryptFailed — pointer was there, key didn't fit. */
        data object CheckDecryptFailed : Snackbar
        /** checkForBackup threw / returned Fetch — surface the detail. */
        data class CheckError(val detail: String) : Snackbar
        /**
         * checkForBackup returned BlobUnreachable — pointer + key on
         * relays, but every Blossom server in the pointer's list said
         * "no" on a HEAD probe. Typical cause: a prior delete-remote
         * opt-out cleaned Blossom while the pointer event survived on
         * a slow relay. UI surfaces a specific message; user can retry
         * later in case the HEAD failure was a transient network blip.
         */
        data object BlobUnreachable : Snackbar
        data object RestoreFailed : Snackbar
        /** Restore completed — carries the imported counts so a successful
         *  restore is never silent (and a 0/0 result is visible too). */
        data class RestoreSucceeded(val ascents: Int, val lists: Int) : Snackbar
        data object BackupSucceeded : Snackbar
        /**
         * [reason] is the structured BackupException reason — the UI
         * maps it to a localized `stringResource(...)`. Null when the
         * underlying throwable is not a [BackupException] (e.g. NPE);
         * the UI falls back to the generic `settings_backup_failed`
         * string in that case so we never surface raw English from
         * a third-party library.
         */
        data class BackupFailed(val reason: BackupErrorReason?) : Snackbar
        /**
         * Active opt-out completed. Carries per-leg stats so the UI
         * can show the user exactly how many relays / Blossom servers
         * ack'd the deletion — a single "done" was misleading when
         * 0/N accepted. [notes] lists any non-fatal problems as
         * structured enum cases (the UI maps each to a localized
         * `stringResource(...)`); empty list + matching attempted /
         * accepted counts is full success.
         */
        data class RemoteBackupsDeleted(
            val relaysAttempted: Int,
            val relaysAccepted: Int,
            val blossomAttempted: Int,
            val blossomAccepted: Int,
            val notes: List<DeleteRemoteNote>,
        ) : Snackbar
    }
}

/**
 * Isolated ViewModel for the Backup & Sync settings section. Keeps the
 * existing (already-overloaded) [SettingsViewModel] out of FEAT-002's way.
 */
@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferences: BackupPreferences,
    private val backupRepository: BackupRepository,
    private val keyStore: NostrKeyStore,
    private val userPreferences: UserPreferences,
    private val signer: NostrSigner,
    private val boardSyncManager: BoardSyncManager,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupSettingsState())
    val state: StateFlow<BackupSettingsState> = _state.asStateFlow()

    init {
        // Observe DataStore reactively. Every action that mutates
        // identity state — confirmDeleteRemoteBackups locally, the
        // KeyImport/KeyManagement VMs on identity switch, or a
        // BackupSyncWorker completing in the background — writes to
        // DataStore. Without a reactive collector those writes only
        // surfaced the next time this VM was re-created, so the UI
        // could keep rendering a stale "Letzte Sicherung" line long
        // after the underlying state was wiped. Collecting the Flows
        // here means the VM state stays in lockstep with DataStore
        // no matter who wrote it.
        viewModelScope.launch {
            preferences.backupFeatureEnabled.collect { featureEnabled ->
                _state.update { it.copy(featureEnabled = featureEnabled) }
            }
        }
        viewModelScope.launch {
            preferences.backupEnabled.collect { enabled ->
                _state.update { it.copy(backupEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.lastBackupSync.collect { epoch ->
                _state.update { it.copy(lastBackupIso = epoch?.toLocalizedDateTime()) }
            }
        }
        // FEAT-021: surface the persisted backup interval so the UI shows
        // the user's previously-picked cadence instead of the in-memory
        // DAILY default that pre-fix made every cold start look like the
        // user had picked Daily.
        viewModelScope.launch {
            preferences.backupInterval.collect { interval ->
                _state.update { it.copy(interval = interval) }
            }
        }
        // hasNostrKey is a point-in-time check — the keystore doesn't
        // expose a Flow, but it only changes on identity switch which
        // forces an app restart (see A2 flow), so a single read on
        // init is correct.
        _state.update {
            it.copy(
                hasNostrKey = keyStore.hasKey(),
                signerMode = signer.getStoredSignerMode(),
            )
        }
        // keyBackedUp can be flipped by either KeyManagementScreen's
        // existing "Mark as backed up" flow or the Cloud-Backup section's
        // own warning-card "Schlüssel ist gesichert" button — both write
        // the same per-identity flag, so collect it reactively.
        viewModelScope.launch {
            userPreferences.keyBackedUp.collect { acked ->
                _state.update { it.copy(keyBackedUp = acked) }
            }
        }
        // Gate the restore button on board-catalogue readiness. Restoring
        // while the board DB is still bulk-importing (the default fresh-
        // install state) raced the importer for the SQLite writer lock and
        // silently rolled the restore back to zero. `isSyncing` covers an
        // active sync; `!alreadyImported` covers the pre-first-import window.
        viewModelScope.launch {
            boardSyncManager.state.collect { sync ->
                _state.update {
                    it.copy(boardImportInProgress = sync.isSyncing || !sync.alreadyImported)
                }
            }
        }
    }


    fun setBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setBackupEnabled(enabled)
            BackupSyncWorker.schedule(appContext, enabled, _state.value.interval)
            _state.update { it.copy(backupEnabled = enabled) }
        }
    }

    fun setInterval(interval: SyncInterval) {
        viewModelScope.launch {
            // FEAT-021: persist BEFORE updating state — the persisted
            // value is what CruxCoachApp.onCreate reads on the next
            // cold start to reschedule the periodic worker, so any
            // in-memory state without a corresponding DataStore write
            // is silently lost the moment the OS reclaims the process.
            preferences.setBackupInterval(interval)
            _state.update { it.copy(interval = interval) }
            if (_state.value.backupEnabled) {
                BackupSyncWorker.schedule(appContext, enabled = true, interval = interval)
            }
        }
    }

    /**
     * Run an on-demand backup inline on [viewModelScope] instead of handing
     * it to WorkManager. This lives on the Settings screen, so a real
     * foreground Activity is always available — the Amber NIP-55 approval
     * dialog can attach to it, which is the only way users who haven't
     * granted "always approve" in Amber can actually complete a backup. A
     * WorkManager worker has no Activity context and throws
     * "No activity to launch from." the moment Amber needs confirmation.
     *
     * Trade-off: if the user navigates away mid-upload, the coroutine
     * gets cancelled and the blob upload is abandoned. That's acceptable
     * — Amber also can't pop its dialog once the Activity is gone — and
     * the next periodic run (or another "Jetzt sichern") will retry from
     * scratch.
     */
    fun runBackupNow() {
        viewModelScope.launch {
            _state.update { it.copy(isRunningOneShot = true) }
            val outcome = runCatching { backupRepository.performFullBackup(trigger = "manual") }
            val snackbar = if (outcome.isSuccess) {
                BackupSettingsState.Snackbar.BackupSucceeded
            } else {
                // BackupException carries a structured BackupErrorReason
                // that the UI maps to a localized string. Anything else
                // (NPE, IOException, etc.) falls through with reason=null
                // → UI shows the generic "Backup failed" string instead
                // of a raw English exception message.
                val ex = outcome.exceptionOrNull()
                BackupSettingsState.Snackbar.BackupFailed(
                    reason = (ex as? BackupException)?.reason,
                )
            }
            val latestLastSync = preferences.lastBackupSync.first()
            _state.update {
                it.copy(
                    isRunningOneShot = false,
                    lastBackupIso = latestLastSync?.toLocalizedDateTime(),
                    snackbar = snackbar,
                )
            }
        }
    }

    fun triggerManualRestore() {
        val existing = _state.value
        if (existing.isCheckingForBackup) return
        viewModelScope.launch {
            _state.update { it.copy(isCheckingForBackup = true) }
            val outcome = runCatching { backupRepository.checkForBackup() }
                .getOrElse { com.cruxcoach.android.nostr.backup.CheckOutcome.Fetch(it.message ?: "error") }
            val info = (outcome as? com.cruxcoach.android.nostr.backup.CheckOutcome.Found)?.info
            _state.update {
                it.copy(
                    isCheckingForBackup = false,
                    pendingRestore = info,
                    snackbar = when (outcome) {
                        is com.cruxcoach.android.nostr.backup.CheckOutcome.Found -> it.snackbar
                        com.cruxcoach.android.nostr.backup.CheckOutcome.NotFound ->
                            BackupSettingsState.Snackbar.NoBackupFound
                        com.cruxcoach.android.nostr.backup.CheckOutcome.DecryptFailed ->
                            BackupSettingsState.Snackbar.CheckDecryptFailed
                        is com.cruxcoach.android.nostr.backup.CheckOutcome.Fetch ->
                            BackupSettingsState.Snackbar.CheckError(outcome.message)
                        is com.cruxcoach.android.nostr.backup.CheckOutcome.BlobUnreachable ->
                            BackupSettingsState.Snackbar.BlobUnreachable
                    },
                )
            }
        }
    }

    fun confirmRestore() {
        val info = _state.value.pendingRestore ?: return
        viewModelScope.launch {
            _state.update { it.copy(pendingRestore = null) }
            val outcome = runCatching { backupRepository.restore(info) }
            val result = outcome.getOrNull()
            if (result != null) {
                preferences.setBackupEnabled(true)
                BackupSyncWorker.schedule(appContext, enabled = true, interval = _state.value.interval)
                val lastSyncEpoch = preferences.lastBackupSync.first()
                _state.update {
                    it.copy(
                        backupEnabled = true,
                        lastBackupIso = lastSyncEpoch?.toLocalizedDateTime(),
                        snackbar = BackupSettingsState.Snackbar.RestoreSucceeded(
                            ascents = result.boardAscents,
                            lists = result.climbLists,
                        ),
                    )
                }
            } else {
                _state.update {
                    it.copy(snackbar = BackupSettingsState.Snackbar.RestoreFailed)
                }
            }
        }
    }

    fun dismissRestoreDialog() {
        _state.update { it.copy(pendingRestore = null) }
    }

    fun consumeSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    // ─── Active opt-out — FEAT-002 §20.2 ────────────────────────────────

    fun requestDeleteRemoteBackups() {
        _state.update { it.copy(showDeleteRemoteConfirm = true) }
    }

    fun dismissDeleteRemoteConfirm() {
        _state.update { it.copy(showDeleteRemoteConfirm = false) }
    }

    fun confirmDeleteRemoteBackups() {
        viewModelScope.launch {
            _state.update { it.copy(showDeleteRemoteConfirm = false, isDeletingRemote = true) }
            // Cancel the periodic worker BEFORE deleting. The worker
            // re-publishes pointer + key events on every tick; cancelling
            // it after deleteRemoteBackups returned could let an in-flight
            // tick resurrect the events seconds later. Combined with
            // BackupRepository.pipelineMutex, an already-running worker
            // run finishes (under the lock) before deleteRemoteBackups
            // can acquire the same lock — so the deletion always observes
            // a quiescent pipeline.
            BackupSyncWorker.schedule(
                appContext,
                enabled = false,
                interval = _state.value.interval,
            )
            val outcome = runCatching { backupRepository.deleteRemoteBackups() }
                .getOrElse { throwable ->
                    com.cruxcoach.android.nostr.backup.DeleteRemoteOutcome(
                        relaysAttempted = 0,
                        relaysAccepted = 0,
                        blossomAttempted = 0,
                        blossomAccepted = 0,
                        notes = listOf(
                            DeleteRemoteNote.UnexpectedError(type = throwable.javaClass.simpleName),
                        ),
                    )
                }
            _state.update {
                it.copy(
                    isDeletingRemote = false,
                    backupEnabled = false,
                    lastBackupIso = null,
                    snackbar = BackupSettingsState.Snackbar.RemoteBackupsDeleted(
                        relaysAttempted = outcome.relaysAttempted,
                        relaysAccepted = outcome.relaysAccepted,
                        blossomAttempted = outcome.blossomAttempted,
                        blossomAccepted = outcome.blossomAccepted,
                        notes = outcome.notes,
                    ),
                )
            }
        }
    }

    /**
     * Format an epoch-seconds timestamp as a short, locale-aware
     * date+time. The previous name `toIso8601` was a misnomer — it
     * always emitted the German `dd.MM.yyyy, HH:mm` shape regardless
     * of locale and is not the ISO 8601 wire format. The renamed
     * version uses the system locale's SHORT style so a German user
     * still sees `25.04.26, 14:32` while an English user sees the
     * locale-appropriate equivalent (`4/25/26, 2:32 PM`).
     */
    private fun Long.toLocalizedDateTime(): String {
        val dt = Instant.ofEpochSecond(this).atZone(ZoneId.systemDefault())
        return dt.format(
            DateTimeFormatter
                .ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                .withLocale(Locale.getDefault()),
        )
    }
}
