package com.cruxcoach.android.boardcell

import org.junit.Assert.*
import org.junit.Test

/**
 * The product rules of the one joinable playlist per BoardCell, exercised
 * directly on the pure policy so each rule is pinned without a mesh.
 *
 * Time is always explicit here. Every canonical deadline the playlist carries
 * is a UTC instant stamped by whichever device serialized the commit, so the
 * tests read like the real thing: a fixed "now", a deadline derived from it,
 * and a later "now" to look at it from.
 */
class BoardPlaylistPolicyTest {

    /** 2026-08-17T12:00:00Z, chosen only so the numbers below read easily. */
    private val now = 1_786_968_000_000L
    private val second = 1_000L

    private fun start(
        items: List<Pair<String, Int>> = listOf("a" to 40),
        rests: List<Int> = emptyList(),
        requestId: String = "request-0001",
        commandId: String = "command-0001",
    ) = BoardPlaylistControl.Start(commandId, 0, requestId, 7, items, rests)

    private fun apply(
        current: BoardPlaylistState,
        senderId: String,
        control: BoardPlaylistControl,
        at: Long = now,
        authority: BoardPlaylistAuthority = BoardPlaylistAuthority.MEMBER,
        cellMembers: Set<String> = setOf(senderId),
    ) = BoardPlaylistPolicy.apply(current, senderId, control, at, authority, cellMembers)

    private fun commit(outcome: BoardPlaylistPolicy.Outcome): BoardPlaylistState =
        (outcome as BoardPlaylistPolicy.Outcome.Commit).playlist

    private fun running(vararg members: String) = BoardPlaylistPolicy.normalize(BoardPlaylistState(
        sessionId = 7, currentIndex = 0, items = listOf("a" to 40),
        hostId = members.first(), members = members.toList()))

    // ===== Start on an empty cell =====

    @Test fun `any member starting on an empty cell becomes playlist host and member`() {
        val result = commit(apply(BoardPlaylistState(), "member-npub",
            start(listOf("a" to 40, "b" to 45))))

        assertEquals("member-npub", result.hostId)
        assertEquals(listOf("member-npub"), result.members)
        assertEquals(0, result.currentIndex)
        assertEquals(listOf("a" to 40, "b" to 45), result.items)
        assertTrue(result.isJoinable)
        assertNull(result.proposal)
    }

    @Test fun `a forty person board starts one playlist for everyone`() {
        val members = (0 until 40).map { "member-%02d".format(it) }.toSet()
        val result = commit(apply(
            BoardPlaylistState(),
            "member-17",
            start(),
            cellMembers = members,
        ))

        assertEquals(40, result.members.size)
        assertEquals(members, result.members.toSet())
    }

    @Test fun `starting is refused without entries`() {
        assertTrue(apply(BoardPlaylistState(), "member", start(items = emptyList()))
            is BoardPlaylistPolicy.Outcome.Reject)
    }

    @Test fun `the rest plan travels with the playlist and is index-parallel`() {
        val result = commit(apply(BoardPlaylistState(), "host",
            start(listOf("a" to 40, "b" to 40, "c" to 40), rests = listOf(120, 300))))

        assertEquals(listOf(120, 300, 0), result.restAfterSeconds)
        assertEquals(300, result.restAt(1))
        assertEquals(0, result.restAt(2))
    }

    @Test fun `rest values beyond the bound are clamped, not rejected`() {
        val result = commit(apply(BoardPlaylistState(), "host",
            start(listOf("a" to 40), rests = listOf(999_999))))
        assertEquals(listOf(BoardPlaylistPolicy.MAX_REST_SECONDS), result.restAfterSeconds)
    }

    // ===== The board group owns one shared playlist =====

    @Test fun `starting while a board playlist runs appends without approval`() {
        val base = BoardPlaylistPolicy.normalize(BoardPlaylistState(
            sessionId = 7, currentIndex = 1, items = listOf("a" to 40, "b" to 40),
            restAfterSeconds = listOf(30, 40), hostId = "host", members = listOf("host")))
        val appended = commit(apply(base, "other",
            BoardPlaylistControl.Start("command-0001", 0, "request-0001", 9,
                listOf("x" to 40), listOf(90)),
            cellMembers = setOf("host", "other")))

        assertEquals(listOf("a" to 40, "b" to 40, "x" to 40), appended.items)
        assertEquals(listOf(30, 40, 90), appended.restAfterSeconds)
        assertEquals(1, appended.currentIndex)
        assertEquals(7, appended.sessionId)
        assertEquals(listOf("host", "other"), appended.members)
        assertNull(appended.proposal)
    }

