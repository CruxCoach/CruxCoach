package com.cruxcoach.android.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.cruxcoach.android.ble.QueueItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class PlaylistCommandFeedbackKind { CONFLICT, UNAVAILABLE, FAILED }

data class PlaylistCommandFeedback(
    val kind: PlaylistCommandFeedbackKind,
    val action: String,
)

enum class PendingSuccessorOrigin { HOST_MIGRATION, BLUETOOTH_RECOVERY }

/**
 * An unsigned nearby advertisement offered to the user after a host handover.
 * The address and session id identify what may be re-resolved from the live
 * scanner; they do not authenticate the peer.
 */
data class PendingSuccessorJoin(
    val sessionId: Int,
    val deviceAddress: String,
    val hostName: String,
    val origin: PendingSuccessorOrigin,
)

/**
 * Bridges [SessionQueueManager] with BLE GATT for shared sessions.
 *
 * - **Host mode**: Starts GATT server + session advertising, pushes delta events to clients.
 * - **Participant mode**: Connects GATT client, sends commands, applies incoming events.
 * Published sessions are intentionally open to nearby compatible clients. A client must
 * complete JOIN before queue commands are accepted, but JOIN is not authentication.
 *
 * Privacy: No personal data is transmitted. Participants are identified only by
 * auto-assigned labels ("Teilnehmer 1", "Teilnehmer 2"). Device addresses (randomized
 * by Android BLE) are only used internally on the host for connection management and
 * are never shared with other participants.
 */
