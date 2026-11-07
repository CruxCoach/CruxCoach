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

/** What the check shows for one size: its layout, the holds it lights and its look-alikes. */
data class BoardSizeCheckSizePlan(
    val size: BoardSize,
    val layoutId: Int?,
    val points: List<BoardSizeCheckPoint>,
    /** Sizes on which [points] light the very same holds; see [com.cruxcoach.android.data.BoardSizeCheckPlan]. */
    val lookAlikes: Set<Int>,
)

data class BoardSizeCheckState(
    val brand: BoardBrand? = null,
    /** The size the host treats as current: the saved board, or the picker's selection. */
    val currentSizeId: Int = 0,
    /** Sizes of the same product that have LED data. */
    val sizes: List<BoardSize> = emptyList(),
    val plans: Map<Int, BoardSizeCheckSizePlan> = emptyMap(),
    /** The size under test. */
    val size: BoardSize? = null,
    val layoutId: Int? = null,
    val points: List<BoardSizeCheckPoint> = emptyList(),
    val step: BoardSizeCheckStep = BoardSizeCheckStep.LOADING,
    /** Sizes the user already answered "No" for or left for another one. */
    val tried: Set<Int> = emptySet(),
    /** Sizes the user answered "No" for. */
    val rejected: Set<Int> = emptySet(),
    /** The points of the size under test are on the connected board: only then an answer counts. */
    val lit: Boolean = false,
    /** Something else was sent to the board since the points were lit. */
    val replaced: Boolean = false,
    /** Something the check lit may still be on the board and must be cleared when it ends. */
    val onBoard: Boolean = false,
    val busy: Boolean = false,
    val sendFailed: Boolean = false,
    val confirmedSizeId: Int? = null,
) {
    val confirmedChange: Boolean get() = confirmedSizeId != null && confirmedSizeId != currentSizeId

    /** Sizes that can be tried now: each one's look-alikes are ruled out already. */
    val otherSizes: List<BoardSize> get() = sizes.filter { it.id != size?.id && testable(it.id.toInt()) }
    val allOthersTried: Boolean get() = otherSizes.isNotEmpty() && otherSizes.all { it.id.toInt() in tried }

    /** "Yes" means the size under test only once no look-alike is left. */
    val canConfirm: Boolean get() = size?.let { testable(it.id.toInt()) } == true

    /** The current size while the check still shows the larger kit it had to start with. */
    val startsWithLookAlike: BoardSize?
        get() = sizes.firstOrNull { it.id.toInt() == currentSizeId }
            ?.takeIf { rejected.isEmpty() && size != null && size.id != it.id }

    fun testable(sizeId: Int): Boolean {
        val plan = plans[sizeId] ?: return false
        return plan.points.isNotEmpty() && plan.lookAlikes.all { it in rejected }
    }
}

/**
 * The board-size check as a small state machine, free of Android so it is tested on its own.
 *
 * It never changes the user's board on its own: [confirm] is the only path to [Source.apply],
 * and only when the host asks for it and the confirmed size differs from the current one. It
 * never confirms a size the lit holds cannot tell apart from another one that is still open.
 * Calls are not thread-safe; the view model serialises them.
 */
class BoardSizeCheckSession(private val source: Source) {
    interface Source {
        /** Sizes of the product [sizeId] belongs to that have LED data, [sizeId] included. */
        suspend fun sizes(brand: BoardBrand, sizeId: Int): List<BoardSize>

        /** Layout whose holds are painted for [size]; [hint] is the host's current layout. */
        suspend fun layoutFor(brand: BoardBrand, size: BoardSize, hint: Int?): Int?

        /** Every hold of [size] on [layoutId] that has an LED. */
        suspend fun holds(brand: BoardBrand, size: BoardSize, layoutId: Int?): List<BoardSizeCheckPoint>

        /** Connected to a board of [brand] and free to send. */
        fun ready(brand: BoardBrand): Boolean

        suspend fun light(brand: BoardBrand, leds: List<Pair<Int, Int>>): Boolean

        /** Clears what the check lit, also when the link dropped since. */
        suspend fun clear(brand: BoardBrand): Boolean

        /** Makes [sizeId] the user's board. */
        suspend fun apply(brand: BoardBrand, layoutId: Int?, sizeId: Int)
    }

    private val mutableState = MutableStateFlow(BoardSizeCheckState())
    val state: StateFlow<BoardSizeCheckState> = mutableState.asStateFlow()

    suspend fun open(brand: BoardBrand, sizeId: Int, layoutHint: Int?) {
        if (mutableState.value.brand != brand) close()
        // A frame an earlier check of this board could not clear stays marked: lighting
        // replaces it, closing clears it.
        val leftOver = mutableState.value.onBoard && mutableState.value.brand == brand
        mutableState.value = BoardSizeCheckState(brand = brand, currentSizeId = sizeId, onBoard = leftOver)
        val sizes = source.sizes(brand, sizeId)
        val current = sizes.firstOrNull { it.id.toInt() == sizeId } ?: sizes.firstOrNull()
        if (current == null) {
            mutableState.update { it.copy(step = BoardSizeCheckStep.NO_DATA) }
            return
        }
        val plans = plans(brand, sizes, layoutHint)
        mutableState.update { it.copy(sizes = sizes, plans = plans) }
        // A size its holds cannot tell apart from a larger kit starts with that kit: once the
        // kit is ruled out, the smaller size can be tested.
        val start = (listOf(current.id.toInt()) + plans[current.id.toInt()]?.lookAlikes.orEmpty().sorted())
            .firstOrNull { mutableState.value.testable(it) }
        if (start == null) {
            mutableState.update { it.copy(step = BoardSizeCheckStep.NO_DATA) }
            return
        }
        show(brand, plans.getValue(start))
    }