    // ===== Membership, rights and handover =====

    @Test fun `an authenticated member joins without host approval`() {
        val joined = commit(apply(running("host"), "guest", BoardPlaylistControl.Join("command-0002", 0)))
        assertEquals(listOf("host", "guest"), joined.members)
        assertEquals("host", joined.hostId)
    }

    @Test fun `joining twice is idempotent`() {
        assertTrue(apply(running("host", "guest"), "guest", BoardPlaylistControl.Join("command-0002", 0))
            is BoardPlaylistPolicy.Outcome.Accepted)
    }

    @Test fun `every member may end the board playlist`() {
        assertEquals(BoardPlaylistState(), commit(apply(
            running("host", "guest"), "guest", BoardPlaylistControl.End("command-0002", 0))))
    }

    @Test fun `ending with the last member clears the playlist`() {
        assertEquals(BoardPlaylistState(), commit(apply(
            running("host"), "host", BoardPlaylistControl.End("command-0002", 0))))
    }

    @Test fun `a non-member may neither end nor edit`() {
        assertTrue(apply(running("host"), "outsider", BoardPlaylistControl.End("command-0002", 0))
            is BoardPlaylistPolicy.Outcome.Reject)
        assertTrue(apply(running("host"), "outsider",
            BoardPlaylistControl.RetryProjection("command-0003", 0))
            is BoardPlaylistPolicy.Outcome.Reject)
        assertTrue(apply(running("host"), "outsider",
            BoardPlaylistControl.SetRest("command-0004", 0, 0, 60))
            is BoardPlaylistPolicy.Outcome.Reject)
        assertFalse(BoardPlaylistPolicy.mayEditQueue(running("host"), "outsider",
            BoardPlaylistAuthority.MEMBER))
    }

    @Test fun `every member may retry the projection`() {
        assertTrue(apply(running("host", "guest"), "guest",
            BoardPlaylistControl.RetryProjection("command-0002", 0))
            is BoardPlaylistPolicy.Outcome.Accepted)
        assertTrue(BoardPlaylistPolicy.mayEditQueue(running("host", "guest"), "guest",
            BoardPlaylistAuthority.MEMBER))
    }

    // ===== The Android-9 leaf's bounded proxy authority =====

    @Test fun `a gateway proxy may edit the queue for its joined leaf`() {
        // The gateway never joined the playlist itself; it is carrying an
        // API-28 leaf's verb, which is exactly what that leaf is allowed to do.
        assertTrue(BoardPlaylistPolicy.mayEditQueue(running("host"), "gateway",
            BoardPlaylistAuthority.GATEWAY_PROXY))
        assertTrue(apply(running("host"), "gateway",
            BoardPlaylistControl.RetryProjection("command-0002", 0),
            authority = BoardPlaylistAuthority.GATEWAY_PROXY)
            is BoardPlaylistPolicy.Outcome.Accepted)
    }

    @Test fun `a gateway proxy may not start, end, join, leave or schedule rests`() {
        val proxy = BoardPlaylistAuthority.GATEWAY_PROXY
        listOf(
            start(),
            BoardPlaylistControl.Decide("command-0002", 0, "request-0001",
                BoardPlaylistProposalDecision.REPLACE),
            BoardPlaylistControl.Join("command-0003", 0),
            BoardPlaylistControl.Leave("command-0004", 0),
            BoardPlaylistControl.End("command-0005", 0),
            BoardPlaylistControl.SetRest("command-0006", 0, 0, 60),
            BoardPlaylistControl.RestStarted("command-0007", 0, 60, 0),
            BoardPlaylistControl.RestEnded("command-0008", 0),
        ).forEach { control ->
            val outcome = apply(running("host"), "gateway", control, authority = proxy)
            val reject = requireIs<BoardPlaylistPolicy.Outcome.Reject>(outcome)
            assertTrue("${control.javaClass.simpleName}: ${reject.reason}",
                reject.reason.contains("GATT leaf"))
        }
    }

    @Test fun `a gateway proxy cannot start a playlist on an empty cell either`() {
        assertTrue(apply(BoardPlaylistState(), "gateway", start(),
            authority = BoardPlaylistAuthority.GATEWAY_PROXY)
            is BoardPlaylistPolicy.Outcome.Reject)
    }

    // ===== Host succession =====

