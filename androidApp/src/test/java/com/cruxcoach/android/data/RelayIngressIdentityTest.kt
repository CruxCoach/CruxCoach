package com.cruxcoach.android.data

import com.cruxcoach.android.boardcell.BoardPlaylistOp
import com.cruxcoach.android.boardcell.BoardPlaylistOps
import com.cruxcoach.android.boardcell.BoardPlaylistPolicy
import com.cruxcoach.android.boardcell.BoardPlaylistState
import com.cruxcoach.android.boardcell.BoardRelayOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whose request is this, and is it the same one as before?
 *
 * Two answers have to hold at once, and the two previous attempts each got one
 * of them. A locally minted id lost the operation across a controller
 * handover; an id derived from the bytes collapsed two guests — and one
 * guest's second, deliberate go — into a single occurrence forever. So the
 * nonce is minted per intention and the record of it is canonical, which is
 * the only place every controller can read it from.
 */
class RelayIngressIdentityTest {

    /** 2026-08-17T12:00:00Z. */
    private val now = 1_786_968_000_000L
    private val cell = "cell-1"
    private val climb = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun fingerprint(climbUuid: String = climb, angle: Int = 40, frames: Long = 77L) =
        RelayIngressIdentity.fingerprint(cell, climbUuid, angle, frames)

    private fun intent(
        guest: String = "AA:01",
        climbUuid: String = climb,
        angle: Int = 40,
        frames: Long = 77L,
        at: Long = now,
    ) = RelayIngressIdentity.newIntent(
        fingerprint(climbUuid, angle, frames), RelayIngressIdentity.guestKey(guest), at,
    )

    private fun playlistWith(vararg records: BoardRelayOperation): BoardPlaylistState =
        BoardPlaylistPolicy.apply(
            BoardPlaylistState(sessionId = 7),
            records.map { BoardPlaylistOp.RecordRelayOperation(it) },
        )

    // ── The fingerprint says what the write is ────────────────────────────

    @Test
    fun `the same bytes in the same cell fingerprint the same`() {
        assertEquals(fingerprint(), fingerprint())
    }

    @Test
    fun `climb uuid casing does not change the fingerprint`() {
        assertEquals(fingerprint(climb.uppercase()), fingerprint(climb.lowercase()))
    }

    @Test
    fun `a different climb, angle, cell or hold set is a different write`() {
        assertNotEquals(fingerprint(), fingerprint(climbUuid = "other-climb"))
        assertNotEquals(fingerprint(), fingerprint(angle = 25))
        assertNotEquals(fingerprint(), fingerprint(frames = 78L))
        assertNotEquals(fingerprint(), RelayIngressIdentity.fingerprint("cell-2", climb, 40, 77L))
    }

    /** The cell replicates a hash, never a stranger's BLE address. */
    @Test
    fun `the guest key is a hash rather than the address`() {
        val key = RelayIngressIdentity.guestKey("AA:BB:CC:DD:EE:FF")

        assertTrue(key.isNotBlank())
        assertTrue("AA:BB:CC:DD:EE:FF" !in key)
        assertEquals(key, RelayIngressIdentity.guestKey("AA:BB:CC:DD:EE:FF"))
        assertNotEquals(key, RelayIngressIdentity.guestKey("11:22:33:44:55:66"))
    }

    // ── A new intention is a new occurrence ───────────────────────────────

    @Test
    fun `two guests sending the same climb get two occurrences`() {
        val first = intent(guest = "AA:01")
        val cellState = playlistWith(first)

        val second = RelayIngressIdentity.openIntent(
            cellState, fingerprint(), RelayIngressIdentity.guestKey("BB:02"), now,
            connectedGuestKeys = setOf(first.guestKey),
        )

        assertNull("the other guest's request is not this guest's", second)
    }

    /** And when there is nothing open, minting is the answer. */
    @Test
    fun `a guest nobody has heard from starts an intention`() {
        assertNull(
            RelayIngressIdentity.openIntent(
                BoardPlaylistState(sessionId = 7), fingerprint(),
                RelayIngressIdentity.guestKey("AA:01"), now,
            ),
        )
    }

    @Test
    fun `a deliberate repeat long after the first is a new intention`() {
        val landed = intent().copy(landed = true)
        val cellState = playlistWith(landed)

        val later = RelayIngressIdentity.openIntent(
            cellState, fingerprint(), landed.guestKey,
            now + RelayIngressIdentity.INTENT_TTL_MS + 1,
        )

        assertNull("the request it recorded is finished", later)
    }

