package com.cruxcoach.app.send

import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.resolveRoleColors
import com.cruxcoach.app.detail.ClimbDetailUiState
import com.cruxcoach.app.platform.KeyValueStore
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
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)

    fun send(detail: ClimbDetailUiState) {
        val data = detail.data ?: return
        val brandWire = data.boardSize?.boardBrand?.wireValue ?: keyValues.getString("board_brand") ?: "kilter"
        val brand = BoardBrand.fromWireOrNull(brandWire) ?: return
        if (brand == BoardBrand.MOONBOARD) {
            connection.sendMoonBoardClimb(data.climb.frames, data.climb.layoutId, keyValues.getString("moonboard_led_mode"))
            return
        }
        val sizeId = data.boardSize?.id?.toInt() ?: return
        val holds = detail.holds.map { BoardHold(it.placementId, it.roleId) }
        scope.launch {
            val maps = try {
                withContext(io) {
                    boardRepository.getPlacementLedMap(sizeId, brandWire) to boardRepository.getRoleColorMapForBrand(brandWire)
                }
            } catch (e: Exception) {
                null
            } ?: return@launch
            connection.sendClimb(holds, maps.first, resolveRoleColors(brand, maps.second, null), brandWire)
        }
    }
}
