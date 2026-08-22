package com.cruxcoach.android.ui.board

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.ble.BoardControllerProfiles
import com.cruxcoach.android.ble.BoardProjectionPolicy
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.BleShareUiState
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.data.repository.brand
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.AngleOption
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.Climb_lists
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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
import java.util.concurrent.ConcurrentHashMap
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

/** One-shot confirmation for an icon-dock quick log. */
data class QuickLogFeedback(
    val eventId: String,
    val entryUuid: String,
    val isSend: Boolean,
)

enum class PersonalNoteSaveStatus { IDLE, SAVING, SAVED, FAILED }

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
    val countdownSeconds: Int = 0,
    /**
     * False while something else owns the wall — today a running playlist.
     * Playback would still animate on screen and reach nothing.
     */
    val canReachBoard: Boolean = true,
)

/** BLE send-to-board state. */
data class BoardSendState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedViaRelay: Boolean = false,
    val connectedViaMesh: Boolean = false,
    val hostedRelayClientCount: Int = 0,
    val isSending: Boolean = false,
    val success: Boolean = false,
    /** Localized error as a string-resource id (resolved at the UI layer),
     *  not raw text — keeps BLE send errors out of hardcoded German and
     *  prevents raw exception messages leaking into the climber-facing send
     *  status. Null = no error. */
    @androidx.annotation.StringRes val error: Int? = null,
    /** Non-blocking post-send warning (string-resource id), e.g. holds
     *  without an LED mapping on the configured board size — the send
     *  succeeded but the wall shows a partial climb. Null = no warning. */
    @androidx.annotation.StringRes val warning: Int? = null
)

/** Climb list / favorites dialog state. */
data class ListDialogState(
    val show: Boolean = false,
    val lists: List<Climb_lists> = emptyList(),
    val climbInListIds: Set<Long> = emptySet(),
    val newListName: String = ""
)

/** Nearby climb sharing debug state (send controller writes debug info here). */
data class NearbySharingState(
    val isAdvertising: Boolean = false,
    val debugInfo: String = ""
)

/**
 * Display data for the climb's setter, resolved through the
 * `display_name → setter_username → npub:short` fallback chain. Only
 * populated for climbs with `origin='cruxcoach'`; Kilter-Original climbs
 * keep the existing setter_username display path (no Kind 0 lookup).
 */
data class SetterProfile(
    /** Resolved display name — display_name from Kind 0, else setter_username,
     *  else `npub:<short>` derived from pubkey. Always non-blank. */
    val displayName: String,
    /** Optional avatar URL from Kind 0 `picture` field. */
    val pictureUrl: String?,
    /** True for `origin='cruxcoach'` climbs — UI shows the "CruxCoach" badge. */
    val isCommunity: Boolean,
)

/**
 * Minimal "known only from the logbook" fallback. Populated when a climb
 * the user has a local Kilter ascent for is absent from the curated board
 * DB (it lives only in Kilter's new PowerSync world — dashed-lowercase
 * uuid, never mirrored into our board DB nor present in BoardSesh). Rather
 * than dead-ending on [ClimbDetailState.error], the detail screen renders
 * what the ascent rows already carry. The diagnostic uuid/angle string is
 * NOT surfaced here — only in the truly-unknown (no-history) error path.
 */
data class LogbookOnlyState(
    val uuid: String,
    val ascents: List<AscentWithClimb>,
)

/**
 * Overlays everything that belongs to the DEVICE, not the climb, onto a cached
 * page.
 *
 * [BoardClimbDetailViewModel.preloadClimb] snapshots the WHOLE state, so a
 * cached page also carries the send mode, the board connection and the palette
 * as they were at preload time. Restoring it verbatim rolled those back — a
 * page preloaded before the send mode resolved came back as AUTOMATIC and the
 * explicit-send button stayed gone until the preference changed again (its
 * collector only emits on change).
 */
internal fun ClimbDetailState.withLiveDeviceState(live: ClimbDetailState): ClimbDetailState =
    copy(
        boardSendMode = live.boardSendMode,
        ble = live.ble,
        nearby = live.nearby,
        gradeScale = live.gradeScale,
        ledColors = live.ledColors,
        restTimerTotalSeconds = live.restTimerTotalSeconds,
        restTimerAutoStart = live.restTimerAutoStart,
        zones = live.zones,
        currentUserPubkey = live.currentUserPubkey,
        boardLayers = live.boardLayers,
        selectedBoardLayerSlot = live.selectedBoardLayerSlot,
        selectedBoardLayerColor = live.selectedBoardLayerColor,
    )

data class ClimbDetailState(
    val isLoading: Boolean = true,
    val climb: ClimbWithStats? = null,
    /** Non-null when the climb is absent from the board DB but the user has
     *  local logbook history for it — drives the friendly logbook-only
     *  fallback instead of [error]. */
    val logbookOnly: LogbookOnlyState? = null,
    val holds: List<BoardHold> = emptyList(),
    val placements: Map<Int, BoardPlacement> = emptyMap(),
    val boardSize: BoardSize? = null,
    val boardImages: List<BoardImage> = emptyList(),
    val userAscents: List<AscentWithClimb> = emptyList(),
    val angle: Int = 40,
    val ledColors: LedHoldColors = LedHoldColors(),
    val gradeScale: GradeScale = GradeScale.V_SCALE,
    val isMirrored: Boolean = false,
    /** Whether the active climb's layout is left-right symmetric (Aurora
     *  `layouts.is_mirrored`). Gates the mirror toggle so it never appears on a
     *  non-mirrorable layout (Tension TB2 Spray, Kilter, MoonBoard, …). Resolved
     *  per climb via [BoardConstants.isLayoutMirrorable]. */
    val isMirrorable: Boolean = false,
    val isFavorited: Boolean = false,
    /** Whether this climb is on the user's built-in "Ignored" list — drives
     *  the overflow menu's ignore/un-ignore action. */
    val isIgnored: Boolean = false,
    /** Private note stored in the encrypted per-user database. */
    val personalNote: String = "",
    /** Unsaved editor content; retained across sheet dismissal and save failures. */
    val personalNoteDraft: String = "",
    val personalNoteSaveStatus: PersonalNoteSaveStatus = PersonalNoteSaveStatus.IDLE,
    val restTimerTotalSeconds: Int = 180,
    val restTimerAutoStart: Boolean = false,
    val zones: IntensityZones? = null,
    val availableAngles: List<AngleOption> = emptyList(),
    val error: String? = null,
    val ascent: AscentFormState = AscentFormState(),
    val isQuickLogging: Boolean = false,
    val quickLogFeedback: QuickLogFeedback? = null,
    val quickLogFailed: Boolean = false,
    val playback: PlaybackState = PlaybackState(),
    val ble: BoardSendState = BoardSendState(),
    val boardSendMode: BoardSendMode = BoardSendMode.AUTOMATIC,
    /** Live physical-board layers. Quantum currently supplies four; other
     * boards remain a single projection through the same capability model. */
    val boardLayers: BoardLayerState = BoardLayerState(),
    val selectedBoardLayerSlot: Int? = null,
    val selectedBoardLayerColor: Int? = null,
    val listDialog: ListDialogState = ListDialogState(),
    val nearby: NearbySharingState = NearbySharingState(),
    /** Hex pubkey of the local NostrSigner. Used by the UI to gate
     *  edit-this-climb actions to the original setter only. */
    val currentUserPubkey: String? = null,
    /** Setter display info — null for non-CruxCoach climbs. Populated
     *  asynchronously after [climb] loads (Kind 0 lookup via
     *  NostrProfileManager). Composables must not block on this — fall
     *  back to the climb's `setterUsername` while loading. */
    val setterProfile: SetterProfile? = null,
    /** Confirm-delete dialog visibility + the Kilter publish state at
     *  the moment the user opened it (drives the variant text:
     *  "manual cleanup on Kilter required" when true). */
    val communityDeleteDialog: CommunityDeleteDialogState? = null,
    /** Confirm-delete dialog for the *draft* path — local-only removal
     *  with no Nostr / Blossom round-trip. Routed to instead of
     *  [communityDeleteDialog] when the climb has never been published
     *  (sync_status='draft'/'failed'/NULL); avoids the
     *  "Veröffentlichung löschen" menu calling
     *  CommunityClimbDeleter for a row with no d-tag, which used to
     *  surface as a confusing "Löschen fehlgeschlagen" snackbar. */
    val draftDeleteDialog: DraftDeleteDialogState? = null,
    /** One-shot feedback from the most recent delete attempt. UI
     *  consumes via [BoardClimbDetailViewModel.consumeCommunityDeleteFeedback]. */
    val communityDeleteFeedback: CommunityDeleteFeedback? = null,
    /** Authorship-gated own-Kilter-climb publish: true only when the
     *  CONNECTED Kilter account authored this climb (kilter_author_uuid
     *  identity match, never display name) AND it has not been published
     *  to the CruxCoach community yet. Drives the overflow action. */
    val canPublishAsMine: Boolean = false,
    /** In-flight flag for the own-climb publish (disables the action). */
    val isOwnPublishInProgress: Boolean = false,
    /** One-shot feedback from the most recent own-climb publish attempt.
     *  UI consumes via [BoardClimbDetailViewModel.consumeOwnPublishFeedback]. */
    val ownPublishFeedback: OwnPublishFeedback? = null,
)

