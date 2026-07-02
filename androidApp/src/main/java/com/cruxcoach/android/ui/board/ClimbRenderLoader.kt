package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Everything a board visualization needs for one climb. */
data class ClimbRenderData(
    val climb: ClimbWithStats,
    val holds: List<BoardHold>,
    val placements: Map<Int, BoardPlacement>,
    val boardSize: BoardSize?,
    val boardImages: List<BoardImage>,
    val ledColors: LedHoldColors,
) {
    val isMoonBoard: Boolean get() = climb.brand == BoardBrand.MOONBOARD
}

/**
 * Loads the render payload for a single climb — the board-geometry subset
 * of what BoardClimbDetailViewModel assembles, reusable by the playlist
 * player (and any future surface that draws a climb outside the detail
 * pager). Placements are cached per brand, mirroring the detail VM.
 */
@Singleton
class ClimbRenderLoader @Inject constructor(
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
) {
    private val placementCache = mutableMapOf<String, Map<Int, BoardPlacement>>()

    /** Null when the climb isn't in the local catalogue. Call on IO. */
    suspend fun load(uuid: String, angle: Int): ClimbRenderData? {
        // Tolerate the GATT protocol's uppercase-no-hyphen uuid shape.
        val climb = boardRepository.getClimbByUuid(uuid, angle)
            ?: boardRepository.getClimbByUuid(uuid.lowercase(), angle)
            ?: boardRepository.getClimbByUuid(uuid.uppercase(), angle)
            ?: boardRepository.getClimbByUuidNormalized(uuid, angle)
            ?: return null

        val holds = BoardClimbParser.parseMultiFrames(climb.frames).firstOrNull() ?: emptyList()
        if (climb.brand == BoardBrand.MOONBOARD) {
            // MoonBoard renders procedurally from `frames` — no Aurora geometry.
            return ClimbRenderData(
                climb = climb,
                holds = holds,
                placements = emptyMap(),
                boardSize = null,
                boardImages = emptyList(),
                ledColors = LedHoldColors.standardFor(climb.brand),
            )
        }

        val brand = climb.brand.wireValue
        val placements = placementCache.getOrPut(brand) {
            boardRepository.getAllPlacements(brand).associateBy { it.placementId.toInt() }
        }
        val prefSizeId = userPreferences.boardProductSizeId.first()
        val prefLayoutId = userPreferences.boardLayoutId.first()
        val (sizeId, layoutId) = pickEffectiveBoard(
            climbUuid = climb.uuid,
            climbLayoutId = climb.layoutId.toInt(),
            preferredSizeId = prefSizeId,
            preferredLayoutId = prefLayoutId,
            boardBrand = brand,
        )
        return ClimbRenderData(
            climb = climb,
            holds = holds,
            placements = placements,
            boardSize = boardRepository.getProductSize(sizeId, brand),
            boardImages = boardRepository.getBoardImages(sizeId, layoutId, brand),
            ledColors = if (climb.brand == BoardBrand.KILTER) userPreferences.ledHoldColors.first()
                        else LedHoldColors.standardFor(climb.brand),
        )
    }

    /**
     * (sizeId, layoutId) pick, same ranking as the detail screen:
     * user's size when it can render the climb → any size whose extent
     * contains the climb → user's size for the climb's layout → any size
     * with images for the layout → user's preferred pair as last resort.
     */
    private fun pickEffectiveBoard(
        climbUuid: String,
        climbLayoutId: Int,
        preferredSizeId: Int,
        preferredLayoutId: Int,
        boardBrand: String,
    ): Pair<Int, Int> {
        if (boardRepository.canRenderClimbOnSize(climbUuid, preferredSizeId, boardBrand)) {
            return preferredSizeId to climbLayoutId
        }
        boardRepository.getProductSizeForClimbRender(climbUuid, boardBrand)?.let { containing ->
            return containing to climbLayoutId
        }
        val candidateSizes = boardRepository.getProductSizesForLayout(climbLayoutId, boardBrand)
        return when {
            preferredSizeId in candidateSizes -> preferredSizeId to climbLayoutId
            candidateSizes.isNotEmpty() -> candidateSizes.first() to climbLayoutId
            else -> preferredSizeId to preferredLayoutId
        }
    }
}
