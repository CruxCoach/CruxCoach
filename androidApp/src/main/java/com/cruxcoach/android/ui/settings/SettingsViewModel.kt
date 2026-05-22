package com.cruxcoach.android.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.kilter.localized
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.data.AnnouncementRepository
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.MoonBoardCatalogueSync
import com.cruxcoach.android.data.SyncInterval
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.android.notification.AnnouncementTagParser
import com.cruxcoach.android.notification.BoardSyncWorker
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.model.UserProfile
import com.cruxcoach.util.DateTimeUtil
import com.cruxcoach.util.GradeConverter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.nostr.OfflineQueueManager
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.board.BoardEasterAnimations
import com.cruxcoach.android.ui.board.EasterAnimation
import javax.inject.Inject

data class ProfileFormState(
    val name: String = "",
    val age: String = "",
    val weightKg: String = "",
    val heightCm: String = "",
    val maxGradeIndex: Int = 4,
    val sessionsPerWeek: Int = 3,
    val profileId: Long = 0
)

data class RoutePlaybackSettings(
    val frameSpeed: Float = 5f,
    val useSetterSpeed: Boolean = true,
    val countdown: Boolean = true,
    val countdownSeconds: Int = 5,
    val autoLoop: Boolean = false
)

data class RestTimerSettings(
    val durationSeconds: Int = 180,
    val autoStart: Boolean = false
)

data class ClimbSharingSettings(
    val enabled: Boolean = false,
    val allowRemoteDisconnect: Boolean = false,
    val advertisingSupported: Boolean? = null
)

