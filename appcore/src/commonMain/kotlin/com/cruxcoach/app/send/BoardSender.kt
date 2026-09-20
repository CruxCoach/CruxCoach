package com.cruxcoach.app.send

import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.resolveRoleColors
import com.cruxcoach.app.detail.ClimbDetailUiState
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.settings.UserLedColors
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicit "light it up" for the climb shown in the detail screen: resolves the
 * LED map and role colours off the main thread, then hands the holds exactly as
 * displayed (current frame, mirror applied) to the connection presenter, which
 * fences on the expected brand.
 */
class BoardSender(
    private val boardRepository: BoardRepository,
    private val connection: BoardConnectionPresenter,
    private val keyValues: KeyValueStore,
    /** Called when the LED map could not be read after the request was accepted. */
    private val onRequestFailed: (String) -> Unit = {},
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)

    /**
     * Starts an explicit send. The result of the BLE write itself arrives on
     * the connection presenter's state; this returns only whether the request
     * could be built at all, so the UI never looks like nothing happened.
     *
     * Returns "started", "noClimb", "unknownBrand", "noBoardGeometry" or
     * "noHolds".
     */
    fun send(detail: ClimbDetailUiState): String {
        val data = detail.data ?: return "noClimb"
        val brandWire = data.boardSize?.boardBrand?.wireValue ?: keyValues.getString("board_brand") ?: "kilter"
        val brand = BoardBrand.fromWireOrNull(brandWire) ?: return "unknownBrand"
        if (brand == BoardBrand.MOONBOARD) {
            connection.sendMoonBoardClimb(data.climb.frames, data.climb.layoutId, keyValues.getString("moonboard_led_mode"))
            return "started"
        }
        // Without the product size there is no LED map, so the board could not
        // be told which holds to light: say so rather than doing nothing.
        val sizeId = data.boardSize?.id?.toInt() ?: return "noBoardGeometry"
        val holds = detail.holds.map { BoardHold(it.placementId, it.roleId) }
        if (holds.isEmpty()) return "noHolds"
        scope.launch {
            val maps = try {
                withContext(io) {
                    boardRepository.getPlacementLedMap(sizeId, brandWire) to boardRepository.getRoleColorMapForBrand(brandWire)
                }
            } catch (e: Exception) {
                null
            }
            if (maps == null) {
                onRequestFailed("noBoardGeometry")
                return@launch
            }
            // The catalogue's own placement_roles win; the user's colours are Kilter's fallback, as on Android.
            connection.sendClimb(holds, maps.first, resolveRoleColors(brand, maps.second, UserLedColors.read(keyValues)), brandWire)
        }
        return "started"
    }
}
