package com.cruxcoach.android.fips

import com.cruxcoach.android.fips.FipsDialScheduler.Admission
import com.cruxcoach.android.fips.FipsScanCoalescer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FipsDialSchedulerTest {

    @Test
    fun `one platform dial runs at a time`() {
        val scheduler = FipsDialScheduler()

        assertEquals(Admission.DIAL, scheduler.admit(1, 0))
        assertEquals(Admission.DEFER, scheduler.admit(2, 0))
        assertEquals(1, scheduler.activeDials())

        scheduler.release(1)
        assertEquals(Admission.DIAL, scheduler.admit(2, 10))
        assertEquals(1, scheduler.activeDials())
        assertEquals(0, scheduler.deferredDials())
    }

    /**
     * The whole point of the class: contention must never turn into a
     * `bleDeliverConnectResult(.., false, ..)`, which FIPS reads as a radio
     * failure and answers with per-address exponential backoff.
     */
    @Test
    fun `contention defers instead of failing`() {
        val scheduler = FipsDialScheduler()
        scheduler.admit(1, 0)

        repeat(5) { attempt ->
            assertEquals(
                "a busy radio must keep deferring, not report a failure",
                Admission.DEFER,
                scheduler.admit(2, attempt * 100L),
            )
        }
    }

    @Test
    fun `a deferral gives up after its budget rather than waiting forever`() {
        val scheduler = FipsDialScheduler(deferBudgetMs = 1_000)
        scheduler.admit(1, 0)

        assertEquals(Admission.DEFER, scheduler.admit(2, 0))
        assertEquals(Admission.DEFER, scheduler.admit(2, 999))
        assertEquals(Admission.ABANDON, scheduler.admit(2, 1_000))
        assertEquals(0, scheduler.deferredDials())
    }

    @Test
    fun `the deferral queue is bounded`() {
        val scheduler = FipsDialScheduler(maxDeferredDials = 2)
        scheduler.admit(1, 0)

        assertEquals(Admission.DEFER, scheduler.admit(2, 0))
        assertEquals(Admission.DEFER, scheduler.admit(3, 0))
        assertEquals(Admission.ABANDON, scheduler.admit(4, 0))
        assertEquals(2, scheduler.deferredDials())
    }

    @Test
    fun `an abandoned deferral frees its queue slot for the next request`() {
        val scheduler = FipsDialScheduler(maxDeferredDials = 1, deferBudgetMs = 500)
        scheduler.admit(1, 0)
        assertEquals(Admission.DEFER, scheduler.admit(2, 0))
        assertEquals(Admission.ABANDON, scheduler.admit(4, 0))

        assertEquals(Admission.ABANDON, scheduler.admit(2, 500))
        assertEquals(Admission.DEFER, scheduler.admit(4, 500))
    }

    @Test
    fun `releasing a dial that was never admitted is harmless`() {
        val scheduler = FipsDialScheduler()
        scheduler.release(99)

        assertEquals(Admission.DIAL, scheduler.admit(1, 0))
        assertEquals(1, scheduler.activeDials())
    }

    @Test
    fun `re-admitting an in-flight dial is idempotent`() {
        val scheduler = FipsDialScheduler()

        assertEquals(Admission.DIAL, scheduler.admit(1, 0))
        assertEquals(Admission.DIAL, scheduler.admit(1, 50))
        assertEquals(1, scheduler.activeDials())
    }

    @Test
    fun `two concurrent dials are allowed only when configured for two`() {
        val scheduler = FipsDialScheduler(maxConcurrentDials = 2)

        assertEquals(Admission.DIAL, scheduler.admit(1, 0))
        assertEquals(Admission.DIAL, scheduler.admit(2, 0))
        assertEquals(Admission.DEFER, scheduler.admit(3, 0))
    }
}

class FipsScanCoalescerTest {

    private val realm = "aabbccdd"
    private val cell = "11223344"
    private val nonce = "deadbeef"

    private fun FipsScanCoalescer.offer(address: String, rssi: Int, nowMs: Long) =
        offer(realm, cell, nonce, address, rssi, nowMs)

    @Test
    fun `the first advertisement of a member is always delivered`() {
        val coalescer = FipsScanCoalescer()

        assertTrue(coalescer.offer("AA:01", -60, 0) is Decision.Deliver)
        assertEquals(1, coalescer.trackedMembers())
    }

