package com.cruxcoach.android.data.kilter

import org.junit.Test
import kotlin.test.*

class KilterLogUploadPlanTest {

    @Test fun timestamps_become_instants_kilter_can_parse_or_are_refused() {
        assertEquals("2026-09-17T15:06:29.253273Z", KilterLogUploadPlan.kilterTimestamp("2026-09-17T15:06:29.253273Z"))
        assertEquals("2026-09-17T13:06:29Z", KilterLogUploadPlan.kilterTimestamp("2026-09-17T15:06:29+02:00"))
        // Kilter answers "YYYY-MM-DD HH:MM:SSZ" with HTTP 400; the space form is repaired.
        assertEquals("2026-09-17T15:06:29Z", KilterLogUploadPlan.kilterTimestamp("2026-09-17 15:06:29Z"))
        assertNotNull(KilterLogUploadPlan.kilterTimestamp("2026-09-17T15:06:29.123")) // device-local
        assertNull(KilterLogUploadPlan.kilterTimestamp("2026-09-17Z"))
        assertNull(KilterLogUploadPlan.kilterTimestamp(""))
    }

    @Test fun same_content_ignores_spelling_precision_and_wall_but_not_edits() {
        val local = KilterLog("log", climbUuid = "D0E5387D5B974D38B4E93FC4DFD61EF6", angle = 40, topped = true,
            attempts = 2, createdAt = "2026-09-17T15:06:29.253Z", gymUuid = "local-gym")
        val remote = local.copy(climbUuid = "d0e5387d-5b97-4d38-b4e9-3fc4dfd61ef6",
            createdAt = "2026-09-17T15:06:29.253000Z", gymUuid = "remote-gym")
        assertTrue(KilterLogUploadPlan.sameContent(local, remote))
        assertFalse(KilterLogUploadPlan.sameContent(local.copy(attempts = 3), remote))
        assertFalse(KilterLogUploadPlan.sameContent(local.copy(angle = 45), remote))
        assertFalse(KilterLogUploadPlan.sameContent(local.copy(createdAt = "2026-09-17T15:06:30Z"), remote))
    }

    @Test fun each_remote_log_backs_one_entry_exact_before_day_matches() {
        val remote = KilterLog("remote", climbUuid = "D0E5387D5B974D38B4E93FC4DFD61EF6", angle = 40,
            topped = true, createdAt = "2026-03-01T23:30:00Z")
        val twins = KilterTwinIndex(listOf(remote))
        val sameSecond = remote.copy(logUuid = "local", climbUuid = "d0e5387d5b974d38b4e93fc4dfd61ef6",
            createdAt = "2026-03-01T23:30:00.400Z")
        assertTrue(twins.claimExact(sameSecond))
        assertFalse(twins.claimExact(sameSecond), "one remote log backs one local entry")
        assertFalse(twins.claimDay(sameSecond, 0), "an exact claim also uses up the day match")

        val nextDay = remote.copy(logUuid = "imported", createdAt = "2026-03-02T01:30:00Z")
        val fresh = KilterTwinIndex(listOf(remote))
        assertFalse(fresh.claimExact(nextDay))
        assertFalse(fresh.claimDay(nextDay, 0))
        assertTrue(fresh.claimDay(nextDay, -1))
        assertFalse(fresh.claimDay(nextDay.copy(logUuid = "second"), -1))
        assertFalse(KilterTwinIndex(listOf(remote)).claimDay(nextDay.copy(topped = false), -1))
    }

    @Test fun remote_times_with_an_offset_still_match() {
        val remote = KilterLog("remote", climbUuid = "X", angle = 40, topped = true, createdAt = "2026-03-01T23:30:00+00:00")
        assertTrue(KilterTwinIndex(listOf(remote)).claimExact(remote.copy(logUuid = "local", createdAt = "2026-03-01T23:30:00Z")))
        assertTrue(KilterLogUploadPlan.sameContent(remote.copy(createdAt = "2026-03-01T23:30:00Z"), remote))
    }
}
