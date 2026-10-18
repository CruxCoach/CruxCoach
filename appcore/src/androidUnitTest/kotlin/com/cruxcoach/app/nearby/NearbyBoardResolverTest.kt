package com.cruxcoach.app.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cases of Android's `BleShareManagerTest` (0e1bad0c6) that concern what nearby phones
 * report and this phone's own sends (LOCAL_ACTIVE / LOCAL_MANAGER, the bridge's clearing of an
 * own live climb). Android's bridge of other phones' climbs into its persisted board state has
 * no iPhone counterpart; see [NearbyBoardResolver].
 */
class NearbyBoardResolverTest {
    private val resolver = NearbyBoardResolver()

    private fun nearbyClimb(
        uuid: String = UUID_A,
        angle: Int = 40,
        connectedOnly: Boolean = false,
        isLastClimb: Boolean = false,
        acceptsDisconnectRequests: Boolean = true,
        projectionSurvivesDisconnect: Boolean = true,
        senderToken: Int? = null,
        identifier: String = "phone",
    ) = NearbyClimb(uuid, angle, -50, 0L, identifier, connectedOnly, isLastClimb,
        acceptsDisconnectRequests = acceptsDisconnectRequests,
        projectionSurvivesDisconnect = projectionSurvivesDisconnect,
        senderToken = senderToken)

    private fun session(sessionId: Int = 12345, currentClimbUuid: String? = UUID_A, angle: Int = 40) =
        NearbySession(sessionId, 1, "TestHost", -50, 0L, "host-$sessionId", currentClimbUuid, angle)

    private fun participant(current: String?, sessionId: Int = 0, angle: Int = 40) =
        OwnSession(member = true, connecting = false, sessionId = sessionId, currentClimbUuid = current, currentClimbAngle = angle)

    private fun resolve(
        climbs: List<NearbyClimb> = emptyList(),
        sessions: List<NearbySession> = emptyList(),
        own: OwnSession? = null,
        local: LocalBoard? = null,
    ) = resolver.resolve(climbs, sessions, own, local)

    /** Android `climb(uuid)` in the board state manager, [live] = `hasActiveClimb()`. */
    private fun mine(uuid: String = UUID_B, live: Boolean = true, survives: Boolean = true, sentAtMs: Long = 1L) =
        LocalBoard(uuid, 40, survives, live, sentAtMs)

    @Test
    fun `REMOTE_LAST is skipped while we have a live climb`() {
        val board = resolve(climbs = listOf(nearbyClimb(UUID_A, isLastClimb = true)), local = mine(UUID_B))
        assertEquals(OnBoardSource.LOCAL_ACTIVE, board.onBoard?.source)
        assertEquals(UUID_B, board.onBoard?.climbUuid)
    }

    @Test
    fun `LOCAL_ACTIVE when connected and sending`() {
        assertEquals(OnBoardSource.LOCAL_ACTIVE, resolve(local = mine(UUID_A)).onBoard?.source)
    }

    @Test
    fun `a participant's queue climb suppresses even a live own climb`() {
        val board = resolve(own = participant(UUID_A), local = mine(UUID_B))
        assertNull(board.onBoard)
    }

    @Test
    fun `SESSION_REMOTE beats LOCAL_MANAGER for a non participant`() {
        val board = resolve(sessions = listOf(session(currentClimbUuid = UUID_A)), local = mine(UUID_B, live = false))
        assertEquals(OnBoardSource.SESSION_REMOTE, board.onBoard?.source)
        assertEquals(UUID_A, board.onBoard?.climbUuid)
    }

    @Test
    fun `LOCAL_ACTIVE beats SESSION_REMOTE for a non participant`() {
        val board = resolve(sessions = listOf(session(currentClimbUuid = UUID_B)), local = mine(UUID_A))
        assertEquals(OnBoardSource.LOCAL_ACTIVE, board.onBoard?.source)
        assertEquals(UUID_A, board.onBoard?.climbUuid)
    }

    @Test
    fun `LOCAL_MANAGER is the fallback and a MoonBoard climb is no longer projected`() {
        var board = resolve(local = mine(UUID_A, live = false))
        assertEquals(OnBoardSource.LOCAL_MANAGER, board.onBoard?.source)
        assertTrue(board.onBoard!!.isStillProjected)
        board = resolve(local = mine(UUID_A, live = false, survives = false))
        assertFalse(board.onBoard!!.isStillProjected)
        assertNull(resolve().onBoard)
    }

