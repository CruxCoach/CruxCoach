package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A relay guest is the one sender who is not standing in front of the wall.
 *
 * They get the same sequencer as everybody else and three checks nobody local
 * needs: not the same climb twice, not faster than a wall can be used, and not
 * a climb from a different board.
 */
class RelayInboundGateTest {

    private val kilterClimb = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val otherClimb = "11111111-2222-3333-4444-555555555555"

    private fun gate() = RelayInboundGate()

    private fun RelayInboundGate.send(
        mode: RelayInboundClimbMode = RelayInboundClimbMode.PROJECT_NOW,
        climb: String? = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        angle: Int? = 40,
        climbBrand: BoardBrand? = BoardBrand.KILTER,
        connectedBrand: BoardBrand? = BoardBrand.KILTER,
        nowMs: Long,
    ) = evaluate(mode, climb, angle, climbBrand, connectedBrand, nowMs)

    @Test
    fun `the default puts an incoming climb on the wall`() {
        assertEquals(
            RelayInboundGate.Decision.ProjectNow,
            gate().send(nowMs = 1_000),
        )
    }

    @Test
    fun `the queue setting leaves the wall alone`() {
        assertEquals(
            RelayInboundGate.Decision.AppendToEnd,
            gate().send(mode = RelayInboundClimbMode.APPEND_TO_END, nowMs = 1_000),
        )
    }

    /** Guest apps re-send; the second copy is the same intention, not a second one. */
    @Test
    fun `the same climb again inside the window is one send`() {
        val gate = gate()
        gate.send(nowMs = 1_000)

        assertEquals(
            RelayInboundGate.Decision.Refused(RelayInboundGate.Refusal.DUPLICATE),
            gate.send(nowMs = 3_000),
        )
    }

    @Test
    fun `the same climb after the window is a genuine second go`() {
        val gate = gate()
        gate.send(nowMs = 1_000)

        assertEquals(RelayInboundGate.Decision.ProjectNow, gate.send(nowMs = 60_000))
    }

    @Test
    fun `a different climb still cannot arrive faster than a wall can be used`() {
        val gate = gate()
        gate.send(nowMs = 1_000)

        assertEquals(
            RelayInboundGate.Decision.Refused(RelayInboundGate.Refusal.RATE_LIMITED),
            gate.send(climb = otherClimb, nowMs = 1_200),
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

    /**
     * An unidentifiable write — a MoonBoard byte stream, or an Aurora frame no
     * catalogue climb matches — cannot be deduplicated or queued as an
     * occurrence, so it only ever passes through as an external write.
     */
    @Test
    fun `an unidentifiable write passes through whatever the setting says`() {
        assertEquals(
            RelayInboundGate.Decision.ProjectNow,
            gate().send(
                mode = RelayInboundClimbMode.APPEND_TO_END,
                climb = null, angle = null, climbBrand = null,
                nowMs = 1_000,
            ),
        )
    }

    @Test
    fun `a refused climb does not become the one that blocks the next`() {
        val gate = gate()
        gate.send(nowMs = 1_000)
        gate.send(nowMs = 1_100)   // rate limited, not accepted

        // Far enough past the accepted one: a different climb gets through.
        assertEquals(
            RelayInboundGate.Decision.ProjectNow,
            gate.send(climb = otherClimb, nowMs = 5_000),
        )
    }

    @Test
    fun `a new relay session starts with no history`() {
        val gate = gate()
        gate.send(nowMs = 1_000)
        gate.reset()

        assertEquals(RelayInboundGate.Decision.ProjectNow, gate.send(nowMs = 1_100))
    }
}
