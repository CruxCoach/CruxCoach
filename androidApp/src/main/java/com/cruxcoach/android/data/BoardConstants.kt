package com.cruxcoach.android.data

import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand

object BoardConstants {
    const val PAGE_SIZE = 50
    const val KILTER_PRODUCT_ID = 1            // Aurora-era product_id for Kilter Board Original
    const val KILTER_HOMEWALL_PRODUCT_ID = 7   // Aurora-era product_id for Kilter Board Homewall
    const val KILTER_DEFAULT_SIZE = 10         // 12x12 with kickboard (Original default)
    const val KILTER_HOMEWALL_DEFAULT_SIZE = 21 // 10x10 (a sensible Homewall default; user can change in Settings)
    const val KILTER_ORIGINAL_LAYOUT = 1       // layout_id of Kilter Board Original
    const val KILTER_HOMEWALL_LAYOUT = 8       // layout_id of Kilter Board Homewall

    /** Layout id for an Aurora product_id (1 Original → layout 1,
     *  7 Homewall → layout 8). Lets a size pick resolve its own
     *  layout, so the picker no longer needs a separate layout chip. */
    fun layoutIdForProduct(productId: Int): Int =
        if (productId == KILTER_HOMEWALL_PRODUCT_ID) KILTER_HOMEWALL_LAYOUT
        else KILTER_ORIGINAL_LAYOUT

    /**
     * A selectable Aurora board variant (FEAT-031): a layout that maps to a
     * distinct physical product/generation, with a sensible default size.
     */
    data class AuroraVariant(
        val layoutId: Int,
        val productId: Int,
        val displayName: String,
        val defaultSizeId: Int,
        /** Aurora `layouts.is_mirrored`: the layout is left-right symmetric, so
         *  every climb can also be climbed mirrored (TB1 + TB2 Mirror = true,
         *  TB2 Spray = false). Surfaced via [isLayoutMirrorable]. */
        val isMirrored: Boolean,
    )

    /**
     * Static catalog of Aurora-family board variants. Only boards with more
     * than one layout need an entry — currently Tension (TB1 / TB2 Mirror /
     * TB2 Spray) and Decoy (Dungeon Trainer / Dots); the rest ship a single
     * layout. This is deliberately NOT data-driven: it must cover
     * every variant regardless of the Blossom/sync state, so a catalogue
     * download problem can never hide a user's board. The chunk still provides
     * the geometry that renders on top; the picker just needs the variant list
     * + a default to fall back on. Kilter keeps its own Original/Homewall path.
     */
    val AURORA_VARIANTS: Map<BoardBrand, List<AuroraVariant>> = mapOf(
        BoardBrand.TENSION to listOf(
            AuroraVariant(layoutId = 9,  productId = 4, displayName = "Tension Board",            defaultSizeId = 1, isMirrored = true),
            AuroraVariant(layoutId = 10, productId = 5, displayName = "Tension Board 2 (Mirror)", defaultSizeId = 6, isMirrored = true),
            AuroraVariant(layoutId = 11, productId = 5, displayName = "Tension Board 2 (Spray)",  defaultSizeId = 6, isMirrored = false),
        ),
        // Decoy ships two listed layouts under product_id=1 (RE-verified from
        // the bundled board DB): Dungeon Trainer (layout 2, ~7.4k climbs) and
        // Dots (layout 1, ~60 climbs, an R&D wall). Both are left-right
        // symmetric (layouts.is_mirrored=1) and share product_size 1 (12x12,
        // the largest). Dungeon Trainer is listed first so it stays the picker
        // default (variants.firstOrNull) — matching the previous most-climbed
        // auto-pick; Dots is the opt-in second choice (it was unreachable
        // before, as Decoy had no variant entry).
        BoardBrand.DECOY to listOf(
            AuroraVariant(layoutId = 2, productId = 1, displayName = "Decoy Dungeon Trainer", defaultSizeId = 1, isMirrored = true),
            AuroraVariant(layoutId = 1, productId = 1, displayName = "Decoy Dots",            defaultSizeId = 1, isMirrored = true),
        ),
    )

    /** Variants for a board, or empty when it has a single layout
     *  (Grasshopper / So iLL / Touchstone) — those skip the variant tier. */
    fun auroraVariants(brand: BoardBrand): List<AuroraVariant> = AURORA_VARIANTS[brand] ?: emptyList()

    /** The variant whose layout matches [layoutId], or null. */
    fun auroraVariant(brand: BoardBrand, layoutId: Int): AuroraVariant? =
        AURORA_VARIANTS[brand]?.firstOrNull { it.layoutId == layoutId }

