package com.cruxcoach.android.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqliteBusyRetryTest {

    @Test
    fun `retries SQLite lock failures then returns successful result`() = runTest {
        var calls = 0
        val result = retryingOnTransientSqliteLock(initialDelayMs = 1L) {
            calls++
            if (calls < 4) throw IllegalStateException("database is locked (code 5 SQLITE_BUSY)")
            "written"
        }

        assertEquals("written", result)
        assertEquals(4, calls)
    }

    @Test
    fun `does not retry unrelated busy errors`() = runTest {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            retryingOnTransientSqliteLock(initialDelayMs = 1L) {
                calls++
                throw IllegalStateException("remote service is busy")
            }
        }
        assertEquals(1, calls)
        assertFalse(IllegalStateException("remote service is busy").isTransientSqliteLockFailure())
        assertTrue(IllegalStateException("SQLITE_LOCKED").isTransientSqliteLockFailure())
    }
}
