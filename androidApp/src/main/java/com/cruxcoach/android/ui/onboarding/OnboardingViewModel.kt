package com.cruxcoach.android.ui.onboarding

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.data.SyncInterval
import com.cruxcoach.android.data.UserPreferences
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * New 3-step onboarding:
 *  - BOARD_SETUP  — welcome header + the one must-have action (Board-DB download).
 *  - PRIVACY      — privacy + backup + (inline) backup-restore choice in a single screen.
 *  - KILTER       — optional Kilter-logbook import, prominent skip.
 */
enum class OnboardingStep {
    BOARD_SETUP, PRIVACY, KILTER
}

/**
 * User choice under the Backup toggle in the Privacy step.
 *
 *  - [FRESH]: a new Nostr key is generated lazily; today's local data
 *    becomes the first snapshot.
 *  - [RESTORE]: the user brings a key from another device. Selecting this
 *    reveals a "Schlüssel importieren" button (no backup search runs yet —
 *    onboarding is shown only to new users, and the only key on the device
 *    at this point is the auto-generated one, for which no backup can
 *    exist). The button navigates to [com.cruxcoach.android.ui.settings.KeyImportScreen]
 *    after persisting a restore-intent marker. On the next cold start
 *    (after the KeyImport-driven app restart) onboarding lands back on
 *    [OnboardingStep.PRIVACY] and only *then* triggers
 *    [BackupRepository.checkForBackup] against the imported key.
 */
enum class BackupChoice { FRESH, RESTORE }