/** Snackbar-mapped outcomes of the own-Kilter-climb community publish. */
sealed interface OwnPublishFeedback {
    data object Published : OwnPublishFeedback
    /** No Nostr identity yet — nudge the user to set up their key. */
    data object NoNostrIdentity : OwnPublishFeedback
    /** Authorship gate refused (backstop — the action is hidden then). */
    data object NotAuthor : OwnPublishFeedback
    data object AlreadyPublished : OwnPublishFeedback
    data object Failed : OwnPublishFeedback
}

data class CommunityDeleteDialogState(
    val uuid: String,
    val kilterAlsoPublished: Boolean,
    val isInProgress: Boolean = false,
)

data class DraftDeleteDialogState(
    val uuid: String,
    val name: String,
    val isInProgress: Boolean = false,
)

sealed interface CommunityDeleteFeedback {
    data class Done(
        val attempted: Int,
        val accepted: Int,
        val kilterAlsoPublished: Boolean,
    ) : CommunityDeleteFeedback
    object NotOwner : CommunityDeleteFeedback
    object NotOurClimb : CommunityDeleteFeedback
    object NotFound : CommunityDeleteFeedback
    object Failed : CommunityDeleteFeedback
    /** Relay delete went out but local SQLite write threw — UI should
     *  warn the user to clear local state manually (relay-permanent +
     *  local-still-visible asymmetry). */
    data class LocalTombstoneFailed(
        val attempted: Int,
        val accepted: Int,
        val kilterAlsoPublished: Boolean,
    ) : CommunityDeleteFeedback
}

