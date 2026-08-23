package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.domain.board.QuantumLaneBadgeKind
import com.cruxcoach.domain.board.QuantumLaneEligibility
import com.cruxcoach.domain.board.QuantumLaneOccupancy
import com.cruxcoach.domain.board.QuantumLaneSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backlog's half of the lane model.
 *
 * What is being pinned down here is mostly restraint: what the list will
 * *not* do. It will not move somebody's climb to another lane because the
 * wall changed, it will not claim compatibility for a climb it does not have,
 * and it will not report a lane as free when its own occurrence is sitting in
 * it — which is true, uninteresting, and hides the only fact on the row that
 * anybody was looking for.
 */
class BoardPlaylistLanePolicyTest {

    private val palette = BoardLayerManager.LAYER_COLORS

    private fun lane(
        index: Int,
        source: QuantumLaneSource = QuantumLaneSource.CONFIRMED,
        placements: Set<Int>? = emptySet(),
        entryId: String? = null,
    ) = QuantumLaneOccupancy(
        lane = index,
        source = source,
        routeKey = "route-$index",
        placements = placements,
        entryId = entryId,
    )

    private fun free(index: Int) = QuantumLaneOccupancy(lane = index)

    private fun rowLanes(
        rack: List<QuantumLaneOccupancy>,
        entryId: String = "e1",
        placements: Set<Int>? = setOf(500),
        assignedLane: Int? = null,
    ) = BoardPlaylistLanePolicy.rowLanes(
        rack = rack,
        maxLanes = 4,
        entryId = entryId,
        placements = placements,
        assignedLane = assignedLane,
        palette = palette,
    )

    // ── Suggestion and assignment ─────────────────────────────────────────

    @Test
    fun `an explicit assignment wins over the app's own preference`() {
        val rack = listOf(free(0), free(1), free(2), free(3))

        val lanes = rowLanes(rack, assignedLane = 3)

        assertEquals(3, lanes.assignedLane)
        assertEquals(3, lanes.suggestedLane, "the app does not overrule a decision")
    }

    @Test
    fun `an assignment the rack has invalidated is surfaced, not rerouted`() {
        val rack = listOf(lane(0, placements = setOf(500)), free(1), free(2), free(3))

        val lanes = rowLanes(rack, assignedLane = 1)

        // Lane 1 now collides with lane 0. The row keeps the preference — the
        // person made it — and offers no replacement of its own.
        assertEquals(1, lanes.assignedLane)
        assertNull(lanes.suggestedLane, "a quiet reassignment is a change nobody asked for")
        assertEquals(
            QuantumLaneEligibility.HOLD_CONFLICT,
            BoardPlaylistLanePolicy.assignmentConflict(rack, 4, setOf(500), 1, palette),
        )
    }

    @Test
    fun `a resend is offered the lane the occurrence is already in`() {
        val rack = listOf(free(0), lane(1, placements = setOf(500), entryId = "e1"), free(2), free(3))

        val lanes = rowLanes(rack, entryId = "e1", placements = setOf(500))

        assertEquals(1, lanes.onBoardLane)
        assertEquals(1, lanes.suggestedLane, "a resend belongs where it was")
    }

    @Test
    fun `with nothing assigned the emptiest eligible lane is offered`() {
        val rack = listOf(
            lane(0, placements = setOf(1)),
            lane(1, source = QuantumLaneSource.PREVIEW, placements = setOf(2)),
            free(2),
            free(3),
        )

        assertEquals(2, rowLanes(rack).suggestedLane)
    }

    @Test
    fun `no eligible lane means no suggestion and an entry that stays put`() {
        val rack = (0..3).map { lane(it, placements = setOf(500)) }

        val lanes = rowLanes(rack)

        assertFalse(lanes.hasEligibleLane)
        assertNull(lanes.suggestedLane)
    }

    // ── Unknown propagates ────────────────────────────────────────────────

    @Test
    fun `a climb this device does not have reads unknown on every lane`() {
        val rack = listOf(free(0), free(1), free(2), free(3))

        val lanes = rowLanes(rack, placements = null)

        assertTrue(lanes.unknown)
        assertTrue(lanes.eligibleLanes.isEmpty())
        assertEquals(4, lanes.badges.size)
        assertTrue(lanes.badges.all { it.kind == QuantumLaneBadgeKind.UNKNOWN })
        assertEquals(
            QuantumLaneEligibility.UNKNOWN_LAYER,
            BoardPlaylistLanePolicy.assignmentConflict(rack, 4, null, 0, palette),
        )
    }

