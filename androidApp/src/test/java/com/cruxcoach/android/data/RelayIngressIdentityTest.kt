package com.cruxcoach.android.data

import com.cruxcoach.android.boardcell.BoardPlaylistOp
import com.cruxcoach.android.boardcell.BoardPlaylistOps
import com.cruxcoach.android.boardcell.BoardPlaylistPolicy
import com.cruxcoach.android.boardcell.BoardPlaylistState
import com.cruxcoach.android.boardcell.BoardRelayOperation
import com.cruxcoach.android.data.RelayIngressIdentity.INTENT_TTL_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            now + INTENT_TTL_MS + 1,
        )

        assertNull("the request it recorded is finished", later)
    }

    /**
     * The official apps re-send the same climb on every re-light and every
     * angle change. A *delivered* record therefore stops being the answer to
     * those bytes long before the intention ages out — otherwise a deliberate
     * re-light minutes later is answered "already delivered" and the wall,
     * which has moved on to somebody else's climb, is never written.
     */
    @Test
    fun `a delivered request stops being theirs once the replay window passes`() {
        val landed = intent().copy(landed = true)
        val cellState = playlistWith(landed)

        val again = RelayIngressIdentity.openIntent(
            cellState, fingerprint(), landed.guestKey,
            now + RelayIngressIdentity.DELIVERED_REPLAY_MS + 1,
        )

        assertNull("a re-send this much later is the person asking again", again)
    }

    /** An *unfinished* one is a different matter: that is what a handover finds. */
    @Test
    fun `an open request is still theirs well past the replay window`() {
        val open = intent()
        val cellState = playlistWith(open)

        val retry = RelayIngressIdentity.openIntent(
            cellState, fingerprint(), open.guestKey,
            now + RelayIngressIdentity.DELIVERED_REPLAY_MS + 1,
        )

        assertEquals(open, retry)
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

    /**
     * A landed request *is* recognised across an address change, briefly.
     *
     * A guest whose success answer was lost reconnects within seconds and
     * re-sends; without this their retry mints new ids and becomes a second
     * occurrence. Inside the window they keep theirs and are told it is already
     * delivered.
     */
    @Test
    fun `a landed request is adopted by a reconnect inside the replay window`() {
        val landed = intent(guest = "AA:01").copy(landed = true)
        val cellState = playlistWith(landed)

        val back = RelayIngressIdentity.openIntent(
            cellState, fingerprint(), RelayIngressIdentity.guestKey("BB:02"), now + 1_000,
        )

        assertEquals(landed.operationId, back?.operationId)
        assertEquals(landed.entryId, back?.entryId)
    }

    /**
     * And not beyond it. Past the replay window an identical payload from
     * somebody who was not here is a new intention — replaying a stranger's
     * occurrence at them would lose theirs.
     */
    @Test
    fun `a landed request is not adopted once the replay window has passed`() {
        val cellState = playlistWith(intent(guest = "AA:01").copy(landed = true))

        val other = RelayIngressIdentity.openIntent(
            cellState, fingerprint(), RelayIngressIdentity.guestKey("BB:02"),
            now + RelayIngressIdentity.DELIVERED_REPLAY_MS + 1,
        )

        assertNull(other)
    }

    /** The original guest, still attached, is never adopted from either. */
    @Test
    fun `a landed request of an attached guest is not adopted`() {
        val landed = intent(guest = "AA:01").copy(landed = true)
        val cellState = playlistWith(landed)

        val other = RelayIngressIdentity.openIntent(
            cellState, fingerprint(), RelayIngressIdentity.guestKey("BB:02"), now + 1_000,
            connectedGuestKeys = setOf(landed.guestKey),
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

    // ── The whole sequence, in the order production runs it ───────────────

    /**
     * Reconnect, rebind, land, and then somebody else.
     *
     * The bug this pins: the rebind used to *add* a record under the new guest
     * key while leaving the original open, both carrying the same operation and
     * entry id. Marking the copy landed left the original adoptable, so the
     * next guest with the same payload inherited an occurrence that was never
     * theirs — and "two guests, two occurrences" quietly stopped holding.
     */
    @Test
    fun `a rebind leaves one record, and the next guest gets their own`() {
        // 1. The guest arrives and the intention is published.
        val first = intent(guest = "AA:01")
        var cell = playlistWith(first)

        // 2. They drop and come back on a rotated address; the intention is
        //    adopted and rebound, and the rebind is recorded.
        val rebound = RelayIngressIdentity.openIntent(
            cell, fingerprint(), RelayIngressIdentity.guestKey("BB:02"), now + 1_000,
            connectedGuestKeys = emptySet(),
        )!!
        cell = BoardPlaylistPolicy.apply(cell, BoardPlaylistOps.recordRelayOperation(rebound))

        assertEquals("one intention is one record", 1, cell.relayOperations.size)
        assertEquals(first.entryId, cell.relayOperations.single().entryId)

        // 3. It lands.
        cell = BoardPlaylistPolicy.apply(
            cell, BoardPlaylistOps.recordRelayOperation(rebound, landed = true),
        )
        assertEquals(1, cell.relayOperations.size)
        assertTrue(cell.relayOperations.single().landed)

        // 4. A different guest sends the identical payload, past the window in
        //    which a reconnect would explain it. Nothing is left for them to
        //    adopt, so they get their own nonce and occurrence.
        val later = now + RelayIngressIdentity.DELIVERED_REPLAY_MS + 1_000
        val other = RelayIngressIdentity.openIntent(
            cell, fingerprint(), RelayIngressIdentity.guestKey("CC:03"), later,
        )
        assertNull("no orphan is left behind to adopt", other)

        val theirs = RelayIngressIdentity.newIntent(
            fingerprint(), RelayIngressIdentity.guestKey("CC:03"), later,
        )
        assertNotEquals(first.entryId, theirs.entryId)
        assertNotEquals(first.operationId, theirs.operationId)
    }

    /** However many times the address rotates, it stays one record. */
    @Test
    fun `repeatedly rotating addresses keep one record of one intention`() {
        var cell = playlistWith(intent(guest = "AA:01"))
        val originalEntryId = cell.relayOperations.single().entryId

        listOf("BB:02", "CC:03", "DD:04").forEachIndexed { index, address ->
            val rebound = RelayIngressIdentity.openIntent(
                cell, fingerprint(), RelayIngressIdentity.guestKey(address),
                now + 1_000L * (index + 1),
            )!!
            cell = BoardPlaylistPolicy.apply(cell, BoardPlaylistOps.recordRelayOperation(rebound))

            assertEquals(1, cell.relayOperations.size)
            assertEquals(originalEntryId, cell.relayOperations.single().entryId)
            assertEquals(
                RelayIngressIdentity.guestKey(address),
                cell.relayOperations.single().guestKey,
            )
        }
    }

    /**
     * The handover, as two managers see it: the successor reads the intention
     * out of the cell and re-records it. That is a replacement too, not a copy.
     */
    @Test
    fun `a successor re-recording the intention does not double it`() {
        val onFirstController = intent(guest = "AA:01")
        var cell = playlistWith(onFirstController)

        val onSuccessor = RelayIngressIdentity.openIntent(
            cell, fingerprint(), onFirstController.guestKey, now + 500,
        )!!
        cell = BoardPlaylistPolicy.apply(
            cell, BoardPlaylistOps.recordRelayOperation(onSuccessor, landed = true),
        )

        assertEquals(1, cell.relayOperations.size)
        assertEquals(onFirstController.operationId, cell.relayOperations.single().operationId)
    }

    /** Two records naming one occurrence cannot survive normalisation either. */
    @Test
    fun `a duplicated identity is normalised away`() {
        val one = intent(guest = "AA:01")
        val impostor = one.copy(guestKey = RelayIngressIdentity.guestKey("BB:02"))

        val cell = BoardPlaylistPolicy.normalize(
            BoardPlaylistState(sessionId = 7, relayOperations = listOf(one, impostor)),
        )

        assertEquals(1, cell.relayOperations.size)
        assertEquals(impostor.guestKey, cell.relayOperations.single().guestKey)
    }

    // ── When the terminal commit does not make it ─────────────────────────

    /**
     * The sequence review 10 asked for, end to end.
     *
     * The board write succeeded and the occurrence exists, but the command that
     * would have marked the request finished was refused — a revision conflict,
     * a stop, a handover. The record stays `landed = false`. What must *not*
     * follow is that the same guest's deliberate send an hour later is folded
     * back onto the finished operation's ids, which is what an unbounded
     * "unlanded means still live" gave them.
     */
    @Test
    fun `a request whose terminal commit was refused still ages out`() {
        // 1. The barrier was published; the write then succeeded, but the
        //    terminal commit that would have set landed=true was refused, so
        //    canonical state still carries the open record.
        val open = intent(guest = "AA:01")
        val cell = playlistWith(open)
        assertFalse(cell.relayOperations.single().landed)

        // 2. Inside the window it is still the same request: a retry finds it.
        assertEquals(
            open,
            RelayIngressIdentity.openIntent(cell, fingerprint(), open.guestKey, now + 1_000),
        )

        // 3. Past the window it is not. Nobody completed it, and a request
        //    nobody completed within the window is not one somebody is still
        //    making.
        assertNull(
            RelayIngressIdentity.openIntent(
                cell, fingerprint(), open.guestKey, now + INTENT_TTL_MS + 1,
            ),
        )

        // 4. So the same guest's identical send later is a new intention.
        val later = RelayIngressIdentity.newIntent(
            fingerprint(), open.guestKey, now + INTENT_TTL_MS + 1,
        )
        assertNotEquals(open.entryId, later.entryId)
        assertNotEquals(open.operationId, later.operationId)
    }

    /** Nor may a stale open record be adopted by somebody else after the window. */
    @Test
    fun `an aged-out open record is not adoptable either`() {
        val cell = playlistWith(intent(guest = "AA:01"))

        assertNull(
            RelayIngressIdentity.openIntent(
                cell, fingerprint(), RelayIngressIdentity.guestKey("BB:02"),
                now + INTENT_TTL_MS + 1,
            ),
        )
    }

    /**
     * And the terminal transition itself: it arrives in the same command as
     * the occurrence, so the two are one canonical step.
     */
    @Test
    fun `the terminal record and the occurrence commit together`() {
        val open = intent(guest = "AA:01")
        val cell = playlistWith(open)

        val ops = BoardPlaylistOps.add("climb-x", 40, entryId = open.entryId) +
            BoardPlaylistOps.recordRelayOperation(open, landed = true)
        val after = BoardPlaylistPolicy.apply(cell, ops)

        assertTrue("the occurrence is there", after.entry(open.entryId) != null)
        assertTrue("and the request is finished", after.relayOperations.single().landed)
        assertEquals(1, after.relayOperations.size)
    }

    /** A refused command changes neither of them — that is the point of one command. */
    @Test
    fun `a refused terminal command leaves no half state`() {
        val open = intent(guest = "AA:01")
        val cell = playlistWith(open)

        // Nothing applied: the controller refused the batch.
        val after = BoardPlaylistPolicy.apply(cell, emptyList())

        assertNull(after.entry(open.entryId))
        assertFalse(after.relayOperations.single().landed)
    }
}
