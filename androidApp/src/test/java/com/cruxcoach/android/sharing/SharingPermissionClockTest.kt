package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.secure.SecureDatabase
import kotlin.test.*

class SharingPermissionClockTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = SecureDatabase(driver)
    private var wall = 1_800_000_000_000L
    private var mono = 1_000L
    init { SecureDatabase.Schema.create(driver) }
    private fun clock() = SharingPermissionClock(database, "a".repeat(64), { wall }, { mono })
    @AfterTest fun close() = driver.close()

    @Test fun monotonic_time_prevents_small_wall_rollback_from_extending_access() {
        val c = clock(); val first = c.now()
        wall -= 1_000; mono += 2_000
        assertEquals(first + 2_000, c.now()); assertTrue(c.healthy())
    }
    @Test fun large_backward_jump_locks_persistently_across_restart_and_clock_correction() {
        val c = clock(); assertTrue(c.healthy())
        wall -= 600_000; assertFalse(c.healthy())
        wall += 600_000; assertFalse(clock().healthy())
    }
    @Test fun large_forward_jump_locks_instead_of_pruning_retained_revocations() {
        val c = clock(); assertTrue(c.healthy())
        wall += 600_000; assertFalse(c.healthy())
    }
    @Test fun expected_time_progress_across_restart_remains_usable() {
        val c = clock(); val first = c.now()
        wall += 86_400_000; mono += 86_400_000
        assertTrue(c.healthy()); assertEquals(first + 86_400_000, clock().now())
        assertTrue(clock().healthy())
    }
}