    @Test
    fun `unchanged ClimbData from before our send is stale and a new remote send overwrites ours`() {
        // Another phone already advertised A; then this phone sent B over its own link.
        assertEquals(OnBoardSource.REMOTE_ACTIVE, resolve(climbs = listOf(nearbyClimb(UUID_A))).onBoard?.source)
        var board = resolve(climbs = listOf(nearbyClimb(UUID_A)), local = mine(UUID_B))
        assertEquals(OnBoardSource.LOCAL_ACTIVE, board.onBoard?.source, "A is stale: the board shows our B")

        // The other phone sends C: it wrote the wall after us (Android's bridge clears our climb).
        board = resolve(climbs = listOf(nearbyClimb(UUID_C)), local = mine(UUID_B))
        assertEquals(OnBoardSource.REMOTE_ACTIVE, board.onBoard?.source)
        assertEquals(UUID_C, board.onBoard?.climbUuid)
        // It stays cleared for that send, also once the other phone only reports its last climb.
        board = resolve(climbs = listOf(nearbyClimb(UUID_C, isLastClimb = true)), local = mine(UUID_B))
        assertEquals(OnBoardSource.REMOTE_LAST, board.onBoard?.source)

        // Our next send is live again; C did not change since, so it is stale.
        board = resolve(climbs = listOf(nearbyClimb(UUID_C)), local = mine(UUID_A, sentAtMs = 2L))
        assertEquals(OnBoardSource.LOCAL_ACTIVE, board.onBoard?.source)
        assertEquals(UUID_A, board.onBoard?.climbUuid)
    }

    @Test
    fun `a nearby session advancing overwrites our live climb but its first sighting does not`() {
        var board = resolve(sessions = listOf(session(currentClimbUuid = UUID_A)), local = mine(UUID_B))
        assertEquals(OnBoardSource.LOCAL_ACTIVE, board.onBoard?.source, "first sighting is no proof")
        board = resolve(sessions = listOf(session(currentClimbUuid = UUID_C)), local = mine(UUID_B))
        assertEquals(OnBoardSource.SESSION_REMOTE, board.onBoard?.source)
        assertEquals(UUID_C, board.onBoard?.climbUuid)
    }

    @Test
    fun `remote active beats a session climb and a last climb`() {
        val board = resolve(
            climbs = listOf(nearbyClimb(UUID_B, isLastClimb = true, identifier = "old"), nearbyClimb(UUID_A)),
            sessions = listOf(session(currentClimbUuid = UUID_C)),
        )
        assertEquals(OnBoardSource.REMOTE_ACTIVE, board.onBoard?.source)
        assertEquals(UUID_A, board.onBoard?.climbUuid)
    }

    @Test
    fun `a remote last climb is shown when nothing else is`() {
        val board = resolve(climbs = listOf(nearbyClimb(UUID_A, isLastClimb = true)))
        assertEquals(OnBoardSource.REMOTE_LAST, board.onBoard?.source)
        assertEquals(UUID_A, board.onBoard?.climbUuid)
        assertTrue(board.onBoard!!.isStillProjected)
    }

    @Test
    fun `a volatile remote last climb is labelled as not projected`() {
        val board = resolve(climbs = listOf(nearbyClimb(UUID_A, isLastClimb = true, projectionSurvivesDisconnect = false)))
        assertEquals(OnBoardSource.REMOTE_LAST, board.onBoard?.source)
        assertFalse(board.onBoard!!.isStillProjected)
    }

    @Test
    fun `a session advertisement beats a stale last climb for a non participant`() {
        val board = resolve(
            climbs = listOf(nearbyClimb(UUID_B, isLastClimb = true)),
            sessions = listOf(session(currentClimbUuid = UUID_A, angle = 35)),
        )
        assertEquals(OnBoardSource.SESSION_REMOTE, board.onBoard?.source)
        assertEquals(UUID_A, board.onBoard?.climbUuid)
        assertEquals(35, board.onBoard?.angle)
    }

    @Test
    fun `nothing nearby resolves to nothing`() {
        val board = resolve()
        assertNull(board.onBoard)
        assertNull(board.boardShowsInstead)
        assertEquals(0, board.boardOccupiedCount)
        assertFalse(board.disconnectRequestable)
    }

    @Test
    fun `the joined queue's climb is suppressed because the session row shows it`() {
        val board = resolve(sessions = listOf(session(currentClimbUuid = UUID_A)), own = participant(UUID_A))
        assertNull(board.onBoard)
        assertNull(board.boardShowsInstead)
    }

    @Test
    fun `remote active is never suppressed even for the queue's own climb`() {
        val board = resolve(climbs = listOf(nearbyClimb(UUID_A)), own = participant(UUID_A))
        assertEquals(OnBoardSource.REMOTE_ACTIVE, board.onBoard?.source)
    }

    @Test
    fun `no suppression while connecting so the climb does not blink out`() {
        val connecting = OwnSession(member = false, connecting = true, sessionId = 0, currentClimbUuid = UUID_A, currentClimbAngle = 40)
        val board = resolve(sessions = listOf(session(currentClimbUuid = UUID_A)), own = connecting)
        assertNotNull(board.onBoard)
        assertEquals(OnBoardSource.SESSION_REMOTE, board.onBoard?.source)
    }