    /**
     * Whether a climb on (brand, layoutId) can be climbed mirrored — i.e. the
     * layout is left-right symmetric, so reflecting every hold across the
     * vertical centre yields a valid second problem. Mirrors Aurora's
     * `layouts.is_mirrored` (RE-verified 2026-06-08).
     *
     * The climb-detail mirror toggle is gated on this so it never appears on a
     * non-mirrorable layout — notably **Tension TB2 Spray** (layout 11), which
     * shares the physically-symmetric TB2 holds with the Mirror layout but is
     * an asymmetric spray wall: a flip there would light unpaired holds and
     * produce a broken, non-canonical problem.
     *
     *  - Tension: per-variant — TB1 (9) + TB2 Mirror (10) = true, TB2 Spray (11) = false.
     *  - Grasshopper / Decoy / So iLL: their layout(s) are symmetric → true.
     *  - Touchstone: its single layout is asymmetric → false.
     *  - Kilter / MoonBoard: asymmetric / fixed-config boards, no mirror in the
     *    vendor app → false.
     *  - Map-only info-layer brands (Aurora / 12climb): no catalogue → false.
     *
     * Exhaustive over [BoardBrand] on purpose: a newly-added board must make a
     * deliberate mirrorability decision here rather than silently defaulting.
     */
    fun isLayoutMirrorable(brand: BoardBrand, layoutId: Int): Boolean = when (brand) {
        BoardBrand.TENSION -> auroraVariant(brand, layoutId)?.isMirrored ?: false
        BoardBrand.GRASSHOPPER, BoardBrand.DECOY, BoardBrand.SOILL -> true
        BoardBrand.TOUCHSTONE,
        BoardBrand.KILTER,
        BoardBrand.MOONBOARD,
        BoardBrand.AURORA,
        BoardBrand.TWELVECLIMB -> false
    }

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
        // Remaining Homewall variants (Mainline / Auxiliary). Edges are
        // verbatim Aurora product_sizes; display names come from
        // KILTER_SIZE_LABELS via [sizeLabel], so the pre-sync onboarding
        // list is the full, correct set of all 16 Kilter boards.
        BoardSize(18L, 7L, "7x10", -44L, 44L, 24L, 144L, null),
        BoardSize(19L, 7L, "7x10", -44L, 44L, 24L, 144L, null),
        BoardSize(22L, 7L, "10x10", -56L, 56L, 24L, 144L, null),
        BoardSize(29L, 7L, "10x10", -56L, 56L, 24L, 144L, null),
        BoardSize(24L, 7L, "8x12", -44L, 44L, -12L, 144L, null),
        BoardSize(26L, 7L, "10x12", -56L, 56L, -12L, 144L, null),
    )

    /**
     * Official Kilter-app display labels for board sizes, keyed by
     * Aurora product_size_id.
     *
     * Each product_size_id is a physically distinct board (its own
     * LED-position map + valid-hold set), so the user must recognise
     * and pick the exact one in front of them. We mirror the wording
     * the *official Kilter app* uses so the board is recognisable:
     *  - Original: "12x12, with/no Kickboard"; "14x12 Super Tall, …";
     *    "12x16 Super Wide, …"; "10x7, no Kickboard"; "12x8, …".
     *  - Homewall: "<size> — Full Ride / Mainline / Auxiliary" (the LED
     *    kit). Full Ride = Mainline+Auxiliary sets; Mainline = set
     *    26(+kicker); Auxiliary = set 27(+kicker).
     *
     * Cross-checked against Aurora product_sizes (+edge_bottom for the
     * kickboard) and the official app's `libapp` size strings; the
     * Aurora catalog is frozen so this curated map is stable. Any
     * product_size_id not listed falls through to its raw name via
     * [sizeLabel] (future-proof).
     */
    val KILTER_SIZE_LABELS: Map<Long, String> = mapOf(
        // Original (product_id=1) — official app wording.
        14L to "10x7, no Kickboard",
        8L to "12x8, with Kickboard",
        10L to "12x12, with Kickboard",
        27L to "12x12, no Kickboard",
        7L to "14x12 Super Tall, with Kickboard",
        28L to "12x16 Super Wide, with Kickboard",
        // Homewall (product_id=7) — official LED-kit naming.
        17L to "Homewall 10x7 — Full Ride",
        18L to "Homewall 10x7 — Mainline",
        19L to "Homewall 10x7 — Auxiliary",
        21L to "Homewall 10x10 — Full Ride",
        22L to "Homewall 10x10 — Mainline",
        29L to "Homewall 10x10 — Auxiliary",
        23L to "Homewall 12x8 — Full Ride",
        24L to "Homewall 12x8 — Mainline",
        25L to "Homewall 10x12 — Full Ride",
        26L to "Homewall 10x12 — Mainline",
    )

    /**
     * Fallback popularity (≈ real-world wall counts from the gym-data
     * census) used to order the picker when live frequency from
     * kilter_board_wall isn't synced yet — so the list is always
     * "common first", never arbitrary DB order. Live frequency, when
     * available, overrides this.
     */
    val DEFAULT_SIZE_FREQUENCY: Map<Int, Long> = mapOf(
        10 to 900L, 28 to 100L, 8 to 80L, 7 to 10L, 14 to 9L, 27 to 2L,
        25 to 34L, 17 to 23L, 21 to 17L, 18 to 13L, 23 to 12L,
        24 to 3L, 26 to 3L, 22 to 2L, 19 to 2L, 29 to 1L,
    )

    /**
     * Human-unambiguous label for a Kilter board size. Returns the
     * disambiguated Homewall variant label when known, otherwise the
     * raw Aurora name (already unambiguous for Original + future-proof
     * for any product_size_id we don't yet have a curated label for).
     */
    fun sizeLabel(
        productSizeId: Long,
        fallbackName: String,
        boardBrand: BoardBrand = BoardBrand.KILTER,
    ): String =
        // KILTER_SIZE_LABELS is keyed by Kilter product_size ids; the Aurora
        // family reuses the same id space for different sizes, so apply the
        // curated labels only for Kilter — others use their own raw name.
        if (boardBrand == BoardBrand.KILTER) KILTER_SIZE_LABELS[productSizeId] ?: fallbackName
        else fallbackName

    /** Resolve a product_size_id against a size list to its unambiguous
     *  display label (empty string if not found). Single place that maps
     *  "selected id → human label" for the current-selection echo. */
    fun sizeLabel(sizes: List<BoardSize>, productSizeId: Int): String =
        sizes.firstOrNull { it.id.toInt() == productSizeId }
            ?.let { sizeLabel(it.id, it.name, it.boardBrand) }
            .orEmpty()
}
