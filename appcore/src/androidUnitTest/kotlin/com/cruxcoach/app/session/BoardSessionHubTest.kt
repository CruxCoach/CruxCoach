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
        override var isBoardConnected: Boolean = false
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
        val presenter = PlaylistPlayerPresenter(
            transport = transport,
            clock = Clock(),
            climbInfo = { uuid -> if (uuid == "nameless") null else PlayerClimbInfo("Climb $uuid", 18.0) },
            gradeLabel = { "6b" },
            scope = scope,
            random = Random(3),
            sessions = sessions,
        )
        return BoardSessionHub(presenter, PlayerScreenModel(presenter), linked, { linked.value }, scope)
    }

    private fun BoardSessionHub.playList(name: String, vararg uuids: String): Boolean =
        play(name, uuids.toList(), uuids.map { 40 }, uuids.map { 0 }, "list", "manual", 0)

    @Test
    fun `starting what is already playing resumes it instead of reloading`() = runTest {
        val transport = Transport()
        val hub = hub(backgroundScope, transport, MutableStateFlow(false))
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

        linked.value = true
        runCurrent()
        assertTrue(seen!!.canLight)
        hub.light()
        assertEquals(listOf("nameless@40", "nameless@40"), transport.sent)

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
}
