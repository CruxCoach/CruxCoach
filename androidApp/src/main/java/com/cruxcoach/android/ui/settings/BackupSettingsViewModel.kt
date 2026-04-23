package com.cruxcoach.android.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.SyncInterval
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.backup.BackupException
import com.cruxcoach.android.nostr.backup.BackupInfo
import com.cruxcoach.android.nostr.backup.BackupPreferences
import com.cruxcoach.android.nostr.backup.BackupRepository
import com.cruxcoach.android.nostr.backup.BackupSyncWorker
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
    val isRunningOneShot: Boolean = false,
    val isCheckingForBackup: Boolean = false,
    val pendingRestore: BackupInfo? = null,
    val snackbar: Snackbar? = null,
    val showDeleteRemoteConfirm: Boolean = false,
    val isDeletingRemote: Boolean = false,
) {
    sealed interface Snackbar {
        data object NoBackupFound : Snackbar
        data object RestoreFailed : Snackbar
        data object BackupSucceeded : Snackbar
        data object BackupFailed : Snackbar
        data object RemoteBackupsDeleted : Snackbar
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
) : ViewModel() {

    private val _state = MutableStateFlow(BackupSettingsState())
    val state: StateFlow<BackupSettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val featureEnabled = preferences.isBackupFeatureEnabled()
            val backupEnabled = preferences.isBackupEnabled()
            val lastSyncEpoch = preferences.lastBackupSync.first()
            _state.update {
                it.copy(
                    featureEnabled = featureEnabled,
                    backupEnabled = backupEnabled,
                    lastBackupIso = lastSyncEpoch?.toIso8601(),
                    hasNostrKey = keyStore.hasKey(),
                )
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
            _state.update { it.copy(interval = interval) }
            if (_state.value.backupEnabled) {
                BackupSyncWorker.schedule(appContext, enabled = true, interval = interval)
            }
        }
    }

    fun runBackupNow() {
        viewModelScope.launch {
            _state.update { it.copy(isRunningOneShot = true) }
            BackupSyncWorker.runOnce(appContext)
            // Observe WorkManager's unique one-shot work until it reaches a
            // terminal state, then reflect the outcome in the UI. Without
            // this the user only ever saw "Backup eingeplant" (queued) and
            // never found out whether the backup actually completed.
            val wm = androidx.work.WorkManager.getInstance(appContext)
            val terminal = wm.getWorkInfosForUniqueWorkFlow(BackupSyncWorker.WORK_NAME_ONESHOT)
                .mapNotNull { it.lastOrNull() }
                .first { it.state.isFinished }
            val snackbar = when (terminal.state) {
                androidx.work.WorkInfo.State.SUCCEEDED ->
                    BackupSettingsState.Snackbar.BackupSucceeded
                else -> BackupSettingsState.Snackbar.BackupFailed
            }
            val latestLastSync = preferences.lastBackupSync.first()
            _state.update {
                it.copy(
                    isRunningOneShot = false,
                    lastBackupIso = latestLastSync?.toIso8601(),
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
            val info = runCatching { backupRepository.checkForBackup() }.getOrNull()
            _state.update {
                it.copy(
                    isCheckingForBackup = false,
                    pendingRestore = info,
                    snackbar = if (info == null) BackupSettingsState.Snackbar.NoBackupFound else it.snackbar,
                )
            }
        }
    }

    fun confirmRestore() {
        val info = _state.value.pendingRestore ?: return
        viewModelScope.launch {
            _state.update { it.copy(pendingRestore = null) }
            val outcome = runCatching { backupRepository.restore(info) }
            if (outcome.isSuccess) {
                preferences.setBackupEnabled(true)
                BackupSyncWorker.schedule(appContext, enabled = true, interval = _state.value.interval)
                val lastSyncEpoch = preferences.lastBackupSync.first()
                _state.update {
                    it.copy(
                        backupEnabled = true,
                        lastBackupIso = lastSyncEpoch?.toIso8601(),
                    )
                }
            } else {
                val error = outcome.exceptionOrNull()
                _state.update {
                    it.copy(
                        snackbar = if (error is BackupException) {
                            BackupSettingsState.Snackbar.RestoreFailed
                        } else {
                            BackupSettingsState.Snackbar.RestoreFailed
                        },
                    )
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
            runCatching { backupRepository.deleteRemoteBackups() }
                // deleteRemoteBackups is best-effort and does not throw on
                // partial failures; we surface the same snackbar either way.
            BackupSyncWorker.schedule(
                appContext,
                enabled = false,
                interval = _state.value.interval,
            )
            _state.update {
                it.copy(
                    isDeletingRemote = false,
                    backupEnabled = false,
                    lastBackupIso = null,
                    snackbar = BackupSettingsState.Snackbar.RemoteBackupsDeleted,
                )
            }
        }
    }

    private fun Long.toIso8601(): String {
        val dt = Instant.ofEpochSecond(this).atZone(ZoneId.systemDefault())
        return dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.getDefault()))
    }
}
