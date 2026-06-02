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
internal fun boardImageAssetPath(brand: BoardBrand, sizeId: Long): String =
    if (brand == BoardBrand.KILTER) "board_images/board_${sizeId}.webp"
    else "board_images/${brand.wireValue}/board_${sizeId}.webp"