    @Test
    fun `an unresolvable layer on the wall blocks every lane`() {
        val rack = listOf(
            free(0), free(1), free(2),
            QuantumLaneOccupancy(lane = -1, source = QuantumLaneSource.FOREIGN, placements = null),
        )

        val lanes = rowLanes(rack)

        assertTrue(lanes.unknown)
        assertTrue(lanes.eligibleLanes.isEmpty())
    }

    // ── The occurrence's own lane wins over its compatibility ─────────────

    @Test
    fun `a row on the wall says so instead of saying it would fit`() {
        val rack = listOf(lane(0, placements = setOf(500), entryId = "e1"), free(1), free(2), free(3))

        val lanes = rowLanes(rack, entryId = "e1")

        assertEquals(QuantumLaneBadgeKind.ON_BOARD, lanes.badges.first { it.lane == 0 }.kind)
        assertEquals(0, lanes.onBoardLane)
    }

    @Test
    fun `a different occurrence of the same climb is a different row`() {
        // Two entries, one climb, one hold set. The one on the wall reads
        // ON_BOARD; the repeat reads as what it is — a conflict with lane 0,
        // because sending it anywhere else would collide with itself.
        val rack = listOf(lane(0, placements = setOf(500), entryId = "zombie-1"), free(1), free(2), free(3))

        val first = rowLanes(rack, entryId = "zombie-1")
        val second = rowLanes(rack, entryId = "zombie-2")

        assertEquals(QuantumLaneBadgeKind.ON_BOARD, first.badges.first { it.lane == 0 }.kind)
        assertEquals(QuantumLaneBadgeKind.COMPATIBLE, second.badges.first { it.lane == 0 }.kind)
        // One hold in the way — the repeat is exactly as near-compatible with
        // itself as any other single-hold collision, and just as unsendable.
        assertEquals(QuantumLaneBadgeKind.NEAR, second.badges.first { it.lane == 1 }.kind)
        assertEquals("L2 ·1", second.badges.first { it.lane == 1 }.label)
        assertEquals(listOf(0), second.eligibleLanes)
    }

    // ── Lane cards ────────────────────────────────────────────────────────

    @Test
    fun `a lane whose occurrence left the list is still lit and marked off-list`() {
        val rack = listOf(
            lane(0, placements = setOf(1), entryId = "gone"),
            lane(1, placements = setOf(2), entryId = "here"),
            free(2),
            free(3),
        )

        val cards = BoardPlaylistLanePolicy.laneCards(rack, 4, setOf("here")) { null }

        assertFalse(cards.first { it.lane == 0 }.onList)
        assertTrue(cards.first { it.lane == 1 }.onList)
        // Both are still confirmed on the controller. Nothing here removes a
        // layer because a list changed.
        assertEquals(QuantumLaneSource.CONFIRMED, cards.first { it.lane == 0 }.source)
    }

    @Test
    fun `a free lane is not reported as off-list`() {
        val cards = BoardPlaylistLanePolicy.laneCards(
            listOf(free(0), free(1), free(2), free(3)), 4, emptySet(),
        ) { null }

        assertTrue(cards.all { it.onList })
        assertTrue(cards.all { it.source == QuantumLaneSource.FREE })
    }

    @Test
    fun `a lane whose holds are unknown says so on its card`() {
        val rack = listOf(lane(0, placements = null, entryId = null), free(1), free(2), free(3))

        val cards = BoardPlaylistLanePolicy.laneCards(rack, 4, emptySet()) { "Climb" }

        assertFalse(cards.first { it.lane == 0 }.holdsKnown)
        assertTrue(cards.first { it.lane == 1 }.holdsKnown)
    }

    // ── The capability gate ───────────────────────────────────────────────

    @Test
    fun `an unavailable lane state draws nothing at all`() {
        val state = BoardPlaylistLaneState()

        assertFalse(state.available)
        assertEquals(0, state.maxLanes)
        assertTrue(state.lanes.isEmpty())
        assertNull(state.blocked)
        assertFalse(state.commitAllowed)
    }

    @Test
    fun `an empty row lane set is the inert default`() {
        val lanes = BoardPlaylistRowLanes()

        assertTrue(lanes.badges.isEmpty())
        assertNull(lanes.assignedLane)
        assertNull(lanes.suggestedLane)
        assertFalse(lanes.hasEligibleLane)
        assertFalse(lanes.unknown)
    }
}
