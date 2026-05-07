package com.cruxcoach.android.data

import com.cruxcoach.data.repository.BoardSize

object BoardConstants {
    const val PAGE_SIZE = 50
    const val KILTER_PRODUCT_ID = 1            // Aurora-era product_id for Kilter Board Original
    const val KILTER_HOMEWALL_PRODUCT_ID = 7   // Aurora-era product_id for Kilter Board Homewall
    const val KILTER_DEFAULT_SIZE = 10         // 12x12 with kickboard (Original default)
    const val KILTER_HOMEWALL_DEFAULT_SIZE = 21 // 10x10 (a sensible Homewall default; user can change in Settings)
    const val KILTER_ORIGINAL_LAYOUT = 1       // layout_id of Kilter Board Original
    const val KILTER_HOMEWALL_LAYOUT = 8       // layout_id of Kilter Board Homewall

    /**
     * Hardware constants for the standard Kilter board sizes — Aurora's
     * `product_sizes` table mirrored here as a *fallback* for the
     * size-picker dialog so the user can pick their physical board
     * size before the full board DB has been synced.
     *
     * Once the cron-shipped DB is loaded, [com.cruxcoach.data.repository.BoardRepository.getAllProductSizes]
     * supersedes this list (real data, more granular Homewall variants).
     * The IDs here are stable — Aurora never reassigns them — so a pre-
     * sync pick remains valid when the post-sync DB arrives.
     *
     * Edge values: `(left, right, bottom, top)` in placement coordinates.
     * Image filename intentionally null — the bundled assets use the
     * `board_<size_id>.webp` naming scheme, not Aurora's path strings.
     */
    val KILTER_KNOWN_SIZES: List<BoardSize> = listOf(
        // Original (product_id=1) — 6 commercial variants, all bundled.
        BoardSize(7L, 1L, "12 x 14", 0L, 144L, 0L, 180L, null),
        BoardSize(8L, 1L, "8 x 12", 24L, 120L, 0L, 156L, null),
        BoardSize(10L, 1L, "12 x 12 with kickboard", 0L, 144L, 0L, 156L, null),
        BoardSize(14L, 1L, "7 x 10", 28L, 116L, 36L, 156L, null),
        BoardSize(27L, 1L, "12 x 12 without kickboard", 0L, 144L, 12L, 156L, null),
        BoardSize(28L, 1L, "16 x 12", -24L, 168L, 0L, 156L, null),
        // Homewall (product_id=7) — Aurora has 10 variants but most are
        // mirror duplicates. Pick the canonical id per dimension; the
        // post-sync DB load adds the rest if a user wants to be exact.
        BoardSize(17L, 7L, "7 x 10", -44L, 44L, 24L, 144L, null),
        BoardSize(21L, 7L, "10 x 10", -56L, 56L, 24L, 144L, null),
        BoardSize(23L, 7L, "8 x 12", -44L, 44L, -12L, 144L, null),
        BoardSize(25L, 7L, "10 x 12", -56L, 56L, -12L, 144L, null),
    )
}
