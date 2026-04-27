package com.cruxcoach.android.ui.board

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.AuroraBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.BleShareUiState
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.data.repository.AuroraAscentWithClimb
import com.cruxcoach.data.repository.AuroraClimbWithStats
import com.cruxcoach.data.repository.AuroraPlacement
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.AngleOption
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.ClimbList
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import com.cruxcoach.android.data.RestTimerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.cruxcoach.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.cruxcoach.android.util.PerfLogger
import javax.inject.Inject

enum class RoutePlaybackMode { MANUAL, AUTO }

/** Ascent logging dialog form state. */
data class AscentFormState(
    val showDialog: Boolean = false,
    val isSend: Boolean = true,
    val bidCount: Int = 1,
    val quality: Int = 0,
    val comment: String = "",
    val isBenchmark: Boolean = false,
    val editingUuid: String? = null,
    val deleteConfirmUuid: String? = null
)

/** Route/multi-frame playback state. */
data class PlaybackState(
    val allFrames: List<List<BoardHold>> = emptyList(),
    val currentFrameIndex: Int = 0,
    val totalFrames: Int = 1,
    val isRoute: Boolean = false,
    val mode: RoutePlaybackMode = RoutePlaybackMode.MANUAL,
    val isPlaying: Boolean = false,
    val isLooping: Boolean = false,
    val speedSec: Float = 5f,
    val showPreview: Boolean = false,
    val countdownSeconds: Int = 0
)

/** BLE send-to-board state. */
data class BoardSendState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isSending: Boolean = false,
    val success: Boolean = false,
    val error: String? = null
)

/** Climb list / favorites dialog state. */
data class ListDialogState(
    val show: Boolean = false,
    val lists: List<ClimbList> = emptyList(),
    val climbInListIds: Set<Long> = emptySet(),
    val newListName: String = ""
)

/** Nearby climb sharing debug state (send controller writes debug info here). */
data class NearbySharingState(
    val isAdvertising: Boolean = false,
    val debugInfo: String = ""
)

data class ClimbDetailState(
    val isLoading: Boolean = true,
    val climb: AuroraClimbWithStats? = null,
    val holds: List<BoardHold> = emptyList(),
    val placements: Map<Int, AuroraPlacement> = emptyMap(),
    val boardSize: BoardSize? = null,
    val boardImages: List<BoardImage> = emptyList(),
    val userAscents: List<AuroraAscentWithClimb> = emptyList(),
    val angle: Int = 40,
    val ledColors: LedHoldColors = LedHoldColors(),
    val gradeScale: GradeScale = GradeScale.V_SCALE,
    val isMirrored: Boolean = false,
    val isFavorited: Boolean = false,
    val restTimerTotalSeconds: Int = 180,
    val restTimerAutoStart: Boolean = false,
    val zones: IntensityZones? = null,
    val availableAngles: List<AngleOption> = emptyList(),
    val error: String? = null,
    val ascent: AscentFormState = AscentFormState(),
    val playback: PlaybackState = PlaybackState(),
    val ble: BoardSendState = BoardSendState(),
    val listDialog: ListDialogState = ListDialogState(),
    val nearby: NearbySharingState = NearbySharingState()
)