    @Test fun `playlist-only leave is an idempotent no-op`() {
        assertTrue(apply(running("host", "b", "c"), "host",
            BoardPlaylistControl.Leave("command-0002", 0, successorId = "c"))
            is BoardPlaylistPolicy.Outcome.Accepted)
    }

    @Test fun `losing the host unexpectedly promotes the longest-active member`() {
        val survived = BoardPlaylistPolicy.withoutMember(running("host", "b", "c"), "host")

        assertEquals("b", survived.hostId)
        assertEquals(listOf("b", "c"), survived.members)
        assertEquals(listOf("a" to 40), survived.items)
    }

    @Test fun `the playlist ends when its last member is gone`() {
        assertEquals(BoardPlaylistState(),
            BoardPlaylistPolicy.withoutMember(running("host"), "host"))
    }

    // ===== Rest semantics =====

    /** A consistent start/end pair, which is the only kind normalize keeps. */
    private fun armed(
        totalSeconds: Int,
        generation: Long,
        nextIndex: Int,
        startedAt: Long = now,
    ) = BoardPlaylistRest(totalSeconds, generation, nextIndex,
        endsAtEpochMs = startedAt + totalSeconds * second, startedAtEpochMs = startedAt)

    private fun withRestPlan(
        items: List<Pair<String, Int>> = listOf("a" to 40, "b" to 40),
        rests: List<Int> = listOf(120, 0),
        index: Int = 0,
        rest: BoardPlaylistRest? = null,
    ) = BoardPlaylistPolicy.normalize(BoardPlaylistState(
        sessionId = 7, currentIndex = index, items = items, restAfterSeconds = rests,
        hostId = "host", members = listOf("host"), activeRest = rest))

    @Test fun `advancing arms the planned rest with a canonical end instant`() {
        val advanced = requireValue(BoardPlaylistOps.next(withRestPlan(), now))

        assertEquals(1, advanced.currentIndex)
        val rest = requireValue(advanced.activeRest)
        assertEquals(120, rest.totalSeconds)
        assertEquals(1, rest.nextIndex)
        assertEquals(1L, rest.generation)
        assertEquals(now + 120 * second, rest.endsAtEpochMs)
    }

    @Test fun `a peer joining part-way through sees the remaining time, not the whole rest`() {
        val rest = requireValue(requireValue(BoardPlaylistOps.next(withRestPlan(), now)).activeRest)

        // 40 s in: 80 s left, not another full two minutes.
        assertEquals(80, rest.remainingSeconds(now + 40 * second))
        assertEquals(1, rest.remainingSeconds(rest.endsAtEpochMs - 1))
        assertEquals(0, rest.remainingSeconds(rest.endsAtEpochMs))
        assertEquals(0, rest.remainingSeconds(rest.endsAtEpochMs + 10 * second))
        assertTrue(rest.hasExpired(rest.endsAtEpochMs))
        assertFalse(rest.hasExpired(rest.endsAtEpochMs - 1))
    }

    @Test fun `a clock running behind the arming device never shows more than was planned`() {
        val rest = requireValue(requireValue(BoardPlaylistOps.next(withRestPlan(), now)).activeRest)
        assertEquals(120, rest.remainingSeconds(now - 60 * second))
    }

    @Test fun `each started rest gets its own generation and its own end`() {
        val base = withRestPlan(
            items = listOf("a" to 40, "b" to 40, "c" to 40), rests = listOf(60, 90, 0))

        val first = requireValue(BoardPlaylistOps.next(base, now))
        val second = requireValue(BoardPlaylistOps.next(first, now + 60 * this.second))

        assertEquals(1L, requireValue(first.activeRest).generation)
        assertEquals(now + 60 * this.second, requireValue(first.activeRest).endsAtEpochMs)
        assertEquals(2L, requireValue(second.activeRest).generation)
        assertEquals(90, requireValue(second.activeRest).totalSeconds)
        assertEquals(now + 150 * this.second, requireValue(second.activeRest).endsAtEpochMs)
    }

    @Test fun `advancing past an entry with no planned rest clears any running one`() {
        val base = withRestPlan(rests = listOf(0, 0), rest = armed(60, 4, 0))
        assertNull(requireValue(BoardPlaylistOps.next(base, now)).activeRest)
    }

    @Test fun `jumping around the queue is an override and cancels the rest`() {
        val base = withRestPlan(index = 1, rest = armed(60, 4, 1))

        assertNull(requireValue(BoardPlaylistOps.setCurrent(base, 0)).activeRest)
        assertNull(requireValue(BoardPlaylistOps.previous(base)).activeRest)
    }

