package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock

class BoardSyncStalenessTest {

    private val day = 24L * 60 * 60 * 1_000

    @Test
    fun `daily and weekly thresholds use epoch duration`() {
        val now = 2_000_000_000_000L
        assertFalse(isBoardSyncStale(now - day + 1, SyncInterval.DAILY, now))
        assertTrue(isBoardSyncStale(now - day, SyncInterval.DAILY, now))
        assertFalse(isBoardSyncStale(now - 7 * day + 1, SyncInterval.WEEKLY, now))
        assertTrue(isBoardSyncStale(now - 7 * day, SyncInterval.WEEKLY, now))
    }

    @Test
    fun `missing or future epoch fails stale while manual never auto syncs`() {
        val now = 2_000_000_000_000L
        assertTrue(isBoardSyncStale(null, SyncInterval.DAILY, now))
        assertTrue(isBoardSyncStale(now + 1, SyncInterval.DAILY, now))
        assertFalse(isBoardSyncStale(null, SyncInterval.MANUAL, now))
    }

    @Test
    fun `display timestamp and epoch are written and cleared atomically`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)
        val before = Clock.System.now().toEpochMilliseconds()

        preferences.setLastSyncTimestamp("2026-07-16T12:00:00")

        val after = Clock.System.now().toEpochMilliseconds()
        assertEquals("2026-07-16T12:00:00", preferences.lastSyncTimestamp.first())
        assertTrue(preferences.lastSyncEpochMillis.first() in before..after)

        preferences.setLastSyncTimestamp(null)
        assertNull(preferences.lastSyncTimestamp.first())
        assertNull(preferences.lastSyncEpochMillis.first())
    }
}