    @Test
    fun `a fresh nonce is not guessable from the content`() {
        assertNotEquals(intent().entryId, intent().entryId)
        assertNotEquals(intent().operationId, intent().operationId)
    }

    // ── A retry is the same intention ─────────────────────────────────────

    @Test
    fun `the same guest retrying finds their own open request`() {
        val first = intent()
        val cellState = playlistWith(first)

        val retry = RelayIngressIdentity.openIntent(cellState, fingerprint(), first.guestKey, now + 500)

        assertEquals(first, retry)
    }

    @Test
    fun `a landed request inside the window is still theirs`() {
        val landed = intent().copy(landed = true)
        val cellState = playlistWith(landed)

        val again = RelayIngressIdentity.openIntent(cellState, fingerprint(), landed.guestKey, now + 500)

        assertEquals(landed, again)
    }

    /**
     * The reconnect case. A central's address rotates, so the same person
     * coming back looks like somebody new — but if the only open request for
     * these exact bytes belongs to a guest who is no longer attached, there is
     * nobody else it could be.
     */
    @Test
    fun `a guest reconnecting on a new address keeps their operation`() {
        val before = intent(guest = "AA:01")
        val cellState = playlistWith(before)

        val after = RelayIngressIdentity.openIntent(
            cellState, fingerprint(), RelayIngressIdentity.guestKey("BB:02"), now + 1_000,
            connectedGuestKeys = emptySet(),
        )

        assertEquals(before.operationId, after?.operationId)
        assertEquals(before.entryId, after?.entryId)
        assertEquals("rebound to the address they came back on",
            RelayIngressIdentity.guestKey("BB:02"), after?.guestKey)
    }

    /** Not when the original guest is still there — then they are two people. */
    @Test
    fun `an attached guest's request is never adopted by another`() {
        val mine = intent(guest = "AA:01")
        val cellState = playlistWith(mine)

        val other = RelayIngressIdentity.openIntent(
            cellState, fingerprint(), RelayIngressIdentity.guestKey("BB:02"), now + 1_000,
            connectedGuestKeys = setOf(mine.guestKey),
        )

        assertNull(other)
    }

    /** Nor when it cannot be told which of two orphans it would be. */
    @Test
    fun `two orphaned requests for the same climb are not guessed between`() {
        val cellState = playlistWith(intent(guest = "AA:01"), intent(guest = "BB:02"))

        val third = RelayIngressIdentity.openIntent(
            cellState, fingerprint(), RelayIngressIdentity.guestKey("CC:03"), now + 1_000,
        )

        assertNull(third)
    }

    /** A landed request is finished, so it is never adopted by a reconnect. */
    @Test
    fun `a landed request is not adopted as somebody's reconnect`() {
        val cellState = playlistWith(intent(guest = "AA:01").copy(landed = true))

        val other = RelayIngressIdentity.openIntent(
            cellState, fingerprint(), RelayIngressIdentity.guestKey("BB:02"), now + 1_000,
        )

        assertNull(other)
    }

    // ── What canonical state will actually hold ───────────────────────────

    @Test
    fun `recording the same intention twice keeps one record`() {
        val op = intent()

        val state = BoardPlaylistPolicy.apply(
            playlistWith(op),
            BoardPlaylistOps.recordRelayOperation(op, landed = true),
        )

        assertEquals(1, state.relayOperations.size)
        assertTrue(state.relayOperations.single().landed)
    }

    @Test
    fun `the record list is bounded and drops the oldest`() {
        val many = (1..BoardPlaylistPolicy.MAX_RELAY_OPERATIONS + 4).map {
            intent(guest = "AA:$it", frames = it.toLong())
        }

        val state = playlistWith(*many.toTypedArray())

        assertEquals(BoardPlaylistPolicy.MAX_RELAY_OPERATIONS, state.relayOperations.size)
        assertEquals(
            "the oldest fell off, not the newest",
            many.takeLast(BoardPlaylistPolicy.MAX_RELAY_OPERATIONS).map { it.entryId },
            state.relayOperations.map { it.entryId },
        )
    }

    @Test
    fun `a record with an implausible stamp is dropped`() {
        val state = playlistWith(intent().copy(stampedAtEpochMs = 1_000))

        assertTrue(state.relayOperations.isEmpty())
    }
}
