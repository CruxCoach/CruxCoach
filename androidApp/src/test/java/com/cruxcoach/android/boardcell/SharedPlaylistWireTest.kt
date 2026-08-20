package com.cruxcoach.android.boardcell

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Wire and durable-store behaviour for the shared playlist: what a V13 peer
 * must accept, what it must refuse, and what an older durable snapshot still
 * means.
 */
class SharedPlaylistWireTest {

    private val cell = BoardCellId("cell-shared")
    private val board = PhysicalBoardId("board-shared")

    /** 2026-08-17T12:00:00Z. */
    private val now = 1_786_968_000_000L

    private val json = Json {
        encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type"
    }

    private fun rest(
        totalSeconds: Int = 120,
        generation: Long = 3,
        nextEntryId: String = "e1",
        startedAtEpochMs: Long = now,
        endsAtEpochMs: Long = startedAtEpochMs + totalSeconds * 1_000L,
    ) = BoardPlaylistRest(totalSeconds, generation, nextEntryId, endsAtEpochMs, startedAtEpochMs)

    private fun frame(message: BoardCellWireMessage, version: Int = BoardCellWireCodec.VERSION) =
        BoardCellWireFrame(version, "message-id-0001", "sender-npub", cell.value, cell, board,
            1, 1, message)

    private fun playlist(
        entries: List<BoardPlaylistEntry> = listOf(BoardPlaylistEntry("e1", "climb-a", 40, 120)),
        current: String? = entries.firstOrNull()?.entryId,
        rest: BoardPlaylistRest? = null,
        pending: BoardPlaylistPendingProjection? = null,
        clearGeneration: Long = 0,
    ) = BoardPlaylistState(7, entries, current, rest, pending, clearGeneration)

    private fun snapshot(playlist: BoardPlaylistState) = BoardCellSnapshot(
        cellId = cell, physicalBoardId = board, epoch = 1, sequence = 3,
        controllerId = "controller-npub", controllerTerm = 1, lineageId = "lineage",
        members = setOf("controller-npub", "member-npub"), playlist = playlist,
        playlistRevision = 2,
    ).withComputedHash()

    private fun command(vararg ops: BoardPlaylistOp) =
        BoardPlaylistCommand("command-0001", 0, 0, ops.toList())

