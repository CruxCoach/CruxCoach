package com.cruxcoach.android.data

import android.util.Log
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.NearbyClimb
import com.cruxcoach.android.ble.NearbyClimbScanner
import com.cruxcoach.android.ble.NearbySession
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.android.util.PerfLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central coordinator for all BLE sharing state.
 * Combines BoardStateManager, NearbyPresenceManager, NearbyClimbScanner (sessions),
 * and SharingConfig into a single [uiState] consumed by the BleStatusArea composable.
 *
 * Also handles the remote-climb-to-manager bridge (previously in BleConnectionViewModel).
 */
@Singleton
class BleShareManager @Inject constructor(
    private val boardStateManager: BoardStateManager,
    private val nearbyPresenceManager: NearbyPresenceManager,
    private val nearbyClimbScanner: NearbyClimbScanner,
    private val sharingConfig: SharingConfig,
    private val climbAdvertiser: ClimbBleAdvertiser,
    private val sessionQueueManager: SessionQueueManager,
    private val boardSessionManager: BoardSessionManager,
    private val userPreferences: UserPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(BleShareUiState())
    val uiState: StateFlow<BleShareUiState> = _uiState.asStateFlow()

    /** Session climb UUID at the time we last saw REMOTE_ACTIVE (external board overwrite).
     *  While the session hasn't advanced past this UUID, REMOTE_LAST wins over SESSION_REMOTE
     *  because the board still physically shows the external user's climb. */
    private var overwriteSessionUuid: String? = null

    /** Last remote ClimbData UUID we observed. Used to distinguish stale ClimbData (same UUID
     *  as before our send) from genuinely new sends (UUID changed → board was overwritten).
     *  The Kilter Board allows multiple simultaneous BLE connections, so we can't use
     *  hasActiveClimb() alone to determine if remote ClimbData is stale. */
    private var lastSeenRemoteActiveUuid: String? = null

    /** Last observed climb UUID from a nearby session advertisement. When this changes while
     *  we have hasActiveClimb(), the session sent a new climb via GATT (overwriting our LEDs).
     *  Sessions don't use ClimbData advertising — they use session advertisements + GATT,
     *  so the remoteActive UUID-tracking doesn't cover them. */
    private var lastSeenSessionClimbUuid: String? = null

    /** Timestamp until which remote LastClimb signals are ignored in bridgeRemoteClimbs().
     *  Set when a session ends to prevent stale participant advertising from overwriting
     *  the correct board state. Participants may continue advertising their pre-session
     *  LastClimb for up to 15s (scanner staleness timeout) after the session ends. */
    private var postSessionProtectedUntil: Long = 0L

    // Disconnect request state (centralized)
    private var disconnectTimeoutJob: Job? = null

    init {
        PerfLogger.log("📡 BleShareManager.init — starting combine collectors")
        // Restore persisted last climb on app start
        scope.launch {
            PerfLogger.logCoroutine("BleShareManager", "boardStateManager.restore() START")
            boardStateManager.restore()
            PerfLogger.logCoroutine("BleShareManager", "boardStateManager.restore() DONE")
        }

        // Bug 1 fix: Single combine block handles BOTH bridge logic AND UI state.
        // No separate nearbyPresenceManager.climbs.collect — that caused a dual-collection
        // race where bridgeRemoteClimbs() called removeEntry() → re-emission → flicker loop.
        scope.launch {
            combine(
                boardStateManager.lastClimb,
                nearbyPresenceManager.climbs,
                nearbyPresenceManager.climbInfos,
                nearbyClimbScanner.nearbySessions,
                sharingConfig.sharingEnabled,
                sessionQueueManager.state,
                userPreferences.gradeScale
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val lastClimb = values[0] as BoardStateManager.LastBoardClimb?
                val nearbyClimbs = values[1] as List<NearbyClimb>
                val climbInfos = values[2] as Map<String, ClimbDisplayInfo>
                val sessions = values[3] as List<NearbySession>
                val sharingEnabled = values[4] as Boolean
                // values[5] = SessionQueueState — triggers re-emit on queue changes
                val gradeScale = values[6] as GradeScale

                // Bridge remote climbs FIRST (side-effect: updates boardStateManager),
                // then build UI state. No removeEntry() — filter locally instead.
                bridgeRemoteClimbs(nearbyClimbs)

                // Check disconnect request resolution inline (was a separate collector)
                if (_uiState.value.isRequestingDisconnect) {
                    val noActiveRemote = nearbyClimbs.none { !it.isLastClimb && !it.connectedOnly }
                    if (noActiveRemote) {
                        cancelDisconnectRequest()
                    }
                }

                buildUiState(lastClimb, nearbyClimbs, climbInfos, sessions, sharingEnabled, gradeScale)
            }.distinctUntilChanged { old, new ->
                // Structural comparison ignoring RSSI fluctuations that don't affect the UI.
                // data class equals includes rssi fields in OnBoardClimbEntry and NearbySessionEntry,
                // which change on every BLE scan callback, causing unnecessary recompositions.
                old.onBoardClimb?.climbUuid == new.onBoardClimb?.climbUuid &&
                old.onBoardClimb?.source == new.onBoardClimb?.source &&
                old.onBoardClimb?.angle == new.onBoardClimb?.angle &&
                old.onBoardClimb?.name == new.onBoardClimb?.name &&
                old.onBoardClimb?.grade == new.onBoardClimb?.grade &&
                old.onBoardClimb?.isStillProjected == new.onBoardClimb?.isStillProjected &&
                old.boardOccupiedCount == new.boardOccupiedCount &&
                old.nearbySessions.size == new.nearbySessions.size &&
                old.nearbySessions.zip(new.nearbySessions).all { (a, b) ->
                    a.sessionId == b.sessionId && a.participantCount == b.participantCount &&
                    a.currentClimbUuid == b.currentClimbUuid && a.currentClimbName == b.currentClimbName &&
                    a.currentClimbGrade == b.currentClimbGrade
                } &&
                old.sharingEnabled == new.sharingEnabled &&
                old.canRequestDisconnect == new.canRequestDisconnect
            }.collect { newState ->
                Log.d(TAG, "STATE onBoard=${newState.onBoardClimb?.source}:${newState.onBoardClimb?.climbUuid?.take(8)} sessions=${newState.nearbySessions.size} occupied=${newState.boardOccupiedCount}")
                // Merge BLE-related fields only — preserve ownSession (set by second collector)
                // and disconnect request state (set by requestDisconnect/cancelDisconnectRequest).
                // Previously used `_uiState.update { newState }` which replaced ALL fields,
                // causing ownSession to briefly become null on every BLE scan change.
                _uiState.update { current ->
                    current.copy(
                        onBoardClimb = newState.onBoardClimb,
                        boardOccupiedCount = newState.boardOccupiedCount,
                        nearbySessions = newState.nearbySessions,
                        sharingEnabled = newState.sharingEnabled,
                        canRequestDisconnect = newState.canRequestDisconnect
                    )
                }
            }
        }

        // Track session state changes — filter out 500ms timer ticks that only change
        // elapsedSeconds. The session chip collects BoardSessionManager.state directly
        // for the live timer display; propagating ticks through uiState would cause
        // every BleStatusArea consumer (on every screen) to recompose 2x/sec.
        scope.launch {
            combine(
                sessionQueueManager.state,
                sessionQueueManager.currentClimbInfo,
                boardSessionManager.state,
                userPreferences.gradeScale
            ) { queueState, climbInfo, sessionState, gradeScale ->
                buildOwnSessionState(queueState, climbInfo, sessionState, gradeScale)
            }.distinctUntilChanged { old, new ->
                old?.isHost == new?.isHost &&
                old?.participantCount == new?.participantCount &&
                old?.queue == new?.queue &&
                old?.currentIndex == new?.currentIndex &&
                old?.currentClimbName == new?.currentClimbName &&
                old?.currentClimbGrade == new?.currentClimbGrade &&
                old?.isPaused == new?.isPaused
                // Deliberately ignoring elapsedSeconds — timer display collects directly
            }.collect { ownSession ->
                _uiState.update { it.copy(ownSession = ownSession) }
            }
        }

        // Watch for disconnect responses
        scope.launch {
            nearbyClimbScanner.disconnectResponses.collect { accepted ->
                if (!_uiState.value.isRequestingDisconnect) return@collect
                if (accepted) {
                    Log.d(TAG, "Disconnect response: accepted")
                } else {
                    Log.d(TAG, "Disconnect response: rejected")
                    _uiState.update { it.copy(
                        isRequestingDisconnect = false,
                        disconnectRequestNoResponse = false
                    ) }
                    disconnectTimeoutJob?.cancel()
                    disconnectTimeoutJob = null
                }
            }
        }

        // Detect session end (HOST/PARTICIPANT → NONE) to activate post-session grace period.
        // Stale participant LastClimb advertising can persist in the scanner for up to 15s
        // after the session ends, overwriting the correct board state.
        scope.launch {
            var previousRole = SessionRole.NONE
            sessionQueueManager.state.collect { queueState ->
                val currentRole = queueState.role
                if (previousRole != SessionRole.NONE && currentRole == SessionRole.NONE && !queueState.isConnecting) {
                    postSessionProtectedUntil = System.currentTimeMillis() + POST_SESSION_GRACE_MS
                    // Clear stale tracking state from the ended session
                    lastSeenRemoteActiveUuid = null
                    lastSeenSessionClimbUuid = null
                    Log.d(TAG, "Session ended ($previousRole→NONE) → grace period ${POST_SESSION_GRACE_MS}ms")
                }
                previousRole = currentRole
            }
        }
    }

    private fun buildOwnSessionState(
        queueState: SessionQueueState,
        climbInfo: ClimbDisplayInfo?,
        sessionState: BoardSessionState,
        gradeScale: GradeScale
    ): OwnSessionState? {
        if (queueState.role == SessionRole.NONE && !queueState.isConnecting) return null
        return OwnSessionState(
            isHost = queueState.role == SessionRole.HOST,
            participantCount = queueState.participantCount,
            queue = queueState.queue,
            currentIndex = queueState.currentIndex,
            currentClimbName = climbInfo?.name,
            currentClimbGrade = climbInfo?.difficultyAverage?.let {
                GradeDisplayHelper.formatDifficulty(it, gradeScale)
            },
            isPaused = sessionState.isPaused,
            elapsedSeconds = sessionState.elapsedSeconds
        )
    }

    /**
     * Bridges remote climb signals to BoardStateManager with priority rules:
     * 1. Session queue active with current climb → ignore all remote signals (we control the board)
     * 2. Remote ClimbData with NEW UUID → accept + clear own activeClimb (board overwritten)
     *    Remote ClimbData with SAME UUID + hasActive → ignore (stale from before our send)
     * 3. Remote LastClimb while we have an activeClimb → ignore (our send is authoritative)
     * 4. Remote LastClimb while no activeClimb → accept normally
     *
     * Bug 1 fix: Does NOT call removeEntry() — stale entries are filtered locally in
     * resolveOnBoardClimb() instead. This prevents re-triggering the combine flow.
     */
    private suspend fun bridgeRemoteClimbs(climbs: List<NearbyClimb>) {
        // When hosting a session with a current queue climb, we control the board.
        // Remote LastClimb signals (from nearby users' stale advertising) must not
        // overwrite boardStateManager, or the "Auf dem Board" banner drifts from the queue.
        val queueState = sessionQueueManager.state.value
        val sessionControlsBoard = queueState.role == SessionRole.HOST && queueState.currentClimb != null

        val remoteActive = climbs.firstOrNull {
            it.climbUuid.isNotEmpty() && !it.connectedOnly && !it.isLastClimb
        }
        if (remoteActive != null) {
            val isNewRemoteSend = remoteActive.climbUuid != lastSeenRemoteActiveUuid
            lastSeenRemoteActiveUuid = remoteActive.climbUuid

            if (climbAdvertiser.hasActiveClimb() && !isNewRemoteSend) {
                // Same remote ClimbData UUID as before — stale from before our send.
                // Kilter Board allows multiple connections, but the remote hasn't sent
                // a NEW climb since we last checked → our send is still on the board.
                Log.d(TAG, "BRIDGE remote=ClimbData uuid=${remoteActive.climbUuid.take(8)} hasActive=true sameUuid → ignore (stale)")
                return
            }
            // Either we don't have an active climb, or the remote UUID changed (new send
            // that overwrites our climb on the board).
            Log.d(TAG, "BRIDGE remote=ClimbData uuid=${remoteActive.climbUuid.take(8)} hasActive=${climbAdvertiser.hasActiveClimb()} newSend=$isNewRemoteSend → accept")
            boardStateManager.setLastClimb(
                remoteActive.climbUuid,
                remoteActive.angle,
                remoteActive.projectionSurvivesDisconnect,
            )
            climbAdvertiser.clearActiveClimb()
            return
        }

        val remoteLast = climbs.firstOrNull {
            it.climbUuid.isNotEmpty() && !it.connectedOnly && it.isLastClimb
        }
        if (remoteLast != null) {
            if (sessionControlsBoard) {
                Log.d(TAG, "BRIDGE remote=LastClimb uuid=${remoteLast.climbUuid.take(8)} sessionControlsBoard → ignore")
                return
            }
            if (climbAdvertiser.hasActiveClimb()) {
                Log.d(TAG, "BRIDGE remote=LastClimb uuid=${remoteLast.climbUuid.take(8)} hasActive=true → ignore")
                return
            }
            if (System.currentTimeMillis() < postSessionProtectedUntil) {
                Log.d(TAG, "BRIDGE remote=LastClimb uuid=${remoteLast.climbUuid.take(8)} postSessionGrace → ignore")
                return
            }
            Log.d(TAG, "BRIDGE remote=LastClimb uuid=${remoteLast.climbUuid.take(8)} hasActive=false → accept")
            boardStateManager.setLastClimb(
                remoteLast.climbUuid,
                remoteLast.angle,
                remoteLast.projectionSurvivesDisconnect,
            )
        }
    }

    private fun buildUiState(
        lastClimb: BoardStateManager.LastBoardClimb?,
        nearbyClimbs: List<NearbyClimb>,
        climbInfos: Map<String, ClimbDisplayInfo>,
        sessions: List<NearbySession>,
        sharingEnabled: Boolean,
        gradeScale: GradeScale
    ): BleShareUiState {
        // Determine on-board climb entry
        val rawOnBoard = resolveOnBoardClimb(lastClimb, nearbyClimbs, climbInfos, sessions, gradeScale)
        // Suppress on-board when the session queue already shows the same information.
        // SESSION_REMOTE from our own queue is redundant with the queue banner — and because
        // currentClimbName resolves asynchronously in a separate collector, showing it in
        // "Auf dem Board" causes name-drift between the two banners.
        // Never suppress:
        // - REMOTE_ACTIVE — someone else is actively climbing, board was overwritten
        // - SESSION_REMOTE only for role=NONE (no queue banner is rendered, so there is
        //   nothing to be redundant with — PARTICIPANT/HOST DO have a banner and must suppress)
        // - REMOTE_LAST while overwriteSessionUuid is set — board still shows external climb
        // Don't suppress during isConnecting — role isn't PARTICIPANT yet.
        val queueState = sessionQueueManager.state.value
        val sessionClimbUuid = queueState.currentClimb?.climbUuid
        val isNonSuppressible = rawOnBoard != null && (
            rawOnBoard.source == OnBoardSource.REMOTE_ACTIVE ||
            (rawOnBoard.source == OnBoardSource.SESSION_REMOTE && queueState.role == SessionRole.NONE) ||
            (rawOnBoard.source == OnBoardSource.REMOTE_LAST && overwriteSessionUuid != null)
        )
        val onBoardClimb = if (rawOnBoard != null && sessionClimbUuid != null &&
            queueState.role != SessionRole.NONE &&
            !isNonSuppressible &&
            !queueState.isConnecting
        ) null else rawOnBoard

        // Count connected-only entries (board occupied without climb)
        val boardOccupiedCount = nearbyClimbs.count { it.connectedOnly }

        // Map nearby sessions
        val nearbySessionEntries = sessions.map { session ->
            val info = session.currentClimbUuid?.let { climbInfos[it] }
            NearbySessionEntry(
                sessionId = session.sessionId,
                hostName = session.hostName.ifEmpty { "Unbekannt" },
                participantCount = session.participantCount,
                rssi = session.rssi,
                currentClimbUuid = session.currentClimbUuid,
                currentClimbName = info?.name,
                currentClimbGrade = info?.difficultyAverage?.let {
                    GradeDisplayHelper.formatDifficulty(it, gradeScale)
                },
                rawSession = session
            )
        }

        return BleShareUiState(
            onBoardClimb = onBoardClimb,
            boardOccupiedCount = boardOccupiedCount,
            nearbySessions = nearbySessionEntries,
            sharingEnabled = sharingEnabled,
            canRequestDisconnect = sharingEnabled && nearbyClimbs.any { !it.isLastClimb && !it.connectedOnly }
        )
    }

    /**
     * Resolves the single "on board" climb using source priority:
     * 1. REMOTE_ACTIVE  — someone is connected and climbing right now
     * 2. REMOTE_LAST (active overwrite) — external user disconnected but session hasn't
     *    sent a new climb yet → board still shows the external user's climb
     * 3. SESSION_REMOTE (session member) — queue data via GATT (HOST/PARTICIPANT)
     * 4. LOCAL_ACTIVE   — user's own climb (currently connected and sending)
     * 5. SESSION_REMOTE (non-participant) — from session BLE advertisement
     * 6. REMOTE_LAST (stale) — no overwrite tracking, or session has advanced
     * 7. LOCAL_MANAGER  — stale saved value from BoardStateManager
     *
     * LOCAL_ACTIVE (4) beats SESSION_REMOTE non-participant (5) because when the user
     * is actively connected and has sent a climb, THAT is what's on the board — not
     * whatever the nearby session's queue shows.
     *
     * Stale ClimbData detection via [lastSeenRemoteActiveUuid]:
     * The Kilter Board allows multiple simultaneous BLE connections. When we send a climb
     * and see remote ClimbData with the SAME UUID as before, it's stale (from before our send).
     * When the UUID CHANGES, the remote sent a NEW climb → board was overwritten → accept.
     *
     * Overwrite tracking via [overwriteSessionUuid]:
     * When REMOTE_ACTIVE is detected, we snapshot the session's current climb UUID.
     * After disconnect (→ REMOTE_LAST), the overwrite persists in the display as long as
     * the session hasn't advanced. Once the session sends a NEW climb (UUID changed),
     * the session has re-taken control and SESSION_REMOTE wins.
     * Cleared only when session advances or session ends — NOT when remote signals
     * briefly disappear (gap between ClimbData→LastClimb on disconnect).
     */
    private fun resolveOnBoardClimb(
        lastClimb: BoardStateManager.LastBoardClimb?,
        nearbyClimbs: List<NearbyClimb>,
        climbInfos: Map<String, ClimbDisplayInfo>,
        sessions: List<NearbySession> = emptyList(),
        gradeScale: GradeScale
    ): OnBoardClimbEntry? {
        fun ClimbDisplayInfo?.grade(): String? = this?.difficultyAverage?.let {
            GradeDisplayHelper.formatDifficulty(it, gradeScale)
        }

        val queueState = sessionQueueManager.state.value
        val memberSessionUuid = queueState.currentClimb?.climbUuid
        val nearbySessionUuid = sessions.firstOrNull { it.currentClimbUuid != null }?.currentClimbUuid
        val anySessionClimbUuid = memberSessionUuid ?: nearbySessionUuid

        // Session-advance detection for non-participants:
        // Sessions send climbs via GATT and advertise via session advertisements (not ClimbData).
        // So the remoteActive UUID-tracking doesn't cover them. Instead, track the session's
        // current climb UUID. When it changes (or first appears) while we have hasActiveClimb(),
        // the session overwrote our LEDs → clear our active state so SESSION_REMOTE can win.
        var activeClimbCleared = false
        if (queueState.role == SessionRole.NONE) {
            if (nearbySessionUuid != null) {
                // Overwrite only counts on a genuine UUID transition (A→B). First discovery
                // (null→A) is not proof our LEDs were overwritten — the session may have
                // been broadcasting UUID A long before we became active — so LOCAL_ACTIVE
                // must remain authoritative while we're connected and sending.
                val sessionOverwrites = lastSeenSessionClimbUuid != null &&
                    nearbySessionUuid != lastSeenSessionClimbUuid
                if (sessionOverwrites && climbAdvertiser.hasActiveClimb()) {
                    Log.d(TAG, "RESOLVE session overwrites ${lastSeenSessionClimbUuid?.take(8)}→${nearbySessionUuid.take(8)} → clearing LOCAL_ACTIVE")
                    climbAdvertiser.clearActiveClimb()
                    activeClimbCleared = true
                }
                lastSeenSessionClimbUuid = nearbySessionUuid
            } else {
                lastSeenSessionClimbUuid = null
            }
        }

        // 1. Remote active climb (ClimbData — someone is connected and climbing)
        //    If we have an active climb AND the remote UUID hasn't changed, the remote ClimbData
        //    is stale from before our send → skip. If UUID changed, the remote sent a NEW climb
        //    that overwrites ours → show as REMOTE_ACTIVE.
        val remoteActive = nearbyClimbs.firstOrNull {
            !it.connectedOnly && it.climbUuid.isNotEmpty() && !it.isLastClimb
        }
        val remoteActiveIsStale = remoteActive != null &&
            climbAdvertiser.hasActiveClimb() &&
            remoteActive.climbUuid == lastSeenRemoteActiveUuid
        if (remoteActive != null && !remoteActiveIsStale) {
            // Snapshot the session's current climb — used later to detect session advancement
            overwriteSessionUuid = anySessionClimbUuid
            val info = climbInfos[remoteActive.climbUuid]
            return OnBoardClimbEntry(
                climbUuid = remoteActive.climbUuid,
                angle = remoteActive.angle,
                name = info?.name,
                grade = info.grade(),
                source = OnBoardSource.REMOTE_ACTIVE,
                rssi = remoteActive.rssi
            )
        }

        // Clear overwrite tracking when not in a session — previous overwrite is irrelevant
        if (queueState.role == SessionRole.NONE && !queueState.isConnecting) {
            overwriteSessionUuid = null
        }

        // 2. Remote last climb — check if this is a recent overwrite that persists
        val remoteLast = nearbyClimbs.firstOrNull {
            !it.connectedOnly && it.climbUuid.isNotEmpty() && it.isLastClimb
        }
        if (remoteLast != null && remoteLast.projectionSurvivesDisconnect &&
            !climbAdvertiser.hasActiveClimb() && overwriteSessionUuid != null
        ) {
            // Session hasn't advanced since the overwrite → board still shows external climb
            if (anySessionClimbUuid == overwriteSessionUuid) {
                val info = climbInfos[remoteLast.climbUuid]
                return OnBoardClimbEntry(
                    climbUuid = remoteLast.climbUuid,
                    angle = remoteLast.angle,
                    name = info?.name,
                    grade = info.grade(),
                    source = OnBoardSource.REMOTE_LAST,
                    rssi = remoteLast.rssi,
                    isStillProjected = true,
                )
            }
            // Session advanced → it sent a new climb → session re-took control
            overwriteSessionUuid = null
        }
        // NOTE: Do NOT clear overwriteSessionUuid when both remoteLast and remoteActive
        // are null. There is a brief gap between ClimbData→LastClimb advertising on
        // disconnect where both are null. Clearing here would lose the overwrite state.
        // overwriteSessionUuid is cleared when: (a) session advances, (b) session ends.

        // 3. Session remote climb for session members — use GATT-synced queue data
        if (queueState.role == SessionRole.PARTICIPANT || queueState.role == SessionRole.HOST) {
            val currentItem = queueState.currentClimb
            if (currentItem != null) {
                val info = climbInfos[currentItem.climbUuid]
                val sessionInfo = sessionQueueManager.currentClimbInfo.value
                return OnBoardClimbEntry(
                    climbUuid = currentItem.climbUuid,
                    angle = currentItem.angle,
                    name = info?.name ?: sessionInfo?.name,
                    grade = info.grade() ?: sessionInfo.grade(),
                    source = OnBoardSource.SESSION_REMOTE
                )
            }
        }

        // 4. Local active climb — user is connected and sending, that's what's on the board
        //    Must come before non-participant session check: MY active send is authoritative.
        //    Skip if we just cleared the active climb due to session overwrite detection.
        if (lastClimb != null && climbAdvertiser.hasActiveClimb() && !activeClimbCleared) {
            val info = climbInfos[lastClimb.uuid]
            return OnBoardClimbEntry(
                climbUuid = lastClimb.uuid,
                angle = lastClimb.angle,
                name = info?.name ?: lastClimb.name,
                grade = info.grade(),
                source = OnBoardSource.LOCAL_ACTIVE
            )
        }

        // 5. Session remote climb for NON-PARTICIPANT — session advertisement
        val sessionClimb = sessions.firstOrNull { it.currentClimbUuid != null }
        if (sessionClimb != null) {
            val info = climbInfos[sessionClimb.currentClimbUuid]
            return OnBoardClimbEntry(
                climbUuid = sessionClimb.currentClimbUuid!!,
                angle = sessionClimb.currentClimbAngle,
                name = info?.name,
                grade = info.grade(),
                source = OnBoardSource.SESSION_REMOTE,
                rssi = sessionClimb.rssi
            )
        }

        // 6. Remote last climb (no overwrite tracking — stale signal)
        if (remoteLast != null && !climbAdvertiser.hasActiveClimb()) {
            val info = climbInfos[remoteLast.climbUuid]
            return OnBoardClimbEntry(
                climbUuid = remoteLast.climbUuid,
                angle = remoteLast.angle,
                name = info?.name,
                grade = info.grade(),
                source = OnBoardSource.REMOTE_LAST,
                rssi = remoteLast.rssi,
                isStillProjected = remoteLast.projectionSurvivesDisconnect,
            )
        }

        // 7. Local manager (stale saved value — no active remote or session signal)
        if (lastClimb != null) {
            val info = climbInfos[lastClimb.uuid]
            return OnBoardClimbEntry(
                climbUuid = lastClimb.uuid,
                angle = lastClimb.angle,
                name = info?.name ?: lastClimb.name,
                grade = info.grade(),
                source = OnBoardSource.LOCAL_MANAGER,
                isStillProjected = lastClimb.projectionSurvivesDisconnect,
            )
        }

        return null
    }

    /** Initiate a disconnect request to the connected user. */
    fun requestDisconnect() {
        if (_uiState.value.isRequestingDisconnect) return
        if (!sharingConfig.sharingEnabled.value) return
        _uiState.update { it.copy(isRequestingDisconnect = true, disconnectRequestNoResponse = false) }
        climbAdvertiser.advertiseDisconnectRequest()
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = scope.launch {
            delay(DISCONNECT_REQUEST_TIMEOUT_MS)
            cancelDisconnectRequest()
        }
    }

    private fun cancelDisconnectRequest() {
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = null
        _uiState.update { it.copy(isRequestingDisconnect = false, disconnectRequestNoResponse = false) }
    }

    /** Immediately update the on-board climb after session ends.
     *  Called from handleStop before the delayed stopSharing() cleanup fires. */
    fun setLastClimbAfterSession(climbUuid: String, angle: Int) {
        scope.launch { boardStateManager.setLastClimb(climbUuid, angle) }
    }

    companion object {
        private const val TAG = "CruxBLE/Manager"
        private const val DISCONNECT_REQUEST_TIMEOUT_MS = 20_000L
        /** Grace period after session ends during which remote LastClimb signals are ignored.
         *  Must exceed the scanner staleness timeout (15s) so stale participant advertising
         *  doesn't overwrite the correct board state set by stopSharing()/leaveSession(). */
        private const val POST_SESSION_GRACE_MS = 20_000L
    }
}
