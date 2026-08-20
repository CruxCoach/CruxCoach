package com.cruxcoach.android.boardcell

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Wire and durable-store compatibility for the joinable playlist: what a V8
 * peer must accept, what it must refuse, and what an old durable snapshot
 * written before any of this existed still means.
 */
class JoinablePlaylistWireTest {

    private val cell = BoardCellId("cell-joinable")
    private val board = PhysicalBoardId("board-joinable")

    /** 2026-08-17T12:00:00Z. */
    private val now = 1_786_968_000_000L

    private fun rest(
        totalSeconds: Int = 120,
        generation: Long = 3,
        nextIndex: Int = 0,
        startedAtEpochMs: Long = now,
        endsAtEpochMs: Long = startedAtEpochMs + totalSeconds * 1_000L,
    ) = BoardPlaylistRest(totalSeconds, generation, nextIndex, endsAtEpochMs, startedAtEpochMs)

    private fun proposal(
        requestId: String = "request-0001",
        requesterId: String = "guest-npub",
        items: List<Pair<String, Int>> = listOf("x" to 40),
        rests: List<Int> = listOf(60),
        requestedAtEpochMs: Long = now,
        expiresAtEpochMs: Long = requestedAtEpochMs + BoardPlaylistPolicy.PROPOSAL_TIMEOUT_MS,
    ) = BoardPlaylistProposal(requestId, requesterId, 9, items, rests, expiresAtEpochMs,
        requestedAtEpochMs)

    private fun frame(message: BoardCellWireMessage, version: Int = BoardCellWireCodec.VERSION) =
        BoardCellWireFrame(version, "message-id-0001", "sender-npub", cell.value, cell, board,
            1, 1, message)

    private fun playlist(
        items: List<Pair<String, Int>> = listOf("climb-a" to 40),
        rests: List<Int> = listOf(120),
        host: String? = "host-npub",
        members: List<String> = listOf("host-npub"),
        rest: BoardPlaylistRest? = null,
        pending: BoardPlaylistPendingProjection? = null,
        proposal: BoardPlaylistProposal? = null,
    ) = BoardPlaylistState(7, 0, items, rests, host, members, rest, pending, proposal)

    private fun snapshot(playlist: BoardPlaylistState) = BoardCellSnapshot(
        cellId = cell, physicalBoardId = board, epoch = 1, sequence = 3,
        controllerId = "controller-npub", controllerTerm = 1, lineageId = "lineage",
        members = setOf("controller-npub", "host-npub"), playlist = playlist,
        playlistRevision = 2,
    ).withComputedHash()

    // ===== Round trips =====

    @Test fun `a snapshot carrying the full joinable playlist round trips`() {
        val value = snapshot(playlist(
            rest = rest(),
            pending = BoardPlaylistPendingProjection("climb-a", 40,
                BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE),
            proposal = proposal()))

        val decoded = BoardCellWireCodec.decode(
            BoardCellWireCodec.encode(frame(BoardCellWireMessage.Snapshot(value))))

        assertEquals(value, (decoded.message as BoardCellWireMessage.Snapshot).value)
        assertTrue(value.hasValidHash())
    }

    @Test fun `every playlist control command round trips`() {
        val controls = listOf(
            BoardPlaylistControl.Start("command-0001", 0, "request-0001", 7,
                listOf("a" to 40), listOf(90)),
            BoardPlaylistControl.Decide("command-0002", 1, "request-0001",
                BoardPlaylistProposalDecision.APPEND),
            BoardPlaylistControl.Join("command-0003", 1),
            BoardPlaylistControl.Leave("command-0004", 1, "successor-npub"),
            BoardPlaylistControl.End("command-0005", 1),
            BoardPlaylistControl.RetryProjection("command-0006", 1),
            BoardPlaylistControl.SetRest("command-0007", 1, 0, 240),
            BoardPlaylistControl.RestStarted("command-0008", 1, 120, 0),
            BoardPlaylistControl.RestEnded("command-0009", 1),
            BoardPlaylistControl.ProjectionPending("command-0010", 1,
                BoardPlaylistPendingProjection("a", 40,
                    BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED)),
        )

        controls.forEach { control ->
            val decoded = BoardCellWireCodec.decode(
                BoardCellWireCodec.encode(frame(BoardCellWireMessage.PlaylistControl(control))))
            assertEquals(control, (decoded.message as BoardCellWireMessage.PlaylistControl).value)
        }
    }