    @Test fun `removing the entry a rest is waiting on ends the rest`() {
        val base = withRestPlan(
            items = listOf("a" to 40, "b" to 40, "c" to 40), rests = listOf(0, 0, 0), index = 1,
            rest = armed(120, 3, 1))

        // The climb the group is resting in front of is gone; counting down
        // towards whatever slid into its index would be meaningless.
        assertNull(requireValue(BoardPlaylistOps.remove(base, 1)).activeRest)
    }

    @Test fun `removing some other entry keeps the rest and follows the index`() {
        val base = withRestPlan(
            items = listOf("a" to 40, "b" to 40, "c" to 40), rests = listOf(0, 0, 0), index = 2,
            rest = armed(120, 3, 2))

        val removed = requireValue(BoardPlaylistOps.remove(base, 0))

        assertEquals(1, removed.currentIndex)
        val rest = requireValue(removed.activeRest)
        assertEquals(1, rest.nextIndex)
        assertEquals(now + 120_000, rest.endsAtEpochMs)
        assertEquals(3L, rest.generation)
    }

    @Test fun `moving the current entry carries the running rest with it`() {
        val base = withRestPlan(
            items = listOf("a" to 40, "b" to 40, "c" to 40), rests = listOf(0, 0, 0), index = 0,
            rest = armed(120, 3, 0))

        val moved = requireValue(BoardPlaylistOps.move(base, 0, 2))

        assertEquals(2, moved.currentIndex)
        val rest = requireValue(moved.activeRest)
        assertEquals(2, rest.nextIndex)
        assertEquals(now + 120_000, rest.endsAtEpochMs)
    }

    @Test fun `an explicit rest command also carries a canonical end`() {
        val started = commit(apply(running("host"), "host",
            BoardPlaylistControl.RestStarted("command-0002", 0, 90, 0)))
        val rest = requireValue(started.activeRest)
        assertEquals(90, rest.totalSeconds)
        assertEquals(now + 90 * second, rest.endsAtEpochMs)
    }

    @Test fun `a member may reschedule the rest that follows an entry`() {
        val updated = commit(apply(running("host", "guest"), "guest",
            BoardPlaylistControl.SetRest("command-0002", 0, 0, 240)))
        assertEquals(listOf(240), updated.restAfterSeconds)
    }

    @Test fun `ending a rest nobody started is accepted without a change`() {
        assertTrue(apply(running("host"), "host", BoardPlaylistControl.RestEnded("command-0002", 0))
            is BoardPlaylistPolicy.Outcome.Accepted)
    }

    // ===== Queue edits keep the rest plan aligned =====

    @Test fun `removing an entry removes its planned rest with it`() {
        val base = BoardPlaylistPolicy.normalize(BoardPlaylistState(
            sessionId = 7, currentIndex = 2, items = listOf("a" to 40, "b" to 40, "c" to 40),
            restAfterSeconds = listOf(10, 20, 30), hostId = "host", members = listOf("host")))

        val removed = requireValue(BoardPlaylistOps.remove(base, 1))

        assertEquals(listOf("a" to 40, "c" to 40), removed.items)
        assertEquals(listOf(10, 30), removed.restAfterSeconds)
        assertEquals(1, removed.currentIndex)
    }

    @Test fun `moving an entry carries its planned rest along`() {
        val base = BoardPlaylistPolicy.normalize(BoardPlaylistState(
            sessionId = 7, currentIndex = 0, items = listOf("a" to 40, "b" to 40, "c" to 40),
            restAfterSeconds = listOf(10, 20, 30), hostId = "host", members = listOf("host")))

        val moved = requireValue(BoardPlaylistOps.move(base, 0, 2))

        assertEquals(listOf("b" to 40, "c" to 40, "a" to 40), moved.items)
        assertEquals(listOf(20, 30, 10), moved.restAfterSeconds)
        assertEquals(2, moved.currentIndex)
    }

    @Test fun `adding to an empty queue selects the new entry`() {
        val started = BoardPlaylistPolicy.normalize(BoardPlaylistState(
            sessionId = 7, currentIndex = -1, hostId = "host", members = listOf("host")))
        val added = requireValue(BoardPlaylistOps.add(started, "new", 40))
        assertEquals(0, added.currentIndex)
        assertEquals(listOf(0), added.restAfterSeconds)
    }

    // ===== Bounds =====

