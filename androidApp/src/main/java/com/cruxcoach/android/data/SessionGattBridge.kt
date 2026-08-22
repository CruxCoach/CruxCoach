package com.cruxcoach.android.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.os.SystemClock
import com.cruxcoach.android.R
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.ble.*
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.mesh.MeshOwners
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.BoardCellPlatformPolicy
import com.cruxcoach.android.boardcell.BoardCellPeerDiagnostics
import com.cruxcoach.android.boardcell.BoardCellHandoverLifecycle
import com.cruxcoach.android.boardcell.BoardCommandAck
import com.cruxcoach.android.boardcell.BoardCommandStatus
import com.cruxcoach.android.boardcell.BoardPlaylistState
import com.cruxcoach.android.boardcell.BoardPlaylistCommand
import com.cruxcoach.android.boardcell.BoardPlaylistOp
import com.cruxcoach.android.boardcell.BoardPlaylistEntryId
import com.cruxcoach.android.boardcell.BoardPlaylistOps
import com.cruxcoach.android.boardcell.BoardPlaylistPolicy
import com.cruxcoach.android.boardcell.BoardProjection
import com.cruxcoach.android.boardcell.BoardCellSnapshot
import com.cruxcoach.android.boardcell.BoardCellScopeRegistry
import com.cruxcoach.android.boardcell.PhysicalBoardIdentity

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
    private val boardScanner: BoardBleScanner? = null,
    private val userPreferences: UserPreferences? = null,
    /** UTC wall clock used to stamp canonical rest deadlines; injectable for tests. */
    private val wallClockEpochMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "CruxBLE/Session"
        private const val MIGRATION_BASE_DELAY_MS = 1000L
        private const val MIGRATION_INDEX_STEP_MS = 3000L
        private const val COMMAND_RESULT_TIMEOUT_MS = 5000L
        /**
         * How long an API-28 leaf's GATT write waits for the controller's real
         * answer.
         *
         * Far longer than the direct-command timeout on purpose: the leaf's
         * edit crosses a gateway, the mesh, a possible handover and the
         * controller's own retry schedule before anything terminal comes back.
         * Reporting failure at five seconds meant the leaf was told its edit
         * was lost while it was still being delivered.
         */
        private const val GATEWAY_COMMAND_RESULT_TIMEOUT_MS = 30_000L
        /**
         * How the shared playlist recovers a dropped command.
         *
         * The first resend is deliberately sub-second and on its own timer:
         * tying it to the 2 s maintenance loop made a single lost BLE frame
         * cost two seconds of apparent dead UI. The backoff then widens so a
         * genuinely unreachable controller is not hammered, and the command id
         * is unchanged throughout, so a resend that crosses a commit is
         * answered from the durable ack window instead of applied twice.
         */
        internal const val PLAYLIST_COMMAND_RETRY_TICK_MS = 200L
        internal const val PLAYLIST_COMMAND_RETRY_INITIAL_MS = 250L
        internal const val PLAYLIST_COMMAND_RETRY_MAX_MS = 4_000L
        internal const val MAX_PLAYLIST_COMMAND_RETRIES = 8

        /** Exponential backoff for resend [attempt], counted from zero. */
        internal fun playlistRetryBackoffMs(attempt: Int): Long =
            (PLAYLIST_COMMAND_RETRY_INITIAL_MS shl attempt.coerceIn(0, 8))
                .coerceAtMost(PLAYLIST_COMMAND_RETRY_MAX_MS)
        private const val COMMAND_RESULT_CACHE_SIZE = 256
        private const val HANDOVER_SCAN_TIMEOUT_MS = 8_000L
        private const val HANDOVER_CONNECT_TIMEOUT_MS = 20_000L
        private const val HANDOVER_DISCONNECT_TIMEOUT_MS = 5_000L
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
    @Volatile private var sharedBoardDisplayName: String? = null
    private var meshRealmHeldForJoin = false
    private val commandGate = SessionCommandGate()
    /**
     * One playlist edit awaiting the controller's terminal answer.
     *
     * Retried on its own sub-second schedule rather than by the 2 s
     * maintenance loop: a dropped BLE frame used to cost a full maintenance
     * tick before anybody noticed, which is long enough to feel like the
     * button did nothing. The command id never changes across retries, so the
     * controller's durable ack window answers a duplicate instead of applying
     * it twice — including after a controller handover, which carries that
     * window in the snapshot it adopts.
     */
    private data class PendingPlaylistCommand(
        val label: String,
        val command: BoardPlaylistCommand,
        val onTerminal: ((BoardCommandAck?) -> Unit)? = null,
        @Volatile var attempts: Int = 0,
        @Volatile var retryAtMs: Long =
            SystemClock.elapsedRealtime() + PLAYLIST_COMMAND_RETRY_INITIAL_MS,
    )
    private val pendingPlaylistCommands = ConcurrentHashMap<String, PendingPlaylistCommand>()
    /**
     * Terminal-ack rendezvous for commands whose caller needs the answer.
     *
     * [BoardCellManager.commandAcks] is a single-consumer channel flow, so a
     * second collector would steal acks from the one below; the collector
     * completes these instead.
     */
    private val meshAckWaiters = ConcurrentHashMap<String, CompletableDeferred<BoardCommandAck>>()
    private val nextRequestId = AtomicLong(System.nanoTime())
    private val pendingBleCommands = ConcurrentHashMap<Long, String>()
    private val _pendingCommandCount = MutableStateFlow(0)
    val pendingCommandCount = _pendingCommandCount.asStateFlow()
    private val _pendingPlaylistCommandCount = MutableStateFlow(0)
    val pendingPlaylistCommandCount = _pendingPlaylistCommandCount.asStateFlow()
    private val _commandFeedback = MutableSharedFlow<PlaylistCommandFeedback>(extraBufferCapacity = 64)
    val commandFeedback = _commandFeedback.asSharedFlow()
    private val handledCommandResults = object : LinkedHashMap<String, SessionCommandResult>(
        COMMAND_RESULT_CACHE_SIZE + 1, 0.75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, SessionCommandResult>?,
        ): Boolean = size > COMMAND_RESULT_CACHE_SIZE
    }
    /** SessionId of the host we just left — used to ignore stale advertisements during migration. */
    private var lastHostSessionId: Int = 0
    private var releasedHandoverBoard: DiscoveredBoard? = null

    private fun projectionSurvivesCurrentBoardDisconnect(): Boolean =
        BoardProjectionPolicy.projectionSurvivesDisconnect(
            bleConnection.connectedBoardBrand.value
        )

    private fun currentBoardConnectionCapacity(): BoardConnectionCapacity =
        BoardControllerProfiles.forBoard(bleConnection.connectedBoard).connectionCapacity

    init {
        queueManager.addToSharedPlaylist = { items -> addToSharedPlaylist(items) }
        userPreferences?.let { preferences ->
            scope.launch {
                preferences.localUserProfile.collect { profile ->
                    sharedBoardDisplayName = profile.displayName.trim()
                        .takeIf { profile.shareWithBoard && it.isNotEmpty() }
                        ?.take(40)
                }
            }
        }
        scope.launch {
            queueManager.state.collect {
                if (!it.isActive) {
                    pendingPlaylistCommands.clear()
                    pendingBleCommands.clear()
                    meshAckWaiters.values.forEach { waiter -> waiter.cancel() }
                    meshAckWaiters.clear()
                    _pendingCommandCount.value = 0
                    _pendingPlaylistCommandCount.value = 0
                }
            }
        }
        boardCellManager?.installHandoverLifecycle(BoardCellHandoverLifecycle(
            releaseSource = { snapshot ->
                val connected = bleConnection.connectedBoard
                if (!boardMatchesSnapshot(connected, snapshot) ||
                    bleConnection.connectionState.value != ConnectionState.CONNECTED) {
                    false
                } else if (BoardControllerProfiles.forBoard(connected).connectionCapacity ==
                    BoardConnectionCapacity.MULTIPLE) {
                    true
                } else {
                    releasedHandoverBoard = connected
                    bleConnection.disconnect()
                    val disconnected = withTimeoutOrNull(HANDOVER_DISCONNECT_TIMEOUT_MS) {
                        bleConnection.connectionState.first { it == ConnectionState.DISCONNECTED }
                    } == ConnectionState.DISCONNECTED
                    if (disconnected) {
                        // Physical disconnect clears the UI selection. Keep the
                        // logical mesh selected until this transfer finishes.
                        BoardCellScopeRegistry.joinCell(snapshot.physicalBoardId, snapshot.cellId)
                    }
                    disconnected
                }
            },
            prepareTarget = { snapshot ->
                val boardReady = ensureHandoverBoardConnected(snapshot)
                boardReady && boardMatchesSnapshot(bleConnection.connectedBoard, snapshot)
            },
            completeSource = {
                // A technical controller handover changes physical write
                // ownership only. The source remains an equal Board member
                // and keeps following the canonical playlist; clearing its
                // queue here made "controller" a hidden product-level host.
                if (bleConnection.connectionState.value != ConnectionState.DISCONNECTED) {
                    bleConnection.disconnect()
                }
                releasedHandoverBoard = null
            },
            abortSource = { snapshot ->
                val board = releasedHandoverBoard
                if (board != null && bleConnection.connectionState.value == ConnectionState.DISCONNECTED) {
                    bleConnection.connect(board)
                    withTimeoutOrNull(HANDOVER_CONNECT_TIMEOUT_MS) {
                        bleConnection.connectionState.first {
                            it == ConnectionState.CONNECTED ||
                                (it == ConnectionState.DISCONNECTED && bleConnection.connectedBoard == null)
                        }
                    }
                }
                if (bleConnection.connectionState.value != ConnectionState.CONNECTED) {
                    BoardCellScopeRegistry.joinCell(snapshot.physicalBoardId, snapshot.cellId)
                }
                releasedHandoverBoard = null
            },
            abortTarget = { snapshot ->
                if (boardMatchesSnapshot(bleConnection.connectedBoard, snapshot)) {
                    bleConnection.disconnect()
                }
            },
            recoverController = { snapshot ->
                ensureHandoverBoardConnected(snapshot) &&
                    bleConnection.connectedBoard?.isCruxRelay != true
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
        // The technical controller is the only device that writes the wall for
        // a joinable playlist. Resolve and write are separate so the canonical
        // state can distinguish "this device does not have the climb" from
        // "the board write failed".
        boardCellManager?.installPlaylistProjectionWriter(
            object : com.cruxcoach.android.boardcell.BoardPlaylistProjectionWriter {
                override fun resolve(climbUuid: String, angle: Int) =
                    queueManager.resolveProjection(climbUuid, angle)

                override suspend fun write(projection: BoardProjection): Boolean =
                    queueManager.writeProjectionToPhysical(projection)
            })
        boardCellManager?.let { manager ->
            manager.installPeerDiagnosticsProvider {
                val session = queueManager.state.value
                BoardCellPeerDiagnostics(
                    appVersionCode = BuildConfig.VERSION_CODE,
                    bluetoothEnabled = runCatching {
                        context.getSystemService(BluetoothManager::class.java)
                            ?.adapter?.isEnabled == true
                    }.getOrDefault(false),
                    meshRuntimeRunning = fipsMeshRuntime?.running?.value == true,
                    boardConnection = bleConnection.connectionState.value.name,
                    boardKeepAlive = bleConnection.keepAliveActive.value,
                    idleDisconnectArmed = bleConnection.idleDisconnectArmed,
                    autoDisconnectSeconds = bleConnection.autoDisconnectSeconds,
                    sessionRole = session.role.name,
                    sessionVisibility = session.visibility.name,
                    sessionVisibilityRequested = session.visibilityRequested.name,
                    sessionConnecting = session.isConnecting,
                    sessionId = session.sessionId.coerceAtLeast(0),
                    queueSize = session.queue.size,
                    currentIndex = session.currentIndex,
                    currentClimbId = session.currentClimb?.climbUuid,
                    awaitingExplicitSend = session.awaitingExplicitSend,
                    externalBoardOverride = session.externalBoardOverride,
                    pendingCommands = _pendingCommandCount.value,
                    displayName = sharedBoardDisplayName,
                )
            }
            scope.launch {
                manager.projectionRequests.collect { inbound ->
                    manager.commitProjectionRequest(inbound) {
                        queueManager.writeProjectionToPhysical(inbound.request.projection)
                    }
                }
            }
            scope.launch {
                manager.commandAcks.collect { ack ->
                    if (ack.status.isTerminalDecision) {
                        meshAckWaiters.remove(ack.commandId)?.complete(ack)
                    }
                    val pending = pendingPlaylistCommands[ack.commandId] ?: return@collect
                    // ACCEPTED means the controller has it and will answer
                    // again; that is precisely what stops the sub-second
                    // resend loop within one round trip.
                    if (ack.status == BoardCommandStatus.ACCEPTED) {
                        pending.retryAtMs = SystemClock.elapsedRealtime() + COMMAND_RESULT_TIMEOUT_MS
                        return@collect
                    }
                    // A handover refusal or a controller that is behind this
                    // replica has not decided the command. Retain its original
                    // identity and retry promptly after routing/repair catches
                    // up instead of reporting a lost edit.
                    if (ack.status == BoardCommandStatus.NOT_CONTROLLER ||
                        ack.status == BoardCommandStatus.REJECTED_STALE) {
                        pending.retryAtMs = SystemClock.elapsedRealtime() +
                            PLAYLIST_COMMAND_RETRY_INITIAL_MS
                        return@collect
                    }
                    removePendingCommand(ack.commandId)
                    pending.onTerminal?.invoke(ack)
                    when (ack.status) {
                        BoardCommandStatus.COMMITTED -> Unit
                        BoardCommandStatus.REJECTED_CONFLICT,
                        BoardCommandStatus.SUPERSEDED ->
                            _commandFeedback.emit(PlaylistCommandFeedback(
                                PlaylistCommandFeedbackKind.CONFLICT, pending.label))
                        BoardCommandStatus.BOARD_WRITE_FAILED ->
                            _commandFeedback.emit(PlaylistCommandFeedback(
                                PlaylistCommandFeedbackKind.FAILED, pending.label))
                        BoardCommandStatus.ACCEPTED,
                        BoardCommandStatus.NOT_CONTROLLER,
                        BoardCommandStatus.REJECTED_STALE -> Unit
                    }
                }
            }
            // A resend loop of its own, ticking far faster than the 2 s
            // maintenance loop. A playlist edit that vanished with one BLE
            // frame is back on the wire within a quarter of a second instead
            // of after a maintenance tick nobody can see coming.
            scope.launch {
                while (true) {
                    delay(PLAYLIST_COMMAND_RETRY_TICK_MS)
                    val now = SystemClock.elapsedRealtime()
                    pendingPlaylistCommands.forEach { (commandId, pending) ->
                        if (now < pending.retryAtMs) return@forEach
                        if (pending.attempts >= MAX_PLAYLIST_COMMAND_RETRIES) {
                            // Give up rather than retry for ever. The command
                            // id stays in the controller's durable ack window,
                            // so a later reconnect still cannot double-apply it
                            // — this only stops the local resend loop and tells
                            // the user the edit did not land.
                            removePendingCommand(commandId)
                            pending.onTerminal?.invoke(null)
                            Log.w(TAG, "event=playlist_command_abandoned action=${pending.label}")
                            _commandFeedback.emit(PlaylistCommandFeedback(
                                PlaylistCommandFeedbackKind.UNAVAILABLE, pending.label))
                            return@forEach
                        }
                        manager.retryPlaylistCommand(pending.command)
                        pending.retryAtMs = now + playlistRetryBackoffMs(pending.attempts)
                        pending.attempts++
                    }
                }
            }
        }
    }

    /**
     * Turns one index-based UI or GATT-leaf command into canonical operations.
     *
     * Indices are only meaningful against the list they were read from, so
     * they are resolved here — once, against this device's current replica —
     * into the occurrence ids the shared playlist actually speaks. An index
     * that no longer names anything yields no operations, which the caller
     * reports as a conflict rather than guessing at a neighbour.
     */
    private fun playlistOpsFor(
        command: SessionCommand,
        playlist: BoardPlaylistState,
    ): List<BoardPlaylistOp> = when (command) {
        is SessionCommand.Add -> BoardPlaylistOps.add(command.climbUuid, command.angle)
        is SessionCommand.Remove -> BoardPlaylistOps.removeAt(playlist, command.index)
        is SessionCommand.SetCurrent -> BoardPlaylistOps.setCurrentAt(playlist, command.index)
        SessionCommand.Next ->
            if (playlist.activeRest != null) BoardPlaylistOps.endRest()
            else BoardPlaylistOps.next(playlist)
        SessionCommand.Prev -> BoardPlaylistOps.previous(playlist)
        is SessionCommand.Move -> BoardPlaylistOps.moveAt(playlist, command.from, command.to)
        // Re-sending changes nothing about the playlist; it is a request to
        // the controller to try the physical write again.
        SessionCommand.Resend -> emptyList()
        is SessionCommand.Join, SessionCommand.Leave -> emptyList()
    }

    /**
     * Commits an Android-9 client's GATT command into the BoardCell.
     *
     * API 28 has no public BLE L2CAP CoC and therefore cannot be a FIPS node,
     * so it takes part as a leaf: this device is its gateway and commits the
     * command under its own authenticated identity, having already gated the
     * client through JOIN.
     *
     * There is no proxy authority left to grant. The leaf's edit is something
     * the gateway is itself entitled to make — every cell member may edit the
     * shared playlist — so the command travels the ordinary path and the
     * leaf's result byte is the controller's real answer rather than a local
     * guess.
     */
    private suspend fun commitGatewayCommand(
        command: SessionCommand,
        snapshot: BoardCellSnapshot,
    ): BoardCommandAck? {
        val manager = boardCellManager ?: return null
        val commandId = UUID.randomUUID().toString()
        if (command == SessionCommand.Resend) {
            return if (manager.projectSelectedEntry())
                BoardCommandAck(commandId, BoardCommandStatus.COMMITTED, snapshot.cellId,
                    snapshot.epoch, snapshot.controllerTerm, snapshot.sequence, snapshot.stateHash)
            else null
        }
        val ops = playlistOpsFor(command, snapshot.playlist)
        if (ops.isEmpty()) return null
        val playlistCommand = manager.composePlaylistCommand(ops, commandId) ?: return null
        if (manager.isLocalController()) return manager.submitPlaylistCommand(playlistCommand)
        return awaitMeshAck(commandId, command.javaClass.simpleName, playlistCommand) {
            manager.submitPlaylistCommand(playlistCommand)
        }
    }

    /**
     * Runs [send] and waits for the controller's terminal answer.
     *
     * The waiter is always removed, including on timeout and on cancellation,
     * so a leaf that disconnects mid-command cannot leave an entry behind.
     */
    private suspend fun awaitMeshAck(
        commandId: String,
        label: String,
        command: BoardPlaylistCommand,
        send: suspend () -> Any?,
    ): BoardCommandAck? {
        val waiter = CompletableDeferred<BoardCommandAck>()
        meshAckWaiters[commandId] = waiter
        // Tracked like any other in-flight edit while the leaf waits, so the
        // ordinary retry schedule carries it through a handover instead of the
        // gateway sitting on a command nothing is resending.
        pendingPlaylistCommands[commandId] = PendingPlaylistCommand(label, command)
        updatePendingCommandCount()
        return try {
            val initial = send() as? BoardCommandAck
            when {
                initial == null -> null
                initial.status.isTerminalDecision -> initial
                else -> withTimeoutOrNull(GATEWAY_COMMAND_RESULT_TIMEOUT_MS) { waiter.await() }
            }
        } finally {
            meshAckWaiters.remove(commandId)
            removePendingCommand(commandId)
        }
    }

    /** Approval is the only entry into this path. Find the physical board whose
     * deterministic identity belongs to the mesh; never auto-connect merely by
     * discovery order or display name. A CruxRelay intentionally advertises the
     * same physical identity, but it must never become the controller's board
     * connection: its lifetime belongs to the controller we are replacing. */
    private suspend fun ensureHandoverBoardConnected(snapshot: BoardCellSnapshot): Boolean {
        if (physicalBoardMatchesSnapshot(bleConnection.connectedBoard, snapshot) &&
            bleConnection.connectionState.value == ConnectionState.CONNECTED) return true
        val scanner = boardScanner ?: return false
        if (bleConnection.connectionState.value != ConnectionState.DISCONNECTED) {
            bleConnection.disconnect()
            withTimeoutOrNull(HANDOVER_DISCONNECT_TIMEOUT_MS) {
                bleConnection.connectionState.first { it == ConnectionState.DISCONNECTED }
            } ?: return false
        }
        nearbyScanner.stopScan(preserveEntries = true)
        scanner.startScan()
        val board = try {
            withTimeoutOrNull(HANDOVER_SCAN_TIMEOUT_MS) {
                scanner.discoveredBoards.first { boards ->
                    boards.any { physicalBoardMatchesSnapshot(it, snapshot) }
                }.first { physicalBoardMatchesSnapshot(it, snapshot) }
            }
        } finally {
            scanner.stopScan()
        } ?: return false
        bleConnection.connect(board)
        val result = withTimeoutOrNull(HANDOVER_CONNECT_TIMEOUT_MS) {
            bleConnection.connectionState.first {
                it == ConnectionState.CONNECTED || it == ConnectionState.DISCONNECTED
            }
        }
        return result == ConnectionState.CONNECTED &&
            physicalBoardMatchesSnapshot(bleConnection.connectedBoard, snapshot)
    }

    private fun physicalBoardMatchesSnapshot(
        board: DiscoveredBoard?,
        snapshot: BoardCellSnapshot,
    ): Boolean = board?.isCruxRelay == false && boardMatchesSnapshot(board, snapshot)

    private fun boardMatchesSnapshot(board: DiscoveredBoard?, snapshot: BoardCellSnapshot): Boolean {
        board ?: return false
        return boardCellManager?.matchesPhysicalBoard(board, snapshot)
            ?: PhysicalBoardIdentity.matches(
                board,
                snapshot.physicalBoardId,
                snapshot.cellId,
            )
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
        // A member of a BoardCell qualifies as much as a legacy local host
        // does. The GATT server exists so an API-28 phone can take part
        // through a gateway, and every member is equally able to be one — its
        // edits travel as ordinary playlist commands under the gateway's own
        // identity. Requiring a session HOST role here left no gateway at all
        // once the shared playlist stopped having one.
        if (state.role != SessionRole.HOST && state.mesh == null) {
            Log.w(TAG, "Cannot share: neither a local host nor a board member")
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
            // "Last on board" is a statement about the wall. In a shared
            // playlist the selected entry is very often not what is lit, and
            // recording it anyway made the browser claim a climb nobody had
            // sent.
            if (currentClimb != null && !queueState.externalBoardOverride &&
                (queueState.mesh == null || queueState.mesh.selectionOnBoard)) {
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

        // Occupied CruxCoach boards are joined through their BoardCell. Asking
        // an owner to disconnect would dismantle the route participants need.
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
        if (!BoardCellPlatformPolicy.legacyGattPlaylistAvailable(Build.VERSION.SDK_INT)) {
            Log.d(TAG, "BT recovered — legacy GATT playlists stay retired")
            return
        }
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
                        val acquiredNow = !meshRealmHeldForJoin
                        if (acquiredNow) {
                            runtime.acquire(MeshOwners.SESSION.value)
                            meshRealmHeldForJoin = true
                        }
                        if (boardCellManager?.prepareParticipantScope(physical, cellId) != false) {
                            gattClient.sendCommand(SessionQueueProtocol.encodeJoin("", runtime.localNpub))
                        } else if (acquiredNow) {
                            runtime.release(MeshOwners.SESSION.value)
                            meshRealmHeldForJoin = false
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

    // ===== Shared playlist: bounded typed operations over FIPS =====

    /**
     * Append climbs to the BoardCell's one shared playlist.
     *
     * There is nothing to start: the playlist exists for as long as the cell
     * does, and every authenticated member may add to it. The whole list goes
     * in one atomic command so an interrupted import cannot leave half a
     * workout behind.
     */
    fun addToSharedPlaylist(items: List<QueueItem>): Boolean {
        if (items.isEmpty()) return false
        val ops = BoardPlaylistOps.addAll(
            items.map { Triple(it.climbUuid, it.angle, it.restAfterSeconds) })
        // A list longer than one command's bound is split, which is why addAll
        // chains each entry behind the one before it: the import stays
        // contiguous and in its own order even when another member's add lands
        // between two chunks.
        return ops.chunked(BoardPlaylistPolicy.MAX_OPS_PER_COMMAND)
            .mapIndexed { index, chunk ->
                submitPlaylistOps("add(${items.size})#$index", chunk)
            }.all { it }
    }

    /**
     * Empty the shared playlist for everybody.
     *
     * The replacement for "end the playlist": there is no playlist lifecycle
     * to end, so the only meaningful group action is clearing what is in it.
     * The clear carries a generation, so edits that were already in flight
     * against the emptied list are dropped rather than partially resurrecting
     * it, and a retried clear changes nothing a second time.
     */
    fun clearSharedPlaylist(): Boolean =
        submitPlaylistOps("clear", BoardPlaylistOps.clear()).also { submitted ->
            if (!submitted) _commandFeedback.tryEmit(PlaylistCommandFeedback(
                PlaylistCommandFeedbackKind.UNAVAILABLE, "clear"))
        }

    /**
     * Put back the list the last clear emptied, for everybody.
     *
     * Open to every member and not only to whoever cleared: the offer is
     * canonical, so the person who notices is the person who can act. It
     * carries the generation it was composed against, which is what keeps it
     * idempotent and what stops it ever resurrecting an older list.
     */
    fun restoreClearedPlaylist(): Boolean {
        val playlist = boardCellManager?.playlist() ?: return unavailablePlaylistCommand("restore")
        val ops = BoardPlaylistOps.restoreClear(playlist)
        if (ops.isEmpty()) return unavailablePlaylistCommand("restore")
        return submitPlaylistOps("restore_clear", ops).also { submitted ->
            if (!submitted) unavailablePlaylistCommand("restore")
        }
    }

    private fun unavailablePlaylistCommand(label: String): Boolean {
        _commandFeedback.tryEmit(PlaylistCommandFeedback(
            PlaylistCommandFeedbackKind.UNAVAILABLE, label))
        return false
    }

    /**
     * One member's edit to the board's shared list, whatever it is.
     *
     * The list UI composes its own occurrence-addressed operations — including
     * the inverse of an edit it wants to offer an undo for — and they all take
     * this one path, so a hand-composed batch conflicts, retries and
     * acknowledges exactly like every other edit.
     */
    fun editSharedPlaylist(
        label: String,
        ops: List<BoardPlaylistOp>,
        onTerminal: ((BoardCommandAck?) -> Unit)? = null,
    ): Boolean = if (ops.isEmpty()) false else submitPlaylistOps(label, ops, onTerminal)

    /**
     * Drop the queued repeats of the climb at [index] for everybody, in one
     * command.
     *
     * One atomic command, because half a dropped block plus the old short rest
     * is a worse state than either end of the change.
     */
    fun dropRepeatedAttempts(index: Int): Boolean {
        val playlist = boardCellManager?.playlist() ?: return false
        val ops = BoardPlaylistOps.dropRepeatsAfter(playlist, index)
        if (ops.isEmpty()) return false
        return submitPlaylistOps("skip_attempts", ops)
    }

    /**
     * The lamp: put the selected entry on the physical board.
     *
     * Open to every member, and deliberately usable when the selected entry is
     * already the confirmed one — pressing it again is a resend.
     */
    fun projectSelectedEntry(label: String = "send") {
        val manager = boardCellManager ?: return
        scope.launch {
            if (!manager.projectSelectedEntry()) {
                _commandFeedback.emit(PlaylistCommandFeedback(
                    PlaylistCommandFeedbackKind.UNAVAILABLE, label))
            }
        }
    }

    /**
     * "On the board now", from a climb page.
     *
     * The group keeps one way onto the wall — this goes through the same
     * controller and the same sequencer as the lamp on the list — but nobody
     * has to queue a climb and then go and press it. What the wall shows stays
     * an occurrence on the shared list, so everybody can see what happened and
     * where it sits.
     */
    fun lightNow(climbUuid: String, angle: Int, fromEntryId: String? = null, label: String = "light_now") {
        val manager = boardCellManager ?: return
        queueManager.resumeFollowingSharedPlaylist()
        scope.launch {
            if (!manager.lightNow(climbUuid, angle, fromEntryId)) {
                _commandFeedback.emit(PlaylistCommandFeedback(
                    PlaylistCommandFeedbackKind.UNAVAILABLE, label))
            }
        }
    }

    /** Append one occurrence at the end, leaving the wall and selection alone. */
    fun appendSharedPlaylistEntry(
        climbUuid: String,
        angle: Int,
        label: String = "add",
        entryId: String = BoardPlaylistEntryId.random(),
    ) {
        submitPlaylistOps(label, BoardPlaylistOps.add(climbUuid, angle, entryId = entryId))
    }

    /**
     * Record a climb that is already on the wall as the group's current
     * occurrence.
     *
     * Used by the relay: the guest's own bytes lit the board, so re-encoding
     * the climb would be a second write of the same thing. This only makes the
     * shared list agree with what everybody can already see.
     */
    fun adoptProjectedEntry(
        climbUuid: String,
        angle: Int,
        label: String = "adopt",
        entryId: String = BoardPlaylistEntryId.random(),
    ) {
        val playlist = boardCellManager?.playlist() ?: return
        // Landed by construction: the guest's bytes are what lit the wall, and
        // the canonical write they caused has already been committed. The
        // caller's [entryId] is what makes a retry after a handover land on
        // the occurrence that exists instead of adding a second one.
        val ops = BoardPlaylistOps.completeLightNow(playlist, entryId, climbUuid, angle, landed = true)
        if (ops.isEmpty()) return
        submitPlaylistOps(label, ops)
    }

    /** The running rest is over — it ran out, or somebody skipped it. */
    fun endCanonicalRest() {
        if (boardCellManager?.playlist()?.activeRest == null) return
        submitPlaylistOps("skip_rest", BoardPlaylistOps.endRest())
    }

    /** Change the planned rest that follows one entry, for everybody. */
    fun setCanonicalRest(index: Int, seconds: Int) {
        val playlist = boardCellManager?.playlist() ?: return
        submitPlaylistOps("set_rest", BoardPlaylistOps.setRestAt(playlist, index, seconds))
    }

    /**
     * Sends one playlist edit and keeps it retryable until it is answered.
     *
     * Returns false only when nothing could be composed or sent at all — an
     * index that no longer names an entry, or no reachable controller. A
     * command that went out is tracked by its own id, so the retry loop and
     * the controller's durable ack window between them guarantee it lands
     * exactly once.
     */
    private fun submitPlaylistOps(
        label: String,
        ops: List<BoardPlaylistOp>,
        onTerminal: ((BoardCommandAck?) -> Unit)? = null,
    ): Boolean {
        val manager = boardCellManager ?: return false
        val command = manager.composePlaylistCommand(ops) ?: return false
        // Acting on the shared playlist is asking to see it, so a player this
        // device closed earlier comes back rather than leaving the user
        // editing a list they cannot see.
        queueManager.resumeFollowingSharedPlaylist()
        scope.launch {
            val ack = manager.submitPlaylistCommand(command)
            if (ack != null && ack.status == BoardCommandStatus.ACCEPTED) {
                // Sent over the mesh; the controller's real answer arrives on
                // commandAcks. Keep it for retry until then.
                pendingPlaylistCommands[command.commandId] =
                    PendingPlaylistCommand(label, command, onTerminal)
                updatePendingCommandCount()
                return@launch
            }
            onTerminal?.invoke(ack)
            if (ack == null) {
                _commandFeedback.emit(PlaylistCommandFeedback(
                    PlaylistCommandFeedbackKind.UNAVAILABLE, label))
            } else if (ack.status != BoardCommandStatus.COMMITTED) {
                Log.w(TAG, "event=playlist_command_refused action=$label status=${ack.status}")
                _commandFeedback.emit(PlaylistCommandFeedback(
                    PlaylistCommandFeedbackKind.CONFLICT, label))
            }
        }
        return true
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
     * Fire one playlist edit at whoever can serialize it, and say so when it
     * does not go out.
     *
     * Inside a BoardCell every edit is a bounded typed operation against
     * occurrence ids: there is no local shortcut for anybody, including the
     * technical controller, so remote and local control take the identical
     * path. Outside a BoardCell this device has a private playlist and simply
     * edits it, and the legacy GATT client path remains for a participant of a
     * pre-FIPS session.
     */
    private fun sendParticipantCommand(label: String, command: SessionCommand) {
        scope.launch {
            val manager = boardCellManager
            val snapshot = manager?.snapshot()
            if (manager != null && snapshot != null && manager.isCellMember()) {
                if (command == SessionCommand.Resend) {
                    projectSelectedEntry(label)
                    return@launch
                }
                val ops = playlistOpsFor(command, snapshot.playlist)
                if (ops.isEmpty()) {
                    _commandFeedback.emit(PlaylistCommandFeedback(
                        PlaylistCommandFeedbackKind.CONFLICT, label))
                    return@launch
                }
                if (!submitPlaylistOps(label, ops)) {
                    _commandFeedback.emit(PlaylistCommandFeedback(
                        PlaylistCommandFeedbackKind.UNAVAILABLE, label))
                } else {
                    Log.i(TAG, "event=transport_sent transport=fips action=$label")
                }
                return@launch
            }
            if (queueManager.state.value.role == SessionRole.HOST) {
                applyLegacyLocalCommand(command)
                return@launch
            }

            val payload = SessionQueueProtocol.encodeCommand(command)
            val state = queueManager.state.value
            val bleContext = SessionCommandRebaser.context(
                command, state.sessionId, state.currentIndex, state.queue,
            )
            if (bleContext == null) {
                _commandFeedback.emit(PlaylistCommandFeedback(
                    PlaylistCommandFeedbackKind.CONFLICT, label))
                return@launch
            }
            val requestId = nextRequestId.incrementAndGet()
            val extendedPayload = SessionQueueProtocol.encodeCommandRequest(
                requestId, command, bleContext,
            )
            val blePayload = if (gattClient.supportsCommandSize(extendedPayload.size)) {
                pendingBleCommands[requestId] = label
                updatePendingCommandCount()
                extendedPayload
            } else payload
            if (gattClient.sendCommand(blePayload)) {
                // Logged on success too, not only on failure. Only logging the
                // failure leaves the working path silent, and during the
                // 2026-08-06 two-device test that made three candidate causes
                // for "next does nothing" indistinguishable: no line meant
                // "never pressed", "not sent", "sent and ignored" or "applied"
                // equally well. A support log has to separate those.
                Log.i(TAG, "event=transport_sent action=$label")
                if (blePayload === extendedPayload) {
                    delay(COMMAND_RESULT_TIMEOUT_MS)
                    if (pendingBleCommands.remove(requestId) != null) updatePendingCommandCount()
                }
            } else {
                pendingBleCommands.remove(requestId)
                updatePendingCommandCount()
                Log.w(TAG, "event=transport_send_failed action=$label")
                _commandFeedback.emit(PlaylistCommandFeedback(
                    PlaylistCommandFeedbackKind.UNAVAILABLE, label))
            }
        }
    }

    private fun removePendingCommand(commandId: String) {
        pendingPlaylistCommands.remove(commandId)
        updatePendingCommandCount()
    }

    private fun updatePendingCommandCount() {
        _pendingCommandCount.value = pendingPlaylistCommands.size + pendingBleCommands.size
        _pendingPlaylistCommandCount.value = pendingPlaylistCommands.size
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
        val request = SessionQueueProtocol.decodeCommandRequest(data)
        if (request == null) {
            Log.w(TAG, "Failed to decode session command (${data.size} bytes)")
            if (!commandGate.hasJoined(deviceAddress)) rejectClient(deviceAddress)
            return
        }
        val receivedCommand = request.command

        if (receivedCommand is SessionCommand.Join) {
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
                receivedCommand.memberNpub?.let { BoardCellManager.current?.approveMember(it) }

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

        request.requestId?.let { requestId ->
            val previous = synchronized(handledCommandResults) {
                handledCommandResults[commandResultKey(
                    deviceAddress, request.context?.sessionId, requestId)]
            }
            if (previous != null) {
                sendCommandResult(deviceAddress, requestId, previous)
                return
            }
        }

        val cmd = if (request.context == null) {
            receivedCommand
        } else {
            val state = queueManager.state.value
            when (val rebased = SessionCommandRebaser.rebase(
                receivedCommand, request.context, state.sessionId, state.currentIndex, state.queue,
            )) {
                is SessionCommandRebaser.Result.Apply -> rebased.command
                is SessionCommandRebaser.Result.Conflict -> {
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
        val snapshot = boardCellManager?.snapshot()
        if (cmd !is SessionCommand.Leave && cmd !is SessionCommand.Join && snapshot != null &&
            boardCellManager.isCellMember()) {
            val ack = commitGatewayCommand(cmd, snapshot)
            Log.i(TAG, "event=gatt_command_result action=${cmd.javaClass.simpleName} " +
                "status=${ack?.status ?: "unavailable"}")
            val result = when (ack?.status) {
                BoardCommandStatus.COMMITTED -> SessionCommandResult.COMMITTED
                BoardCommandStatus.REJECTED_CONFLICT,
                BoardCommandStatus.REJECTED_STALE,
                BoardCommandStatus.SUPERSEDED -> SessionCommandResult.CONFLICT
                else -> SessionCommandResult.FAILED
            }
            request.requestId?.let {
                rememberCommandResult(deviceAddress, request.context?.sessionId, it, result)
                sendCommandResult(deviceAddress, it, result)
            }
            return
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
        // Once FIPS has supplied a canonical playlist, the old GATT stream is
        // compatibility traffic only. Applying even one delayed delta here
        // could rewind the UI after a newer hashed BoardCell snapshot.
        if (queueManager.state.value.mesh != null) return
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
                val action = pendingBleCommands.remove(event.requestId) ?: return
                updatePendingCommandCount()
                when (event.result) {
                    SessionCommandResult.COMMITTED ->
                        Log.i(TAG, "event=command_committed action=$action")
                    SessionCommandResult.CONFLICT -> _commandFeedback.tryEmit(
                        PlaylistCommandFeedback(PlaylistCommandFeedbackKind.CONFLICT, action))
                    SessionCommandResult.FAILED -> _commandFeedback.tryEmit(
                        PlaylistCommandFeedback(PlaylistCommandFeedbackKind.FAILED, action))
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