    // ===== Version fencing =====

    @Test fun `the wire version includes canonical join mode`() {
        assertEquals(11, BoardCellWireCodec.VERSION)
    }

    @Test fun `member admission prompt decision and result round trip`() {
        val messages = listOf<BoardCellWireMessage>(
            BoardCellWireMessage.MemberAdmissionPrompt(BoardCellAdmissionPrompt(
                "request-0001", "candidate", "sponsor", now, now + 30_000L,
            )),
            BoardCellWireMessage.MemberAdmissionDecision(BoardCellAdmissionDecision(
                "request-0001", "candidate", approved = true,
            )),
            BoardCellWireMessage.MemberAdmissionResult(BoardCellAdmissionResult(
                "request-0001", "candidate", approved = false, retryAfterEpochMs = now + 60_000L,
            )),
        )

        messages.forEach { message ->
            assertEquals(message, BoardCellWireCodec.decode(
                BoardCellWireCodec.encode(frame(message)),
            ).message)
        }
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
            sessionRole = "HOST",
            sessionVisibility = "JOINABLE",
            sessionVisibilityRequested = "JOINABLE",
            sessionId = 42,
            queueSize = 2,
            currentIndex = 1,
            currentClimbId = "climb-123",
            canonicalPlaylist = true,
            playlistHost = true,
            playlistMember = true,
            pendingCommands = 1,
        )
        val decoded = BoardCellWireCodec.decode(BoardCellWireCodec.encode(frame(
            BoardCellWireMessage.MemberHeartbeat(2, diagnostics))))

