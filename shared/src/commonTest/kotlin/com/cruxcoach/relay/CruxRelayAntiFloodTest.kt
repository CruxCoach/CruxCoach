package com.cruxcoach.relay

import com.cruxcoach.domain.relay.CruxRelayAntiFlood
import com.cruxcoach.domain.relay.RelayAdmission
import kotlin.test.Test
import kotlin.test.assertEquals

class CruxRelayAntiFloodTest {

    private val A = "aa:bb:cc:dd:ee:01"
    private val B = "aa:bb:cc:dd:ee:02"

    @Test
    fun newClimb_isAccepted() {
        val af = CruxRelayAntiFlood()
        assertEquals(RelayAdmission.ACCEPT, af.onClimb(A, 100L, nowMs = 0, currentRelayQueueSize = 0))
    }

    @Test
    fun sameClimb_withinWindow_isDeduped_notReAddedButReAckedThenSuppressed() {
        val af = CruxRelayAntiFlood(dedupWindowMs = 10_000, reAckThrottleMs = 2_000)
        assertEquals(RelayAdmission.ACCEPT, af.onClimb(A, 1L, nowMs = 0, currentRelayQueueSize = 0))
        // Immediate retry: within re-ACK throttle of the accept's own flash.
        assertEquals(RelayAdmission.DUPLICATE_SUPPRESSED, af.onClimb(A, 1L, nowMs = 500, currentRelayQueueSize = 1))
        // After the throttle: re-flash so the user finally sees it landed.
        assertEquals(RelayAdmission.DUPLICATE_RE_ACK, af.onClimb(A, 1L, nowMs = 3_000, currentRelayQueueSize = 1))
    }

    @Test
    fun sameClimb_afterWindow_canBeReAddedDeliberately() {
        val af = CruxRelayAntiFlood(dedupWindowMs = 10_000)
        assertEquals(RelayAdmission.ACCEPT, af.onClimb(A, 7L, nowMs = 0, currentRelayQueueSize = 0))
        // 11s later the sliding window has lapsed -> a genuine re-add.
        assertEquals(RelayAdmission.ACCEPT, af.onClimb(A, 7L, nowMs = 11_000, currentRelayQueueSize = 1))
    }

    @Test
    fun distinctClimbs_fasterThanRefill_hitTheTokenBucket() {
        // cap 3, refill 1 token / 2s. Four distinct climbs at t=0.
        val af = CruxRelayAntiFlood(bucketCapacity = 3, refillIntervalMs = 2_000)
        assertEquals(RelayAdmission.ACCEPT, af.onClimb(A, 1L, 0, 0))
        assertEquals(RelayAdmission.ACCEPT, af.onClimb(A, 2L, 0, 1))
        assertEquals(RelayAdmission.ACCEPT, af.onClimb(A, 3L, 0, 2))
        assertEquals(RelayAdmission.RATE_LIMITED, af.onClimb(A, 4L, 0, 3))
        // 2s later one token has refilled.
        assertEquals(RelayAdmission.ACCEPT, af.onClimb(A, 4L, 2_000, 3))
    }

    @Test
    fun tokenBucket_isPerClient() {
        val af = CruxRelayAntiFlood(bucketCapacity = 1, refillIntervalMs = 10_000)
        assertEquals(RelayAdmission.ACCEPT, af.onClimb(A, 1L, 0, 0))
        assertEquals(RelayAdmission.RATE_LIMITED, af.onClimb(A, 2L, 0, 1))
        // A different client has its own full bucket.
        assertEquals(RelayAdmission.ACCEPT, af.onClimb(B, 3L, 0, 1))
    }

    @Test
    fun globalQueueCap_dropsEvenWithTokens() {
        val af = CruxRelayAntiFlood(globalQueueCap = 50, bucketCapacity = 3)
        assertEquals(RelayAdmission.QUEUE_FULL, af.onClimb(A, 1L, nowMs = 0, currentRelayQueueSize = 50))
    }

    @Test
    fun clientGone_resetsItsBucketAndDedupState() {
        val af = CruxRelayAntiFlood(bucketCapacity = 1, refillIntervalMs = 10_000)
        assertEquals(RelayAdmission.ACCEPT, af.onClimb(A, 1L, 0, 0))
        assertEquals(RelayAdmission.RATE_LIMITED, af.onClimb(A, 2L, 0, 1))
        af.onClientGone(A)
        // Reconnected client (same address) starts fresh.
        assertEquals(RelayAdmission.ACCEPT, af.onClimb(A, 2L, 0, 1))
    }
}
