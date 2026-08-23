package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.BoardBrand

/**
 * Asset path of a board-size background photo, namespaced by brand.
 *
 * Kilter keeps the historical flat layout (`board_images/board_<id>.webp`);
 * the other Aurora-family boards live in a per-brand subfolder
 * (`board_images/<wireValue>/board_<id>.webp`) because `product_size` ids
 * collide across brands — every board numbers its sizes from 1, so a flat
 * scheme would alias e.g. Kilter size 7 onto Tension size 7.
 *
 * Kept out of the Composable renderer so it stays unit-testable without the
 * Compose/Android runtime. A path whose asset is absent decodes to null in
 * [BoardImageCache], degrading to a placements-only view (never a crash).
 */
internal fun boardImageAssetPath(brand: BoardBrand, sizeId: Long): String = when {
    brand == BoardBrand.KILTER -> "board_images/board_${sizeId}.webp"
    brand == BoardBrand.QUANTUM -> when (sizeId) {
        9201L, 9202L, 9203L -> "board_images/quantum/board_${sizeId}.png"
        9204L, 9205L -> "board_images/quantum/board_${sizeId}.jpg"
        else -> "board_images/quantum/board_${sizeId}.png"
    }
    else -> "board_images/${brand.wireValue}/board_${sizeId}.webp"
}

/**
 * Candidate background-image asset paths, most specific first.
 *
 * A board whose holds differ per layout at the same physical size — Tension
 * TB2 ships a Mirror (layout 10) and a Spray (layout 11) on sizes 6-9 — needs
 * a layout-specific composite, so [layoutId] selects `board_<size>_<layout>`.
 * The size-only `board_<size>` path is the fallback: single-layout boards (and
 * any not yet regenerated as full composites) only have that one, and the
 * renderer falls back once more to a placements-only view if neither decodes.
 */
internal fun boardImageCandidatePaths(
    brand: BoardBrand,
    sizeId: Long,
    layoutId: Long?,
): List<String> {
    val sizePath = boardImageAssetPath(brand, sizeId)
    return if (brand != BoardBrand.QUANTUM && layoutId != null && layoutId > 0L) {
        listOf(sizePath.removeSuffix(".webp") + "_$layoutId.webp", sizePath)
    } else {
        listOf(sizePath)
    }
}

internal fun boardMarkerScale(
    @Suppress("UNUSED_PARAMETER") brand: BoardBrand,
    xScale: Float,
    @Suppress("UNUSED_PARAMETER") boardWidth: Float,
): Float = xScale
