package com.cruxcoach.android.aurora

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pin the timestamp normaliser to the two formats Aurora's email
 * exports actually emit, plus the standard ISO 8601. A regression
 * here surfaces as silent dedup failures — two re-imports of the
 * same export produce different `external_id` hashes if a timestamp
 * canonicalises differently across runs.
 */
class AuroraTimestampTest {

    @Test
    fun normalizes_aurora_space_format_to_iso_z() {
        val out = AuroraTimestamp.normalize("2024-01-15 10:30:00")
        assertEquals("2024-01-15T10:30:00Z", out)
    }

    @Test
    fun preserves_explicit_z_suffix() {
        val out = AuroraTimestamp.normalize("2024-01-15T10:30:00Z")
        assertEquals("2024-01-15T10:30:00Z", out)
    }

    @Test
    fun preserves_offset_timezone() {
        // +02:00 stays — Instant.parse converts to UTC and the
        // canonical toString() emits with Z. So we just check it
        // round-trips to the same instant.
        val out = AuroraTimestamp.normalize("2024-01-15T10:30:00+02:00")
        assertEquals("2024-01-15T08:30:00Z", out)
    }

    @Test
    fun two_formats_of_same_instant_collapse_to_same_string() {
        // The whole point of normalisation: if two re-imports of the
        // same export send the same instant in different formats,
        // they must hash identically.
        val a = AuroraTimestamp.normalize("2024-01-15 10:30:00")
        val b = AuroraTimestamp.normalize("2024-01-15T10:30:00Z")
        assertEquals(a, b)
    }

    @Test
    fun truncates_microseconds_to_millis() {
        val out = AuroraTimestamp.normalize("2024-01-15T10:30:00.123456Z")
        assertNotNull(out)
        // Either 0.123 or 0.123Z depending on Instant.toString's emit
        // policy on the JVM. Both forms are acceptable as long as the
        // sub-second part is at most 3 digits.
        assertTrue(out.contains(".123") && !out.contains("123456"),
            "expected millis-truncated, got: $out")
    }

    @Test
    fun returns_null_on_garbage_input() {
        assertNull(AuroraTimestamp.normalize(""))
        assertNull(AuroraTimestamp.normalize("   "))
        assertNull(AuroraTimestamp.normalize("not-a-date"))
        assertNull(AuroraTimestamp.normalize("2024-99-99 00:00:00"))
    }
}