    @Test fun `normalize enforces the queue, member and index bounds`() {
        val oversized = BoardPlaylistState(
            sessionId = 7,
            currentIndex = 9_999,
            items = List(BoardPlaylistPolicy.MAX_ITEMS + 50) { "climb$it" to 40 },
            hostId = "host",
            members = List(BoardPlaylistPolicy.MAX_MEMBERS + 20) { "member$it" } + "host",
        )

        val normalized = BoardPlaylistPolicy.normalize(oversized)

        assertEquals(BoardPlaylistPolicy.MAX_ITEMS, normalized.items.size)
        assertEquals(BoardPlaylistPolicy.MAX_ITEMS, normalized.restAfterSeconds.size)
        assertEquals(BoardPlaylistPolicy.MAX_MEMBERS, normalized.members.size)
        assertEquals(BoardPlaylistPolicy.MAX_ITEMS - 1, normalized.currentIndex)
        assertTrue(normalized.hostId in normalized.members)
    }

    @Test fun `normalize drops an absurd rest end and an absurd request deadline`() {
        val base = withRestPlan(rest = BoardPlaylistRest(120, 1, 0, Long.MAX_VALUE))
        assertNull(BoardPlaylistPolicy.normalize(base).activeRest)

        val withProposal = running("host").copy(proposal = BoardPlaylistProposal(
            "request-0001", "other", 9, listOf("x" to 40), emptyList(), expiresAtEpochMs = 0))
        assertNull(BoardPlaylistPolicy.normalize(withProposal).proposal)
    }

    @Test fun `a host that is not a member cannot stay host`() {
        val normalized = BoardPlaylistPolicy.normalize(BoardPlaylistState(
            sessionId = 7, currentIndex = 0, items = listOf("a" to 40),
            hostId = "ghost", members = listOf("b", "c")))
        assertEquals("b", normalized.hostId)
    }

    @Test fun `a playlist with no members at all is no playlist`() {
        assertEquals(BoardPlaylistState(), BoardPlaylistPolicy.normalize(BoardPlaylistState(
            sessionId = 7, currentIndex = 0, items = listOf("a" to 40), hostId = "ghost")))
    }

    @Test fun `a pending send only ever describes the current entry`() {
        // Not in the queue at all.
        assertNull(BoardPlaylistPolicy.normalize(withRestPlan().copy(
            pendingProjection = BoardPlaylistPendingProjection("removed", 40,
                BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE))).pendingProjection)
        // Queued, but not the entry the wall is supposed to be showing: a
        // stale "send pending" here survived a next() and misreported a climb
        // that had since been projected perfectly well.
        assertNull(BoardPlaylistPolicy.normalize(withRestPlan(index = 0).copy(
            pendingProjection = BoardPlaylistPendingProjection("b", 40,
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED))).pendingProjection)
        assertNotNull(BoardPlaylistPolicy.normalize(withRestPlan(index = 0).copy(
            pendingProjection = BoardPlaylistPendingProjection("a", 40,
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED))).pendingProjection)
    }

    // ===== Local-only playlist taking the wall =====

    @Test fun `a local send over the shared climb needs consent once per playlist`() {
        val playlist = running("host")

        assertTrue(BoardPlaylistPolicy.requiresOverwriteConsent(
            playlist, "outsider", "different", 40, confirmedSessionId = null))
        assertFalse(BoardPlaylistPolicy.requiresOverwriteConsent(
            playlist, "outsider", "different", 40, confirmedSessionId = playlist.sessionId))
        assertTrue(BoardPlaylistPolicy.requiresOverwriteConsent(
            playlist, "outsider", "different", 40, confirmedSessionId = 999))
    }

    @Test fun `re-sending the climb already on the wall asks nothing`() {
        assertFalse(BoardPlaylistPolicy.requiresOverwriteConsent(
            running("host"), "outsider", "a", 40, confirmedSessionId = null))
    }

    @Test fun `a playlist member sending is just that playlist running`() {
        assertFalse(BoardPlaylistPolicy.requiresOverwriteConsent(
            running("host", "guest"), "guest", "different", 40, confirmedSessionId = null))
    }

    @Test fun `with no joinable playlist there is nothing to take over`() {
        assertFalse(BoardPlaylistPolicy.requiresOverwriteConsent(
            BoardPlaylistState(), "anyone", "a", 40, confirmedSessionId = null))
    }

    private fun <T : Any> requireValue(value: T?): T {
        assertNotNull("expected a value", value)
        return value!!
    }

    private inline fun <reified T> requireIs(value: Any?): T {
        assertTrue("expected ${T::class.simpleName}, was $value", value is T)
        return value as T
    }
}
