package com.cruxcoach.android.ui.onboarding

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.SyncInterval
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.kilter.KilterApiClient
import com.cruxcoach.android.data.kilter.KilterAuthResult
import com.cruxcoach.android.data.kilter.KilterImportPreview
import com.cruxcoach.android.data.kilter.KilterSyncEngine
import com.cruxcoach.android.data.kilter.KilterTokenStore
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.backup.BackupInfo
import com.cruxcoach.android.nostr.backup.BackupPreferences
import com.cruxcoach.android.nostr.backup.BackupRepository
import com.cruxcoach.android.nostr.backup.BackupSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep {
    WELCOME, PRIVACY, BOARD_SETUP, NOSTR_KEY, NOSTR_BACKUP
}

data class OnboardingState(
    val currentStep: OnboardingStep = OnboardingStep.WELCOME,

    // Privacy preferences
    val bleSharing: Boolean = true,
    val communityFeatures: Boolean = true,

    // Kilter login (inline in board setup step)
    val kilterEmail: String = "",
    val kilterPassword: String = "",
    val kilterLoginError: String? = null,
    val isKilterLoggingIn: Boolean = false,
    val kilterConnected: Boolean = false,
    val kilterUsername: String = "",
    val kilterImportPreview: KilterImportPreview? = null,
    val isKilterImporting: Boolean = false,
    val kilterImportResult: String? = null,

    val boardDataImported: Boolean = false,

    // FEAT-002: Nostr backup onboarding
    val hasNostrKey: Boolean = false,
    val isCheckingForBackup: Boolean = false,
    val backupCheckAttempted: Boolean = false,
    val pendingRestore: BackupInfo? = null,
    val restoreInProgress: Boolean = false,
    val restoreFailed: Boolean = false,
    val backupOptIn: Boolean = false,

    val isSaving: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val userPreferences: UserPreferences,
    private val kilterApiClient: KilterApiClient,
    private val kilterTokenStore: KilterTokenStore,
    private val kilterSyncEngine: KilterSyncEngine,
    private val boardSyncManager: BoardSyncManager,
    private val keyStore: NostrKeyStore,
    private val backupPreferences: BackupPreferences,
    private val backupRepository: BackupRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "OnboardingVM"
    }

    private val _state = MutableStateFlow(
        OnboardingState(hasNostrKey = keyStore.hasKey()),
    )
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            boardSyncManager.state.collect { syncState ->
                _state.update { it.copy(boardDataImported = syncState.alreadyImported) }
            }
        }
    }

    fun nextStep() {
        val next = when (_state.value.currentStep) {
            OnboardingStep.WELCOME -> OnboardingStep.PRIVACY
            OnboardingStep.PRIVACY -> OnboardingStep.BOARD_SETUP
            OnboardingStep.BOARD_SETUP -> OnboardingStep.NOSTR_KEY
            OnboardingStep.NOSTR_KEY -> OnboardingStep.NOSTR_BACKUP
            OnboardingStep.NOSTR_BACKUP -> return
        }
        _state.update { it.copy(currentStep = next) }
        if (next == OnboardingStep.NOSTR_KEY) onNostrKeyStepEntered()
    }

    fun previousStep() {
        val prev = when (_state.value.currentStep) {
            OnboardingStep.WELCOME -> return
            OnboardingStep.PRIVACY -> OnboardingStep.WELCOME
            OnboardingStep.BOARD_SETUP -> OnboardingStep.PRIVACY
            OnboardingStep.NOSTR_KEY -> OnboardingStep.BOARD_SETUP
            OnboardingStep.NOSTR_BACKUP -> OnboardingStep.NOSTR_KEY
        }
        _state.update { it.copy(currentStep = prev) }
    }

    // Privacy toggles
    fun updateBleSharing(enabled: Boolean) {
        _state.update { it.copy(bleSharing = enabled) }
    }

    fun updateCommunityFeatures(enabled: Boolean) {
        _state.update { it.copy(communityFeatures = enabled) }
    }

    // Kilter login
    fun updateKilterEmail(email: String) {
        _state.update { it.copy(kilterEmail = email) }
    }

    fun updateKilterPassword(password: String) {
        _state.update { it.copy(kilterPassword = password) }
    }

    fun kilterLogin() {
        val s = _state.value
        if (s.kilterEmail.isBlank() || s.kilterPassword.isBlank()) return
        _state.update { it.copy(isKilterLoggingIn = true, kilterLoginError = null) }

        viewModelScope.launch {
            val result = kilterApiClient.authenticate(s.kilterEmail, s.kilterPassword)
            when (result) {
                is KilterAuthResult.Success -> {
                    kilterTokenStore.storeTokens(
                        accessToken = result.accessToken,
                        refreshToken = result.refreshToken,
                        expiresInSeconds = result.expiresIn,
                        userUuid = result.userUuid,
                        username = result.username
                    )
                    // Fetch import preview
                    val preview = kilterSyncEngine.previewImport()
                    _state.update {
                        it.copy(
                            isKilterLoggingIn = false,
                            kilterConnected = true,
                            kilterUsername = result.username,
                            kilterImportPreview = preview.getOrNull()
                        )
                    }
                }
                is KilterAuthResult.Error -> {
                    _state.update {
                        it.copy(isKilterLoggingIn = false, kilterLoginError = result.message)
                    }
                }
            }
        }
    }

    fun kilterImportOneTime() {
        _state.update { it.copy(isKilterImporting = true) }
        viewModelScope.launch {
            val result = kilterSyncEngine.importLogs(oneTimeOnly = true)
            _state.update {
                it.copy(
                    isKilterImporting = false,
                    kilterImportResult = result.fold(
                        onSuccess = { count -> "$count" },
                        onFailure = { e -> e.message }
                    ),
                    kilterConnected = false // credentials cleared
                )
            }
        }
    }

    fun kilterImportPersistent() {
        _state.update { it.copy(isKilterImporting = true) }
        viewModelScope.launch {
            val result = kilterSyncEngine.importLogs(oneTimeOnly = false)
            _state.update {
                it.copy(
                    isKilterImporting = false,
                    kilterImportResult = result.fold(
                        onSuccess = { count -> "$count" },
                        onFailure = { e -> e.message }
                    )
                )
            }
        }
    }

    // ─── FEAT-002 onboarding actions ───────────────────────────────────────

    /**
     * On entering the NOSTR_KEY step: if the user already has a Nostr key
     * (typically an app-update scenario — EncryptedSharedPreferences survives
     * updates but not uninstalls), we run [BackupRepository.checkForBackup]
     * silently. A hit surfaces the restore dialog; a miss or absent key is
     * silent — the spec (§8.2) is explicit that negative outcomes don't get
     * UI, only the user-initiated Settings button does.
     */
    private fun onNostrKeyStepEntered() {
        val s = _state.value
        if (!keyStore.hasKey() || s.backupCheckAttempted) return
        _state.update { it.copy(isCheckingForBackup = true) }
        viewModelScope.launch {
            val info = runCatching { backupRepository.checkForBackup() }
                .onFailure { Log.w(TAG, "checkForBackup during onboarding failed", it) }
                .getOrNull()
            _state.update {
                it.copy(
                    isCheckingForBackup = false,
                    pendingRestore = info,
                    backupCheckAttempted = true,
                )
            }
        }
    }

    fun confirmOnboardingRestore() {
        val info = _state.value.pendingRestore ?: return
        _state.update { it.copy(restoreInProgress = true, restoreFailed = false) }
        viewModelScope.launch {
            val result = runCatching { backupRepository.restore(info) }
            if (result.isSuccess) {
                // A successful restore implies the user wants backup enabled.
                backupPreferences.setBackupEnabled(true)
                _state.update {
                    it.copy(
                        restoreInProgress = false,
                        pendingRestore = null,
                        backupOptIn = true,
                    )
                }
            } else {
                Log.w(TAG, "onboarding restore failed", result.exceptionOrNull())
                _state.update {
                    it.copy(
                        restoreInProgress = false,
                        pendingRestore = null,
                        restoreFailed = true,
                    )
                }
            }
        }
    }

    fun dismissOnboardingRestore() {
        _state.update { it.copy(pendingRestore = null) }
    }

    fun consumeRestoreFailure() {
        _state.update { it.copy(restoreFailed = false) }
    }

    fun setBackupOptIn(enabled: Boolean) {
        _state.update { it.copy(backupOptIn = enabled) }
    }

    // ────────────────────────────────────────────────────────────────────────

    fun completeOnboarding(onComplete: () -> Unit) {
        val s = _state.value
        _state.update { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            try {
                userPreferences.setNearbyClimbSharing(s.bleSharing)
                userPreferences.setAllowRemoteDisconnect(s.bleSharing)
                userPreferences.setCrashReportOptIn(s.communityFeatures)
                userPreferences.setAnnouncementsEnabled(s.communityFeatures)
                // FEAT-002: persist backup opt-in and kick off the scheduler.
                // Defaults to daily when the user opted in; the user can change
                // the interval in Settings.
                backupPreferences.setBackupEnabled(s.backupOptIn)
                backupPreferences.setBackupOnboardingSeen(true)
                BackupSyncWorker.schedule(
                    appContext,
                    enabled = s.backupOptIn && backupPreferences.isBackupFeatureEnabled(),
                    interval = SyncInterval.DAILY,
                )
                userPreferences.setOnboardingCompleted(true)
                _state.update { it.copy(isSaving = false) }
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "completeOnboarding failed", e)
                _state.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
