package com.cruxcoach.app.quantum

import com.cruxcoach.app.ble.BoardConnectionUiState
import com.cruxcoach.app.ble.BoardLayerBoardIdentity
import com.cruxcoach.app.ble.BoardSendResult
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.ble.PhysicalBoardIdentity
import com.cruxcoach.app.ble.QuantumCommandFailure
import com.cruxcoach.app.ble.QuantumControllerState
import com.cruxcoach.app.ble.QuantumControllerSyncStatus
import com.cruxcoach.app.ble.hasCompleteQuantumLedMapping
import com.cruxcoach.app.ble.hasConfirmableQuantumDiodeCount
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.QuantumActivePlayer
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The board connection as the Quantum rack needs it (implemented by the connection presenter). */
internal interface QuantumLink {
    val connection: StateFlow<BoardConnectionUiState>
    val controller: StateFlow<QuantumControllerState>
    suspend fun refresh(): Boolean
    suspend fun sendClimb(
        holds: List<BoardHold>,
        placementToLed: Map<Int, Int>,
        routeId: String,
        userId: String,
        color: Int,
        expectedPlayers: List<QuantumActivePlayer>,
        expectedBoard: BoardLayerBoardIdentity,
    ): BoardSendResult
    suspend fun removeLayer(userId: String, expectedRouteId: String, expectedBoard: BoardLayerBoardIdentity): BoardSendResult
}

/** One climb as the detail shows it. Quantum climbs are single-frame and never mirrored. */
internal data class QuantumClimbSnapshot(
    val uuid: String,
    val name: String,
    val angle: Int,
    val layoutId: Long,
    val difficultyAverage: Double?,
    val holds: List<BoardHold>,
)

/** [error] is the Android string key of the failure, for Swift's `L()`. */
internal data class QuantumSendState(
    val sending: Boolean = false,
    val success: Boolean = false,
    val error: String? = null,
)

/**
 * Port of the Quantum half of Android's `BoardSendController` plus the
 * process-wide roster sync of `SessionQueueManager`: binds the rack to the
 * connected controller, reconciles and hydrates every authoritative roster,
 * and lights, removes or replaces this installation's layers — each mutation
 * behind a fresh roster and the full preflight. Never run on hardware.
 */
