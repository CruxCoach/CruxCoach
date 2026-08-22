package com.cruxcoach.android.boardcell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Five answers, because "not confirmed" covers two opposite situations.
 *
 * Somebody standing at the wall waiting for holds to light up and somebody
 * whose send was refused are both "not on the board", and telling them apart
 * is the difference between waiting a moment and pressing the lamp again.
 */
class BoardProjectionConfidenceTest {

    private val onTheWall = BoardProjection("climb-a", 40)

    private fun cell(
        projection: BoardProjection? = onTheWall,
        known: Boolean = true,
        playlist: BoardPlaylistState = BoardPlaylistState(),
    ) = BoardCellSnapshot(
        BoardCellId("cell"), PhysicalBoardId("board"),
        epoch = 1, sequence = 0, controllerId = "controller", lineageId = "lineage",
        members = setOf("controller"), projection = projection, projectionKnown = known,
        playlist = playlist,
    ).withComputedHash()

    private fun playlistWithPendingFailure() = BoardPlaylistState(
        entries = listOf(BoardPlaylistEntry("e1", "climb-b", 40)),
        currentEntryId = "e1",
        pendingProjection = BoardPlaylistPendingProjection(
            "e1", "climb-b", 40, BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED,
        ),
    )

    @Test
    fun `a completed transport is the strongest claim a write-only board allows`() {
        val status = BoardProjectionConfidencePolicy.evaluate(cell())

        assertEquals(BoardProjectionConfidence.TRANSPORTED, status.confidence)
        assertEquals(onTheWall, status.projection)
    }

    /** Quantum answers what it holds; everything else is asked nothing. */
    @Test
    fun `a controller that names the climb confirms it`() {
        val status = BoardProjectionConfidencePolicy.evaluate(
            cell(), readbackNamesProjection = true, brandConfirmsByReadback = true,
        )

        assertEquals(BoardProjectionConfidence.CONTROLLER_CONFIRMED, status.confidence)
    }

    @Test
    fun `a board that cannot be asked is never reported as confirmed`() {
        val status = BoardProjectionConfidencePolicy.evaluate(
            cell(), readbackNamesProjection = true, brandConfirmsByReadback = false,
        )

        assertEquals(BoardProjectionConfidence.TRANSPORTED, status.confidence)
    }

    /** Asked, and it named something else — the write went out, no more than that. */
    @Test
    fun `a controller holding another climb does not confirm this one`() {
        val status = BoardProjectionConfidencePolicy.evaluate(
            cell(), readbackNamesProjection = false, brandConfirmsByReadback = true,
        )

        assertEquals(BoardProjectionConfidence.TRANSPORTED, status.confidence)
    }

    @Test
    fun `a write on its way is pending`() {
        val status = BoardProjectionConfidencePolicy.evaluate(
            cell(), inFlight = BoardProjection("climb-b", 40),
        )

        assertEquals(BoardProjectionConfidence.PENDING, status.confidence)
        assertEquals("climb-b", status.projection?.climbUuid)
    }

    /** A fresh attempt outranks the record of the last failed one. */
    @Test
    fun `a retry after a failure is pending, not failed`() {
        val status = BoardProjectionConfidencePolicy.evaluate(
            cell(playlist = playlistWithPendingFailure()),
            inFlight = BoardProjection("climb-b", 40),
        )

        assertEquals(BoardProjectionConfidence.PENDING, status.confidence)
    }

    /**
     * A failed send is a fact about the occurrence it happened to, not about
     * the wall — the wall is still showing whatever it was showing, and its
     * own confidence is unchanged. Collapsing the two is how a screen ends up
     * reporting a board as "failed" while it is lit and correct.
     */
    @Test
    fun `a write that did not land is failed for its occurrence, not for the wall`() {
        val status = BoardProjectionConfidencePolicy.evaluate(
            cell(playlist = playlistWithPendingFailure()),
        )

        assertEquals(BoardProjectionConfidence.TRANSPORTED, status.confidence)
        assertEquals("e1", status.pending?.entryId)
        assertEquals(
            BoardProjectionConfidence.FAILED,
            status.confidenceFor(BoardPlaylistEntry("e1", "climb-b", 40)),
        )
    }

