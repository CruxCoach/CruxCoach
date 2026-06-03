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
    ): Outcome {
        userPreferences.setBoardBrand(board.wireValue)
        // A catalog-driven variant pick (e.g. Tension TB1 vs TB2 Mirror/Spray)
        // is persisted up-front, before the download: the static catalog is
        // sync-independent, so the selection sticks even if the catalogue
        // fetch fails — the climbs simply arrive on a later successful sync.
        // (Conservative: never leave a TB2 owner unable to select their board.)
        if (variant != null) {
            userPreferences.setBoardLayoutId(variant.layoutId)
            userPreferences.setBoardProductSizeId(variant.defaultSizeId)
        }
        return when (val result = withContext(Dispatchers.IO) { auroraCatalogueSync.sync(board) }) {
            is AuroraCatalogueSync.Result.Failed -> Outcome(
                status = Status.FAILED,
                layoutId = variant?.layoutId,
                productSizeId = variant?.defaultSizeId,
                productSizeName = variant?.displayName,
            )
            is AuroraCatalogueSync.Result.AlreadyCurrent,
            is AuroraCatalogueSync.Result.Imported -> {
                // With no explicit variant (single-layout boards) derive the
                // default layout + size from the freshly-loaded chunk.
                val layout = variant?.layoutId ?: withContext(Dispatchers.IO) {
                    boardRepository.getDefaultLayoutForBrand(board.wireValue)
                }
                val size: Pair<Int, String>? = if (variant != null) {
                    variant.defaultSizeId to variant.displayName
                } else withContext(Dispatchers.IO) {
                    boardRepository.getDefaultProductSizeForBrand(board.wireValue)
                }
                if (variant == null && layout != null && size != null) {
                    userPreferences.setBoardLayoutId(layout)
                    userPreferences.setBoardProductSizeId(size.first)
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