data class SettingsState(
    val isLoading: Boolean = true,
    val darkMode: DarkModeSetting = DarkModeSetting.SYSTEM,
    val gradeScale: GradeScale = GradeScale.FRENCH,
    val boardLayoutId: Int = BoardConstants.KILTER_ORIGINAL_LAYOUT,
    val boardProductSizeId: Int = BoardConstants.KILTER_DEFAULT_SIZE,
    val boardProductSizeName: String = "",
    /** Active board brand — "kilter" | "moonboard" (FEAT-027). */
    val boardBrand: String = "kilter",
    /** Active MoonBoard variant, or null when the brand is Kilter (FEAT-027). */
    val moonBoardVariant: MoonBoardVariant? = null,
    /** One-shot snackbar text from the most recent MoonBoard catalogue
     *  sync, surfaced via the existing delete-success snackbar slot. */
    val moonBoardSyncMessage: String? = null,
    val boardSizeFrequency: Map<Int, Long> = emptyMap(),
    val boardSearchEnabled: Boolean = false,
    val syncInterval: SyncInterval = SyncInterval.MANUAL,
    val lastSyncTimestamp: String? = null,
    val hasAssessment: Boolean = false,
    val ledColors: LedHoldColors = LedHoldColors(),
    val bleAutoDisconnectSeconds: Int = 60,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val error: String? = null,
    val profile: ProfileFormState = ProfileFormState(),
    val routePlayback: RoutePlaybackSettings = RoutePlaybackSettings(),
    val restTimer: RestTimerSettings = RestTimerSettings(),
    val climbSharing: ClimbSharingSettings = ClimbSharingSettings(),
    val keepScreenOn: Boolean = false,
    val easterAnimationsUnlocked: Boolean = false,
    val isAnimating: Boolean = false,
    val crashReportOptIn: Boolean = false,
    val announcementsEnabled: Boolean = true,
    val announcementCatRelease: Boolean = true,
    val announcementCatIssue: Boolean = true,
    val announcementCatTip: Boolean = true,
    val announcementCatGeneral: Boolean = true,
    val unreadAnnouncements: Int = 0,
    val queuedCount: Int = 0,
    val productSizes: List<com.cruxcoach.data.repository.BoardSize> = emptyList(),
    val showDeleteBoardDataDialog: Boolean = false,
    val showDeleteUserDataDialog: Boolean = false,
    val deleteSuccess: String? = null,
    val kilterAccount: KilterAccountState = KilterAccountState()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val syncManager: BoardSyncManager,
    private val userPreferences: UserPreferences,
    private val bleConnection: BoardBleConnection,
    private val climbAdvertiser: ClimbBleAdvertiser,
    private val moonBoardCatalogueSync: MoonBoardCatalogueSync,
    private val announcementRepository: AnnouncementRepository,
    private val queueManager: OfflineQueueManager,
    private val kilterTokenStore: com.cruxcoach.android.data.kilter.KilterTokenStore,
    private val kilterSyncEngine: com.cruxcoach.android.data.kilter.KilterSyncEngine,
    private val kilterApiClient: com.cruxcoach.android.data.kilter.KilterApiClient,
    private val boardLocationRepository: com.cruxcoach.data.repository.BoardLocationRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        loadSettings()
        viewModelScope.launch {
            val freq = withContext(Dispatchers.IO) { boardLocationRepository.productSizeFrequency() }
            val enabled = withContext(Dispatchers.IO) { boardLocationRepository.countWalls() > 0L }
            _state.update { it.copy(boardSizeFrequency = freq, boardSearchEnabled = enabled) }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            // Batch-load ALL initial values in one IO block to avoid flash of defaults
            val initialState = withContext(Dispatchers.IO) {
                val profile = userRepository.getActiveProfile()
                val layoutId = userPreferences.boardLayoutId.first()
                val boardSizeId = userPreferences.boardProductSizeId.first()
                val boardSizeName = boardRepository.getProductSize(boardSizeId)
                    ?.let { BoardConstants.sizeLabel(it.id, it.name) } ?: ""
                val boardBrand = userPreferences.boardBrand.first()
                // MoonBoard layout ids (2/4/5) are disjoint from Kilter's,
                // so the active variant is derived directly from the
                // single boardLayoutId pref.
                val moonBoardVariant = MoonBoardVariant.fromLayoutId(layoutId.toLong())
                val interval = userPreferences.syncInterval.first()
                val lastSync = userPreferences.lastSyncTimestamp.first()
                val scale = userPreferences.gradeScale.first()
                val autoDisconnect = userPreferences.bleAutoDisconnectSeconds.first()
                val ledColors = userPreferences.ledHoldColors.first()
                val frameSpeed = userPreferences.routeFrameSpeed.first()
                val useSetterSpeed = userPreferences.routeUseSetterSpeed.first()
                val countdown = userPreferences.routeCountdown.first()
                val countdownSeconds = userPreferences.routeCountdownSeconds.first()
                val autoLoop = userPreferences.routeAutoLoop.first()
                val timerDuration = userPreferences.restTimerDurationSeconds.first()
                val timerAutoStart = userPreferences.restTimerAutoStart.first()
                val sharingEnabled = userPreferences.nearbyClimbSharing.first()
                val remoteDisconnect = userPreferences.allowRemoteDisconnect.first()
                val easterUnlocked = userPreferences.easterAnimationsUnlocked.first()
                val keepScreenOn = userPreferences.keepScreenOn.first()
                val crashOptIn = userPreferences.crashReportOptIn.first() ?: false
                val announcementsOn = userPreferences.announcementsEnabled.first()
                val catRelease = userPreferences.announcementCatRelease.first()
                val catIssue = userPreferences.announcementCatIssue.first()
                val catTip = userPreferences.announcementCatTip.first()
                val catGeneral = userPreferences.announcementCatGeneral.first()
                val unreadAnnouncements = announcementRepository.getUnreadCount().toInt()
                val darkMode = userPreferences.darkMode.first()
                val advertisingSupported = climbAdvertiser.checkSupported()
                val queueStats = runCatching { boardRepository.getKilterPublishQueueStats() }
                    .getOrElse { com.cruxcoach.data.repository.KilterPublishQueueStats(0, 0, null) }

                val profileForm = if (profile != null) {
                    val gradeIndex = GradeConverter.gradeToIndex(profile.maxBoulderGrade)
                        .let { if (it < 0) 4 else it }
                    ProfileFormState(
                        name = profile.name,
                        age = profile.age.toString(),
                        weightKg = profile.weightKg.toString(),
                        heightCm = profile.heightCm.toString(),
                        maxGradeIndex = gradeIndex,
                        sessionsPerWeek = profile.sessionsPerWeek,
                        profileId = profile.id
                    )
                } else ProfileFormState()

                val hasAssessment = profile?.let {
                    userRepository.getLatestAssessment(it.id) != null
                } ?: false

                SettingsState(
                    isLoading = false,
                    darkMode = darkMode,
                    gradeScale = scale,
                    boardLayoutId = layoutId,
                    boardProductSizeId = boardSizeId,
                    boardProductSizeName = boardSizeName,
                    boardBrand = boardBrand,
                    moonBoardVariant = moonBoardVariant,
                    syncInterval = interval,
                    lastSyncTimestamp = lastSync,
                    hasAssessment = hasAssessment,
                    ledColors = ledColors,
                    bleAutoDisconnectSeconds = autoDisconnect,
                    profile = profileForm,
                    routePlayback = RoutePlaybackSettings(
                        frameSpeed = frameSpeed,
                        useSetterSpeed = useSetterSpeed,
                        countdown = countdown,
                        countdownSeconds = countdownSeconds,
                        autoLoop = autoLoop
                    ),
                    restTimer = RestTimerSettings(
                        durationSeconds = timerDuration,
                        autoStart = timerAutoStart
                    ),
                    keepScreenOn = keepScreenOn,
                    easterAnimationsUnlocked = easterUnlocked,
                    climbSharing = ClimbSharingSettings(
                        enabled = sharingEnabled,
                        allowRemoteDisconnect = remoteDisconnect,
                        advertisingSupported = advertisingSupported
                    ),
                    crashReportOptIn = crashOptIn,
                    announcementsEnabled = announcementsOn,
                    announcementCatRelease = catRelease,
                    announcementCatIssue = catIssue,
                    announcementCatTip = catTip,
                    announcementCatGeneral = catGeneral,
                    unreadAnnouncements = unreadAnnouncements,
                    kilterAccount = KilterAccountState(
                        isConnected = kilterTokenStore.hasCredentials() &&
                            userPreferences.kilterSyncEnabled.first(),
                        username = kilterTokenStore.getUsername() ?: "",
                        lastSync = userPreferences.kilterLastSync.first(),
                        pushEnabled = userPreferences.kilterPushEnabled.first(),
                        climbPublishEnabled = userPreferences.kilterClimbPublishEnabled.first(),
                        publishPendingCount = queueStats.pendingCount,
                        publishFailedCount = queueStats.failedCount,
                        publishLastAttemptAtMs = queueStats.lastAttemptAtMs,
                    )
                )
            }
            _state.update { initialState }

            // Start collectors for live updates after initial load
            launch { userPreferences.ledHoldColors.collect { colors -> _state.update { it.copy(ledColors = colors) } } }
            launch { userPreferences.routeFrameSpeed.collect { speed -> _state.update { it.copy(routePlayback = it.routePlayback.copy(frameSpeed = speed)) } } }
            launch { userPreferences.routeUseSetterSpeed.collect { v -> _state.update { it.copy(routePlayback = it.routePlayback.copy(useSetterSpeed = v)) } } }
            launch { userPreferences.routeCountdown.collect { v -> _state.update { it.copy(routePlayback = it.routePlayback.copy(countdown = v)) } } }
            launch { userPreferences.routeCountdownSeconds.collect { v -> _state.update { it.copy(routePlayback = it.routePlayback.copy(countdownSeconds = v)) } } }
            launch { userPreferences.routeAutoLoop.collect { v -> _state.update { it.copy(routePlayback = it.routePlayback.copy(autoLoop = v)) } } }
            launch { userPreferences.restTimerDurationSeconds.collect { v -> _state.update { it.copy(restTimer = it.restTimer.copy(durationSeconds = v)) } } }
            launch { userPreferences.restTimerAutoStart.collect { v -> _state.update { it.copy(restTimer = it.restTimer.copy(autoStart = v)) } } }
            launch { userPreferences.lastSyncTimestamp.collect { v -> _state.update { it.copy(lastSyncTimestamp = v) } } }
            launch { userPreferences.darkMode.collect { v -> _state.update { it.copy(darkMode = v) } } }
            launch { userPreferences.keepScreenOn.collect { v -> _state.update { it.copy(keepScreenOn = v) } } }
            launch { userPreferences.nearbyClimbSharing.collect { v -> _state.update { it.copy(climbSharing = it.climbSharing.copy(enabled = v)) } } }
            launch { userPreferences.allowRemoteDisconnect.collect { v -> _state.update { it.copy(climbSharing = it.climbSharing.copy(allowRemoteDisconnect = v)) } } }
            launch { userPreferences.crashReportOptIn.collect { v -> _state.update { it.copy(crashReportOptIn = v ?: false) } } }
            launch { kilterSyncEngine.sessionExpired.collect { expired -> _state.update { it.copy(kilterAccount = it.kilterAccount.copy(sessionExpired = expired)) } } }
            launch { userPreferences.announcementsEnabled.collect { v -> _state.update { it.copy(announcementsEnabled = v) } } }
            launch { userPreferences.announcementCatRelease.collect { v -> _state.update { it.copy(announcementCatRelease = v) } } }
            launch { userPreferences.announcementCatIssue.collect { v -> _state.update { it.copy(announcementCatIssue = v) } } }
            launch { userPreferences.announcementCatTip.collect { v -> _state.update { it.copy(announcementCatTip = v) } } }
            launch { userPreferences.announcementCatGeneral.collect { v -> _state.update { it.copy(announcementCatGeneral = v) } } }
            launch { queueManager.queuedCount.collect { v -> _state.update { it.copy(queuedCount = v) } } }
            launch { queueManager.refreshCount() }
        }
    }

    fun updateName(v: String) { _state.update { it.copy(profile = it.profile.copy(name = v), saveSuccess = false) } }
    fun updateAge(v: String) { _state.update { it.copy(profile = it.profile.copy(age = v.filter { c -> c.isDigit() }), saveSuccess = false) } }
    fun updateWeight(v: String) { _state.update { it.copy(profile = it.profile.copy(weightKg = v), saveSuccess = false) } }
    fun updateHeight(v: String) { _state.update { it.copy(profile = it.profile.copy(heightCm = v), saveSuccess = false) } }
    fun updateSessionsPerWeek(v: Int) { _state.update { it.copy(profile = it.profile.copy(sessionsPerWeek = v.coerceIn(1, 7)), saveSuccess = false) } }

    fun gradeUp() {
        _state.update { s ->
            val frenchMode = s.gradeScale == GradeScale.FRENCH
            s.copy(profile = s.profile.copy(maxGradeIndex = GradeConverter.nextIndex(s.profile.maxGradeIndex, frenchMode)), saveSuccess = false)
        }
    }

    fun gradeDown() {
        _state.update { s ->
            val frenchMode = s.gradeScale == GradeScale.FRENCH
            s.copy(profile = s.profile.copy(maxGradeIndex = GradeConverter.prevIndex(s.profile.maxGradeIndex, frenchMode)), saveSuccess = false)
        }
    }

    fun saveProfile() {
        val p = _state.value.profile
        if (p.name.isBlank()) return
        _state.update { it.copy(isSaving = true, error = null, saveSuccess = false) }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val existing = userRepository.getActiveProfile() ?: return@withContext
                    val updated = existing.copy(
                        name = p.name.trim(),
                        age = p.age.toIntOrNull() ?: existing.age,
                        weightKg = p.weightKg.toDoubleOrNull() ?: existing.weightKg,
                        heightCm = p.heightCm.toDoubleOrNull() ?: existing.heightCm,
                        maxBoulderGrade = GradeConverter.indexToFrench(p.maxGradeIndex),
                        sessionsPerWeek = p.sessionsPerWeek,
                        updatedAt = DateTimeUtil.nowIso()
                    )
                    userRepository.updateProfile(updated)
                }
                _state.update { it.copy(isSaving = false, saveSuccess = true) }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }

    fun updateDarkMode(mode: DarkModeSetting) {
        _state.update { it.copy(darkMode = mode) }
        viewModelScope.launch {
            userPreferences.setDarkMode(mode)
        }
    }

    fun updateGradeScale(scale: GradeScale) {
        _state.update { it.copy(gradeScale = scale) }
        viewModelScope.launch {
            userPreferences.setGradeScale(scale)
        }
    }

    fun updateSyncInterval(interval: SyncInterval) {
        _state.update { it.copy(syncInterval = interval) }
        viewModelScope.launch {
            userPreferences.setSyncInterval(interval)
            BoardSyncWorker.schedule(context, interval)
        }
    }

    fun updateBoardProductSize(id: Int, name: String) {
        // Also record brand = "kilter": the unified board picker can switch
        // back from MoonBoard, and the brand pref must follow so Browse +
        // Detail stop treating the active board as a MoonBoard (FEAT-027).
        _state.update {
            it.copy(
                boardProductSizeId = id,
                boardProductSizeName = name,
                boardBrand = "kilter",
                moonBoardVariant = null,
            )
        }
        viewModelScope.launch {
            userPreferences.setBoardProductSizeId(id)
            userPreferences.setBoardBrand("kilter")
        }
    }

    /** FEAT-007 Path B: apply a board chosen via gym search. Atomic —
     *  sets layout + size together, no layout-roll side effect (unlike
     *  updateBoardLayout). label is the official BoardConstants wording. */
    fun selectBoardFromGym(layoutId: Int, productSizeId: Int, label: String) {
        // FEAT-027: a gym/dialog board selection is always a Kilter board —
        // record brand = "kilter" so switching back from MoonBoard sticks
        // (Browse + Detail stop treating the active board as a MoonBoard).
        _state.update {
            it.copy(
                boardLayoutId = layoutId,
                boardProductSizeId = productSizeId,
                boardProductSizeName = label,
                boardBrand = "kilter",
                moonBoardVariant = null,
            )
        }
        viewModelScope.launch {
            userPreferences.setBoardLayoutId(layoutId)
            userPreferences.setBoardProductSizeId(productSizeId)
            userPreferences.setBoardBrand("kilter")
        }
    }

    /**
     * Select a MoonBoard variant as the active board (FEAT-027). A MoonBoard
     * "set up" is a fixed, standardised hold configuration — the variant
     * fully determines the board, there is no separate hold-set choice.
     * Persists atomically via [UserPreferences.setMoonBoardSelection]
     * (variant layout id, brand="moonboard", angle 40°), updates state, then
     * kicks off a MoonBoard catalogue sync. The result surfaces as a snackbar.
     */
    fun selectMoonBoardVariant(variant: MoonBoardVariant) {
        _state.update {
            it.copy(
                boardBrand = "moonboard",
                boardLayoutId = variant.layoutId.toInt(),
                moonBoardVariant = variant,
                // The Kilter board-size label is meaningless for a MoonBoard;
                // SettingsScreen shows the variant name instead.
                boardProductSizeName = variant.displayName,
            )
        }
        viewModelScope.launch {
            userPreferences.setMoonBoardSelection(variant.layoutId.toInt())
            val result = withContext(Dispatchers.IO) { moonBoardCatalogueSync.sync() }
            val message = when (result) {
                is MoonBoardCatalogueSync.Result.AlreadyCurrent ->
                    context.getString(R.string.moonboard_sync_already_current)
                is MoonBoardCatalogueSync.Result.Imported ->
                    context.getString(R.string.moonboard_sync_imported)
                is MoonBoardCatalogueSync.Result.Failed ->
                    context.getString(R.string.moonboard_sync_failed, result.message)
            }
            _state.update { it.copy(moonBoardSyncMessage = message) }
        }
    }

    fun dismissMoonBoardSyncMessage() {
        _state.update { it.copy(moonBoardSyncMessage = null) }
    }

    /**
     * Switch the active Kilter layout (Original ↔ Homewall). The
     * product_size also has to roll over because Original sizes
     * (12x12, 16x12, …) aren't valid on a Homewall and vice-versa —
     * picking the FIRST visible size for the new layout's product is
     * the sensible default; the user can refine via the "Board-Modell"
     * picker right below.
     */
    fun updateBoardLayout(layoutId: Int) {
        if (_state.value.boardLayoutId == layoutId) return
        viewModelScope.launch {
            userPreferences.setBoardLayoutId(layoutId)
            val targetProductId = layoutToProductId(layoutId)
            val newSize = withContext(Dispatchers.IO) {
                boardRepository.getAllProductSizes(targetProductId.toLong())
                    .firstOrNull()
            }
            val newSizeId = newSize?.id?.toInt()
                ?: when (layoutId) {
                    BoardConstants.KILTER_HOMEWALL_LAYOUT -> BoardConstants.KILTER_HOMEWALL_DEFAULT_SIZE
                    else -> BoardConstants.KILTER_DEFAULT_SIZE
                }
            val newSizeName = newSize?.let { BoardConstants.sizeLabel(it.id, it.name) } ?: ""
            userPreferences.setBoardProductSizeId(newSizeId)
            _state.update {
                it.copy(
                    boardLayoutId = layoutId,
                    boardProductSizeId = newSizeId,
                    boardProductSizeName = newSizeName,
                )
            }
        }
    }

    private fun layoutToProductId(layoutId: Int): Int = when (layoutId) {
        BoardConstants.KILTER_HOMEWALL_LAYOUT -> BoardConstants.KILTER_HOMEWALL_PRODUCT_ID
        else -> BoardConstants.KILTER_PRODUCT_ID
    }

    fun loadProductSizes() {
        if (_state.value.productSizes.isNotEmpty()) return
        viewModelScope.launch {
            val sizes = withContext(Dispatchers.IO) {
                // Combined picker needs BOTH products — the in-dialog
                // Original/Homewall segment only appears when the list
                // spans >1 product. (Loading a single product post-sync
                // hid Homewall entirely.)
                boardRepository.getAllProductSizes(
                    BoardConstants.KILTER_PRODUCT_ID.toLong()
                ) + boardRepository.getAllProductSizes(
                    BoardConstants.KILTER_HOMEWALL_PRODUCT_ID.toLong()
                )
            }
            _state.update { it.copy(productSizes = sizes) }
        }
    }

    fun updateLedColor(roleId: Int, colorByte: Int) {
        viewModelScope.launch {
            userPreferences.setLedColor(roleId, colorByte)
            if (bleConnection.isConnected()) {
                val current = _state.value.ledColors
                val updated = LedHoldColors(
                    start = if (roleId == HoldRole.START) colorByte else current.start,
                    hand = if (roleId == HoldRole.HAND) colorByte else current.hand,
                    finish = if (roleId == HoldRole.FINISH) colorByte else current.finish,
                    foot = if (roleId == HoldRole.FOOT) colorByte else current.foot
                )
                bleConnection.resendWithColors(updated.toRoleColorMap())
            }
        }
    }

    fun resetLedColors() {
        viewModelScope.launch {
            userPreferences.resetLedColors()
            if (bleConnection.isConnected()) {
                bleConnection.resendWithColors(LedHoldColors().toRoleColorMap())
            }
        }
    }

    fun setKilterColors() {
        viewModelScope.launch {
            userPreferences.setKilterColors()
            if (bleConnection.isConnected()) {
                bleConnection.resendWithColors(LedHoldColors.kilterStandard().toRoleColorMap())
            }
        }
    }

    fun updateRouteFrameSpeed(seconds: Float) {
        viewModelScope.launch { userPreferences.setRouteFrameSpeed(seconds) }
    }

    fun updateRouteUseSetterSpeed(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setRouteUseSetterSpeed(enabled) }
    }

    fun updateRouteCountdown(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setRouteCountdown(enabled) }
    }

    fun updateRouteCountdownSeconds(seconds: Int) {
        viewModelScope.launch { userPreferences.setRouteCountdownSeconds(seconds) }
    }

    fun updateRouteAutoLoop(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setRouteAutoLoop(enabled) }
    }

    fun updateRestTimerDuration(seconds: Int) {
        viewModelScope.launch { userPreferences.setRestTimerDurationSeconds(seconds) }
    }

    fun updateRestTimerAutoStart(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setRestTimerAutoStart(enabled) }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setKeepScreenOn(enabled) }
    }

    fun updateBleAutoDisconnect(seconds: Int) {
        _state.update { it.copy(bleAutoDisconnectSeconds = seconds) }
        bleConnection.autoDisconnectSeconds = seconds
        viewModelScope.launch {
            userPreferences.setBleAutoDisconnectSeconds(seconds)
        }
    }

    fun updateNearbyClimbSharing(enabled: Boolean) {
        _state.update { it.copy(climbSharing = it.climbSharing.copy(
            enabled = enabled,
            allowRemoteDisconnect = enabled
        )) }
        if (!enabled) {
            climbAdvertiser.stopAdvertising()
        }
        viewModelScope.launch {
            userPreferences.setNearbyClimbSharing(enabled)
            userPreferences.setAllowRemoteDisconnect(enabled)
        }
    }

    fun updateCrashReportOptIn(enabled: Boolean) {
        _state.update { it.copy(crashReportOptIn = enabled) }
        viewModelScope.launch { userPreferences.setCrashReportOptIn(enabled) }
    }

    fun updateAnnouncementsEnabled(enabled: Boolean) {
        _state.update { it.copy(announcementsEnabled = enabled) }
        viewModelScope.launch { userPreferences.setAnnouncementsEnabled(enabled) }
    }

    fun updateAnnouncementCategory(category: String, enabled: Boolean) {
        _state.update {
            when (category) {
                AnnouncementTagParser.CATEGORY_RELEASE -> it.copy(announcementCatRelease = enabled)
                AnnouncementTagParser.CATEGORY_ISSUE -> it.copy(announcementCatIssue = enabled)
                AnnouncementTagParser.CATEGORY_TIP -> it.copy(announcementCatTip = enabled)
                AnnouncementTagParser.CATEGORY_GENERAL -> it.copy(announcementCatGeneral = enabled)
                else -> it
            }
        }
        viewModelScope.launch { userPreferences.setAnnouncementCategoryEnabled(category, enabled) }
    }

    fun drainQueue() {
        viewModelScope.launch { queueManager.drainQueue() }
    }

    fun isBleConnected(): Boolean = bleConnection.isConnected()

    fun unlockEasterAnimations() {
        _state.update { it.copy(easterAnimationsUnlocked = true) }
        viewModelScope.launch {
            userPreferences.setEasterAnimationsUnlocked(true)
        }
    }

    // ── Easter animation ─────────────────────────────────────────

    private var animationJob: Job? = null

    fun playEasterAnimation() {
        if (!bleConnection.isConnected()) return
        animationJob?.cancel()
        animationJob = viewModelScope.launch {
            _state.update { it.copy(isAnimating = true) }
            try {
                val grid = withContext(Dispatchers.IO) {
                    boardRepository.getLedGrid(_state.value.boardProductSizeId)
                }
                if (grid.isEmpty()) return@launch
                val frames = BoardEasterAnimations.easterEgg(grid)
                if (frames.isEmpty() || frames.all { it.leds.isEmpty() }) return@launch
                val encoder = com.cruxcoach.domain.board.BoardPacketEncoder(3)
                repeat(3) {
                    for (frame in frames) {
                        val chunks = encoder.encodeClimb(frame.leds)
                        bleConnection.sendRawChunks(chunks)
                        delay(250)
                    }
                }
                bleConnection.clearBoard()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Mirrors the BoardBrowserViewModel.playEasterAnimation
                // fix — pre-fix a BLE / SQL / encoder throw skipped the
                // try/finally's catch arm and poisoned the parent scope.
                Log.w(TAG, "easter animation failed", e)
            } finally {
                _state.update { it.copy(isAnimating = false) }
            }
        }
    }

    fun stopAnimation() {
        animationJob?.cancel()
        animationJob = null
        _state.update { it.copy(isAnimating = false) }
        viewModelScope.launch { bleConnection.clearBoard() }
    }

    // ── Data management ──────────────────────────────────────────

    fun showDeleteBoardDataDialog() { _state.update { it.copy(showDeleteBoardDataDialog = true) } }
    fun showDeleteUserDataDialog() { _state.update { it.copy(showDeleteUserDataDialog = true) } }
    fun dismissDeleteDialog() { _state.update { it.copy(showDeleteBoardDataDialog = false, showDeleteUserDataDialog = false) } }
    fun dismissDeleteSuccess() { _state.update { it.copy(deleteSuccess = null) } }

    fun deleteBoardData() {
        _state.update { it.copy(showDeleteBoardDataDialog = false) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                boardRepository.deleteAllBoardData()
            }
            syncManager.resetAfterDataDeletion()
            _state.update { it.copy(deleteSuccess = context.getString(R.string.settings_delete_board_success)) }
        }
    }

    fun deleteUserBoardData() {
        _state.update { it.copy(showDeleteUserDataDialog = false) }
        viewModelScope.launch {
            // Audit-trail: log the destructive action with a timestamp so a
            // user reporting "my logbook is empty" can be triaged via logcat
            // without DB forensics.
            Log.i(TAG, "destructive: deleteAllUserBoardData() requested at ${System.currentTimeMillis() / 1000}")
            withContext(Dispatchers.IO) {
                personalBoardRepo.deleteAllUserBoardData()
            }
            Log.i(TAG, "destructive: deleteAllUserBoardData() done")
            _state.update { it.copy(deleteSuccess = context.getString(R.string.settings_delete_logbook_success)) }
        }
    }

    // --- Kilter Account ---

    fun showKilterLogin() {
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(showLoginSheet = true, loginError = null)) }
    }

    fun dismissKilterLogin() {
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(
            showLoginSheet = false, loginEmail = "", loginPassword = "", loginError = null
        )) }
    }

    fun updateKilterEmail(email: String) {
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(loginEmail = email)) }
    }

    fun updateKilterPassword(password: String) {
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(loginPassword = password)) }
    }

    fun kilterLogin() {
        val ka = _state.value.kilterAccount
        if (ka.isLoggingIn) return
        _state.update { it.copy(kilterAccount = ka.copy(isLoggingIn = true, loginError = null)) }

        viewModelScope.launch {
            // Engine-side `Result.map` (not `mapCatching`) lets a SQL throw
            // out of insertLogs / getAllClimbUuids escape the Result and
            // kill the coroutine — leaving isLoggingIn = true forever with
            // tokens already persisted, so the user sees a stuck spinner
            // and dismissKilterLogin never runs. Wrap defensively.
            try {
                val result = kilterApiClient.authenticate(ka.loginEmail, ka.loginPassword)
                when (result) {
                    is com.cruxcoach.android.data.kilter.KilterAuthResult.Success -> {
                        // Re-login: revoke the prior offline_access refresh token
                        // server-side before overwriting it locally, so a stolen
                        // copy can't outlive the new session for the full 30-day
                        // Keycloak window. Best-effort — runCatching keeps a
                        // network failure from blocking the new login.
                        if (kilterTokenStore.hasCredentials()) {
                            runCatching { kilterApiClient.revokeRefreshToken() }
                        }
                        kilterTokenStore.storeTokens(
                            result.accessToken, result.refreshToken,
                            result.expiresIn, result.userUuid, result.username
                        )
                        kilterSyncEngine.clearSessionExpired()
                        // Fetch preview
                        val preview = kilterSyncEngine.previewImport()
                        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(
                            isLoggingIn = false,
                            showLoginSheet = false,
                            loginEmail = "", loginPassword = "",
                            showImportPreview = true,
                            importPreview = preview.getOrNull(),
                            username = result.username
                        )) }
                    }
                    is com.cruxcoach.android.data.kilter.KilterAuthResult.Error -> {
                        val msg = result.localized(context)
                        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(
                            isLoggingIn = false, loginError = msg
                        )) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "kilterLogin threw", e)
                _state.update { it.copy(kilterAccount = it.kilterAccount.copy(
                    isLoggingIn = false,
                    loginError = context.getString(R.string.kilter_sync_error, ""),
                )) }
            }
        }
    }

    fun kilterImportOneTime() {
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(isImporting = true)) }
        viewModelScope.launch {
            try {
                val result = kilterSyncEngine.importLogs(oneTimeOnly = true)
                _state.update { it.copy(kilterAccount = it.kilterAccount.copy(
                    isImporting = false,
                    showImportPreview = false,
                    isConnected = false,
                    resultMessage = result.fold(
                        onSuccess = { context.getString(R.string.kilter_import_success, it) },
                        onFailure = { context.getString(R.string.kilter_sync_error, it.message ?: "") }
                    )
                )) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "kilterImportOneTime threw", e)
                _state.update { it.copy(kilterAccount = it.kilterAccount.copy(
                    isImporting = false,
                    showImportPreview = false,
                    resultMessage = context.getString(R.string.kilter_sync_error, ""),
                )) }
            }
        }
    }

    fun kilterImportPersistent() {
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(isImporting = true)) }
        viewModelScope.launch {
            try {
                val result = kilterSyncEngine.importLogs(oneTimeOnly = false)
                val lastSync = userPreferences.kilterLastSync.first()
                _state.update { it.copy(kilterAccount = it.kilterAccount.copy(
                    isImporting = false,
                    showImportPreview = false,
                    isConnected = true,
                    lastSync = lastSync,
                    resultMessage = result.fold(
                        onSuccess = { context.getString(R.string.kilter_import_success, it) },
                        onFailure = { context.getString(R.string.kilter_sync_error, it.message ?: "") }
                    )
                )) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "kilterImportPersistent threw", e)
                _state.update { it.copy(kilterAccount = it.kilterAccount.copy(
                    isImporting = false,
                    showImportPreview = false,
                    resultMessage = context.getString(R.string.kilter_sync_error, ""),
                )) }
            }
        }
    }

    fun dismissKilterPreview() {
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(showImportPreview = false)) }
        viewModelScope.launch {
            kilterApiClient.revokeRefreshToken()
            kilterTokenStore.clear()
        }
    }

    fun kilterSyncNow() {
        if (_state.value.kilterAccount.isSyncing) return
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(isSyncing = true)) }
        viewModelScope.launch {
            val result = kilterSyncEngine.syncBidirectional()
            val lastSync = userPreferences.kilterLastSync.first()
            _state.update { it.copy(kilterAccount = it.kilterAccount.copy(
                isSyncing = false,
                lastSync = lastSync,
                resultMessage = result.fold(
                    onSuccess = { context.getString(R.string.kilter_sync_success, it.downloaded, it.uploaded) },
                    onFailure = { context.getString(R.string.kilter_sync_error, it.message ?: "") }
                )
            )) }
        }
    }

    /**
     * User tapped "Retry now" on the Kilter publish-queue card. Fans out
     * to [KilterPublishRetryWorker.runOnce] and then refreshes the
     * queue-stats so the UI reflects the post-batch state.
     *
     * No retry-running flag flicker: WorkManager's APPEND_OR_REPLACE
     * semantics serialize this behind any in-flight periodic worker run,
     * so a double-tap before the previous attempt finishes is safe.
     */
    fun retryKilterPublishQueueNow() {
        if (_state.value.kilterAccount.publishRetryRunning) return
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(publishRetryRunning = true)) }
        viewModelScope.launch {
            com.cruxcoach.android.data.kilter.KilterPublishRetryWorker.runOnce(context)
            // Optimistic delay then refresh — WorkManager fires the
            // worker on a background thread; we don't have a deferred
            // observation hook here, so a small wait + re-read covers
            // the common case (sub-second worker runs). The user can
            // also re-tap retry which idempotently re-queues.
            kotlinx.coroutines.delay(2_000L)
            val stats = withContext(Dispatchers.IO) {
                runCatching { boardRepository.getKilterPublishQueueStats() }
                    .getOrElse { com.cruxcoach.data.repository.KilterPublishQueueStats(0, 0, null) }
            }
            _state.update {
                it.copy(kilterAccount = it.kilterAccount.copy(
                    publishRetryRunning = false,
                    publishPendingCount = stats.pendingCount,
                    publishFailedCount = stats.failedCount,
                    publishLastAttemptAtMs = stats.lastAttemptAtMs,
                ))
            }
        }
    }

    fun showKilterDisconnectConfirm() {
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(showDisconnectConfirm = true)) }
    }

    fun dismissKilterDisconnectConfirm() {
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(showDisconnectConfirm = false)) }
    }

    fun setKilterClimbPublishEnabled(enabled: Boolean) {
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(climbPublishEnabled = enabled)) }
        viewModelScope.launch { userPreferences.setKilterClimbPublishEnabled(enabled) }
        // Drain the retry queue immediately when the user enables the
        // toggle — without this, climbs published while opted-out wait up
        // to 6h for the next periodic tick. WorkManager.runOnce() is
        // network-gated so it harmlessly defers until connectivity if
        // we're offline at this moment.
        if (enabled) {
            com.cruxcoach.android.data.kilter.KilterPublishRetryWorker.runOnce(context)
        }
    }

    fun setKilterPushEnabled(enabled: Boolean) {
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(pushEnabled = enabled)) }
        viewModelScope.launch { userPreferences.setKilterPushEnabled(enabled) }
    }

    fun kilterDisconnect() {
        viewModelScope.launch {
            // Audit-trail: log the disconnect with a timestamp so post-hoc
            // triage of "I lost my Kilter login" or "my pending publishes
            // disappeared" reports can be matched against logcat.
            Log.i(TAG, "destructive: kilterDisconnect() requested at ${System.currentTimeMillis() / 1000}")
            kilterApiClient.revokeRefreshToken()
            kilterTokenStore.clear()
            userPreferences.setKilterSyncEnabled(false)
            _state.update { it.copy(kilterAccount = KilterAccountState()) }
            Log.i(TAG, "destructive: kilterDisconnect() done — token cleared, sync disabled")
        }
    }

    fun dismissKilterResult() {
        _state.update { it.copy(kilterAccount = it.kilterAccount.copy(resultMessage = null)) }
    }

    private companion object {
        const val TAG = "SettingsVM"
    }
}