@HiltViewModel
class BoardClimbDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val userPreferences: UserPreferences,
    private val bleConnection: BoardBleConnection,
    private val boardLayerManager: BoardLayerManager,
    private val sessionManager: BoardSessionManager,
    private val zoneManager: IntensityZoneManager,
    private val climbAdvertiser: ClimbBleAdvertiser,
    private val sessionQueueManager: com.cruxcoach.android.data.SessionQueueManager,
    private val cruxRelayManager: com.cruxcoach.android.data.CruxRelayManager,
    private val bleShareManager: BleShareManager,
    private val kilterSyncEngine: com.cruxcoach.android.data.kilter.KilterSyncEngine,
    private val nostrSigner: com.cruxcoach.android.nostr.NostrSigner,
    private val nostrProfileManager: com.cruxcoach.android.payment.NostrProfileManager,
    private val communityClimbDeleter: com.cruxcoach.android.community.CommunityClimbDeleter,
    private val ownClimbPublisher: com.cruxcoach.android.community.OwnKilterClimbPublisher,
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
    private val personalNoteSaveJobs = ConcurrentHashMap<String, Job>()
    private val personalNoteDrafts = ConcurrentHashMap<String, String>()
    private val personalNoteSaveStatuses = ConcurrentHashMap<String, PersonalNoteSaveStatus>()
    private var mirrorPlacementMap: Map<Int, Int> = emptyMap()
    private var originalAllFrames: List<List<BoardHold>> = emptyList()
    private var cachedPlacementMap: Map<Int, BoardPlacement>? = null
    private val supportedAnglesCache = ConcurrentHashMap<Pair<Int, String>, Set<Int>>()

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
        onFrameChanged = {
            // Frame navigation/playback is already an explicit user command.
            // Once started, every frame must reach the board regardless of the
            // saved climb-selection send mode — but only when a send can get
            // there at all. Asking isConnected() alone let the animation run
            // through while a session queue swallowed every single frame.
            if (sendController.canSendToBoard()) sendController.sendToBoard(automaticLayer = true)
        }
    )

    private val sendController = BoardSendController(
        scope = viewModelScope,
        state = _state,
        boardRepository = boardRepository,
        personalBoardRepo = personalBoardRepo,
        bleConnection = bleConnection,
        userPreferences = userPreferences,
        climbAdvertiser = climbAdvertiser,
        sessionQueueManager = sessionQueueManager,
        isSharingEnabled = { bleShareManager.uiState.value.sharingEnabled },
        boardLayerManager = boardLayerManager,
    )

    init {
        PerfLogger.navMilestone("BoardClimbDetailVM.init start")
        viewModelScope.launch {
            boardLayerManager.state.collect { layers ->
                _state.update { current -> current.copy(boardLayers = layers) }
            }
        }
        viewModelScope.launch {
            bleConnection.quantumControllerState.collect { controller ->
                if (controller.authoritative) {
                    boardLayerManager.reconcile(controller.players)
                }
            }
        }
        // Re-load the displayed climb whenever a creator-side mutation happens
        // (e.g. an edit/publish from the editor opened on top of this screen).
        // The nav entry can stay RESUMED behind the editor, so a lifecycle
        // trigger wouldn't re-fire — collect the reactive revision instead.
        // drop(1) skips the replayed initial value.
        viewModelScope.launch {
            climbNavState.creatorRevision.drop(1).collect {
                loadClimb(currentClimbUuid, currentAngle)
            }
        }
        // Each init-coroutine is wrapped in try/catch so a DataStore
        // read failure or a flow-collection throw on one stream doesn't
        // silently kill the entire VM init and leave subsequent flow
        // updates lost.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                PerfLogger.traceSuspend("VM.init prefs") {
                    val speed = userPreferences.routeFrameSpeed.first()
                    val loop = userPreferences.routeAutoLoop.first()
                    val restDuration = userPreferences.restTimerDurationSeconds.first()
                    val restAutoStart = userPreferences.restTimerAutoStart.first()
                    val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
                    _state.update { it.copy(
                        playback = it.playback.copy(speedSec = speed, isLooping = loop),
                        restTimerTotalSeconds = restDuration,
                        restTimerAutoStart = restAutoStart,
                        currentUserPubkey = pubkey,
                    ) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "init prefs read failed; sticking with defaults", e)
            }
        }
        viewModelScope.launch {
            cruxRelayManager.state.collect { relayState ->
                _state.update {
                    it.copy(
                        ble = it.ble.copy(hostedRelayClientCount = relayState.clientCount),
                    )
                }
            }
        }
        viewModelScope.launch {
            var previouslyViaMesh = false
            com.cruxcoach.android.boardcell.BoardCellManager.current?.snapshots?.collect { snapshot ->
                val viaMesh = com.cruxcoach.android.boardcell.BoardCellManager.current?.canSendViaMesh() == true
                val sharedMesh = (snapshot?.members?.size ?: 0) > 1
                val meshMode = if (sharedMesh) runCatching {
                    userPreferences.multiConnectionBoardSendMode.first()
                }.getOrNull() else null
                _state.update { state -> state.copy(
                    boardSendMode = meshMode ?: state.boardSendMode,
                    ble = state.ble.copy(connectedViaMesh = viaMesh),
                ) }
                if (viaMesh && !previouslyViaMesh) sendAutomaticallyIfEnabled()
                previouslyViaMesh = viaMesh
            }
        }
        viewModelScope.launch {
            try {
                userPreferences.gradeScale.collect { scale ->
                    _state.update { it.copy(gradeScale = scale) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "gradeScale collect terminated", e)
            }
        }
        viewModelScope.launch {
            try {
                userPreferences.ledHoldColors.collect { colors ->
                    _state.update { it.copy(ledColors = colors) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ledHoldColors collect terminated", e)
            }
        }
        viewModelScope.launch {
            try {
                var previousCapacity = BoardControllerProfiles
                    .forBoard(bleConnection.connectedBoard)
                    .connectionCapacity
                var previousResolvedMode = _state.value.boardSendMode
                combine(
                    userPreferences.singleConnectionBoardSendMode,
                    userPreferences.multiConnectionBoardSendMode,
                    bleConnection.connectedBoardDescriptor,
                    cruxRelayManager.state,
                    com.cruxcoach.android.boardcell.BoardCellManager.current?.snapshots
                        ?: kotlinx.coroutines.flow.flowOf(null),
                ) { singleMode, multiMode, board, relayState, meshSnapshot ->
                    val capacity = BoardControllerProfiles.forBoard(board).connectionCapacity
                    BoardSendModePolicy.resolve(
                        connectionCapacity = capacity,
                        singleConnectionMode = singleMode,
                        multiConnectionMode = multiMode,
                        hostingForOthers = relayState.clientCount > 0,
                        meshParticipant = (meshSnapshot?.members?.size ?: 0) > 1,
                    ) to capacity
                }.distinctUntilChanged().collect { (mode, capacity) ->
                    val shouldAutoSend = BoardSendModePolicy
                        .shouldAutoSendAfterCapacityResolution(
                            previousCapacity = previousCapacity,
                            currentCapacity = capacity,
                            previousResolvedMode = previousResolvedMode,
                            resolvedMode = mode,
                        )
                    previousCapacity = capacity
                    previousResolvedMode = mode
                    _state.update { it.copy(boardSendMode = mode) }
                    if (shouldAutoSend &&
                        bleConnection.connectionState.value == ConnectionState.CONNECTED
                    ) {
                        sendAutomaticallyIfEnabled()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "capacity-specific board send mode collect terminated", e)
            }
        }
        // Keep the playback controls honest about whether a frame can land.
        // Both inputs matter: the link can drop, and a playlist can take the
        // wall over while this screen is open.
        viewModelScope.launch {
            try {
                kotlinx.coroutines.flow.combine(
                    bleConnection.connectionState,
                    sessionQueueManager.state,
                ) { _, _ -> sendController.canSendToBoard() }
                    .distinctUntilChanged()
                    .collect { canReach ->
                        _state.update {
                            it.copy(playback = it.playback.copy(canReachBoard = canReach))
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "canReachBoard collect terminated", e)
            }
        }
        viewModelScope.launch {
            try {
                // Track previous state to only auto-send on genuine first-connect,
                // not on SENDING->CONNECTED transitions (which caused a send loop on Android 9).
                var prevConnState = bleConnection.connectionState.value
                bleConnection.connectionState.collect { connState ->
                    val wasDisconnectedOrConnecting = prevConnState == ConnectionState.DISCONNECTED
                        || prevConnState == ConnectionState.CONNECTING
                    val justFinishedSending = prevConnState == ConnectionState.SENDING
                        && connState == ConnectionState.CONNECTED
                    prevConnState = connState

                    _state.update {
                        it.copy(
                            ble = it.ble.copy(
                                connectionState = connState,
                                connectedViaRelay = connState != ConnectionState.DISCONNECTED &&
                                    bleConnection.connectedBoard?.isCruxRelay == true,
                            ),
                        )
                    }

                    val climbState = _state.value
                    if (connState == ConnectionState.CONNECTED &&
                        wasDisconnectedOrConnecting &&
                        BoardProjectionPolicy.hasSendablePayload(
                            brand = climbState.climb?.brand,
                            holdCount = climbState.holds.size,
                            frames = climbState.climb?.frames,
                        )
                    ) {
                        sendAutomaticallyIfEnabled()
                    }

                    // Auto-disconnect after a send is now driven entirely by
                    // BoardBleConnection's idle timer (Settings → BLE auto-
                    // disconnect). The send path re-arms it from its finally
                    // block so the timer never fires mid-send.
                    // Don't call clearClimb() here -- BleConnectionViewModel.onBoardDisconnected()
                    // handles the transition to LAST_CLIMB advertising.
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "BLE connectionState collect terminated", e)
            }
        }
        viewModelScope.launch {
            try {
                zoneManager.zones.collect { zones ->
                    _state.update { it.copy(zones = zones) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "zones collect terminated", e)
            }
        }
        loadClimb(currentClimbUuid, currentAngle)
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** Open the confirm-delete dialog for the currently-displayed climb.
     *  No-op when the climb isn't an own published cruxcoach climb —
     *  the UI already gates the action button on the same predicate, but
     *  we re-check here so an out-of-band caller can't bypass it. */
    fun requestCommunityDelete() {
        val climb = _state.value.climb ?: return
        val signer = _state.value.currentUserPubkey ?: return
        if (climb.origin != "cruxcoach") return
        if (climb.createdByPubkey != signer) return
        _state.update {
            it.copy(
                communityDeleteDialog = CommunityDeleteDialogState(
                    uuid = climb.uuid,
                    kilterAlsoPublished = climb.kilterStatus == "synced",
                ),
            )
        }
    }

    fun dismissCommunityDeleteDialog() {
        _state.update { it.copy(communityDeleteDialog = null) }
    }

    /**
     * Open the local-only delete confirm for a draft climb (sync_status
     * 'draft' / 'failed' / NULL — never made it to a relay). The
     * actual deletion is gated at the SQL layer too via
     * [com.cruxcoach.data.repository.BoardRepository.deleteLocalClimb]
     * (`source='local' AND nostr_event_id IS NULL`), so even a UI bug
     * that opens this for a published row would silently no-op
     * server-side.
     */
    fun requestDraftDelete() {
        val climb = _state.value.climb ?: return
        val signer = _state.value.currentUserPubkey ?: return
        if (climb.origin != "cruxcoach") return
        if (climb.createdByPubkey != signer) return
        _state.update {
            it.copy(
                draftDeleteDialog = DraftDeleteDialogState(
                    uuid = climb.uuid,
                    name = climb.name,
                ),
            )
        }
    }

    fun dismissDraftDeleteDialog() {
        _state.update { it.copy(draftDeleteDialog = null) }
    }

    /** Run a draft (local-only) deletion. The repository call is
     *  idempotent and gated by `source='local' AND nostr_event_id IS
     *  NULL` so it's safe even on a sync race. [onDeleted] is invoked
     *  on success so the screen can pop back. */
    fun confirmDraftDelete(onDeleted: () -> Unit) {
        val dialog = _state.value.draftDeleteDialog ?: return
        if (dialog.isInProgress) return
        _state.update { it.copy(draftDeleteDialog = dialog.copy(isInProgress = true)) }
        viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    boardRepository.deleteLocalClimb(dialog.uuid)
                }
                true
            }.getOrElse { e ->
                Log.w(TAG, "draft delete failed uuid=${dialog.uuid}", e)
                false
            }
            _state.update { it.copy(draftDeleteDialog = null) }
            if (ok) {
                // Browser cache stale — the deleted draft must drop
                // on its next refresh. Same flag the editor uses for
                // save/delete so back-nav from either screen lands
                // on a consistent list.
                climbNavState.creatorDataChanged = true
                onDeleted()
            }
        }
    }

    /**
     * Run the actual deletion. Best-effort across the relay pool — even
     * with `accepted == 0` the local row is tombstoned so the user sees
     * an immediate UI effect; any future re-import via Live-Sub is
     * absorbed by the L3 is_deleted=1 guard. [onDeleted] is invoked
     * on a successful Done outcome so the screen can navigate back.
     */
    fun confirmCommunityDelete(onDeleted: () -> Unit) {
        val dialog = _state.value.communityDeleteDialog ?: return
        if (dialog.isInProgress) return
        _state.update { it.copy(communityDeleteDialog = dialog.copy(isInProgress = true)) }
        viewModelScope.launch {
            val outcome = runCatching { communityClimbDeleter.delete(dialog.uuid) }
                .onFailure { Log.w(TAG, "community delete threw uuid=${dialog.uuid}", it) }
                .getOrNull()
            val feedback: CommunityDeleteFeedback = when (outcome) {
                is com.cruxcoach.android.community.CommunityClimbDeleter.Outcome.Done ->
                    CommunityDeleteFeedback.Done(
                        attempted = outcome.attempted,
                        accepted = outcome.accepted,
                        kilterAlsoPublished = outcome.kilterWasPublished,
                    )
                is com.cruxcoach.android.community.CommunityClimbDeleter.Outcome.LocalTombstoneFailed ->
                    CommunityDeleteFeedback.LocalTombstoneFailed(
                        attempted = outcome.attempted,
                        accepted = outcome.accepted,
                        kilterAlsoPublished = outcome.kilterWasPublished,
                    )
                com.cruxcoach.android.community.CommunityClimbDeleter.Outcome.NotOwner ->
                    CommunityDeleteFeedback.NotOwner
                com.cruxcoach.android.community.CommunityClimbDeleter.Outcome.NotOurClimb ->
                    CommunityDeleteFeedback.NotOurClimb
                com.cruxcoach.android.community.CommunityClimbDeleter.Outcome.NotFound ->
                    CommunityDeleteFeedback.NotFound
                null -> CommunityDeleteFeedback.Failed
            }
            _state.update {
                it.copy(
                    communityDeleteDialog = null,
                    communityDeleteFeedback = feedback,
                )
            }
            if (feedback is CommunityDeleteFeedback.Done) {
                // Browser cache is stale: the deleted publication must
                // drop on its next refresh. Same flag the editor uses
                // for save/delete so back-nav from either screen lands
                // on a consistent list.
                climbNavState.creatorDataChanged = true
                onDeleted()
            }
        }
    }

    fun consumeCommunityDeleteFeedback() {
        _state.update { it.copy(communityDeleteFeedback = null) }
    }

    /**
     * Publish the currently-displayed OWN Kilter climb to the CruxCoach
     * community. The UI gates the action on [ClimbDetailState.canPublishAsMine];
     * [com.cruxcoach.android.community.OwnKilterClimbPublisher] re-checks the
     * authorship gate (connected-account userUuid identity) so an
     * out-of-band caller can never publish someone else's work.
     */
    fun publishOwnClimb() {
        val climb = _state.value.climb ?: return
        if (_state.value.isOwnPublishInProgress) return
        _state.update { it.copy(isOwnPublishInProgress = true) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ownClimbPublisher.publish(climb.uuid) }
                    .onFailure { Log.w(TAG, "publishOwnClimb threw uuid=${climb.uuid}", it) }
                    .getOrNull()
            }
            val feedback = when (outcome) {
                is com.cruxcoach.android.community.OwnKilterClimbPublisher.Outcome.Published ->
                    OwnPublishFeedback.Published
                com.cruxcoach.android.community.OwnKilterClimbPublisher.Outcome.NotAuthor ->
                    OwnPublishFeedback.NotAuthor
                com.cruxcoach.android.community.OwnKilterClimbPublisher.Outcome.NoNostrIdentity ->
                    OwnPublishFeedback.NoNostrIdentity
                com.cruxcoach.android.community.OwnKilterClimbPublisher.Outcome.AlreadyPublished ->
                    OwnPublishFeedback.AlreadyPublished
                is com.cruxcoach.android.community.OwnKilterClimbPublisher.Outcome.Failed,
                null -> OwnPublishFeedback.Failed
            }
            _state.update {
                it.copy(isOwnPublishInProgress = false, ownPublishFeedback = feedback)
            }
            if (feedback == OwnPublishFeedback.Published) {
                // Provenance changed in place (origin→cruxcoach, owner set,
                // published) — reload so the detail header, setter profile
                // and owner-gated actions reflect the community state, and
                // drop the stale cached page. The browser cache is stale
                // too (community badge / origin filters).
                _pageCache.update { it - climb.uuid }
                climbNavState.creatorDataChanged = true
                loadClimb(currentClimbUuid, currentAngle)
            }
        }
    }

    fun consumeOwnPublishFeedback() {
        _state.update { it.copy(ownPublishFeedback = null) }
    }

    fun switchClimb(uuid: String, angle: Int) {
        if (uuid == currentClimbUuid && angle == currentAngle) return
        ascentLogger.finishQuickSequence()
        Log.d(TAG, "switchClimb: $uuid angle=$angle (was: $currentClimbUuid)")
        currentClimbUuid = uuid
        currentAngle = angle
        playbackController.stopPlayback()
        loadJob?.cancel()
        sendController.cancelSend()
        // Reset BLE send state so a stale isSending=true from the previous climb
        // doesn't block auto-send for the new climb.
        val currentConn = bleConnection.connectionState.value
        // Use cached page state if available to avoid loading flash during pager swipe
        val cached = _pageCache.value[uuid]
        if (cached != null) {
            _state.update { current -> cached.withLiveDeviceState(current).copy(
                ascent = AscentFormState(),
                listDialog = ListDialogState(),
                ble = current.ble.copy(
                    connectionState = currentConn,
                    isSending = false,
                    success = false,
                    error = null,
                    warning = null,
                ),
            ) }
        } else {
            _state.update { it.copy(
                isLoading = true,
                error = null,
                isMirrored = false,
                ble = it.ble.copy(
                    connectionState = currentConn,
                    isSending = false,
                    success = false,
                    error = null,
                    warning = null,
                ),
                playback = it.playback.copy(showPreview = false),
                ascent = AscentFormState(),
                listDialog = it.listDialog.copy(show = false)
            ) }
        }
        loadClimb(uuid, angle)
    }

    fun onAngleSelected(angle: Int) {
        if (angle == currentAngle) return
        ascentLogger.finishQuickSequence()
        loadJob?.cancel()
        currentAngle = angle
        _state.update { current ->
            current.copy(
                isLoading = true,
                error = null,
                angle = angle,
                ble = current.ble.copy(isSending = false, success = false, error = null),
            )
        }
        loadClimb(currentClimbUuid, angle)
    }

    /**
     * Build the angle picker for [climb]. A climb can be climbed at every
     * angle its board physically supports, not just the angles it already has
     * community stats for. The statted angles (which carry the community
     * grade/quality) are merged with the board's supported angles; any extra
     * angle becomes a pickable option with no grade. The setter's own angle
     * (a community climb is created at a single angle) is flagged so it stays
     * visible as info while the climb opens up to the full range.
     *
     * The supported set is board-specific so no nonsensical angle is offered:
     *  - Aurora-protocol boards adjust continuously → every angle that board's
     *    catalogue is actually used at (data-driven from climb_stats).
     *  - MoonBoard is fixed-config → only the handful of angles the specific
     *    variant is built for (e.g. Masters 2017 = 25°/40°, 2016 = 40°).
     */
    private fun buildAngleOptions(climb: ClimbWithStats, statted: List<AngleOption>): List<AngleOption> {
        val brand = BoardBrand.fromWire(climb.boardBrand)
        // A community climb has one stats row = the angle the setter chose;
        // imported climbs are angle-agnostic, so there's no single setter angle.
        val setterAngle = if (climb.origin == "cruxcoach") statted.firstOrNull()?.angle else null
        val supported: Set<Int> = when {
            brand.usesAuroraProtocol -> {
                // Pass the climb's own brand: layout ids collide across brands
                // (layout 1 = Kilter Original AND Grasshopper/So iLL/Touchstone),
                // so an unscoped lookup would union other brands' angle ranges.
                val cacheKey = climb.layoutId.toInt() to climb.boardBrand
                supportedAnglesCache[cacheKey] ?: PerfLogger.trace("loadClimb.supportedAngles") {
                    boardRepository
                        .getSupportedAnglesForLayout(climb.layoutId.toInt(), climb.boardBrand)
                        .toSet()
                }.also { loaded ->
                    // Do not retain an empty result: the first detail can race a
                    // just-started catalogue import, and the completed import
                    // must be allowed to populate the picker later.
                    if (loaded.isNotEmpty()) supportedAnglesCache.putIfAbsent(cacheKey, loaded)
                }
            }
            brand == BoardBrand.MOONBOARD ->
                MoonBoardVariant.fromLayoutId(climb.layoutId)?.angles?.toSet() ?: emptySet()
            else -> emptySet()
        }
        val byAngle = statted.associateBy { it.angle }
        val allAngles = (byAngle.keys + supported).toSortedSet()
        // Defensive: if nothing resolved, fall back to the statted angles so
        // the picker is never emptier than before.
        val source = allAngles.ifEmpty { byAngle.keys.toSortedSet() }
        return source.map { a ->
            (byAngle[a] ?: AngleOption(a, null, null, null, 0.0))
                .copy(isSetterAngle = a == setterAngle)
        }
    }

    private fun loadClimb(uuid: String, angle: Int) {
        loadJob = viewModelScope.launch {
            try {
                PerfLogger.navMilestone("loadClimb start ($uuid)")
                withContext(Dispatchers.IO) {
                    // Try exact match first, then case variants (DB may store
                    // upper/lowercase). Keep the fast primary-key path first;
                    // only on a miss fall back to the normalized (strip-hyphens
                    // + lowercase) scan, which resolves uuids whose hyphenation
                    // differs from the stored row (logbook dashed-lowercase vs
                    // legacy nodash-UPPERCASE board rows).
                    val climb = PerfLogger.trace("loadClimb.getClimbByUuid") {
                        boardRepository.getClimbByUuid(uuid, angle)
                            ?: boardRepository.getClimbByUuid(uuid.lowercase(), angle)
                            ?: boardRepository.getClimbByUuid(uuid.uppercase(), angle)
                            ?: boardRepository.getClimbByUuidNormalized(uuid, angle)
                    }
                    if (climb != null) {
                        // FEAT-027: a MoonBoard climb has no Aurora
                        // product_size / board_images / placement-LED rows —
                        // its visualization is procedural from `frames`.
                        // Skip every Kilter-only board-geometry lookup.
                        val isMoonBoard = !climb.brand.usesAuroraPlacements
                        val allFrames = BoardClimbParser.parseMultiFrames(climb.frames)
                        val isRoute = allFrames.size > 1
                        val holds = allFrames.firstOrNull() ?: emptyList()
                        // FEAT-031: fetch geometry for the CLIMB's own board
                        // brand (Aurora layout/size/placement ids are namespaced
                        // by board_brand; defaulting to "kilter" renders the
                        // wrong board for Tension/Grasshopper/etc.).
                        val brand = climb.brand.wireValue
                        val placementMap = if (isMoonBoard) emptyMap() else
                            PerfLogger.trace("loadClimb.placements") {
                                cachedPlacementMap ?: run {
                                    val map = boardRepository.getAllPlacements(brand).associateBy { it.placementId.toInt() }
                                    cachedPlacementMap = map
                                    map
                                }
                            }
                        val prefSizeId = userPreferences.boardProductSizeId.first()
                        val prefLayoutId = userPreferences.boardLayoutId.first()
                        val effectiveBoard = if (isMoonBoard) null else pickEffectiveBoardForClimb(
                            climbUuid = uuid,
                            climbLayoutId = climb.layoutId.toInt(),
                            preferredSizeId = prefSizeId,
                            preferredLayoutId = prefLayoutId,
                            boardBrand = brand,
                        )
                        val boardSize = effectiveBoard?.let { (sizeId, _) ->
                            boardRepository.getProductSize(sizeId, brand)
                        }
                        val boardImages = effectiveBoard?.let { (sizeId, layoutId) ->
                            boardRepository.getBoardImages(sizeId, layoutId, brand)
                        } ?: emptyList()
                        val userAscents = PerfLogger.trace("loadClimb.userHistory") {
                            personalBoardRepo.getUserHistoryForClimb(uuid)
                        }
                        val isFavorited = personalBoardRepo.isClimbFavorited(uuid)
                        val isIgnored = personalBoardRepo.isClimbIgnored(uuid)
                        val personalNote = personalBoardRepo.getClimbNote(climb.uuid).orEmpty()
                        val personalNoteDraft = personalNoteDrafts[climb.uuid] ?: personalNote
                        val personalNoteStatus = personalNoteSaveStatuses[climb.uuid]
                            ?: PersonalNoteSaveStatus.IDLE
                        val angles = buildAngleOptions(climb, boardRepository.getAnglesForClimb(uuid))
                        val isMirrorable = BoardConstants.isLayoutMirrorable(
                            climb.brand, climb.layoutId.toInt()
                        )
                        // Authorship-gated publish action (own Kilter climbs).
                        // Cheap (two indexed lookups); never throws — a read
                        // failure just hides the action.
                        val canPublishAsMine =
                            ownClimbPublisher.isPublishableAsMine(climb.uuid)

                        mirrorPlacementMap = if (effectiveBoard == null) emptyMap() else
                            PerfLogger.trace("loadClimb.mirrorMap") {
                                // Derive the mirror map geometrically (pure
                                // horizontal reflection of placements). The DB
                                // getMirrorPlacementMap path reads the `holes`
                                // table, which is never populated for any board,
                                // so it always returned empty and fell through to
                                // this fallback anyway — and it was brand-blind
                                // (defaulted to Kilter). Call the geometric path
                                // directly: correct for every mirrorable board.
                                computeMirrorMapFromPlacements(placementMap, boardSize)
                            }
                        originalAllFrames = allFrames

                        val useSetterSpeed = userPreferences.routeUseSetterSpeed.first()
                        val speedOverride = if (useSetterSpeed && isRoute && climb.framesPace > 0) {
                            climb.framesPace.toFloat() / 1000f
                        } else null

                        _state.update { s ->
                            s.copy(
                                isLoading = false,
                                error = null,
                                logbookOnly = null,
                                climb = climb,
                                holds = holds,
                                placements = placementMap,
                                boardSize = boardSize,
                                boardImages = boardImages,
                                userAscents = userAscents,
                                angle = angle,
                                isFavorited = isFavorited,
                                isIgnored = isIgnored,
                                personalNote = personalNote,
                                personalNoteDraft = personalNoteDraft,
                                personalNoteSaveStatus = personalNoteStatus,
                                availableAngles = angles,
                                isMirrorable = isMirrorable,
                                canPublishAsMine = canPublishAsMine,
                                // Seed setter profile synchronously with the
                                // local fallback (`setter_username` from the
                                // blob, or the npub-short stub). Async Kind 0
                                // lookup overwrites it below if richer data
                                // is available on relays.
                                setterProfile = seedSetterProfile(climb),
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
                        sendAutomaticallyIfEnabled()
                        // Fire-and-forget Kind 0 lookup. Doesn't block UI;
                        // updates state when the relay responds (or skips
                        // silently if not). Only relevant for community
                        // climbs — Kilter-Original climbs already have
                        // setter_username populated from the API.
                        loadSetterProfileFromNostr(climb)
                    } else {
                        // Climb absent from the board DB even after the
                        // normalized fallback. Before dead-ending on the raw
                        // diagnostic error, check whether the user has local
                        // logbook history for this uuid (e.g. a Kilter ascent
                        // imported via FEAT-030 for a new-PowerSync-world climb
                        // we never mirrored). If so, render the friendly
                        // logbook-only state from what the ascent rows carry,
                        // and keep the uuid/angle diagnostic out of the UI.
                        val history = PerfLogger.trace("loadClimb.notFound.userHistory") {
                            personalBoardRepo.getUserHistoryForClimb(uuid)
                        }
                        if (history.isNotEmpty()) {
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    error = null,
                                    climb = null,
                                    userAscents = history,
                                    logbookOnly = LogbookOnlyState(uuid = uuid, ascents = history),
                                )
                            }
                        } else {
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    logbookOnly = null,
                                    error = context.getString(R.string.error_climb_not_found, uuid, angle),
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Build a [SetterProfile] using only the locally-known data. For
     * Kilter-Original climbs this returns null (existing setter_username
     * display path is enough). For CruxCoach climbs this falls back to
     * `setter_username` from the row, or the `npub:<short>` stub if even
     * that is missing.
     */
    private fun seedSetterProfile(climb: ClimbWithStats): SetterProfile? {
        if (climb.origin != "cruxcoach") return null
        val setterUsername = climb.setterUsername?.takeIf { it.isNotBlank() }
        val pubkey = climb.createdByPubkey
        val displayName = setterUsername
            ?: pubkey?.let { "npub:${it.take(16)}" }
            ?: "Unbekannt"
        return SetterProfile(
            displayName = displayName,
            pictureUrl = null,
            isCommunity = true,
        )
    }

    /**
     * Async Kind-0 lookup for the climb's setter, lazy-loaded into the
     * detail screen. No-op for non-community climbs and for climbs
     * without a [createdByPubkey] (Kilter-imported rows). When the
     * profile resolves, [SetterProfile.displayName] is rebuilt with the
     * `display_name → setter_username → npub:short` fallback chain and
     * `pictureUrl` populated if the setter has set a `picture` field
     * in their Kind 0 metadata.
     */
    private fun loadSetterProfileFromNostr(climb: ClimbWithStats) {
        val pubkey = climb.createdByPubkey?.takeIf { it.isNotBlank() } ?: return
        if (climb.origin != "cruxcoach") return
        viewModelScope.launch {
            val profile = runCatching { nostrProfileManager.getProfile(pubkey) }.getOrNull()
                ?: return@launch
            // Only update if we got something more specific than what's
            // already shown — avoids triggering a stutter when the cache
            // returns the same data we seeded with.
            val newDisplayName = profile.displayName?.takeIf { it.isNotBlank() }
                ?: climb.setterUsername?.takeIf { it.isNotBlank() }
                ?: "npub:${pubkey.take(16)}"
            _state.update { s ->
                if (s.climb?.uuid != climb.uuid) return@update s   // user navigated away
                s.copy(setterProfile = SetterProfile(
                    displayName = newDisplayName,
                    pictureUrl = profile.pictureUrl?.takeIf { it.isNotBlank() },
                    isCommunity = true,
                ))
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
                    // FEAT-027: skip Kilter-only board geometry for MoonBoard climbs.
                    val isMoonBoard = !climb.brand.usesAuroraPlacements
                    val allFrames = BoardClimbParser.parseMultiFrames(climb.frames)
                    val isRoute = allFrames.size > 1
                    val holds = allFrames.firstOrNull() ?: emptyList()
                    val brand = climb.brand.wireValue
                    val placementMap = if (isMoonBoard) emptyMap() else cachedPlacementMap ?: run {
                        val map = boardRepository.getAllPlacements(brand).associateBy { it.placementId.toInt() }
                        cachedPlacementMap = map
                        map
                    }
                    val prefSizeId = userPreferences.boardProductSizeId.first()
                    val prefLayoutId = userPreferences.boardLayoutId.first()
                    val effectiveBoard = if (isMoonBoard) null else pickEffectiveBoardForClimb(
                        climbUuid = uuid,
                        climbLayoutId = climb.layoutId.toInt(),
                        preferredSizeId = prefSizeId,
                        preferredLayoutId = prefLayoutId,
                        boardBrand = brand,
                    )
                    val boardSize = effectiveBoard?.let { (sizeId, _) ->
                        boardRepository.getProductSize(sizeId, brand)
                    }
                    val boardImages = effectiveBoard?.let { (sizeId, layoutId) ->
                        boardRepository.getBoardImages(sizeId, layoutId, brand)
                    } ?: emptyList()
                    val userAscents = personalBoardRepo.getUserHistoryForClimb(uuid)
                    val isFavorited = personalBoardRepo.isClimbFavorited(uuid)
                    val isIgnored = personalBoardRepo.isClimbIgnored(uuid)
                    val personalNote = personalBoardRepo.getClimbNote(climb.uuid).orEmpty()
                    val angles = buildAngleOptions(climb, boardRepository.getAnglesForClimb(uuid))
                    val isMirrorable = BoardConstants.isLayoutMirrorable(
                        climb.brand, climb.layoutId.toInt()
                    )

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
                        isIgnored = isIgnored,
                        personalNote = personalNote,
                        availableAngles = angles,
                        isMirrored = false,
                        isMirrorable = isMirrorable,
                        canPublishAsMine = ownClimbPublisher.isPublishableAsMine(climb.uuid),
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
    fun quickLogAscent(isSend: Boolean) = ascentLogger.quickLog(isSend)
    fun consumeQuickLogFeedback() = ascentLogger.consumeQuickLogFeedback()
    fun undoQuickLog() = ascentLogger.undoQuickLog()
    fun consumeQuickLogFailure() = _state.update { it.copy(quickLogFailed = false) }
    fun dismissAscentDialog() = ascentLogger.dismissDialog()
    fun updateAscentIsSend(isSend: Boolean) = ascentLogger.updateIsSend(isSend)
    fun updateAscentBidCount(count: Int) = ascentLogger.updateBidCount(count)
    fun updateAscentQuality(quality: Int) = ascentLogger.updateQuality(quality)
    fun updateAscentComment(comment: String) = ascentLogger.updateComment(comment)
    fun updateAscentIsBenchmark(value: Boolean) {
        _state.update { it.copy(ascent = it.ascent.copy(isBenchmark = value)) }
    }
    fun saveAscent() = ascentLogger.save()
    fun editAscent(ascent: AscentWithClimb) = ascentLogger.edit(ascent)
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

    fun selectBoardLayer(slot: Int) {
        val existing = _state.value.boardLayers.layers.firstOrNull { it.slot == slot }
        _state.update {
            it.copy(
                selectedBoardLayerSlot = slot,
                selectedBoardLayerColor = existing?.color ?: boardLayerManager.defaultColor(slot),
            )
        }
    }

    fun selectBoardLayerColor(color: Int) {
        _state.update { it.copy(selectedBoardLayerColor = color) }
    }

    fun assignCurrentToBoardLayer() = sendController.assignCurrentToBoardLayer()
    fun sendBoardLayer(slot: Int) = sendController.sendBoardLayer(slot)
    fun sendAllBoardLayers() = sendController.sendAllBoardLayers()
    fun removeBoardLayer(slot: Int) = sendController.removeBoardLayer(slot)

    /**
     * Primary detail action. A shared session owns board delivery, so the
     * same surface that lights a directly connected board adds to the shared
     * queue when this device is a host or participant.
     */
    fun deliverClimb() {
        val climbState = _state.value
        val climb = climbState.climb ?: return
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = climbState.boardSendMode,
            sessionRole = sessionQueueManager.state.value.role,
            sessionConnecting = sessionQueueManager.state.value.isConnecting,
            boardConnected = sendController.isConnected(),
            hasDirectPayload = BoardProjectionPolicy.hasSendablePayload(
                brand = climb.brand,
                holdCount = climbState.holds.size,
                frames = climb.frames,
            ),
            connectedViaRelay = climbState.ble.connectedViaRelay,
            connectedViaMesh = sendController.isConnectedViaMesh(),
            // The screen hides its lamp while a group is on the board, but the
            // hiding was the only thing stopping this. A deep link, a stale
            // recomposition or a test calling straight in would still have lit
            // a wall somebody else is climbing on.
            boardCellActive = sendController.isBoardOwnedByCell(),
        )
        when (decision.target) {
            BoardDeliveryTarget.DIRECT_BOARD -> sendController.sendToBoard()
            BoardDeliveryTarget.MESH_BOARD -> sendController.sendToBoard()
            BoardDeliveryTarget.SHARED_QUEUE ->
                sessionQueueManager.addClimb(climb.uuid, climbState.angle)
            BoardDeliveryTarget.NONE -> Unit
        }
    }

    // --- Favorites & lists ---

    fun toggleFavorite() {
        viewModelScope.launch {
            try {
                val newState = withContext(Dispatchers.IO) {
                    personalBoardRepo.toggleFavorite(currentClimbUuid)
                }
                _state.update { it.copy(isFavorited = newState) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // SQLite lock contention with the import worker / mid-
                // migration / disk-full would otherwise leave the heart
                // icon in the wrong state and silently kill the
                // coroutine. Log + leave the previous favorite-state
                // visible (no UI flip).
                Log.w(TAG, "toggleFavorite failed", e)
            }
        }
    }

    fun savePersonalNote(note: String) {
        val climbUuid = _state.value.climb?.uuid ?: currentClimbUuid
        val normalized = note.trim().take(1000)
        if (
            _state.value.climb?.uuid == climbUuid &&
            _state.value.personalNote == normalized &&
            _state.value.personalNoteSaveStatus != PersonalNoteSaveStatus.FAILED
        ) return
        personalNoteDrafts[climbUuid] = note.take(1000)
        personalNoteSaveStatuses[climbUuid] = PersonalNoteSaveStatus.SAVING
        personalNoteSaveJobs.remove(climbUuid)?.cancel()
        _state.update { current ->
            if (current.climb?.uuid == climbUuid) {
                current.copy(
                    personalNoteDraft = note.take(1000),
                    personalNoteSaveStatus = PersonalNoteSaveStatus.SAVING,
                )
            } else current
        }
        personalNoteSaveJobs[climbUuid] = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    personalBoardRepo.saveClimbNote(climbUuid, normalized)
                }
                personalNoteDrafts.remove(climbUuid)
                personalNoteSaveStatuses[climbUuid] = PersonalNoteSaveStatus.SAVED
                _state.update { current ->
                    if (current.climb?.uuid == climbUuid) current.copy(
                        personalNote = normalized,
                        personalNoteDraft = normalized,
                        personalNoteSaveStatus = PersonalNoteSaveStatus.SAVED,
                    )
                    else current
                }
                _pageCache.update { cache ->
                    cache.mapValues { (_, cached) ->
                        if (cached.climb?.uuid == climbUuid) cached.copy(
                            personalNote = normalized,
                            personalNoteDraft = normalized,
                            personalNoteSaveStatus = PersonalNoteSaveStatus.SAVED,
                        )
                        else cached
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "savePersonalNote failed", e)
                personalNoteSaveStatuses[climbUuid] = PersonalNoteSaveStatus.FAILED
                _state.update { current ->
                    if (current.climb?.uuid == climbUuid) {
                        current.copy(personalNoteSaveStatus = PersonalNoteSaveStatus.FAILED)
                    } else current
                }
            }
        }
    }

    fun updatePersonalNoteDraft(note: String) {
        val climbUuid = _state.value.climb?.uuid ?: return
        val bounded = note.take(1000)
        personalNoteDrafts[climbUuid] = bounded
        personalNoteSaveStatuses[climbUuid] = PersonalNoteSaveStatus.IDLE
        _state.update { current ->
            if (current.climb?.uuid == climbUuid) current.copy(
                personalNoteDraft = bounded,
                personalNoteSaveStatus = PersonalNoteSaveStatus.IDLE,
            ) else current
        }
        _pageCache.update { cache ->
            cache.mapValues { (_, cached) ->
                if (cached.climb?.uuid == climbUuid) cached.copy(
                    personalNoteDraft = bounded,
                    personalNoteSaveStatus = PersonalNoteSaveStatus.IDLE,
                ) else cached
            }
        }
    }

    /** Toggle the climb on/off the built-in "Ignored" list. Ignored climbs
     *  are excluded from every browse suggestion. Flags the browser dirty
     *  (creatorDataChanged) so it re-reads its ignore set on return and the
     *  climb drops out of — or comes back into — the list. */
    fun toggleIgnored() {
        viewModelScope.launch {
            try {
                val newState = withContext(Dispatchers.IO) {
                    personalBoardRepo.toggleIgnored(currentClimbUuid)
                }
                _state.update { it.copy(isIgnored = newState) }
                climbNavState.creatorDataChanged = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "toggleIgnored failed", e)
            }
        }
    }

    fun toggleMirror() {
        ascentLogger.finishQuickSequence()
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
        viewModelScope.launch { sendAutomaticallyIfEnabled() }
    }

    private suspend fun sendAutomaticallyIfEnabled() {
        val mode = try {
            combine(
                userPreferences.singleConnectionBoardSendMode,
                userPreferences.multiConnectionBoardSendMode,
            ) { singleMode, multiMode ->
                BoardSendModePolicy.resolve(
                    connectionCapacity = BoardControllerProfiles
                        .forBoard(bleConnection.connectedBoard)
                        .connectionCapacity,
                    singleConnectionMode = singleMode,
                    multiConnectionMode = multiMode,
                    hostingForOthers = _state.value.ble.hostedRelayClientCount > 0,
                    meshParticipant = (com.cruxcoach.android.boardcell.BoardCellManager.current
                        ?.snapshot()?.members?.size ?: 0) > 1,
                )
            }.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "capacity-specific board send mode read failed; using UI state", e)
            _state.value.boardSendMode
        }
        val climbState = _state.value
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = mode,
            sessionRole = sessionQueueManager.state.value.role,
            sessionConnecting = sessionQueueManager.state.value.isConnecting,
            boardConnected = sendController.isConnected(),
            hasDirectPayload = BoardProjectionPolicy.hasSendablePayload(
                brand = climbState.climb?.brand,
                holdCount = climbState.holds.size,
                frames = climbState.climb?.frames,
            ),
            connectedViaRelay = climbState.ble.connectedViaRelay,
            connectedViaMesh = sendController.isConnectedViaMesh(),
            boardCellActive = sendController.isBoardOwnedByCell(),
        )
        if (decision.dispatchAutomatically) {
            sendController.sendToBoard(automaticLayer = true)
        }
    }

    private fun mirrorHolds(holds: List<BoardHold>): List<BoardHold> {
        return holds.map { hold ->
            val mirroredId = mirrorPlacementMap[hold.placementId]
            if (mirroredId != null) hold.copy(placementId = mirroredId) else hold
        }
    }

    /**
     * Resolve which (productSize, layout) pair to render this specific
     * climb under. Aurora-imported and cross-board community climbs
     * carry a layout_id that may differ from the user's currently-
     * configured board (e.g. user has Original 12×12 set but the climb
     * was set on Homewall). Pre-fix the detail screen blindly used the
     * user's preferred (size, layout) pair, which had two visible
     * effects:
     *  - the wrong image tiles loaded behind the holds (Original
     *    background under Homewall placements, etc.), and
     *  - the BoardSize edges drove a coordinate transformation tuned
     *    for the wrong physical board, so holds shifted off their hole
     *    centers.
     *
     * Resolution rules, in priority order:
     *  1. **User's currently-configured board** (Settings → Board)
     *     when it can host the climb — same layout, has images, and
     *     its extent contains the climb's bbox. This is the default
     *     because the BoardBrowser → Detail flow filters by the
     *     user's board, so the user expects to see "this climb on MY
     *     board". A climb from a smaller-or-equal physical board
     *     renders here at the user's bigger board's size, with the
     *     climb's holds positioned correctly inside the larger frame.
     *  2. **Per-climb containment fallback** — only when the user's
     *     board can't host the climb (Aurora-imported climb from a
     *     different layout, or a climb whose bbox extends past the
     *     user's smaller board). Picks the smallest size whose
     *     extent contains the climb's bbox, so cropped sub-routes
     *     stay rendered on the right Kilter SKU.
     *  3. Prefer the user's size for the climb's layout (most users
     *     have one physical size and bundles ship multiple layouts).
     *  4. Any size that has images for the climb's layout.
     *  5. Last-resort fall back to the user's preferred pair so the
     *     screen never goes blank for an unrecognised layout.
     */
    private suspend fun pickEffectiveBoardForClimb(
        climbUuid: String,
        climbLayoutId: Int,
        preferredSizeId: Int,
        preferredLayoutId: Int,
        boardBrand: String,
    ): Pair<Int, Int> {
        if (boardRepository.canRenderClimbOnSize(climbUuid, preferredSizeId, boardBrand)) {
            // Use the climb's layout (not preferredLayoutId) so a
            // multi-layout size still renders the right image set —
            // canRenderClimbOnSize already verified images exist for
            // (preferredSizeId, climb.layoutId). For the typical case
            // where the user's preferred layout already matches the
            // climb, this collapses to the user's full settings pair.
            return preferredSizeId to climbLayoutId
        }
        boardRepository.getProductSizeForClimbRender(climbUuid, boardBrand)?.let { containing ->
            return containing to climbLayoutId
        }
        val candidateSizes = boardRepository.getProductSizesForLayout(climbLayoutId, boardBrand)
        return when {
            preferredSizeId in candidateSizes -> preferredSizeId to climbLayoutId
            candidateSizes.isNotEmpty() -> candidateSizes.first() to climbLayoutId
            else -> preferredSizeId to preferredLayoutId
        }
    }

    private fun computeMirrorMapFromPlacements(
        placements: Map<Int, BoardPlacement>,
        boardSize: BoardSize?
    ): Map<Int, Int> {
        if (boardSize == null || placements.isEmpty()) return emptyMap()
        val centerX2 = (boardSize.edgeLeft + boardSize.edgeRight).toInt()
        val holds = placements.values.map {
            MirrorMapDeriver.Hold(
                placementId = it.placementId.toInt(),
                x = it.x.toInt(),
                y = it.y.toInt(),
                setId = it.setId.toInt(),
            )
        }
        return MirrorMapDeriver.derive(holds, centerX2)
    }

    fun showAddToListDialog() {
        // Content is loaded by AddToListDialogHost's own ViewModel — the
        // detail VM only tracks visibility.
        _state.update { it.copy(listDialog = ListDialogState(show = true)) }
    }

    fun dismissAddToListDialog() {
        _state.update { it.copy(listDialog = it.listDialog.copy(show = false)) }
        viewModelScope.launch {
            try {
                val isFav = withContext(Dispatchers.IO) { personalBoardRepo.isClimbFavorited(currentClimbUuid) }
                _state.update { it.copy(isFavorited = isFav) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "dismissAddToListDialog favorite-refresh failed", e)
            }
        }
    }

    override fun onCleared() {
        // The Snackbar belongs to the screen, but persistence finalization
        // must not disappear when Back removes that screen immediately.
        ascentLogger.finishQuickSequence()
        super.onCleared()
        // Don't touch the advertiser here. BleConnectionViewModel fully manages
        // the advertising lifecycle (connected -> climb -> lastClimb -> gone).
    }

    // --- Rest timer (delegates to singleton BoardSessionManager) ---

    /** Auto-start after logging + the settings default duration. */
    fun startRestTimer() {
        sessionManager.startRestTimer(_state.value.restTimerTotalSeconds)
    }

    /** Manual start from the detail screen with a per-use custom
     *  duration. Does not touch the settings default (which stays the
     *  pre-fill + the post-logging auto-start value). */
    fun startRestTimer(durationSeconds: Int) {
        sessionManager.startRestTimer(durationSeconds)
    }

    fun cancelRestTimer() {
        sessionManager.cancelRestTimer()
    }

    fun dismissRestTimerFinished() {
        sessionManager.dismissRestTimerFinished()
    }

    /** @deprecated The setter-link click no longer applies a browse filter
     *  — it navigates to [SetterDetailScreen] instead. The browser still
     *  honours `pendingSetterFilter` if some future code wants to set it,
     *  but the climb-detail-by-setter row stopped using it as of Plan 8.
     *  Left in place for binary compat with the existing nav-state field. */
    @Deprecated("Use SetterDetailScreen via onNavigateToSetter instead.")
    fun requestSetterFilter(setter: String) {
        climbNavState.pendingSetterFilter = setter
    }

    companion object {
        private const val TAG = "BoardClimbDetailVM"
    }
}
