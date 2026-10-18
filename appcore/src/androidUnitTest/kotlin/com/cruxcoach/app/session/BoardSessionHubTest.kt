package com.cruxcoach.app.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.playlist.BoardSessionRecorder
import com.cruxcoach.app.playlist.PlaybackClock
import com.cruxcoach.app.playlist.PlaybackTransport
import com.cruxcoach.app.playlist.PlayerClimbInfo
import com.cruxcoach.app.playlist.PlaylistPlayerPresenter
import com.cruxcoach.app.ui.PlayerScreenModel
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * One running playlist for the whole app, as Android's single
 * PlaylistPlaybackCoordinator: leaving the player keeps it, reopening resumes
 * it, the banner mirrors it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardSessionHubTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it) }
    private val repo = PersonalBoardRepositoryImpl(SecureDatabase(driver))
    private var nowMs = 1_800_000_000_000L
    private val sessions = BoardSessionRecorder(repo, nowMs = { nowMs }, nowIso = { "2026-10-18T09:00:00" })

    @AfterTest
    fun tearDown() = driver.close()

    private class Transport : PlaybackTransport {
        val sent = mutableListOf<String>()
        /** The hub's board link, set by [hub]: the transport sees the same connection. */
        var link: MutableStateFlow<Boolean> = MutableStateFlow(false)
        override val isBoardConnected: Boolean get() = link.value
        override fun sendClimb(climbUuid: String, angle: Int) {
            sent += "$climbUuid@$angle"
        }
    }

    private class Clock : PlaybackClock {
        var seconds = 1_000L
        override fun epochSeconds(): Long = seconds
        override suspend fun awaitTick() {
            delay(1_000)
            seconds += 1
        }
    }

    private fun hub(scope: CoroutineScope, transport: Transport, linked: MutableStateFlow<Boolean>): BoardSessionHub {
        transport.link = linked
        val presenter = PlaylistPlayerPresenter(
            transport = transport,
            clock = Clock(),
            climbInfo = { uuid -> if (uuid == "nameless") null else PlayerClimbInfo("Climb $uuid", 18.0) },
            gradeLabel = { "6b" },
            scope = scope,
            random = Random(3),
            sessions = sessions,
        )
        return BoardSessionHub(
            presenter, PlayerScreenModel(presenter), linked, { linked.value }, scope,
            rowInfo = { uuid, _ -> if (uuid.startsWith("nameless")) null else PlayerClimbInfo("Climb $uuid", if (uuid == "x") null else 18.0) },
            gradeLabel = { "6b" },
        )
    }

    private fun BoardSessionHub.playList(name: String, vararg uuids: String): Boolean =
        play(name, uuids.toList(), uuids.map { 40 }, uuids.map { 0 }, "list", "manual", 0)

    @Test
    fun `starting what is already playing resumes it instead of reloading`() = runTest {
        val transport = Transport()
        // A board is linked: without one nothing reaches the wall (Android `QueueDeliveryPolicy`).
        val hub = hub(backgroundScope, transport, MutableStateFlow(true))
        assertTrue(hub.playList("Warm-up", "a", "b", "c"))
        assertNotNull(repo.getActiveSession(), "the run is recorded as a board session")
        hub.next()
        assertEquals(2, hub.bannerState.position)

        // The player view reopened from the list: same list, same climbs.
        assertFalse(hub.playList("Warm-up", "a", "b", "c"))
        assertEquals(2, hub.bannerState.position, "the queue stays where the climber left it")
        assertTrue(hub.isPlaying("Warm-up", listOf("a", "b", "c"), listOf(40, 40, 40)))

        // The banner opens the running player with no climbs: that starts nothing.
        assertFalse(hub.play("Warm-up", emptyList(), emptyList(), emptyList(), "", "", 0))
        assertEquals(2, hub.bannerState.position)
        assertEquals(listOf("a@40", "b@40"), transport.sent)
    }

    @Test
    fun `another list replaces the queue and the session clock runs on`() = runTest {
        val hub = hub(backgroundScope, Transport(), MutableStateFlow(false))
        hub.playList("One", "a", "b")
        val sessionId = repo.getActiveSession()?.id
        nowMs += 10 * 60_000L
        assertTrue(hub.playList("Two", "x", "y", "z"))
        assertEquals("Two", hub.bannerState.listName)
        assertEquals(1, hub.bannerState.position)
        assertEquals(3, hub.bannerState.total)
        assertEquals(sessionId, repo.getActiveSession()?.id, "one session at a time: no second session row")
        assertEquals(1, repo.getAllBoardSessions().size)
    }

    @Test
    fun `the banner mirrors the player and the live board link`() = runTest {
        val transport = Transport()
        val linked = MutableStateFlow(false)
        val hub = hub(backgroundScope, transport, linked)
        var seen: SessionBannerState? = null
        val watch = hub.watchBanner { seen = it }
        runCurrent()
        assertFalse(assertNotNull(seen).isActive)
        assertEquals(0, seen!!.position, "nothing to count while idle")

        hub.playList("Board", "nameless", "b")
        runCurrent()
        var banner = assertNotNull(seen)
        assertTrue(banner.isActive)
        assertEquals("", banner.currentClimbName, "an unknown name falls back to the player title in Swift")
        assertEquals("nameless", banner.currentClimbUuid)
        assertEquals(1, banner.position)
        assertEquals(2, banner.total)
        assertFalse(banner.hasPrevious)
        assertTrue(banner.hasNext)
        assertFalse(banner.canLight, "no board, no lamp")
        assertTrue(transport.sent.isEmpty(), "no board: the send is skipped (Android QueueDeliveryPolicy)")

        linked.value = true
        runCurrent()
        assertTrue(seen!!.canLight)
        assertEquals(listOf("nameless@40"), transport.sent, "the board that connects gets the current climb")
        hub.light()
        assertEquals(listOf("nameless@40", "nameless@40"), transport.sent, "the lamp sends again")

        hub.next()
        runCurrent()
        banner = seen!!
        assertEquals("Climb b", banner.currentClimbName)
        assertTrue(banner.hasPrevious)
        assertFalse(banner.hasNext)

        hub.player.endSession()
        runCurrent()
        assertFalse(seen!!.isActive)
        assertFalse(hub.isActive)
        assertNull(repo.getActiveSession(), "ending from the player ends the one session")
        watch.cancel()
    }

    @Test
    fun `mismatched input starts nothing`() = runTest {
        val hub = hub(backgroundScope, Transport(), MutableStateFlow(false))
        assertFalse(hub.play("Bad", listOf("a", "b"), listOf(40), listOf(0, 0), "list", "manual", 0))
        assertFalse(hub.isActive)
        assertNull(repo.getActiveSession())
    }

    @Test
    fun `the queue sheet jumps removes and reorders as Android's queue manager`() = runTest {
        val transport = Transport()
        val hub = hub(backgroundScope, transport, MutableStateFlow(true))
        hub.playList("Queue", "a", "b", "c", "d")
        var q = hub.queueState
        assertEquals(listOf("Climb a", "Climb b", "Climb c", "Climb d"), q.rows.map { it.name })
        assertEquals("6b", q.rows[0].grade)
        assertTrue(q.rows[0].isCurrent)

        hub.selectQueueClimb(2)
        assertEquals(2, hub.queueState.currentIndex)
        assertEquals("c@40", transport.sent.last(), "a jump puts the climb on the wall")

        // Removing before the current one keeps the current climb current.
        hub.removeQueueClimb(0)
        q = hub.queueState
        assertEquals(listOf("b", "c", "d"), q.rows.map { it.climbUuid })
        assertEquals(1, q.currentIndex)
        assertEquals("c", q.rows[q.currentIndex].climbUuid)
        val sentBefore = transport.sent.size
        // Removing the current one makes the next current and lights it.
        hub.removeQueueClimb(1)
        q = hub.queueState
        assertEquals(listOf("b", "d"), q.rows.map { it.climbUuid })
        assertEquals("d", q.rows[q.currentIndex].climbUuid)
        assertEquals("Climb d", hub.bannerState.currentClimbName)
        assertEquals(listOf("d@40"), transport.sent.drop(sentBefore))

        // Moving follows the current climb and sends nothing.
        hub.moveQueueClimb(1, 0)
        q = hub.queueState
        assertEquals(listOf("d", "b"), q.rows.map { it.climbUuid })
        assertEquals(0, q.currentIndex)
        assertEquals(1 + sentBefore, transport.sent.size)
        hub.moveQueueClimb(0, 5) // out of range: ignored
        assertEquals(listOf("d", "b"), hub.queueState.rows.map { it.climbUuid })

        // Removing the last one: an empty queue, no current climb (Android shows "add climbs").
        hub.removeQueueClimb(1)
        hub.removeQueueClimb(0)
        q = hub.queueState
        assertTrue(q.rows.isEmpty())
        assertEquals(-1, q.currentIndex)
        assertTrue(q.isActive, "the playlist itself runs on until it is ended")
        assertFalse(hub.bannerState.canLight)
    }

    @Test
    fun `an unresolved queued climb shows its uuid start and no grade`() = runTest {
        val hub = hub(backgroundScope, Transport(), MutableStateFlow(false))
        hub.playList("Odd", "nameless-climb-uuid", "x")
        val rows = hub.queueState.rows
        assertEquals("nameless", rows[0].name.take(8))
        assertEquals("Climb x", rows[1].name)
        assertEquals("", rows[1].grade, "no difficulty, no grade")
    }

    @Test
    fun `the browser plus queues a climb and counts it per angle`() = runTest {
        val transport = Transport()
        val hub = hub(backgroundScope, transport, MutableStateFlow(true))
        hub.addToQueue("idle", 40) // nothing runs: nothing to add to
        assertFalse(hub.isActive)
        hub.playList("Mix", "a", "b")
        hub.addToQueue("C", 40)
        hub.addToQueue("c", 40)
        hub.addToQueue("c", 25)
        val banner = hub.bannerState
        assertEquals(5, banner.total)
        assertEquals(2, hub.queuedCount(banner, "c", 40), "Android counts case-blind at the browsed angle")
        assertEquals(1, hub.queuedCount(banner, "C", 25))
        assertEquals(0, hub.queuedCount(banner, "c", 30))
        assertEquals(listOf("a@40"), transport.sent, "an append does not take the wall")
        assertEquals(0, hub.queueState.rows.last { it.climbUuid == "c" }.restAfterSeconds)

        // Into an emptied queue the added climb becomes current and is lit.
        repeat(5) { hub.removeQueueClimb(0) }
        assertEquals(-1, hub.queueState.currentIndex)
        hub.addToQueue("z", 40)
        assertEquals(0, hub.queueState.currentIndex)
        assertEquals("Climb z", hub.bannerState.currentClimbName)
        assertEquals("z@40", transport.sent.last())
    }

    @Test
    fun `a board connecting while the playlist runs gets the current climb once`() = runTest {
        val transport = Transport()
        val linked = MutableStateFlow(false)
        val hub = hub(backgroundScope, transport, linked)
        runCurrent()
        hub.playList("Later", "a", "b")
        hub.next()
        runCurrent()
        assertTrue(transport.sent.isEmpty(), "automatic mode without a board skips every send")
        assertEquals(2, hub.bannerState.position)

        linked.value = true
        runCurrent()
        assertEquals(listOf("b@40"), transport.sent)
        assertTrue(hub.player.currentState.boardConnected)

        // A reconnect does not send the climb the wall already shows (Android `lastSentClimbKey`).
        linked.value = false
        runCurrent()
        assertFalse(hub.player.currentState.boardConnected)
        linked.value = true
        runCurrent()
        assertEquals(listOf("b@40"), transport.sent)

        // A move sends again; a connect after the playlist ended sends nothing.
        hub.previous()
        assertEquals(listOf("b@40", "a@40"), transport.sent)
        hub.player.endSession()
        linked.value = false
        runCurrent()
        linked.value = true
        runCurrent()
        assertEquals(listOf("b@40", "a@40"), transport.sent)
    }
}
