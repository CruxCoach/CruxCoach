package com.cruxcoach.android.ui.board

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.BoardSizeCheckPoint
import com.cruxcoach.android.data.BoardSizeCheckPoints
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.led.LedAnimationEngine
import com.cruxcoach.android.led.LedAnimationService
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    now: () -> Long,
    /** An LED effect will keep running in other apps, so the board stays connected in the background. */
    effectKeepsBoard: () -> Boolean,
) : ViewModel() {
    @Inject constructor(
        connection: BoardBleConnection,
        boardRepository: BoardRepository,
        userPreferences: UserPreferences,
        boardLocationRepository: BoardLocationRepository,
        ledEffects: LedAnimationEngine,
        @ApplicationContext context: Context,
    ) : this(
        connection, boardRepository, userPreferences, boardLocationRepository, Dispatchers.IO, SystemClock::elapsedRealtime,
        // As CruxCoachApp decides on the way out of the app.
        effectKeepsBoard = { ledEffects.shouldKeepRunningInBackground() && LedAnimationService.notificationsVisible(context) },
    )

    val connectionState: StateFlow<ConnectionState> = connection.connectionState
    val connectedBrand: StateFlow<BoardBrand?> = connection.connectedBoardBrand

    private val link = BoardSizeCheckConnection(connection, now = now, effectKeepsBoard = effectKeepsBoard)
    private val session = BoardSizeCheckSession(Source())
    val state: StateFlow<BoardSizeCheckState> = session.state
    private val lock = Mutex()

    /** Between ON_STOP and ON_START: nothing is lit, and a check that opens meanwhile starts paused. */
    private var inBackground = false

    /** The user's tap is connecting to the board let go in the background: it lights once the board is there. */
    private var lightWhenReady = false

    init {
        viewModelScope.launch {
            connection.connectionState.collect {
                serial {
                    link.noteLink()
                    if (it == ConnectionState.DISCONNECTED) {
                        // The tap's connect failed: the usual connect prompt takes over.
                        if (lightWhenReady) session.leaveAway()
                        lightWhenReady = false
                        session.boardGone()
                    }
                }
            }
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

    /** Whether the climb's ⋯ menu offers the check; a few small queries, stopped at two sizes. */
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

    /**
     * Starts a check; the board link stays up until [close]. In the background it lights nothing
     * and waits for [resume]; held meanwhile, as the user may be back before it has opened.
     */
    fun open(brand: BoardBrand, sizeId: Int, layoutHint: Int?) = serial {
        link.hold()
        session.open(brand, sizeId, layoutHint)
        if (inBackground) pauseNow()
    }

    /** The user's tap; back after a long time away it connects to the board first and lights once it is there. */
    fun light() = serial {
        if (state.value.away && link.connectPaused()) lightWhenReady = true else session.light()
    }

    fun lightIfDark() = serial {
        if (lightWhenReady) {
            lightWhenReady = false
            session.light()
        } else {
            session.lightIfDark()
        }
    }

    fun reject() = serial { session.reject() }

    /**
     * The app went to the background: the board goes dark and is disconnected until [resume],
     * unless a session or the relay holds the link. A gym board takes one phone at a time.
     */
    fun pause() {
        inBackground = true
        // Back already (a quick lock and unlock while the board was busy): nothing to do.
        serial { if (inBackground) pauseNow() }
    }

    fun resume() {
        inBackground = false
        serial {
            if (inBackground || !state.value.paused) return@serial
            // A frame sent while the pause's clear waited for the board is not lit over.
            link.noteWrites()
            if (link.sentOverSince()) session.replaced()
            session.resume(away = !link.resume())
        }
    }

    private suspend fun pauseNow() {
        val brand = state.value.brand ?: return
        if (state.value.paused) return
        lightWhenReady = false
        session.pause()
        // Back while the board was being cleared: the link stays, and the resume queued next lights again.
        if (!inBackground) return
        link.pause(brand)
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
                    lightWhenReady = false
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

        // Nothing goes to the board while the app is in the background, also from a check that
        // opens or switches sizes then.
        override fun ready(brand: BoardBrand): Boolean = !inBackground && link.ready(brand)

        override fun mayRelight(): Boolean = link.mayRelight()

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
