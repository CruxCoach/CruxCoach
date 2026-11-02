package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.BoardSizeCheckPoint
import com.cruxcoach.android.data.BoardSizeCheckPoints
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The saved board, as the climb detail menu needs it to offer the size check. */
data class SavedBoardForCheck(
    val brand: BoardBrand,
    val layoutId: Int,
    val sizeId: Int,
    val offered: Boolean,
)

@HiltViewModel
class BoardSizeCheckViewModel @Inject constructor(
    private val connection: BoardBleConnection,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {
    val connectionState: StateFlow<ConnectionState> = connection.connectionState
    val connectedBrand: StateFlow<BoardBrand?> = connection.connectedBoardBrand

    private val session = BoardSizeCheckSession(Source())
    val state: StateFlow<BoardSizeCheckState> = session.state
    private val lock = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    val savedBoard: StateFlow<SavedBoardForCheck?> = combine(
        userPreferences.boardBrand,
        userPreferences.boardLayoutId,
        userPreferences.boardProductSizeId,
    ) { brand, layoutId, sizeId -> Triple(BoardBrand.fromWire(brand), layoutId, sizeId) }
        .distinctUntilChanged()
        .mapLatest { (brand, layoutId, sizeId) ->
            val sizeCount = if (brand.usesAuroraProtocol) {
                withContext(Dispatchers.IO) { runCatching { sameProductSizes(brand, sizeId).size }.getOrDefault(0) }
            } else {
                0
            }
            SavedBoardForCheck(brand, layoutId, sizeId, BoardSizeCheckPoints.offered(brand, sizeCount))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    fun open(brand: BoardBrand, sizeId: Int, layoutHint: Int?) = serial { session.open(brand, sizeId, layoutHint) }

    fun light() = serial { session.light() }

    fun lightIfDark() = serial { session.lightIfDark() }

    fun reject() = serial { session.reject() }

    fun test(sizeId: Int) = serial { session.test(sizeId) }

    /** "Yes". With [apply] a different size becomes the user's board; [onConfirmed] gets the size. */
    fun confirm(apply: Boolean, onConfirmed: (Int) -> Unit) = serial {
        session.confirm(applyChange = apply)?.let(onConfirmed)
    }

    /** Clears what the check lit. Runs to the end even when the screen goes away. */
    fun close() {
        viewModelScope.launch { withContext(NonCancellable) { lock.withLock { session.close() } } }
    }

    private fun serial(block: suspend () -> Unit) {
        viewModelScope.launch { lock.withLock { block() } }
    }

    private fun sameProductSizes(brand: BoardBrand, sizeId: Int): List<BoardSize> {
        val wire = brand.wireValue
        val all = if (brand == BoardBrand.KILTER) {
            val known = BoardConstants.KILTER_KNOWN_SIZES
            val size = boardRepository.getProductSize(sizeId, wire)
                ?: known.firstOrNull { it.id.toInt() == sizeId }
                ?: return emptyList()
            boardRepository.getAllProductSizes(size.productId, wire)
                .ifEmpty { known.filter { it.productId == size.productId } }
        } else {
            val size = boardRepository.getProductSize(sizeId, wire) ?: return emptyList()
            boardRepository.getSelectableProductSizesForBrand(wire)
                .filter { it.productId == size.productId && !BoardConstants.isExcludedAuroraSize(brand, it.id.toInt()) } +
                size
        }
        return all.distinctBy { it.id }
            .filter { boardRepository.getPlacementLedMap(it.id.toInt(), wire).isNotEmpty() }
    }

    private inner class Source : BoardSizeCheckSession.Source {
        override suspend fun sizes(brand: BoardBrand, sizeId: Int): List<BoardSize> =
            withContext(Dispatchers.IO) { sameProductSizes(brand, sizeId) }

        override suspend fun layoutFor(brand: BoardBrand, size: BoardSize, hint: Int?): Int? =
            withContext(Dispatchers.IO) {
                if (brand == BoardBrand.KILTER) {
                    BoardConstants.layoutIdForProduct(size.productId.toInt())
                } else {
                    hint?.takeIf { it > 0 }
                        ?: boardRepository.getLayoutForProductSize(size.id.toInt(), brand.wireValue)?.toInt()
                }
            }

        override suspend fun points(brand: BoardBrand, size: BoardSize, layoutId: Int?): List<BoardSizeCheckPoint> =
            withContext(Dispatchers.IO) {
                val wire = brand.wireValue
                val placements = layoutId?.let { boardRepository.getPlacementsForLayout(size.id.toInt(), it, wire) }
                    ?: boardRepository.getAllPlacements(wire)
                BoardSizeCheckPoints.pick(placements, boardRepository.getPlacementLedMap(size.id.toInt(), wire))
            }

        override fun ready(brand: BoardBrand): Boolean =
            connection.connectionState.value == ConnectionState.CONNECTED &&
                connection.connectedBoardBrand.value == brand

        override suspend fun light(brand: BoardBrand, leds: List<Pair<Int, Int>>): Boolean =
            connection.sendRawLeds(leds, expectedBrand = brand)

        override suspend fun clear(brand: BoardBrand): Boolean = connection.clearBoard(expectedBrand = brand)

        override suspend fun apply(brand: BoardBrand, layoutId: Int?, sizeId: Int) {
            val layout = layoutId ?: userPreferences.boardLayoutId.first()
            userPreferences.setBoardSelection(brand.wireValue, layout, sizeId)
        }
    }
}