    @Test
    fun `full join flow keeps the climb until the session row takes over`() {
        val sessions = listOf(session(currentClimbUuid = UUID_A))
        assertEquals(OnBoardSource.SESSION_REMOTE, resolve(sessions = sessions).onBoard?.source)
        val connecting = OwnSession(member = false, connecting = true, sessionId = 12345, currentClimbUuid = UUID_A, currentClimbAngle = 40)
        assertNotNull(resolve(sessions = sessions, own = connecting).onBoard, "on-board must not be null during connecting")
        assertNull(resolve(sessions = sessions, own = participant(UUID_A, sessionId = 12345)).onBoard)
    }

    @Test
    fun `a participant without a known session id still sees the host climb`() {
        val board = resolve(sessions = listOf(session(sessionId = 777, currentClimbUuid = UUID_A)), own = participant(null, sessionId = 0))
        assertEquals(UUID_A, board.onBoard?.climbUuid)
    }

    @Test
    fun `a stranger's session climb is not shown to a participant of another session`() {
        val board = resolve(sessions = listOf(session(sessionId = 999, currentClimbUuid = UUID_C)), own = participant(null, sessionId = 12345))
        assertNull(board.onBoard)
    }

    @Test
    fun `the session climb follows the host through the queue`() {
        assertEquals(UUID_A, resolve(sessions = listOf(session(currentClimbUuid = UUID_A))).onBoard?.climbUuid)
        val board = resolve(sessions = listOf(session(currentClimbUuid = UUID_B)))
        assertEquals(UUID_B, board.onBoard?.climbUuid)
        assertEquals(OnBoardSource.SESSION_REMOTE, board.onBoard?.source)
    }

    @Test
    fun `an overwrite stays on the wall until the session advances`() {
        val own = participant(UUID_A)
        // Someone else wrote the wall during the session.
        assertEquals(OnBoardSource.REMOTE_ACTIVE, resolve(climbs = listOf(nearbyClimb(UUID_B)), own = own).onBoard?.source)
        // They disconnected; their climb is still lit and the session has not sent a new one.
        val lastB = listOf(nearbyClimb(UUID_B, isLastClimb = true))
        val after = resolve(climbs = lastB, own = own)
        assertEquals(OnBoardSource.REMOTE_LAST, after.onBoard?.source)
        assertEquals(UUID_B, after.onBoard?.climbUuid)
        assertTrue(after.onBoard!!.isStillProjected)
        // The host moved on: the session owns the wall again and its row says so.
        val advanced = resolve(climbs = lastB, own = participant(UUID_C))
        assertNull(advanced.onBoard)
        assertNull(advanced.boardShowsInstead)
    }

    @Test
    fun `a member without a queue climb sees the last climb and the queue's own climb is no mismatch`() {
        val board = resolve(climbs = listOf(nearbyClimb(UUID_B, isLastClimb = true)), own = participant(null))
        // No queue climb yet: nothing to suppress against, the last climb is shown.
        assertEquals(OnBoardSource.REMOTE_LAST, board.onBoard?.source)
        // With a queue climb the session stage wins; the same id spelled differently is not a mismatch.
        val same = resolve(climbs = listOf(nearbyClimb(UUID_B, isLastClimb = true)), own = participant(UUID_B.lowercase()))
        assertNull(same.onBoard)
        assertNull(same.boardShowsInstead)
    }

    @Test
    fun `the occupied count counts every phone connected without a climb`() {
        val board = resolve(climbs = listOf(
            nearbyClimb("", connectedOnly = true, senderToken = 1, identifier = "one"),
            nearbyClimb("", connectedOnly = true, identifier = "two"),
            nearbyClimb(UUID_A, identifier = "three"),
        ))
        assertEquals(2, board.boardOccupiedCount)
        // A climb is the primary information; the occupied count stays beside it.
        assertEquals(OnBoardSource.REMOTE_ACTIVE, board.onBoard?.source)
    }

    @Test
    fun `a disconnect request is possible only towards an active sender that accepts one`() {
        assertTrue(resolve(climbs = listOf(nearbyClimb(UUID_A))).disconnectRequestable)
        assertTrue(resolve(climbs = listOf(nearbyClimb("", connectedOnly = true))).disconnectRequestable)
        assertFalse(resolve(climbs = listOf(nearbyClimb(UUID_A, isLastClimb = true))).disconnectRequestable)
        assertFalse(resolve(climbs = listOf(nearbyClimb(UUID_A, acceptsDisconnectRequests = false))).disconnectRequestable)
    }

    @Test
    fun `climb ids compare across hyphenation and case`() {
        assertTrue(NearbyBoardResolver.sameClimb("ac9fcf7f-01fc-44bd-835c-fc41cb2224da", UUID_A))
        assertFalse(NearbyBoardResolver.sameClimb(UUID_A, UUID_B))
        assertFalse(NearbyBoardResolver.sameClimb(UUID_A, null))
    }

    companion object {
        const val UUID_A = "AC9FCF7F01FC44BD835CFC41CB2224DA"
        const val UUID_B = "D0E5387D5B974D38B4E93FC4DFD61EF6"
        const val UUID_C = "36E949A6395D4290AF08FDFBCC6010C1"
    }
}
