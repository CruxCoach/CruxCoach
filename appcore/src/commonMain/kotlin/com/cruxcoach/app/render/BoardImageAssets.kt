package com.cruxcoach.app.render

import com.cruxcoach.domain.board.BoardBrand

// Port of Android's ui/board/BoardImageAssets.kt. iOS bundles the identical
// `board_images/` folder, so these relative paths resolve in both apps.

/** Kilter keeps the flat layout; other brands are namespaced because product_size ids collide. */
fun boardImageAssetPath(brand: BoardBrand, sizeId: Long): String = when {
    brand == BoardBrand.KILTER -> "board_images/board_${sizeId}.webp"
    brand == BoardBrand.QUANTUM -> when (sizeId) {
        9201L, 9202L, 9203L -> "board_images/quantum/board_${sizeId}.png"
        9204L, 9205L -> "board_images/quantum/board_${sizeId}.jpg"
        else -> "board_images/quantum/board_${sizeId}.png"
    }
    else -> "board_images/${brand.wireValue}/board_${sizeId}.webp"
}

/** Most specific first: `board_<size>_<layout>` (e.g. Tension TB2 Mirror vs Spray), then `board_<size>`. */
fun boardImageCandidatePaths(brand: BoardBrand, sizeId: Long, layoutId: Long?): List<String> {
    val sizePath = boardImageAssetPath(brand, sizeId)
    return if (brand != BoardBrand.QUANTUM && layoutId != null && layoutId > 0L) {
        listOf(sizePath.removeSuffix(".webp") + "_$layoutId.webp", sizePath)
    } else {
        listOf(sizePath)
    }
}

fun boardMarkerScale(
    @Suppress("UNUSED_PARAMETER") brand: BoardBrand,
    xScale: Float,
    @Suppress("UNUSED_PARAMETER") boardWidth: Float,
): Float = xScale

/** Kilter sizes with a bundled image: Original 7, 8, 10, 14, 27, 28; Homewall 17-19, 21-26, 29. */
val BUNDLED_KILTER_BOARD_SIZES: Set<Long> = setOf(
    7L, 8L, 10L, 14L, 27L, 28L,
    17L, 18L, 19L, 21L, 22L, 23L, 24L, 25L, 26L, 29L,
)

/** Size id the renderer assumes when no product size is configured. */
const val DEFAULT_BOARD_SIZE_ID = 10L

/** Kilter's set is enumerated; other Aurora-family boards and Quantum attempt-and-fall-back. */
fun hasBundledBoardImage(brand: BoardBrand, sizeId: Long): Boolean = when {
    brand == BoardBrand.KILTER -> sizeId in BUNDLED_KILTER_BOARD_SIZES
    brand.usesAuroraProtocol || brand == BoardBrand.QUANTUM -> true
    else -> false
}
