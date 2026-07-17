package com.cruxcoach.android.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.cruxcoach.android.ble.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bridges [SessionQueueManager] with BLE GATT for shared sessions.
 *
 * - **Host mode**: Starts GATT server + session advertising, pushes delta events to clients.
 * - **Participant mode**: Connects GATT client, sends commands, applies incoming events.
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    companion object {
        private const val TAG = "CruxBLE/Session"
        private const val MIGRATION_BASE_DELAY_MS = 1000L
        private const val MIGRATION_INDEX_STEP_MS = 3000L
    }

    private var migrationJob: Job? = null
    private var joinJob: Job? = null
    private var hostJob: Job? = null
    private var isSharing = false
    private var isRejoining = false
    /** SessionId of the host we just left — used to ignore stale advertisements during migration. */
    private var lastHostSessionId: Int = 0

    private fun projectionSurvivesCurrentBoardDisconnect(): Boolean =
        BoardProjectionPolicy.projectionSurvivesDisconnect(
            bleConnection.connectedBoardBrand.value
        )

    private fun currentBoardConnectionCapacity(): BoardConnectionCapacity =
        BoardControllerProfiles.forBoard(bleConnection.connectedBoard).connectionCapacity

    init {
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

        // Start GATT server
        if (!gattServer.start()) {
            Log.e(TAG, "startSharing(): GATT server failed to start")
            queueManager.setError("GATT-Server konnte nicht gestartet werden")
            return
        }

        // Wire data providers
        gattServer.sessionInfoProvider = { queueManager.encodeSessionInfo() }
        gattServer.queueStateProvider = { queueManager.encodeQueueState() }
        gattServer.currentClimbProvider = { queueManager.encodeCurrentClimb() }
        gattServer.participantListProvider = { queueManager.encodeParticipantList() }

        // Wire queue change listeners to push delta events
        queueManager.onQueueChanged = {
            gattServer.notifyAll(
                SessionGattUuids.QUEUE_STATE,
                queueManager.encodeQueueState()
            )
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
                            queueManager.removeParticipant(event.deviceAddress)
                            // Restart advertising in case it stopped
                            if (isSharing) {
                                updateSessionAdvertising()
                            }
                        }
                    }
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
        updateSessionAdvertising()
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

    fun stopSharing(allowBoardRelease: Boolean) {
        Log.d(TAG, "stopSharing() called, isSharing=$isSharing, " +
            "connectedClients=${gattServer.getConnectedCount()}, " +
            "boardConnected=${bleConnection.connectionState.value}")
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
            SessionQueueProtocol.encodeSessionInfo("", 0)
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
            if (lastQueueClimb != null) {
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
        val state = queueManager.state.value
        if (state.role != SessionRole.HOST) return

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
        // Record the host's advertised session ID so migration can filter stale ads later.
        // queueManager.state.sessionId is always 0 for participants (passed as 0 in setParticipantRole),
        // so we must get the real ID from the BLE advertising scan data.
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
                            val joinSent = gattClient.sendCommand(SessionQueueProtocol.encodeJoin(""))
                            Log.d(TAG, "JOIN command sent: success=$joinSent")
                            gattClient.readInitialState()
                            queueManager.setParticipantRole(0, "")
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
                                queueManager.setError("Verbindung fehlgeschlagen")
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
                        Log.d(TAG, "Received session-ended signal from host")
                        handleSessionEndedByHost()
                        return@collect
                    }
                    queueManager.updateSessionInfo(info.hostName, info.participantCount)
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

            // Listen for full queue state (initial sync + updates)
            launch {
                gattClient.queueStateUpdates.collect { data ->
                    val parsed = SessionQueueProtocol.decodeQueueState(data) ?: return@collect
                    val (currentIndex, items) = parsed
                    Log.d(TAG, "Received queue state: ${items.size} items, currentIndex=$currentIndex")
                    queueManager.applyRemoteState(currentIndex, items)
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
        scope.launch {
            gattClient.sendCommand(SessionQueueProtocol.encodeAdd(climbUuid, angle))
        }
    }

    fun sendRemoveClimb(index: Int) {
        scope.launch {
            gattClient.sendCommand(SessionQueueProtocol.encodeRemove(index))
        }
    }

    fun sendNext() {
        scope.launch { gattClient.sendCommand(SessionQueueProtocol.encodeNext()) }
    }

    fun sendPrev() {
        scope.launch { gattClient.sendCommand(SessionQueueProtocol.encodePrev()) }
    }

    fun sendSetCurrent(index: Int) {
        scope.launch { gattClient.sendCommand(SessionQueueProtocol.encodeSetCurrent(index)) }
    }

    fun sendMove(from: Int, to: Int) {
        scope.launch { gattClient.sendCommand(SessionQueueProtocol.encodeMove(from, to)) }
    }

    // ===== Internal: Host processes commands from clients =====

    private fun handleClientCommand(deviceAddress: String, data: ByteArray) {
        val cmd = SessionQueueProtocol.decodeCommand(data)
        if (cmd == null) {
            Log.w(TAG, "Failed to decode command from $deviceAddress (${data.size} bytes)")
            return
        }
        Log.d(TAG, "Received command from $deviceAddress: $cmd")
        when (cmd) {
            is SessionCommand.Add -> queueManager.addClimb(cmd.climbUuid, cmd.angle)
            is SessionCommand.Remove -> queueManager.removeClimb(cmd.index)
            is SessionCommand.SetCurrent -> queueManager.setCurrentClimb(cmd.index)
            is SessionCommand.Next -> queueManager.nextClimb()
            is SessionCommand.Prev -> queueManager.previousClimb()
            is SessionCommand.Join -> {
                val count = queueManager.state.value.participants.size
                val label = "Teilnehmer ${count + 1}"
                Log.d(TAG, "Participant joined: $label (device=$deviceAddress)")
                queueManager.addParticipant(deviceAddress, label)
            }
            is SessionCommand.Leave -> {
                Log.d(TAG, "Processing LEAVE from $deviceAddress, " +
                    "participants before: ${queueManager.state.value.participants.map { it.deviceAddress }}")
                queueManager.removeParticipant(deviceAddress)
                Log.d(TAG, "After removeParticipant: count=${queueManager.state.value.participantCount}, " +
                    "participants=${queueManager.state.value.participants.map { it.deviceAddress }}")
                // Proactively disconnect from server side to ensure clean teardown
                gattServer.cancelDevice(deviceAddress)
            }
            is SessionCommand.Move -> queueManager.moveClimb(cmd.from, cmd.to)
        }
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
            queueManager.promoteToHost("Warteschlange")
            Log.d(TAG, "Migration: promoteToHost complete, role=${queueManager.state.value.role}, " +
                "queue=${queueManager.state.value.queue.size}, calling startSharing()")
            startSharing()
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

    private fun updateSessionAdvertising() {
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
    }
}
