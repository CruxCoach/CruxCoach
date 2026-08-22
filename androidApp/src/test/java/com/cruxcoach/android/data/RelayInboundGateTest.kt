package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A relay guest is the one sender who is not standing in front of the wall.
 *
 * They get the same sequencer as everybody else, the checks nobody local needs
 * — not the same climb twice, not faster than a wall can be used, not a climb
 * this board cannot show — and one thing the local paths get for free: an
 * identity for the whole operation, decided once, before anything is written.
 */
class RelayInboundGateTest {

    private val otherClimb = "11111111-2222-3333-4444-555555555555"

    /** Deterministic ids, so a reuse is visible as a reuse. */
    private class Ids {
        private var next = 0
        val mint: () -> String = { "id-${next++}" }
    }

    private fun gate() = RelayInboundGate()

    private fun RelayInboundGate.send(
        mode: RelayInboundClimbMode = RelayInboundClimbMode.PROJECT_NOW,
        climb: String? = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        angle: Int? = 40,
        climbBrand: BoardBrand? = BoardBrand.KILTER,
        connectedBrand: BoardBrand? = BoardBrand.KILTER,
        nowMs: Long,
        fingerprint: String = "guest-a|1234",
        climbLayoutId: Long? = null,
        connectedLayoutId: Long? = null,
        connectedAngle: Int? = null,
        ids: Ids = Ids(),
    ) = evaluate(
        mode = mode,
        climbUuid = climb,
        angle = angle,
        climbBrand = climbBrand,
        connectedBrand = connectedBrand,
        nowMs = nowMs,
        fingerprint = fingerprint,
        climbLayoutId = climbLayoutId,
        connectedLayoutId = connectedLayoutId,
        connectedAngle = connectedAngle,
        newId = ids.mint,
    )

    private fun operationOf(decision: RelayInboundGate.Decision): RelayInboundGate.Operation =
        when (decision) {
            is RelayInboundGate.Decision.ProjectNow -> decision.operation
            is RelayInboundGate.Decision.AppendToEnd -> decision.operation
            is RelayInboundGate.Decision.Refused -> error("refused: ${decision.reason}")
        }

    @Test
    fun `the default puts an incoming climb on the wall`() {
        val decision = gate().send(nowMs = 1_000)
        assertTrue(decision is RelayInboundGate.Decision.ProjectNow)
    }

    @Test
    fun `the queue setting leaves the wall alone`() {
        val decision = gate().send(mode = RelayInboundClimbMode.APPEND_TO_END, nowMs = 1_000)
        assertTrue(decision is RelayInboundGate.Decision.AppendToEnd)
    }

    /** Both ids exist before anything is written, and they are not the same id. */
    @Test
    fun `an accepted write is one operation with one occurrence`() {
        val operation = operationOf(gate().send(nowMs = 1_000, ids = Ids()))

        assertEquals("id-0", operation.operationId)
        assertEquals("id-1", operation.entryId)
        assertEquals("guest-a|1234", operation.fingerprint)
    }

    /**
     * The retry case the relay exists to survive: the same bytes from the same
     * guest are the same operation, so the wall is written once and the list
     * gains one occurrence however many times the guest's app re-sends.
     */
    @Test
    fun `a repeat of a write still in flight is the same operation`() {
        val gate = gate()
        val ids = Ids()
        val first = operationOf(gate.send(nowMs = 1_000, ids = ids))

        val again = operationOf(gate.send(nowMs = 1_050, ids = ids))

        assertEquals(first, again)
    }

    /**
     * A failed write is a retry, not a duplicate. Refusing it — which is what
     * this did before — left a guest whose climb never lit with no way to try
     * again until the window had passed.
     */
    @Test
    fun `a retry after a failed write reuses the same ids`() {
        val gate = gate()
        val ids = Ids()
        val first = operationOf(gate.send(nowMs = 1_000, ids = ids))
        gate.markFailed(first, 1_100)

        val retry = operationOf(gate.send(nowMs = 1_200, ids = ids))

        assertEquals(first, retry)
    }

    /** Guest apps re-send; once it has landed, the copy is the same intention. */
    @Test
    fun `the same climb again inside the window is one send`() {
        val gate = gate()
        val ids = Ids()
        val first = operationOf(gate.send(nowMs = 1_000, ids = ids))
        gate.markLanded(first, 1_000)

        assertEquals(
            RelayInboundGate.Decision.Refused(RelayInboundGate.Refusal.DUPLICATE),
            gate.send(nowMs = 3_000, ids = ids),
        )
    }

