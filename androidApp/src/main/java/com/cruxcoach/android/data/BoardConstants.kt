package com.cruxcoach.android.data

import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant

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
        // Decoy has ONE real consumer board (the "Dungeon Trainer" layout,
        // layout 2 — 852 setters / ~7970 climbs). The board DB also carries a
        // layout 1 "Dots", but RE + decoy-holds.com confirm it is NOT a product:
        // a PASSWORD-GATED internal/R&D layout (3 internal setters incl. an Aurora
        // dev, placeholder 2017-01-01 date) absent from Decoy's catalogue. So it
        // is intentionally NOT offered. A single explicit variant pins layout 2
        // (not chunk-derive, which could pick the lower-id Dots). Sizes: id1
        // 12x12 (default, largest), id2 8x12, id3 8x10.
        BoardBrand.DECOY to listOf(
            AuroraVariant(layoutId = 2, productId = 1, displayName = "Decoy", defaultSizeId = 1, isMirrored = true),
        ),
    )

    private fun auroraSize(id: Int, name: String, l: Int, r: Int, b: Int, t: Int, brand: BoardBrand, productId: Int = 1) =
        BoardSize(id.toLong(), productId = productId.toLong(), name = name,
                  edgeLeft = l.toLong(), edgeRight = r.toLong(),
                  edgeBottom = b.toLong(), edgeTop = t.toLong(),
                  imageFilename = null, boardBrand = brand)

    /**
     * Pre-sync product-size tier for EVERY interactive Aurora board, so the
     * picker offers each board's real sizes IMMEDIATELY — before its catalogue is
     * synced — just as [AURORA_VARIANTS] surfaces the variants up front.
     * Single-layout boards (Grasshopper / So iLL / Touchstone) expose their sizes
     * directly; variant boards (Tension TB1+TB2, Decoy) bundle every product's
     * sizes and the dialog narrows to the active variant's product.
     *
     * Sourced from the PUBLISHED chunks (build_board_db output — the app's actual
     * catalogue), NOT the aurora-re extract: the two DIVERGE on product_size ids
     * (Grasshopper GrandMaster is id 4 in the chunk but id 1 in aurora-re). The
     * ids + edges below are exactly the deduped-by-dimension set the catalogue
     * yields post-sync — Aurora chunks omit board_images, so set_count is 0 and
     * the dedup keeps the lowest id per dimension — so the pre-sync and post-sync
     * lists are identical. Known non-product phantoms ([AURORA_EXCLUDED_SIZES],
     * e.g. Tension id 10) are omitted here and filtered from the catalogue too.
     * Verified against the manufacturers' size pages (Tension TB2 = 4 sizes,
     * 12 wide max; Decoy = 8x10 / 8x12 / 12x12). Aurora ids are stable upstream.
     */
    val AURORA_BUNDLED_SIZES: Map<BoardBrand, List<BoardSize>> = mapOf(
        BoardBrand.TENSION to listOf(
            // TB1 (product 4) — the original Tension Board is ONE 8 ft x 12 ft
            // wall; these are kickboard/height CONFIGS (all 96 units wide), not
            // W x H sizes, so they carry no appended dimension.
            auroraSize(1, "Full Wall",       0, 96, 0, 156, BoardBrand.TENSION, productId = 4),
            auroraSize(2, "Half Kickboard",  0, 96, 4, 156, BoardBrand.TENSION, productId = 4),
            auroraSize(3, "No Kickboard",    0, 96, 8, 156, BoardBrand.TENSION, productId = 4),
            auroraSize(4, "Short",           0, 96, 8, 132, BoardBrand.TENSION, productId = 4),
            auroraSize(5, "Short & Narrow", 16, 80, 8, 132, BoardBrand.TENSION, productId = 4),
            // TB2 (product 5) — the 4 official sizes, already labelled
            // HEIGHT x WIDTH (Tension's house convention). id 10 "12 high x 16
            // wide" is an Aurora phantom (no such product) → AURORA_EXCLUDED_SIZES.
            auroraSize(6, "12 high x 12 wide", -68, 68, 0, 144, BoardBrand.TENSION, productId = 5),
            auroraSize(7, "10 high x 12 wide", -68, 68, 0, 120, BoardBrand.TENSION, productId = 5),
            auroraSize(8, "12 high x 8 wide",  -44, 44, 0, 144, BoardBrand.TENSION, productId = 5),
            auroraSize(9, "10 high x 8 wide",  -44, 44, 0, 120, BoardBrand.TENSION, productId = 5),
        ),
        BoardBrand.DECOY to listOf(
            auroraSize(1, "12 x 12", -68, 68, 0, 144, BoardBrand.DECOY),
            auroraSize(2, "8 x 12",  -44, 44, 0, 144, BoardBrand.DECOY),
            auroraSize(3, "8 x 10",  -44, 44, 0, 120, BoardBrand.DECOY),
        ),
        BoardBrand.GRASSHOPPER to listOf(
            // ids 4/5/6 (NOT 2/3/4): ids 5/6 are dimension duplicates of 2/3 with
            // MORE board_images, so they win the catalogue's max-set_count dedup.
            // The bundle must match those survivors or the list — and the id-keyed
            // W x H labels below — would shift the moment the board syncs. Ordered
            // by id to match the catalogue's ORDER BY id (GrandMaster/Master/Ninja).
            auroraSize(4, "GrandMaster", -68, 68, 0, 144, BoardBrand.GRASSHOPPER),
            auroraSize(5, "Master",      -44, 44, 0, 144, BoardBrand.GRASSHOPPER),
            auroraSize(6, "Ninja",       -44, 44, 0, 120, BoardBrand.GRASSHOPPER),
        ),
        BoardBrand.SOILL to listOf(
            auroraSize(1, "8 x 12",  -48, 48, -16, 144, BoardBrand.SOILL),
            auroraSize(2, "12 x 12", -72, 72, -16, 144, BoardBrand.SOILL),
        ),
        BoardBrand.TOUCHSTONE to listOf(
            auroraSize(1, "Full Size", -72, 72, -12, 144, BoardBrand.TOUCHSTONE),
        ),
    )

    /** Bundled (pre-sync) product sizes for an interactive Aurora board, or
     *  empty for Kilter / MoonBoard (which have their own size paths). */
    fun auroraBundledSizes(brand: BoardBrand): List<BoardSize> = AURORA_BUNDLED_SIZES[brand] ?: emptyList()

    /**
     * Aurora product sizes that ship in the live /sync feed (is_listed=1, so
     * build_board_db keeps them) yet are NOT real commercial products — the
     * picker must hide them. Verified against the manufacturer pages AND the
     * aurora-re APK extract.
     *
     * - (TENSION, 10) "12 high x 16 wide": no 16-wide TB2 exists — the official
     *   page lists exactly 4 sizes, 12 wide max — and it is ABSENT from the APK
     *   extract. Its geometry (width span 184) is the exact linear extrapolation
     *   of TB2's width ladder (88 → 136 → 184), i.e. a templated Aurora phantom,
     *   not a measured wall. The cron drops it at source too (build_board_db.py);
     *   this guards installs whose chunk was cached before that ran.
     */
    val AURORA_EXCLUDED_SIZES: Set<Pair<BoardBrand, Int>> = setOf(
        BoardBrand.TENSION to 10,
    )

    /** True when [sizeId] for [brand] is a known non-product phantom to hide
     *  from the picker (see [AURORA_EXCLUDED_SIZES]). */
    fun isExcludedAuroraSize(brand: BoardBrand, sizeId: Int): Boolean =
        (brand to sizeId) in AURORA_EXCLUDED_SIZES

    /**
     * Physical W x H for Aurora product sizes whose catalogue name is DESCRIPTIVE
     * (Grasshopper Master/Ninja/GrandMaster) or otherwise dimensionless
     * (Touchstone "Full Size") rather than already a dimension. Standard product
     * dimensions — NOT computable from the hold-grid edges (boards use different
     * grid units + kickboard offsets), so kept as metadata keyed by the stable
     * (brand, product_size_id). Boards whose names already carry the dimension
     * (So iLL "8 x 12", Decoy "12 x 12", Tension TB2 "12 high x 12 wide") are
     * absent, as are config-style names (Tension TB1 Full Wall / Half Kickboard /
     * … are kickboard configs, not W x H).
     */
    private val AURORA_SIZE_DIMENSIONS: Map<Pair<BoardBrand, Int>, String> = mapOf(
        (BoardBrand.GRASSHOPPER to 4) to "12 x 12",  // GrandMaster
        (BoardBrand.GRASSHOPPER to 5) to "8 x 12",   // Master
        (BoardBrand.GRASSHOPPER to 6) to "8 x 10",   // Ninja
        (BoardBrand.TOUCHSTONE to 1) to "12 x 12",   // Full Size — single fixed wall
    )

    /** Picker label for an Aurora size: appends the physical W x H when the name
     *  is descriptive (Grasshopper "GrandMaster" → "GrandMaster (12 x 12)") and
     *  returns the name unchanged when it already conveys the dimension. Used at
     *  BOTH render sites (Settings/onboarding dialog + gym sheet) so the label is
     *  identical pre- and post-sync. */
    fun auroraSizeLabel(brand: BoardBrand, size: BoardSize): String =
        AURORA_SIZE_DIMENSIONS[brand to size.id.toInt()]?.let { "${size.name} ($it)" } ?: size.name

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
     *  - Kilter: true for every layout — the 0.1.4 display-only mirror toggle,
     *    restored by product decision (2026-06-10). It predates this gate and
     *    uses the geometric fallback (computeMirrorMapFromPlacements), which
     *    simply maps no partner for unpaired holds; removing it in 0.2.0 was an
     *    unintended upgrade regression.
     *  - Touchstone: its single layout is asymmetric → false.
     *  - MoonBoard: fixed-config board, no mirror concept → false.
     *  - CruxCoach Board: hold layout is user-built (per-wall hold map on the
     *    board, no canonical symmetric layout), so no mirror concept → false.
     *  - Map-only info-layer brands (Aurora / 12climb): no catalogue → false.
     *
     * Exhaustive over [BoardBrand] on purpose: a newly-added board must make a
     * deliberate mirrorability decision here rather than silently defaulting.
     */
    fun isLayoutMirrorable(brand: BoardBrand, layoutId: Int): Boolean = when (brand) {
        BoardBrand.TENSION -> auroraVariant(brand, layoutId)?.isMirrored ?: false
        BoardBrand.GRASSHOPPER, BoardBrand.DECOY, BoardBrand.SOILL -> true
        BoardBrand.KILTER -> true
        BoardBrand.TOUCHSTONE,
        BoardBrand.MOONBOARD,
        BoardBrand.CRUXCOACH,
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

    /**
     * One concrete board the stats hold-heatmap can be rendered on (FEAT-039):
     * a fully-resolved (brand, layout, size) plus a human label. The hold-id
     * spaces are disjoint across brands/layouts, so the heatmap is inherently
     * per-board — the selector lets the user pick exactly which board's grid to
     * overlay their ascents on. [brandWire] is the [BoardBrand.wireValue] (the
     * form the repository + ascent filter expect).
     */
    data class HeatmapBoardOption(
        val brandWire: String,
        val layoutId: Int,
        val sizeId: Int,
        val displayName: String,
    )

    /**
     * Enumerate the heatmap-renderable board options from the SAME picker model
     * the board picker uses (single source of truth — a new board needs no
     * extra wiring here): Kilter Original + Homewall (one representative size
     * each; hold positions barely shift with Kilter size so a per-size fan-out
     * isn't worth the menu length), each Aurora variant with its default size,
     * and each MoonBoard variant.
     *
     * Gated by [loggedBoards] — the set of (brandWire, layoutId) the user has
     * ACTUALLY logged on. The heatmap plots the user's own ascents, so it only
     * offers board TYPES with real logs: a brand whose catalogue is imported but
     * which the user has never climbed is NOT offered, and within a brand only
     * the logged layouts appear (e.g. Kilter Original but not Homewall). Multi-
     * layout boards (Kilter, Tension/Decoy variants, MoonBoard variants) match
     * by exact (brand, layout); single-layout boards have exactly one type so a
     * log on the brand (any layout) surfaces it. Pure (no I/O) → unit-testable;
     * the ViewModel derives the logged-board set from the logbook.
     *
     * Single-layout Aurora boards (Grasshopper / So iLL / Touchstone) have no
     * [AURORA_VARIANTS] entry to pin a layout — but the user's own logged ascent
     * already carries the layout id, so it's taken straight from [loggedBoards].
     * This deliberately avoids a catalogue lookup: resolving the layout via a
     * `GROUP BY` over the unindexed `climbs.board_brand` cost ~6s PER brand
     * (measured 12s for Grasshopper + So iLL together on first stats open).
     */
    fun heatmapBoardOptions(
        loggedBoards: Set<Pair<String, Int>>,
    ): List<HeatmapBoardOption> {
        val options = mutableListOf<HeatmapBoardOption>()
        // Brands the user has any log on — for single-layout boards, where
        // "brand has logs" is equivalent to "this one type has logs".
        val loggedBrands = loggedBoards.mapTo(mutableSetOf()) { it.first }

        // Kilter: two physically distinct boards (Original / Homewall), each
        // offered only if the user has a log on that specific layout. One
        // default size each is enough — placements barely move across Kilter
        // sizes, and offering all sizes would bloat the menu.
        if (Pair(BoardBrand.KILTER.wireValue, KILTER_ORIGINAL_LAYOUT) in loggedBoards) {
            options += HeatmapBoardOption(
                brandWire = BoardBrand.KILTER.wireValue,
                layoutId = KILTER_ORIGINAL_LAYOUT,
                sizeId = KILTER_DEFAULT_SIZE,
                displayName = "Kilter Original",
            )
        }
        if (Pair(BoardBrand.KILTER.wireValue, KILTER_HOMEWALL_LAYOUT) in loggedBoards) {
            options += HeatmapBoardOption(
                brandWire = BoardBrand.KILTER.wireValue,
                layoutId = KILTER_HOMEWALL_LAYOUT,
                sizeId = KILTER_HOMEWALL_DEFAULT_SIZE,
                displayName = "Kilter Homewall",
            )
        }

        // Aurora family. Variant boards (Tension / Decoy) expand to one option
        // per variant with the variant's default size; single-layout boards
        // (Grasshopper / So iLL / Touchstone) have no AURORA_VARIANTS entry, so
        // fall back to a single option on their default layout + largest size.
        for (brand in BoardBrand.entries) {
            if (!brand.usesAuroraProtocol || brand == BoardBrand.KILTER) continue
            val variants = auroraVariants(brand)
            if (variants.isNotEmpty()) {
                variants.forEach { v ->
                    // Multi-layout board: offer only the variants the user has
                    // a log on (TB1 vs TB2 Mirror vs TB2 Spray are distinct).
                    if (Pair(brand.wireValue, v.layoutId) in loggedBoards) {
                        options += HeatmapBoardOption(
                            brandWire = brand.wireValue,
                            layoutId = v.layoutId,
                            sizeId = v.defaultSizeId,
                            displayName = v.displayName,
                        )
                    }
                }
            } else if (brand.wireValue in loggedBrands) {
                // Single-layout board (Grasshopper / So iLL / Touchstone): there
                // is exactly one real layout, and the user's own logged ascent
                // already carries it — take it straight from loggedBoards (no
                // catalogue query). Default size = largest bundled size (matches
                // getDefaultProductSizeForBrand's area ordering). Layout 1 only as
                // a last-resort fallback (legacy null-layout rows).
                val loggedLayout = loggedBoards.firstOrNull { it.first == brand.wireValue }?.second
                val largest = auroraBundledSizes(brand)
                    .maxByOrNull { (it.edgeRight - it.edgeLeft) * (it.edgeTop - it.edgeBottom) }
                options += HeatmapBoardOption(
                    brandWire = brand.wireValue,
                    layoutId = loggedLayout ?: KILTER_ORIGINAL_LAYOUT,
                    sizeId = largest?.id?.toInt() ?: 1,
                    displayName = brand.displayName,
                )
            }
        }

        // MoonBoard variants the user has logged on — only when MoonBoard can
        // actually render a heatmap. Today it can't (hasHeatmap = false: the
        // canvas overlays Aurora hole coordinates, which MoonBoard lacks — its
        // holds live on a fixed 11×N lattice instead), so a MoonBoard entry
        // would be a dead-end "no hold heatmap" pick. We therefore omit it from
        // the selector for now; MoonBoard stats still appear in the all-boards
        // counts/pyramid. FEAT-040 adds a real MoonBoard hold-heatmap (lattice
        // overlay); flipping hasHeatmap re-includes the variants here. MoonBoard
        // variants ARE distinct boards (fundamentally different holds, unlike
        // Kilter sizes) — they are not collapsed, just not yet renderable.
        if (BoardBrand.MOONBOARD.hasHeatmap) {
            MoonBoardVariant.entries.forEach { v ->
                if (Pair(BoardBrand.MOONBOARD.wireValue, v.layoutId.toInt()) in loggedBoards) {
                    options += HeatmapBoardOption(
                        brandWire = BoardBrand.MOONBOARD.wireValue,
                        layoutId = v.layoutId.toInt(),
                        sizeId = 0, // MoonBoard variants are size-less (fixed config)
                        displayName = v.displayName,
                    )
                }
            }
        }

        return options
    }
}