    private fun refuses(message: BoardCellWireMessage) {
        val bytes = BoardCellWireCodec.encode(frame(message))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(bytes) }
    }

    // ===== Round trips =====

    @Test fun `a snapshot carrying the full shared playlist round trips`() {
        val value = snapshot(playlist(
            entries = listOf(
                BoardPlaylistEntry("e1", "climb-a", 40, 120),
                BoardPlaylistEntry("e2", "climb-a", 40, 0),
            ),
            current = "e2",
            rest = rest(nextEntryId = "e2"),
            pending = BoardPlaylistPendingProjection("e2", "climb-a", 40,
                BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE),
            clearGeneration = 4))

        val decoded = BoardCellWireCodec.decode(
            BoardCellWireCodec.encode(frame(BoardCellWireMessage.Snapshot(value))))

        assertEquals(value, (decoded.message as BoardCellWireMessage.Snapshot).value)
        assertTrue(value.hasValidHash())
    }

    @Test fun `every sendable operation round trips inside a command`() {
        val value = command(
            BoardPlaylistOp.Add("e1", "a", 40, 90, BoardPlaylistAnchor.Tail),
            BoardPlaylistOp.Add("e2", "b", 40, 0, BoardPlaylistAnchor.Head),
            BoardPlaylistOp.Add("e3", "c", 40, 0, BoardPlaylistAnchor.After("e1")),
            BoardPlaylistOp.Remove("e1"),
            BoardPlaylistOp.Move("e2", BoardPlaylistAnchor.After("e3")),
            BoardPlaylistOp.SetCurrent("e3"),
            BoardPlaylistOp.SetRest("e3", 240),
            BoardPlaylistOp.StartRest("e3", 120),
            BoardPlaylistOp.EndRest,
            BoardPlaylistOp.Clear(),
        )

        val decoded = BoardCellWireCodec.decode(
            BoardCellWireCodec.encode(frame(BoardCellWireMessage.PlaylistCommand(value))))

        assertEquals(value, (decoded.message as BoardCellWireMessage.PlaylistCommand).value)
    }

    @Test fun `a committed operation delta round trips inside an event`() {
        val ops = listOf(
            BoardPlaylistOp.Add("e1", "a", 40),
            BoardPlaylistOp.StartRest("e1", 120, 4, now, now + 120_000L),
            BoardPlaylistOp.Clear(9),
            BoardPlaylistOp.SetPendingProjection(BoardPlaylistPendingProjection("e1", "a", 40,
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED)),
        )
        val envelope = BoardCellEnvelope(cell, board, 1, 1, 4, "previous",
            BoardCellEvent.PlaylistOpsCommitted(ops, "command-0001"), "resulting")

        val decoded = BoardCellWireCodec.decode(
            BoardCellWireCodec.encode(frame(BoardCellWireMessage.Event(envelope))))

        assertEquals(envelope, (decoded.message as BoardCellWireMessage.Event).value)
    }

    // ===== Version fencing =====

    @Test fun `the wire version marks the restorable clear`() {
        assertEquals(13, BoardCellWireCodec.VERSION)
    }

    @Test fun `a V12 peer frame is refused rather than read as an offer nobody made`() {
        // A V12 reader has no field for the restore offer, so it would show a
        // clear that cannot be taken back while the rest of the mesh counts one
        // down. Both directions must fail closed.
        val bytes = BoardCellWireCodec.encode(
            frame(BoardCellWireMessage.Snapshot(snapshot(playlist())), version = 12))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(bytes) }
    }

    @Test fun `a V11 peer frame is refused rather than read as an empty playlist`() {
        // A V11 reader has no entries field, so it would decode a populated
        // shared playlist as an empty one and quietly show the group nothing.
        val bytes = BoardCellWireCodec.encode(
            frame(BoardCellWireMessage.Snapshot(snapshot(playlist())), version = 11))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(bytes) }
    }

    // ===== The restorable clear =====

    private fun undo(
        generation: Long = 4,
        entries: List<BoardPlaylistEntry> = listOf(BoardPlaylistEntry("u1", "climb-u", 40, 60)),
        current: String? = "u1",
        clearedAt: Long = now,
        until: Long = clearedAt + BoardPlaylistPolicy.RESTORE_WINDOW_MS,
    ) = BoardPlaylistClearUndo(generation, entries, current, clearedAt, until)

    @Test fun `a snapshot carrying a restore offer round trips`() {
        val value = snapshot(playlist(clearGeneration = 4).copy(
            entries = emptyList(), currentEntryId = null, lastClear = undo()))

        val decoded = BoardCellWireCodec.decode(
            BoardCellWireCodec.encode(frame(BoardCellWireMessage.Snapshot(value))))

        assertEquals(value, (decoded.message as BoardCellWireMessage.Snapshot).value)
    }

    @Test fun `an offer that does not belong to the current clear is refused`() {
        refuses(BoardCellWireMessage.Snapshot(snapshot(playlist(clearGeneration = 4).copy(
            entries = emptyList(), currentEntryId = null, lastClear = undo(generation = 3)))))
    }

    @Test fun `an offer whose window is not really a window is refused`() {
        // Bounding only the far end would let a "thirty second" offer stand
        // until 2099, which every replica would then hash and count down.
        refuses(BoardCellWireMessage.Snapshot(snapshot(playlist(clearGeneration = 4).copy(
            entries = emptyList(), currentEntryId = null,
            lastClear = undo(until = now + 80L * 365 * 86_400_000)))))
    }

    @Test fun `a command may not stamp the window it wants the clear to stand for`() {
        refuses(BoardCellWireMessage.PlaylistCommand(command(
            BoardPlaylistOp.Clear(0, now, now + BoardPlaylistPolicy.RESTORE_WINDOW_MS))))
    }

    @Test fun `a committed clear either names a real window or names none`() {
        val stamped = BoardCellEnvelope(cell, board, 1, 1, 4, "previous",
            BoardCellEvent.PlaylistOpsCommitted(listOf(
                BoardPlaylistOp.Clear(9, now, now + BoardPlaylistPolicy.RESTORE_WINDOW_MS)),
                "command-0001"), "resulting")
        val decoded = BoardCellWireCodec.decode(
            BoardCellWireCodec.encode(frame(BoardCellWireMessage.Event(stamped))))
        assertEquals(stamped, (decoded.message as BoardCellWireMessage.Event).value)

        val halfStamped = BoardCellEnvelope(cell, board, 1, 1, 4, "previous",
            BoardCellEvent.PlaylistOpsCommitted(
                listOf(BoardPlaylistOp.Clear(9, now, now + 5_000)), "command-0001"), "resulting")
        refuses(BoardCellWireMessage.Event(halfStamped))
    }

    /**
     * The restore names the clear it takes back, so unlike the controller's
     * stamps that generation travels in both directions — it is a precondition,
     * not an authority claim.
     */
    @Test fun `a member may ask to restore and names the generation`() {
        val value = command(BoardPlaylistOp.RestoreClear(4))

        val decoded = BoardCellWireCodec.decode(
            BoardCellWireCodec.encode(frame(BoardCellWireMessage.PlaylistCommand(value))))

        assertEquals(value, (decoded.message as BoardCellWireMessage.PlaylistCommand).value)
        refuses(BoardCellWireMessage.PlaylistCommand(command(BoardPlaylistOp.RestoreClear(0))))
    }

    @Test fun `only the controller may retire a lapsed offer`() {
        refuses(BoardCellWireMessage.PlaylistCommand(command(
            BoardPlaylistOp.ExpireClearUndo(4))))

        val committed = BoardCellEnvelope(cell, board, 1, 1, 4, "previous",
            BoardCellEvent.PlaylistOpsCommitted(
                listOf(BoardPlaylistOp.ExpireClearUndo(4)), "command-0001"), "resulting")
        val decoded = BoardCellWireCodec.decode(
            BoardCellWireCodec.encode(frame(BoardCellWireMessage.Event(committed))))
        assertEquals(committed, (decoded.message as BoardCellWireMessage.Event).value)
    }

    // ===== The controller's stamps are the controller's =====

    /**
     * A member may ask for a rest; it may not say when the rest ends. Refusing
     * a stamped command at the wire keeps that a property of the protocol
     * rather than of one reducer branch remembering to overwrite it.
     */
    @Test fun `a command carrying a pre-stamped rest window is refused`() {
        refuses(BoardCellWireMessage.PlaylistCommand(command(
            BoardPlaylistOp.StartRest("e1", 120, 1, now, now + 120_000L))))
    }

    @Test fun `a command carrying a clear generation is refused`() {
        refuses(BoardCellWireMessage.PlaylistCommand(command(BoardPlaylistOp.Clear(3))))
    }

    @Test fun `a command claiming the physical send state is refused outright`() {
        refuses(BoardCellWireMessage.PlaylistCommand(command(
            BoardPlaylistOp.SetPendingProjection(BoardPlaylistPendingProjection("e1", "a", 40,
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED)))))
    }

    @Test fun `a committed delta with an unstamped rest or clear is refused`() {
        val unstampedRest = BoardCellEnvelope(cell, board, 1, 1, 4, "previous",
            BoardCellEvent.PlaylistOpsCommitted(
                listOf(BoardPlaylistOp.StartRest("e1", 120)), "command-0001"), "resulting")
        refuses(BoardCellWireMessage.Event(unstampedRest))

        val unstampedClear = BoardCellEnvelope(cell, board, 1, 1, 4, "previous",
            BoardCellEvent.PlaylistOpsCommitted(
                listOf(BoardPlaylistOp.Clear()), "command-0001"), "resulting")
        refuses(BoardCellWireMessage.Event(unstampedClear))
    }

    // ===== Bounds =====

    @Test fun `an oversized playlist never reaches the durable store`() {
        refuses(BoardCellWireMessage.Snapshot(snapshot(playlist(
            entries = List(BoardPlaylistPolicy.MAX_ENTRIES + 1) {
                BoardPlaylistEntry("e$it", "climb$it", 40)
            }))))
    }

    @Test fun `duplicate occurrence ids are refused because every later edit needs them`() {
        refuses(BoardCellWireMessage.Snapshot(snapshot(playlist(entries = listOf(
            BoardPlaylistEntry("same", "a", 40),
            BoardPlaylistEntry("same", "b", 40))))))
    }

    @Test fun `a current entry that is not in the playlist is refused`() {
        refuses(BoardCellWireMessage.Snapshot(snapshot(playlist(current = "not-here"))))
        refuses(BoardCellWireMessage.Snapshot(snapshot(
            playlist(entries = listOf(BoardPlaylistEntry("e1", "a", 40)), current = null))))
    }

    @Test fun `a rest waiting on an entry that is not in the playlist is refused`() {
        refuses(BoardCellWireMessage.Snapshot(snapshot(
            playlist(rest = rest(nextEntryId = "not-here")))))
    }

    @Test fun `a pending send that names some other entry is refused`() {
        refuses(BoardCellWireMessage.Snapshot(snapshot(playlist(
            entries = listOf(BoardPlaylistEntry("e1", "a", 40), BoardPlaylistEntry("e2", "b", 40)),
            current = "e1",
            pending = BoardPlaylistPendingProjection("e2", "b", 40,
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED)))))
    }

    @Test fun `a pending send must match the selected occurrence exactly`() {
        refuses(BoardCellWireMessage.Snapshot(snapshot(playlist(
            pending = BoardPlaylistPendingProjection("e1", "different-climb", 40,
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED)))))
        refuses(BoardCellWireMessage.Snapshot(snapshot(playlist(
            pending = BoardPlaylistPendingProjection("e1", "climb-a", 55,
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED)))))
    }

    @Test fun `a snapshot rest generation must be canonical and positive`() {
        refuses(BoardCellWireMessage.Snapshot(snapshot(playlist(rest = rest(generation = 0)))))
    }

    @Test fun `an out-of-range rest value is refused`() {
        refuses(BoardCellWireMessage.Snapshot(snapshot(playlist(entries = listOf(
            BoardPlaylistEntry("e1", "a", 40, BoardPlaylistPolicy.MAX_REST_SECONDS + 1))))))
        refuses(BoardCellWireMessage.PlaylistCommand(command(
            BoardPlaylistOp.SetRest("e1", BoardPlaylistPolicy.MAX_REST_SECONDS + 1))))
    }

    @Test fun `an empty or oversized command is refused at the wire`() {
        refuses(BoardCellWireMessage.PlaylistCommand(BoardPlaylistCommand("command-0001", 0, 0)))
        refuses(BoardCellWireMessage.PlaylistCommand(BoardPlaylistCommand("command-0001", 0, 0,
            List(BoardPlaylistPolicy.MAX_OPS_PER_COMMAND + 1) {
                BoardPlaylistOp.Add("e$it", "climb", 40)
            })))
    }

    @Test fun `a command with a too-short command id is refused`() {
        refuses(BoardCellWireMessage.PlaylistCommand(
            BoardPlaylistCommand("short", 0, 0, listOf(BoardPlaylistOp.Remove("e1")))))
    }

    @Test fun `an oversized entry id or climb id is refused`() {
        refuses(BoardCellWireMessage.PlaylistCommand(command(BoardPlaylistOp.Add(
            "e".repeat(BoardPlaylistPolicy.MAX_ENTRY_ID_LENGTH + 1), "climb", 40))))
        refuses(BoardCellWireMessage.PlaylistCommand(command(
            BoardPlaylistOp.Add("e1", "c".repeat(65), 40))))
        refuses(BoardCellWireMessage.PlaylistCommand(command(
            BoardPlaylistOp.Move("e1", BoardPlaylistAnchor.After("a".repeat(65))))))
    }

    /** A large but legal delta still fits comfortably inside the frame bound. */
    @Test fun `a maximal operation batch stays far inside the wire bound`() {
        val ops = List(BoardPlaylistPolicy.MAX_OPS_PER_COMMAND) {
            BoardPlaylistOp.Add(BoardPlaylistEntryId.random(),
                "00000000-0000-4000-8000-00000000%04d".format(it), 40, 120)
        }
        val bytes = BoardCellWireCodec.encode(frame(BoardCellWireMessage.PlaylistCommand(
            BoardPlaylistCommand("command-0001", 0, 0, ops))))

        assertTrue("delta was ${bytes.size} bytes",
            bytes.size < BoardCellMeshTransport.MAX_WIRE_BYTES / 2)
        assertEquals(ops, (BoardCellWireCodec.decode(bytes).message
            as BoardCellWireMessage.PlaylistCommand).value.ops)
    }

    /**
     * The point of operation deltas: a normal edit is a fraction of the
     * snapshot it would otherwise have broadcast.
     */
    @Test fun `one edit is far smaller than the snapshot it would have replaced`() {
        val entries = List(200) { BoardPlaylistEntry(BoardPlaylistEntryId.random(),
            "00000000-0000-4000-8000-00000000%04d".format(it), 40, 60) }
        val full = BoardCellWireCodec.encode(frame(BoardCellWireMessage.Snapshot(
            snapshot(playlist(entries = entries)))))
        val delta = BoardCellWireCodec.encode(frame(BoardCellWireMessage.PlaylistCommand(
            command(BoardPlaylistOp.Remove(entries[100].entryId)))))

        assertTrue("delta ${delta.size} vs snapshot ${full.size}", delta.size * 10 < full.size)
    }

    // ===== Hash =====

    @Test fun `every part of the shared playlist is covered by the state hash`() {
        val base = snapshot(playlist(entries = listOf(
            BoardPlaylistEntry("e1", "climb-a", 40, 120),
            BoardPlaylistEntry("e2", "climb-a", 40, 0))))

        assertFalse(base.copy(playlist = base.playlist.copy(currentEntryId = "e2")).hasValidHash())
        assertFalse(base.copy(playlist = base.playlist.copy(
            entries = base.playlist.entries.reversed())).hasValidHash())
        assertFalse(base.copy(playlist = base.playlist.copy(
            entries = base.playlist.entries.map { it.copy(restAfterSeconds = 999) })).hasValidHash())
        assertFalse(base.copy(playlist = base.playlist.copy(
            entries = base.playlist.entries.map { it.copy(entryId = it.entryId + "x") },
            currentEntryId = "e1x")).hasValidHash())
        assertFalse(base.copy(playlist = base.playlist.copy(clearGeneration = 1)).hasValidHash())
        assertFalse(base.copy(playlist = base.playlist.copy(
            activeRest = rest(totalSeconds = 60, generation = 1))).hasValidHash())
        assertFalse(base.copy(playlist = base.playlist.copy(
            pendingProjection = BoardPlaylistPendingProjection("e1", "climb-a", 40,
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED))).hasValidHash())
    }

    /**
     * The same climb twice is a legitimate playlist, and the two occurrences
     * are different canonical facts. A hash that could not tell them apart
     * would let a replica silently disagree about which one is current.
     */
    @Test fun `two occurrences of one climb hash differently from one`() {
        val once = snapshot(playlist(entries = listOf(BoardPlaylistEntry("e1", "z", 40))))
        val twice = snapshot(playlist(entries = listOf(
            BoardPlaylistEntry("e1", "z", 40), BoardPlaylistEntry("e2", "z", 40))))

        assertNotEquals(once.stateHash, twice.stateHash)
    }

    @Test fun `a rest generation change alone changes the hash`() {
        assertNotEquals(
            snapshot(playlist(rest = rest(generation = 1))).stateHash,
            snapshot(playlist(rest = rest(generation = 2))).stateHash)
    }

    @Test fun `the canonical rest window is covered by the hash`() {
        val base = snapshot(playlist(rest = rest()))
        assertFalse(base.copy(playlist = base.playlist.copy(
            activeRest = rest(endsAtEpochMs = now + 600_000))).hasValidHash())
        assertFalse(base.copy(playlist = base.playlist.copy(
            activeRest = base.playlist.activeRest!!.copy(startedAtEpochMs = now - 60_000)))
            .hasValidHash())
    }

    /**
     * Both ends inside the epoch range is not enough. Without the pairwise
     * check a controller could stamp a "two minute" pause that ran until 2099
     * — every value in between is a perfectly ordinary-looking timestamp — and
     * every replica would have hashed and honoured it.
     */
    @Test fun `a rest whose window is not its stated duration is refused`() {
        val stretched = rest(totalSeconds = 120,
            startedAtEpochMs = now, endsAtEpochMs = now + 80L * 365 * 24 * 3_600 * 1_000)
        refuses(BoardCellWireMessage.Snapshot(snapshot(playlist(rest = stretched))))
        // One second out is still out: the invariant is exact, so every replica
        // reaches the same verdict without consulting a clock.
        val offByOne = rest(totalSeconds = 120,
            startedAtEpochMs = now, endsAtEpochMs = now + 121_000)
        refuses(BoardCellWireMessage.Snapshot(snapshot(playlist(rest = offByOne))))
        refuses(BoardCellWireMessage.Snapshot(snapshot(
            playlist(rest = rest(endsAtEpochMs = -1)))))

        // And normalization refuses the same shapes, so they can never be hashed.
        assertNull(BoardPlaylistPolicy.normalize(playlist(rest = stretched)).activeRest)
        assertNull(BoardPlaylistPolicy.normalize(playlist(rest = offByOne)).activeRest)
    }

    @Test fun `remaining time is derived from the canonical end, not the duration`() {
        val value = rest(totalSeconds = 120, endsAtEpochMs = now + 120_000)
        assertEquals(120, value.remainingSeconds(now))
        assertEquals(80, value.remainingSeconds(now + 40_000))
        assertEquals(0, value.remainingSeconds(now + 120_000))
        assertEquals(0, value.remainingSeconds(now + 500_000))
        // A peer whose clock lags the arming device never sees more than the plan.
        assertEquals(120, value.remainingSeconds(now - 90_000))
    }

    // ===== Durable migration =====

    /**
     * A durable snapshot written before the shared playlist existed hashes
     * under an older schema and carries no playlist at all. It must keep
     * validating and keep meaning exactly what it meant.
     */
    @Test fun `a pre-playlist durable snapshot still validates`() {
        val legacy = BoardCellSnapshot(
            cellId = cell, physicalBoardId = board, epoch = 1, sequence = 3,
            controllerId = "controller-npub", controllerTerm = 1, lineageId = "lineage",
            members = setOf("controller-npub"), membershipRevision = 4, playlistRevision = 2,
        )

        assertTrue(legacy.copy(stateHash = BoardCellHash.computeLegacyV5(legacy)).hasValidHash())
        assertTrue(legacy.copy(stateHash = BoardCellHash.computeLegacyV6(legacy)).hasValidHash())
        assertTrue(legacy.playlist.usesLegacyShapeOnly)
    }

    @Test fun `no legacy hash is accepted once the playlist carries anything`() {
        val populated = BoardCellSnapshot(
            cellId = cell, physicalBoardId = board, epoch = 1, sequence = 3,
            controllerId = "controller-npub", controllerTerm = 1, lineageId = "lineage",
            members = setOf("controller-npub", "member-npub"),
            playlist = playlist(), playlistRevision = 2,
        )

        assertFalse(populated.copy(stateHash = BoardCellHash.computeLegacyV6(populated)).hasValidHash())
        assertFalse(populated.copy(stateHash = BoardCellHash.computeLegacyV5(populated)).hasValidHash())
        assertTrue(populated.withComputedHash().hasValidHash())
    }

    /**
     * The durable store decodes with `ignoreUnknownKeys = false`, and that is
     * the migration: a document written by the index-addressed build carries
     * fields this build does not know, so it fails to decode and the cell is
     * rebuilt from the mesh rather than reinterpreted under a shape it was
     * never written in.
     */
    @Test fun `durable JSON from the index-addressed build fails closed`() {
        val legacyDocument = """
            {"sessionId":7,"currentIndex":1,"items":[["a",40],["b",45]],
             "restAfterSeconds":[120,0],"hostId":"host-npub","members":["host-npub"],
             "activeRest":null,"pendingProjection":null,"proposal":null}
        """.trimIndent()

        assertThrows(Exception::class.java) {
            json.decodeFromString<BoardPlaylistState>(legacyDocument)
        }
    }

    /**
     * The rest gained a canonical instant after the first shared-playlist
     * build. A durable document written by that build has none, and must not
     * be read as a rest that ended in 1970 — normalization drops it rather
     * than honouring a nonsensical deadline.
     */
    @Test fun `durable JSON from a pre-instant build drops the rest`() {
        val current = playlist(rest = rest())
        val withoutInstants = JsonObject(
            json.encodeToJsonElement(current).jsonObject.mapValues { (key, value) ->
                if (key == "activeRest") JsonObject(value.jsonObject - "endsAtEpochMs") else value
            })

        val decoded = json.decodeFromString<BoardPlaylistState>(withoutInstants.toString())

        assertEquals(0L, decoded.activeRest!!.endsAtEpochMs)
        val normalized = BoardPlaylistPolicy.normalize(decoded)
        assertNull("a rest with no end must not survive", normalized.activeRest)
        // The playlist itself is untouched — only the timed thing goes.
        assertEquals(current.entries, normalized.entries)
        assertEquals(current.currentEntryId, normalized.currentEntryId)
    }

    @Test fun `a shared playlist survives the durable JSON round trip`() {
        val value = snapshot(playlist(
            rest = rest(),
            pending = BoardPlaylistPendingProjection("e1", "climb-a", 40,
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED),
            clearGeneration = 2))

        val decoded = json.decodeFromString<BoardCellSnapshot>(json.encodeToString(value))

        assertEquals(value, decoded)
        assertTrue(decoded.hasValidHash())
    }

    @Test fun `peer diagnostics round trip without entering canonical snapshot`() {
        val diagnostics = BoardCellPeerDiagnostics(
            appVersionCode = 1_000_010,
            bluetoothEnabled = true,
            meshRuntimeRunning = true,
            meshRole = "member",
            meshMemberCount = 2,
            controllerAvailable = true,
            boardConnection = "DISCONNECTED",
            boardKeepAlive = true,
            autoDisconnectSeconds = 60,
            sessionRole = "PARTICIPANT",
            sessionVisibility = "JOINABLE",
            sessionVisibilityRequested = "JOINABLE",
            sessionId = 42,
            queueSize = 2,
            currentIndex = 1,
            currentClimbId = "climb-123",
            canonicalPlaylist = true,
            playlistSynchronized = true,
            pendingCommands = 1,
        )

        val decoded = BoardCellWireCodec.decode(BoardCellWireCodec.encode(frame(
            BoardCellWireMessage.MemberHeartbeat(2, diagnostics))))

        assertEquals(diagnostics,
            (decoded.message as BoardCellWireMessage.MemberHeartbeat).diagnostics)
    }
}
