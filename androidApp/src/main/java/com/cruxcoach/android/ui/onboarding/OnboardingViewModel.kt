package com.cruxcoach.android.ui.onboarding

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.R
import com.cruxcoach.android.data.SyncInterval
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.kilter.KilterApiClient
import com.cruxcoach.android.data.kilter.KilterAuthResult
import com.cruxcoach.android.data.kilter.KilterImportPreview
import com.cruxcoach.android.data.kilter.formatKilterImportSummary
import com.cruxcoach.android.data.kilter.localizeKilterImportError
import com.cruxcoach.android.data.kilter.KilterSyncEngine
import com.cruxcoach.android.data.kilter.KilterTokenStore
import com.cruxcoach.android.data.kilter.localized
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.backup.BackupInfo
import com.cruxcoach.android.nostr.backup.BackupPreferences
import com.cruxcoach.android.nostr.backup.BackupRepository
import com.cruxcoach.android.nostr.backup.BackupSyncWorker
import com.cruxcoach.domain.board.BoardBrand
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.cruxcoach.android.util.safeLaunch
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
    /** Formatted per-object import summary (success) or error message. */
    val kilterImportResult: String? = null,
    /** True when [kilterImportResult] is an error rather than a summary. */
    val kilterImportError: Boolean = false,

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
    /** True iff [restoreInProgress] AND board-sync is still finishing
     *  the climbs-table import. The restore pipeline blocks on
     *  board-sync to avoid a SQLITE_BUSY race against the bulk
     *  importer; on a fresh install this typically takes 1–3
     *  minutes (Blossom CDN download + decompression + bulk SQL
     *  insert of ~190K climbs), during which the previous UI showed
     *  no indication beyond a frozen confirm dialog. Drives a phase-
     *  aware progress message in the dialog. */
    val restoreAwaitingBoardSync: Boolean = false,
    val restoreFailed: Boolean = false,
    val restoreSucceeded: Boolean = false,
    /** Counts from the completed restore — surfaced in the onboarding success
     *  line so the user can sanity-check the magnitudes, matching the Settings
     *  restore snackbar. */
    val restoredAscents: Int = 0,
    val restoredLists: Int = 0,
    val noBackupFoundForKey: Boolean = false,
    val showRestartConfirm: Boolean = false,

    val isSaving: Boolean = false,
    val error: String? = null,

    /** FEAT-005 — when true, the Kilter step renders a ModalBottomSheet
     *  hosting the AuroraMigrationScreen body. Optional path: users
     *  with an Aurora email export can run the import without leaving
     *  onboarding. Default false (most users will skip). */
    val auroraSheetOpen: Boolean = false,

    /** Board-model picker, surfaced in the BOARD_SETUP step BEFORE the
     *  sync card. Hardware knowledge — the user knows their physical
     *  board, so making them sync-then-pick is unnecessary friction.
     *  These values are persisted to UserPreferences immediately on
     *  change so they're available everywhere else in the app. */
    val boardLayoutId: Int = com.cruxcoach.android.data.BoardConstants.KILTER_ORIGINAL_LAYOUT,
    val boardProductSizeId: Int = com.cruxcoach.android.data.BoardConstants.KILTER_DEFAULT_SIZE,
    val boardProductSizeName: String = "",
    /** Active board family — "kilter" or "moonboard" (FEAT-027). Decides
     *  which category the unified board picker lands on. */
    val boardBrand: String = BoardBrand.KILTER.wireValue,
    /** Selected MoonBoard variant when [boardBrand] == "moonboard". */
    val moonBoardVariant: com.cruxcoach.domain.board.MoonBoardVariant? = null,
    val boardSizeFrequency: Map<Int, Long> = emptyMap(),
    val boardSearchEnabled: Boolean = false,
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
    private val boardSyncManager: com.cruxcoach.android.data.BoardSyncManager,
    private val boardLocationRepository: com.cruxcoach.data.repository.BoardLocationRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "OnboardingVM"
    }

    fun setAuroraSheetOpen(value: Boolean) {
        _state.update { it.copy(auroraSheetOpen = value) }
    }

    private val _state = MutableStateFlow(
        OnboardingState(hasNostrKey = keyStore.hasKey()),
    )

    init {
        viewModelScope.safeLaunch("OnboardingViewModel") {
            val freq = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                boardLocationRepository.productSizeFrequency()
            }
            val enabled = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                boardLocationRepository.countWalls() > 0L
            }
            _state.update { it.copy(boardSizeFrequency = freq, boardSearchEnabled = enabled) }
        }
        // FEAT-031: the shared board picker persists selections from any screen;
        // mirror the choice into onboarding's state so its board step reflects it.
        viewModelScope.launch {
            combine(
                userPreferences.boardBrand,
                userPreferences.boardLayoutId,
                userPreferences.boardProductSizeId,
            ) { brand, layoutId, sizeId -> Triple(brand, layoutId, sizeId) }
                .distinctUntilChanged()
                .collect { (brand, layoutId, sizeId) ->
                    val variant = com.cruxcoach.domain.board.MoonBoardVariant.fromLayoutId(layoutId.toLong())
                    val parsed = BoardBrand.fromWire(brand)
                    val name = when {
                        parsed == BoardBrand.MOONBOARD -> variant?.displayName ?: ""
                        parsed.usesAuroraProtocol && parsed != BoardBrand.KILTER -> parsed.displayName
                        else -> com.cruxcoach.android.data.BoardConstants.sizeLabel(
                            com.cruxcoach.android.data.BoardConstants.KILTER_KNOWN_SIZES, sizeId,
                        )
                    }
                    _state.update {
                        it.copy(
                            boardBrand = brand,
                            boardLayoutId = layoutId,
                            boardProductSizeId = sizeId,
                            moonBoardVariant = variant,
                            boardProductSizeName = name,
                        )
                    }
                }
        }
    }
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /**
     * Whether the board catalogue is still importing. A Kilter import can run
     * before this finishes, in which case the imported ascents show up with
     * no name/grade until the catalogue lands (they self-heal on display) —
     * the kilter step surfaces this as a hint rather than letting the user
     * hit the nameless state unwarned.
     */
    val boardCatalogueSyncing: StateFlow<Boolean> =
        boardSyncManager.state
            .map { it.isSyncing }
            .stateIn(viewModelScope, SharingStarted.Eagerly, boardSyncManager.state.value.isSyncing)

    init {
        // Resume the restore flow if the user came back from KeyImportScreen
        // via app-restart. The marker is only set right before that navigation
        // and is cleared after the restore attempt resolves.
        viewModelScope.safeLaunch("OnboardingViewModel") {
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
        // Seed the board-model fields from the persisted preferences so
        // the user's prior choice survives onboarding restarts (e.g.
        // backup-restore round trip).
        viewModelScope.safeLaunch("OnboardingViewModel") {
            val layoutId = userPreferences.boardLayoutId.first()
            val sizeId = userPreferences.boardProductSizeId.first()
            val name = com.cruxcoach.android.data.BoardConstants.sizeLabel(
                com.cruxcoach.android.data.BoardConstants.KILTER_KNOWN_SIZES, sizeId)
            _state.update {
                it.copy(
                    boardLayoutId = layoutId,
                    boardProductSizeId = sizeId,
                    boardProductSizeName = name,
                )
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
            // Engine-side `Result.map` (not `mapCatching`) lets a SQL throw
            // out of insertLogs / getAllClimbUuids escape the Result and
            // kill the coroutine — leaving isKilterLoggingIn = true forever
            // and blocking the Skip button. Wrap defensively so any
            // unexpected throw resets the spinner with a localized error.
            try {
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
                        // Fetch import preview. A preview failure after a
                        // successful login used to fall back to the empty
                        // login form with no message (connected=true +
                        // preview=null renders the form branch) — surface it
                        // in the login error slot instead.
                        val preview = kilterSyncEngine.previewImport()
                        val previewError = preview.exceptionOrNull()
                        _state.update {
                            it.copy(
                                isKilterLoggingIn = false,
                                kilterConnected = previewError == null,
                                kilterUsername = result.username,
                                kilterImportPreview = preview.getOrNull(),
                                kilterLoginError = previewError?.let { e ->
                                    appContext.getString(
                                        R.string.kilter_preview_failed,
                                        e.message ?: e.javaClass.simpleName,
                                    )
                                },
                            )
                        }
                    }
                    is KilterAuthResult.Error -> {
                        _state.update {
                            it.copy(isKilterLoggingIn = false, kilterLoginError = result.localized(appContext))
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "kilterLogin threw", e)
                _state.update {
                    it.copy(
                        isKilterLoggingIn = false,
                        kilterLoginError = appContext.getString(R.string.kilter_sync_error, e.message ?: e.javaClass.simpleName),
                    )
                }
            }
        }
    }

    fun kilterImportOneTime() {
        _state.update { it.copy(isKilterImporting = true) }
        viewModelScope.launch {
            try {
                val result = kilterSyncEngine.importLogs(oneTimeOnly = true)
                _state.update {
                    it.copy(
                        isKilterImporting = false,
                        kilterImportResult = result.fold(
                            onSuccess = { r -> formatKilterImportSummary(appContext, r) },
                            // Localized, never raw exception text; also never
                            // null (a message-less failure used to render
                            // nothing despite kilterImportError=true).
                            onFailure = { e ->
                                Log.w(TAG, "kilterImportOneTime failed", e)
                                localizeKilterImportError(appContext, e)
                            }
                        ),
                        kilterImportError = result.isFailure,
                        // Only a SUCCESSFUL one-time import clears credentials
                        // (the engine revokes + clears tokens on success). On
                        // failure the tokens are untouched, so stay connected
                        // and let the user retry instead of dead-ending.
                        kilterConnected = if (result.isSuccess) false else it.kilterConnected
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "kilterImportOneTime threw", e)
                _state.update {
                    it.copy(
                        isKilterImporting = false,
                        kilterImportResult = localizeKilterImportError(appContext, e),
                        kilterImportError = true,
                    )
                }
            }
        }
    }

    fun kilterImportPersistent() {
        _state.update { it.copy(isKilterImporting = true) }
        viewModelScope.launch {
            try {
                val result = kilterSyncEngine.importLogs(oneTimeOnly = false)
                _state.update {
                    it.copy(
                        isKilterImporting = false,
                        kilterImportResult = result.fold(
                            onSuccess = { r -> formatKilterImportSummary(appContext, r) },
                            onFailure = { e ->
                                Log.w(TAG, "kilterImportPersistent failed", e)
                                localizeKilterImportError(appContext, e)
                            }
                        ),
                        kilterImportError = result.isFailure
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "kilterImportPersistent threw", e)
                _state.update {
                    it.copy(
                        isKilterImporting = false,
                        kilterImportResult = localizeKilterImportError(appContext, e),
                        kilterImportError = true,
                    )
                }
            }
        }
    }

    /**
     * Dismiss the import result/error view and return to the preview so the
     * user can retry. On a failed one-time import the tokens were NOT cleared
     * (see [kilterImportOneTime]), so the connection is still live and a
     * retry works without re-login.
     */
    fun clearKilterImportResult() {
        _state.update { it.copy(kilterImportResult = null, kilterImportError = false) }
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
        _state.update { it.copy(
            restoreInProgress = true,
            restoreAwaitingBoardSync = boardSyncManager.state.value.isSyncing,
            restoreFailed = false,
        ) }
        // Surface the board-sync wait phase to the UI. The restore
        // pipeline itself blocks on `boardSyncManager.state.first
        // { !it.isSyncing }`; without this collector the user sees
        // a frozen "Wiederherstellen…" dialog for the ~30 s of board
        // sync on a fresh install. Cancellation is automatic when the
        // restore launch above completes (collector lives inside the
        // same viewModelScope job).
        val boardSyncWatcher = viewModelScope.launch {
            boardSyncManager.state.collect { sync ->
                _state.update { it.copy(restoreAwaitingBoardSync = sync.isSyncing) }
            }
        }
        viewModelScope.launch {
            val result = runCatching { backupRepository.restore(info) }
            boardSyncWatcher.cancel()
            val imported = result.getOrNull()
            if (imported != null) {
                backupPreferences.setBackupEnabled(true)
                backupPreferences.setBackupRestoreIntent(false)
                _state.update {
                    it.copy(
                        restoreInProgress = false,
                        restoreAwaitingBoardSync = false,
                        pendingRestore = null,
                        restoreSucceeded = true,
                        restoredAscents = imported.boardAscents,
                        restoredLists = imported.climbLists,
                        backupOptIn = true,
                        backupChoice = BackupChoice.RESTORE,
                    )
                }
            } else {
                Log.w(TAG, "onboarding restore failed", result.exceptionOrNull())
                _state.update {
                    it.copy(
                        restoreInProgress = false,
                        restoreAwaitingBoardSync = false,
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
                // Preview-only Kilter login: tokens were stored at the
                // KILTER step (so the user could see their account), but
                // they finished onboarding without picking Import Once /
                // Import Sync. The Settings analog `dismissKilterPreview`
                // revokes server-side and clears local — mirror that here
                // so the 30-day Keycloak refresh token doesn't outlive a
                // step the user effectively cancelled.
                if (s.kilterConnected && s.kilterImportResult == null) {
                    runCatching { kilterApiClient.revokeRefreshToken() }
                    kilterTokenStore.clear()
                }
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
                // Commit the board-step selection even when the user accepted
                // the displayed defaults without tapping a chip. Without this,
                // the DataStore keys stay unset and BoardSyncViewModel's
                // checkFirstSyncModelSelection (gated on
                // `isBoardProductSizeDefault`) would re-prompt the model
                // dialog right after the first board sync — duplicating
                // the choice the user just made in the BOARD_SETUP step.
                if (BoardBrand.fromWire(s.boardBrand) == BoardBrand.MOONBOARD) {
                    userPreferences.setMoonBoardSelection(s.boardLayoutId)
                } else {
                    userPreferences.setBoardLayoutId(s.boardLayoutId)
                    userPreferences.setBoardProductSizeId(s.boardProductSizeId)
                    userPreferences.setBoardBrand(BoardBrand.KILTER.wireValue)
                }
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
