package com.cruxcoach.android.fips

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CCJ1 admission is symmetric but its hello is not acknowledged: the controller
 * admits a candidate only once the candidate's hello reached it, and the
 * candidate reaches DIRECT_AUTHENTICATED only once the controller's hello
 * reached it. Neither side can observe the opposite direction, so the re-send
 * schedule must not use its own validation state as a reason to stay silent.
 */
class DirectJoinHelloScheduleTest {

    @Test fun `a peer that already proved its own scope still receives our hello`() {
        assertTrue(DirectJoinHelloSchedule.shouldSend(null, 1_000L, peerValidatedByUs = true))
    }

    @Test fun `the side that polls second must not skip its own hello`() {
        // Both nodes see the direct BLE edge; A polls first and its hello
        // arrives before B polls, so B enters its own tick already validated.
        var aLastSent: Long? = null
        var bLastSent: Long? = null
        var aValidatedB = false
        var bValidatedA = false

        // A's peer-loop tick.
        if (DirectJoinHelloSchedule.shouldSend(aLastSent, 2_000L, aValidatedB)) {
            aLastSent = 2_000L
            bValidatedA = true // delivered and validated before B's own tick
        }
        // B's peer-loop tick, 700 ms later.
        if (DirectJoinHelloSchedule.shouldSend(bLastSent, 2_700L, bValidatedA)) {
            bLastSent = 2_700L
            aValidatedB = true
        }

        assertTrue(bLastSent != null, "B must still assert its own scope to A")
        assertTrue(aValidatedB, "A can only sponsor/admit B once B's hello validated")
        assertTrue(bValidatedA)
    }

    @Test fun `an established edge is re-asserted only once per retry window`() {
        assertFalse(DirectJoinHelloSchedule.shouldSend(1_000L,
            1_000L + DirectJoinHelloSchedule.RETRY_MS - 1, peerValidatedByUs = true))
        assertTrue(DirectJoinHelloSchedule.shouldSend(1_000L,
            1_000L + DirectJoinHelloSchedule.RETRY_MS, peerValidatedByUs = true))
        assertFalse(DirectJoinHelloSchedule.shouldSend(1_000L,
            1_000L + DirectJoinHelloSchedule.RETRY_MS - 1, peerValidatedByUs = false))
    }
}
