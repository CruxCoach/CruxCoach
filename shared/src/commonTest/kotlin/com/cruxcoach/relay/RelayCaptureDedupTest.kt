package com.cruxcoach.relay

import com.cruxcoach.domain.relay.RelayCaptureDedup
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayCaptureDedupTest {

    private val A = "aa:01"
    private val B = "bb:02"

    @Test
    fun newClimb_isCaptured() {
        assertTrue(RelayCaptureDedup().shouldCapture(A, 100L, nowMs = 0, currentCapturedCount = 0))
    }

    @Test
    fun sameClimb_withinWindow_isSkipped_thenReAllowedAfterWindow() {
        val d = RelayCaptureDedup(windowMs = 30_000)
        assertTrue(d.shouldCapture(A, 1L, nowMs = 0, currentCapturedCount = 0))
        assertFalse(d.shouldCapture(A, 1L, nowMs = 5_000, currentCapturedCount = 1))
        assertTrue(d.shouldCapture(A, 1L, nowMs = 31_000, currentCapturedCount = 1))
    }

    @Test
    fun distinctClimbs_eachCapturedOnce() {
        val d = RelayCaptureDedup()
        assertTrue(d.shouldCapture(A, 1L, 0, 0))
        assertTrue(d.shouldCapture(A, 2L, 0, 1))
        assertFalse(d.shouldCapture(A, 1L, 100, 2)) // re-send of #1
    }

    @Test
    fun dedup_isPerClient() {
        val d = RelayCaptureDedup()
        assertTrue(d.shouldCapture(A, 7L, 0, 0))
        assertTrue(d.shouldCapture(B, 7L, 0, 1)) // same holds, different sender
    }

    @Test
    fun globalCap_stopsCapture() {
        val d = RelayCaptureDedup(globalCap = 50)
        assertFalse(d.shouldCapture(A, 1L, nowMs = 0, currentCapturedCount = 50))
    }

    @Test
    fun clientGone_forgetsState() {
        val d = RelayCaptureDedup(windowMs = 30_000)
        assertTrue(d.shouldCapture(A, 1L, 0, 0))
        assertFalse(d.shouldCapture(A, 1L, 100, 1))
        d.onClientGone(A)
        assertTrue(d.shouldCapture(A, 1L, 200, 1))
    }
}
