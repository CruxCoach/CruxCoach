package com.cruxcoach.android.ui.board

import android.util.Log
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardClimbLayer
import com.cruxcoach.android.ble.BoardLayerBoardIdentity
import com.cruxcoach.android.ble.BoardLayerControllerRouteKey
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerPlanKey
import com.cruxcoach.android.ble.BoardLayerRouteDetails
import com.cruxcoach.android.ble.BoardLayerStatus
import com.cruxcoach.android.ble.BoardLayerConflictPolicy
import com.cruxcoach.android.ble.QuantumCommandFailure
import com.cruxcoach.android.ble.BoardProjectionPolicy
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.PhysicalBoardIdentity
import com.cruxcoach.android.ble.reservedLayerColors
import com.cruxcoach.android.ble.hasCompleteQuantumLedMapping
import com.cruxcoach.android.ble.hasConfirmableQuantumDiodeCount
import com.cruxcoach.android.ble.matchesQuantumPlayers
import com.cruxcoach.android.ble.planKey
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.data.SessionVisibility
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.ui.settings.BoardConfigurationMismatch
import com.cruxcoach.android.ui.settings.BoardMismatchKind
import com.cruxcoach.android.ui.settings.BoardSendIdentity
import com.cruxcoach.android.ui.settings.boardSizeMismatch
import com.cruxcoach.android.ui.settings.resolveBoardConfigurationMismatch
import com.cruxcoach.util.DateTimeUtil
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.BoardClimbParser
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
 * Detail-layer controls may coexist with the playlist only while the playlist
 * is wholly local. Once a session is advertised, requested to be advertised,
 * joined, or still connecting, its queue exclusively owns board mutations.
 */
internal fun localQuantumLayerManagementAllowed(session: SessionQueueState): Boolean =
    !session.isConnecting && (
        !session.isActive ||
            (session.isPlaylist &&
                session.role == SessionRole.HOST &&
                session.visibility == SessionVisibility.LOCAL_ONLY &&
                session.visibilityRequested == SessionVisibility.LOCAL_ONLY)
        )

/** A private playlist is local transport state, not a shared-session owner.
 * Its explicit detail lamp may temporarily override a single-projection wall;
 * automatic detail sends must still leave playlist pacing in control. */