    private suspend fun plans(brand: BoardBrand, sizes: List<BoardSize>, layoutHint: Int?): Map<Int, BoardSizeCheckSizePlan> {
        val holds = sizes.associate { size ->
            val layout = source.layoutFor(brand, size, layoutHint)
            size.id.toInt() to (layout to source.holds(brand, size, layout))
        }
        return sizes.associate { size ->
            val id = size.id.toInt()
            val (layout, own) = holds.getValue(id)
            val plan = BoardSizeCheckPoints.plan(own, holds.filterKeys { it != id }.mapValues { it.value.second })
            id to BoardSizeCheckSizePlan(size, layout, plan.points, plan.lookAlikes)
        }
    }

    private suspend fun show(brand: BoardBrand, plan: BoardSizeCheckSizePlan) {
        mutableState.update {
            it.copy(
                size = plan.size,
                layoutId = plan.layoutId,
                points = plan.points,
                step = if (plan.points.isEmpty()) BoardSizeCheckStep.NO_DATA else BoardSizeCheckStep.ASK,
                lit = false,
                replaced = false,
                sendFailed = false,
                confirmedSizeId = null,
            )
        }
        // Without a board the previous size's points stay until it is back (lighting replaces
        // them) or the check ends (closing clears them).
        if (plan.points.isNotEmpty() && source.ready(brand)) light()
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
        mutableState.update {
            it.copy(busy = false, lit = sent, replaced = false, onBoard = sent || it.onBoard, sendFailed = !sent)
        }
    }

    /**
     * Lights the points once the board is there, unless they already are or another frame
     * replaced them: the board turns ready again after every send, also another sender's.
     */
    suspend fun lightIfDark() {
        val current = mutableState.value
        if (!current.lit && !current.replaced) light()
    }

    /**
     * The link dropped: what the board shows is unknown once it is back, so the points are lit
     * again then. What the check lit still has to be cleared when it ends.
     */
    fun boardGone() {
        mutableState.update { it.copy(lit = false) }
    }

    /**
     * Another frame replaced the points (a session, the relay, another screen): no answer counts
     * until the user lights them again. Not relit on its own, so the check does not fight a sender.
     */
    fun replaced() {
        mutableState.update { if (it.lit) it.copy(lit = false, replaced = true) else it }
    }

    /**
     * "Yes, these holds are lit." Returns the confirmed size. With [applyChange] a size other
     * than the current one becomes the user's board. Refused while a look-alike is still open
     * or the points are not known to be on the board.
     */
    suspend fun confirm(applyChange: Boolean): Int? {
        val current = mutableState.value
        val brand = current.brand ?: return null
        val id = current.size?.id?.toInt() ?: return null
        if (current.step != BoardSizeCheckStep.ASK || !current.lit || !current.canConfirm) return null
        mutableState.update { it.copy(step = BoardSizeCheckStep.CONFIRMED, confirmedSizeId = id) }
        if (applyChange && id != current.currentSizeId) source.apply(brand, current.layoutId, id)
        return id
    }

    /**
     * "No": offer the other sizes. Only while the points are on the board: a "No" to holds that
     * never lit would rule out a size the board was not asked about, and unlock its look-alikes.
     */
    fun reject() {
        val current = mutableState.value
        val size = current.size ?: return
        if (current.step != BoardSizeCheckStep.ASK || !current.lit) return
        val id = size.id.toInt()
        mutableState.update { it.copy(step = BoardSizeCheckStep.PICK_OTHER, tried = it.tried + id, rejected = it.rejected + id) }
    }

    /** Switches to [sizeId] and lights its points. Only sizes that can be told apart now. */
    suspend fun test(sizeId: Int) {
        val current = mutableState.value
        val brand = current.brand ?: return
        val plan = current.plans[sizeId]?.takeIf { current.testable(sizeId) } ?: return
        current.size?.let { previous -> mutableState.update { it.copy(tried = it.tried + previous.id.toInt()) } }
        show(brand, plan)
    }

    /** Leaves the board dark if the check lit it. Safe to call more than once. */
    suspend fun close() {
        val brand = mutableState.value.brand ?: return
        val onBoard = mutableState.value.onBoard
        // A sheet opened while the board is cleared shows nothing of this check.
        mutableState.value = BoardSizeCheckState(brand = brand, onBoard = onBoard)
        if (!onBoard) return
        val cleared = try {
            source.clear(brand)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        // Not cleared: the next check of this board keeps it marked and tries again.
        mutableState.update { it.copy(onBoard = !cleared) }
    }
}
