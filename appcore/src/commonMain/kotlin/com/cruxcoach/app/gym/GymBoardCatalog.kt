package com.cruxcoach.app.gym

import com.cruxcoach.domain.board.BoardBrand

/**
 * The parts of Android's `BoardConstants` the gym picker needs, ported as
 * data: Aurora-family variants, their pre-sync sizes and the curated Kilter
 * size labels. Static on purpose (as on Android): a gym pick must resolve to
 * a real board even before that brand's catalogue is downloaded.
 */
object GymBoardCatalog {

    /** A multi-layout Aurora board's variant with the size applied by default. */
    class AuroraVariant(val layoutId: Int, val productId: Int, val displayName: String, val defaultSizeId: Int)

    /** A pre-sync product size: Aurora id and catalogue name. */
    class BundledSize(val id: Int, val name: String)

    private val variants: Map<BoardBrand, List<AuroraVariant>> = mapOf(
        BoardBrand.TENSION to listOf(
            AuroraVariant(9, 4, "Tension Board", 1),
            AuroraVariant(10, 5, "Tension Board 2 (Mirror)", 6),
            AuroraVariant(11, 5, "Tension Board 2 (Spray)", 6),
        ),
        // Layout 1 "Dots" is an internal non-product layout, so only layout 2 is offered.
        BoardBrand.DECOY to listOf(AuroraVariant(2, 1, "Decoy", 1)),
    )

    /** Layout and size a single-layout board resolves to (Android `AURORA_SINGLE_LAYOUT_DEFAULTS`). */
    private val singleLayoutDefaults: Map<BoardBrand, Pair<Int, Int>> = mapOf(
        BoardBrand.GRASSHOPPER to (1 to 4),
        BoardBrand.SOILL to (1 to 2),
        BoardBrand.TOUCHSTONE to (1 to 1),
    )

    private val bundledSizes: Map<BoardBrand, List<BundledSize>> = mapOf(
        BoardBrand.TENSION to listOf(
            BundledSize(1, "Full Wall"), BundledSize(2, "Half Kickboard"), BundledSize(3, "No Kickboard"),
            BundledSize(4, "Short"), BundledSize(5, "Short & Narrow"),
            BundledSize(6, "12 high x 12 wide"), BundledSize(7, "10 high x 12 wide"),
            BundledSize(8, "12 high x 8 wide"), BundledSize(9, "10 high x 8 wide"),
        ),
        BoardBrand.DECOY to listOf(BundledSize(1, "12 x 12"), BundledSize(2, "8 x 12"), BundledSize(3, "8 x 10")),
        BoardBrand.GRASSHOPPER to listOf(
            BundledSize(4, "GrandMaster"), BundledSize(5, "Master"), BundledSize(6, "Ninja"),
        ),
        BoardBrand.SOILL to listOf(BundledSize(1, "8 x 12"), BundledSize(2, "12 x 12")),
        BoardBrand.TOUCHSTONE to listOf(BundledSize(1, "Full Size")),
    )

    /** W x H for sizes whose name is descriptive rather than a dimension. */
    private val sizeDimensions: Map<Pair<BoardBrand, Int>, String> = mapOf(
        (BoardBrand.GRASSHOPPER to 4) to "12 x 12",
        (BoardBrand.GRASSHOPPER to 5) to "8 x 12",
        (BoardBrand.GRASSHOPPER to 6) to "8 x 10",
        (BoardBrand.TOUCHSTONE to 1) to "12 x 12",
    )

    /** The official Kilter app's wording per product size id. */
    private val kilterSizeLabels: Map<Long, String> = mapOf(
        14L to "10x7, no Kickboard",
        8L to "12x8, with Kickboard",
        10L to "12x12, with Kickboard",
        27L to "12x12, no Kickboard",
        7L to "14x12 Super Tall, with Kickboard",
        28L to "12x16 Super Wide, with Kickboard",
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

    fun auroraVariants(brand: BoardBrand): List<AuroraVariant> = variants[brand] ?: emptyList()

    fun auroraVariant(brand: BoardBrand, layoutId: Int): AuroraVariant? =
        variants[brand]?.firstOrNull { it.layoutId == layoutId }

    fun auroraBundledSizes(brand: BoardBrand): List<BundledSize> = bundledSizes[brand] ?: emptyList()

    fun auroraSizeLabel(brand: BoardBrand, size: BundledSize): String =
        sizeDimensions[brand to size.id]?.let { "${size.name} ($it)" } ?: size.name

    /** Layout id a single-layout board uses, or null for multi-layout ones. */
    fun singleLayoutDefault(brand: BoardBrand): Pair<Int, Int>? = singleLayoutDefaults[brand]

    /** Curated Kilter label, else [fallbackName] (Android `BoardConstants.sizeLabel`). */
    fun kilterSizeLabel(productSizeId: Long, fallbackName: String): String =
        kilterSizeLabels[productSizeId] ?: fallbackName
}
