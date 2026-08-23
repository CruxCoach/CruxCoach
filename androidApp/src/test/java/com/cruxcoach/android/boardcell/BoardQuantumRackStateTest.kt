package com.cruxcoach.android.boardcell

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape a shared rack would take, and the rules over it.
 *
 * None of this is on the BoardCell wire yet — see [BoardQuantumRackState] for
 * why the transport is a separate, reviewable step. What is tested here is
 * everything that would have to be right *before* that step: that the record
 * defaults to invisible, that an older replica's bytes still decode, that two
 * devices reach the same answer without talking, and above all that nothing in
 * this file can turn a light off.
 */
class BoardQuantumRackStateTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Mixed-version safety ──────────────────────────────────────────────

    @Test
    fun `an empty rack is indistinguishable from a replica that never had one`() {
        val state = BoardQuantumRackState()

        assertTrue(state.usesPreRackShapeOnly)
        assertTrue(state.assignments.isEmpty())
        assertTrue(state.claims.isEmpty())
    }

    @Test
    fun `bytes written before the rack existed still decode`() {
        // Exactly what an older writer produced: no rack fields at all.
        val legacy = """{}"""

        val decoded = json.decodeFromString(BoardQuantumRackState.serializer(), legacy)

        assertEquals(BoardQuantumRackState.SCHEMA_VERSION, decoded.schemaVersion)
        assertTrue(decoded.usesPreRackShapeOnly)
    }

    @Test
    fun `fields a newer writer added do not break this reader`() {
        val future = """
            {"schemaVersion":7,"assignments":[{"entryId":"e1","lane":2,"revision":4,
             "setBy":"ada","mood":"determined"}],"claims":[],"racksPerWall":9}
        """.trimIndent()

        val decoded = json.decodeFromString(BoardQuantumRackState.serializer(), future)

        assertEquals(7, decoded.schemaVersion)
        assertEquals(2, decoded.laneFor("e1"))
        assertFalse(decoded.usesPreRackShapeOnly)
    }

    @Test
    fun `an assignment written without the optional fields keeps its defaults`() {
        val minimal = """{"assignments":[{"entryId":"e1","lane":0}]}"""

        val decoded = json.decodeFromString(BoardQuantumRackState.serializer(), minimal)

        assertEquals(0, decoded.laneFor("e1"))
        assertEquals(0L, decoded.assignments.single().revision)
        assertEquals("", decoded.assignments.single().setBy)
    }

    @Test
    fun `a populated rack round-trips`() {
        val state = BoardQuantumRackState(
            assignments = listOf(BoardQuantumLaneAssignment("e1", 1, 3, "ada")),
            claims = listOf(BoardQuantumLaneClaim(1, "ada", 2, 9_000, 600_000)),
        )

        val decoded = json.decodeFromString(
            BoardQuantumRackState.serializer(),
            json.encodeToString(BoardQuantumRackState.serializer(), state),
        )

        assertEquals(state, decoded)
    }

    // ── Assignment ────────────────────────────────────────────────────────

    @Test
    fun `assigning the same lane twice changes nothing`() {
        val once = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e1", 2, revision = 1)
        val twice = BoardQuantumRackPolicy.assign(once, "e1", 2, revision = 1)

        assertSame(once, twice)
    }

    @Test
    fun `a message from behind the current revision is a duplicate, not an intention`() {
        val current = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e1", 3, revision = 5)

        val stale = BoardQuantumRackPolicy.assign(current, "e1", 0, revision = 2)

        assertEquals(3, stale.laneFor("e1"))
    }

    @Test
    fun `one lane holds one occurrence`() {
        var state = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e1", 1, 1)
        state = BoardQuantumRackPolicy.assign(state, "e2", 1, 2)

        assertEquals("e2", state.entryInLane(1))
        assertNull(state.laneFor("e1"))
    }

    @Test
    fun `duplicate occurrences of one climb carry separate lanes`() {
        // A 4x4 is the same climb four times; "the second one goes in lane 3"
        // has to mean the second one.
        var state = BoardQuantumRackState()
        state = BoardQuantumRackPolicy.assign(state, "zombie-1", 0, 1)
        state = BoardQuantumRackPolicy.assign(state, "zombie-2", 2, 2)

        assertEquals(0, state.laneFor("zombie-1"))
        assertEquals(2, state.laneFor("zombie-2"))
    }

    // ── Following the list, never the wall ────────────────────────────────

    @Test
    fun `retaining entries drops preferences and reports the orphaned lanes`() {
        var state = BoardQuantumRackState()
        state = BoardQuantumRackPolicy.assign(state, "e1", 0, 1)
        state = BoardQuantumRackPolicy.assign(state, "e2", 1, 2)

        val orphaned = BoardQuantumRackPolicy.orphanedLanes(state, setOf("e1"))
        val kept = BoardQuantumRackPolicy.retainEntries(state, setOf("e1"))

        assertEquals(listOf(1), orphaned)
        assertEquals(0, kept.laneFor("e1"))
        assertNull(kept.laneFor("e2"))
    }

    @Test
    fun `clearing the whole list leaves an empty record and no command`() {
        var state = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e1", 0, 1)
        state = BoardQuantumRackPolicy.assign(state, "e2", 1, 2)

        val cleared = BoardQuantumRackPolicy.retainEntries(state, emptySet())

        assertTrue(cleared.assignments.isEmpty())
        // The record is a record. Whatever the lanes were showing, they still
        // are: nothing in this policy can produce a controller write.
        assertEquals(listOf(0, 1), BoardQuantumRackPolicy.orphanedLanes(state, emptySet()))
    }

    @Test
    fun `releasing an entry that has no preference is a no-op`() {
        val state = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e1", 0, 1)

        assertSame(state, BoardQuantumRackPolicy.release(state, "nobody"))
    }

    // ── Claims are leases over lanes, not over climbs ─────────────────────

    @Test
    fun `a free lane is granted`() {
        val outcome = BoardQuantumRackPolicy.claim(
            BoardQuantumRackState(), lane = 1, participantId = "ada", nowEpochMs = 1_000,
        )

        val granted = outcome as BoardQuantumClaimOutcome.Granted
        assertEquals("ada", granted.state.claimFor(1)?.holderId)
        assertEquals(1L, granted.state.claimFor(1)?.revision)
    }

    @Test
    fun `a live foreign claim asks for a takeover instead of overwriting it`() {
        val held = (BoardQuantumRackPolicy.claim(
            BoardQuantumRackState(), 1, "ada", nowEpochMs = 0,
        ) as BoardQuantumClaimOutcome.Granted).state

        val outcome = BoardQuantumRackPolicy.claim(held, 1, "bo", nowEpochMs = 1_000)

        val takeover = outcome as BoardQuantumClaimOutcome.RequiresTakeover
        assertEquals("ada", takeover.existing.holderId)
        // Nothing changed until somebody confirmed it.
        assertEquals("ada", held.claimFor(1)?.holderId)

        val confirmed = BoardQuantumRackPolicy.takeover(held, 1, "bo", nowEpochMs = 1_000)
        assertEquals("bo", confirmed.claimFor(1)?.holderId)
        assertEquals(2L, confirmed.claimFor(1)?.revision)
    }

    @Test
    fun `an expired claim is free to take without a question`() {
        val held = (BoardQuantumRackPolicy.claim(
            BoardQuantumRackState(), 1, "ada", nowEpochMs = 0, leaseMs = 1_000,
        ) as BoardQuantumClaimOutcome.Granted).state

        val outcome = BoardQuantumRackPolicy.claim(held, 1, "bo", nowEpochMs = 5_000)

        assertTrue(outcome is BoardQuantumClaimOutcome.Granted)
    }

    @Test
    fun `renewing your own lane needs no takeover`() {
        val held = (BoardQuantumRackPolicy.claim(
            BoardQuantumRackState(), 1, "ada", nowEpochMs = 0, leaseMs = 1_000,
        ) as BoardQuantumClaimOutcome.Granted).state

        val renewed = BoardQuantumRackPolicy.claim(held, 1, "ada", nowEpochMs = 500, leaseMs = 1_000)

        val granted = renewed as BoardQuantumClaimOutcome.Granted
        assertEquals(1_500L, granted.state.claimFor(1)?.expiresAtEpochMs)
        assertEquals(2L, granted.state.claimFor(1)?.revision)
    }

    @Test
    fun `only the holder may release a lane`() {
        val held = (BoardQuantumRackPolicy.claim(
            BoardQuantumRackState(), 1, "ada", nowEpochMs = 0,
        ) as BoardQuantumClaimOutcome.Granted).state

        assertSame(held, BoardQuantumRackPolicy.releaseClaim(held, 1, "bo"))
        assertNull(BoardQuantumRackPolicy.releaseClaim(held, 1, "ada").claimFor(1))
    }

    @Test
    fun `pruning an expired lease removes a record and nothing else`() {
        var state = (BoardQuantumRackPolicy.claim(
            BoardQuantumRackState(), 1, "ada", nowEpochMs = 0, leaseMs = 1_000,
        ) as BoardQuantumClaimOutcome.Granted).state
        state = BoardQuantumRackPolicy.assign(state, "e1", 1, revision = 1)

        val pruned = BoardQuantumRackPolicy.pruneExpiredClaims(state, nowEpochMs = 9_000)

        assertNull(pruned.claimFor(1))
        // The lane assignment — and, in the real system, the light it points
        // at — is untouched. A lease running out is not a request to go dark.
        assertEquals(1, pruned.laneFor("e1"))
        assertEquals(state.assignments, pruned.assignments)
    }

    @Test
    fun `pruning nothing returns the same instance`() {
        val state = (BoardQuantumRackPolicy.claim(
            BoardQuantumRackState(), 1, "ada", nowEpochMs = 0, leaseMs = 10_000,
        ) as BoardQuantumClaimOutcome.Granted).state

        assertSame(state, BoardQuantumRackPolicy.pruneExpiredClaims(state, 1_000))
    }

    // ── Merge is deterministic on both sides ──────────────────────────────

    @Test
    fun `two replicas editing different lanes keep both edits`() {
        val local = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e1", 0, 1, "ada")
        val remote = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e2", 2, 1, "bo")

        val merged = BoardQuantumRackPolicy.merge(local, remote)
        val mirrored = BoardQuantumRackPolicy.merge(remote, local)

        assertEquals(0, merged.laneFor("e1"))
        assertEquals(2, merged.laneFor("e2"))
        assertEquals(merged, mirrored)
    }

    @Test
    fun `the higher revision wins a collision on the same occurrence`() {
        val local = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e1", 0, 1, "ada")
        val remote = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e1", 3, 4, "bo")

        assertEquals(3, BoardQuantumRackPolicy.merge(local, remote).laneFor("e1"))
        assertEquals(3, BoardQuantumRackPolicy.merge(remote, local).laneFor("e1"))
    }

    @Test
    fun `an exact revision tie is broken the same way on both devices`() {
        val local = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e1", 0, 2, "ada")
        val remote = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e1", 3, 2, "bo")

        assertEquals(
            BoardQuantumRackPolicy.merge(local, remote),
            BoardQuantumRackPolicy.merge(remote, local),
        )
    }

    @Test
    fun `merging never lets two occurrences hold one lane`() {
        val local = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e1", 1, 1, "ada")
        val remote = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e2", 1, 2, "bo")

        val merged = BoardQuantumRackPolicy.merge(local, remote)

        assertEquals(1, merged.assignments.count { it.lane == 1 })
        assertEquals("e2", merged.entryInLane(1))
    }

    @Test
    fun `merging keeps the newer lease per lane and the higher schema version`() {
        val local = BoardQuantumRackState(
            schemaVersion = 1,
            claims = listOf(BoardQuantumLaneClaim(0, "ada", revision = 1, expiresAtEpochMs = 10)),
        )
        val remote = BoardQuantumRackState(
            schemaVersion = 3,
            claims = listOf(BoardQuantumLaneClaim(0, "bo", revision = 2, expiresAtEpochMs = 20)),
        )

        val merged = BoardQuantumRackPolicy.merge(local, remote)

        assertEquals("bo", merged.claimFor(0)?.holderId)
        assertEquals(3, merged.schemaVersion)
    }

    @Test
    fun `merging an empty replica changes nothing it did not know about`() {
        val local = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "e1", 1, 1, "ada")

        val merged = BoardQuantumRackPolicy.merge(local, BoardQuantumRackState())

        assertEquals(1, merged.laneFor("e1"))
    }
}