    @Test
    fun `the retained address keeps being delivered`() {
        val coalescer = FipsScanCoalescer()
        coalescer.offer("AA:01", -60, 0)

        assertTrue(coalescer.offer("AA:01", -62, 500) is Decision.Deliver)
        assertTrue(coalescer.offer("AA:01", -58, 1_000) is Decision.Deliver)
    }

    /**
     * A rotated address looks like a second peer to a scanner. Handing both to
     * FIPS is what produced the redundant dial the old code then had to
     * decline — and declining it was the thing that poisoned the backoff.
     */
    @Test
    fun `a second address of the same live member is suppressed`() {
        val coalescer = FipsScanCoalescer()
        coalescer.offer("AA:01", -60, 0)

        val decision = coalescer.offer("BB:02", -61, 500)

        assertTrue(decision is Decision.Suppress)
        assertEquals("AA:01", (decision as Decision.Suppress).retained)
        assertEquals(1, coalescer.trackedMembers())
    }

    @Test
    fun `a rotation converges once the old address stops advertising`() {
        val coalescer = FipsScanCoalescer(supersedeAfterMs = 3_000)
        coalescer.offer("AA:01", -60, 0)
        assertTrue(coalescer.offer("BB:02", -60, 1_000) is Decision.Suppress)

        // The rotated-away address is gone, so nothing refreshes it.
        assertTrue(coalescer.offer("BB:02", -60, 3_100) is Decision.Deliver)
        // ...and the new one is now the retained candidate.
        assertTrue(coalescer.offer("BB:02", -60, 3_200) is Decision.Deliver)
        assertTrue(coalescer.offer("AA:01", -60, 3_300) is Decision.Suppress)
    }

    @Test
    fun `a decisively stronger candidate takes over immediately`() {
        val coalescer = FipsScanCoalescer(rssiMarginDb = 12)
        coalescer.offer("AA:01", -80, 0)

        assertTrue("small fluctuations must not flap the choice",
            coalescer.offer("BB:02", -72, 100) is Decision.Suppress)
        assertTrue(coalescer.offer("BB:02", -68, 200) is Decision.Deliver)
    }

    @Test
    fun `a candidate that could not be dialled stops masking the others`() {
        val coalescer = FipsScanCoalescer()
        coalescer.offer("AA:01", -60, 0)
        assertTrue(coalescer.offer("BB:02", -61, 100) is Decision.Suppress)

        coalescer.forget("AA:01")

        assertTrue(coalescer.offer("BB:02", -61, 200) is Decision.Deliver)
    }

    @Test
    fun `different nonce windows and different cells are different candidates`() {
        val coalescer = FipsScanCoalescer()
        coalescer.offer(realm, cell, nonce, "AA:01", -60, 0)

        assertTrue(coalescer.offer(realm, cell, "0badc0de", "BB:02", -61, 100) is Decision.Deliver)
        assertTrue(coalescer.offer(realm, "99887766", nonce, "CC:03", -61, 100) is Decision.Deliver)
        assertEquals(3, coalescer.trackedMembers())
    }

    @Test
    fun `stale groups expire and the map stays bounded`() {
        val coalescer = FipsScanCoalescer(groupTtlMs = 1_000, maxGroups = 2)
        coalescer.offer(realm, cell, "aaaaaaaa", "AA:01", -60, 0)
        coalescer.offer(realm, cell, "bbbbbbbb", "BB:02", -60, 0)
        coalescer.offer(realm, cell, "cccccccc", "CC:03", -60, 0)
        assertEquals(2, coalescer.trackedMembers())

        coalescer.offer(realm, cell, "dddddddd", "DD:04", -60, 5_000)
        assertEquals("everything older than the TTL is gone", 1, coalescer.trackedMembers())
    }

    @Test
    fun `matching realm scans remain active after canonical membership`() {
        // FIPS needs both peers to cross-probe so its authenticated node-key
        // tie-breaker can retain exactly one deterministic direction.
        assertTrue(shouldDeliverFipsScan(matchesActiveRealm = true))
        assertFalse(shouldDeliverFipsScan(matchesActiveRealm = false))
    }
}
