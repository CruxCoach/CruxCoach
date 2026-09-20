package com.cruxcoach.app.community

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The iOS build cannot use `java.time`, so the conversions are hand-written.
 * These tests pin them against `java.time.Instant`, which is exactly what the
 * Android app writes into `climbs.created_at` and into `created_at` on the wire.
 */
class CommunityEventTimeTest {

    @Test
    fun formatsExactlyLikeJavaTimeInstant() {
        val samples = listOf(
            0L, 1L, 59L, 60L, 86_399L, 86_400L,
            951_782_400L,          // 2000-02-29, leap day in a 400-year leap century
            1_078_012_800L,        // 2004-02-29
            1_790_000_000L,
            2_147_483_647L,
            4_102_444_800L,        // 2100-01-01, a century that is not a leap year
            -1L, -86_400L, -2_208_988_800L,
        )
        for (seconds in samples) {
            assertEquals(
                Instant.ofEpochSecond(seconds).toString(),
                CommunityEventTime.isoFromEpochSeconds(seconds),
                "epoch $seconds",
            )
        }
    }

    @Test
    fun roundTripsEveryDayOfADecade() {
        var seconds = 1_500_000_000L
        repeat(3_650) {
            val iso = CommunityEventTime.isoFromEpochSeconds(seconds)
            assertEquals(seconds, CommunityEventTime.epochSecondsFromIso(iso), iso)
            seconds += 86_400L + 3_671L
        }
    }

    @Test
    fun parsesTheShapesOtherWritersEmit() {
        assertEquals(1_789_894_400L, CommunityEventTime.epochSecondsFromIso("2026-09-20T08:53:20Z"))
        // Millisecond precision: Android's Instant.now() writes it on local drafts.
        assertEquals(1_789_894_400L, CommunityEventTime.epochSecondsFromIso("2026-09-20T08:53:20.123Z"))
        assertEquals(1_789_894_400L, CommunityEventTime.epochSecondsFromIso("2026-09-20T08:53:20.123456789Z"))
        // Offsets resolve to the same instant.
        assertEquals(1_789_894_400L, CommunityEventTime.epochSecondsFromIso("2026-09-20T10:53:20+02:00"))
        assertEquals(1_789_894_400L, CommunityEventTime.epochSecondsFromIso("2026-09-20T06:53:20-02:00"))
    }

    @Test
    fun rejectsWhatInstantParseRejects() {
        // The catalogue's space-separated stamp must NOT be read as an instant:
        // Android's runCatching(Instant.parse) returns null for it too.
        assertNull(CommunityEventTime.epochSecondsFromIso("2026-09-20 08:53:20"))
        assertNull(CommunityEventTime.epochSecondsFromIso(""))
        assertNull(CommunityEventTime.epochSecondsFromIso("2026-09-20"))
        assertNull(CommunityEventTime.epochSecondsFromIso("nonsense"))
        assertNull(CommunityEventTime.epochSecondsFromIso("2026-13-20T08:53:20Z"))
        assertNull(CommunityEventTime.epochSecondsFromIso("2026-09-20T08:53:20+0200"))
        assertNull(CommunityEventTime.epochSecondsFromIso("2026-09-20T08:53:20.Z"))
    }

    @Test
    fun clampsSuccessiveEmitsStrictlyForward() {
        // No prior publication: the wall clock stands.
        assertEquals(1000L, CommunityEventTime.monotonicCreatedAtSeconds(1000L, null))
        assertEquals(1000L, CommunityEventTime.monotonicCreatedAtSeconds(1000L, "not an instant"))
        // Same-second republish (fast typo fix) must still advance.
        val prior = CommunityEventTime.isoFromEpochSeconds(1000L)
        assertEquals(1001L, CommunityEventTime.monotonicCreatedAtSeconds(1000L, prior))
        // Backward clock step must not regress the replaceable event.
        assertEquals(1001L, CommunityEventTime.monotonicCreatedAtSeconds(900L, prior))
        // A clock that has genuinely moved on wins.
        assertEquals(5000L, CommunityEventTime.monotonicCreatedAtSeconds(5000L, prior))
    }
}
