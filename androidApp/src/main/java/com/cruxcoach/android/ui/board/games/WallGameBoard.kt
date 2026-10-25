package com.cruxcoach.android.ui.board.games

import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.ShinyWallVariant
import com.cruxcoach.domain.board.games.WallHold
import com.cruxcoach.domain.board.games.WallRows
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** The active board as the wall games see it: its holds in rows, how to draw them and how to light them. */
data class WallGameBoard(
    val brand: BoardBrand,
    val layoutId: Long,
    val rows: WallRows,
    /** Drawing (Aurora, Shiny Wall): the board's placements, its size and photos. */
    val placements: Map<Int, BoardPlacement> = emptyMap(),
    val boardSize: BoardSize? = null,
    val boardImages: List<BoardImage> = emptyList(),
    val ledColors: LedHoldColors = LedHoldColors.standardFor(brand),
    /** Lighting an Aurora board: placement → LED and role → colour. */
    val ledMap: Map<Int, Int> = emptyMap(),
    val roleColors: Map<Int, Int> = emptyMap(),
    val shinyVariant: ShinyWallVariant? = null,
    val moonVariant: MoonBoardVariant? = null,
    val moonLedMode: MoonBoardLedMode = MoonBoardLedMode.BELOW,
)

sealed interface WallGameBoardLoad {
    data class Ready(val board: WallGameBoard) : WallGameBoardLoad

    /** Quantum shares its lights between climbers; a game would take them over. */
    data object Unsupported : WallGameBoardLoad

    /** The board's hold positions are not on the device (no catalogue or geometry yet). */
    data object NoHolds : WallGameBoardLoad
}

/** Pure conversions between a board's own hold data and [WallHold]s. */
internal object WallGameHolds {

    /** Placements that [keep] accepts, at their stored positions. */
    fun fromPlacements(placements: Collection<BoardPlacement>, keep: (BoardPlacement) -> Boolean): List<WallHold> =
        placements.filter(keep).map { WallHold(it.placementId.toInt(), it.x.toDouble(), it.y.toDouble()) }

    /** True when [placement] lies on the board size [size] (edges exclusive, like the LED map). */
    fun onBoard(placement: BoardPlacement, size: BoardSize?): Boolean =
        size == null || (placement.x > size.edgeLeft && placement.x < size.edgeRight &&
            placement.y > size.edgeBottom && placement.y < size.edgeTop)

    /**
     * MoonBoard holds mounted in [selectedSets]: grid hold ids
     * `(row - 1) * 11 + column + 1`, at column/row positions. A variant
     * without a cell map, or no selected set, falls back to the whole grid
     * (every grid position has an LED).
     */
    fun moonBoard(variant: MoonBoardVariant, selectedSets: Collection<Long>): List<WallHold> {
        val all = 1..(MOON_COLUMNS * variant.gridRows)
        val cells = MoonBoardHoldSets.cellSets(variant)
        val mounted = cells.filterValues { it in selectedSets }.keys.filter { it in all }
        val ids = if (cells.isEmpty() || mounted.isEmpty()) all.toList() else mounted.sorted()
        return ids.map { id -> WallHold(id, ((id - 1) % MOON_COLUMNS).toDouble(), ((id - 1) / MOON_COLUMNS).toDouble()) }
    }

    /** MoonBoard `frames` for [holds]: start, hand and top; feet show as hands (the board has no foot colour). */
    fun moonBoardFrames(holds: List<BoardHold>): String = holds.joinToString("") { hold ->
        val role = when (HoldRole.roleClass(hold.roleId)) {
            HoldRole.START -> HoldRole.ROUTE_START
            HoldRole.FINISH -> HoldRole.ROUTE_FINISH
            else -> HoldRole.ROUTE_HAND
        }
        "p${hold.placementId}r$role"
    }

    private const val MOON_COLUMNS = 11
}