internal fun privatePlaylistDetailLightAllowed(session: SessionQueueState): Boolean =
    session.isActive &&
        !session.isConnecting &&
        session.isPlaylist &&
        session.role == SessionRole.HOST &&
        session.visibility == SessionVisibility.LOCAL_ONLY &&
        session.visibilityRequested == SessionVisibility.LOCAL_ONLY

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var sendJob: Job? = null

    /** Cancel any in-flight send (used when switching climbs). */
    fun cancelSend() {
        sendJob?.cancel()
    }

    /**
     * Which climb variant a send belongs to.
     *
     * A send outlives the screen state it started from: the job suspends on
     * preference reads, an LED-map query and the BLE write itself, and an
     * angle change in between replaces the climb underneath it. Cancelling
     * covers the suspended case — a cancelled coroutine never resumes past its
     * suspension point. It does not cover the rest of the window, where the
     * job is already past its last suspension and would report the previous
     * variant's result as the new one's: "sent", a layer selection, a history
     * entry, all for a climb the user has already navigated away from.
     */
    private data class SendVariant(
        val climbUuid: String?,
        val angle: Int,
        /**
         * The holds actually being sent, not a flag that stands in for them.
         *
         * Mirroring is one way the hold set changes under a running send;
         * stepping a route frame is another, and neither touches the climb or
         * the angle. Identifying the variant by what goes on the wall covers
         * every way it can change, including ones nobody has added yet.
         */
        val holds: List<BoardHold>,
    )

    private fun ClimbDetailState.variant() = SendVariant(climb?.uuid, angle, holds)

    private suspend fun sendIdentity(snapshot: ClimbDetailState): BoardSendIdentity? {
        val climb = snapshot.climb ?: return null
        val activeBrand = preferenceEvidence { userPreferences.boardBrand.first() }
            ?.let(BoardBrand::fromWire)
        return BoardSendIdentity(
            climbBrand = climb.brand,
            climbLayoutId = climb.layoutId,
            activeBrand = activeBrand,
            activeLayoutId = preferenceEvidence { userPreferences.boardLayoutId.first().toLong() },
            activeProductSizeId = preferenceEvidence { userPreferences.boardProductSizeId.first() },
            connectedBrand = runCatching { bleConnection.connectedBoardBrand.value }.getOrNull(),
            connectedQuantumModel = runCatching { bleConnection.connectedQuantumModel.value }.getOrNull(),
        )
    }

    private suspend fun <T> preferenceEvidence(read: suspend () -> T): T? = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun mismatchError(mismatch: BoardConfigurationMismatch): Int = when (mismatch.kind) {
        BoardMismatchKind.CONNECTED_BRAND,
        BoardMismatchKind.CONNECTED_MODEL -> R.string.board_send_error_connected_board_mismatch
        BoardMismatchKind.ACTIVE_SIZE -> R.string.board_send_error_climb_off_board
        BoardMismatchKind.ACTIVE_BRAND,
        BoardMismatchKind.ACTIVE_LAYOUT -> R.string.board_send_error_brand_mismatch
    }

    private fun updateMismatch(fence: SendFence, mismatch: BoardConfigurationMismatch) {
        updateForVariant(fence) {
            it.copy(
                ble = it.ble.copy(
                    isSending = false,
                    error = mismatchError(mismatch),
                    mismatch = mismatch,
                ),
                nearby = it.nearby.copy(debugInfo = "board configuration mismatch: ${mismatch.kind}"),
            )
        }
    }

    /** One send, and the variant it was started for. */
    private data class SendFence(val id: Long, val variant: SendVariant)

    private var sendSequence = 0L

    private fun beginSendFence(snapshot: ClimbDetailState = state.value): SendFence {
        sendSequence += 1
        return SendFence(sendSequence, snapshot.variant())
    }

    /**
     * A state write from a send that may already have been superseded.
     *
     * Two questions, deliberately answered apart:
     *
     *  - the **claim** — "sent", an error, a layer selection, a history entry —
     *    belongs to the variant it was made for, and is dropped once the screen
     *    has moved on. That is the whole point of the fence.
     *  - the **spinner** belongs to this controller, not to any variant. If a
     *    dropped claim also dropped the `isSending = false` that came with it,
     *    the screen would sit busy forever with nothing behind it. So it is
     *    cleared whenever this is still the newest send; when a newer one has
     *    already started, that one owns the spinner and this write leaves it be.
     */
    private fun updateForVariant(
        fence: SendFence,
        transform: (ClimbDetailState) -> ClimbDetailState,
    ) {
        state.update { current ->
            when {
                current.variant() == fence.variant -> transform(current)
                fence.id == sendSequence -> current.copy(ble = current.ble.copy(isSending = false))
                else -> current
            }
        }
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

    fun sendToBoard(
        automaticLayer: Boolean = false,
        userInitiated: Boolean = false,
    ) {
        // When a session queue is active, the queue controls what's on the board.
        // The sole exception is Quantum's private local playlist: its stable
        // layer rack is the playlist's own direct-controller state. Do not
        // relax this for other brands or for any joinable session.
        val localQuantumLayer = state.value.climb?.brand == BoardBrand.QUANTUM &&
            localQuantumLayerManagementAllowed(sessionQueueManager.state.value)
        val privatePlaylistOverride = userInitiated &&
            privatePlaylistDetailLightAllowed(sessionQueueManager.state.value)
        if (isBoardOwnedBySession() && !localQuantumLayer && !privatePlaylistOverride) {
            Log.d(TAG, "sendToBoard: suppressed (session queue active)")
            return
        }
        // FEAT-027: a MoonBoard climb sends an ASCII `frames` payload — it has
        // no Aurora `holds` list and no LED map. Gate on a non-blank frames
        // string and route through the dedicated MoonBoard transport.
        if (state.value.climb?.brand == BoardBrand.MOONBOARD) {
            sendMoonBoardToBoard(privatePlaylistOverride)
            return
        }
        if (state.value.climb?.brand == BoardBrand.QUANTUM) {
            // Quantum never follows page selection automatically. The detail
            // lamp is an explicit request: assign the current climb to the
            // selected local layer, then transmit exactly that identity.
            sendCurrentQuantumClimb(automaticLayer)
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
                mismatch = null,
            ),
            nearby = it.nearby.copy(debugInfo = "sending...")
        ) }
        Log.i(TAG, "sendToBoard: start frames=${s.holds.size}")
        val variant = beginSendFence(s)
        sendJob = scope.launch {
            try {
                // Board-match guard, part 1: the CONNECTED board's brand wins.
                // Switching the active board in Settings never disconnects, so
                // the pref can diverge from the board still on the link — the
                // pref-only check below would happily send a Tension climb to
                // a still-connected Kilter board, lighting the wrong holds.
                val identity = sendIdentity(s) ?: return@launch
                resolveBoardConfigurationMismatch(identity)?.let {
                    updateMismatch(variant, it)
                    return@launch
                }
                // Board-match guard, part 2: you can only send a climb to a
                // board of the same family as the ACTIVE board. A climb opened
                // from a mixed list or deep link can differ from the active
                // board; sending it would light the wrong holds. (This Kilter
                // branch is only reached for non-MoonBoard climbs, so the
                // check catches the "active board is a MoonBoard" mismatch.)
                val activeBrand = identity.activeBrand?.wireValue
                    ?: userPreferences.boardBrand.first()
                updateForVariant(variant) { it.copy(nearby = it.nearby.copy(debugInfo = "loading LED map...")) }
                val productSizeId = userPreferences.boardProductSizeId.first()
                val placementToLed = withContext(ioDispatcher) {
                    // FEAT-031: scope the LED map to the active board's brand so an
                    // Aurora board (Tension etc.) lights its OWN holds, not Kilter's
                    // same-numbered product_size rows. activeBrand == climb.brand here
                    // (guarded above), so it is the connected board's brand.
                    boardRepository.getPlacementLedMap(productSizeId, activeBrand)
                }
                if (placementToLed.isEmpty()) {
                    updateForVariant(variant) { it.copy(
                        ble = it.ble.copy(isSending = false, error = R.string.board_send_error_no_led_data),
                        nearby = it.nearby.copy(debugInfo = "no LED data")
                    ) }
                    return@launch
                }
                updateForVariant(variant) { it.copy(nearby = it.nearby.copy(debugInfo = "BLE sending...")) }
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
                    updateMismatch(variant, boardSizeMismatch(identity))
                    return@launch
                }
                val success = bleConnection.sendClimb(
                    s.holds,
                    placementToLed,
                    roleColorMap,
                    expectedBrand = brand,
                )
                Log.i(TAG, "sendToBoard: writes done success=$success unmapped=$unmappedHolds")
                updateForVariant(variant) { it.copy(
                    ble = it.ble.copy(
                        isSending = false,
                        success = success,
                        error = if (!success) R.string.board_send_error_send_failed else null,
                        warning = if (success && unmappedHolds > 0) R.string.board_send_warning_holds_not_lit else null,
                    ),
                    nearby = it.nearby.copy(debugInfo = "sent ok=$success unmapped=$unmappedHolds")
                ) }
                if (success) {
                    recordSentToHistory(s)
                    if (privatePlaylistOverride) {
                        sessionQueueManager.markExternalBoardWrite(s.climb?.uuid, s.angle)
                    }
                }
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
                updateForVariant(variant) { it.copy(nearby = it.nearby.copy(debugInfo = debugMsg)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sendToBoard failed", e)
                updateForVariant(variant) { it.copy(
                    ble = it.ble.copy(isSending = false, error = R.string.board_send_error_generic),
                    nearby = it.nearby.copy(debugInfo = "exception: ${e.message?.take(50)}")
                ) }
            }
        }
    }

    /** Store the current Quantum climb in the selected local slot. No BLE
     * command is sent; PREVIEW is intentionally a useful offline state. */
    fun assignCurrentToBoardLayer() {
        if (!localQuantumLayerManagementAllowed(sessionQueueManager.state.value)) return
        val snapshot = state.value
        if (snapshot.climb?.brand != BoardBrand.QUANTUM || snapshot.holds.isEmpty()) return
        val slot = selectedSlotFor(snapshot) ?: return
        val slotBefore = boardLayerManager.state.value.layers.firstOrNull { it.slot == slot }
        val expectedCurrent = slotBefore?.planKey()
        val variant = beginSendFence(snapshot)
        scope.launch {
            val layer = buildQuantumLayer(snapshot, slot, slotBefore) ?: return@launch
            if (!boardLayerManager.assignPreviewIfCurrent(layer, expectedCurrent)) {
                updateForVariant(variant) { current -> current.copy(
                    ble = current.ble.copy(error = R.string.board_layer_error_state_unavailable),
                ) }
                return@launch
            }
            updateForVariant(variant) {
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

    /**
     * The rack is staged for the board that is on the link right now.
     *
     * The binding in [BoardClimbDetailViewModel] clears the rack when the board
     * changes, so this is the window it cannot cover: a swap that lands between
     * the tap and the write. A layer is a diode plan for one controller, and
     * sending it to another one lights holds nobody chose.
     */
    private suspend fun layersBelongToConnectedBoard(): Boolean {
        val board = bleConnection.connectedBoardDescriptor.value ?: return false
        if (board.boardBrand != BoardBrand.QUANTUM) return false
        val physical = runCatching { PhysicalBoardIdentity.resolve(board) }.getOrNull() ?: return false
        val model = bleConnection.connectedQuantumModel.value ?: return false
        return boardLayerManager.isBoundTo(
            BoardLayerBoardIdentity(physical.value, model.productSizeId)
        )
    }

    /** Send all four local assignments sequentially. A full capacity
     * preflight prevents a half-applied rack when foreign users leave fewer
     * physical controller places than the local preview needs. */
    fun sendAllBoardLayers() {
        val slots = boardLayerManager.state.value.layers.sortedBy { it.slot }.map { it.slot }
        if (slots.isEmpty()) return
        // Capacity shown in the rack can already be stale because another app
        // may have joined or left. The send path refreshes authoritatively and
        // performs an all-plan preflight before its first mutation.
        launchQuantumLayerSend(slots)
    }

    private fun sendCurrentQuantumClimb(automaticLayer: Boolean) {
        if (!localQuantumLayerManagementAllowed(sessionQueueManager.state.value)) return
        val snapshot = state.value
        if (snapshot.holds.isEmpty() || snapshot.ble.connectionState != ConnectionState.CONNECTED) return
        val existingSlot = snapshot.climb?.uuid
            ?.let(boardLayerManager::layerForClimb)
            ?.slot
        val explicitlySelectedSlot = snapshot.selectedBoardLayerSlot
        if (!automaticLayer && existingSlot != null && explicitlySelectedSlot != null &&
            explicitlySelectedSlot != existingSlot
        ) {
            state.update {
                it.copy(ble = it.ble.copy(error = R.string.quantum_layer_already_assigned_error))
            }
            return
        }
        val resolvedSlot = existingSlot
            ?: if (automaticLayer) automaticQuantumSlot(snapshot) else explicitlySelectedSlot
        if (resolvedSlot == null) {
            if (!automaticLayer) {
                state.update { it.copy(ble = it.ble.copy(error = R.string.board_layer_error_all_assigned)) }
            }
            return
        }
        val slot: Int = resolvedSlot
        val slotBefore = boardLayerManager.state.value.layers.firstOrNull { it.slot == slot }
        val expectedCurrent = slotBefore?.planKey()
        sendJob?.cancel()
        val variant = beginSendFence(snapshot)
        sendJob = scope.launch {
            val identity = sendIdentity(snapshot) ?: return@launch
            resolveBoardConfigurationMismatch(identity)?.let {
                updateMismatch(variant, it)
                return@launch
            }
            val connectedBrand = bleConnection.connectedBoardBrand.value
            if (connectedBrand != null && connectedBrand != BoardBrand.QUANTUM) {
                updateForVariant(variant) { it.copy(
                    ble = it.ble.copy(error = R.string.board_send_error_connected_board_mismatch),
                ) }
                return@launch
            }
            if (BoardBrand.fromWire(userPreferences.boardBrand.first()) != BoardBrand.QUANTUM) {
                updateForVariant(variant) { it.copy(
                    ble = it.ble.copy(error = R.string.board_send_error_brand_mismatch),
                ) }
                return@launch
            }
            val layer = buildQuantumLayer(snapshot, slot, slotBefore) ?: return@launch
            if (!boardLayerManager.assignPreviewIfCurrent(layer, expectedCurrent)) {
                updateForVariant(variant) { current -> current.copy(
                    ble = current.ble.copy(error = R.string.board_layer_error_state_unavailable),
                ) }
                return@launch
            }
            updateForVariant(variant) {
                it.copy(
                    selectedBoardLayerSlot = slot,
                    selectedBoardLayerColor = layer.color,
                )
            }
            sendQuantumLayers(listOf(layer.planKey()), variant)
        }
    }

    /**
     * Resolve a slot for the detail screen's primary "light" action.
     *
     * Automatic allocation is deliberately conservative: previews reserve a
     * local slot, controller occupancy must leave room for a new identity, and
     * every physically active route must be resolved well enough to prove
     * there is no hold conflict. This is the same one-answer policy rendered
     * by the rack, so the lamp and its visible recommendation cannot diverge.
     */
    private fun automaticQuantumSlot(snapshot: ClimbDetailState): Int? {
        val suggestion = QuantumLayerUiPolicy.summarize(
            state = boardLayerManager.state.value,
            currentClimbUuid = snapshot.climb?.uuid,
            currentPlacements = snapshot.holds.mapTo(mutableSetOf(), BoardHold::placementId),
        )
        val slot = suggestion.suggestedSlot
        val color = suggestion.suggestedColor
        if (slot == null || color == null) {
            val error = when (suggestion.suggestionBlock) {
                QuantumLayerSuggestionBlock.NO_HOLDS -> R.string.board_send_error_no_led_data
                QuantumLayerSuggestionBlock.MULTI_FRAME_UNVERIFIED ->
                    R.string.board_layer_error_multi_frame_unverified
                QuantumLayerSuggestionBlock.UNKNOWN_LAYER -> R.string.board_layer_error_external_unknown
                QuantumLayerSuggestionBlock.HOLD_CONFLICT -> R.string.board_layer_error_shared_hold
                QuantumLayerSuggestionBlock.BOARD_FULL -> R.string.board_layer_error_board_full
                QuantumLayerSuggestionBlock.NO_COLOR -> R.string.board_layer_error_color_taken
                QuantumLayerSuggestionBlock.NO_SLOT, null -> R.string.board_layer_error_all_assigned
            }
            state.update { it.copy(ble = it.ble.copy(error = error)) }
            return null
        }
        state.update {
            it.copy(
                selectedBoardLayerSlot = slot,
                selectedBoardLayerColor = color,
            )
        }
        return slot
    }

    private fun selectedSlotFor(snapshot: ClimbDetailState): Int? {
        val existing = snapshot.climb?.uuid?.let(boardLayerManager::layerForClimb)
        val selected = snapshot.selectedBoardLayerSlot
        if (existing != null && selected != null && selected != existing.slot) {
            // A controller route cannot safely jump to another stable user UUID:
            // when it is live that would require an explicit scoped removal, and
            // even a local duplicate would make the rack label disagree with the
            // identity actually updated. Keep the old layer until the user
            // removes it deliberately.
            state.update {
                it.copy(
                    ble = it.ble.copy(error = R.string.quantum_layer_already_assigned_error),
                )
            }
            return null
        }
        return selected ?: existing?.slot ?: run {
            state.update {
                it.copy(ble = it.ble.copy(error = R.string.board_layer_error_all_assigned))
            }
            null
        }
    }

    private suspend fun buildQuantumLayer(
        snapshot: ClimbDetailState,
        slot: Int,
        existing: BoardClimbLayer?,
    ): BoardClimbLayer? {
        val climb = snapshot.climb ?: return null
        val colorsUsedElsewhere = boardLayerManager.state.value.reservedLayerColors(slot)
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
        if (!localQuantumLayerManagementAllowed(sessionQueueManager.state.value) ||
            state.value.ble.isSending
        ) return
        if (bleConnection.connectionState.value != ConnectionState.CONNECTED) return
        val connectedBrand = bleConnection.connectedBoardBrand.value
        if (connectedBrand != null && connectedBrand != BoardBrand.QUANTUM) {
            state.update { it.copy(
                ble = it.ble.copy(error = R.string.board_send_error_connected_board_mismatch),
            ) }
            return
        }
        val requestedPlans = slots.map { slot ->
            boardLayerManager.state.value.layers.firstOrNull { it.slot == slot }?.planKey()
                ?: return
        }
        sendJob?.cancel()
        val variant = beginSendFence()
        sendJob = scope.launch { sendQuantumLayers(requestedPlans, variant) }
    }

    private suspend fun sendQuantumLayers(
        requestedPlans: List<BoardLayerPlanKey>,
        variant: SendFence,
    ) {
        val slots = requestedPlans.map(BoardLayerPlanKey::slot)
        try {
        val expectedBoard = boardLayerManager.state.value.board
        if (expectedBoard == null || !layersBelongToConnectedBoard()) {
            updateForVariant(variant) { it.copy(
                ble = it.ble.copy(
                    isSending = false,
                    error = R.string.board_layer_error_other_board,
                ),
            ) }
            return
        }
        if (BoardBrand.fromWire(userPreferences.boardBrand.first()) != BoardBrand.QUANTUM) {
            updateForVariant(variant) { it.copy(
                ble = it.ble.copy(error = R.string.board_send_error_brand_mismatch),
            ) }
            return
        }
        // Revalidate immediately before allocating controller capacity. A
        // second eWalls client can change the four-player snapshot while this
        // detail page is open; the snapshot from connection time is not a safe
        // send precondition. Unknown foreign routes remain occupied and block
        // projection because their holds cannot be proven non-overlapping.
        if (!refreshAndHydrateQuantumState()) {
            updateForVariant(variant) { it.copy(
                ble = it.ble.copy(
                    isSending = false,
                    error = R.string.board_layer_error_state_unavailable,
                ),
            ) }
            return
        }
        quantumLayerPreflight(slots)?.let { error ->
            requestedPlans.forEach(boardLayerManager::failProjection)
            updateForVariant(variant) { it.copy(
                ble = it.ble.copy(isSending = false, error = error),
            ) }
            return
        }
        updateForVariant(variant) {
            it.copy(
                ble = it.ble.copy(isSending = true, success = false, error = null, warning = null),
                nearby = it.nearby.copy(debugInfo = "sending Quantum layers ${slots.map { slot -> slot + 1 }}"),
            )
        }
        var success = true
        for ((index, expectedPlan) in requestedPlans.withIndex()) {
            val slot = expectedPlan.slot
            // Sequential projection cannot be made transactional by the
            // controller protocol. Close the coexistence race as far as it
            // permits: immediately before every mutation, obtain fresh
            // authoritative truth and validate every remaining plan against
            // it. A foreign write between slots therefore stops us before the
            // next command rather than being overwritten or guessed around.
            if (!refreshAndHydrateQuantumState()) {
                updateForVariant(variant) { it.copy(
                    ble = it.ble.copy(error = R.string.board_layer_error_state_unavailable),
                ) }
                success = false
                break
            }
            val remainingPlans = requestedPlans.drop(index)
            val remainingSlots = remainingPlans.map(BoardLayerPlanKey::slot)
            val currentError = quantumLayerPreflight(remainingSlots)
            if (currentError != null) {
                remainingPlans.forEach(boardLayerManager::failProjection)
                updateForVariant(variant) { it.copy(ble = it.ble.copy(error = currentError)) }
                success = false
                break
            }
            val layer = boardLayerManager.state.value.layers.firstOrNull {
                it.planKey() == expectedPlan
            }
            if (layer == null || !sendQuantumLayer(layer, expectedPlan, variant, expectedBoard)) {
                success = false
                break
            }
        }
        val failure = runCatching {
            bleConnection.quantumControllerState.value.lastFailure
        }.getOrNull()
        updateForVariant(variant) {
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Quantum layer send failed", error)
            updateForVariant(variant) { current ->
                current.copy(
                    ble = current.ble.copy(
                        isSending = false,
                        success = false,
                        error = R.string.board_send_error_generic,
                    ),
                )
            }
        } finally {
            finishQuantumJob(variant, requestedPlans)
        }
    }

    /** No repository/parser/encoder exception may strand a rack row or spinner. */
    private fun finishQuantumJob(variant: SendFence, plans: List<BoardLayerPlanKey>) {
        plans.forEach { plan ->
            if (boardLayerManager.state.value.layers.firstOrNull { it.planKey() == plan }?.status ==
                BoardLayerStatus.SENDING
            ) boardLayerManager.failProjection(plan)
        }
        // Do not let an older cancelled job lower a newer send's spinner when
        // both sends happen to belong to the same climb/angle/hold variant.
        if (variant.id == sendSequence) {
            state.update { current -> current.copy(ble = current.ble.copy(isSending = false)) }
        }
    }

    private suspend fun sendQuantumLayer(
        layer: BoardClimbLayer,
        expectedPlan: BoardLayerPlanKey,
        variant: SendFence,
        expectedBoard: BoardLayerBoardIdentity,
    ): Boolean {
        val expectedPlayers = bleConnection.quantumControllerState.value.players
        if (!boardLayerManager.state.value.matchesQuantumPlayers(expectedPlayers)) {
            boardLayerManager.failProjection(expectedPlan)
            updateForVariant(variant) { it.copy(
                ble = it.ble.copy(error = R.string.board_layer_error_state_unavailable),
            ) }
            return false
        }
        if (!boardLayerManager.hasControllerCapacityFor(layer.slot)) {
            boardLayerManager.failProjection(expectedPlan)
            updateForVariant(variant) { it.copy(ble = it.ble.copy(error = R.string.board_layer_error_board_full)) }
            return false
        }
        if (!hasConfirmableQuantumDiodeCount(layer.holds)) {
            boardLayerManager.failProjection(expectedPlan)
            updateForVariant(variant) { it.copy(
                ble = it.ble.copy(error = R.string.board_layer_error_multi_frame_unverified),
            ) }
            return false
        }
        val controllerState = boardLayerManager.state.value
        val assessment = BoardLayerConflictPolicy.assess(
            candidate = layer.holds,
            activeLayers = controllerState.layers,
            externalLayers = controllerState.externalLayers,
            replacingSlot = layer.slot,
        )
        if (assessment.unknownLayerCount > 0) {
            boardLayerManager.failProjection(expectedPlan)
            updateForVariant(variant) { it.copy(
                ble = it.ble.copy(error = R.string.board_layer_error_external_unknown),
            ) }
            return false
        }
        if (assessment.sharedHoldCount > 0) {
            boardLayerManager.failProjection(expectedPlan)
            updateForVariant(variant) { it.copy(ble = it.ble.copy(error = R.string.board_layer_error_shared_hold)) }
            return false
        }
        val occupiedColors = controllerState.reservedLayerColors(layer.slot)
        if (layer.color in occupiedColors) {
            boardLayerManager.failProjection(expectedPlan)
            updateForVariant(variant) { it.copy(ble = it.ble.copy(error = R.string.board_layer_error_color_taken)) }
            return false
        }
        val productSizeId = expectedBoard.productSizeId.toInt()
        val placementToLed = withContext(ioDispatcher) {
            boardRepository.getPlacementLedMap(productSizeId, BoardBrand.QUANTUM.wireValue)
        }
        if (!boardLayerManager.isBoundTo(expectedBoard) ||
            !hasCompleteQuantumLedMapping(layer.holds, placementToLed)
        ) {
            boardLayerManager.failProjection(expectedPlan)
            updateForVariant(variant) { it.copy(ble = it.ble.copy(error = R.string.board_send_error_no_led_data)) }
            return false
        }
        if (!boardLayerManager.beginProjection(expectedPlan)) {
            updateForVariant(variant) { it.copy(
                ble = it.ble.copy(error = R.string.board_layer_error_state_unavailable),
            ) }
            return false
        }
        val written = bleConnection.sendClimb(
            holds = layer.holds,
            placementToLed = placementToLed,
            roleColors = emptyMap(),
            routeId = layer.routeUuid,
            quantumUserId = layer.userUuid,
            quantumColor = layer.color,
            expectedQuantumPlayers = expectedPlayers,
            expectedQuantumBoard = expectedBoard,
            expectedBrand = BoardBrand.QUANTUM,
        )
        if (written) boardLayerManager.confirmProjection(expectedPlan)
        else boardLayerManager.failProjection(expectedPlan)
        val onScreen = state.value
        if (written &&
            onScreen.climb?.uuid == layer.climbUuid &&
            onScreen.angle == layer.angle
        ) {
            recordSentToHistory(onScreen)
        }
        return written
    }

    /** Validate a multi-layer plan before its first BLE mutation. Individual
     * sends are checked again at the write boundary, but that alone can leave
     * a send-all half applied when a later plan conflicts with controller
     * truth or another new plan. */
    @androidx.annotation.StringRes
    private fun quantumLayerPreflight(slots: List<Int>): Int? =
        QuantumLayerUiPolicy.planBlock(boardLayerManager.state.value, slots)
            ?.let(::quantumLayerSuggestionBlockResource)

    /** Pull controller truth and resolve every reported vendor route against
     * the active model's local catalogue. No lookup result ever changes board
     * ownership: it only enriches the already-authoritative player snapshot. */
    private suspend fun refreshAndHydrateQuantumState(): Boolean {
        if (!bleConnection.refreshQuantumState()) return false
        return hydrateQuantumControllerState()
    }

    /** Enrich the latest notification/read snapshot for the layer UI. */
    suspend fun hydrateQuantumControllerState(): Boolean {
        val controller = bleConnection.quantumControllerState.value
        if (!controller.authoritative) return false
        val descriptor = bleConnection.connectedBoardDescriptor.value ?: return false
        if (descriptor.boardBrand != BoardBrand.QUANTUM) return false
        val physicalBoard = runCatching { PhysicalBoardIdentity.resolve(descriptor) }.getOrNull()
            ?: return false
        val verifiedModel = bleConnection.connectedQuantumModel.value ?: return false
        val productSizeId = verifiedModel.productSizeId
        val model = verifiedModel.wireValue
        val boardIdentity = BoardLayerBoardIdentity(physicalBoard.value, productSizeId)
        if (!boardLayerManager.isBoundTo(boardIdentity)) return false
        boardLayerManager.reconcile(controller.players)
        val resolved = withContext(ioDispatcher) {
            val ledMap = boardRepository.getPlacementLedMap(
                productSizeId.toInt(), BoardBrand.QUANTUM.wireValue,
            )
            controller.players.mapNotNull { player ->
                val climb = boardRepository.getQuantumClimbByExternalRoute(
                    routeUuid = player.routeId,
                    model = model,
                    allowDirectUuidFallback = boardLayerManager.ownsIdentity(player.userId),
                ) ?: run {
                    Log.d(TAG, "Quantum route absent: ${player.routeId} model=$model")
                    return@mapNotNull null
                }
                val holds = BoardClimbParser.parseSingleFrameStrict(climb.frames) ?: run {
                    Log.w(TAG, "Quantum route has invalid frames: ${player.routeId} app=${climb.uuid}")
                    return@mapNotNull null
                }
                if (!hasCompleteQuantumLedMapping(holds, ledMap)) {
                    val missing = holds.map(BoardHold::placementId).filterNot(ledMap::containsKey)
                    Log.w(
                        TAG,
                        "Quantum route has incomplete LED mapping: ${player.routeId} " +
                            "map=${ledMap.size} missing=$missing",
                    )
                    return@mapNotNull null
                }
                BoardLayerControllerRouteKey(player.routeId, player.userId) to
                    BoardLayerRouteDetails(
                        climbUuid = climb.uuid,
                        climbName = climb.name,
                        holds = holds,
                    )
            }.toMap()
        }
        val latestDescriptor = bleConnection.connectedBoardDescriptor.value
        val latestPhysical = latestDescriptor?.let {
            runCatching { PhysicalBoardIdentity.resolve(it) }.getOrNull()
        }
        // The manager enriches only a currently present exact route/user key.
        // A concurrently refreshed roster is therefore safe and must not make
        // a valid catalogue lookup disappear from the UI.
        if (latestPhysical != physicalBoard || bleConnection.connectedQuantumModel.value != verifiedModel ||
            !boardLayerManager.isBoundTo(boardIdentity)
        ) return false
        Log.d(
            TAG,
            "Quantum catalogue hydration: model=$model " +
                "players=${controller.players.size} resolved=${resolved.size}",
        )
        boardLayerManager.hydrateControllerRoutes(resolved)
        return true
    }

    /**
     * MoonBoard branch of [sendToBoard] (FEAT-027). Gates on a non-blank
     * `frames` string instead of an Aurora `holds` list, skips the LED-map
     * load entirely, and pushes the climb via [BoardBleConnection.sendMoonBoardClimb].
     * Drives the same [BoardSendState] connect/send UI state machine so the
     * detail screen's send-status row behaves identically across brands.
     */
    private fun sendMoonBoardToBoard(privatePlaylistOverride: Boolean = false) {
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
                mismatch = null,
            ),
            nearby = it.nearby.copy(debugInfo = "sending (moonboard)...")
        ) }
        Log.i(TAG, "sendMoonBoardToBoard: start frames=${frames.length}")
        val sendVariant = beginSendFence(s)
        sendJob = scope.launch {
            try {
                // Board-match guard, part 1: the CONNECTED board must be a
                // MoonBoard. Switching the active board in Settings never
                // disconnects, so the pref check below alone would let a
                // MoonBoard ASCII frame go to a still-connected Aurora board.
                val identity = sendIdentity(s) ?: return@launch
                resolveBoardConfigurationMismatch(identity)?.let {
                    updateMismatch(sendVariant, it)
                    return@launch
                }
                // Board-match guard, part 2: a MoonBoard climb can only go to a
                // connected MoonBoard of the same variant. A cross-board list
                // / deep link can surface a MoonBoard climb while a Kilter (or
                // a different MoonBoard variant) is configured; sending it
                // would light wrong/garbled holds. Refuse with a clear message.
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
                val ledMode = userPreferences.moonBoardLedMode.first()
                val success = bleConnection.sendMoonBoardClimb(
                    frames,
                    variant,
                    ledMode,
                )
                Log.i(TAG, "sendMoonBoardToBoard: writes done success=$success variant=$variant")
                updateForVariant(sendVariant) { it.copy(
                    ble = it.ble.copy(
                        isSending = false,
                        success = success,
                        error = if (!success) R.string.board_send_error_send_failed else null,
                    ),
                    nearby = it.nearby.copy(debugInfo = "sent ok=$success")
                ) }
                if (success) {
                    recordSentToHistory(s)
                    if (privatePlaylistOverride) {
                        sessionQueueManager.markExternalBoardWrite(climb.uuid, s.angle)
                    }
                    val result = climbAdvertiser.advertiseClimb(
                        climbUuid = climb.uuid,
                        angle = s.angle,
                        sharingEnabled = isSharingEnabled(),
                        projectionSurvivesDisconnect =
                            BoardProjectionPolicy.projectionSurvivesDisconnect(climb.brand),
                    )
                    updateForVariant(sendVariant) { it.copy(
                        nearby = it.nearby.copy(debugInfo = "adv: $result")
                    ) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sendMoonBoardToBoard failed", e)
                updateForVariant(sendVariant) { it.copy(
                    ble = it.ble.copy(isSending = false, error = R.string.board_send_error_generic),
                    nearby = it.nearby.copy(debugInfo = "exception: ${e.message?.take(50)}")
                ) }
            }
        }
    }

    /** Whether the BLE board is currently connected. */
    fun isConnected(): Boolean =
        bleConnection.connectionState.value == ConnectionState.CONNECTED

    /**
     * Whether a send from this screen would actually reach the wall.
     *
     * [sendToBoard] drops a request while a session queue owns the board, and
     * a caller that only checked [isConnected] got a silent no-op: route
     * playback ran its whole animation, frame counter and all, while every
     * frame was discarded. Surfaces that offer a send ask this, not the
     * connection alone.
     *
     * A BoardCell group is the same answer for a worse reason. A per-frame
     * send does not "not arrive" there — it arrives as a whole-climb
     * [BoardProjection], because the mesh wire model has no frame index. The
     * controller then re-resolves the climb and lights frame 1 again for every
     * frame this device animates through: the counter runs, the wall does not
     * move, and the group's own list is overwritten at animation rate. Until
     * that protocol carries a frame, playback stays on this screen.
     */
    fun canSendToBoard(): Boolean =
        isConnected() && (
            !isBoardOwnedBySession() ||
                (state.value.climb?.brand == BoardBrand.QUANTUM &&
                    localQuantumLayerManagementAllowed(sessionQueueManager.state.value))
            )

    fun removeBoardLayer(slot: Int) {
        if (!localQuantumLayerManagementAllowed(sessionQueueManager.state.value)) return
        val layer = boardLayerManager.state.value.layers.firstOrNull { it.slot == slot } ?: return
        val expectedPlan = layer.planKey()
        if (layer.confirmedRouteUuid == null) {
            // Purely local: dropping an unsent preview is not a board command.
            boardLayerManager.removePreview(slot)
            return
        }
        if (bleConnection.connectedBoardBrand.value != BoardBrand.QUANTUM) return
        sendJob?.cancel()
        val variant = beginSendFence()
        state.update { it.copy(ble = it.ble.copy(isSending = true, success = false, error = null)) }
        sendJob = scope.launch {
            try {
            val expectedBoard = boardLayerManager.state.value.board
            if (expectedBoard == null || !layersBelongToConnectedBoard() ||
                !refreshAndHydrateQuantumState() || !boardLayerManager.isBoundTo(expectedBoard)
            ) {
                updateForVariant(variant) { current -> current.copy(
                    ble = current.ble.copy(
                        isSending = false,
                        error = R.string.board_layer_error_state_unavailable,
                    ),
                ) }
                return@launch
            }
            val currentSlotLayer = boardLayerManager.state.value.layers.firstOrNull { it.slot == slot }
            if (currentSlotLayer != null && currentSlotLayer.planKey() != expectedPlan) {
                updateForVariant(variant) { current -> current.copy(
                    ble = current.ble.copy(
                        isSending = false,
                        error = R.string.board_layer_error_state_unavailable,
                    ),
                ) }
                return@launch
            }
            val refreshed = currentSlotLayer
            val expectedRoute = layer.confirmedRouteUuid
            if (refreshed?.confirmedRouteUuid == null) {
                boardLayerManager.removeOwned(expectedPlan)
                updateForVariant(variant) { current -> current.copy(
                    ble = current.ble.copy(isSending = false, success = true, error = null),
                ) }
                return@launch
            }
            if (refreshed.userUuid != layer.userUuid ||
                !refreshed.confirmedRouteUuid.equals(expectedRoute, ignoreCase = true)
            ) {
                updateForVariant(variant) { current -> current.copy(
                    ble = current.ble.copy(
                        isSending = false,
                        error = R.string.board_layer_error_state_unavailable,
                    ),
                ) }
                return@launch
            }
            val success = runCatching {
                bleConnection.removeQuantumLayer(
                    refreshed.userUuid,
                    expectedRouteId = expectedRoute,
                    expectedQuantumBoard = expectedBoard,
                )
            }.getOrDefault(false)
            if (success) boardLayerManager.removeOwned(expectedPlan)
            else boardLayerManager.failProjection(expectedPlan)
            val failure = runCatching {
                bleConnection.quantumControllerState.value.lastFailure
            }.getOrNull()
            updateForVariant(variant) { current -> current.copy(
                ble = current.ble.copy(
                    isSending = false,
                    success = success,
                    error = if (success) null else quantumFailureResource(failure),
                ),
            ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Quantum layer removal failed", error)
                boardLayerManager.failProjection(expectedPlan)
                updateForVariant(variant) { current ->
                    current.copy(
                        ble = current.ble.copy(
                            isSending = false,
                            success = false,
                            error = R.string.board_send_error_generic,
                        ),
                    )
                }
            } finally {
                finishQuantumJob(variant, listOf(expectedPlan))
            }
        }
    }

    /** Cancel only the local replacement plan; the confirmed route remains lit. */
    fun cancelBoardLayerReplacement(slot: Int) {
        if (!localQuantumLayerManagementAllowed(sessionQueueManager.state.value) ||
            state.value.ble.isSending
        ) return
        boardLayerManager.cancelReplacement(slot)
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
