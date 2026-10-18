package com.cruxcoach.app.send

import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.resolveRoleColors
import com.cruxcoach.app.browse.BrowsePreferences
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
    /**
     * The send's late outcome after the request was accepted: "noBoardGeometry" (the LED map
     * could not be read), "noLedData", "climbOffBoard" (refused) or "holdsNotLit" (sent, partly).
     */
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
        // The nearby area's "your climb" once the board confirms the write.
        val sent = com.cruxcoach.app.ble.SentClimb(data.climb.uuid, detail.angle, data.climb.name)
        if (brand == BoardBrand.MOONBOARD) {
            connection.sendMoonBoardClimb(data.climb.frames, data.climb.layoutId, keyValues.getString("moonboard_led_mode"), sent)
            return "started"
        }
        // Android sends with the ACTIVE board's size: the detail may draw a larger
        // "effective" board whose LED map addresses LEDs this wall does not have.
        // Only when the guard did not run (another family is configured) does the
        // drawn board stand in. Without either there is no LED map: say so.
        val sizeId = activeSizeFor(brandWire) ?: data.boardSize?.id?.toInt() ?: return "noBoardGeometry"
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
            when (BoardSendFit.of(holds, maps.first)) {
                BoardSendFit.NO_LED_DATA -> {
                    onRequestFailed("noLedData")
                    return@launch
                }
                BoardSendFit.OFF_BOARD -> {
                    onRequestFailed("climbOffBoard")
                    return@launch
                }
                // Android sends what fits and warns that the rest stayed dark.
                BoardSendFit.PARTIAL -> onRequestFailed("holdsNotLit")
                else -> Unit
            }
            // The catalogue's own placement_roles win; the user's colours are Kilter's fallback, as on Android.
            connection.sendClimb(holds, maps.first, resolveRoleColors(brand, maps.second, UserLedColors.read(keyValues)), brandWire, sent)
        }
        return "started"
    }

    /** The configured size when the configured board is this climb's family (Android `boardProductSizeId`). */
    private fun activeSizeFor(brandWire: String): Int? = try {
        val active = BrowsePreferences(keyValues).boardSelection()
        if (active.boardBrand == brandWire) active.productSizeId else null
    } catch (e: Exception) {
        null
    }
}

/**
 * Android BoardSendController's LED-map checks before a direct send: no LED map at all is
 * "no LED data"; holds of which NONE map to the configured size's LEDs are a wrong-size climb
 * the family guard cannot catch (both are e.g. Kilter) and are refused; some unmapped holds
 * are sent with a warning that they stay dark.
 */
internal object BoardSendFit {
    const val FITS = "fits"
    const val PARTIAL = "partial"
    const val OFF_BOARD = "offBoard"
    const val NO_LED_DATA = "noLedData"

    fun of(holds: List<BoardHold>, placementToLed: Map<Int, Int>): String {
        if (placementToLed.isEmpty()) return NO_LED_DATA
        val unmapped = holds.count { it.placementId !in placementToLed }
        return when {
            unmapped == 0 -> FITS
            unmapped == holds.size -> OFF_BOARD
            else -> PARTIAL
        }
    }
}