internal class QuantumLayerController(
    val layers: BoardLayerManager,
    private val link: QuantumLink,
    private val boardRepository: BoardRepository,
    /** Active board selection (brand wire, layout id); null when unreadable. */
    private val activeBoard: () -> Pair<String, Int>?,
    /** False while a joined/connecting shared session owns the board (Android localQuantumLayerManagementAllowed). */
    private val localManagementAllowed: () -> Boolean,
    private val recordSent: suspend (QuantumClimbSnapshot) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _send = MutableStateFlow(QuantumSendState())
    val send: StateFlow<QuantumSendState> = _send.asStateFlow()

    val connectionState: StateFlow<BoardConnectionUiState> get() = link.connection

    /** The browser's "Fits the wall" input (Android BoardBrowserViewModel.refreshQuantumLayers). */
    val browserLayers: kotlinx.coroutines.flow.Flow<com.cruxcoach.app.browse.BrowserQuantumLayerState> =
        layers.state.map { it.toBrowserQuantumLayers() }

    private var sendJob: Job? = null
    private var sendSequence = 0L

    init {
        // Android SessionQueueManager: keep the process-wide rack live whatever screen is open.
        scope.launch {
            link.controller.collect { controller ->
                try {
                    syncRoster(controller)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed catalogue lookup leaves the routes unknown, which blocks sends: fail closed.
                }
            }
        }
        scope.launch {
            link.connection.collect { connection -> connectedLayerBoard(connection)?.let(layers::bindBoard) }
        }
    }

    // ── Roster ───────────────────────────────────────────────────────

    private suspend fun syncRoster(controller: QuantumControllerState) {
        if (controller.syncStatus == QuantumControllerSyncStatus.STALE) {
            layers.setQuantumSyncStatus(controller.syncStatus)
            return
        }
        val connection = link.connection.value
        if (connection.connectedBoard?.boardBrand != BoardBrand.QUANTUM) return
        val identity = connectedLayerBoard(connection)
        if (identity == null) {
            layers.setQuantumSyncStatus(controller.syncStatus)
            return
        }
        // Bind as soon as the model is known: another Quantum must never show the previous board's roster.
        layers.bindBoard(identity)
        layers.setQuantumSyncStatus(controller.syncStatus)
        if (controller.authoritative) hydrate(controller)
    }

    /** Android `hydrateQuantumControllerState`: reconcile, then resolve every reported route locally. */
    private suspend fun hydrate(controller: QuantumControllerState): Boolean {
        if (!controller.authoritative) return false
        val connection = link.connection.value
        val identity = connectedLayerBoard(connection) ?: return false
        val model = connection.quantumModel ?: return false
        if (!layers.isBoundTo(identity)) return false
        layers.reconcile(controller.players)
        val resolved = withContext(io) {
            val ledMap = boardRepository.getPlacementLedMap(model.productSizeId.toInt(), BoardBrand.QUANTUM.wireValue)
            controller.players.mapNotNull { player ->
                val climb = boardRepository.getQuantumClimbByExternalRoute(
                    routeUuid = player.routeId,
                    model = model.wireValue,
                    allowDirectUuidFallback = layers.ownsIdentity(player.userId),
                ) ?: return@mapNotNull null
                val holds = BoardClimbParser.parseSingleFrameStrict(climb.frames) ?: return@mapNotNull null
                if (!hasCompleteQuantumLedMapping(holds, ledMap)) return@mapNotNull null
                BoardLayerControllerRouteKey(player.routeId, player.userId) to
                    BoardLayerRouteDetails(climbUuid = climb.uuid, climbName = climb.name, holds = holds)
            }.toMap()
        }
        // Details apply by exact route/user tuple to current entries only, so a
        // concurrent roster refresh is safe; a different board or model is not.
        val latest = link.connection.value
        if (connectedLayerBoard(latest) != identity || latest.quantumModel != model || !layers.isBoundTo(identity)) return false
        layers.hydrateControllerRoutes(resolved)
        return true
    }

    private suspend fun refreshAndHydrate(): Boolean {
        if (!link.refresh()) return false
        return hydrate(link.controller.value)
    }

    private fun connectedLayerBoard(connection: BoardConnectionUiState): BoardLayerBoardIdentity? {
        val board = connection.connectedBoard ?: return null
        val model = connection.quantumModel ?: return null
        if (board.boardBrand != BoardBrand.QUANTUM) return null
        val physical = PhysicalBoardIdentity.resolve(board) ?: return null
        return BoardLayerBoardIdentity(physical, model.productSizeId)
    }

    private fun connected(): Boolean = link.connection.value.connection == ConnectionState.CONNECTED

    private fun layersBelongToConnectedBoard(): Boolean = layers.isBoundTo(connectedLayerBoard(link.connection.value))

    // ── Commands ─────────────────────────────────────────────────────

    /** The detail lamp: the current climb into its existing or the first free conflict-free layer, then sent. */
    fun light(climb: QuantumClimbSnapshot) {
        if (!localManagementAllowed() || _send.value.sending) return
        if (climb.holds.isEmpty() || !connected()) return
        val slot = layers.layerForClimb(climb.uuid)?.slot ?: automaticSlot(climb) ?: return
        val color = QuantumLayerUiPolicy.summarize(
            layers.state.value, climb.uuid, climb.holds.mapTo(mutableSetOf()) { it.placementId },
        ).suggestedColor
        val slotBefore = layers.state.value.layers.firstOrNull { it.slot == slot }
        start { fence ->
            mismatchError(climb)?.let { error ->
                report(fence, QuantumSendState(error = error))
                return@start
            }
            val layer = buildLayer(climb, slot, slotBefore, color) ?: run {
                report(fence, QuantumSendState(error = KEY_COLOR_TAKEN))
                return@start
            }
            if (!layers.assignPreviewIfCurrent(layer, slotBefore?.planKey())) {
                report(fence, QuantumSendState(error = KEY_STATE_UNAVAILABLE))
                return@start
            }
            sendLayers(listOf(layer.planKey()), fence, climb)
        }
    }

    /** Android `assignCurrentToBoardLayer`: a local plan only, no BLE command. */
    fun assign(climb: QuantumClimbSnapshot, slot: Int, color: Int?) {
        if (!localManagementAllowed() || climb.holds.isEmpty()) return
        val existing = layers.layerForClimb(climb.uuid)
        if (existing != null && existing.slot != slot) {
            // A live route cannot jump to another controller identity without an explicit removal.
            _send.value = QuantumSendState(error = KEY_ALREADY_ASSIGNED)
            return
        }
        val slotBefore = layers.state.value.layers.firstOrNull { it.slot == slot }
        start(showsSpinner = false) { fence ->
            val layer = buildLayer(climb, slot, slotBefore, color) ?: run {
                report(fence, QuantumSendState(error = KEY_COLOR_TAKEN))
                return@start
            }
            if (!layers.assignPreviewIfCurrent(layer, slotBefore?.planKey())) {
                report(fence, QuantumSendState(error = KEY_STATE_UNAVAILABLE))
            } else {
                report(fence, QuantumSendState())
            }
        }
    }

    /** Send one assigned layer; the layer owns its immutable route/hold snapshot. */
    fun sendLayer(slot: Int) = launchLayerSend(listOf(slot))

    /** All local plans, sequentially, behind a whole-rack preflight. */
    fun sendAll() {
        val slots = layers.state.value.layers.sortedBy { it.slot }.map { it.slot }
        if (slots.isNotEmpty()) launchLayerSend(slots)
    }

    fun cancelReplacement(slot: Int) {
        if (!localManagementAllowed() || _send.value.sending) return
        layers.cancelReplacement(slot)
    }

    /** Android `removeBoardLayer`: a plan is dropped locally, a lit layer only via a scoped, confirmed removal. */
    fun remove(slot: Int) {
        if (!localManagementAllowed()) return
        val layer = layers.state.value.layers.firstOrNull { it.slot == slot } ?: return
        val expectedPlan = layer.planKey()
        if (layer.confirmedRouteUuid == null) {
            layers.removePreview(slot)
            return
        }
        if (link.connection.value.connectedBoard?.boardBrand != BoardBrand.QUANTUM) return
        start { fence ->
            try {
                val expectedBoard = layers.state.value.board
                if (expectedBoard == null || !layersBelongToConnectedBoard() || !refreshAndHydrate() ||
                    !layers.isBoundTo(expectedBoard)
                ) {
                    report(fence, QuantumSendState(error = KEY_STATE_UNAVAILABLE))
                    return@start
                }
                val refreshed = layers.state.value.layers.firstOrNull { it.slot == slot }
                if (refreshed != null && refreshed.planKey() != expectedPlan) {
                    report(fence, QuantumSendState(error = KEY_STATE_UNAVAILABLE))
                    return@start
                }
                val expectedRoute = layer.confirmedRouteUuid
                if (refreshed?.confirmedRouteUuid == null) {
                    layers.removeOwned(expectedPlan)
                    report(fence, QuantumSendState(success = true))
                    return@start
                }
                if (refreshed.userUuid != layer.userUuid || !refreshed.confirmedRouteUuid.equals(expectedRoute, ignoreCase = true)) {
                    report(fence, QuantumSendState(error = KEY_STATE_UNAVAILABLE))
                    return@start
                }
                val result = try {
                    link.removeLayer(refreshed.userUuid, expectedRoute, expectedBoard)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    BoardSendResult.WRITE_FAILED
                }
                val success = result == BoardSendResult.OK
                if (success) layers.removeOwned(expectedPlan) else layers.failProjection(expectedPlan)
                report(
                    fence,
                    QuantumSendState(success = success, error = if (success) null else failureKey(result, link.controller.value.lastFailure)),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                layers.failProjection(expectedPlan)
                report(fence, QuantumSendState(error = KEY_GENERIC))
            } finally {
                finish(fence, listOf(expectedPlan))
            }
        }
    }

    fun dismissResult() {
        if (!_send.value.sending) _send.value = QuantumSendState()
    }

    // ── Sending ──────────────────────────────────────────────────────

    private fun launchLayerSend(slots: List<Int>) {
        if (!localManagementAllowed() || _send.value.sending || !connected()) return
        val connectedBrand = link.connection.value.connectedBoard?.boardBrand
        if (connectedBrand != null && connectedBrand != BoardBrand.QUANTUM) {
            _send.value = QuantumSendState(error = KEY_CONNECTED_MISMATCH)
            return
        }
        val plans = slots.map { slot -> layers.state.value.layers.firstOrNull { it.slot == slot }?.planKey() ?: return }
        start { fence -> sendLayers(plans, fence, null) }
    }

    private suspend fun sendLayers(requested: List<BoardLayerPlanKey>, fence: Long, onScreen: QuantumClimbSnapshot?) {
        val slots = requested.map(BoardLayerPlanKey::slot)
        try {
            val expectedBoard = layers.state.value.board
            if (expectedBoard == null || !layersBelongToConnectedBoard()) {
                report(fence, QuantumSendState(error = KEY_OTHER_BOARD))
                return
            }
            if (BoardBrand.fromWireOrNull(activeBoard()?.first) != BoardBrand.QUANTUM) {
                report(fence, QuantumSendState(error = KEY_BRAND_MISMATCH))
                return
            }
            // Another client can change the four-player roster while the screen is open:
            // the connection-time snapshot is not a safe precondition.
            if (!refreshAndHydrate()) {
                report(fence, QuantumSendState(error = KEY_STATE_UNAVAILABLE))
                return
            }
            QuantumLayerUiPolicy.planBlock(layers.state.value, slots)?.let { block ->
                requested.forEach(layers::failProjection)
                report(fence, QuantumSendState(error = blockKey(block)))
                return
            }
            report(fence, QuantumSendState(sending = true))
            var success = true
            var lastResult = BoardSendResult.OK
            for ((index, expectedPlan) in requested.withIndex()) {
                // Not transactional on the controller: fresh truth and a full preflight before every mutation.
                if (!refreshAndHydrate()) {
                    report(fence, QuantumSendState(sending = true, error = KEY_STATE_UNAVAILABLE))
                    success = false
                    break
                }
                val remaining = requested.drop(index)
                val block = QuantumLayerUiPolicy.planBlock(layers.state.value, remaining.map(BoardLayerPlanKey::slot))
                if (block != null) {
                    remaining.forEach(layers::failProjection)
                    report(fence, QuantumSendState(sending = true, error = blockKey(block)))
                    success = false
                    break
                }
                val layer = layers.state.value.layers.firstOrNull { it.planKey() == expectedPlan }
                if (layer == null) {
                    success = false
                    break
                }
                val outcome = sendLayer(layer, expectedPlan, expectedBoard, fence)
                if (outcome == null) {
                    success = false
                    break
                }
                lastResult = outcome
                if (outcome != BoardSendResult.OK) {
                    success = false
                    break
                }
                if (onScreen != null && onScreen.uuid == layer.climbUuid && onScreen.angle == layer.angle) {
                    try { recordSent(onScreen) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
            }
            val current = _send.value
            report(
                fence,
                QuantumSendState(
                    success = success,
                    error = when {
                        success -> null
                        lastResult != BoardSendResult.OK -> failureKey(lastResult, link.controller.value.lastFailure)
                        else -> current.error ?: KEY_SEND_FAILED
                    },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            report(fence, QuantumSendState(error = KEY_GENERIC))
        } finally {
            finish(fence, requested)
        }
    }

    /** One layer at the write boundary. Null: refused locally (state already reported). */
    private suspend fun sendLayer(
        layer: BoardClimbLayer,
        expectedPlan: BoardLayerPlanKey,
        expectedBoard: BoardLayerBoardIdentity,
        fence: Long,
    ): BoardSendResult? {
        fun refuse(key: String): BoardSendResult? {
            layers.failProjection(expectedPlan)
            report(fence, QuantumSendState(sending = true, error = key))
            return null
        }
        val expectedPlayers = link.controller.value.players
        if (!layers.state.value.matchesQuantumPlayers(expectedPlayers)) return refuse(KEY_STATE_UNAVAILABLE)
        if (!layers.hasControllerCapacityFor(layer.slot)) return refuse(KEY_BOARD_FULL)
        if (!hasConfirmableQuantumDiodeCount(layer.holds)) return refuse(KEY_MULTI_FRAME)
        val rack = layers.state.value
        val assessment = BoardLayerConflictPolicy.assess(layer.holds, rack.layers, rack.externalLayers, layer.slot)
        if (assessment.unknownLayerCount > 0) return refuse(KEY_EXTERNAL_UNKNOWN)
        if (assessment.sharedHoldCount > 0) return refuse(KEY_SHARED_HOLD)
        if (layer.color in rack.reservedLayerColors(layer.slot)) return refuse(KEY_COLOR_TAKEN)
        val placementToLed = withContext(io) {
            boardRepository.getPlacementLedMap(expectedBoard.productSizeId.toInt(), BoardBrand.QUANTUM.wireValue)
        }
        if (!layers.isBoundTo(expectedBoard) || !hasCompleteQuantumLedMapping(layer.holds, placementToLed)) {
            return refuse(KEY_NO_LED_DATA)
        }
        if (!layers.beginProjection(expectedPlan)) {
            report(fence, QuantumSendState(sending = true, error = KEY_STATE_UNAVAILABLE))
            return null
        }
        val result = link.sendClimb(
            holds = layer.holds,
            placementToLed = placementToLed,
            routeId = layer.routeUuid,
            userId = layer.userUuid,
            color = layer.color,
            expectedPlayers = expectedPlayers,
            expectedBoard = expectedBoard,
        )
        if (result == BoardSendResult.OK) layers.confirmProjection(expectedPlan) else layers.failProjection(expectedPlan)
        return result
    }

    private suspend fun buildLayer(
        climb: QuantumClimbSnapshot,
        slot: Int,
        existing: BoardClimbLayer?,
        requestedColor: Int?,
    ): BoardClimbLayer? {
        val usedElsewhere = layers.state.value.reservedLayerColors(slot)
        val requested = requestedColor ?: existing?.color ?: layers.defaultColor(slot)
        val color = requested.takeIf { it in BoardLayerManager.LAYER_COLORS && it !in usedElsewhere }
            ?: BoardLayerManager.LAYER_COLORS.firstOrNull { it !in usedElsewhere }
            ?: return null
        val routeUuid = withContext(io) { boardRepository.getQuantumExternalRouteUuid(climb.uuid) } ?: climb.uuid
        return BoardClimbLayer(
            slot = slot,
            climbUuid = climb.uuid,
            routeUuid = routeUuid,
            climbName = climb.name,
            angle = climb.angle,
            userUuid = layers.identityForSlot(slot),
            color = color,
            holds = climb.holds,
            status = BoardLayerStatus.PREVIEW,
        )
    }

    /** The rack's one-answer policy, so the lamp and the visible recommendation cannot diverge. */
    private fun automaticSlot(climb: QuantumClimbSnapshot): Int? {
        val summary = QuantumLayerUiPolicy.summarize(
            layers.state.value, climb.uuid, climb.holds.mapTo(mutableSetOf()) { it.placementId },
        )
        val slot = summary.suggestedSlot
        if (slot == null || summary.suggestedColor == null) {
            _send.value = QuantumSendState(error = summary.suggestionBlock?.let(::blockKey) ?: KEY_ALL_ASSIGNED)
            return null
        }
        return slot
    }

    /**
     * Android `resolveBoardConfigurationMismatch` for a Quantum climb: controller
     * evidence first, then the active board selection. Its fix-it dialog is not ported.
     */
    private fun mismatchError(climb: QuantumClimbSnapshot): String? {
        val connection = link.connection.value
        val connectedBrand = connection.connectedBoard?.boardBrand
        if (connectedBrand != null && connectedBrand != BoardBrand.QUANTUM) return KEY_CONNECTED_MISMATCH
        val active = activeBoard()
        if (active != null && BoardBrand.fromWireOrNull(active.first) != BoardBrand.QUANTUM) return KEY_BRAND_MISMATCH
        val verified: QuantumBoardModel? = connection.quantumModel
        if (verified != null && climb.layoutId != verified.layoutId) return KEY_CONNECTED_MISMATCH
        val requiredLayout = verified?.layoutId ?: climb.layoutId
        if (active != null && requiredLayout != active.second.toLong()) return KEY_BRAND_MISMATCH
        return null
    }

    // ── Plumbing ─────────────────────────────────────────────────────

    /** Every command gets a fence; only the newest one may write the visible outcome. */
    private fun start(showsSpinner: Boolean = true, block: suspend (Long) -> Unit) {
        sendJob?.cancel()
        sendSequence += 1
        val fence = sendSequence
        if (showsSpinner) _send.value = QuantumSendState(sending = true)
        sendJob = scope.launch { block(fence) }
    }

    private fun report(fence: Long, state: QuantumSendState) {
        if (fence == sendSequence) _send.value = state
    }

    /** No exception may strand a rack row or the spinner. */
    private fun finish(fence: Long, plans: List<BoardLayerPlanKey>) {
        plans.forEach { plan ->
            if (layers.state.value.layers.firstOrNull { it.planKey() == plan }?.status == BoardLayerStatus.SENDING) {
                layers.failProjection(plan)
            }
        }
        if (fence == sendSequence) _send.update { it.copy(sending = false) }
    }

    companion object {
        // Android string keys, rendered by Swift.
        const val KEY_NO_LED_DATA = "board_send_error_no_led_data"
        const val KEY_MULTI_FRAME = "board_layer_error_multi_frame_unverified"
        const val KEY_EXTERNAL_UNKNOWN = "board_layer_error_external_unknown"
        const val KEY_SHARED_HOLD = "board_layer_error_shared_hold"
        const val KEY_BOARD_FULL = "board_layer_error_board_full"
        const val KEY_COLOR_TAKEN = "board_layer_error_color_taken"
        const val KEY_ALL_ASSIGNED = "board_layer_error_all_assigned"
        const val KEY_STATE_UNAVAILABLE = "board_layer_error_state_unavailable"
        const val KEY_OTHER_BOARD = "board_layer_error_other_board"
        const val KEY_ALREADY_ASSIGNED = "quantum_layer_already_assigned_error"
        const val KEY_CONNECTED_MISMATCH = "board_send_error_connected_board_mismatch"
        const val KEY_BRAND_MISMATCH = "board_send_error_brand_mismatch"
        const val KEY_SEND_FAILED = "board_send_error_send_failed"
        const val KEY_GENERIC = "board_send_error_generic"

        fun blockKey(block: QuantumLayerSuggestionBlock): String = when (block) {
            QuantumLayerSuggestionBlock.NO_HOLDS -> KEY_NO_LED_DATA
            QuantumLayerSuggestionBlock.MULTI_FRAME_UNVERIFIED -> KEY_MULTI_FRAME
            QuantumLayerSuggestionBlock.UNKNOWN_LAYER -> KEY_EXTERNAL_UNKNOWN
            QuantumLayerSuggestionBlock.HOLD_CONFLICT -> KEY_SHARED_HOLD
            QuantumLayerSuggestionBlock.BOARD_FULL -> KEY_BOARD_FULL
            QuantumLayerSuggestionBlock.NO_COLOR -> KEY_COLOR_TAKEN
            QuantumLayerSuggestionBlock.NO_SLOT -> KEY_ALL_ASSIGNED
        }

        /** Android `quantumFailureResource`, extended by the iOS transport's own result codes. */
        fun failureKey(result: BoardSendResult, failure: QuantumCommandFailure? = null): String = when (failure) {
            QuantumCommandFailure.ROUTE_IN_USE -> "board_layer_error_route_in_use"
            QuantumCommandFailure.SPOT_UNAVAILABLE -> KEY_SHARED_HOLD
            QuantumCommandFailure.COLOR_TAKEN -> KEY_COLOR_TAKEN
            QuantumCommandFailure.USER_ID_IN_USE -> "board_layer_error_user_in_use"
            QuantumCommandFailure.BOARD_FULL -> KEY_BOARD_FULL
            QuantumCommandFailure.ROUTESETTER_MODE -> "board_layer_error_routesetter"
            QuantumCommandFailure.DIODE_MISSING -> "board_layer_error_diode_missing"
            QuantumCommandFailure.ACK_TIMEOUT -> "board_layer_error_timeout"
            QuantumCommandFailure.REFUSED -> KEY_SEND_FAILED
            null -> when (result) {
                BoardSendResult.NOT_CONNECTED -> KEY_STATE_UNAVAILABLE
                BoardSendResult.BOARD_MISMATCH -> KEY_OTHER_BOARD
                BoardSendResult.WRONG_BOARD_FAMILY -> KEY_CONNECTED_MISMATCH
                BoardSendResult.QUANTUM_PRECONDITION_FAILED, BoardSendResult.QUANTUM_STATE_UNAVAILABLE -> KEY_STATE_UNAVAILABLE
                BoardSendResult.FRAME_TOO_LARGE -> KEY_MULTI_FRAME
                BoardSendResult.WRITE_TIMEOUT -> "board_layer_error_timeout"
                else -> KEY_SEND_FAILED
            }
        }
    }
}
