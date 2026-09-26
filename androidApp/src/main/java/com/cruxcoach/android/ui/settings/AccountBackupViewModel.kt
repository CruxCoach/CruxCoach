package com.cruxcoach.android.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.SyncInterval
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.backup.BackupPreferences
import com.cruxcoach.android.nostr.backup.BackupRepository
import com.cruxcoach.android.nostr.backup.BackupSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

internal enum class AccountBackupStep { CLOSED, LOADING, COPY, STORE, RUNNING, SUCCESS, ERROR }
internal data class AccountBackupState(
    val step: AccountBackupStep = AccountBackupStep.CLOSED,
    val wantsBackup: Boolean = false,
    val alreadyEnabled: Boolean = false,
    val authenticating: Boolean = false,
    val copiedInFlow: Boolean = false,
)

/** Holds only flow choices, never the private key. Consent is committed after key storage. */
@HiltViewModel
class AccountBackupViewModel @Inject constructor(
    private val preferences: BackupPreferences,
    private val userPreferences: UserPreferences,
    private val repository: BackupRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AccountBackupState())
    internal val state = mutableState.asStateFlow()

    internal fun open(alreadyStored: Boolean = false) {
        if (state.value.step != AccountBackupStep.CLOSED) return
        mutableState.value = AccountBackupState(step = AccountBackupStep.LOADING)
        viewModelScope.launch {
            val enabled = preferences.backupEnabled.first()
            if (state.value.step == AccountBackupStep.LOADING) mutableState.value = AccountBackupState(
                step = if (alreadyStored) AccountBackupStep.STORE else AccountBackupStep.COPY,
                wantsBackup = enabled, alreadyEnabled = enabled,
            )
        }
    }
    internal fun chooseBackup(enabled: Boolean) {
        if (!state.value.alreadyEnabled && state.value.step in listOf(AccountBackupStep.COPY, AccountBackupStep.STORE))
            mutableState.update { it.copy(wantsBackup = enabled) }
    }
    internal fun beginAuthentication(): Boolean {
        if (state.value.step !in listOf(AccountBackupStep.COPY, AccountBackupStep.STORE) || state.value.authenticating) return false
        mutableState.update { it.copy(authenticating = true) }
        return true
    }
    internal fun authenticationFinished(copied: Boolean) {
        if (state.value.step !in listOf(AccountBackupStep.COPY, AccountBackupStep.STORE)) return
        mutableState.update { it.copy(authenticating = false, copiedInFlow = it.copiedInFlow || copied, step = if (copied) AccountBackupStep.STORE else it.step) }
    }
    internal fun close() {
        if (state.value.step != AccountBackupStep.RUNNING) mutableState.value = AccountBackupState()
    }
    internal fun confirmStored() {
        val choice = state.value
        if (choice.authenticating || choice.step !in listOf(AccountBackupStep.STORE, AccountBackupStep.ERROR)) return
        mutableState.update { it.copy(step = AccountBackupStep.RUNNING) }
        viewModelScope.launch {
            try {
                userPreferences.setKeyBackedUp(true)
                if (choice.wantsBackup) {
                    check(preferences.isBackupFeatureEnabled())
                    repository.performFullBackup(trigger = "key-storage")
                    // Preserve existing cadence. New automatic uploads start only after
                    // the first confirmed successful backup, never while saving the nsec.
                    if (!choice.alreadyEnabled) {
                        preferences.setBackupInterval(SyncInterval.DAILY)
                        preferences.setBackupEnabled(true)
                        BackupSyncWorker.schedule(context, enabled = true, interval = SyncInterval.DAILY)
                    }
                }
                mutableState.update { it.copy(step = AccountBackupStep.SUCCESS) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(step = AccountBackupStep.ERROR) }
            }
        }
    }
}
