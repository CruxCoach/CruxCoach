package com.cruxcoach.android.ui.board

import android.util.Log
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardClimbLayer
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerStatus
import com.cruxcoach.android.ble.BoardLayerConflictPolicy
import com.cruxcoach.android.ble.QuantumCommandFailure
import com.cruxcoach.android.ble.BoardProjectionPolicy
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.boardcell.BoardProjection
import com.cruxcoach.android.boardcell.ActiveBoardCellWriteGateway
import com.cruxcoach.android.boardcell.BoardCellWriteGateway
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.util.DateTimeUtil
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles BLE send/clear operations and nearby climb advertising.
 *
 * Plain Kotlin class (not a ViewModel). Receives a [CoroutineScope] from the
 * parent ViewModel for launching async work.
 */
internal class BoardSendController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ClimbDetailState>,
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val bleConnection: BoardBleConnection,
    private val userPreferences: UserPreferences,
    private val climbAdvertiser: ClimbBleAdvertiser,
    private val sessionQueueManager: SessionQueueManager,
    private val isSharingEnabled: () -> Boolean,
    private val boardLayerManager: BoardLayerManager,
    private val boardCellWriteGateway: BoardCellWriteGateway = ActiveBoardCellWriteGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var sendJob: Job? = null

    /** Cancel any in-flight send (used when switching climbs). */
    fun cancelSend() {
        sendJob?.cancel()
    }

    /** Record a successful board-send into the local "Verlauf" history.
     *
     * The Verlauf is "climbs you SENT to the board" — the engagement event the
     * user asked for (sent, not just clicked) — so it fires on every
     * successful push (Aurora/Kilter AND MoonBoard), independent of whether an
     * ascent is later logged. Deduped by (climb, angle) at the DB layer
     * (INSERT OR REPLACE), so re-sending the same climb just bumps its entry to
     * most-recent rather than flooding the list. Best-effort: a history write
     * must never fail the send. */
    private suspend fun recordSentToHistory(s: ClimbDetailState) {
        val climb = s.climb ?: return
        val now = DateTimeUtil.nowIso()
        runCatching {
            personalBoardRepo.recordClimbHistory(
                climbUuid = climb.uuid,
                climbName = climb.name,
                angle = s.angle.toLong(),
                difficultyAverage = climb.difficultyAverage,
                boardBrand = climb.boardBrand,
                layoutId = climb.layoutId,
                climbedAt = now,
                recordedAt = now,
            )
        }.onFailure { Log.w(TAG, "recordClimbHistory(send) failed", it) }
    }

    fun sendToBoard(@Suppress("UNUSED_PARAMETER") automaticLayer: Boolean = false) {
        // When a session queue is active, the queue controls what's on the board.
        // Individual climb sends from detail views are suppressed.
        if (isBoardOwnedBySession()) {
            Log.d(TAG, "sendToBoard: suppressed (session queue active)")
            return
        }
        val meshManager = com.cruxcoach.android.boardcell.BoardCellManager.current
        if (meshManager?.canSendViaMesh() == true) {
            val s = state.value
            val climb = s.climb ?: return
            val commandId = meshManager.sendProjectionRequest(BoardProjection(
                climb.uuid,
                s.angle,
                BoardProjectionPolicy.projectionSurvivesDisconnect(climb.brand),
            ))
            state.update { current -> current.copy(
                ble = current.ble.copy(
                    isSending = false,
                    success = commandId != null,
                    error = if (commandId == null) R.string.board_send_error_send_failed else null,
                ),
                nearby = current.nearby.copy(
                    debugInfo = if (commandId != null) "sent via board mesh" else "mesh send failed")
            ) }
            if (commandId != null) scope.launch { recordSentToHistory(s) }
            return
        }
        // FEAT-027: a MoonBoard climb sends an ASCII `frames` payload — it has
        // no Aurora `holds` list and no LED map. Gate on a non-blank frames
        // string and route through the dedicated MoonBoard transport.
        if (state.value.climb?.brand == BoardBrand.MOONBOARD) {
            sendMoonBoardToBoard()
            return
        }
        if (state.value.climb?.brand == BoardBrand.QUANTUM) {
            // Quantum never follows page selection automatically. The detail
            // lamp is an explicit request: assign the current climb to the
            // selected local layer, then transmit exactly that identity.
            sendCurrentQuantumClimb()
            return
        }
        val s = state.value
        if (s.holds.isEmpty() || s.ble.connectionState != ConnectionState.CONNECTED) {
            state.update { it.copy(nearby = it.nearby.copy(
                debugInfo = "skip: holds=${s.holds.size} conn=${s.ble.connectionState}"
            )) }
            return
        }
        if (s.ble.isSending) {
            state.update { it.copy(nearby = it.nearby.copy(debugInfo = "skip: already sending")) }
            return
        }

        state.update { it.copy(
            ble = it.ble.copy(
                isSending = true,
                success = false,
                error = null,
                warning = null,
            ),
            nearby = it.nearby.copy(debugInfo = "sending...")
        ) }
        Log.i(TAG, "sendToBoard: start frames=${s.holds.size}")
        sendJob = scope.launch {
            try {
                // Board-match guard, part 1: the CONNECTED board's brand wins.
                // Switching the active board in Settings never disconnects, so
                // the pref can diverge from the board still on the link — the
                // pref-only check below would happily send a Tension climb to
                // a still-connected Kilter board, lighting the wrong holds.
                val connectedBrand = bleConnection.connectedBoardBrand.value
                if (connectedBrand != null && s.climb != null && s.climb.brand != connectedBrand) {
                    state.update { it.copy(
                        ble = it.ble.copy(isSending = false, error = R.string.board_send_error_connected_board_mismatch),
                        nearby = it.nearby.copy(debugInfo = "connected-board brand mismatch")
                    ) }
                    return@launch
                }
                // Board-match guard, part 2: you can only send a climb to a
                // board of the same family as the ACTIVE board. A climb opened
                // from a mixed list or deep link can differ from the active
                // board; sending it would light the wrong holds. (This Kilter
                // branch is only reached for non-MoonBoard climbs, so the
                // check catches the "active board is a MoonBoard" mismatch.)
                val activeBrand = userPreferences.boardBrand.first()
                if (s.climb != null && s.climb.brand != BoardBrand.fromWire(activeBrand)) {
                    state.update { it.copy(
                        ble = it.ble.copy(isSending = false, error = R.string.board_send_error_brand_mismatch),
                        nearby = it.nearby.copy(debugInfo = "board-brand mismatch")
                    ) }
                    return@launch
                }
                state.update { it.copy(nearby = it.nearby.copy(debugInfo = "loading LED map...")) }
                val productSizeId = userPreferences.boardProductSizeId.first()
                val placementToLed = withContext(ioDispatcher) {
                    // FEAT-031: scope the LED map to the active board's brand so an
                    // Aurora board (Tension etc.) lights its OWN holds, not Kilter's
                    // same-numbered product_size rows. activeBrand == climb.brand here
                    // (guarded above), so it is the connected board's brand.
                    boardRepository.getPlacementLedMap(productSizeId, activeBrand)
                }
                if (placementToLed.isEmpty()) {
                    state.update { it.copy(
                        ble = it.ble.copy(isSending = false, error = R.string.board_send_error_no_led_data),
                        nearby = it.nearby.copy(debugInfo = "no LED data")
                    ) }
                    return@launch
                }
                state.update { it.copy(nearby = it.nearby.copy(debugInfo = "BLE sending...")) }
                // FEAT-031 colours, in priority order:
                //  1. the board's OWN catalogue colours (placement_roles.led_color),
                //     keyed by the real frame role-id — once the board's chunk ships
                //     placement_roles this is exact + per-board;
                //  2. else the conventional per-brand defaults — Kilter stays
                //     user-configurable, the Aurora family uses its standard scheme
                //     (MoonBoard uses its own send path).
                // brand == climb.brand == active board (guarded above).
                val brand = BoardBrand.fromWire(activeBrand)
                val roleColorMap = withContext(ioDispatcher) {
                    boardRepository.getRoleColorMapForBrand(activeBrand)
                }.ifEmpty {
                    val fallback = if (brand == BoardBrand.KILTER) {
                        userPreferences.ledHoldColors.first()
                    } else {
                        LedHoldColors.standardFor(brand)
                    }
                    fallback.toRoleColorMap()
                }
                // Holds outside the configured board size (e.g. the detail
                // screen's larger "effective board" render, or kickboard rows
                // on a no-kickboard size) have no LED mapping and are skipped
                // by the encoder — the wall shows a partial climb. Surface a
                // non-blocking warning instead of a plain "sent ok".
                val unmappedHolds = s.holds.count { it.placementId !in placementToLed }
                // FEAT-023: if NONE of the climb's holds map to the active
                // board's LED grid it's a wrong-board climb the brand guard
                // can't catch (e.g. a Kilter Homewall climb opened from a list
                // while an Original size is configured — both are 'kilter').
                // Refuse with a clear message instead of firing an empty frame
                // + a vague "some holds not lit" warning.
                if (unmappedHolds == s.holds.size) {
                    state.update { it.copy(
                        ble = it.ble.copy(isSending = false, error = R.string.board_send_error_climb_off_board),
                        nearby = it.nearby.copy(debugInfo = "all holds unmapped — wrong board/size")
                    ) }
                    return@launch
                }
                val success = boardCellWriteGateway.project(
                    BoardProjection(s.climb!!.uuid, s.angle,
                        BoardProjectionPolicy.projectionSurvivesDisconnect(s.climb.brand))) {
                        bleConnection.sendClimb(
                            s.holds, placementToLed, roleColorMap,
                        )
                    }
                Log.i(TAG, "sendToBoard: writes done success=$success unmapped=$unmappedHolds")
                state.update { it.copy(
                    ble = it.ble.copy(
                        isSending = false,
                        success = success,
                        error = if (!success) R.string.board_send_error_send_failed else null,
                        warning = if (success && unmappedHolds > 0) R.string.board_send_warning_holds_not_lit else null,
                    ),
                    nearby = it.nearby.copy(debugInfo = "sent ok=$success unmapped=$unmappedHolds")
                ) }
                if (success) recordSentToHistory(s)
                // Advertise climb to nearby devices if sharing is enabled
                val sharingEnabled = isSharingEnabled()
                val climb = state.value.climb
                val debugMsg = when {
                    !success -> "send failed"
                    climb == null -> "climb null"
                    else -> {
                        val result = climbAdvertiser.advertiseClimb(climb.uuid, state.value.angle, sharingEnabled)
                        "adv: $result"
                    }
                }
                state.update { it.copy(nearby = it.nearby.copy(debugInfo = debugMsg)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sendToBoard failed", e)
                state.update { it.copy(
                    ble = it.ble.copy(isSending = false, error = R.string.board_send_error_generic),
                    nearby = it.nearby.copy(debugInfo = "exception: ${e.message?.take(50)}")
                ) }
            }
        }
    }

    /** Store the current Quantum climb in the selected local slot. No BLE
     * command is sent; PREVIEW is intentionally a useful offline state. */
    fun assignCurrentToBoardLayer() {
        if (isBoardOwnedBySession()) return
        val snapshot = state.value
        if (snapshot.climb?.brand != BoardBrand.QUANTUM || snapshot.holds.isEmpty()) return
        val slot = selectedSlotFor(snapshot) ?: run {
            state.update { it.copy(ble = it.ble.copy(error = R.string.board_layer_error_all_assigned)) }
            return
        }
        scope.launch {
            val layer = buildQuantumLayer(snapshot, slot) ?: return@launch
            boardLayerManager.assignPreview(layer)
            state.update {
                it.copy(
                    selectedBoardLayerSlot = slot,
                    selectedBoardLayerColor = layer.color,
                    ble = it.ble.copy(success = false, error = null, warning = null),
                )
            }
        }
    }

    /** Send one already assigned layer. The displayed detail climb may be a
     * different page; the layer owns the immutable route/hold snapshot. */
    fun sendBoardLayer(slot: Int) = launchQuantumLayerSend(listOf(slot))

    /** Send all four local assignments sequentially. A full capacity
     * preflight prevents a half-applied rack when foreign users leave fewer
     * physical controller places than the local preview needs. */
    fun sendAllBoardLayers() {
        val slots = boardLayerManager.state.value.layers.sortedBy { it.slot }.map { it.slot }
        if (slots.isEmpty()) return
        if (!boardLayerManager.canProjectAll()) {
            state.update { it.copy(ble = it.ble.copy(error = R.string.board_layer_error_board_full)) }
            return
        }
        launchQuantumLayerSend(slots)
    }

    private fun sendCurrentQuantumClimb() {
        if (isBoardOwnedBySession()) return
        val snapshot = state.value
        if (snapshot.holds.isEmpty() || snapshot.ble.connectionState != ConnectionState.CONNECTED) return
        val slot = selectedSlotFor(snapshot) ?: run {
            state.update { it.copy(ble = it.ble.copy(error = R.string.board_layer_error_all_assigned)) }
            return
        }
        sendJob?.cancel()
        sendJob = scope.launch {
            val connectedBrand = bleConnection.connectedBoardBrand.value
            if (connectedBrand != null && connectedBrand != BoardBrand.QUANTUM) {
                state.update { it.copy(
                    ble = it.ble.copy(error = R.string.board_send_error_connected_board_mismatch),
                ) }
                return@launch
            }
            if (BoardBrand.fromWire(userPreferences.boardBrand.first()) != BoardBrand.QUANTUM) {
                state.update { it.copy(
                    ble = it.ble.copy(error = R.string.board_send_error_brand_mismatch),
                ) }
                return@launch
            }
            val layer = buildQuantumLayer(snapshot, slot) ?: return@launch
            boardLayerManager.assignPreview(layer)
            state.update {
                it.copy(
                    selectedBoardLayerSlot = slot,
                    selectedBoardLayerColor = layer.color,
                )
            }
            sendQuantumLayers(listOf(slot))
        }
    }

    private fun selectedSlotFor(snapshot: ClimbDetailState): Int? =
        snapshot.climb?.uuid?.let(boardLayerManager::layerForClimb)?.slot
            ?: snapshot.selectedBoardLayerSlot

    private suspend fun buildQuantumLayer(snapshot: ClimbDetailState, slot: Int): BoardClimbLayer? {
        val climb = snapshot.climb ?: return null
        val existing = boardLayerManager.state.value.layers.firstOrNull { it.slot == slot }
        val colorsUsedElsewhere = boardLayerManager.state.value.layers
            .filterNot { it.slot == slot }.mapTo(mutableSetOf()) { it.color } +
            boardLayerManager.state.value.externalLayers.map { it.color }
        val requested = snapshot.selectedBoardLayerColor
            ?: existing?.color
            ?: boardLayerManager.defaultColor(slot)
        val color = requested.takeIf {
            it in BoardLayerManager.LAYER_COLORS && it !in colorsUsedElsewhere
        }
            ?: BoardLayerManager.LAYER_COLORS.firstOrNull { it !in colorsUsedElsewhere }
            ?: run {
                state.update { it.copy(ble = it.ble.copy(error = R.string.board_layer_error_color_taken)) }
                return null
            }
        val routeUuid = withContext(ioDispatcher) {
            boardRepository.getQuantumExternalRouteUuid(climb.uuid)
        } ?: climb.uuid
        return BoardClimbLayer(
            slot = slot,
            climbUuid = climb.uuid,
            routeUuid = routeUuid,
            climbName = climb.name,
            angle = snapshot.angle,
            userUuid = boardLayerManager.identityForSlot(slot),
            color = color,
            holds = snapshot.holds,
            status = BoardLayerStatus.PREVIEW,
        )
    }

    private fun launchQuantumLayerSend(slots: List<Int>) {
        if (isBoardOwnedBySession() || state.value.ble.isSending) return
        if (bleConnection.connectionState.value != ConnectionState.CONNECTED) return
        val connectedBrand = bleConnection.connectedBoardBrand.value
        if (connectedBrand != null && connectedBrand != BoardBrand.QUANTUM) {
            state.update { it.copy(
                ble = it.ble.copy(error = R.string.board_send_error_connected_board_mismatch),
            ) }
            return
        }
        sendJob?.cancel()
        sendJob = scope.launch { sendQuantumLayers(slots) }
    }

    private suspend fun sendQuantumLayers(slots: List<Int>) {
        if (BoardBrand.fromWire(userPreferences.boardBrand.first()) != BoardBrand.QUANTUM) {
            state.update { it.copy(
                ble = it.ble.copy(error = R.string.board_send_error_brand_mismatch),
            ) }
            return
        }
        state.update {
            it.copy(
                ble = it.ble.copy(isSending = true, success = false, error = null, warning = null),
                nearby = it.nearby.copy(debugInfo = "sending Quantum layers ${slots.map { slot -> slot + 1 }}"),
            )
        }
        var success = true
        for (slot in slots) {
            val layer = boardLayerManager.state.value.layers.firstOrNull { it.slot == slot }
            if (layer == null || !sendQuantumLayer(layer)) {
                success = false
                break
            }
        }
        val failure = runCatching {
            bleConnection.quantumControllerState.value.lastFailure
        }.getOrNull()
        state.update {
            it.copy(
                ble = it.ble.copy(
                    isSending = false,
                    success = success,
                    error = when {
                        success -> null
                        failure != null -> quantumFailureResource(failure)
                        else -> it.ble.error ?: R.string.board_send_error_send_failed
                    },
                ),
                nearby = it.nearby.copy(debugInfo = "Quantum layers sent=$success"),
            )
        }
    }

    private suspend fun sendQuantumLayer(layer: BoardClimbLayer): Boolean {
        if (!boardLayerManager.hasControllerCapacityFor(layer.slot)) {
            boardLayerManager.failProjection(layer.slot)
            state.update { it.copy(ble = it.ble.copy(error = R.string.board_layer_error_board_full)) }
            return false
        }
        val activeOwnedLayers = boardLayerManager.state.value.layers.filter {
            it.slot != layer.slot && it.confirmedRouteUuid != null
        }
        if (BoardLayerConflictPolicy.sharedHoldCount(layer.holds, activeOwnedLayers, null) > 0) {
            boardLayerManager.failProjection(layer.slot)
            state.update { it.copy(ble = it.ble.copy(error = R.string.board_layer_error_shared_hold)) }
            return false
        }
        val occupiedColors = activeOwnedLayers.mapTo(mutableSetOf()) {
            it.confirmedColor ?: it.color
        } + boardLayerManager.state.value.externalLayers.map { it.color }
        if (layer.color in occupiedColors) {
            boardLayerManager.failProjection(layer.slot)
            state.update { it.copy(ble = it.ble.copy(error = R.string.board_layer_error_color_taken)) }
            return false
        }
        val productSizeId = userPreferences.boardProductSizeId.first()
        val placementToLed = withContext(ioDispatcher) {
            boardRepository.getPlacementLedMap(productSizeId, BoardBrand.QUANTUM.wireValue)
        }
        if (placementToLed.isEmpty() || layer.holds.none { it.placementId in placementToLed }) {
            boardLayerManager.failProjection(layer.slot)
            state.update { it.copy(ble = it.ble.copy(error = R.string.board_send_error_no_led_data)) }
            return false
        }
        boardLayerManager.beginProjection(layer.slot)
        val written = boardCellWriteGateway.project(
            BoardProjection(
                layer.climbUuid,
                layer.angle,
                BoardProjectionPolicy.projectionSurvivesDisconnect(BoardBrand.QUANTUM),
            ),
        ) {
            bleConnection.sendClimb(
                holds = layer.holds,
                placementToLed = placementToLed,
                roleColors = emptyMap(),
                routeId = layer.routeUuid,
                quantumUserId = layer.userUuid,
                quantumColor = layer.color,
            )
        }
        if (written) boardLayerManager.confirmProjection(layer.slot)
        else boardLayerManager.failProjection(layer.slot)
        if (written && state.value.climb?.uuid == layer.climbUuid) {
            recordSentToHistory(state.value)
        }
        return written
    }

    /**
     * MoonBoard branch of [sendToBoard] (FEAT-027). Gates on a non-blank
     * `frames` string instead of an Aurora `holds` list, skips the LED-map
     * load entirely, and pushes the climb via [BoardBleConnection.sendMoonBoardClimb].
     * Drives the same [BoardSendState] connect/send UI state machine so the
     * detail screen's send-status row behaves identically across brands.
     */
    private fun sendMoonBoardToBoard() {
        val s = state.value
        val climb = s.climb ?: return
        val frames = climb.frames
        if (frames.isBlank() || s.ble.connectionState != ConnectionState.CONNECTED) {
            state.update { it.copy(nearby = it.nearby.copy(
                debugInfo = "skip: frames=${frames.length} conn=${s.ble.connectionState}"
            )) }
            return
        }
        if (s.ble.isSending) {
            state.update { it.copy(nearby = it.nearby.copy(debugInfo = "skip: already sending")) }
            return
        }

        state.update { it.copy(
            ble = it.ble.copy(
                isSending = true,
                success = false,
                error = null,
                warning = null,
            ),
            nearby = it.nearby.copy(debugInfo = "sending (moonboard)...")
        ) }
        Log.i(TAG, "sendMoonBoardToBoard: start frames=${frames.length}")
        sendJob = scope.launch {
            try {
                // Board-match guard, part 1: the CONNECTED board must be a
                // MoonBoard. Switching the active board in Settings never
                // disconnects, so the pref check below alone would let a
                // MoonBoard ASCII frame go to a still-connected Aurora board.
                val connectedBrand = bleConnection.connectedBoardBrand.value
                if (connectedBrand != null && connectedBrand != BoardBrand.MOONBOARD) {
                    state.update { it.copy(
                        ble = it.ble.copy(isSending = false, error = R.string.board_send_error_connected_board_mismatch),
                        nearby = it.nearby.copy(debugInfo = "connected board not moonboard")
                    ) }
                    return@launch
                }
                // Board-match guard, part 2: a MoonBoard climb can only go to a
                // connected MoonBoard of the same variant. A cross-board list
                // / deep link can surface a MoonBoard climb while a Kilter (or
                // a different MoonBoard variant) is configured; sending it
                // would light wrong/garbled holds. Refuse with a clear message.
                val activeBrand = userPreferences.boardBrand.first()
                if (BoardBrand.fromWire(activeBrand) != BoardBrand.MOONBOARD) {
                    state.update { it.copy(
                        ble = it.ble.copy(isSending = false, error = R.string.board_send_error_active_not_moonboard),
                        nearby = it.nearby.copy(debugInfo = "active board not moonboard")
                    ) }
                    return@launch
                }
                val activeLayout = userPreferences.boardLayoutId.first().toLong()
                if (climb.layoutId != activeLayout) {
                    state.update { it.copy(
                        ble = it.ble.copy(isSending = false, error = R.string.board_send_error_moonboard_variant_mismatch),
                        nearby = it.nearby.copy(debugInfo = "moonboard variant mismatch")
                    ) }
                    return@launch
                }
                // Resolve the MoonBoard variant from the CLIMB being sent,
                // not the active-board pref — the encoder's per-column-height
                // serpentine differs (18 for standard 11×18 boards, 12 for
                // Mini 2020), and a list / deep-link can surface a climb of a
                // different variant than the one currently configured. Using
                // the climb's own layout_id guarantees the wire frame matches
                // the holds we're rendering. A stale/corrupt layout id falls
                // back to MOONBOARD_2016 in the variant lookup below.
                val layoutId = climb.layoutId
                val variant = com.cruxcoach.domain.board.MoonBoardVariant
                    .fromLayoutId(layoutId)
                    ?: com.cruxcoach.domain.board.MoonBoardVariant.MOONBOARD_2016
                val success = boardCellWriteGateway.project(
                    BoardProjection(climb.uuid, s.angle,
                        BoardProjectionPolicy.projectionSurvivesDisconnect(climb.brand))) {
                        bleConnection.sendMoonBoardClimb(
                            frames,
                            variant,
                            userPreferences.moonBoardLedMode.first(),
                        )
                    }
                Log.i(TAG, "sendMoonBoardToBoard: writes done success=$success variant=$variant")
                state.update { it.copy(
                    ble = it.ble.copy(
                        isSending = false,
                        success = success,
                        error = if (!success) R.string.board_send_error_send_failed else null,
                    ),
                    nearby = it.nearby.copy(debugInfo = "sent ok=$success")
                ) }
                if (success) {
                    recordSentToHistory(s)
                    val result = climbAdvertiser.advertiseClimb(
                        climbUuid = climb.uuid,
                        angle = s.angle,
                        sharingEnabled = isSharingEnabled(),
                        projectionSurvivesDisconnect =
                            BoardProjectionPolicy.projectionSurvivesDisconnect(climb.brand),
                    )
                    state.update { it.copy(
                        nearby = it.nearby.copy(debugInfo = "adv: $result")
                    ) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sendMoonBoardToBoard failed", e)
                state.update { it.copy(
                    ble = it.ble.copy(isSending = false, error = R.string.board_send_error_generic),
                    nearby = it.nearby.copy(debugInfo = "exception: ${e.message?.take(50)}")
                ) }
            }
        }
    }

    /** Whether the BLE board is currently connected. */
    fun isConnected(): Boolean =
        bleConnection.connectionState.value == ConnectionState.CONNECTED ||
            com.cruxcoach.android.boardcell.BoardCellManager.current?.canSendViaMesh() == true

    fun isConnectedViaMesh(): Boolean =
        com.cruxcoach.android.boardcell.BoardCellManager.current?.canSendViaMesh() == true

    /**
     * Whether a send from this screen would actually reach the wall.
     *
     * [sendToBoard] drops a request while a session queue owns the board, and
     * a caller that only checked [isConnected] got a silent no-op: route
     * playback ran its whole animation, frame counter and all, while every
     * frame was discarded. Surfaces that offer a send ask this, not the
     * connection alone.
     */
    fun canSendToBoard(): Boolean = isConnected() && !isBoardOwnedBySession()

    fun removeBoardLayer(slot: Int) {
        if (isBoardOwnedBySession()) return
        val layer = boardLayerManager.state.value.layers.firstOrNull { it.slot == slot } ?: return
        if (layer.confirmedRouteUuid == null) {
            boardLayerManager.removePreview(slot)
            return
        }
        if (bleConnection.connectedBoardBrand.value != BoardBrand.QUANTUM) return
        sendJob?.cancel()
        state.update { it.copy(ble = it.ble.copy(isSending = true, success = false, error = null)) }
        sendJob = scope.launch {
            val success = runCatching { bleConnection.removeQuantumLayer(layer.userUuid) }.getOrDefault(false)
            if (success) boardLayerManager.removeOwned(slot) else boardLayerManager.failProjection(slot)
            val failure = runCatching {
                bleConnection.quantumControllerState.value.lastFailure
            }.getOrNull()
            state.update { current -> current.copy(
                ble = current.ble.copy(
                    isSending = false,
                    success = success,
                    error = if (success) null else quantumFailureResource(failure),
                ),
            ) }
        }
    }

    private fun isBoardOwnedBySession(): Boolean =
        sessionQueueManager.state.value.isActive ||
            sessionQueueManager.state.value.isConnecting

    @androidx.annotation.StringRes
    private fun quantumFailureResource(failure: QuantumCommandFailure?): Int = when (failure) {
        QuantumCommandFailure.ROUTE_IN_USE -> R.string.board_layer_error_route_in_use
        QuantumCommandFailure.SPOT_UNAVAILABLE -> R.string.board_layer_error_shared_hold
        QuantumCommandFailure.COLOR_TAKEN -> R.string.board_layer_error_color_taken
        QuantumCommandFailure.USER_ID_IN_USE -> R.string.board_layer_error_user_in_use
        QuantumCommandFailure.BOARD_FULL -> R.string.board_layer_error_board_full
        QuantumCommandFailure.ROUTESETTER_MODE -> R.string.board_layer_error_routesetter
        QuantumCommandFailure.DIODE_MISSING -> R.string.board_layer_error_diode_missing
        QuantumCommandFailure.ACK_TIMEOUT -> R.string.board_layer_error_timeout
        QuantumCommandFailure.REFUSED, null -> R.string.board_send_error_send_failed
    }

    private companion object {
        const val TAG = "BoardSendController"
    }
}
