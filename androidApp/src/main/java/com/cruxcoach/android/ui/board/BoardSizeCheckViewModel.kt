package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.BoardSizeCheckPoint
import com.cruxcoach.android.data.BoardSizeCheckPoints
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
class BoardSizeCheckViewModel internal constructor(
    private val connection: BoardBleConnection,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
    private val boardLocationRepository: BoardLocationRepository,
    /** Where the catalogue is read; tests read it in place. */
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(
        connection: BoardBleConnection,
        boardRepository: BoardRepository,
        userPreferences: UserPreferences,
        boardLocationRepository: BoardLocationRepository,
    ) : this(connection, boardRepository, userPreferences, boardLocationRepository, Dispatchers.IO)

    val connectionState: StateFlow<ConnectionState> = connection.connectionState
    val connectedBrand: StateFlow<BoardBrand?> = connection.connectedBoardBrand

    private val link = BoardSizeCheckConnection(connection)
    private val session = BoardSizeCheckSession(Source())
    val state: StateFlow<BoardSizeCheckState> = session.state
    private val lock = Mutex()

    /** Between ON_STOP and ON_START: a check that opens meanwhile goes dark at once. */
    private var inBackground = false

    init {
        viewModelScope.launch {
            connection.connectionState.collect { if (it == ConnectionState.DISCONNECTED) serial { session.boardGone() } }
        }
        viewModelScope.launch {
            // Checked under the lock: the check's own writes count while they are sent.
            connection.boardWrites.collect {
                serial {
                    link.noteWrites()
                    if (!link.showsCheckFrame()) session.replaced()
                }
            }
        }
    }

    /** Computed while collected: the climb screen collects it only while its ⋯ menu is open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val savedBoard: StateFlow<SavedBoardForCheck?> = combine(
        userPreferences.boardBrand,
        userPreferences.boardLayoutId,
        userPreferences.boardProductSizeId,
    ) { brand, layoutId, sizeId -> Triple(BoardBrand.fromWire(brand), layoutId, sizeId) }
        .distinctUntilChanged()
        .mapLatest { (brand, layoutId, sizeId) ->
            val sizeCount = if (brand.usesAuroraProtocol) {
                // Two sizes with LED data are enough to offer the check.
                withContext(io) { runCatching { sameProductSizes(brand, sizeId, atMost = 2).size }.getOrDefault(0) }
            } else {
                0
            }
            SavedBoardForCheck(brand, layoutId, sizeId, BoardSizeCheckPoints.offered(brand, sizeCount))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Starts a check; the board link stays up until [close]. */
    fun open(brand: BoardBrand, sizeId: Int, layoutHint: Int?) = serial {
        link.hold()
        session.open(brand, sizeId, layoutHint)
        if (inBackground) pauseNow()
    }

    fun light() = serial { session.light() }

    fun lightIfDark() = serial { session.lightIfDark() }

    fun reject() = serial { session.reject() }

    /** The app went to the background: the board goes dark and the link is let go until [resume]. */
    fun pause() {
        inBackground = true
        serial { pauseNow() }
    }

    fun resume() {
        inBackground = false
        serial {
            if (!state.value.paused) return@serial
            link.hold()
            session.resume()
        }
    }

    private suspend fun pauseNow() {
        if (state.value.brand == null || state.value.paused) return
        session.pause()
        link.release()
    }

    fun test(sizeId: Int) = serial { session.test(sizeId) }

    /** "Yes". With [apply] a different size becomes the user's board; [onConfirmed] gets the size. */
    fun confirm(apply: Boolean, onConfirmed: (Int) -> Unit) = serial {
        session.confirm(applyChange = apply)?.let(onConfirmed)
    }

    /** Clears what the check lit and lets the link go. Runs to the end even when the screen goes away. */
    fun close() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                lock.withLock {
                    session.close()
                    link.release()
                }
            }
        }
    }

    override fun onCleared() {
        // A close that never got to run must not keep the board connected for good.
        link.release()
    }

    private fun serial(block: suspend () -> Unit) {
        viewModelScope.launch { lock.withLock { block() } }
    }

    private fun sameProductSizes(brand: BoardBrand, sizeId: Int, atMost: Int = Int.MAX_VALUE): List<BoardSize> {
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
        return all.distinctBy { it.id }.asSequence()
            .filter { boardRepository.getPlacementLedMap(it.id.toInt(), wire).isNotEmpty() }
            .take(atMost)
            .toList()
    }

    /** Kilter walls per size in gyms, the bundled numbers until the gym list is on the phone. */
    private fun kilterSizeWalls(): Map<Int, Long> = try {
        boardLocationRepository.productSizeFrequency()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }.ifEmpty { BoardConstants.DEFAULT_SIZE_FREQUENCY }

    private inner class Source : BoardSizeCheckSession.Source {
        override suspend fun sizes(brand: BoardBrand, sizeId: Int): List<BoardSize> =
            withContext(io) {
                val sizes = sameProductSizes(brand, sizeId)
                sizeCheckOrder(brand, sizes, if (brand == BoardBrand.KILTER) kilterSizeWalls() else emptyMap())
            }

        override suspend fun layoutFor(brand: BoardBrand, size: BoardSize, hint: Int?): Int? =
            withContext(io) {
                if (brand == BoardBrand.KILTER) {
                    BoardConstants.layoutIdForProduct(size.productId.toInt())
                } else {
                    hint?.takeIf { it > 0 }
                        ?: boardRepository.getLayoutForProductSize(size.id.toInt(), brand.wireValue)?.toInt()
                }
            }

        override suspend fun holds(brand: BoardBrand, size: BoardSize, layoutId: Int?): List<BoardSizeCheckPoint> =
            withContext(io) {
                val wire = brand.wireValue
                val placements = layoutId?.let { boardRepository.getPlacementsForLayout(size.id.toInt(), it, wire) }
                    ?: boardRepository.getAllPlacements(wire)
                BoardSizeCheckPoints.candidates(placements, boardRepository.getPlacementLedMap(size.id.toInt(), wire))
            }

        override fun ready(brand: BoardBrand): Boolean = link.ready(brand)

        override suspend fun light(brand: BoardBrand, leds: List<Pair<Int, Int>>): Boolean = link.light(brand, leds)

        override suspend fun clear(brand: BoardBrand): Boolean = link.clear(brand)

        override suspend fun apply(brand: BoardBrand, layoutId: Int?, sizeId: Int) {
            val layout = layoutId ?: userPreferences.boardLayoutId.first()
            userPreferences.setBoardSelection(brand.wireValue, layout, sizeId)
        }
    }
}

/**
 * The order the check lists sizes in, fixed when it opens. Kilter: most common in gyms first, like
 * the picker, so "No" leads to the likeliest other size. Every other brand keeps the catalogue's
 * order: the gym list has no sizes for it, and Kilter's numbers say nothing about its size ids.
 */
internal fun sizeCheckOrder(brand: BoardBrand, sizes: List<BoardSize>, kilterWalls: Map<Int, Long>): List<BoardSize> =
    if (brand == BoardBrand.KILTER) sizes.sortedByDescending { kilterWalls[it.id.toInt()] ?: 0L } else sizes
