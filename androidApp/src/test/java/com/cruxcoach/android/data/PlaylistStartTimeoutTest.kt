package com.cruxcoach.android.data

import com.cruxcoach.android.boardcell.BoardPlaylistPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the "waiting for the playlist host" display gives up.
 *
 * The canonical expiry inside the request decides the outcome; this only
 * decides what the screen says while nobody has answered. The two must not be
 * able to contradict each other.
 */
class PlaylistStartTimeoutTest {

    /** 2026-08-17T12:00:00Z. */
    private val sentAt = 1_786_968_000_000L
    private val window = BoardPlaylistPolicy.PROPOSAL_TIMEOUT_MS

    @Test fun `with no canonical deadline yet the fallback outlives the canonical window`() {
        // Nothing may have reached a controller at all, so this is the only
        // thing that stops the screen waiting for ever — but it still waits
        // longer than the canonical window so the two cannot disagree.
        val fallback = PlaylistStartTimeout.transportFallbackMs(window)
        assertTrue(fallback > window)
        assertEquals(window + PlaylistStartTimeout.GRACE_MS, fallback)
    }

    @Test fun `a request committed ten seconds late is not abandoned five seconds early`() {
        // The controller accepted the request 10 s after this device sent it,
        // so its canonical deadline is 10 s later than a local timer would
        // have assumed. Counting a flat 30 s from the send would have said
        // "no answer" while the host still had 10 s to answer in.
        val committedAt = sentAt + 10_000
        val expiresAt = committedAt + window
        val observedAt = committedAt

        val delay = PlaylistStartTimeout.fromCanonicalDeadline(expiresAt, observedAt)

        assertEquals(window + PlaylistStartTimeout.GRACE_MS, delay)
        val firesAt = observedAt + delay
        assertTrue("must not fire before the canonical deadline", firesAt > expiresAt)
        assertEquals(PlaylistStartTimeout.GRACE_MS, firesAt - expiresAt)
        // And it is genuinely later than the naive local schedule would be.
        assertTrue(firesAt > sentAt + PlaylistStartTimeout.transportFallbackMs(window))
    }

    @Test fun `observing an open request late still waits out its remaining window`() {
        val expiresAt = sentAt + window
        // 25 s in: five seconds of canonical window left, plus the grace.
        val delay = PlaylistStartTimeout.fromCanonicalDeadline(expiresAt, sentAt + 25_000)
        assertEquals(5_000 + PlaylistStartTimeout.GRACE_MS, delay)
    }

    @Test fun `an already expired deadline gives up immediately rather than never`() {
        assertEquals(0L, PlaylistStartTimeout.fromCanonicalDeadline(
            sentAt + window, sentAt + window + PlaylistStartTimeout.GRACE_MS + 1))
        // Never negative, which would be an immediate-fire loop or a crash
        // depending on the coroutine delay implementation.
        assertTrue(PlaylistStartTimeout.fromCanonicalDeadline(sentAt, sentAt + 10 * window) >= 0)
    }
}
