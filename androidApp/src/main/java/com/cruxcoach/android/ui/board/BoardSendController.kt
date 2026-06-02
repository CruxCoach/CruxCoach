package com.cruxcoach.android.ui.board

import android.util.Log
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CancellationException
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
    private val bleConnection: BoardBleConnection,
    private val userPreferences: UserPreferences,
    private val climbAdvertiser: ClimbBleAdvertiser,
    private val sessionQueueManager: SessionQueueManager,
    private val isSharingEnabled: () -> Boolean
) {

    private var sendJob: Job? = null

    /** Cancel any in-flight send (used when switching climbs). */
    fun cancelSend() {
        sendJob?.cancel()
    }

    fun sendToBoard() {
        // When a session queue is active, the queue controls what's on the board.
        // Individual climb sends from detail views are suppressed.
        if (sessionQueueManager.state.value.isActive) {
            Log.d(TAG, "sendToBoard: suppressed (session queue active)")
            return
        }
        // FEAT-027: a MoonBoard climb sends an ASCII `frames` payload — it has
        // no Aurora `holds` list and no LED map. Gate on a non-blank frames
        // string and route through the dedicated MoonBoard transport.
        if (state.value.climb?.brand == BoardBrand.MOONBOARD) {
            sendMoonBoardToBoard()
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
            ble = BoardSendState(connectionState = it.ble.connectionState, isSending = true),
            nearby = it.nearby.copy(debugInfo = "sending...")
        ) }
        Log.i(TAG, "sendToBoard: start frames=${s.holds.size}")
        sendJob = scope.launch {
            try {
                // Board-match guard: you can only send a climb to a connected
                // board of the same family. A climb opened from a mixed list
                // or deep link can differ from the active board; sending it
                // would light the wrong holds. (This Kilter branch is only
                // reached for non-MoonBoard climbs, so the check catches the
                // "active board is a MoonBoard" mismatch.)
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
                val placementToLed = withContext(Dispatchers.IO) {
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
                val roleColorMap = withContext(Dispatchers.IO) {
                    boardRepository.getRoleColorMapForBrand(activeBrand)
                }.ifEmpty {
                    val fallback = if (brand == BoardBrand.KILTER) {
                        userPreferences.ledHoldColors.first()
                    } else {
                        LedHoldColors.standardFor(brand)
                    }
                    fallback.toRoleColorMap()
                }
                val success = bleConnection.sendClimb(s.holds, placementToLed, roleColorMap)
                Log.i(TAG, "sendToBoard: writes done success=$success")
                state.update { it.copy(
                    ble = it.ble.copy(isSending = false, success = success, error = if (!success) R.string.board_send_error_send_failed else null),
                    nearby = it.nearby.copy(debugInfo = "sent ok=$success")
                ) }
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

    /**
     * MoonBoard branch of [sendToBoard] (FEAT-027). Gates on a non-blank
     * `frames` string instead of an Aurora `holds` list, skips the LED-map
     * load entirely, and pushes the climb via [BoardBleConnection.sendMoonBoardClimb].
     * Drives the same [BoardSendState] connect/send UI state machine so the
     * detail screen's send-status row behaves identically across brands.
     */
    private fun sendMoonBoardToBoard() {
        val s = state.value
        val frames = s.climb?.frames
        if (frames.isNullOrBlank() || s.ble.connectionState != ConnectionState.CONNECTED) {
            state.update { it.copy(nearby = it.nearby.copy(
                debugInfo = "skip: frames=${frames?.length ?: 0} conn=${s.ble.connectionState}"
            )) }
            return
        }
        if (s.ble.isSending) {
            state.update { it.copy(nearby = it.nearby.copy(debugInfo = "skip: already sending")) }
            return
        }

        state.update { it.copy(
            ble = BoardSendState(connectionState = it.ble.connectionState, isSending = true),
            nearby = it.nearby.copy(debugInfo = "sending (moonboard)...")
        ) }
        Log.i(TAG, "sendMoonBoardToBoard: start frames=${frames.length}")
        sendJob = scope.launch {
            try {
                // Board-match guard: a MoonBoard climb can only go to a
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
                if (s.climb?.layoutId?.toLong() != null && s.climb.layoutId.toLong() != activeLayout) {
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
                // the holds we're rendering. Falls back to the active pref,
                // then MOONBOARD_2016, only if the climb carries no usable
                // layout id (stale/corrupt row).
                val layoutId = s.climb?.layoutId?.toLong()
                    ?: userPreferences.boardLayoutId.first().toLong()
                val variant = com.cruxcoach.domain.board.MoonBoardVariant
                    .fromLayoutId(layoutId)
                    ?: com.cruxcoach.domain.board.MoonBoardVariant.MOONBOARD_2016
                val success = bleConnection.sendMoonBoardClimb(frames, variant)
                Log.i(TAG, "sendMoonBoardToBoard: writes done success=$success variant=$variant")
                state.update { it.copy(
                    ble = it.ble.copy(
                        isSending = false,
                        success = success,
                        error = if (!success) R.string.board_send_error_send_failed else null,
                    ),
                    nearby = it.nearby.copy(debugInfo = "sent ok=$success")
                ) }
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

    /** Re-advertise the current climb to nearby devices (called on every climb load/switch). */
    fun updateNearbyAdvertising(uuid: String, angle: Int) {
        if (!isSharingEnabled()) return
        // Only advertise when the board is connected -- browsing without connection should not share
        if (bleConnection.connectionState.value != ConnectionState.CONNECTED) return
        scope.launch {
            climbAdvertiser.advertiseClimb(uuid, angle)
        }
    }

    /** Whether the BLE board is currently connected. */
    fun isConnected(): Boolean =
        bleConnection.connectionState.value == ConnectionState.CONNECTED

    private companion object {
        const val TAG = "BoardSendController"
    }
}
