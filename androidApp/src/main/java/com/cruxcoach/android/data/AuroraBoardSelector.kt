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

    suspend fun select(board: BoardBrand): Outcome {
        userPreferences.setBoardBrand(board.wireValue)
        return when (val result = withContext(Dispatchers.IO) { auroraCatalogueSync.sync(board) }) {
            is AuroraCatalogueSync.Result.Failed -> Outcome(Status.FAILED)
            is AuroraCatalogueSync.Result.AlreadyCurrent,
            is AuroraCatalogueSync.Result.Imported -> {
                val layout = withContext(Dispatchers.IO) {
                    boardRepository.getDefaultLayoutForBrand(board.wireValue)
                }
                val size = withContext(Dispatchers.IO) {
                    boardRepository.getDefaultProductSizeForBrand(board.wireValue)
                }
                if (layout != null && size != null) {
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
