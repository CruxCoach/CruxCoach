package com.cruxcoach.app.playlist

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Android `QueueDeliveryPolicy` for this phone's own playlist: under "tap to send" an
 * advance leaves the wall as it is and the lamp puts the climb up; starting a playlist
 * and the lamp are explicit in either mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistSendModeTest {

    private class ModeTransport(var automatic: Boolean) : PlaybackTransport {
        val sent = mutableListOf<String>()
        override val isBoardConnected: Boolean = true
        override val sendsAutomatically: Boolean get() = automatic
        override fun sendClimb(climbUuid: String, angle: Int) {
            sent += climbUuid
        }
    }

    private fun TestScope.player(transport: PlaybackTransport) = PlaylistPlayerPresenter(
        transport = transport,
        climbInfo = { PlayerClimbInfo("Name of $it", 18.0) },
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        random = Random(7),
    )

    private val queue = listOf("a", "b", "c").map { PlaybackItem(it, 40, 0) }

    @Test
    fun `tap to send waits for the lamp after every advance`() = runTest {
        val transport = ModeTransport(automatic = false)
        val p = player(transport)
        p.play("List", queue)
        assertEquals(listOf("a"), transport.sent, "starting the playlist is the request for its first climb")
        assertFalse(p.state.value.awaitingExplicitSend)

        p.next()
        assertEquals(listOf("a"), transport.sent, "the wall keeps the climb someone may be on")
        assertTrue(p.state.value.awaitingExplicitSend)
        assertEquals("b", p.state.value.currentClimb?.climbUuid)

        p.resendCurrentClimb()
        assertEquals(listOf("a", "b"), transport.sent)
        assertFalse(p.state.value.awaitingExplicitSend)

        p.setCurrent(2)
        p.removeAt(2)
        assertEquals(listOf("a", "b"), transport.sent, "removing the current climb is no request either")
        assertTrue(p.state.value.awaitingExplicitSend)
        p.close()
    }

    @Test
    fun `automatic mode lights every climb the player moves to`() = runTest {
        val transport = ModeTransport(automatic = true)
        val p = player(transport)
        p.play("List", queue)
        p.next()
        p.previous()
        assertEquals(listOf("a", "b", "a"), transport.sent)
        assertFalse(p.state.value.awaitingExplicitSend)

        // The mode is read at each move: switched in the settings, it applies to the next advance.
        transport.automatic = false
        p.next()
        assertEquals(listOf("a", "b", "a"), transport.sent)
        assertTrue(p.state.value.awaitingExplicitSend)
        transport.automatic = true
        p.next()
        assertEquals(listOf("a", "b", "a", "c"), transport.sent)
        assertFalse(p.state.value.awaitingExplicitSend)
        p.close()
    }
}
