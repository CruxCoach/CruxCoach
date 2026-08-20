package com.cruxcoach.android.boardcell

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BoardCellWireTest {
    private class AckStore : BoardCellDurableStore {
        private val acks = mutableMapOf<String, BoardCommandAck>()
        override fun persistSnapshot(snapshot: BoardCellSnapshot) = Unit
        override fun persistSnapshotWithAck(snapshot: BoardCellSnapshot, ack: BoardCommandAck) { acks[ack.commandId] = ack }
        override fun persistIntent(intent: BoardWriteIntent) = Unit
        override fun markPhysicalWriteSucceeded(intent: BoardWriteIntent) = Unit
        override fun commit(snapshot: BoardCellSnapshot, intent: BoardWriteIntent, ack: BoardCommandAck) { acks[ack.commandId] = ack }
        override fun recordAck(ack: BoardCommandAck) { acks[ack.commandId] = ack }
        override fun discardIntent(boardId: PhysicalBoardId, commandId: String) = Unit
        override fun pendingIntent(boardId: PhysicalBoardId): BoardWriteIntent? = null
        override fun commandAck(commandId: String): BoardCommandAck? = acks[commandId]
    }

    private class Link(
        override val localNpub: String,
        var direct: Set<String> = emptySet(),
        private val realm: String = "cell",
        var acceptsSends: Boolean = true,
    ) : AuthenticatedMeshLink {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val recycleReasons = mutableListOf<String>()
        override fun send(authenticatedPeerNpub: String, payload: ByteArray): Boolean {
            sent += authenticatedPeerNpub to payload; return acceptsSends
        }
        override fun directAuthenticatedPeers() = direct
        override fun activeRealmId() = realm
        override fun recycleTransport(reason: String): Boolean {
            recycleReasons += reason
            return true
        }
    }

    private fun frame(sender: String, message: BoardCellWireMessage, epoch: Long = 1, term: Long = 1,
        id: String = "message-0001") = BoardCellWireCodec.encode(BoardCellWireFrame(
        messageId = id, senderId = sender, realmId = "cell", cellId = BoardCellId("cell"),
        physicalBoardId = PhysicalBoardId("board"), epoch = epoch, controllerTerm = term, message = message))

    @Test fun `discovery claim from transit peer and forged sender are rejected`() = runTest {
        val link = Link("host")
        val transport = BoardCellMeshTransport(link)
        transport.attach(BoardCellCoordinator("host", transport, settleMs = 0))
        val claim = BoardCellClaim(PhysicalBoardId("board"), BoardCellId("cell"), "remote", 1, "lineage")
        assertTrue(transport.receive("remote", frame("remote", BoardCellWireMessage.DirectClaim(claim))) is BoardCellApplyResult.Rejected)
        assertTrue(transport.receive("attacker", frame("remote", BoardCellWireMessage.DirectClaim(claim), id = "message-0002")) is BoardCellApplyResult.Rejected)
    }

    @Test fun `wire rejects unsupported version bounds wrong realm and replay`() = runTest {
        val link = Link("host", setOf("remote"))
        val transport = BoardCellMeshTransport(link)
        transport.attach(BoardCellCoordinator("host", transport, settleMs = 0))
        val claim = BoardCellClaim(PhysicalBoardId("board"), BoardCellId("cell"), "remote", 1, "lineage")
        val valid = frame("remote", BoardCellWireMessage.DirectClaim(claim))
        assertNull(transport.receive("remote", valid))
        assertTrue(transport.receive("remote", valid) is BoardCellApplyResult.IgnoredStale)
        val decoded = BoardCellWireCodec.decode(valid)
        val wrongVersion = BoardCellWireCodec.encode(decoded.copy(version = 1, messageId = "message-v1"))
        assertTrue(transport.receive("remote", wrongVersion) is BoardCellApplyResult.Rejected)
        val wrongRealm = BoardCellWireCodec.encode(decoded.copy(realmId = "neighbor", messageId = "message-realm"))
        assertTrue(transport.receive("remote", wrongRealm) is BoardCellApplyResult.Rejected)
        assertTrue(transport.receive("remote", ByteArray(BoardCellMeshTransport.MAX_WIRE_BYTES + 1)) is BoardCellApplyResult.Rejected)
    }

    @Test fun `session command requires member term and exact base and returns correlated acknowledgements`() = runTest {
        val link = Link("host", setOf("member"))
        val transport = BoardCellMeshTransport(link)
        val coordinator = BoardCellCoordinator("host", transport, settleMs = 0)
        transport.attach(coordinator)
        val board = PhysicalBoardId("board")
        coordinator.beginClaim(board, BoardCellId("cell"), 1); coordinator.settle(board, 1)
        coordinator.joinMember(board, "member")
        val snapshot = coordinator.snapshot(board)!!; transport.rememberSnapshot(snapshot)
        val accepted = mutableListOf<InboundSessionCommand>()
        transport.onSessionCommand = { accepted += it }

        val stale = frame("member", BoardCellWireMessage.SessionCommand("stale-command", snapshot.playlistRevision + 1, byteArrayOf(7)),
            epoch = snapshot.epoch, term = snapshot.controllerTerm, id = "message-stale")
        assertTrue(transport.receive("member", stale) is BoardCellApplyResult.Rejected)
        assertEquals(BoardCommandStatus.REJECTED_STALE,
            (BoardCellWireCodec.decode(link.sent.last().second).message as BoardCellWireMessage.CommandAck).value.status)

        val context = BoardPlaylistCommandContext(null, BoardPlaylistCommandKind.ADD)
        val good = frame("member", BoardCellWireMessage.SessionCommand("good-command",
            snapshot.playlistRevision, byteArrayOf(8), context),
            epoch = snapshot.epoch, term = snapshot.controllerTerm, id = "message-good")
        assertNull(transport.receive("member", good))
        assertEquals("good-command", accepted.single().commandId)
        assertEquals(context, accepted.single().context)
        assertEquals(BoardCommandStatus.ACCEPTED,
            (BoardCellWireCodec.decode(link.sent.last().second).message as BoardCellWireMessage.CommandAck).value.status)
    }

    @Test fun `durable terminal acknowledgement supersedes cached accepted retry`() = runTest {
        val link = Link("host", setOf("member")); val transport = BoardCellMeshTransport(link)
        val store = AckStore()
        val coordinator = BoardCellCoordinator("host", transport, store, settleMs = 0)
        transport.attach(coordinator)
        val board = PhysicalBoardId("board")
        coordinator.beginClaim(board, BoardCellId("cell"), 1); coordinator.settle(board, 1)
        coordinator.joinMember(board, "member")
        val snapshot = coordinator.snapshot(board)!!; transport.rememberSnapshot(snapshot)
        val commandId = "accepted-then-committed"
        val command = BoardCellWireMessage.SessionCommand(commandId, snapshot.playlistRevision, byteArrayOf(8))

        assertNull(transport.receive("member", frame("member", command,
            snapshot.epoch, snapshot.controllerTerm, "message-first"), 2))
        assertEquals(BoardCommandStatus.ACCEPTED,
            (BoardCellWireCodec.decode(link.sent.last().second).message as BoardCellWireMessage.CommandAck).value.status)
        assertNotNull(coordinator.replacePlaylist(board, BoardPlaylistState(42, 0), 3,
            commandId, snapshot.sequence))

        assertNull(transport.receive("member", frame("member", command,
            snapshot.epoch, snapshot.controllerTerm, "message-retry"), 4))
        assertEquals(BoardCommandStatus.COMMITTED,
            (BoardCellWireCodec.decode(link.sent.last().second).message as BoardCellWireMessage.CommandAck).value.status)
    }

    /**
     * The gateway proxy claim is only as good as the sender behind it.
     *
     * It says "I am carrying a verb for an API-28 leaf of mine", and it is
     * accepted from an authenticated cell member and nobody else — the same
     * bar every other gateway assertion clears. What it buys is deliberately
     * less than membership, so a stranger gains nothing by setting it.
     */
    @Test fun `a leaf command is admitted from a cell member and refused from a stranger`() = runTest {
        val link = Link("host", setOf("gateway")); val transport = BoardCellMeshTransport(link)
        val coordinator = BoardCellCoordinator("host", transport, AckStore(), settleMs = 0)
        transport.attach(coordinator)
        val board = PhysicalBoardId("board")
        coordinator.beginClaim(board, BoardCellId("cell"), 1); coordinator.settle(board, 1)
        coordinator.joinMember(board, "gateway")
        val snapshot = coordinator.snapshot(board)!!; transport.rememberSnapshot(snapshot)
        val admitted = mutableListOf<InboundLeafCommand>()
        transport.onLeafCommand = { admitted += it }

        // The gateway is a cell member. It never joined the playlist, and it
        // does not have to: it is lending its authenticated hop.
        assertNull(transport.receive("gateway", frame("gateway",
            BoardCellWireMessage.LeafSessionCommand("leaf-command-01",
                snapshot.playlistRevision, byteArrayOf(8)),
            snapshot.epoch, snapshot.controllerTerm, "message-leaf")))
        assertEquals(listOf("leaf-command-01"), admitted.map { it.commandId })
        assertEquals("gateway", admitted.single().senderId)

        // A node outside the cell gets nothing from claiming to have a leaf.
        assertTrue(transport.receive("stranger", frame("stranger",
            BoardCellWireMessage.LeafSessionCommand("leaf-command-02",
                snapshot.playlistRevision, byteArrayOf(8)),
            snapshot.epoch, snapshot.controllerTerm, "message-stranger"))
            is BoardCellApplyResult.Rejected)
        assertEquals(1, admitted.size)
    }

    @Test fun `a leaf retry carries no payload and is deduplicated like any command`() = runTest {
        val link = Link("host", setOf("gateway")); val transport = BoardCellMeshTransport(link)
        val store = AckStore()
        val coordinator = BoardCellCoordinator("host", transport, store, settleMs = 0)
        transport.attach(coordinator)
        val board = PhysicalBoardId("board")
        coordinator.beginClaim(board, BoardCellId("cell"), 1); coordinator.settle(board, 1)
        coordinator.joinMember(board, "gateway")
        val snapshot = coordinator.snapshot(board)!!; transport.rememberSnapshot(snapshot)
        val admitted = mutableListOf<InboundLeafCommand>()
        transport.onLeafCommand = { admitted += it }
        val retry = BoardCellWireMessage.LeafRetryProjection("leaf-retry-01",
            snapshot.playlistRevision)

        assertNull(transport.receive("gateway", frame("gateway", retry,
            snapshot.epoch, snapshot.controllerTerm, "message-retry-1")))
        assertNull(admitted.single().payload)

        // A resend of the same command id is answered from the cache rather
        // than re-running the projection.
        assertNull(transport.receive("gateway", frame("gateway", retry,
            snapshot.epoch, snapshot.controllerTerm, "message-retry-2")))
        assertEquals(1, admitted.size)
    }

    @Test fun `reordered event freezes and requests full snapshot`() = runTest {
        val link = Link("member")
        val transport = BoardCellMeshTransport(link)
        val coordinator = BoardCellCoordinator("member", transport, settleMs = 0)
        transport.attach(coordinator)
        val initial = BoardCellSnapshot(BoardCellId("cell"), PhysicalBoardId("board"), 1, 0,
            "controller", lineageId = "lineage", members = setOf("controller", "member")).withComputedHash()
        coordinator.restoreTrustedSnapshot(initial, 10); transport.rememberSnapshot(initial)
        val event = BoardCellEvent.ProjectCommitted(BoardProjection("later", 40), "command")
        val next = BoardCellReplica.reduce(initial, event, 2)
        val envelope = BoardCellEnvelope(initial.cellId, initial.physicalBoardId, initial.epoch,
            initial.controllerTerm, 2, initial.stateHash, event, next.stateHash)
        val result = transport.receive("controller", frame("controller", BoardCellWireMessage.Event(envelope),
            epoch = 1, term = 1, id = "message-gap"), 11)
        assertTrue(result is BoardCellApplyResult.NeedSnapshot)
        assertTrue(BoardCellWireCodec.decode(link.sent.last().second).message is BoardCellWireMessage.SnapshotRequest)
    }

    @Test fun `controller anti entropy pushes canonical snapshot to stale member`() = runTest {
        val link = Link("host")
        val transport = BoardCellMeshTransport(link)
        val coordinator = BoardCellCoordinator("host", transport, settleMs = 0)
        transport.attach(coordinator)
        val board = PhysicalBoardId("board")
        coordinator.beginClaim(board, BoardCellId("cell"), 1)
        coordinator.settle(board, 1)
        coordinator.joinMember(board, "member")
        val snapshot = coordinator.snapshot(board)!!
        transport.rememberSnapshot(snapshot)
        link.sent.clear()

        val stale = BoardCellWireMessage.AntiEntropy(snapshot.sequence - 1, "stale-hash")
        assertNull(transport.receive("member", frame("member", stale, snapshot.epoch,
            snapshot.controllerTerm, "anti-entropy-stale"), 2))

        assertEquals("member", link.sent.single().first)
        val response = BoardCellWireCodec.decode(link.sent.single().second).message
        assertTrue(response is BoardCellWireMessage.Snapshot)
        assertEquals(snapshot.stateHash, (response as BoardCellWireMessage.Snapshot).value.stateHash)
    }

    @Test fun `periodic anti entropy never occupies durable outbox`() = runTest {
        val link = Link("host", acceptsSends = false)
        val transport = BoardCellMeshTransport(link)
        val snapshot = BoardCellSnapshot(BoardCellId("cell"), PhysicalBoardId("board"), 1, 0,
            "host", lineageId = "lineage", members = setOf("host", "offline")).withComputedHash()
        transport.rememberSnapshot(snapshot)

        repeat(20) { transport.antiEntropy() }
        assertEquals(20, link.sent.size)
        link.acceptsSends = true
        transport.retryOutbox()
        assertEquals("best-effort digests must not be replayed", 20, link.sent.size)
    }

    @Test fun `realm switch drops old snapshots and queued frames`() = runTest {
        val link = Link("host", acceptsSends = false)
        val transport = BoardCellMeshTransport(link)
        val snapshot = BoardCellSnapshot(BoardCellId("cell"), PhysicalBoardId("board"), 1, 0,
            "host", lineageId = "lineage", members = setOf("host", "offline")).withComputedHash()

        transport.publishSnapshot(snapshot)
        assertEquals(1, link.sent.size)
        transport.resetForRealm()
        link.acceptsSends = true
        transport.retryOutbox()
        transport.antiEntropy()

        assertEquals("old realm state must never be retried in the new realm", 1, link.sent.size)
    }

    @Test fun `superseded controller heartbeats never occupy durable outbox`() = runTest {
        val link = Link("host", acceptsSends = false)
        val transport = BoardCellMeshTransport(link)
        val snapshot = BoardCellSnapshot(BoardCellId("cell"), PhysicalBoardId("board"), 1, 0,
            "host", lineageId = "lineage", members = setOf("host", "offline")).withComputedHash()
        transport.rememberSnapshot(snapshot)
        repeat(20) { index ->
            val event = BoardCellEvent.ControllerHeartbeat(index.toLong() + 1)
            val next = BoardCellReplica.reduce(snapshot, event, index.toLong() + 1)
            transport.publishEvent(BoardCellEnvelope(snapshot.cellId, snapshot.physicalBoardId,
                snapshot.epoch, snapshot.controllerTerm, next.sequence, snapshot.stateHash,
                event, next.stateHash))
        }
        assertEquals(20, link.sent.size)
        link.acceptsSends = true
        transport.retryOutbox()
        assertEquals("superseded heartbeats must not be replayed", 20, link.sent.size)
    }

    @Test fun `only a member can request control and only controller can decide`() = runTest {
        val hostLink = Link("host")
        val hostTransport = BoardCellMeshTransport(hostLink)
        val hostCoordinator = BoardCellCoordinator("host", hostTransport, settleMs = 0)
        hostTransport.attach(hostCoordinator)
        val board = PhysicalBoardId("board")
        hostCoordinator.beginClaim(board, BoardCellId("cell"), 1)
        hostCoordinator.settle(board, 1)
        hostCoordinator.joinMember(board, "member")
        val snapshot = hostCoordinator.snapshot(board)!!
        hostTransport.rememberSnapshot(snapshot)
        val received = mutableListOf<BoardCellControllerRequest>()
        hostTransport.onControllerRequest = { _, request -> received += request }
        val request = BoardCellControllerRequest("request-0001", "member")

        assertNull(hostTransport.receive("member", frame("member",
            BoardCellWireMessage.ControllerRequest(request), snapshot.epoch,
            snapshot.controllerTerm, "controller-request")))
        assertEquals(request, received.single())
        assertTrue(hostTransport.receive("attacker", frame("attacker",
            BoardCellWireMessage.ControllerRequest(request.copy(requesterId = "attacker")),
            snapshot.epoch, snapshot.controllerTerm, "controller-attacker")) is BoardCellApplyResult.Rejected)

        val memberLink = Link("member")
        val memberTransport = BoardCellMeshTransport(memberLink)
        val memberCoordinator = BoardCellCoordinator("member", memberTransport, settleMs = 0)
        memberTransport.attach(memberCoordinator)
        memberCoordinator.restoreTrustedSnapshot(snapshot, 2)
        memberTransport.rememberSnapshot(snapshot)
        val decisions = mutableListOf<BoardCellControllerDecision>()
        memberTransport.onControllerDecision = { _, decision -> decisions += decision }
        val accepted = BoardCellControllerDecision(request.requestId, true)
        assertNull(memberTransport.receive("host", frame("host",
            BoardCellWireMessage.ControllerDecision(accepted), snapshot.epoch,
            snapshot.controllerTerm, "controller-decision")))
        assertEquals(accepted, decisions.single())
        assertTrue(memberTransport.receive("other", frame("other",
            BoardCellWireMessage.ControllerDecision(accepted), snapshot.epoch,
            snapshot.controllerTerm, "decision-forged")) is BoardCellApplyResult.Rejected)
    }

    @Test fun `direct neighbor sponsorship becomes a member approval request`() = runTest {
        val board = PhysicalBoardId("board")
        val hostLink = Link("host")
        val hostTransport = BoardCellMeshTransport(hostLink)
        val host = BoardCellCoordinator("host", hostTransport, settleMs = 0)
        hostTransport.attach(host)
        host.beginClaim(board, BoardCellId("cell"), 1)
        host.settle(board, 1)
        host.joinMember(board, "sponsor")
        val shared = host.snapshot(board)!!
        hostTransport.rememberSnapshot(shared)
        val admissionRequests = mutableListOf<BoardCellJoinRequest>()
        hostTransport.onAdmissionRequested = { _, request -> admissionRequests += request }

        val sponsorLink = Link("sponsor", direct = setOf("candidate"))
        val sponsorTransport = BoardCellMeshTransport(sponsorLink)
        val sponsor = BoardCellCoordinator("sponsor", sponsorTransport, settleMs = 0)
        sponsorTransport.attach(sponsor)
        sponsor.restoreTrustedSnapshot(shared, 2)
        sponsorTransport.rememberSnapshot(shared)

        assertTrue(sponsorTransport.sponsorMember(shared, "candidate"))
        val (target, encoded) = sponsorLink.sent.single()
        assertEquals("host", target)
        val sponsoredFrame = BoardCellWireCodec.decode(encoded)
        assertTrue(sponsoredFrame.message is BoardCellWireMessage.MemberJoinRequest)
        assertNull(hostTransport.receive("sponsor", encoded, 3))
        assertFalse("candidate" in host.snapshot(board)!!.members)
        assertEquals("candidate", admissionRequests.single().candidateId)

        // Only an explicit approval commits membership.
        host.joinMember(board, "candidate", 3)
        assertTrue("candidate" in host.snapshot(board)!!.members)

        // Process restart keeps the per-realm npub but loses in-memory state.
        // Sponsoring that existing identity must deliver a full snapshot.
        hostLink.sent.clear()
        val rejoin = BoardCellJoinRequest("join-reconnect-01", "candidate", "sponsor")
        assertNull(hostTransport.receive("sponsor", frame("sponsor",
            BoardCellWireMessage.MemberJoinRequest(rejoin), shared.epoch,
            shared.controllerTerm, "join-reconnect-frame"), 4))
        assertEquals("candidate", hostLink.sent.single().first)
        assertTrue(BoardCellWireCodec.decode(hostLink.sent.single().second).message is
            BoardCellWireMessage.Snapshot)

        val forged = BoardCellJoinRequest("join-forged-01", "other", "sponsor")
        assertTrue(hostTransport.receive("attacker", frame("attacker",
            BoardCellWireMessage.MemberJoinRequest(forged), shared.epoch,
            shared.controllerTerm, "join-forged-frame"), 4) is BoardCellApplyResult.Rejected)
    }

    @Test fun `resumed durable controller admits waiting direct peer and publishes full snapshot`() = runTest {
        val board = PhysicalBoardId("board")
        val cell = BoardCellId("cell")
        val durable = BoardCellSnapshot(
            cellId = cell,
            physicalBoardId = board,
            epoch = 1,
            sequence = 7,
            controllerId = "old-controller",
            controllerTerm = 3,
            controllerHeartbeat = 4,
            lineageId = "existing-lineage",
            members = setOf("old-controller"),
        ).withComputedHash()
        val link = Link("old-controller", direct = setOf("waiting-peer"))
        val transport = BoardCellMeshTransport(link)
        val resumed = BoardCellCoordinator("old-controller", transport, settleMs = 0)
        transport.attach(resumed)

        assertTrue(resumed.restoreTrustedSnapshot(durable, 100) is BoardCellApplyResult.Applied)
        transport.rememberSnapshot(durable)
        link.sent.clear()
        assertNotNull(resumed.joinMember(board, "waiting-peer", 101))

        val current = resumed.snapshot(board)!!
        assertEquals(setOf("old-controller", "waiting-peer"), current.members)
        val welcome = link.sent.asSequence()
            .filter { it.first == "waiting-peer" }
            .map { BoardCellWireCodec.decode(it.second).message }
            .filterIsInstance<BoardCellWireMessage.Snapshot>()
            .lastOrNull()
        assertNotNull("new direct peer must receive an authoritative welcome snapshot", welcome)
        assertEquals(current.stateHash, welcome!!.value.stateHash)
        assertEquals(8, welcome.value.sequence)
    }

    @Test fun `candidate accepts admission result only from the controller that prompted it`() = runTest {
        val candidateLink = Link("candidate")
        val candidateTransport = BoardCellMeshTransport(candidateLink)
        candidateTransport.attach(BoardCellCoordinator("candidate", candidateTransport, settleMs = 0))
        val prompt = BoardCellAdmissionPrompt(
            "admission-request-01", "candidate", "sponsor", 1_000L, 31_000L,
        )
        val received = mutableListOf<BoardCellAdmissionResult>()
        candidateTransport.onAdmissionResult = { received += it }

        assertNull(candidateTransport.receive("host", frame(
            "host", BoardCellWireMessage.MemberAdmissionPrompt(prompt), id = "admission-prompt-01",
        )))
        val result = BoardCellAdmissionResult(
            prompt.requestId, prompt.candidateId, approved = false, retryAfterEpochMs = 91_000L,
        )
        assertTrue(candidateTransport.receive("attacker", frame(
            "attacker", BoardCellWireMessage.MemberAdmissionResult(result), id = "admission-result-forged",
        )) is BoardCellApplyResult.Rejected)
        assertNull(candidateTransport.receive("host", frame(
            "host", BoardCellWireMessage.MemberAdmissionResult(result), id = "admission-result-valid",
        )))
        assertEquals(result, received.single())
    }

    @Test fun `authenticated multi hop heartbeat keeps member live without direct peer view`() = runTest {
        val board = PhysicalBoardId("board")
        val link = Link("host", direct = emptySet())
        val transport = BoardCellMeshTransport(link)
        val host = BoardCellCoordinator("host", transport, settleMs = 0)
        transport.attach(host)
        host.beginClaim(board, BoardCellId("cell"), 1)
        host.settle(board, 1)
        host.joinMember(board, "member", 1)
        val snapshot = host.snapshot(board)!!
        transport.rememberSnapshot(snapshot)

        assertNull(transport.receive("member", frame("member",
            BoardCellWireMessage.MemberHeartbeat(2), snapshot.epoch,
            snapshot.controllerTerm, "member-heartbeat-01"), 5))
        assertTrue(host.evictExpiredMembers(board, 10, 6).isEmpty())
        assertTrue("member" in host.snapshot(board)!!.members)
        assertEquals(1, host.evictExpiredMembers(board, 11, 6).size)
    }

    @Test fun `voluntary leave request is authenticated and sequenced by controller`() = runTest {
        val board = PhysicalBoardId("board")
        val link = Link("host")
        val transport = BoardCellMeshTransport(link)
        val host = BoardCellCoordinator("host", transport, settleMs = 0)
        transport.attach(host)
        host.beginClaim(board, BoardCellId("cell"), 1)
        host.settle(board, 1)
        host.joinMember(board, "member", 1)
        val snapshot = host.snapshot(board)!!
        transport.rememberSnapshot(snapshot)
        val request = BoardCellWireMessage.MemberLeaveRequest(
            BoardCellLeaveRequest("member-leave-01"))

        assertNull(transport.receive("member", frame("member", request, snapshot.epoch,
            snapshot.controllerTerm, "member-leave-frame"), 2))
        assertFalse("member" in host.snapshot(board)!!.members)
        assertTrue(link.sent.any { (target, bytes) -> target == "member" &&
            ((BoardCellWireCodec.decode(bytes).message as? BoardCellWireMessage.Event)?.value?.event
                is BoardCellEvent.MemberLeft) })
        assertEquals(listOf("last remote member left voluntarily"), link.recycleReasons)
    }

    @Test fun `removed stale member receives exclusion snapshot on reconnect digest`() = runTest {
        val board = PhysicalBoardId("board")
        val link = Link("host")
        val transport = BoardCellMeshTransport(link)
        val host = BoardCellCoordinator("host", transport, settleMs = 0)
        transport.attach(host)
        host.beginClaim(board, BoardCellId("cell"), 1)
        host.settle(board, 1)
        host.joinMember(board, "member", 1)
        val stale = host.snapshot(board)!!
        host.leaveMember(board, "member", BoardCellMemberLeaveReason.LIVENESS_TIMEOUT)
        val current = host.snapshot(board)!!
        transport.rememberSnapshot(current)
        link.sent.clear()

        val digest = BoardCellWireMessage.AntiEntropy(stale.sequence, stale.stateHash)
        assertTrue(transport.receive("member", frame("member", digest, current.epoch,
            current.controllerTerm, "stale-member-digest"), 9) is BoardCellApplyResult.Rejected)
        val repair = BoardCellWireCodec.decode(link.sent.single().second).message
            as BoardCellWireMessage.Snapshot
        assertFalse("member" in repair.value.members)
    }

    @Test fun `frozen controller cannot extend membership on a stale history`() = runTest {
        val board = PhysicalBoardId("board")
        val link = Link("host", direct = setOf("member", "candidate"))
        val transport = BoardCellMeshTransport(link)
        val coordinator = BoardCellCoordinator("host", transport, settleMs = 0,
            heartbeatTimeoutMs = 10)
        transport.attach(coordinator)
        coordinator.beginClaim(board, BoardCellId("cell"), 1)
        coordinator.settle(board, 1)
        coordinator.joinMember(board, "member")
        coordinator.expireLocalDeadlines(20)
        val frozen = coordinator.snapshot(board)!!
        transport.rememberSnapshot(frozen)

        assertEquals(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER, frozen.availability)
        assertNull(coordinator.joinMember(board, "candidate"))

        val memberLink = Link("member", direct = setOf("candidate"))
        val memberTransport = BoardCellMeshTransport(memberLink)
        assertFalse(memberTransport.sponsorMember(frozen, "candidate"))
    }

    @Test fun `only an explicit live decline starts admission cooldown`() {
        val now = 10_000L
        val cooldown = 60_000L

        assertEquals(now + cooldown, BoardCellAdmissionCooldownPolicy.retryAfterEpochMs(
            approved = false, expired = false, nowEpochMs = now, cooldownMs = cooldown))
        assertEquals(0L, BoardCellAdmissionCooldownPolicy.retryAfterEpochMs(
            approved = false, expired = true, nowEpochMs = now, cooldownMs = cooldown))
        assertEquals(0L, BoardCellAdmissionCooldownPolicy.retryAfterEpochMs(
            approved = true, expired = false, nowEpochMs = now, cooldownMs = cooldown))
    }
}