class SessionGattBridge(
    private val context: Context,
    private val queueManager: SessionQueueManager,
    private val gattServer: SessionGattServer,
    private val gattClient: SessionGattClient,
    private val advertiser: ClimbBleAdvertiser,
    private val nearbyScanner: NearbyClimbScanner,
    private val bleConnection: BoardBleConnection,
    private val boardStateManager: BoardStateManager,
    private val boardSessionManager: BoardSessionManager,
    private val shouldAdvertiseIndividualClimbs: () -> Boolean = { true },
    private val hasHostingPermissions: () -> Boolean = {
        BlePermissionHelper.hasAdvertisingPermission(context) &&
            BlePermissionHelper.hasConnectionPermission(context)
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    companion object {
        private const val TAG = "CruxBLE/Session"
        private const val MIGRATION_BASE_DELAY_MS = 1000L
        private const val MIGRATION_INDEX_STEP_MS = 3000L
        private const val HANDOFF_SENTINEL_DELAY_MS = 500L
        private const val COMMAND_RESULT_TIMEOUT_MS = 5000L
        private const val COMMAND_RESULT_CACHE_SIZE = 256
    }

    /**
     * Transport control a participant asked for, routed back through the
     * host's own playback logic instead of straight into the queue.
     *
     * Set by [com.cruxcoach.android.data.PlaylistPlaybackCoordinator]; a
     * callback rather than a constructor dependency because the coordinator
     * already depends on this class, and injecting it back would close the
     * cycle. Same shape as [SessionQueueManager.onRestRequested].
     *
     * Why this exists: advancing is phase-aware on the host. While a rest
     * counts down, the queue already sits on the *upcoming* climb, so "next"
     * means "skip the pause" — not "advance again". Calling
     * `queueManager.nextClimb()` directly from a remote command skipped that
     * rule and silently jumped a climb nobody had tried. Falls back to the
     * raw queue call when unset, so a bridge used without a coordinator
     * (tests, ad-hoc sessions before playback starts) keeps working.
     */
    @Volatile var onRemoteNext: (() -> Unit)? = null

    /** Participant-requested step back; see [onRemoteNext]. */
    @Volatile var onRemotePrev: (() -> Unit)? = null

    private var migrationJob: Job? = null
    private val migrationGeneration = AtomicLong(0L)
    private var recoveryHandoffJob: Job? = null
    private var joinJob: Job? = null
    private var hostJob: Job? = null
    @Volatile private var isSharing = false
    private var isRejoining = false
    private val commandGate = SessionCommandGate()
    private val nextRequestId = AtomicLong(System.nanoTime())
    private val pendingCommands = ConcurrentHashMap<Long, String>()
    private val _pendingCommandCount = MutableStateFlow(0)
    val pendingCommandCount = _pendingCommandCount.asStateFlow()
    private val _commandFeedback = MutableSharedFlow<PlaylistCommandFeedback>(extraBufferCapacity = 32)
    val commandFeedback = _commandFeedback.asSharedFlow()
    private val pendingSuccessorLock = Any()
    private val _pendingSuccessorJoin = MutableStateFlow<PendingSuccessorJoin?>(null)
    val pendingSuccessorJoin = _pendingSuccessorJoin.asStateFlow()
    private val handledCommandResults = object : LinkedHashMap<String, SessionCommandResult>(
        COMMAND_RESULT_CACHE_SIZE + 1, 0.75f, true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionCommandResult>?): Boolean =
            size > COMMAND_RESULT_CACHE_SIZE
    }
    /** Session id learned from the advertisement for the host we explicitly joined. */
    private var lastHostSessionId: Int = 0

    private fun projectionSurvivesCurrentBoardDisconnect(): Boolean =
        BoardProjectionPolicy.projectionSurvivesDisconnect(
            bleConnection.connectedBoardBrand.value
        )

    private fun currentBoardConnectionCapacity(): BoardConnectionCapacity =
        BoardControllerProfiles.forBoard(bleConnection.connectedBoard).connectionCapacity

    init {
        scope.launch {
            queueManager.state.collect { state ->
                if (!state.isActive && pendingCommands.isNotEmpty()) {
                    pendingCommands.clear()
                    _pendingCommandCount.value = 0
                }
                if (!state.isActive) {
                    cancelHostMigration()
                    cancelRecoveryHandoff()
                    clearPendingSuccessorJoin()
                }
            }
        }
        // Auto-recover BLE when Bluetooth is toggled off/on.
        // Intentionally never unregistered: this class is a @Singleton, so the receiver
        // lives for the entire process lifetime — no leak.
        val btReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (btState == BluetoothAdapter.STATE_ON) {
                    scope.launch {
                        delay(1000) // Give BLE stack time to initialize
                        recoverAfterBluetoothRestart()
                    }
                }
            }
        }
        context.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    // ===== Host mode =====

    /** Starts the host transport only when it is not already active. */
    fun ensureHostSharing(): Boolean {
        if (!isSharing) startSharing()
        return isSharing
    }

    fun startSharing() {
        val state = queueManager.state.value
        Log.d(TAG, "startSharing() called, role=${state.role}, isSharing=$isSharing, " +
            "hostJob=${hostJob != null}, joinJob=${joinJob != null}")
        if (state.role != SessionRole.HOST) {
            Log.w(TAG, "Cannot share: not in HOST mode")
            return
        }
        if (isSharing) return
        if (state.isPlaylist) {
            // Saved/running playlists are intentionally private. This guard is
            // independent of SessionQueueManager's visibility coercion so a
            // future caller cannot open a GATT server and advertise private
            // queue contents while the state still reads LOCAL_ONLY.
            Log.w(TAG, "Cannot share: saved playlist is local-only")
            queueManager.setVisibilityRequested(SessionVisibility.LOCAL_ONLY)
            return
        }

        if (state.visibilityRequested != SessionVisibility.JOINABLE) {
            Log.w(TAG, "Cannot share: host has not selected joinable visibility")
            return
        }

        // A participant can receive BLUETOOTH_CONNECT without ADVERTISE and
        // therefore be promoted successfully but be unable to host. Do not
        // open and immediately tear down a GATT server in that state: some
        // vendor BLE stacks stall openGattServer during the client-to-server
        // handover, which can trigger an ANR when this runs on Main.
        if (!hasHostingPermissions()) {
            Log.w(TAG, "Cannot share: missing session-hosting permission")
            queueManager.setVisibility(SessionVisibility.LOCAL_ONLY)
            return
        }
        commandGate.clear()
        // This server is a process singleton and its bounded channel outlives
        // one host session. Never let commands accepted during an earlier
        // teardown become the first commands of this session.
        gattServer.discardPendingCommands()

        // Before start(): Android hands a freshly opened server every device
        // already on the adapter, and our own board arrives before start()
        // even returns. Set after the fact it would be counted once as a
        // participant. Same wiring CruxRelayManager does for the relay server.
        gattServer.boardAddressProvider = { bleConnection.connectedBoard?.address }

        // Start GATT server
        if (!gattServer.start()) {
            Log.e(TAG, "startSharing(): GATT server failed to start")
            queueManager.setVisibility(SessionVisibility.LOCAL_ONLY)
            queueManager.setError(context.getString(R.string.ble_error_gatt_server))
            return
        }

        // Wire data providers
        gattServer.sessionInfoProvider = { queueManager.encodeSessionInfo() }
        gattServer.queueStateProvider = { queueManager.encodeQueueState() }
        gattServer.currentClimbProvider = { queueManager.encodeCurrentClimb() }
        gattServer.participantListProvider = { queueManager.encodeParticipantList() }

        // Wire queue change listeners to push delta events
        queueManager.onQueueChanged = {
            // Every page: a generated session can run to 38 entries and one
            // frame carries 29. The old single notification was silently
            // truncated past that and the participant dropped it whole.
            queueManager.encodeQueueStatePages().forEach { page ->
                gattServer.notifyAll(SessionGattUuids.QUEUE_STATE, page)
            }
            // Update session advertisement scan response (e.g. first climb added)
            if (isSharing) {
                updateSessionAdvertising()
            }
        }
        queueManager.onCurrentClimbChanged = {
            val encoded = queueManager.encodeCurrentClimb()
            Log.d(TAG, "onCurrentClimbChanged: notifying CURRENT_CLIMB (${encoded.size} bytes, index=${encoded[0].toInt() and 0xFF})")
            gattServer.notifyAll(
                SessionGattUuids.CURRENT_CLIMB,
                encoded
            )
            // Update board state synchronously so the chip reflects the new climb immediately.
            // Use setLastClimbQuick (not setLastClimb) to avoid race conditions: rapid
            // navigation launches concurrent coroutines whose async name resolution can
            // finish out of order, causing the final state to show an earlier climb.
            // Full persistence + name resolution happens in stopSharing()/leaveSession().
            val queueState = queueManager.state.value
            val currentClimb = queueState.currentClimb
            if (currentClimb != null && !queueState.externalBoardOverride) {
                boardStateManager.setLastClimbQuick(
                    currentClimb.climbUuid,
                    currentClimb.angle,
                    projectionSurvivesCurrentBoardDisconnect(),
                )
            }
            // Update session advertisement scan response with new current climb
            if (isSharing) {
                updateSessionAdvertising()
            }
        }
        queueManager.onParticipantsChanged = {
            val s = queueManager.state.value
            Log.d(TAG, "onParticipantsChanged: ${s.participantCount} participants, notifying clients")
            gattServer.notifyAll(
                SessionGattUuids.PARTICIPANT_LIST,
                queueManager.encodeParticipantList()
            )
            gattServer.notifyAll(
                SessionGattUuids.SESSION_INFO,
                queueManager.encodeSessionInfo()
            )
            updateSessionAdvertising()
        }
        queueManager.onSessionInfoChanged = {
            gattServer.notifyAll(
                SessionGattUuids.SESSION_INFO,
                queueManager.encodeSessionInfo(),
            )
        }

        // Cancel previous host collectors to avoid duplicate processing after BT recovery
        hostJob?.cancel()
        hostJob = scope.launch {
            // Listen for GATT commands from clients
            launch {
                gattServer.commands.collect { cmd ->
                    handleClientCommand(cmd.deviceAddress, cmd.data)
                }
            }

            // Listen for GATT connection events
            launch {
                gattServer.connectionEvents.collect { event ->
                    when (event) {
                        is GattConnectionEvent.Connected -> {
                            Log.d(TAG, "Client connected: ${event.deviceAddress}")
                            // BLE legacy connectable advertising stops after a client connects.
                            // Restart immediately so other devices can still discover the session.
                            if (isSharing) {
                                updateSessionAdvertising()
                            }
                        }
                        is GattConnectionEvent.Disconnected -> {
                            Log.d(TAG, "Client disconnected: ${event.deviceAddress}")
                            commandGate.remove(event.deviceAddress)
                            queueManager.removeParticipant(event.deviceAddress)
                            // Restart advertising in case it stopped
                            if (isSharing) {
                                updateSessionAdvertising()
                            }
                        }
                    }
                }
            }

            // Broadcast the rest phase.
            //
            // Without this a participant only ever hears CurrentChanged, which
            // the queue emits when the advance ARMS the pause — so it jumped
            // straight to the upcoming climb while the host counted down.
            // Measured on two devices 2026-08-06: host "Pause 0:26 · next DA
            // REAL 6A+", participant showing DA REAL 6A+ ready to climb.
            //
            // Edge-triggered rather than per-tick: the countdown ticks once a
            // second and notifying every tick would spend the connection on
            // data the participant can derive itself from its own timer.
            launch {
                var wasResting = false
                boardSessionManager.restTimer.collect { rest ->
                    if (rest.isRunning && !wasResting) {
                        val index = queueManager.state.value.currentIndex
                        Log.i(
                            TAG,
                            "event=rest_broadcast state=started " +
                                "seconds=${rest.secondsRemaining} nextIndex=$index",
                        )
                        gattServer.notifyAll(
                            SessionGattUuids.QUEUE_EVENT,
                            SessionQueueProtocol.encodeEventRestStarted(
                                rest.secondsRemaining, index,
                            ),
                        )
                    } else if (!rest.isRunning && wasResting) {
                        Log.i(TAG, "event=rest_broadcast state=ended")
                        gattServer.notifyAll(
                            SessionGattUuids.QUEUE_EVENT,
                            SessionQueueProtocol.encodeEventRestEnded(),
                        )
                    }
                    wasResting = rest.isRunning
                }
            }
        }

        // Auto-import the active/last climb from nearby devices into the queue.
        // Lets the session start with the boulder already on the board, so the other
        // user joins and immediately sees their climb as the first queue item.
        // SKIPPED for playlist-driven queues: a generated training session is a
        // plan — nearby strangers' climbs must not be injected into it (they
        // stay visible in the nearby section and can be added by hand).
        if (!queueManager.isPlaylistQueue) {
            val existingUuids = queueManager.state.value.queue.map { it.climbUuid }.toSet()
            val nearbyToImport = nearbyScanner.nearbyClimbs.value
                .filter { climb ->
                    !climb.connectedOnly && climb.climbUuid.isNotEmpty() && climb.climbUuid !in existingUuids
                }
                .sortedByDescending { it.rssi }
            if (nearbyToImport.isNotEmpty()) {
                Log.d(TAG, "Auto-importing ${nearbyToImport.size} nearby climb(s) into queue")
                nearbyToImport.forEach { climb ->
                    queueManager.addClimb(climb.climbUuid, climb.angle)
                    Log.d(TAG, "Auto-added: ${climb.climbUuid.take(8)} angle=${climb.angle} isLastClimb=${climb.isLastClimb}")
                }
            }
        }

        // Suppress individual climb advertising — climb data is now embedded in the
        // session advertisement's scan response (same MAC, no separate advertising set).
        advertiser.suppressClimbAdvertising = true

        // When the first queue climb is sent, the board state is updated by the advertiser's
        // advertiseClimb() → boardStateManager.setLastClimb(). No clearing needed.
        queueManager.onFirstQueueClimbSent = {
            Log.d(TAG, "First queue climb sent to board — board state updated by advertiser")
        }

        // Request other devices to disconnect from the board so the host can connect.
        // The DisconnectRequest is sent via BLE advertising — it only affects OTHER
        // devices; the host doesn't receive its own advertising packets.
        val exclusiveNearbyOwner = nearbyScanner.nearbyClimbs.value.any {
            !it.isLastClimb &&
                !it.supportsConcurrentConnections &&
                it.acceptsDisconnectRequests
        }
        if (currentBoardConnectionCapacity() == BoardConnectionCapacity.SINGLE ||
            exclusiveNearbyOwner
        ) {
            Log.d(TAG, "Sending DisconnectRequest to free exclusive board for session host")
            advertiser.advertiseDisconnectRequest()
        }

        // Start advertising session (replaces the DisconnectRequest advertising)
        if (!updateSessionAdvertising()) {
            Log.e(TAG, "Session publication failed; continuing as local-only")
            hostJob?.cancel()
            hostJob = null
            gattServer.stop()
            commandGate.clear()
            queueManager.onQueueChanged = null
            queueManager.onCurrentClimbChanged = null
            queueManager.onParticipantsChanged = null
            queueManager.onSessionInfoChanged = null
            queueManager.onFirstQueueClimbSent = null
            advertiser.stopSessionAdvertising()
            advertiser.stopAdvertising()
            advertiser.suppressClimbAdvertising = false
            queueManager.setVisibility(SessionVisibility.LOCAL_ONLY)
            queueManager.setError(context.getString(R.string.ble_error_publish_failed))
            restartClimbAdvertisingIfConnected()
            return
        }
        isSharing = true
        queueManager.setVisibility(SessionVisibility.JOINABLE)

        // Stop the disconnect request after a brief pulse. The primary advertising set
        // (disconnect request, 20s timeout) runs in parallel with the session set —
        // without this cleanup it spams nearby scanners for the full 20 seconds.
        scope.launch {
            delay(2000)
            if (isSharing) advertiser.stopAdvertising()
        }
        Log.d(TAG, "Sharing started")
    }

    fun stopSharing() {
        stopSharing(allowBoardRelease = true)
    }

    /**
     * @param endForEveryone true when the host wants the playlist over rather
     *   than handed on. Without it the sentinel starts host migration and the
     *   group keeps climbing — which is the right default, but it used to be
     *   the only option and the UI called it "end session".
     */
    fun stopSharing(allowBoardRelease: Boolean, endForEveryone: Boolean = false) {
        cancelRecoveryHandoff()
        clearPendingSuccessorJoin()
        Log.d(TAG, "stopSharing() called, isSharing=$isSharing, " +
            "connectedClients=${gattServer.getConnectedCount()}, " +
            "boardConnected=${bleConnection.connectionState.value}")
        commandGate.clear()
        queueManager.setVisibility(SessionVisibility.LOCAL_ONLY)
        // Capture last queue climb BEFORE endQueue() clears it (called by UI right after)
        val sessionState = queueManager.state.value
        val lastQueueClimb = sessionState.currentClimb
            ?.takeUnless { sessionState.externalBoardOverride }
        val projectionSurvivesDisconnect = projectionSurvivesCurrentBoardDisconnect()
        // A viable successor must have completed JOIN (counted by the queue)
        // and still have a live GATT link. Either signal on its own can be
        // stale while callbacks and commands cross during teardown.
        val hasSuccessor = sessionState.participantCount > 1 &&
            gattServer.getConnectedCount() > 0
        Log.d(TAG, "stopSharing(): lastQueueClimb=${lastQueueClimb?.climbUuid?.take(8)}")

        // Update board state SYNCHRONOUSLY before returning. The UI calls endQueue()
        // right after stopSharing(), which triggers the combine flow. Without this
        // immediate update, boardStateManager still has the stale pre-session climb.
        if (lastQueueClimb != null) {
            boardStateManager.setLastClimbQuick(
                lastQueueClimb.climbUuid,
                lastQueueClimb.angle,
                projectionSurvivesDisconnect,
            )
        }

        // Notify all clients that the session is ending (participantCount=0 = sentinel).
        // This ensures participants detect the end even if GATT disconnect callbacks
        // don't fire reliably on their side.
        Log.d(TAG, "stopSharing(): sending session-ended sentinel (participantCount=0)")
        gattServer.notifyAll(
            SessionGattUuids.SESSION_INFO,
            SessionQueueProtocol.encodeSessionEnded(migrate = !endForEveryone)
        )
        isSharing = false
        hostJob?.cancel()
        hostJob = null
        joinJob?.cancel()
        joinJob = null
        // Release the physical controller for a real successor. With no
        // successor, Aurora-family controllers can still be released because
        // they retain their LEDs; a MoonBoard must remain connected or its
        // final projection disappears immediately.
        val releaseBoard = allowBoardRelease &&
            BoardProjectionPolicy.shouldReleaseBoardAfterHosting(
                hasSuccessor = hasSuccessor,
                projectionSurvivesDisconnect = projectionSurvivesDisconnect,
                connectionCapacity = currentBoardConnectionCapacity(),
                pinnedByAnotherFeature = bleConnection.hasOtherKeepAliveOwners(
                    BoardConnectionOwner.SESSION,
                ),
            )
        if (bleConnection.connectionState.value == ConnectionState.CONNECTED && releaseBoard) {
            Log.d(TAG, "stopSharing(): releasing board (successor=$hasSuccessor retained=$projectionSurvivesDisconnect)")
            bleConnection.disconnect()
        } else if (bleConnection.connectionState.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "stopSharing(): keeping volatile projection connected (no successor)")
        }
        // Brief delay so the sentinel notification is delivered before we tear down
        // the server and disconnect clients. Participants use the sentinel to trigger
        // host migration instead of just ending the queue.
        scope.launch {
            delay(500)
            gattServer.stop()
            advertiser.stopSessionAdvertising()
            // Re-enable individual climb advertising (was suppressed during session)
            advertiser.suppressClimbAdvertising = false
            // A retained controller that was released transitions to
            // LAST_CLIMB. A solo MoonBoard host stays physically connected,
            // so restore an active ClimbData advertisement instead of claiming
            // the sender has disconnected.
            if (!shouldAdvertiseIndividualClimbs()) {
                advertiser.stopAdvertising()
            } else if (lastQueueClimb != null) {
                if (releaseBoard) {
                    boardStateManager.setLastClimb(
                        lastQueueClimb.climbUuid,
                        lastQueueClimb.angle,
                        projectionSurvivesDisconnect,
                    )
                    advertiser.advertiseLastClimb(
                        lastQueueClimb.climbUuid,
                        lastQueueClimb.angle,
                        projectionSurvivesDisconnect,
                    )
                } else {
                    advertiser.advertiseClimb(
                        lastQueueClimb.climbUuid,
                        lastQueueClimb.angle,
                        projectionSurvivesDisconnect = projectionSurvivesDisconnect,
                    )
                }
            } else {
                restartClimbAdvertisingIfConnected()
            }
            Log.d(TAG, "stopSharing(): teardown complete")
        }
        Log.d(TAG, "stopSharing(): sentinel sent, releaseBoard=$releaseBoard, teardown scheduled")
    }

    /**
     * Called when Bluetooth comes back on. A joinable host first restores its
     * own authoritative transport. An unsigned nearby advertisement may then
     * be offered as an explicit switch, but can never change the host role on
     * its own.
     */
    internal fun recoverAfterBluetoothRestart() {
        val state = queueManager.state.value
        if (state.role != SessionRole.HOST) return
        // Asked of the wish, not the state: a failed startSharing() sets the
        // state to LOCAL_ONLY, and reading that here is what made the failure
        // permanent — this early return fired on the very sessions that were
        // waiting for Bluetooth to come back.
        if (state.visibilityRequested != SessionVisibility.JOINABLE) {
            Log.d(TAG, "BT recovered — local-only session stays unpublished")
            return
        }

        if (!isSharing) {
            // Session was started while BT was off — GATT server never initialized.
            // Now that BT is on, start sharing for the first time.
            Log.d(TAG, "BT turned on — starting session sharing (was pending)")
            startSharing()
            return
        }

        Log.d(TAG, "BT recovered — restarting GATT server + session advertising")
        isSharing = false
        gattServer.stop()
        startSharing()

        strongestConnectableSuccessor(excludedSessionId = state.sessionId)?.let { candidate ->
            Log.d(
                TAG,
                "BT recovered with an unverified nearby session; awaiting explicit join consent",
            )
            stageSuccessorJoin(candidate, PendingSuccessorOrigin.BLUETOOTH_RECOVERY)
        }
    }

    private fun strongestConnectableSuccessor(excludedSessionId: Int): NearbySession? =
        nearbyScanner.nearbySessions.value
            .asSequence()
            .filter { session ->
                session.sessionId != excludedSessionId &&
                    session.deviceAddress.isNotBlank() &&
                    session.device != null
            }
            .maxByOrNull { it.rssi }

    private fun stageSuccessorJoin(
        candidate: NearbySession,
        origin: PendingSuccessorOrigin,
    ) {
        val pending = PendingSuccessorJoin(
            sessionId = candidate.sessionId,
            deviceAddress = candidate.deviceAddress,
            hostName = candidate.hostName.trim(),
            origin = origin,
        )
        synchronized(pendingSuccessorLock) {
            // Never let a later unauthenticated advertisement replace the
            // exact offer the user is currently reading.
            if (_pendingSuccessorJoin.value == null) {
                _pendingSuccessorJoin.value = pending
            }
        }
    }

    /** Joins only the exact still-live advertisement the user approved once. */
    fun confirmPendingSuccessorJoin() {
        val pending = consumePendingSuccessorJoin() ?: return
        val state = queueManager.state.value
        val originStillValid = when (pending.origin) {
            PendingSuccessorOrigin.HOST_MIGRATION ->
                state.role == SessionRole.PARTICIPANT && state.queue.isNotEmpty()
            PendingSuccessorOrigin.BLUETOOTH_RECOVERY ->
                state.role == SessionRole.HOST
        }
        if (!originStillValid) return

        val live = nearbyScanner.nearbySessions.value.firstOrNull { session ->
            session.sessionId == pending.sessionId &&
                session.deviceAddress == pending.deviceAddress &&
                session.device != null
        }
        val device = live?.device
        if (device == null) {
            Log.w(TAG, "Approved successor vanished or changed before connection; preserving queue")
            preserveQueueAfterSuccessorDeclined(pending.origin)
            return
        }

        when (pending.origin) {
            PendingSuccessorOrigin.HOST_MIGRATION -> {
                cancelHostMigration()
                joinSession(device, pending.sessionId)
            }
            PendingSuccessorOrigin.BLUETOOTH_RECOVERY -> {
                beginRecoveredHostHandoff(pending, device)
            }
        }
    }

    /** Keeps the current queue/host and consumes the untrusted offer once. */
    fun declinePendingSuccessorJoin() {
        val pending = consumePendingSuccessorJoin() ?: return
        preserveQueueAfterSuccessorDeclined(pending.origin)
    }

    private fun preserveQueueAfterSuccessorDeclined(origin: PendingSuccessorOrigin) {
        when (origin) {
            PendingSuccessorOrigin.HOST_MIGRATION -> {
                val state = queueManager.state.value
                if (state.role == SessionRole.PARTICIPANT && state.queue.isNotEmpty()) {
                    cancelHostMigration()
                    queueManager.promoteToHost(
                        context.getString(R.string.ble_session_name_promoted),
                    )
                    Log.d(TAG, "Unverified successor declined; queue promoted locally")
                }
            }
            PendingSuccessorOrigin.BLUETOOTH_RECOVERY -> {
                // recoverAfterBluetoothRestart() restored this host before it
                // exposed the offer, so declining requires no state change.
                Log.d(TAG, "Unverified successor declined; recovered host retained")
            }
        }
    }

    private fun stopRecoveredHostTransportForExplicitJoin() {
        isSharing = false
        hostJob?.cancel()
        hostJob = null
        commandGate.clear()
        gattServer.stop()
        queueManager.onQueueChanged = null
        queueManager.onCurrentClimbChanged = null
        queueManager.onParticipantsChanged = null
        queueManager.onSessionInfoChanged = null
        queueManager.onFirstQueueClimbSent = null
        advertiser.stopSessionAdvertising()
        advertiser.stopAdvertising()
        queueManager.setVisibility(SessionVisibility.LOCAL_ONLY)
    }

    /**
     * Give participants the same reliable migration sentinel used by ordinary
     * host teardown before a user-approved switch. The advertisement was
     * re-resolved at the click boundary above; the captured address/session-id
     * pair is frozen for this bounded delivery window so radio churn cannot
     * silently retarget the answer.
     */
    private fun beginRecoveredHostHandoff(
        pending: PendingSuccessorJoin,
        device: BluetoothDevice,
    ) {
        cancelRecoveryHandoff()
        val hostedSessionId = queueManager.state.value.sessionId
        gattServer.notifyAll(
            SessionGattUuids.SESSION_INFO,
            SessionQueueProtocol.encodeSessionEnded(migrate = true),
        )
        recoveryHandoffJob = scope.launch {
            try {
                delay(HANDOFF_SENTINEL_DELAY_MS)
                val state = queueManager.state.value
                if (state.role != SessionRole.HOST || state.sessionId != hostedSessionId) {
                    Log.w(TAG, "Recovered-host handoff cancelled because the local session changed")
                    return@launch
                }
                stopRecoveredHostTransportForExplicitJoin()
                joinSession(device, pending.sessionId)
            } finally {
                recoveryHandoffJob = null
            }
        }
    }

    private fun cancelRecoveryHandoff() {
        recoveryHandoffJob?.cancel()
        recoveryHandoffJob = null
    }

    private fun consumePendingSuccessorJoin(): PendingSuccessorJoin? =
        synchronized(pendingSuccessorLock) {
            _pendingSuccessorJoin.value.also { _pendingSuccessorJoin.value = null }
        }

    private fun clearPendingSuccessorJoin() {
        synchronized(pendingSuccessorLock) {
            _pendingSuccessorJoin.value = null
        }
    }

    // ===== Participant mode =====

    fun joinSession(device: BluetoothDevice) {
        cancelHostMigration()
        cancelRecoveryHandoff()
        joinSession(device, expectedSessionId = null)
    }

    private fun joinSession(device: BluetoothDevice, expectedSessionId: Int?) {
        clearPendingSuccessorJoin()
        Log.d(TAG, "joinSession() called, device=${device.address}, " +
            "isRejoining=$isRejoining, joinJob=${joinJob != null}, " +
            "clientState=${gattClient.connectionState.value}")
        // Record the host's advertised session ID. It is what the participant
        // carries as session identity from here on — the JOIN handshake never
        // sends it back, so the user-selected scan row is the only source.
        val selectedSessionId = expectedSessionId ?: nearbyScanner.nearbySessions.value
            .firstOrNull { session -> session.device?.address == device.address }
            ?.sessionId
        selectedSessionId?.let { sessionId ->
            lastHostSessionId = sessionId
            Log.d(TAG, "joinSession: tracking host sessionId=$lastHostSessionId for stale filter")
        }
        // Cancel any previous join collectors to avoid stacking.
        // Set isRejoining so the old collector's DISCONNECTED event doesn't trigger migration.
        isRejoining = true
        joinJob?.cancel()
        queueManager.setConnecting()

        // Suppress individual climb advertising — session handles sharing via GATT
        advertiser.suppressClimbAdvertising = true

        // Wire remote command sender for participant mode
        queueManager.remoteAddClimb = { uuid, angle -> sendAddClimb(uuid, angle) }

        gattClient.connect(device)

        joinJob = scope.launch {
            isRejoining = false
            Log.d(TAG, "joinSession: joinJob started, isRejoining reset to false")

            // Listen for connection state
            launch {
                gattClient.connectionState.collect { state ->
                    Log.d(TAG, "joinSession: connectionState changed to $state")
                    when (state) {
                        SessionClientState.CONNECTED -> {
                            cancelHostMigration()
                            Log.d(TAG, "Connected to host, sending JOIN command")
                            val joinSent = gattClient.sendCommand(SessionQueueProtocol.encodeJoin(""))
                            Log.d(TAG, "JOIN command sent: success=$joinSent")
                            gattClient.readInitialState()
                            // The host's id, not a literal 0. Without it a
                            // participant had no session identity at all, and
                            // the on-board resolver could not tell this
                            // session's advertisement from a stranger's.
                            queueManager.setParticipantRole(lastHostSessionId, "")
                            Log.d(TAG, "setParticipantRole complete, role=${queueManager.state.value.role}")
                        }
                        SessionClientState.DISCONNECTED -> {
                            if (isRejoining) {
                                Log.d(TAG, "Ignoring DISCONNECTED during rejoin (isRejoining=true)")
                                return@collect
                            }
                            val qState = queueManager.state.value
                            Log.d(TAG, "GATT client disconnected, role=${qState.role}, " +
                                "isConnecting=${qState.isConnecting}, " +
                                "queue=${qState.queue.size}, " +
                                "participantIndex=${qState.participantIndex}")
                            if (qState.isConnecting && qState.queue.isNotEmpty()) {
                                // Connection failed during migration (joining the new host) —
                                // retry migration so the next candidate can take over.
                                Log.d(TAG, "DISCONNECTED during migration join (queue=${qState.queue.size}) → retrying migration")
                                migrationJob = null  // reset so attemptHostMigration() can start fresh
                                attemptHostMigration()
                            } else if (qState.isConnecting) {
                                queueManager.setError(context.getString(R.string.ble_error_connect_failed))
                                advertiser.suppressClimbAdvertising = false
                                restartClimbAdvertisingIfConnected()
                                boardSessionManager.endSession()
                            } else if (qState.role == SessionRole.PARTICIPANT) {
                                Log.d(TAG, "Participant disconnected from host → attempting migration")
                                attemptHostMigration()
                            } else {
                                Log.d(TAG, "DISCONNECTED but role=${qState.role}, not migrating")
                            }
                        }
                        SessionClientState.CONNECTING -> {
                            Log.d(TAG, "GATT client connecting...")
                        }
                    }
                }
            }

            // Listen for queue events
            launch {
                gattClient.queueEvents.collect { data ->
                    val event = SessionQueueProtocol.decodeEvent(data) ?: return@collect
                    applyRemoteEvent(event)
                }
            }

            // Listen for session info updates (host name + participant count).
            // participantCount == 0 is a sentinel meaning "session ended by host".
            launch {
                gattClient.sessionInfoUpdates.collect { data ->
                    val info = SessionQueueProtocol.decodeSessionInfo(data) ?: return@collect
                    if (info.participantCount == 0) {
                        if (SessionQueueProtocol.isFinalSessionEnd(data)) {
                            Log.d(TAG, "Host ended the playlist for everyone")
                            handleSessionEndedForEveryone()
                            return@collect
                        }
                        Log.d(TAG, "Received session-ended signal from host")
                        handleSessionEndedByHost()
                        return@collect
                    }
                    queueManager.updateSessionInfo(
                        info.hostName,
                        info.participantCount,
                        info.awaitingExplicitSend,
                    )
                }
            }

            // Listen for current climb changes (index navigation)
            launch {
                gattClient.currentClimbUpdates.collect { data ->
                    if (data.isNotEmpty()) {
                        val index = data[0].toInt() and 0xFF
                        if (SessionQueueManager.isExternalBoardOverride(data)) {
                            Log.d(TAG, "Physical board was overwritten by an external app")
                            queueManager.applyRemoteExternalBoardWrite()
                        } else if (index != 0xFF) {
                            Log.d(TAG, "Current climb changed to index $index")
                            queueManager.applyRemoteCurrentIndex(index)
                            queueManager.sendCurrentClimbToBoard()
                        }
                    }
                }
            }

            // Listen for full queue state (initial sync + updates).
            // Reassembled from pages — applied only once every page of a set
            // has arrived, so a half-received queue never replaces a whole one.
            launch {
                val pages = mutableMapOf<Int, List<QueueItem>>()
                var expectedPageCount = -1
                var pendingIndex = 0
                gattClient.queueStateUpdates.collect { data ->
                    val parsed = SessionQueueProtocol.decodeQueueState(data) ?: return@collect
                    if (parsed.pageCount != expectedPageCount) {
                        // A new set supersedes whatever was half-collected.
                        pages.clear()
                        expectedPageCount = parsed.pageCount
                    }
                    pendingIndex = parsed.currentIndex
                    pages[parsed.page] = parsed.items
                    if (pages.size < expectedPageCount) {
                        Log.d(TAG, "Queue state page ${parsed.page + 1}/$expectedPageCount")
                        return@collect
                    }
                    val items = (0 until expectedPageCount).flatMap { pages[it].orEmpty() }
                    pages.clear()
                    expectedPageCount = -1
                    Log.d(TAG, "Received queue state: ${items.size} items, currentIndex=$pendingIndex")
                    queueManager.applyRemoteState(pendingIndex, items)
                }
            }

            // Listen for participant list updates (names + our index)
            launch {
                gattClient.participantListUpdates.collect { data ->
                    val names = SessionQueueProtocol.decodeParticipantList(data) ?: return@collect
                    queueManager.applyRemoteParticipants(names)
                }
            }
        }
    }

    fun leaveSession() {
        cancelHostMigration()
        cancelRecoveryHandoff()
        clearPendingSuccessorJoin()
        Log.d(TAG, "leaveSession() called, joinJob=${joinJob != null}, " +
            "clientState=${gattClient.connectionState.value}, " +
            "role=${queueManager.state.value.role}")
        joinJob?.cancel()
        joinJob = null
        // Re-enable individual climb advertising
        advertiser.suppressClimbAdvertising = false
        restartClimbAdvertisingIfConnected()
        // Set last climb to the current queue item so the banner shows what was on the board
        val queueState = queueManager.state.value
        val lastItem = queueState.currentClimb
            ?.takeUnless { queueState.externalBoardOverride }
        val projectionSurvivesDisconnect = projectionSurvivesCurrentBoardDisconnect()
        // Update board state SYNCHRONOUSLY before endQueue() triggers combine flow
        if (lastItem != null) {
            boardStateManager.setLastClimbQuick(
                lastItem.climbUuid,
                lastItem.angle,
                projectionSurvivesDisconnect,
            )
        }
        // End queue immediately so UI updates right away (banner reappears)
        queueManager.endQueue()
        boardSessionManager.endSession()
        // Async: full persistence + name resolution
        if (lastItem != null) {
            scope.launch {
                boardStateManager.setLastClimb(
                    lastItem.climbUuid,
                    lastItem.angle,
                    projectionSurvivesDisconnect,
                )
            }
        }
        // Send leave command, then wait briefly so the host processes it before we disconnect
        scope.launch {
            Log.d(TAG, "leaveSession: sending LEAVE command, " +
                "gatt=${gattClient.connectionState.value}")
            val leaveSent = gattClient.sendCommand(SessionQueueProtocol.encodeLeave())
            Log.d(TAG, "leaveSession: LEAVE command sent: success=$leaveSent")
            delay(300)
            Log.d(TAG, "leaveSession: disconnecting GATT client")
            gattClient.disconnect()
            Log.d(TAG, "leaveSession: disconnect complete")
        }
    }

    // ===== Participant: send commands to host =====

    fun sendAddClimb(climbUuid: String, angle: Int) {
        sendParticipantCommand("add", SessionCommand.Add(climbUuid, angle))
    }

    fun sendRemoveClimb(index: Int) {
        sendParticipantCommand("remove", SessionCommand.Remove(index))
    }

    fun sendNext() = sendParticipantCommand("next", SessionCommand.Next)

    fun sendPrev() = sendParticipantCommand("prev", SessionCommand.Prev)

    fun sendSetCurrent(index: Int) =
        sendParticipantCommand("setCurrent($index)", SessionCommand.SetCurrent(index))

    fun sendMove(from: Int, to: Int) =
        sendParticipantCommand("move($from→$to)", SessionCommand.Move(from, to))

    fun sendResend() =
        sendParticipantCommand("resend", SessionCommand.Resend)

    /**
     * Fire a participant's control command at the host, and say so when it
     * does not go out.
     *
     * These are the only way a participant can steer the playlist, and the
     * write can fail for mundane reasons — the command characteristic not
     * resolved yet, the GATT link dropped. The result used to be discarded,
     * so a failed write looked exactly like a working one that the host chose
     * to ignore: the button did nothing and nothing said why. The command is
     * still fire-and-forget by design (the host re-broadcasts the resulting
     * state, so there is nothing local to roll back) — this only makes the
     * failure findable.
     */
    private fun sendParticipantCommand(label: String, command: SessionCommand) {
        scope.launch {
            val state = queueManager.state.value
            val context = SessionCommandRebaser.context(
                command, state.sessionId, state.currentIndex, state.queue,
            )
            // Invalid local indices are a local conflict, not a malformed BLE write.
            if (context == null) {
                _commandFeedback.tryEmit(
                    PlaylistCommandFeedback(PlaylistCommandFeedbackKind.CONFLICT, label),
                )
                return@launch
            }
            val requestId = nextRequestId.incrementAndGet()
            pendingCommands[requestId] = label
            _pendingCommandCount.value = pendingCommands.size
            val extendedPayload = SessionQueueProtocol.encodeCommandRequest(requestId, command, context)
            val payload = if (gattClient.supportsCommandSize(extendedPayload.size)) {
                extendedPayload
            } else {
                // Android 9 fallback when MTU negotiation is unavailable.
                // The host's authoritative state still converges through its
                // normal broadcast, but this old-size command has no result id.
                pendingCommands.remove(requestId)
                _pendingCommandCount.value = pendingCommands.size
                SessionQueueProtocol.encodeCommand(command)
            }
            if (gattClient.sendCommand(payload)) {
                // Logged on success too, not only on failure. Only logging the
                // failure leaves the working path silent, and during the
                // 2026-08-06 two-device test that made three candidate causes
                // for "next does nothing" indistinguishable: no line meant
                // "never pressed", "not sent", "sent and ignored" or "applied"
                // equally well. A support log has to separate those.
                Log.i(TAG, "event=transport_sent action=$label")
                // Old 0.2.2 hosts ignore the appended context and cannot send
                // a result. They still broadcast authoritative state, so age
                // the indicator out without retrying (a retry would duplicate Add).
                if (payload === extendedPayload) {
                    delay(COMMAND_RESULT_TIMEOUT_MS)
                    if (pendingCommands.remove(requestId) != null) {
                        _pendingCommandCount.value = pendingCommands.size
                    }
                }
            } else {
                pendingCommands.remove(requestId)
                _pendingCommandCount.value = pendingCommands.size
                _commandFeedback.tryEmit(
                    PlaylistCommandFeedback(PlaylistCommandFeedbackKind.UNAVAILABLE, label),
                )
                Log.w(TAG, "event=transport_send_failed action=$label")
            }
        }
    }

    // ===== Internal: Host processes commands from clients =====

    private fun handleClientCommand(deviceAddress: String, data: ByteArray) {
        val request = SessionQueueProtocol.decodeCommandRequest(data)
        if (request == null) {
            Log.w(TAG, "Failed to decode session command (${data.size} bytes)")
            if (!commandGate.hasJoined(deviceAddress)) rejectClient(deviceAddress)
            return
        }
        val receivedCommand = request.command

        if (receivedCommand is SessionCommand.Join) {
            if (!gattServer.isConnected(deviceAddress)) {
                Log.w(TAG, "Rejecting JOIN from a disconnected GATT address")
                rejectClient(deviceAddress)
                return
            }
            if (commandGate.join(deviceAddress)) {
                val count = queueManager.state.value.participants.size
                // The host names participants and hands the names out over GATT,
                // so a literal here reaches every guest's screen regardless of
                // their own locale — same trap as the promoteToHost name below.
                val label = context.getString(R.string.ble_participant_label, count + 1)
                // INFO and structured: during the 2026-08-06 two-device test
                // the host produced no app-level line for a join at all, so
                // the only evidence a participant had arrived was Android's
                // own BluetoothGattServer chatter plus a screenshot of the
                // counter. Deliberately no address or name — the count is
                // what diagnosis needs, and the rest is the guest's.
                Log.i(TAG, "event=participant_joined count=${count + 1}")
                queueManager.addParticipant(deviceAddress, label)

                // Tell a late joiner that a rest is running.
                //
                // The rest broadcast is edge-triggered, and the initial state a
                // client reads (session info, queue, current climb, participant
                // list) has no phase in it. Without this, joining DURING a rest
                // reproduces the exact defect the rest events were added to fix:
                // the newcomer sees the upcoming climb and is invited to start
                // on a wall everyone else is resting in front of.
                //
                // notifyAll rather than a targeted write: participants already
                // resting get the same remaining seconds they are counting
                // anyway, so the resync is a no-op for them, and the alternative
                // is a second code path for one client.
                val rest = boardSessionManager.restTimer.value
                if (rest.isRunning && rest.secondsRemaining > 0) {
                    Log.i(TAG, "event=rest_broadcast state=resync seconds=${rest.secondsRemaining}")
                    gattServer.notifyAll(
                        SessionGattUuids.QUEUE_EVENT,
                        SessionQueueProtocol.encodeEventRestStarted(
                            rest.secondsRemaining,
                            queueManager.state.value.currentIndex,
                        ),
                    )
                }
            } else {
                Log.d(TAG, "Ignoring duplicate JOIN from current connection")
            }
            return
        }

        if (!commandGate.hasJoined(deviceAddress)) {
            rejectClient(deviceAddress)
            return
        }

        if (request.context != null) {
            commandGate.markContextCapable(deviceAddress)
        } else if (receivedCommand !is SessionCommand.Leave &&
            commandGate.isContextCapable(deviceAddress)
        ) {
            // A genuinely legacy peer never sets this bit and keeps its old
            // behavior. A peer that already proved modern support cannot opt
            // out of the session/rebase guard one command at a time.
            Log.i(
                TAG,
                "event=command_conflict action=${receivedCommand.javaClass.simpleName} " +
                    "reason=context_downgrade",
            )
            return
        }

        // A participant may repeat a write after an Android 9 GATT failure.
        // Return the original decision without applying the mutation twice.
        request.requestId?.let { requestId ->
            val key = commandResultKey(deviceAddress, request.context?.sessionId, requestId)
            val previous = synchronized(handledCommandResults) { handledCommandResults[key] }
            if (previous != null) {
                sendCommandResult(deviceAddress, requestId, previous)
                return
            }
        }

        val cmd = if (request.context == null) {
            // Compatibility with older clients: their index command retains
            // its historical behavior, while upgraded peers get safe rebase.
            receivedCommand
        } else {
            val state = queueManager.state.value
            when (val rebased = SessionCommandRebaser.rebase(
                receivedCommand,
                request.context,
                state.sessionId,
                state.currentIndex,
                state.queue,
            )) {
                is SessionCommandRebaser.Result.Apply -> rebased.command
                is SessionCommandRebaser.Result.Conflict -> {
                    Log.i(TAG, "event=command_conflict action=${receivedCommand.javaClass.simpleName} reason=${rebased.reason}")
                    request.requestId?.let {
                        rememberCommandResult(deviceAddress, request.context.sessionId, it,
                            SessionCommandResult.CONFLICT)
                        sendCommandResult(deviceAddress, it, SessionCommandResult.CONFLICT)
                    }
                    return
                }
            }
        }

        Log.d(TAG, "Received joined session command (${cmd.javaClass.simpleName})")
        val applied = runCatching {
            when (cmd) {
                is SessionCommand.Add -> queueManager.addClimb(cmd.climbUuid, cmd.angle)
                is SessionCommand.Remove -> queueManager.removeClimb(cmd.index)
                is SessionCommand.SetCurrent -> queueManager.setCurrentClimb(cmd.index)
                // Through the host's phase-aware playback logic, not straight
                // into the queue — see onRemoteNext.
                is SessionCommand.Next -> {
                    Log.i(TAG, "event=transport_received action=next")
                    (onRemoteNext ?: queueManager::nextClimb).invoke()
                }
                is SessionCommand.Prev -> {
                    Log.i(TAG, "event=transport_received action=prev")
                    (onRemotePrev ?: queueManager::previousClimb).invoke()
                }
                is SessionCommand.Join -> Unit // handled before authorization gate
                is SessionCommand.Leave -> {
                    Log.d(TAG, "Processing LEAVE from $deviceAddress, " +
                        "participants before: ${queueManager.state.value.participants.map { it.deviceAddress }}")
                    Log.i(TAG, "event=participant_left")
                    queueManager.removeParticipant(deviceAddress)
                    commandGate.remove(deviceAddress)
                    Log.d(TAG, "After removeParticipant: count=${queueManager.state.value.participantCount}, " +
                        "participants=${queueManager.state.value.participants.map { it.deviceAddress }}")
                    // Proactively disconnect from server side to ensure clean teardown
                    gattServer.cancelDevice(deviceAddress)
                }
                is SessionCommand.Move -> queueManager.moveClimb(cmd.from, cmd.to)
                is SessionCommand.Resend -> {
                    Log.i(TAG, "event=transport_received action=resend")
                    queueManager.requestRemoteResend()
                }
            }
        }.onFailure { Log.e(TAG, "Failed to apply session command", it) }.isSuccess
        if (!applied) {
            request.requestId?.let {
                rememberCommandResult(deviceAddress, request.context?.sessionId, it,
                    SessionCommandResult.FAILED)
                sendCommandResult(deviceAddress, it, SessionCommandResult.FAILED)
            }
            return
        }
        if (cmd !is SessionCommand.Leave) {
            request.requestId?.let {
                rememberCommandResult(deviceAddress, request.context?.sessionId, it,
                    SessionCommandResult.COMMITTED)
                sendCommandResult(deviceAddress, it, SessionCommandResult.COMMITTED)
            }
        }
    }

    private fun commandResultKey(deviceAddress: String, sessionId: Int?, requestId: Long) =
        "$deviceAddress:${sessionId ?: 0}:$requestId"

    private fun rememberCommandResult(
        deviceAddress: String,
        sessionId: Int?,
        requestId: Long,
        result: SessionCommandResult,
    ) {
        synchronized(handledCommandResults) {
            handledCommandResults[commandResultKey(deviceAddress, sessionId, requestId)] = result
        }
    }

    private fun sendCommandResult(
        deviceAddress: String,
        requestId: Long,
        result: SessionCommandResult,
    ) {
        gattServer.notifyDevice(
            deviceAddress,
            SessionGattUuids.QUEUE_EVENT,
            SessionQueueProtocol.encodeEventCommandResult(requestId, result),
        )
    }

    private fun rejectClient(deviceAddress: String) {
        Log.w(TAG, "Rejected session command before JOIN")
        gattServer.cancelDevice(deviceAddress)
    }

    // ===== Internal: Participant applies remote events =====

    private fun applyRemoteEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Added -> {
                queueManager.addClimb(event.climbUuid, event.angle)
            }
            is SessionEvent.Removed -> queueManager.removeClimb(event.index)
            is SessionEvent.CurrentChanged -> {
                queueManager.setCurrentClimb(event.index)
                queueManager.sendCurrentClimbToBoard()
            }
            is SessionEvent.Cleared -> queueManager.clearQueue()
            is SessionEvent.ParticipantJoined, is SessionEvent.ParticipantLeft -> {
                // Participant list comes via dedicated characteristic
            }
            // Drive the participant's OWN rest timer rather than inventing a
            // second kind of rest UI. PlaylistPlaybackCoordinator derives
            // PlaybackPhase.Resting from exactly this flow, so the participant
            // gets the identical countdown, "up next" card and skip button the
            // host has, for free.
            is SessionEvent.RestStarted -> {
                Log.i(
                    TAG,
                    "event=rest_applied state=started " +
                        "seconds=${event.remainingSeconds} nextIndex=${event.nextIndex}",
                )
                // The queue may still be behind if CurrentChanged was dropped;
                // the host tells us where it landed, so trust that.
                if (event.nextIndex != queueManager.state.value.currentIndex) {
                    queueManager.setCurrentClimb(event.nextIndex)
                }
                if (event.remainingSeconds > 0) {
                    boardSessionManager.startRestTimer(event.remainingSeconds)
                }
            }
            is SessionEvent.RestEnded -> {
                Log.i(TAG, "event=rest_applied state=ended")
                boardSessionManager.cancelRestTimer()
            }
            is SessionEvent.CommandResult -> {
                val action = pendingCommands.remove(event.requestId) ?: return
                _pendingCommandCount.value = pendingCommands.size
                when (event.result) {
                    SessionCommandResult.COMMITTED ->
                        Log.i(TAG, "event=command_committed action=$action")
                    SessionCommandResult.CONFLICT -> _commandFeedback.tryEmit(
                        PlaylistCommandFeedback(PlaylistCommandFeedbackKind.CONFLICT, action),
                    )
                    SessionCommandResult.FAILED -> _commandFeedback.tryEmit(
                        PlaylistCommandFeedback(PlaylistCommandFeedbackKind.FAILED, action),
                    )
                }
            }
        }
    }

    // ===== Host migration =====

    /**
     * When the host disconnects, this participant attempts to become the new host.
     *
     * Strategy — deterministic election by join order:
     * 1. Each participant knows their position via [SessionQueueState.participantIndex]
     * 2. Participant at index 0 waits 1s, index 1 waits 4s, index 2 waits 7s, etc.
     * 3. The first participant promotes locally after its delay. A later
     *    participant may then stage a visible successor's unsigned
     *    advertisement for one explicit answer; it never joins automatically.
     * 4. Decline/vanish/no candidate → preserve the queue under a local-only
     *    promoted host. Publication remains a separate explicit answer.
     *
     * Privacy: No personal data is transmitted. Election uses only the locally stored
     * participant index (join order). No device addresses or names are exchanged.
     */
    private fun attemptHostMigration() {
        val queueState = queueManager.state.value
        Log.d(TAG, "attemptHostMigration() called, role=${queueState.role}, " +
            "queue=${queueState.queue.size}, participantIndex=${queueState.participantIndex}, " +
            "isConnecting=${queueState.isConnecting}, migrating=${migrationJob?.isActive}")
        // Guard: avoid restarting migration if already in progress
        // (both sentinel + GATT disconnect can trigger this)
        if (migrationJob?.isActive == true) {
            Log.d(TAG, "attemptHostMigration: already migrating, skipping")
            return
        }
        if (_pendingSuccessorJoin.value != null) {
            Log.d(TAG, "attemptHostMigration: explicit successor decision already pending")
            return
        }
        if (queueState.queue.isEmpty()) {
            Log.d(TAG, "attemptHostMigration: queue is empty, ending queue instead of migrating")
            val lastQueueClimb = queueState.currentClimb
                ?.takeUnless { queueState.externalBoardOverride }
            val projectionSurvivesDisconnect = projectionSurvivesCurrentBoardDisconnect()
            advertiser.suppressClimbAdvertising = false
            restartClimbAdvertisingIfConnected()
            if (lastQueueClimb != null) {
                boardStateManager.setLastClimbQuick(
                    lastQueueClimb.climbUuid,
                    lastQueueClimb.angle,
                    projectionSurvivesDisconnect,
                )
                scope.launch {
                    boardStateManager.setLastClimb(
                        lastQueueClimb.climbUuid,
                        lastQueueClimb.angle,
                        projectionSurvivesDisconnect,
                    )
                }
            }
            queueManager.endQueue()
            boardSessionManager.endSession()
            return
        }

        val myIndex = queueState.participantIndex.coerceAtLeast(0)
        val waitMs = MIGRATION_BASE_DELAY_MS + myIndex * MIGRATION_INDEX_STEP_MS

        Log.d(TAG, "Host disconnected — migration election " +
            "(index=$myIndex, wait=${waitMs}ms, queue=${queueState.queue.size} items)")

        val generation = migrationGeneration.incrementAndGet()
        migrationJob = scope.launch {
            Log.d(TAG, "Migration job started, waiting ${waitMs}ms for a possible successor")
            delay(waitMs)

            val liveState = queueManager.state.value
            if (migrationGeneration.get() != generation ||
                liveState.role != SessionRole.PARTICIPANT ||
                liveState.queue.isEmpty()
            ) {
                Log.d(TAG, "Migration cancelled because the participant queue changed")
                return@launch
            }

            // Index 0 is the designated first successor, so no ambient radio
            // can divert it. Later indices may see index 0's newly published
            // session, but membership still requires an explicit answer.
            val candidate = if (myIndex > 0) {
                strongestConnectableSuccessor(lastHostSessionId)
            } else {
                null
            }
            if (candidate != null) {
                if (migrationGeneration.get() != generation) return@launch
                Log.d(
                    TAG,
                    "Migration: unverified successor appeared; awaiting explicit join consent " +
                        "(id=${candidate.sessionId}, address=${candidate.deviceAddress})",
                )
                stageSuccessorJoin(candidate, PendingSuccessorOrigin.HOST_MIGRATION)
                return@launch
            }

            // No new session detected — promote self
            if (migrationGeneration.get() != generation ||
                queueManager.state.value.role != SessionRole.PARTICIPANT ||
                queueManager.state.value.queue.isEmpty()
            ) {
                Log.d(TAG, "Migration promotion cancelled because the participant queue changed")
                return@launch
            }
            Log.d(TAG, "Migration: no new host found after ${waitMs}ms, promoting self to host")
            queueManager.promoteToHost(
                // Was a German literal here, so an English-locale user who happened
                // to outlive the host ended up in a session called "Warteschlange".
                context.getString(R.string.ble_session_name_promoted)
            )
            Log.d(TAG, "Migration complete — queue preserved locally with " +
                "${queueState.queue.size} climbs; awaiting host visibility choice")
        }
    }

    /**
     * Called on the participant side when the host signals session end
     * (participantCount == 0 in SESSION_INFO notification).
     *
     * Instead of ending the queue, we attempt host migration so the first
     * participant takes over and the group continues climbing.
     */
    /**
     * The host ended the playlist outright — no migration, no successor.
     *
     * Same teardown as an empty-queue migration, minus the election: nobody is
     * promoted because nobody is meant to continue.
     */
    private fun handleSessionEndedForEveryone() {
        cancelHostMigration()
        cancelRecoveryHandoff()
        clearPendingSuccessorJoin()
        joinJob?.cancel()
        joinJob = null
        gattClient.disconnect()
        queueManager.remoteAddClimb = null
        advertiser.suppressClimbAdvertising = false
        restartClimbAdvertisingIfConnected()
        queueManager.endQueue()
        boardSessionManager.endSession()
    }

    private fun cancelHostMigration() {
        migrationGeneration.incrementAndGet()
        migrationJob?.cancel()
        migrationJob = null
    }

    private fun handleSessionEndedByHost() {
        val qState = queueManager.state.value
        Log.d(TAG, "handleSessionEndedByHost() called, role=${qState.role}, " +
            "joinJob=${joinJob != null}, queue=${qState.queue.size}, " +
            "participantIndex=${qState.participantIndex}, " +
            "migrationJob=${migrationJob?.isActive}, lastHostSessionId=$lastHostSessionId")
        // lastHostSessionId was already set in joinSession() from the host's advertised session ID.
        // (qState.sessionId is always 0 for participants, so it cannot be used here.)
        joinJob?.cancel()
        joinJob = null
        gattClient.disconnect()
        // Clear remote command sender so addClimb() falls through to local add
        // during migration (GATT is disconnected, remote sends would fail silently).
        queueManager.remoteAddClimb = null
        Log.d(TAG, "handleSessionEndedByHost: GATT client disconnected, starting migration")
        // Don't end queue or unsuppress advertising — attemptHostMigration()
        // either preserves it under a local promoted host awaiting a visibility
        // choice, offers an exact successor for consent, or ends an empty queue.
        attemptHostMigration()
    }

    /** Bug 6: After session ends, restart climb/boardConnected advertising if still connected.
     *  Bug 5: Guards prevent interference during active sessions or migration. */
    private fun restartClimbAdvertisingIfConnected() {
        if (advertiser.suppressClimbAdvertising) {
            Log.d(TAG, "restartClimbAdvertising: skipped (session still active)")
            return
        }
        if (isRejoining) {
            Log.d(TAG, "restartClimbAdvertising: skipped (migration in progress)")
            return
        }
        if (!shouldAdvertiseIndividualClimbs()) {
            Log.d(TAG, "restartClimbAdvertising: skipped (nearby climb sharing disabled)")
            advertiser.stopAdvertising()
            return
        }
        if (!advertiser.isBoardConnected()) return
        val active = advertiser.getActiveClimb()
        if (active != null) {
            Log.d(TAG, "restartClimbAdvertising: resuming ClimbData ${active.first.take(8)}")
            advertiser.advertiseClimb(
                active.first,
                active.second,
                projectionSurvivesDisconnect = advertiser.activeProjectionSurvivesDisconnect(),
            )
        } else {
            Log.d(TAG, "restartClimbAdvertising: resuming BoardConnected")
            advertiser.advertiseConnected()
        }
    }

    private fun updateSessionAdvertising(): Boolean {
        val s = queueManager.state.value
        val currentClimb = s.currentClimb?.takeUnless { s.externalBoardOverride }
        val result = advertiser.advertiseSession(
            s.sessionId, s.participantCount, s.hostName,
            climbUuid = currentClimb?.climbUuid,
            climbAngle = currentClimb?.angle ?: 0
        )
        Log.d(TAG, "updateSessionAdvertising: sessionId=${s.sessionId}, " +
            "count=${s.participantCount}, hostName='${s.hostName}', " +
            "climb=${currentClimb?.climbUuid?.take(8)}, result=$result")
        return result == "started" || result == "updated"
    }
}