        assertEquals(
            diagnostics,
            (decoded.message as BoardCellWireMessage.MemberHeartbeat).diagnostics,
        )
    }

    @Test fun `a V7 peer frame is refused rather than read as a hostless playlist`() {
        // A V7 reader has no playlist host or member fields, so it would treat
        // a canonical joinable playlist as one nobody owns and hand every
        // member the host's rights. Both directions must fail closed.
        val bytes = BoardCellWireCodec.encode(
            frame(BoardCellWireMessage.Snapshot(snapshot(playlist())), version = 7))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(bytes) }
    }

    // ===== Bounds =====

    @Test fun `an oversized playlist never reaches the durable store`() {
        val bytes = BoardCellWireCodec.encode(frame(BoardCellWireMessage.Snapshot(snapshot(
            playlist(items = List(BoardPlaylistPolicy.MAX_ITEMS + 1) { "climb$it" to 40 },
                rests = emptyList())))))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(bytes) }
    }

    @Test fun `an oversized playlist membership is refused`() {
        val members = List(BoardPlaylistPolicy.MAX_MEMBERS + 1) { "member$it" }
        val bytes = BoardCellWireCodec.encode(frame(BoardCellWireMessage.Snapshot(
            snapshot(playlist(host = members.first(), members = members)))))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(bytes) }
    }

    @Test fun `an out-of-range rest value is refused`() {
        val bytes = BoardCellWireCodec.encode(frame(BoardCellWireMessage.Snapshot(
            snapshot(playlist(rests = listOf(BoardPlaylistPolicy.MAX_REST_SECONDS + 1))))))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(bytes) }
    }

    @Test fun `a current index outside the queue is refused`() {
        val bytes = BoardCellWireCodec.encode(frame(BoardCellWireMessage.Snapshot(
            snapshot(playlist().copy(currentIndex = 9)))))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(bytes) }
    }

    @Test fun `an empty start command is refused at the wire`() {
        val bytes = BoardCellWireCodec.encode(frame(BoardCellWireMessage.PlaylistControl(
            BoardPlaylistControl.Start("command-0001", 0, "request-0001", 7, emptyList()))))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(bytes) }
    }

    @Test fun `a control command with a too-short command id is refused`() {
        val bytes = BoardCellWireCodec.encode(frame(BoardCellWireMessage.PlaylistControl(
            BoardPlaylistControl.Join("short", 0))))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(bytes) }
    }

    // ===== Hash =====

    @Test fun `playlist host and membership are covered by the state hash`() {
        val base = snapshot(playlist(members = listOf("host-npub")))

        assertFalse(base.copy(playlist = base.playlist.copy(hostId = "someone-else",
            members = listOf("someone-else"))).hasValidHash())
        assertFalse(base.copy(playlist = base.playlist.copy(
            members = listOf("host-npub", "extra"))).hasValidHash())
        assertFalse(base.copy(playlist = base.playlist.copy(
            restAfterSeconds = listOf(999))).hasValidHash())
        assertFalse(base.copy(playlist = base.playlist.copy(
            activeRest = rest(totalSeconds = 60, generation = 1))).hasValidHash())
        assertFalse(base.copy(playlist = base.playlist.copy(
            pendingProjection = BoardPlaylistPendingProjection("climb-a", 40,
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED))).hasValidHash())
        assertFalse(base.copy(playlist = base.playlist.copy(proposal = proposal()))
            .hasValidHash())
    }

    @Test fun `a rest generation change alone changes the hash`() {
        val one = snapshot(playlist(rest = rest(generation = 1)))
        val two = snapshot(playlist(rest = rest(generation = 2)))
        assertNotEquals(one.stateHash, two.stateHash)
    }

    /**
     * The canonical instants are what keep a rest and a request synchronised
     * across devices, so a peer must not be able to move either of them
     * without the hash noticing.
     */
    @Test fun `the canonical rest end and request deadline are covered by the hash`() {
        val withRest = snapshot(playlist(rest = rest()))
        assertFalse(withRest.copy(playlist = withRest.playlist.copy(
            activeRest = rest(endsAtEpochMs = now + 600_000))).hasValidHash())

        val withProposal = snapshot(playlist(proposal = proposal()))
        assertFalse(withProposal.copy(playlist = withProposal.playlist.copy(
            proposal = proposal(expiresAtEpochMs = now + 600_000))).hasValidHash())
    }

    @Test fun `an absurd rest end or request deadline is refused at the wire`() {
        val farFuture = BoardCellWireCodec.encode(frame(BoardCellWireMessage.Snapshot(
            snapshot(playlist(rest = rest(endsAtEpochMs = Long.MAX_VALUE / 2))))))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(farFuture) }

        val zeroDeadline = BoardCellWireCodec.encode(frame(BoardCellWireMessage.Snapshot(
            snapshot(playlist(proposal = proposal(expiresAtEpochMs = 0))))))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(zeroDeadline) }

        val negative = BoardCellWireCodec.encode(frame(BoardCellWireMessage.Snapshot(
            snapshot(playlist(rest = rest(endsAtEpochMs = -1))))))
        assertThrows(IllegalArgumentException::class.java) { BoardCellWireCodec.decode(negative) }
    }

    /**
     * Both ends inside the epoch range is not enough. Without the pairwise
     * check a controller could stamp a "two minute" pause that ran until 2099
     * — every value in between is a perfectly ordinary-looking timestamp — and
     * every replica would have hashed and honoured it.
     */
    @Test fun `a rest whose window is not its stated duration is refused`() {
        // Ends eighty years after it started, but calls itself two minutes.
        val stretched = rest(totalSeconds = 120,
            startedAtEpochMs = now, endsAtEpochMs = now + 80L * 365 * 24 * 3_600 * 1_000)
        assertThrows(IllegalArgumentException::class.java) {
            BoardCellWireCodec.decode(BoardCellWireCodec.encode(
                frame(BoardCellWireMessage.Snapshot(snapshot(playlist(rest = stretched))))))
        }
        // One second out is still out: the invariant is exact, so every replica
        // reaches the same verdict without consulting a clock.
        val offByOne = rest(totalSeconds = 120,
            startedAtEpochMs = now, endsAtEpochMs = now + 121_000)
        assertThrows(IllegalArgumentException::class.java) {
            BoardCellWireCodec.decode(BoardCellWireCodec.encode(
                frame(BoardCellWireMessage.Snapshot(snapshot(playlist(rest = offByOne))))))
        }
        // And normalization refuses the same shape, so it can never be hashed.
        assertNull(BoardPlaylistPolicy.normalize(playlist(rest = stretched)).activeRest)
        assertNull(BoardPlaylistPolicy.normalize(playlist(rest = offByOne)).activeRest)
    }

    @Test fun `a request whose window is not the promised thirty seconds is refused`() {
        val yearLong = proposal(requestedAtEpochMs = now,
            expiresAtEpochMs = now + 365L * 24 * 3_600 * 1_000)
        assertThrows(IllegalArgumentException::class.java) {
            BoardCellWireCodec.decode(BoardCellWireCodec.encode(
                frame(BoardCellWireMessage.Snapshot(snapshot(playlist(proposal = yearLong))))))
        }
        val tooShort = proposal(requestedAtEpochMs = now, expiresAtEpochMs = now + 1_000)
        assertThrows(IllegalArgumentException::class.java) {
            BoardCellWireCodec.decode(BoardCellWireCodec.encode(
                frame(BoardCellWireMessage.Snapshot(snapshot(playlist(proposal = tooShort))))))
        }
        assertNull(BoardPlaylistPolicy.normalize(playlist(proposal = yearLong)).proposal)
        assertNull(BoardPlaylistPolicy.normalize(playlist(proposal = tooShort)).proposal)
    }

    @Test fun `the canonical rest start is covered by the hash`() {
        val base = snapshot(playlist(rest = rest()))
        // Moving the start without moving the end would change how much of the
        // pause a late joiner believes is left.
        assertFalse(base.copy(playlist = base.playlist.copy(
            activeRest = base.playlist.activeRest!!.copy(startedAtEpochMs = now - 60_000)))
            .hasValidHash())
    }

    @Test fun `the canonical request start is covered by the hash`() {
        val base = snapshot(playlist(proposal = proposal()))
        assertFalse(base.copy(playlist = base.playlist.copy(
            proposal = base.playlist.proposal!!.copy(requestedAtEpochMs = now - 10_000)))
            .hasValidHash())
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
     * A durable snapshot written before joinable playlists existed hashes
     * under V5 and has none of the new fields. It must keep validating and
     * keep meaning exactly what it meant, rather than being read as a
     * playlist whose host and members simply went missing.
     */
    @Test fun `a pre-joinable durable snapshot still validates and stays non-joinable`() {
        val legacy = BoardCellSnapshot(
            cellId = cell, physicalBoardId = board, epoch = 1, sequence = 3,
            controllerId = "controller-npub", controllerTerm = 1, lineageId = "lineage",
            members = setOf("controller-npub"), membershipRevision = 4,
            playlist = BoardPlaylistState(7, 0, listOf("legacy" to 40)),
            playlistRevision = 2,
        )
        val v5Hashed = legacy.copy(stateHash = BoardCellHash.computeLegacyV5(legacy))

        assertTrue(v5Hashed.hasValidHash())
        assertFalse(v5Hashed.playlist.isJoinable)
        assertNull(v5Hashed.playlist.hostId)
        assertTrue(v5Hashed.playlist.usesLegacyShapeOnly)
    }

    @Test fun `the V5 hash is not accepted once real playlist state is present`() {
        val joinable = BoardCellSnapshot(
            cellId = cell, physicalBoardId = board, epoch = 1, sequence = 3,
            controllerId = "controller-npub", controllerTerm = 1, lineageId = "lineage",
            members = setOf("controller-npub", "host-npub"),
            playlist = playlist(), playlistRevision = 2,
        )
        assertFalse(joinable.copy(stateHash = BoardCellHash.computeLegacyV5(joinable)).hasValidHash())
        assertTrue(joinable.withComputedHash().hasValidHash())
    }

    /**
     * The durable store decodes with `ignoreUnknownKeys = false`, so a JSON
     * document written by the old build must still decode: the new playlist
     * fields all carry defaults, and those defaults are the non-joinable
     * meaning.
     */
    @Test fun `durable JSON written without the joinable fields decodes to a local playlist`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type" }
        // Exactly what the old build wrote: the current encoding with every
        // field the joinable playlist added removed again.
        val legacyOnly = setOf("sessionId", "currentIndex", "items")
        val legacyDocument = JsonObject(
            json.encodeToJsonElement(BoardPlaylistState(7, 1, listOf("a" to 40, "b" to 45)))
                .jsonObject.filterKeys { it in legacyOnly })
        assertEquals(legacyOnly, legacyDocument.keys)

        val decoded = json.decodeFromString<BoardPlaylistState>(legacyDocument.toString())

        assertEquals(listOf("a" to 40, "b" to 45), decoded.items)
        assertEquals(1, decoded.currentIndex)
        assertFalse(decoded.isJoinable)
        assertTrue(decoded.restAfterSeconds.isEmpty())
        assertTrue(decoded.members.isEmpty())
        assertNull(decoded.activeRest)
        assertNull(decoded.proposal)
        // And it re-encodes into the current shape without losing anything.
        assertEquals(decoded, json.decodeFromString<BoardPlaylistState>(json.encodeToString(decoded)))
    }

    /**
     * The rest and the request gained a canonical instant after the first
     * joinable-playlist build. A durable document written by that build has
     * neither, and must not be read as a rest that ended in 1970 or a request
     * that expired before it was made — normalization drops both rather than
     * honouring a nonsensical deadline.
     */
    @Test fun `durable JSON from the pre-instant build drops the rest and the request`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type" }
        val current = playlist(rest = rest(), proposal = proposal())
        val withoutInstants = JsonObject(
            json.encodeToJsonElement(current).jsonObject.mapValues { (key, value) ->
                when (key) {
                    "activeRest" -> JsonObject(value.jsonObject - "endsAtEpochMs")
                    "proposal" -> JsonObject(value.jsonObject - "expiresAtEpochMs")
                    else -> value
                }
            })

        val decoded = json.decodeFromString<BoardPlaylistState>(withoutInstants.toString())

        assertEquals(0L, decoded.activeRest!!.endsAtEpochMs)
        assertEquals(0L, decoded.proposal!!.expiresAtEpochMs)
        val normalized = BoardPlaylistPolicy.normalize(decoded)
        assertNull("a rest with no end must not survive", normalized.activeRest)
        assertNull("a request with no deadline must not survive", normalized.proposal)
        // The queue itself is untouched — only the two timed things go.
        assertEquals(current.items, normalized.items)
        assertEquals(current.hostId, normalized.hostId)
    }

    @Test fun `a joinable playlist survives the durable JSON round trip`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type" }
        val value = snapshot(playlist(
            rest = rest(),
            pending = BoardPlaylistPendingProjection("climb-a", 40,
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED),
            proposal = proposal()))

        val decoded = json.decodeFromString<BoardCellSnapshot>(json.encodeToString(value))

        assertEquals(value, decoded)
        assertTrue(decoded.hasValidHash())
    }
}
