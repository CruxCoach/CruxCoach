package com.cruxcoach.app.setup

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant

/** One selectable physical board: what Android's board picker resolves to. [productSizeId] is 0 for MoonBoard. */
data class BoardOption(
    val brandWire: String,
    /** Product name for headers, e.g. "Kilter Board". */
    val brandTitle: String,
    val layoutId: Int,
    val productSizeId: Int,
    val layoutName: String,
    val sizeName: String,
)

/**
 * Board choices for a brand, read from the imported catalogue. Layout/product
 * pairing mirrors Android's `BoardConstants` (Kilter Original 1/1 and Homewall
 * 8/7, the three Tension variants, Decoy pinned to layout 2 because layout 1 is
 * an internal non-product layout).
 */
object BoardOptions {
    /** Brands whose catalogue the iOS sync can install today. Quantum import is not ported. */
    val downloadableBrands: List<BoardBrand> = listOf(
        BoardBrand.KILTER, BoardBrand.MOONBOARD, BoardBrand.TENSION, BoardBrand.GRASSHOPPER,
        BoardBrand.DECOY, BoardBrand.SOILL, BoardBrand.TOUCHSTONE,
    )

    private fun title(brand: BoardBrand): String = com.cruxcoach.app.ui.UiCodes.brandTitle(brand)

    private class Variant(val layoutId: Int, val productId: Long, val name: String)

    private fun variants(brand: BoardBrand): List<Variant> = when (brand) {
        BoardBrand.KILTER -> listOf(Variant(1, 1, "Kilter Board Original"), Variant(8, 7, "Kilter Board Homewall"))
        BoardBrand.TENSION -> listOf(
            Variant(9, 4, "Tension Board"),
            Variant(10, 5, "Tension Board 2 (Mirror)"),
            Variant(11, 5, "Tension Board 2 (Spray)"),
        )
        BoardBrand.DECOY -> listOf(Variant(2, 1, "Decoy"))
        BoardBrand.GRASSHOPPER -> listOf(Variant(1, 1, "Grasshopper"))
        BoardBrand.SOILL -> listOf(Variant(1, 1, "So iLL"))
        BoardBrand.TOUCHSTONE -> listOf(Variant(1, 1, "Touchstone"))
        else -> emptyList()
    }

    fun forBrand(brand: BoardBrand, repository: BoardRepository): List<BoardOption> {
        if (brand == BoardBrand.MOONBOARD) {
            return MoonBoardVariant.entries.map {
                BoardOption(brand.wireValue, title(brand), it.layoutId.toInt(), 0, it.displayName, "")
            }
        }
        val sizes: List<BoardSize> = repository.getSelectableProductSizesForBrand(brand.wireValue)
        val variants = variants(brand)
        val multiProduct = variants.map { it.productId }.distinct().size > 1
        return variants.flatMap { variant ->
            sizes.filter { !multiProduct && variants.size == 1 || it.productId == variant.productId }
                .map { BoardOption(brand.wireValue, title(brand), variant.layoutId, it.id.toInt(), variant.name, it.name) }
        }
    }
}
