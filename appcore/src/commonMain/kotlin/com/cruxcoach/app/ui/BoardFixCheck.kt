package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.BoardSelection
import com.cruxcoach.app.setup.BoardMismatchPolicy
import com.cruxcoach.app.setup.BoardSendIdentity
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.QuantumBoardModel

/**
 * Android BoardSendController's board-match guard (`resolveBoardConfigurationMismatch`) for the
 * places that light one climb: the climb detail and the Quantum layer rack. Before a send, the
 * board on the link and the configured board must fit the climb; otherwise the send is refused
 * with Android's error and "Fix board configuration" opens the guided picker with this evidence.
 * Nothing here changes the active board.
 */
class BoardFixCheck internal constructor(
    /** The catalogue climb's family and layout; null when the climb is not in the catalogue. */
    private val climb: (String) -> Pair<BoardBrand, Long?>?,
    private val activeBoard: () -> BoardSelection?,
    /** The family on the link while it is up; null when nothing is connected. */
    private val connectedBrand: () -> BoardBrand?,
    private val connectedQuantum: () -> QuantumBoardModel?,
) {
    /** The mismatch for lighting [climbUuid] now; inactive when it fits or the climb is unknown. */
    fun forClimb(climbUuid: String): BoardMismatchUi = try {
        BoardMismatchUi.of(resolve(climbUuid))
    } catch (e: Exception) {
        // A failed lookup must not block a send the controller's own fences still guard.
        BoardMismatchUi.NONE
    }

    /**
     * The send's LED-map check refused [climbUuid] ("climbOffBoard": none of its holds is on the
     * configured size): Android's `boardSizeMismatch`, which offers the climb's layout and leaves
     * the size to the user. Inactive when the climb is unknown.
     */
    fun sizeMismatch(climbUuid: String): BoardMismatchUi = try {
        identity(climbUuid)?.let { BoardMismatchUi.of(BoardMismatchPolicy.size(it)) } ?: BoardMismatchUi.NONE
    } catch (e: Exception) {
        BoardMismatchUi.NONE
    }

    internal fun resolve(climbUuid: String): com.cruxcoach.app.setup.BoardConfigurationMismatch? =
        identity(climbUuid)?.let(BoardMismatchPolicy::resolve)

    private fun identity(climbUuid: String): BoardSendIdentity? {
        val (brand, layoutId) = climb(climbUuid) ?: return null
        val active = activeBoard()
        return BoardSendIdentity(
            climbBrand = brand,
            climbLayoutId = layoutId,
            activeBrand = active?.let { BoardBrand.fromWireOrNull(it.boardBrand) },
            activeLayoutId = active?.layoutId?.toLong(),
            activeProductSizeId = active?.productSizeId,
            connectedBrand = connectedBrand(),
            connectedQuantumModel = connectedQuantum(),
        )
    }
}
