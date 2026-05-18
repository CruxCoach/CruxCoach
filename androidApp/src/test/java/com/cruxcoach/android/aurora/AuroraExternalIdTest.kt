package com.cruxcoach.android.aurora

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuroraExternalIdTest {

    @Test
    fun ascent_id_is_deterministic_across_calls() {
        val a = AuroraExternalId.ascent("abc-uuid", 40, "2024-01-15T10:30:00Z")
        val b = AuroraExternalId.ascent("abc-uuid", 40, "2024-01-15T10:30:00Z")
        assertEquals(a, b)
    }

    @Test
    fun ascent_id_changes_when_any_input_changes() {
        val base = AuroraExternalId.ascent("abc", 40, "2024-01-15T10:30:00Z")
        assertNotEquals(base, AuroraExternalId.ascent("abc", 45, "2024-01-15T10:30:00Z"))
        assertNotEquals(base, AuroraExternalId.ascent("abc", 40, "2024-01-15T10:30:01Z"))
        assertNotEquals(base, AuroraExternalId.ascent("xyz", 40, "2024-01-15T10:30:00Z"))
    }

    @Test
    fun ascent_and_bid_namespaces_dont_collide() {
        // Same key data, different entity prefix → different IDs.
        // Otherwise an ascent+bid pair on the same (climb, angle, ts)
        // would dedup wrongly across tables.
        val ascent = AuroraExternalId.ascent("u", 40, "2024-01-15T10:30:00Z")
        val bid = AuroraExternalId.bid("u", 40, "2024-01-15T10:30:00Z")
        assertNotEquals(ascent, bid)
        assertTrue(ascent.startsWith("aurora-json:ascent:"))
        assertTrue(bid.startsWith("aurora-json:bid:"))
    }

    @Test
    fun circuit_id_format_matches_spec() {
        val id = AuroraExternalId.circuit("My Project List", "2024-03-10T08:00:00Z")
        assertTrue(id.startsWith("aurora-json:circuit:"))
        // The 32-char hash slice keeps us well under any column-length
        // worry (full prefix + hash = 51 chars).
        val hash = id.removePrefix("aurora-json:circuit:")
        assertEquals(32, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]+")), "expected hex, got: $hash")
    }
}