@HiltViewModel
class BoardClimbDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val userPreferences: UserPreferences,
    private val bleConnection: AuroraBleConnection,
    private val sessionManager: BoardSessionManager,
    private val zoneManager: IntensityZoneManager,
    private val climbAdvertiser: ClimbBleAdvertiser,
    private val sessionQueueManager: com.cruxcoach.android.data.SessionQueueManager,
    private val bleShareManager: BleShareManager,
    private val kilterSyncEngine: com.cruxcoach.android.data.kilter.KilterSyncEngine,
    val climbNavState: com.cruxcoach.android.ui.navigation.ClimbNavigationState,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    /** Exposed for the pager to compute its initial page synchronously (before async load). */
    val initialClimbUuid: String = savedStateHandle["climbUuid"] ?: ""
    private var currentClimbUuid: String = initialClimbUuid
    private var currentAngle: Int = savedStateHandle.get<String>("angle")?.toIntOrNull() ?: 40

    private val _state = MutableStateFlow(ClimbDetailState(angle = currentAngle))
    val state: StateFlow<ClimbDetailState> = _state.asStateFlow()

    /** Single source of truth for BLE sharing UI (banner, nearby climbs). Collect in UI with collectAsStateWithLifecycle(). */
    val bleShareUiState: StateFlow<BleShareUiState> = bleShareManager.uiState

    val restTimerState: StateFlow<RestTimerState> = sessionManager.restTimer

    /** Derived boolean — only emits when rest timer starts/stops, not every 500ms tick. */
    val isRestTimerRunning: StateFlow<Boolean> = sessionManager.restTimer
        .map { it.isRunning }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, sessionManager.restTimer.value.isRunning)

    /** Derived boolean — only emits when sharing toggle changes. */
    val isSharingEnabled: StateFlow<Boolean> = bleShareManager.uiState
        .map { it.sharingEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, bleShareManager.uiState.value.sharingEnabled)

    // Per-page state cache for smooth pager swiping
    private val _pageCache = MutableStateFlow<Map<String, ClimbDetailState>>(emptyMap())
    val pageCache: StateFlow<Map<String, ClimbDetailState>> = _pageCache.asStateFlow()

    private var loadJob: Job? = null
    private var mirrorPlacementMap: Map<Int, Int> = emptyMap()
    private var originalAllFrames: List<List<BoardHold>> = emptyList()
    private var cachedPlacementMap: Map<Int, AuroraPlacement>? = null

    // --- Delegated controllers ---

    private val ascentLogger = AscentLogger(
        scope = viewModelScope,
        state = _state,
        personalBoardRepo = personalBoardRepo,
        sessionManager = sessionManager,
        zoneManager = zoneManager,
        climbNavState = climbNavState,
        currentClimbUuid = { currentClimbUuid },
        onAscentSaved = { isSend ->
            if (_state.value.restTimerAutoStart) startRestTimer()
            kilterSyncEngine.uploadNewAscentIfEnabled()
        }
    )

    private val playbackController = RoutePlaybackController(
        scope = viewModelScope,
        state = _state,
        userPreferences = userPreferences,
        onFrameChanged = { holds ->
            if (sendController.isConnected()) sendController.sendToBoard()
        }
    )

    private val sendController = BoardSendController(
        scope = viewModelScope,
        state = _state,
        boardRepository = boardRepository,
        bleConnection = bleConnection,
        userPreferences = userPreferences,
        climbAdvertiser = climbAdvertiser,
        sessionQueueManager = sessionQueueManager,
        isSharingEnabled = { bleShareManager.uiState.value.sharingEnabled }
    )

    init {
        PerfLogger.navMilestone("BoardClimbDetailVM.init start")
        viewModelScope.launch(Dispatchers.IO) {
            PerfLogger.traceSuspend("VM.init prefs") {
                val speed = userPreferences.routeFrameSpeed.first()
                val loop = userPreferences.routeAutoLoop.first()
                val restDuration = userPreferences.restTimerDurationSeconds.first()
                val restAutoStart = userPreferences.restTimerAutoStart.first()
                _state.update { it.copy(
                    playback = it.playback.copy(speedSec = speed, isLooping = loop),
                    restTimerTotalSeconds = restDuration,
                    restTimerAutoStart = restAutoStart
                ) }
            }
        }
        viewModelScope.launch {
            userPreferences.gradeScale.collect { scale ->
                _state.update { it.copy(gradeScale = scale) }
            }
        }
        viewModelScope.launch {
            userPreferences.ledHoldColors.collect { colors ->
                _state.update { it.copy(ledColors = colors) }
            }
        }
        viewModelScope.launch {
            // Track previous state to only auto-send on genuine first-connect,
            // not on SENDING->CONNECTED transitions (which caused a send loop on Android 9).
            var prevConnState = bleConnection.connectionState.value
            bleConnection.connectionState.collect { connState ->
                val wasDisconnectedOrConnecting = prevConnState == ConnectionState.DISCONNECTED
                    || prevConnState == ConnectionState.CONNECTING
                val justFinishedSending = prevConnState == ConnectionState.SENDING
                    && connState == ConnectionState.CONNECTED
                prevConnState = connState

                _state.update { it.copy(ble = it.ble.copy(connectionState = connState)) }

                if (connState == ConnectionState.CONNECTED
                    && wasDisconnectedOrConnecting
                    && _state.value.holds.isNotEmpty()
                ) {
                    sendToBoard()
                }

                // Quick-Send auto-disconnect for the "manually connected in
                // BoardBrowser → navigated into climb-detail → first send
                // fired" path. Boulders only: routes need the connection
                // alive for subsequent frames during playback, and route-
                // playback's onFrameChanged also writes through the same
                // SENDING→CONNECTED edge — disconnecting after frame 0
                // would strand mid-route.
                if (justFinishedSending && !_state.value.playback.isRoute) {
                    val quickSendOn = userPreferences.quickBoardSend.first()
                    if (quickSendOn) {
                        Log.i(TAG, "quick-send auto-disconnect after boulder send")
                        bleConnection.disconnect()
                    }
                }
                // Don't call clearClimb() here -- BleConnectionViewModel.onBoardDisconnected()
                // handles the transition to LAST_CLIMB advertising.
            }
        }
        viewModelScope.launch {
            zoneManager.zones.collect { zones ->
                _state.update { it.copy(zones = zones) }
            }
        }
        loadClimb(currentClimbUuid, currentAngle)
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun switchClimb(uuid: String, angle: Int) {
        if (uuid == currentClimbUuid && angle == currentAngle) return
        Log.d(TAG, "switchClimb: $uuid angle=$angle (was: $currentClimbUuid)")
        currentClimbUuid = uuid
        currentAngle = angle
        playbackController.stopPlayback()
        loadJob?.cancel()
        sendController.cancelSend()
        // Update nearby advertising immediately on swipe (before async load / state replacement)
        val sharingEnabled = bleShareManager.uiState.value.sharingEnabled
        val isConnected = bleConnection.connectionState.value == ConnectionState.CONNECTED
        Log.d(TAG, "switchClimb: climbSharingEnabled=$sharingEnabled connected=$isConnected")
        if (sharingEnabled && isConnected) {
            val result = climbAdvertiser.advertiseClimb(uuid, angle)
            Log.d(TAG, "switchClimb: advertiseClimb result=$result")
        }
        // Reset BLE send state so a stale isSending=true from the previous climb
        // doesn't block auto-send for the new climb.
        val currentConn = bleConnection.connectionState.value
        // Use cached page state if available to avoid loading flash during pager swipe
        val cached = _pageCache.value[uuid]
        if (cached != null) {
            _state.update { current -> cached.copy(
                ascent = AscentFormState(),
                listDialog = ListDialogState(),
                ble = BoardSendState(connectionState = currentConn),
                nearby = current.nearby
            ) }
        } else {
            _state.update { it.copy(
                isLoading = true,
                error = null,
                isMirrored = false,
                ble = BoardSendState(connectionState = currentConn),
                playback = it.playback.copy(showPreview = false),
                ascent = AscentFormState(),
                listDialog = it.listDialog.copy(show = false)
            ) }
        }
        loadClimb(uuid, angle, advertise = false) // switchClimb already called advertiseClimb
    }

    fun onAngleSelected(angle: Int) {
        if (angle == currentAngle) return
        loadClimb(currentClimbUuid, angle)
    }

    private fun loadClimb(uuid: String, angle: Int, advertise: Boolean = true) {
        loadJob = viewModelScope.launch {
            try {
                PerfLogger.navMilestone("loadClimb start ($uuid)")
                withContext(Dispatchers.IO) {
                    // Try exact match first, then case variants (DB may store upper/lowercase)
                    val climb = PerfLogger.trace("loadClimb.getClimbByUuid") {
                        boardRepository.getClimbByUuid(uuid, angle)
                            ?: boardRepository.getClimbByUuid(uuid.lowercase(), angle)
                            ?: boardRepository.getClimbByUuid(uuid.uppercase(), angle)
                    }
                    if (climb != null) {
                        val allFrames = BoardClimbParser.parseMultiFrames(climb.frames)
                        val isRoute = allFrames.size > 1
                        val holds = allFrames.firstOrNull() ?: emptyList()

                        val placementMap = PerfLogger.trace("loadClimb.placements") {
                            cachedPlacementMap ?: run {
                                val map = boardRepository.getAllPlacements().associateBy { it.placementId.toInt() }
                                cachedPlacementMap = map
                                map
                            }
                        }
                        val prefSizeId = userPreferences.boardProductSizeId.first()
                        val prefLayoutId = userPreferences.boardLayoutId.first()
                        val boardSize = boardRepository.getProductSize(prefSizeId)
                        val boardImages = boardRepository.getBoardImages(
                            prefSizeId, prefLayoutId
                        )
                        val userAscents = PerfLogger.trace("loadClimb.userHistory") {
                            personalBoardRepo.getUserHistoryForClimb(uuid)
                        }
                        val isFavorited = personalBoardRepo.isClimbFavorited(uuid)
                        val angles = boardRepository.getAnglesForClimb(uuid)

                        mirrorPlacementMap = PerfLogger.trace("loadClimb.mirrorMap") {
                            boardRepository.getMirrorPlacementMap(prefSizeId).ifEmpty {
                                computeMirrorMapFromPlacements(placementMap, boardSize)
                            }
                        }
                        originalAllFrames = allFrames

                        val useSetterSpeed = userPreferences.routeUseSetterSpeed.first()
                        val speedOverride = if (useSetterSpeed && isRoute && climb.framesPace > 0) {
                            climb.framesPace.toFloat() / 1000f
                        } else null

                        _state.update { s ->
                            s.copy(
                                isLoading = false,
                                climb = climb,
                                holds = holds,
                                placements = placementMap,
                                boardSize = boardSize,
                                boardImages = boardImages,
                                userAscents = userAscents,
                                angle = angle,
                                isFavorited = isFavorited,
                                availableAngles = angles,
                                playback = s.playback.copy(
                                    allFrames = allFrames,
                                    currentFrameIndex = 0,
                                    totalFrames = allFrames.size,
                                    isRoute = isRoute,
                                    speedSec = speedOverride ?: s.playback.speedSec
                                )
                            )
                        }
                        _pageCache.update { it + (uuid to _state.value) }
                        PerfLogger.navMilestone("loadClimb complete ($uuid)")
                        if (advertise) sendController.updateNearbyAdvertising(uuid, angle)
                        if (sendController.isConnected()) sendController.sendToBoard()
                    } else {
                        _state.update { it.copy(isLoading = false, error = context.getString(R.string.error_climb_not_found, uuid, angle)) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /** Preloads a climb's visual data into the page cache for smooth pager swiping. */
    fun preloadClimb(uuid: String, angle: Int) {
        if (_pageCache.value.containsKey(uuid)) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val climb = boardRepository.getClimbByUuid(uuid, angle) ?: return@withContext
                    val allFrames = BoardClimbParser.parseMultiFrames(climb.frames)
                    val isRoute = allFrames.size > 1
                    val holds = allFrames.firstOrNull() ?: emptyList()
                    val placementMap = cachedPlacementMap ?: run {
                        val map = boardRepository.getAllPlacements().associateBy { it.placementId.toInt() }
                        cachedPlacementMap = map
                        map
                    }
                    val prefSizeId = userPreferences.boardProductSizeId.first()
                    val prefLayoutId = userPreferences.boardLayoutId.first()
                    val boardSize = boardRepository.getProductSize(prefSizeId)
                    val boardImages = boardRepository.getBoardImages(
                        prefSizeId, prefLayoutId
                    )
                    val userAscents = personalBoardRepo.getUserHistoryForClimb(uuid)
                    val isFavorited = personalBoardRepo.isClimbFavorited(uuid)
                    val angles = boardRepository.getAnglesForClimb(uuid)

                    val pageState = _state.value.copy(
                        isLoading = false,
                        climb = climb,
                        holds = holds,
                        placements = placementMap,
                        boardSize = boardSize,
                        boardImages = boardImages,
                        userAscents = userAscents,
                        angle = angle,
                        isFavorited = isFavorited,
                        availableAngles = angles,
                        isMirrored = false,
                        error = null,
                        ascent = AscentFormState(),
                        listDialog = ListDialogState(),
                        playback = _state.value.playback.copy(
                            allFrames = allFrames,
                            currentFrameIndex = 0,
                            totalFrames = allFrames.size,
                            isRoute = isRoute,
                            showPreview = false,
                            isPlaying = false
                        )
                    )
                    _pageCache.update { it + (uuid to pageState) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "preloadClimb failed for $uuid", e)
            }
        }
    }

    // --- Ascent delegation ---

    fun showAscentDialog() = ascentLogger.showDialog()
    fun dismissAscentDialog() = ascentLogger.dismissDialog()
    fun updateAscentIsSend(isSend: Boolean) = ascentLogger.updateIsSend(isSend)
    fun updateAscentBidCount(count: Int) = ascentLogger.updateBidCount(count)
    fun updateAscentQuality(quality: Int) = ascentLogger.updateQuality(quality)
    fun updateAscentComment(comment: String) = ascentLogger.updateComment(comment)
    fun updateAscentIsBenchmark(value: Boolean) {
        _state.update { it.copy(ascent = it.ascent.copy(isBenchmark = value)) }
    }
    fun saveAscent() = ascentLogger.save()
    fun editAscent(ascent: AuroraAscentWithClimb) = ascentLogger.edit(ascent)
    fun requestDeleteAscent(uuid: String) = ascentLogger.requestDelete(uuid)
    fun dismissDeleteConfirm() = ascentLogger.dismissDeleteConfirm()
    fun confirmDeleteAscent() = ascentLogger.confirmDelete()

    // --- Playback delegation ---

    fun goToFrame(index: Int) = playbackController.goToFrame(index)
    fun nextFrame() = playbackController.nextFrame()
    fun previousFrame() = playbackController.previousFrame()
    fun startPlayback() = playbackController.startPlayback()
    fun stopPlayback() = playbackController.stopPlayback()
    fun toggleLoop() = playbackController.toggleLoop()
    fun togglePreview() = playbackController.togglePreview()
    fun updatePlaybackSpeed(seconds: Float) = playbackController.updateSpeed(seconds)

    // --- BLE send delegation ---

    fun sendToBoard() = sendController.sendToBoard()

    // --- Favorites & lists ---

    fun toggleFavorite() {
        viewModelScope.launch {
            val newState = withContext(Dispatchers.IO) {
                personalBoardRepo.toggleFavorite(currentClimbUuid)
            }
            _state.update { it.copy(isFavorited = newState) }
        }
    }

    fun toggleMirror() {
        val s = _state.value
        val newMirrored = !s.isMirrored
        val frames = if (newMirrored) {
            originalAllFrames.map { frame -> mirrorHolds(frame) }
        } else {
            originalAllFrames
        }
        val holds = if (s.playback.showPreview) {
            frames.flatten()
        } else {
            frames.getOrElse(s.playback.currentFrameIndex) { emptyList() }
        }
        _state.update { it.copy(
            isMirrored = newMirrored,
            holds = holds,
            playback = it.playback.copy(allFrames = frames)
        ) }
        if (sendController.isConnected()) sendController.sendToBoard()
    }

    private fun mirrorHolds(holds: List<BoardHold>): List<BoardHold> {
        return holds.map { hold ->
            val mirroredId = mirrorPlacementMap[hold.placementId]
            if (mirroredId != null) hold.copy(placementId = mirroredId) else hold
        }
    }

    private fun computeMirrorMapFromPlacements(
        placements: Map<Int, AuroraPlacement>,
        boardSize: BoardSize?
    ): Map<Int, Int> {
        if (boardSize == null || placements.isEmpty()) return emptyMap()
        val centerX2 = boardSize.edgeLeft + boardSize.edgeRight
        val byYAndSet = placements.values.groupBy { it.y to it.setId }
        val result = mutableMapOf<Int, Int>()
        for (placement in placements.values) {
            val mirrorX = centerX2 - placement.x
            val candidates = byYAndSet[placement.y to placement.setId] ?: continue
            val mirror = candidates.find { it.x == mirrorX }
            if (mirror != null && mirror.placementId != placement.placementId) {
                result[placement.placementId.toInt()] = mirror.placementId.toInt()
            }
        }
        return result
    }

    fun showAddToListDialog() {
        viewModelScope.launch {
            val lists = withContext(Dispatchers.IO) {
                personalBoardRepo.ensureFavoritesListExists()
                personalBoardRepo.getAllClimbLists()
            }
            val inListIds = withContext(Dispatchers.IO) {
                personalBoardRepo.getListIdsForClimb(currentClimbUuid)
            }
            _state.update { it.copy(listDialog = ListDialogState(
                show = true, lists = lists, climbInListIds = inListIds
            )) }
        }
    }

    fun dismissAddToListDialog() {
        _state.update { it.copy(listDialog = it.listDialog.copy(show = false)) }
        viewModelScope.launch {
            val isFav = withContext(Dispatchers.IO) { personalBoardRepo.isClimbFavorited(currentClimbUuid) }
            _state.update { it.copy(isFavorited = isFav) }
        }
    }

    fun toggleClimbInList(listId: Long) {
        viewModelScope.launch {
            val currentlyIn = _state.value.listDialog.climbInListIds.contains(listId)
            withContext(Dispatchers.IO) {
                if (currentlyIn) {
                    personalBoardRepo.removeClimbFromList(listId, currentClimbUuid)
                } else {
                    personalBoardRepo.addClimbToList(listId, currentClimbUuid)
                }
            }
            val newIds = if (currentlyIn) {
                _state.value.listDialog.climbInListIds - listId
            } else {
                _state.value.listDialog.climbInListIds + listId
            }
            _state.update { it.copy(listDialog = it.listDialog.copy(climbInListIds = newIds)) }
        }
    }

    fun updateNewListName(name: String) {
        _state.update { it.copy(listDialog = it.listDialog.copy(newListName = name)) }
    }

    fun createNewListAndAdd() {
        val name = _state.value.listDialog.newListName.trim()
        if (name.isBlank()) return
        val existing = _state.value.listDialog.lists.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) {
            toggleClimbInList(existing.id)
            _state.update { it.copy(listDialog = it.listDialog.copy(newListName = "")) }
            return
        }
        viewModelScope.launch {
            val newListId = withContext(Dispatchers.IO) {
                val id = personalBoardRepo.createClimbList(name)
                personalBoardRepo.addClimbToList(id, currentClimbUuid)
                id
            }
            val updatedLists = withContext(Dispatchers.IO) { personalBoardRepo.getAllClimbLists() }
            _state.update { it.copy(listDialog = it.listDialog.copy(
                lists = updatedLists,
                climbInListIds = it.listDialog.climbInListIds + newListId,
                newListName = ""
            )) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Don't touch the advertiser here. BleConnectionViewModel fully manages
        // the advertising lifecycle (connected -> climb -> lastClimb -> gone).
    }

    // --- Rest timer (delegates to singleton BoardSessionManager) ---

    fun startRestTimer() {
        sessionManager.startRestTimer(_state.value.restTimerTotalSeconds)
    }

    fun cancelRestTimer() {
        sessionManager.cancelRestTimer()
    }

    fun dismissRestTimerFinished() {
        sessionManager.dismissRestTimerFinished()
    }

    fun requestSetterFilter(setter: String) {
        climbNavState.pendingSetterFilter = setter
    }

    companion object {
        private const val TAG = "BoardClimbDetailVM"
    }
}
