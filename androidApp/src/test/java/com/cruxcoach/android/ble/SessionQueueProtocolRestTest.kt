package com.cruxcoach.android.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire format for the rest phase, added 2026-08-06.
 *
 * Measured on two devices that day: the host showed "Pause 0:26 · next DA REAL
 * 6A+" while the participant showed DA REAL 6A+ ready to climb, both labelled
 * "2 of 3". The protocol had six events and none of them could say "resting" —
 * a participant only ever heard CurrentChanged, which fires when the advance
 * ARMS the pause, so it jumped straight to the upcoming climb.
 */
class SessionQueueProtocolRestTest {

    @Test
    fun `rest start survives the round trip`() {
        val encoded = SessionQueueProtocol.encodeEventRestStarted(30, 2)
        val decoded = SessionQueueProtocol.decodeEvent(encoded)
        assertEquals(SessionEvent.RestStarted(remainingSeconds = 30, nextIndex = 2), decoded)
    }

    @Test
    fun `a full-length rest fits`() {
        // The editor offers up to 60 minutes, which does not fit in one byte —
        // the reason the countdown is encoded big-endian across two.
        val hour = 60 * 60
        val decoded = SessionQueueProtocol.decodeEvent(
            SessionQueueProtocol.encodeEventRestStarted(hour, 0),
        )
        assertEquals(SessionEvent.RestStarted(hour, 0), decoded)
    }

    @Test
    fun `rest end survives the round trip`() {
        assertEquals(
            SessionEvent.RestEnded,
            SessionQueueProtocol.decodeEvent(SessionQueueProtocol.encodeEventRestEnded()),
        )
    }

    @Test
    fun `a truncated rest frame is refused rather than half-read`() {
        // A short frame would otherwise index past the end. Dropping it leaves
        // the participant climbing, which is the pre-change behaviour — bad,
        // but not worse than crashing on a corrupt notification.
        assertNull(
            SessionQueueProtocol.decodeEvent(
                byteArrayOf(SessionQueueProtocol.EVT_REST_STARTED, 0x00, 0x1E),
            ),
        )
    }

    @Test
    fun `an older participant ignores the new events instead of breaking`() {
        // 0.2.2 and older decode with the same `else -> null` arm, so the
        // guarantee this test states is the one that makes shipping the change
        // mid-version safe: unknown opcode in, null out, nothing applied.
        for (unknown in listOf(0x7F.toByte(), 0x09.toByte(), 0xFF.toByte())) {
            assertNull(
                "opcode $unknown must not decode",
                SessionQueueProtocol.decodeEvent(byteArrayOf(unknown, 1, 2, 3)),
            )
        }
    }

    @Test
    fun `the rest opcodes do not collide with the existing six`() {
        val existing = listOf(
            SessionQueueProtocol.EVT_ADDED,
            SessionQueueProtocol.EVT_REMOVED,
            SessionQueueProtocol.EVT_CURRENT,
            SessionQueueProtocol.EVT_CLEARED,
            SessionQueueProtocol.EVT_PARTICIPANT_JOINED,
            SessionQueueProtocol.EVT_PARTICIPANT_LEFT,
        )
        for (opcode in listOf(
            SessionQueueProtocol.EVT_REST_STARTED,
            SessionQueueProtocol.EVT_REST_ENDED,
        )) {
            assert(opcode !in existing) { "opcode $opcode is already taken" }
        }
    }
}
