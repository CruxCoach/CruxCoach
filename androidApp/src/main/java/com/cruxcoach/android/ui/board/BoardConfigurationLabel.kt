package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant

/** Compact, human-readable echo of the board configuration used by browse. */
internal fun activeBoardConfigurationLabel(
    brand: BoardBrand,
    layoutId: Int,
    angle: Int,
    boardSize: BoardSize?,
): String {
    val boardName = when {
        brand == BoardBrand.MOONBOARD ->
            MoonBoardVariant.fromLayoutId(layoutId.toLong())?.displayName ?: brand.displayName
        brand.usesAuroraProtocol && brand != BoardBrand.KILTER ->
            BoardConstants.auroraVariant(brand, layoutId)?.displayName ?: brand.displayName
        else -> brand.displayName
    }
    val size = when {
        boardSize == null || brand == BoardBrand.MOONBOARD -> null
        brand == BoardBrand.KILTER ->
            BoardConstants.sizeLabel(boardSize.id, boardSize.name, brand)
        else -> BoardConstants.auroraSizeLabel(brand, boardSize)
    }
    return listOfNotNull(boardName, size, "$angle\u00B0")
        .joinToString(" \u00B7 ")
}
