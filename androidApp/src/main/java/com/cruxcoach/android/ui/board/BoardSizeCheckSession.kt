package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardSizeCheckPoint
import com.cruxcoach.android.data.BoardSizeCheckPoints
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BoardSizeCheckStep { LOADING, NO_DATA, ASK, PICK_OTHER, CONFIRMED }

data class BoardSizeCheckState(
    val brand: BoardBrand? = null,
    /** The size the host treats as current: the saved board, or the picker's selection. */
    val currentSizeId: Int = 0,
    /** Sizes of the same product that have LED data; each can be tried. */
    val sizes: List<BoardSize> = emptyList(),
    /** The size under test. */
    val size: BoardSize? = null,
    val layoutId: Int? = null,
    val points: List<BoardSizeCheckPoint> = emptyList(),
    val step: BoardSizeCheckStep = BoardSizeCheckStep.LOADING,
    /** Sizes the user already answered "No" for or left for another one. */
    val tried: Set<Int> = emptySet(),
    /** The check's points are on the board and must be cleared when it ends. */
    val lit: Boolean = false,
    val busy: Boolean = false,
    val sendFailed: Boolean = false,
    val confirmedSizeId: Int? = null,
) {
    val confirmedChange: Boolean get() = confirmedSizeId != null && confirmedSizeId != currentSizeId
    val otherSizes: List<BoardSize> get() = sizes.filter { it.id != size?.id }
    val allOthersTried: Boolean get() = otherSizes.isNotEmpty() && otherSizes.all { it.id.toInt() in tried }
}

/**
 * The board-size check as a small state machine, free of Android so it is tested on its own.
 *
 * It never changes the user's board on its own: [confirm] is the only path to [Source.apply],
 * and only when the host asks for it and the confirmed size differs from the current one.
 * Calls are not thread-safe; the view model serialises them.
 */
class BoardSizeCheckSession(private val source: Source) {
    interface Source {
        /** Sizes of the product [sizeId] belongs to that have LED data, [sizeId] included. */
        suspend fun sizes(brand: BoardBrand, sizeId: Int): List<BoardSize>

        /** Layout whose holds are painted for [size]; [hint] is the host's current layout. */
        suspend fun layoutFor(brand: BoardBrand, size: BoardSize, hint: Int?): Int?

        suspend fun points(brand: BoardBrand, size: BoardSize, layoutId: Int?): List<BoardSizeCheckPoint>

        /** Connected to a board of [brand] and free to send. */
        fun ready(brand: BoardBrand): Boolean

        suspend fun light(brand: BoardBrand, leds: List<Pair<Int, Int>>): Boolean

        suspend fun clear(brand: BoardBrand): Boolean

        /** Makes [sizeId] the user's board. */
        suspend fun apply(brand: BoardBrand, layoutId: Int?, sizeId: Int)
    }

    private val mutableState = MutableStateFlow(BoardSizeCheckState())
    val state: StateFlow<BoardSizeCheckState> = mutableState.asStateFlow()
    private var layoutHint: Int? = null

    suspend fun open(brand: BoardBrand, sizeId: Int, layoutHint: Int?) {
        close()
        this.layoutHint = layoutHint
        mutableState.value = BoardSizeCheckState(brand = brand, currentSizeId = sizeId)
        val sizes = source.sizes(brand, sizeId)
        val size = sizes.firstOrNull { it.id.toInt() == sizeId } ?: sizes.firstOrNull()
        if (size == null) {
            mutableState.update { it.copy(step = BoardSizeCheckStep.NO_DATA) }
            return
        }
        mutableState.update { it.copy(sizes = sizes) }
        show(brand, size)
    }

    private suspend fun show(brand: BoardBrand, size: BoardSize) {
        val layout = source.layoutFor(brand, size, layoutHint)
        val points = source.points(brand, size, layout)
        mutableState.update {
            it.copy(
                size = size,
                layoutId = layout,
                points = points,
                step = if (points.isEmpty()) BoardSizeCheckStep.NO_DATA else BoardSizeCheckStep.ASK,
                sendFailed = false,
                confirmedSizeId = null,
            )
        }
        when {
            points.isNotEmpty() && source.ready(brand) -> light()
            // The previous size's points must not stay on the board under the new picture.
            mutableState.value.lit -> clearLit(brand)
        }
    }

    /** Lights the points, replacing whatever the board showed. Does nothing unless [Source.ready]. */
    suspend fun light() {
        val current = mutableState.value
        val brand = current.brand ?: return
        if (current.points.isEmpty() || !source.ready(brand)) return
        mutableState.update { it.copy(busy = true, sendFailed = false) }
        val sent = try {
            source.light(brand, current.points.map { it.led to BoardSizeCheckPoints.LIGHT_COLOR })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        // A failed send may still have left the previous frame up: keep it marked for clearing.
        mutableState.update { it.copy(busy = false, lit = sent || it.lit, sendFailed = !sent) }
    }

    /** Lights the points once the board is there, unless they already are. */
    suspend fun lightIfDark() {
        if (!mutableState.value.lit) light()
    }

    /**
     * "Yes, these holds are lit." Returns the confirmed size. With [applyChange] a size other
     * than the current one becomes the user's board.
     */
    suspend fun confirm(applyChange: Boolean): Int? {
        val current = mutableState.value
        val brand = current.brand ?: return null
        val id = current.size?.id?.toInt() ?: return null
        if (current.step != BoardSizeCheckStep.ASK) return null
        mutableState.update { it.copy(step = BoardSizeCheckStep.CONFIRMED, confirmedSizeId = id) }
        if (applyChange && id != current.currentSizeId) source.apply(brand, current.layoutId, id)
        return id
    }

    /** "No": offer the other sizes. */
    fun reject() {
        val size = mutableState.value.size ?: return
        mutableState.update { it.copy(step = BoardSizeCheckStep.PICK_OTHER, tried = it.tried + size.id.toInt()) }
    }

    /** Switches to [sizeId] and lights its points. */
    suspend fun test(sizeId: Int) {
        val current = mutableState.value
        val brand = current.brand ?: return
        val size = current.sizes.firstOrNull { it.id.toInt() == sizeId } ?: return
        current.size?.let { previous -> mutableState.update { it.copy(tried = it.tried + previous.id.toInt()) } }
        show(brand, size)
    }

    /** Leaves the board dark if the check lit it. Safe to call more than once. */
    suspend fun close() {
        val brand = mutableState.value.brand ?: return
        if (mutableState.value.lit) clearLit(brand)
    }

    private suspend fun clearLit(brand: BoardBrand) {
        try {
            source.clear(brand)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Nothing else to do: the next send replaces the frame anyway.
        }
        mutableState.update { it.copy(lit = false) }
    }
}
