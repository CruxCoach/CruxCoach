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

    private fun gate() = RelayInboundGate()

    /**
     * A guest write's intention.
     *
     * The nonce is minted per intention, so two calls with the same content
     * are two different operations unless one of them was *found* rather than
     * minted — which is what canonical state is for and what
     * [RelayIngressIdentityTest] covers. Here the ids are supplied, so the
     * gate's own rules are what is under test.
     */
    private var nonce = 0

    /**
     * The intention this test's guest is on.
     *
     * One per test, because that is what the ingress does: it looks the open
     * intention up in canonical state and only mints when there is none. A
     * fresh one per call would make every send a different request.
     */
    private val currentIntent by lazy { identity() }

    private fun identity(
        cellId: String = "cell-1",
        climb: String = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        angle: Int = 40,
        framesHash: Long = 4242L,
        guest: String = "AA:01",
    ) = RelayIngressIdentity.newIntent(
        fingerprint = RelayIngressIdentity.fingerprint(cellId, climb, angle, framesHash),
        guestKey = RelayIngressIdentity.guestKey(guest),
        nowEpochMs = 1_786_968_000_000L,
        newId = { "nonce-${nonce++}" },
    )

    private fun RelayInboundGate.send(
        mode: RelayInboundClimbMode = RelayInboundClimbMode.PROJECT_NOW,
        climb: String? = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        angle: Int? = 40,
        climbBrand: BoardBrand? = BoardBrand.KILTER,
        connectedBrand: BoardBrand? = BoardBrand.KILTER,
        nowMs: Long,
        operation: RelayInboundGate.Operation? = currentIntent,
        climbLayoutId: Long? = null,
        connectedLayoutId: Long? = null,
        connectedAngle: Int? = null,
        canonicallyLanded: Boolean = false,
    ) = evaluate(
        mode = mode,
        climbUuid = climb,
        angle = angle,
        climbBrand = climbBrand,
        connectedBrand = connectedBrand,
        nowMs = nowMs,
        operation = operation,
        climbLayoutId = climbLayoutId,
        connectedLayoutId = connectedLayoutId,
        connectedAngle = connectedAngle,
        canonicallyLanded = canonicallyLanded,
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
        val operation = operationOf(gate().send(nowMs = 1_000))

        assertTrue(operation.operationId.isNotBlank())
        assertTrue(operation.entryId.isNotBlank())
        assertNotEquals(operation.operationId, operation.entryId)
    }

    /** An entry id has to fit the playlist's own bounds to survive normalisation. */
    @Test
    fun `the minted entry id is within the playlist id bounds`() {
        val operation = identity()

        assertTrue(operation.entryId.isNotBlank())
        assertTrue(operation.entryId.length <= 64)
    }

    /**
     * The blocker this replaces: two guests sending the same climb are two
     * people asking for it, so they are two operations with two occurrences.
     */
    @Test
    fun `two guests sending the same climb are two operations`() {
        val gate = gate()
        val first = identity(guest = "AA:01")
        val second = identity(guest = "BB:02")

        assertNotEquals(first.entryId, second.entryId)
        assertTrue(gate.send(nowMs = 1_000, operation = first) is RelayInboundGate.Decision.ProjectNow)
        // The second guest is paced like anybody else, but not deduplicated
        // away: past the interval their request stands on its own.
        val decision = gate.send(nowMs = 5_000, operation = second)
        assertEquals(second, operationOf(decision))
    }

    /**
     * The retry case the relay exists to survive: the same bytes from the same
     * guest are the same operation, so the wall is written once and the list
     * gains one occurrence however many times the guest's app re-sends.
     */
    @Test
    fun `a repeat of a write still in flight is the same operation`() {
        val gate = gate()
        val first = operationOf(gate.send(nowMs = 1_000))

        val again = operationOf(gate.send(nowMs = 1_050))

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
        val first = operationOf(gate.send(nowMs = 1_000))
        gate.markFailed(first, 1_100)

        val retry = operationOf(gate.send(nowMs = 1_200))

        assertEquals(first, retry)
    }

    /** Guest apps re-send; once it has landed, the copy is the same intention. */
    @Test
    fun `the same climb again inside the window is one send`() {
        val gate = gate()
        val first = operationOf(gate.send(nowMs = 1_000))
        gate.markLanded(first, 1_000)

        assertEquals(
            RelayInboundGate.Decision.Refused(RelayInboundGate.Refusal.DUPLICATE),
            gate.send(nowMs = 3_000),
        )
    }

    @Test
    fun `the same climb after the window is a genuine second go`() {
        val gate = gate()
        val first = operationOf(gate.send(nowMs = 1_000))
        gate.markLanded(first, 1_000)

        // Same write, so the same occurrence — it is re-lit rather than
        // duplicated, which is what "existing entry id = no-op" means on every
        // other path into the playlist too.
        // A deliberate re-send is a new intention, so the caller mints a new
        // nonce for it — and the gate treats it as the new operation it is.
        val later = identity()
        val second = operationOf(gate.send(nowMs = 60_000, operation = later))

        assertNotEquals(first.entryId, second.entryId)
    }

    @Test
    fun `a different climb still cannot arrive faster than a wall can be used`() {
        val gate = gate()
        gate.send(nowMs = 1_000)

        assertEquals(
            RelayInboundGate.Decision.Refused(RelayInboundGate.Refusal.RATE_LIMITED),
            gate.send(climb = otherClimb, nowMs = 1_200, operation = identity(climb = otherClimb)),
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
        gate.send(nowMs = 1_100, operation = identity(framesHash = 2L))   // rate limited, not accepted

        // Far enough past the accepted one: a different climb gets through.
        val decision = gate.send(climb = otherClimb, nowMs = 5_000, operation = identity(climb = otherClimb))
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
        val first = operationOf(gate.send(nowMs = 1_000))
        gate.markLanded(first, 1_000)

        gate.reset()

        assertEquals(
            RelayInboundGate.Decision.Refused(RelayInboundGate.Refusal.DUPLICATE),
            gate.send(nowMs = 1_100),
        )
    }

    /** What the restart does clear is this device's own pacing. */
    @Test
    fun `a relay restart paces from scratch`() {
        val gate = gate()
        gate.send(nowMs = 1_000)
        gate.reset()

        val decision = gate.send(climb = otherClimb, nowMs = 1_100, operation = identity(climb = otherClimb))
        assertTrue(decision is RelayInboundGate.Decision.ProjectNow)
    }

    /** Past the point where what happened to it is still worth remembering. */
    @Test
    fun `the record of an operation ages out`() {
        val gate = gate()
        val first = operationOf(gate.send(nowMs = 1_000))
        gate.markLanded(first, 1_000)

        // Inside the duplicate window this is a re-send; long after it, the
        // record is gone and an identical write is accepted again.
        val later = gate.send(nowMs = 1_000 + 11 * 60_000, operation = first)

        assertTrue(later is RelayInboundGate.Decision.ProjectNow)
        assertEquals(first, operationOf(later))
    }
}
