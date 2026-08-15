package com.cruxcoach.android.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.os.SystemClock
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.fips.FipsRealmContext
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.BoardCellHandoverLifecycle
import com.cruxcoach.android.boardcell.BoardCommandStatus
import com.cruxcoach.android.boardcell.BoardPlaylistState

enum class PlaylistCommandFeedbackKind { CONFLICT, UNAVAILABLE, FAILED }
data class PlaylistCommandFeedback(val kind: PlaylistCommandFeedbackKind, val action: String)

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
    private val hostSetupDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val fipsMeshRuntime: FipsMeshRuntime? = null,
    private val boardCellManager: BoardCellManager? = null,
) {
    companion object {
        private const val TAG = "CruxBLE/Session"
        private const val MIGRATION_BASE_DELAY_MS = 1000L
        private const val MIGRATION_INDEX_STEP_MS = 3000L
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
    private var joinJob: Job? = null
    private var hostJob: Job? = null
    @Volatile private var isSharing = false
    private var isRejoining = false
    private var meshRealmHeldForJoin = false
    private val commandGate = SessionCommandGate()
    private data class PendingMeshCommand(
        val label: String,
        val payload: ByteArray,
        val context: com.cruxcoach.android.boardcell.BoardPlaylistCommandContext,
        val basePlaylistRevision: Long,
        @Volatile var attempts: Int = 0,
        @Volatile var retryAtMs: Long = SystemClock.elapsedRealtime() + 2_000,
    )
    private val pendingMeshCommands = ConcurrentHashMap<String, PendingMeshCommand>()
    private val _pendingCommandCount = MutableStateFlow(0)
    val pendingCommandCount = _pendingCommandCount.asStateFlow()
    private val _commandFeedback = MutableSharedFlow<PlaylistCommandFeedback>(extraBufferCapacity = 64)
    val commandFeedback = _commandFeedback.asSharedFlow()
    /** SessionId of the host we just left — used to ignore stale advertisements during migration. */
    private var lastHostSessionId: Int = 0

    private fun projectionSurvivesCurrentBoardDisconnect(): Boolean =
        BoardProjectionPolicy.projectionSurvivesDisconnect(
            bleConnection.connectedBoardBrand.value
        )

    private fun currentBoardConnectionCapacity(): BoardConnectionCapacity =
        BoardControllerProfiles.forBoard(bleConnection.connectedBoard).connectionCapacity

    init {
        scope.launch {
            queueManager.state.collect {
                if (!it.isActive) {
                    pendingMeshCommands.clear()
                    _pendingCommandCount.value = 0
                }
            }
        }
        boardCellManager?.installHandoverLifecycle(BoardCellHandoverLifecycle(
            prepareTarget = {
                if (queueManager.state.value.role != SessionRole.HOST) {
                    queueManager.promoteToHostForBoardCell(context.getString(R.string.ble_session_name_promoted))
                }
                val hostReady = ensureHostSharing()
                hostReady && bleConnection.connectionState.value == ConnectionState.CONNECTED
            },
            completeSource = {
                // Only HANDOVER_COMPLETED reaches this callback. The target has
                // already assumed HOST, board keep-alive and write authority.
                stopSharing(allowBoardRelease = true, endForEveryone = false)
                queueManager.completeTransferredQueue()
                boardSessionManager.endSession()
            },
            abortTarget = { snapshot ->
                stopSharing(allowBoardRelease = false, endForEveryone = true)
                queueManager.setParticipantRole(
                    snapshot.playlist.sessionId ?: queueManager.state.value.sessionId,
                    queueManager.state.value.hostName,
                )
            },
        ))
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
        boardCellManager?.let { manager ->
            scope.launch {
                manager.sessionCommands.collect { command ->
                    handleMeshCommand(command)
                }
            }
            scope.launch {
                manager.commandAcks.collect { ack ->
                    val pending = pendingMeshCommands[ack.commandId] ?: return@collect
                    val action = pending.label
                    when (ack.status) {
                        BoardCommandStatus.COMMITTED -> removePendingCommand(ack.commandId)
                        BoardCommandStatus.REJECTED_CONFLICT,
                        BoardCommandStatus.REJECTED_STALE,
                        BoardCommandStatus.SUPERSEDED -> {
                            removePendingCommand(ack.commandId)
                            _commandFeedback.emit(PlaylistCommandFeedback(
                                PlaylistCommandFeedbackKind.CONFLICT, action))
                        }
                        BoardCommandStatus.NOT_CONTROLLER,
                        BoardCommandStatus.BOARD_WRITE_FAILED -> {
                            removePendingCommand(ack.commandId)
                            _commandFeedback.emit(PlaylistCommandFeedback(
                                PlaylistCommandFeedbackKind.FAILED, action))
                        }
                        BoardCommandStatus.ACCEPTED -> Unit
                    }
                }
            }
            scope.launch {
                while (true) {
                    delay(1_000)
                    val now = SystemClock.elapsedRealtime()
                    pendingMeshCommands.forEach { (commandId, pending) ->
                        if (now < pending.retryAtMs) return@forEach
                        manager.retrySessionCommand(pending.payload, pending.context, commandId,
                            pending.basePlaylistRevision)
                        pending.attempts++
                        val backoff = (2_000L shl pending.attempts.coerceAtMost(3)).coerceAtMost(15_000L)
                        pending.retryAtMs = now + backoff
                    }
                }
            }
        }
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

        queueManager.setVisibilityRequested(SessionVisibility.JOINABLE)

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
     * Called when Bluetooth comes back on. If we were hosting a session,
     * check if another participant already took over as host (migration).
     * If not, restart our own GATT server and advertising.
     */
    private fun recoverAfterBluetoothRestart() {
        fipsMeshRuntime?.restartAfterBluetoothAvailable()
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

        // Check if someone else already promoted to host during our BT outage
        val nearbySessions = nearbyScanner.nearbySessions.value
        if (nearbySessions.isNotEmpty()) {
            val newHost = nearbySessions.first()
            val device = newHost.device
            if (device != null) {
                Log.d(TAG, "BT recovered but another host exists — joining as participant")
                isSharing = false
                gattServer.stop()
                joinSession(device)
                return
            }
        }

        Log.d(TAG, "BT recovered — restarting GATT server + session advertising")
        gattServer.stop()
        startSharing()
    }

    // ===== Participant mode =====

    fun joinSession(device: BluetoothDevice) {
        Log.d(TAG, "joinSession() called, device=${device.address}, " +
            "isRejoining=$isRejoining, joinJob=${joinJob != null}, " +
            "clientState=${gattClient.connectionState.value}")
        // Record the host's advertised session ID. Migration filters stale ads
        // with it, and it is also what the participant carries as their own
        // session identity from here on — the JOIN handshake never sends it
        // back, so the scan is the only place it exists.
        nearbyScanner.nearbySessions.value
            .firstOrNull { it.device?.address == device.address }
            ?.let { session ->
                lastHostSessionId = session.sessionId
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
                            migrationJob?.cancel()
                            Log.d(TAG, "Connected to host, sending JOIN command")
                            val joinSent = gattClient.sendCommand(SessionQueueProtocol.encodeJoin(
                                "", fipsMeshRuntime?.localNpub?.takeIf { it.isNotBlank() }))
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
                    val accepted = queueManager.updateSessionInfo(
                        info.hostName,
                        info.participantCount,
                        info.physicalBoardId,
                        info.boardCellId,
                        info.awaitingExplicitSend,
                    )
                    if (!accepted) return@collect
                    info.boardCellId?.let { cellId ->
                        val runtime = fipsMeshRuntime ?: return@let
                        val physical = info.physicalBoardId ?: return@let
                        if (boardCellManager?.prepareParticipantScope(physical, cellId) == false) return@let
                        if (runtime.activateRealm(FipsRealmContext(cellId, cellId))) {
                            if (!meshRealmHeldForJoin) {
                                runtime.acquire()
                                meshRealmHeldForJoin = true
                            }
                            gattClient.sendCommand(SessionQueueProtocol.encodeJoin("", runtime.localNpub))
                        }
                    }
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
        Log.d(TAG, "leaveSession() called, joinJob=${joinJob != null}, " +
            "clientState=${gattClient.connectionState.value}, " +
            "role=${queueManager.state.value.role}")
        joinJob?.cancel()
        joinJob = null
        meshRealmHeldForJoin = false // SessionQueueManager.endQueue releases the corresponding owner.
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
        sendParticipantCommand("remove($index)", SessionCommand.Remove(index))
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
            val payload = SessionQueueProtocol.encodeCommand(command) ?: return@launch
            val snapshot = boardCellManager?.snapshot()
            val context = snapshot?.let { PlaylistCommandRebaser.context(command, it.playlist) }
            if (queueManager.state.value.role == SessionRole.HOST) {
                if (snapshot == null || context == null) {
                    applyLegacyLocalCommand(command)
                    return@launch
                }
                val commandId = UUID.randomUUID().toString()
                val ack = boardCellManager.commitLocalSessionCommand(commandId,
                    snapshot.playlistRevision) { current, exact ->
                        applyRebasedCommand(command, context, current, exact)
                    }
                if (ack?.status != BoardCommandStatus.COMMITTED) {
                    _commandFeedback.emit(PlaylistCommandFeedback(
                        PlaylistCommandFeedbackKind.CONFLICT, label))
                }
                return@launch
            }
            val candidateId = UUID.randomUUID().toString()
            if (context != null) pendingMeshCommands[candidateId] = PendingMeshCommand(
                label, payload, context, snapshot.playlistRevision)
            _pendingCommandCount.value = pendingMeshCommands.size
            val commandId = if (context != null)
                boardCellManager.sendSessionCommand(payload, context, candidateId) else null
            val sentByFips = commandId != null
            if (!sentByFips) removePendingCommand(candidateId)
            if (sentByFips || gattClient.sendCommand(payload)) {
                // Logged on success too, not only on failure. Only logging the
                // failure leaves the working path silent, and during the
                // 2026-08-06 two-device test that made three candidate causes
                // for "next does nothing" indistinguishable: no line meant
                // "never pressed", "not sent", "sent and ignored" or "applied"
                // equally well. A support log has to separate those.
                Log.i(TAG, "event=transport_sent action=$label")
            } else {
                Log.w(TAG, "event=transport_send_failed action=$label")
                _commandFeedback.emit(PlaylistCommandFeedback(
                    PlaylistCommandFeedbackKind.UNAVAILABLE, label))
            }
        }
    }

    private fun removePendingCommand(commandId: String) {
        pendingMeshCommands.remove(commandId)
        _pendingCommandCount.value = pendingMeshCommands.size
    }

    /** FIPS has already authenticated membership and exact BoardCell epoch/sequence. */
    private suspend fun handleMeshCommand(command: com.cruxcoach.android.boardcell.InboundSessionCommand) {
        val decoded = SessionQueueProtocol.decodeCommand(command.payload)
        boardCellManager?.commitSessionCommand(command) { current, exact ->
            if (queueManager.state.value.role != SessionRole.HOST) return@commitSessionCommand null
            val cmd = decoded ?: return@commitSessionCommand null
            applyRebasedCommand(cmd, command.context, current, exact)
        }
    }

    private fun applyRebasedCommand(command: SessionCommand,
        context: com.cruxcoach.android.boardcell.BoardPlaylistCommandContext?,
        current: BoardPlaylistState, exact: Boolean): BoardPlaylistState? {
        val resolved = PlaylistCommandRebaser.rebase(command, context, current, exact)
            as? PlaylistCommandRebaser.Result.Apply ?: return null
        queueManager.alignHostQueue(current)
        when (val cmd = resolved.command) {
            is SessionCommand.Add -> queueManager.addClimb(cmd.climbUuid, cmd.angle)
            is SessionCommand.Remove -> queueManager.removeClimb(cmd.index)
            is SessionCommand.SetCurrent -> queueManager.setCurrentClimb(cmd.index)
            SessionCommand.Next -> (onRemoteNext ?: queueManager::nextClimb).invoke()
            SessionCommand.Prev -> (onRemotePrev ?: queueManager::previousClimb).invoke()
            is SessionCommand.Move -> queueManager.moveClimb(cmd.from, cmd.to)
            SessionCommand.Resend -> queueManager.resendCurrentClimb()
            is SessionCommand.Join, SessionCommand.Leave -> return null
        }
        val state = queueManager.state.value
        return BoardPlaylistState(state.sessionId, state.currentIndex,
            state.queue.map { it.climbUuid to it.angle })
    }

    private fun applyLegacyLocalCommand(command: SessionCommand) {
        when (command) {
            is SessionCommand.Add -> queueManager.addClimb(command.climbUuid, command.angle)
            is SessionCommand.Remove -> queueManager.removeClimb(command.index)
            is SessionCommand.SetCurrent -> queueManager.setCurrentClimb(command.index)
            SessionCommand.Next -> (onRemoteNext ?: queueManager::nextClimb).invoke()
            SessionCommand.Prev -> (onRemotePrev ?: queueManager::previousClimb).invoke()
            is SessionCommand.Move -> queueManager.moveClimb(command.from, command.to)
            SessionCommand.Resend -> queueManager.resendCurrentClimb()
            is SessionCommand.Join, SessionCommand.Leave -> Unit
        }
    }

    // ===== Internal: Host processes commands from clients =====

    private suspend fun handleClientCommand(deviceAddress: String, data: ByteArray) {
        val cmd = SessionQueueProtocol.decodeCommand(data)
        if (cmd == null) {
            Log.w(TAG, "Failed to decode session command (${data.size} bytes)")
            if (!commandGate.hasJoined(deviceAddress)) rejectClient(deviceAddress)
            return
        }

        if (cmd is SessionCommand.Join) {
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
                cmd.memberNpub?.let { BoardCellManager.current?.approveMember(it) }

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

        Log.d(TAG, "Received joined session command (${cmd.javaClass.simpleName})")
        val snapshot = boardCellManager?.snapshot()
        if (cmd !is SessionCommand.Leave && snapshot != null) {
            val semanticContext = PlaylistCommandRebaser.context(cmd, snapshot.playlist)
            if (semanticContext != null) {
                val commandId = UUID.randomUUID().toString()
                val ack = boardCellManager.commitLocalSessionCommand(commandId,
                    snapshot.playlistRevision) { current, exact ->
                    applyRebasedCommand(cmd, semanticContext, current, exact)
                }
                Log.i(TAG, "event=gatt_command_result action=${cmd.javaClass.simpleName} " +
                    "status=${ack?.status ?: "unavailable"}")
                val result = when (ack?.status) {
                    BoardCommandStatus.COMMITTED -> SessionCommandResult.COMMITTED
                    BoardCommandStatus.REJECTED_CONFLICT,
                    BoardCommandStatus.REJECTED_STALE,
                    BoardCommandStatus.SUPERSEDED -> SessionCommandResult.CONFLICT
                    else -> SessionCommandResult.FAILED
                }
                gattServer.notifyDevice(deviceAddress, SessionGattUuids.QUEUE_EVENT,
                    SessionQueueProtocol.encodeEventCommandResult(result))
                return
            }
        }
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
                queueManager.resendCurrentClimb()
            }
        }
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
            is SessionEvent.CommandResult -> when (event.result) {
                SessionCommandResult.COMMITTED -> Unit
                SessionCommandResult.CONFLICT -> _commandFeedback.tryEmit(
                    PlaylistCommandFeedback(PlaylistCommandFeedbackKind.CONFLICT, "gatt"))
                SessionCommandResult.FAILED -> _commandFeedback.tryEmit(
                    PlaylistCommandFeedback(PlaylistCommandFeedbackKind.FAILED, "gatt"))
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
     * 3. During the wait, check periodically if a new session appeared (higher-priority
     *    participant already promoted) → join that instead
     * 4. If no new session after the wait → promote to host
     *
     * Privacy: No personal data is transmitted. Election uses only the locally stored
     * participant index (join order). No device addresses or names are exchanged.
     */
    private fun attemptHostMigration() {
        val queueState = queueManager.state.value
        Log.d(TAG, "attemptHostMigration() called, role=${queueState.role}, " +
            "queue=${queueState.queue.size}, participantIndex=${queueState.participantIndex}, " +
            "isConnecting=${queueState.isConnecting}, migrating=${migrationJob?.isActive}")
        if (queueState.boardCellId != null) {
            // A join-order timer can elect two hosts on opposite sides of a
            // partition. Scoped sessions therefore freeze until the ordered
            // BoardCell controller/lease stream is reachable again.
            Log.w(TAG, "Scoped BoardCell host unreachable — freezing; no local host election")
            queueManager.freezeForController()
            return
        }
        // Guard: avoid restarting migration if already in progress
        // (both sentinel + GATT disconnect can trigger this)
        if (migrationJob?.isActive == true) {
            Log.d(TAG, "attemptHostMigration: already migrating, skipping")
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

        migrationJob = scope.launch {
            Log.d(TAG, "Migration job started, waiting ${waitMs}ms before promoting")
            val pollInterval = 500L
            var elapsed = 0L
            while (elapsed < waitMs) {
                delay(pollInterval)
                elapsed += pollInterval

                val nearbySessions = nearbyScanner.nearbySessions.value
                    .filter { it.sessionId != lastHostSessionId }
                if (nearbySessions.isNotEmpty()) {
                    val newHost = nearbySessions.first()
                    val device = newHost.device
                    Log.d(TAG, "Migration: found new session during wait " +
                        "(id=${newHost.sessionId}, host='${newHost.hostName}', device=${device?.address}, lastHostId=$lastHostSessionId)")
                    if (device != null) {
                        Log.d(TAG, "Migration: joining new host instead of promoting")
                        joinSession(device)
                    } else {
                        Log.w(TAG, "Migration: new host has no BluetoothDevice — cannot join")
                    }
                    return@launch
                }
                Log.d(TAG, "Migration: ${elapsed}ms/${waitMs}ms elapsed, no new session found")
            }

            // No new session detected — promote self
            Log.d(TAG, "Migration: no new host found after ${waitMs}ms, promoting self to host")
            queueManager.promoteToHost(
                // Was a German literal here, so an English-locale user who happened
                // to outlive the host ended up in a session called "Warteschlange".
                context.getString(R.string.ble_session_name_promoted)
            )
            Log.d(TAG, "Migration: promoteToHost complete, role=${queueManager.state.value.role}, " +
                "queue=${queueManager.state.value.queue.size}, calling startSharing()")
            // Host migration already performs a GATT client-to-server role
            // switch. Keep vendor Bluetooth stack latency off the UI thread.
            withContext(hostSetupDispatcher) { startSharing() }
            Log.d(TAG, "Migration complete — now hosting with ${queueState.queue.size} queued climbs")
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
        joinJob?.cancel()
        joinJob = null
        migrationJob?.cancel()
        migrationJob = null
        gattClient.disconnect()
        queueManager.remoteAddClimb = null
        advertiser.suppressClimbAdvertising = false
        restartClimbAdvertisingIfConnected()
        queueManager.endQueue()
        boardSessionManager.endSession()
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
        // Don't end queue or unsuppress advertising — attemptHostMigration() handles both
        // (promotes to host with startSharing(), or calls endQueue() if queue is empty)
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