    /** And the occurrence the wall really has keeps its own answer. */
    @Test
    fun `the occurrence on the wall is still transported while another failed`() {
        val status = BoardProjectionConfidencePolicy.evaluate(
            cell(playlist = playlistWithPendingFailure()),
        )

        assertEquals(
            BoardProjectionConfidence.TRANSPORTED,
            status.confidenceFor(BoardPlaylistEntry("e9", "climb-a", 40)),
        )
    }

    /** A queued occurrence nobody has asked for claims nothing at all. */
    @Test
    fun `an occurrence nobody has sent has no claim`() {
        val status = BoardProjectionConfidencePolicy.evaluate(cell())

        assertNull(status.confidenceFor(BoardPlaylistEntry("e5", "climb-z", 40)))
    }

    @Test
    fun `the occurrence being sent right now is pending`() {
        val status = BoardProjectionConfidencePolicy.evaluate(
            cell(), inFlight = BoardProjection("climb-b", 40),
        )

        assertEquals(
            BoardProjectionConfidence.PENDING,
            status.confidenceFor(BoardPlaylistEntry("e2", "climb-b", 40)),
        )
    }

    @Test
    fun `an external write nobody can name is unknown`() {
        val status = BoardProjectionConfidencePolicy.evaluate(cell(known = false))

        assertEquals(BoardProjectionConfidence.UNKNOWN, status.confidence)
        assertNull(status.projection)
    }

    @Test
    fun `a cell with nothing on the wall is unknown`() {
        val status = BoardProjectionConfidencePolicy.evaluate(cell(projection = null))

        assertEquals(BoardProjectionConfidence.UNKNOWN, status.confidence)
    }

    @Test
    fun `no cell at all is unknown`() {
        assertEquals(
            BoardProjectionStatus.UNKNOWN,
            BoardProjectionConfidencePolicy.evaluate(null),
        )
    }

    // ── What the ViewModel asks the controller ────────────────────────────

    @Test
    fun `a route list naming the climb is a confirmation`() {
        assertTrue(
            BoardProjectionConfidencePolicy.readbackNames(
                onTheWall, authoritative = true, heldRouteIds = listOf("other", "CLIMB-A"),
            ),
        )
    }

    @Test
    fun `a route list naming other climbs is not`() {
        assertFalse(
            BoardProjectionConfidencePolicy.readbackNames(
                onTheWall, authoritative = true, heldRouteIds = listOf("climb-b"),
            ),
        )
    }

    /** Never having been told is not the same as having been told "nothing". */
    @Test
    fun `a controller that has said nothing yet confirms nothing`() {
        assertFalse(
            BoardProjectionConfidencePolicy.readbackNames(
                onTheWall, authoritative = false, heldRouteIds = listOf("climb-a"),
            ),
        )
    }

    @Test
    fun `an empty wall confirms nothing`() {
        assertFalse(
            BoardProjectionConfidencePolicy.readbackNames(
                null, authoritative = true, heldRouteIds = listOf("climb-a"),
            ),
        )
    }

    // ── What the screen asks it ───────────────────────────────────────────

    @Test
    fun `the wall shows the selected occurrence`() {
        val status = BoardProjectionConfidencePolicy.evaluate(cell())

        assertTrue(status.shows(BoardPlaylistEntry("e1", "climb-a", 40)))
        assertFalse(status.shows(BoardPlaylistEntry("e2", "climb-a", 25)))
        assertFalse(status.shows(BoardPlaylistEntry("e3", "climb-z", 40)))
        assertFalse(status.shows(null))
    }

    /** Not knowing what is up there is never "your climb is up there". */
    @Test
    fun `an unknown wall shows nothing`() {
        val status = BoardProjectionConfidencePolicy.evaluate(cell(known = false))

        assertFalse(status.shows(BoardPlaylistEntry("e1", "climb-a", 40)))
    }
}
