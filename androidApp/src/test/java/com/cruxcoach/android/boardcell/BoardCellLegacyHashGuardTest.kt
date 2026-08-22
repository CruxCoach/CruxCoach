package com.cruxcoach.android.boardcell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an accepted legacy hash is allowed to leave unauthenticated: nothing.
 *
 * A legacy hash is computed over bytes that do not mention the fields added
 * after it. Accepting one while such a field carries a non-default value does
 * not mean "an old peer might not know about this" — it means anybody may
 * write whatever they like into it and the hash still verifies. That is how
 * relay intent metadata could ride into canonical state under a valid V10
 * hash, and the guard below is per-version because each version left a
 * different set of fields uncovered.
 */
class BoardCellLegacyHashGuardTest {

    /** 2026-08-17T12:00:00Z. */
    private val now = 1_786_968_000_000L

    private fun base(
        playlist: BoardPlaylistState = BoardPlaylistState(),
        relay: BoardCellRelayState = BoardCellRelayState.NONE,
        joinMode: BoardJoinMode = BoardJoinMode.OPEN,
        membershipRevision: Long = 0,
        playlistRevision: Long = 0,
    ) = BoardCellSnapshot(
        cellId = BoardCellId("cell"), physicalBoardId = PhysicalBoardId("board"),
        epoch = 1, sequence = 4, controllerId = "controller", lineageId = "lineage",
        members = setOf("controller", "member"),
        playlist = playlist, joinMode = joinMode,
        membershipRevision = membershipRevision, playlistRevision = playlistRevision,
    )

    private fun relayRecord() = BoardRelayOperation(
        fingerprint = "fp", guestKey = "gk", operationId = "relay-op-1",
        entryId = "rl1", stampedAtEpochMs = now,
    )

    /** As an older build really wrote it: one value in `currentEntryId`. */
    private fun preSplitPlaylist() = BoardPlaylistState(
        sessionId = 7,
        entries = listOf(BoardPlaylistEntry("e1", "climb-a", 40)),
        selectedEntryId = null,
        currentEntryId = "e1",
    )

    private fun signedWith(
        snapshot: BoardCellSnapshot,
        hash: (BoardCellSnapshot) -> String,
    ) = snapshot.copy(stateHash = hash(snapshot.copy(stateHash = "")))

    // ── The upgrade path still works ──────────────────────────────────────

    @Test
    fun `a genuine pre-split snapshot still verifies`() {
        val v10 = signedWith(base(playlist = preSplitPlaylist()), BoardCellHash::computeLegacyV10)

        assertTrue(v10.hasValidHash())
    }

    @Test
    fun `a genuine pre-relay snapshot still verifies`() {
        val v9 = signedWith(base(playlist = preSplitPlaylist()), BoardCellHash::computeLegacyV9)

        assertTrue(v9.hasValidHash())
    }

    // ── And authenticates everything ──────────────────────────────────────

    /**
     * The hole this closes. `relayOperations` is only in the V11 bytes, so
     * under any older hash it was free-form space in canonical state — and it
     * is the one field a device outside the cell can cause to change.
     */
    @Test
    fun `relay operations cannot ride in under any legacy hash`() {
        val honest = base(playlist = preSplitPlaylist())

        listOf<(BoardCellSnapshot) -> String>(
            BoardCellHash::computeLegacyV10,
            BoardCellHash::computeLegacyV9,
            BoardCellHash::computeLegacyV8,
        ).forEach { hash ->
            val signed = signedWith(honest, hash)
            val tampered = signed.copy(
                playlist = signed.playlist.copy(relayOperations = listOf(relayRecord())),
            )

            assertFalse("a legacy hash may not authenticate a relay record", tampered.hasValidHash())
        }
    }

    /** The same for the cursor: it is not in the legacy bytes either. */
    @Test
    fun `a selection cannot ride in under any legacy hash`() {
        val honest = base(playlist = preSplitPlaylist())

        listOf<(BoardCellSnapshot) -> String>(
            BoardCellHash::computeLegacyV10,
            BoardCellHash::computeLegacyV9,
            BoardCellHash::computeLegacyV8,
        ).forEach { hash ->
            val signed = signedWith(honest, hash)
            val tampered = signed.copy(
                playlist = signed.playlist.copy(selectedEntryId = "e1"),
            )

            assertFalse("a legacy hash may not authenticate a cursor", tampered.hasValidHash())
        }
    }

    /** The relay claim was the previous version of this same hole. */
    @Test
    fun `a relay claim cannot ride in under a pre-relay hash`() {
        val signed = signedWith(base(playlist = preSplitPlaylist()), BoardCellHash::computeLegacyV9)

        val tampered = signed.copy(
            relay = BoardCellRelayState(offered = true, guaranteedSlots = 1, freeSlots = 1),
        )

        assertFalse(tampered.hasValidHash())
    }

    /** The oldest schemas may not carry a playlist at all, let alone its extras. */
    @Test
    fun `the pre-playlist schemas stay empty-playlist only`() {
        listOf<(BoardCellSnapshot) -> String>(
            BoardCellHash::computeLegacyV6,
            BoardCellHash::computeLegacyV5,
            BoardCellHash::computeLegacyV4,
            BoardCellHash::computeLegacyV3,
            BoardCellHash::computeLegacyV2,
        ).forEach { hash ->
            val signed = signedWith(base(), hash)
            assertTrue("an empty cell still verifies", signed.hasValidHash())

            listOf(
                signed.playlist.copy(relayOperations = listOf(relayRecord())),
                signed.playlist.copy(selectedEntryId = "e1"),
                signed.playlist.copy(currentEntryId = "e1"),
            ).forEach { playlist ->
                assertFalse(
                    "nothing newer may ride in under it",
                    signed.copy(playlist = playlist).hasValidHash(),
                )
            }
        }
    }

    /** A current schema snapshot is authenticated field for field. */
    @Test
    fun `the current schema covers the new fields`() {
        val honest = base(
            playlist = BoardPlaylistPolicy.normalize(
                preSplitPlaylist().copy(relayOperations = listOf(relayRecord())),
            ),
        ).withComputedHash()
        assertTrue(honest.hasValidHash())

        assertFalse(
            honest.copy(playlist = honest.playlist.copy(selectedEntryId = null)).hasValidHash(),
        )
        assertFalse(
            honest.copy(
                playlist = honest.playlist.copy(
                    relayOperations = listOf(relayRecord().copy(entryId = "rl-other")),
                ),
            ).hasValidHash(),
        )
    }
}