    @Test
    fun `the same climb after the window is a genuine second go`() {
        val gate = gate()
        val ids = Ids()
        val first = operationOf(gate.send(nowMs = 1_000, ids = ids))
        gate.markLanded(first, 1_000)

        val second = operationOf(gate.send(nowMs = 60_000, ids = ids))

        assertNotEquals(first.entryId, second.entryId)
        assertNotEquals(first.operationId, second.operationId)
    }

    @Test
    fun `a different climb still cannot arrive faster than a wall can be used`() {
        val gate = gate()
        gate.send(nowMs = 1_000)

        assertEquals(
            RelayInboundGate.Decision.Refused(RelayInboundGate.Refusal.RATE_LIMITED),
            gate.send(climb = otherClimb, nowMs = 1_200, fingerprint = "guest-a|9999"),
        )
    }

    @Test
    fun `a climb for another board family never reaches this one`() {
        assertEquals(
            RelayInboundGate.Decision.Refused(RelayInboundGate.Refusal.BOARD_MISMATCH),
            gate().send(
                climbBrand = BoardBrand.MOONBOARD,
                connectedBrand = BoardBrand.KILTER,
                nowMs = 1_000,
            ),
        )
    }

    /** The same family still comes in layouts whose holds are in other places. */
    @Test
    fun `a climb for another layout of the same family is refused`() {
        assertEquals(
            RelayInboundGate.Decision.Refused(RelayInboundGate.Refusal.LAYOUT_MISMATCH),
            gate().send(nowMs = 1_000, climbLayoutId = 8L, connectedLayoutId = 1L),
        )
    }

    /** Every LED right and the wall at another angle is a different problem. */
    @Test
    fun `a climb for another angle is refused`() {
        assertEquals(
            RelayInboundGate.Decision.Refused(RelayInboundGate.Refusal.ANGLE_MISMATCH),
            gate().send(nowMs = 1_000, angle = 40, connectedAngle = 25),
        )
    }

    @Test
    fun `a matching layout and angle pass`() {
        val decision = gate().send(
            nowMs = 1_000, climbLayoutId = 1L, connectedLayoutId = 1L,
            angle = 40, connectedAngle = 40,
        )
        assertTrue(decision is RelayInboundGate.Decision.ProjectNow)
    }

    /**
     * An unidentifiable write — a MoonBoard byte stream, or an Aurora frame no
     * catalogue climb matches — cannot be deduplicated or queued as an
     * occurrence, so it only ever passes through as an external write.
     */
    @Test
    fun `an unidentifiable write passes through whatever the setting says`() {
        val decision = gate().send(
            mode = RelayInboundClimbMode.APPEND_TO_END,
            climb = null, angle = null, climbBrand = null,
            nowMs = 1_000,
        )
        assertTrue(decision is RelayInboundGate.Decision.ProjectNow)
    }

    @Test
    fun `a refused climb does not become the one that blocks the next`() {
        val gate = gate()
        gate.send(nowMs = 1_000)
        gate.send(nowMs = 1_100, fingerprint = "guest-a|2")   // rate limited, not accepted

        // Far enough past the accepted one: a different climb gets through.
        val decision = gate.send(climb = otherClimb, nowMs = 5_000, fingerprint = "guest-a|3")
        assertTrue(decision is RelayInboundGate.Decision.ProjectNow)
    }

    /**
     * The relay is torn down and rebuilt for reasons that have nothing to do
     * with the guest. Forgetting the operation across one of those is how the
     * same guest write became a second occurrence.
     */
    @Test
    fun `a relay restart does not forget an operation that already landed`() {
        val gate = gate()
        val ids = Ids()
        val first = operationOf(gate.send(nowMs = 1_000, ids = ids))
        gate.markLanded(first, 1_000)

        gate.reset()

        assertEquals(
            RelayInboundGate.Decision.Refused(RelayInboundGate.Refusal.DUPLICATE),
            gate.send(nowMs = 1_100, ids = ids),
        )
    }

    /** What the restart does clear is this device's own pacing. */
    @Test
    fun `a relay restart paces from scratch`() {
        val gate = gate()
        gate.send(nowMs = 1_000)
        gate.reset()

        val decision = gate.send(climb = otherClimb, nowMs = 1_100, fingerprint = "guest-b|7")
        assertTrue(decision is RelayInboundGate.Decision.ProjectNow)
    }

    /** Past the point where a retry can be told from a new intention. */
    @Test
    fun `an operation ages out`() {
        val gate = gate()
        val ids = Ids()
        val first = operationOf(gate.send(nowMs = 1_000, ids = ids))
        gate.markFailed(first, 1_000)

        val later = operationOf(gate.send(nowMs = 1_000 + 11 * 60_000, ids = ids))

        assertNotEquals(first.operationId, later.operationId)
    }
}