data class OnboardingState(
    val currentStep: OnboardingStep = OnboardingStep.BOARD_SETUP,

    // Privacy preferences
    val bleSharing: Boolean = true,
    val communityFeatures: Boolean = true,

    // Kilter login (inline in kilter step)
    val kilterEmail: String = "",
    val kilterPassword: String = "",
    val kilterLoginError: String? = null,
    val isKilterLoggingIn: Boolean = false,
    val kilterConnected: Boolean = false,
    val kilterUsername: String = "",
    val kilterImportPreview: KilterImportPreview? = null,
    val isKilterImporting: Boolean = false,
    val kilterImportResult: String? = null,

    // FEAT-002: encrypted cloud backup onboarding (Nostr + Blossom internally)
    val hasNostrKey: Boolean = false,
    val backupOptIn: Boolean = false,
    val backupChoice: BackupChoice = BackupChoice.FRESH,
    /** Backup cadence the user picked while still in onboarding. Default
     *  MANUAL — keeps the toggle-on UX free of background-job surprises;
     *  user can pick DAILY/WEEKLY here so they don't have to find Settings
     *  later. Used by [completeOnboarding] when scheduling the worker. */
    val backupFrequency: SyncInterval = SyncInterval.MANUAL,
    val isCheckingForBackup: Boolean = false,
    val backupCheckAttempted: Boolean = false,
    val pendingRestore: BackupInfo? = null,
    val restoreInProgress: Boolean = false,
    val restoreFailed: Boolean = false,
    val restoreSucceeded: Boolean = false,
    val noBackupFoundForKey: Boolean = false,
    val showRestartConfirm: Boolean = false,

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
        // Resume the restore flow if the user came back from KeyImportScreen
        // via app-restart. The marker is only set right before that navigation
        // and is cleared after the restore attempt resolves.
        viewModelScope.launch {
            if (backupPreferences.isBackupRestoreIntent()) {
                _state.update {
                    it.copy(
                        currentStep = OnboardingStep.PRIVACY,
                        backupOptIn = true,
                        backupChoice = BackupChoice.RESTORE,
                        hasNostrKey = keyStore.hasKey(),
                    )
                }
                triggerBackupCheckIfNeeded()
            }
        }
    }

    fun nextStep() {
        val next = when (_state.value.currentStep) {
            OnboardingStep.BOARD_SETUP -> OnboardingStep.PRIVACY
            OnboardingStep.PRIVACY -> OnboardingStep.KILTER
            OnboardingStep.KILTER -> return
        }
        _state.update { it.copy(currentStep = next) }
    }

    fun previousStep() {
        val prev = when (_state.value.currentStep) {
            OnboardingStep.BOARD_SETUP -> return
            OnboardingStep.PRIVACY -> OnboardingStep.BOARD_SETUP
            OnboardingStep.KILTER -> OnboardingStep.PRIVACY
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

    // ─── FEAT-002 backup ────────────────────────────────────────────────

    fun setBackupOptIn(enabled: Boolean) {
        _state.update { it.copy(backupOptIn = enabled) }
    }

    fun setBackupFrequency(interval: SyncInterval) {
        _state.update { it.copy(backupFrequency = interval) }
    }

    fun setBackupChoice(choice: BackupChoice) {
        // noBackupFoundForKey is a pure derivation from the existing state —
        // it's true iff we already ran a check against the current key and
        // it came back empty. The UI surfaces it as *context* above the
        // import button (not as a dead-end branch), so seeing it does not
        // block the user from importing a different key.
        _state.update { state ->
            val derivedNoBackup = choice == BackupChoice.RESTORE &&
                state.hasNostrKey &&
                state.backupCheckAttempted &&
                state.pendingRestore == null &&
                !state.restoreSucceeded
            state.copy(
                backupChoice = choice,
                noBackupFoundForKey = derivedNoBackup,
            )
        }
    }

    /** Shows the "App will restart" confirm dialog before navigating to KeyImport. */
    fun requestKeyImport() {
        _state.update { it.copy(showRestartConfirm = true) }
    }

    fun dismissRestartConfirm() {
        _state.update { it.copy(showRestartConfirm = false) }
    }

    /**
     * Persist the restore-intent marker and tell the caller it's safe to
     * navigate to the KeyImport route. Navigation itself is a UI concern.
     */
    fun confirmKeyImportNavigation(navigate: () -> Unit) {
        viewModelScope.launch {
            backupPreferences.setBackupRestoreIntent(true)
            _state.update { it.copy(showRestartConfirm = false) }
            navigate()
        }
    }

    /**
     * Triggers [BackupRepository.checkForBackup] if we have a key and
     * haven't tried this session. A hit populates [OnboardingState.pendingRestore]
     * which the UI turns into the restore dialog.
     *
     * Only called from [init] when the restore-intent marker is set, i.e.
     * after the user came back from [com.cruxcoach.android.ui.settings.KeyImportScreen].
     * Running this against a freshly-generated key (which is what every new
     * user has at the start of onboarding) is pointless — there can never be
     * a backup tied to a key that was never used elsewhere.
     */
    private fun triggerBackupCheckIfNeeded() {
        val s = _state.value
        if (!keyStore.hasKey()) {
            _state.update { it.copy(hasNostrKey = false) }
            return
        }
        if (s.backupCheckAttempted || s.isCheckingForBackup) return
        _state.update { it.copy(hasNostrKey = true, isCheckingForBackup = true) }
        viewModelScope.launch {
            val outcome = runCatching { backupRepository.checkForBackup() }
                .onFailure { Log.w(TAG, "checkForBackup during onboarding failed", it) }
                .getOrElse { com.cruxcoach.android.nostr.backup.CheckOutcome.Fetch(it.message ?: "error") }
            val info = (outcome as? com.cruxcoach.android.nostr.backup.CheckOutcome.Found)?.info
            _state.update {
                it.copy(
                    isCheckingForBackup = false,
                    backupCheckAttempted = true,
                    pendingRestore = info,
                    // noBackupFoundForKey flips on for *any* terminal non-hit
                    // (NotFound, DecryptFailed, Fetch error) while in RESTORE
                    // mode — it's the "you can continue, a fresh backup will
                    // be created" hint, and none of the non-hit cases should
                    // leave the user stuck on a disabled Next button.
                    noBackupFoundForKey = info == null && it.backupChoice == BackupChoice.RESTORE,
                )
            }
            // If we arrived here via restart-resume and find nothing, clear
            // the marker so we don't loop forever.
            if (info == null && backupPreferences.isBackupRestoreIntent()) {
                backupPreferences.setBackupRestoreIntent(false)
            }
        }
    }

    fun confirmOnboardingRestore() {
        val info = _state.value.pendingRestore ?: return
        _state.update { it.copy(restoreInProgress = true, restoreFailed = false) }
        viewModelScope.launch {
            val result = runCatching { backupRepository.restore(info) }
            if (result.isSuccess) {
                backupPreferences.setBackupEnabled(true)
                backupPreferences.setBackupRestoreIntent(false)
                _state.update {
                    it.copy(
                        restoreInProgress = false,
                        pendingRestore = null,
                        restoreSucceeded = true,
                        backupOptIn = true,
                        backupChoice = BackupChoice.RESTORE,
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
        viewModelScope.launch {
            backupPreferences.setBackupRestoreIntent(false)
            _state.update { it.copy(pendingRestore = null) }
        }
    }

    fun consumeRestoreFailure() {
        _state.update { it.copy(restoreFailed = false) }
    }

    // ────────────────────────────────────────────────────────────────────

    fun completeOnboarding(onComplete: () -> Unit) {
        val s = _state.value
        _state.update { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            try {
                userPreferences.setNearbyClimbSharing(s.bleSharing)
                userPreferences.setAllowRemoteDisconnect(s.bleSharing)
                userPreferences.setCrashReportOptIn(s.communityFeatures)
                userPreferences.setAnnouncementsEnabled(s.communityFeatures)
                // FEAT-002: persist backup opt-in + schedule worker. RESTORE
                // path has already set backupEnabled=true via confirmRestore,
                // so the check here is the user's explicit toggle state.
                backupPreferences.setBackupEnabled(s.backupOptIn)
                backupPreferences.setBackupOnboardingSeen(true)
                backupPreferences.setBackupRestoreIntent(false)
                BackupSyncWorker.schedule(
                    appContext,
                    enabled = s.backupOptIn && backupPreferences.isBackupFeatureEnabled(),
                    // Cadence picked in the backup step. Defaults to MANUAL
                    // so the toggle-on flow runs no background work unless
                    // the user explicitly opts into a schedule — but they
                    // can pick DAILY/WEEKLY right here in onboarding instead
                    // of having to find Settings later. Aligns with the
                    // privacy-first philosophy: no surprise background
                    // activity from a single toggle, but no forced trip
                    // through Settings either.
                    interval = s.backupFrequency,
                )
                userPreferences.setOnboardingCompleted(true)
                // Suppress the "what's new" dialog for features the user
                // already chose during onboarding — they would otherwise
                // re-see the FEAT-002 announcement on the very next launch.
                // Monotonic: never lower an existing higher watermark
                // (matters in the rare identity-switch + downgrade combo).
                val existingSeen = userPreferences.lastSeenAppVersionCode.first()
                val currentVersion = BuildConfig.VERSION_CODE
                if (existingSeen == null || existingSeen < currentVersion) {
                    userPreferences.setLastSeenAppVersionCode(currentVersion)
                }
                _state.update { it.copy(isSaving = false) }
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "completeOnboarding failed", e)
                _state.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
