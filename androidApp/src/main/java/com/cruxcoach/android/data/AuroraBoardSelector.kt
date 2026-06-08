package com.cruxcoach.android.data

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared Aurora-family board selection (FEAT-031).
 *
 * Makes [select]'s board the active board: persists the brand, syncs that
 * board's catalogue, then derives + persists a sensible default layout +
 * product size so Browse/Detail work immediately. Used by every board picker
 * (Settings, Filter, Onboarding, sync card) so they behave identically — the
 * single source of truth for "select an Aurora board".
 */
@Singleton
class AuroraBoardSelector @Inject constructor(
    private val userPreferences: UserPreferences,
    private val auroraCatalogueSync: AuroraCatalogueSync,
    private val boardRepository: BoardRepository,
) {
    enum class Status { IMPORTED, ALREADY_CURRENT, FAILED }

    data class Outcome(
        val status: Status,
        val layoutId: Int? = null,
        val productSizeId: Int? = null,
        val productSizeName: String? = null,
    )

    suspend fun select(
        board: BoardBrand,
        variant: BoardConstants.AuroraVariant? = null,
        /** Explicit product size from the picker's size tier. Null = use the
         *  variant's default. Lets a TB2 owner pick 10x12 / 12x8 / 10x8 instead
         *  of being pinned to the 12x12 default (FEAT-031). */
        productSizeId: Int? = null,
    ): Outcome {
        val brand = board.wireValue
        // Effective size for a variant pick: the user's explicit choice, else
        // the variant's default.
        val variantSize = variant?.let { productSizeId ?: it.defaultSizeId }
        // A catalog-driven variant pick (e.g. Tension TB1 vs TB2 Mirror/Spray)
        // has a known (layout, size) up-front, so it's persisted ATOMICALLY
        // before the download: the static catalog is sync-independent, so the
        // selection sticks even if the catalogue fetch fails — the climbs
        // simply arrive on a later successful sync (the FAILED Outcome drives
        // a "sync failed" snackbar so the empty board is explained).
        // (Conservative: never leave a TB2 owner unable to select their board.)
        if (variant != null) {
            userPreferences.setBoardSelection(brand, variant.layoutId, variantSize!!)
        }
        return when (val result = withContext(Dispatchers.IO) { auroraCatalogueSync.sync(board) }) {
            is AuroraCatalogueSync.Result.Failed -> Outcome(
                status = Status.FAILED,
                layoutId = variant?.layoutId,
                productSizeId = variantSize,
                productSizeName = variant?.displayName,
            )
            is AuroraCatalogueSync.Result.AlreadyCurrent,
            is AuroraCatalogueSync.Result.Imported -> {
                // With no explicit variant (single-layout boards) derive the
                // default layout + size from the freshly-loaded chunk.
                val layout = variant?.layoutId ?: withContext(Dispatchers.IO) {
                    boardRepository.getDefaultLayoutForBrand(brand)
                }
                val size: Pair<Int, String>? = when {
                    variant != null -> variantSize!! to variant.displayName
                    // Single-layout board with an explicit size from the picker's
                    // size tier (e.g. Grasshopper Ninja / So iLL 8x12) — honour
                    // it instead of the largest-by-default.
                    productSizeId != null -> productSizeId to
                        (withContext(Dispatchers.IO) { boardRepository.getProductSize(productSizeId, brand) }?.name ?: "")
                    else -> withContext(Dispatchers.IO) {
                        boardRepository.getDefaultProductSizeForBrand(brand)
                    }
                }
                // A no-variant board is made the active board ONLY here, after a
                // successful sync derived a coherent (layout, size) — and the
                // brand is written atomically WITH that layout/size. Two
                // consequences fall out: a failed first-time sync never strands
                // the user on an empty catalogue (nothing was persisted up
                // front), and the brand is never active without a matching
                // layout/size (no brand/layout mismatch on a derive miss).
                if (variant == null && layout != null && size != null) {
                    userPreferences.setBoardSelection(brand, layout, size.first)
                }
                Outcome(
                    status = if (result is AuroraCatalogueSync.Result.AlreadyCurrent) {
                        Status.ALREADY_CURRENT
                    } else {
                        Status.IMPORTED
                    },
                    layoutId = layout,
                    productSizeId = size?.first,
                    productSizeName = size?.second,
                )
            }
        }
    }
}
