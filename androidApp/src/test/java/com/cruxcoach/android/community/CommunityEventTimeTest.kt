package com.cruxcoach.android.community

import com.cruxcoach.data.repository.monotonicCreatedAtSeconds
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the monotonic `created_at` clamp that fixes the FEAT-039 convergence
 * audit BUG-1 (same-second republish / backward clock would let an edit tie or
 * regress and diverge live-sub vs Blossom chunk).
 */
class CommunityEventTimeTest {

    private fun iso(epoch: Long) = Instant.ofEpochSecond(epoch).toString()

    @Test
    fun `no prior or unparseable falls back to now`() {
        assertEquals(1000L, monotonicCreatedAtSeconds(1000L, null))
        assertEquals(1000L, monotonicCreatedAtSeconds(1000L, "garbage"))
    }

    @Test
    fun `forward clock keeps now`() {
        assertEquals(1000L, monotonicCreatedAtSeconds(1000L, iso(900L)))
    }

    @Test
    fun `same-second republish strictly advances by one`() {
        assertEquals(1001L, monotonicCreatedAtSeconds(1000L, iso(1000L)))
    }

    @Test
    fun `backward clock still advances past the prior emit`() {
        // wall clock regressed to 1500 but the climb last emitted at 2000 ->
        // the new emit must still strictly exceed 2000, not roll back.
        assertEquals(2001L, monotonicCreatedAtSeconds(1500L, iso(2000L)))
    }
}