/** Reads the active board for the wall games. Call on IO. */
class WallGameBoardLoader @Inject constructor(
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
) {
    suspend fun load(): WallGameBoardLoad {
        val brand = BoardBrand.fromWire(userPreferences.boardBrand.first())
        val layoutId = userPreferences.boardLayoutId.first()
        val sizeId = userPreferences.boardProductSizeId.first()
        return when (brand) {
            BoardBrand.QUANTUM -> WallGameBoardLoad.Unsupported
            BoardBrand.MOONBOARD -> loadMoonBoard(layoutId.toLong())
            BoardBrand.SHINYWALL -> loadShinyWall(layoutId, sizeId)
            else -> loadAurora(brand, layoutId, sizeId)
        }
    }

    private suspend fun loadMoonBoard(layoutId: Long): WallGameBoardLoad {
        val variant = MoonBoardVariant.fromLayoutId(layoutId) ?: return WallGameBoardLoad.Unsupported
        val sets = userPreferences.getMoonBoardHoldSets(variant)
        return WallGameBoardLoad.Ready(
            WallGameBoard(
                brand = BoardBrand.MOONBOARD,
                layoutId = layoutId,
                rows = WallRows.of(WallGameHolds.moonBoard(variant, sets)),
                moonVariant = variant,
                moonLedMode = userPreferences.moonBoardLedMode.first(),
            ),
        )
    }

    private fun loadShinyWall(layoutId: Int, sizeId: Int): WallGameBoardLoad {
        val variant = ShinyWallVariant.fromLayoutId(layoutId.toLong())
            ?.takeIf { it.supported } ?: return WallGameBoardLoad.Unsupported
        val brand = BoardBrand.SHINYWALL.wireValue
        // The placements of every variant share the brand; keep this wall's.
        val placements = boardRepository.getPlacementsForLayout(sizeId, layoutId, brand)
            .filter { variant.indexOf(it.placementId.toInt()) != null }
        if (placements.isEmpty()) return WallGameBoardLoad.NoHolds
        return WallGameBoardLoad.Ready(
            WallGameBoard(
                brand = BoardBrand.SHINYWALL,
                layoutId = layoutId.toLong(),
                rows = WallRows.of(WallGameHolds.fromPlacements(placements) { true }),
                placements = placements.associateBy { it.placementId.toInt() },
                boardSize = boardRepository.getProductSize(variant.productSizeId.toInt(), brand),
                boardImages = boardRepository.getBoardImages(sizeId, layoutId, brand),
                shinyVariant = variant,
            ),
        )
    }

    private suspend fun loadAurora(brand: BoardBrand, layoutId: Int, sizeId: Int): WallGameBoardLoad {
        val wire = brand.wireValue
        val size = boardRepository.getProductSize(sizeId, wire)
        val all = boardRepository.getPlacementsForLayout(sizeId, layoutId, wire)
        val ledMap = boardRepository.getPlacementLedMap(sizeId, wire)
        // A hold with an LED on this size is a hold the game can light; without
        // LED data, the size's edges still say which holds are on the wall.
        val placements = if (ledMap.isNotEmpty()) {
            all.filter { it.placementId.toInt() in ledMap }
        } else {
            all.filter { WallGameHolds.onBoard(it, size) }
        }
        if (placements.isEmpty()) return WallGameBoardLoad.NoHolds
        val ledColors = if (brand == BoardBrand.KILTER) userPreferences.ledHoldColors.first()
        else LedHoldColors.standardFor(brand)
        val roleColors = boardRepository.getRoleColorMapForBrand(wire).ifEmpty { ledColors.toRoleColorMap() }
        return WallGameBoardLoad.Ready(
            WallGameBoard(
                brand = brand,
                layoutId = layoutId.toLong(),
                rows = WallRows.of(WallGameHolds.fromPlacements(placements) { true }),
                placements = placements.associateBy { it.placementId.toInt() },
                boardSize = size,
                boardImages = boardRepository.getBoardImages(sizeId, layoutId, wire),
                ledColors = ledColors,
                ledMap = ledMap,
                roleColors = roleColors,
            ),
        )
    }
}
