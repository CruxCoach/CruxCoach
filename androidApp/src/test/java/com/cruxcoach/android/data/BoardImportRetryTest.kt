package com.cruxcoach.android.data

import android.app.Application
import android.database.sqlite.SQLiteDatabaseLockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BoardImportRetryTest {
    @Test
    fun temporaryDatabaseLockRetriesTheLocalImport() = runTest {
        var attempts = 0
        val result = retryBoardImport {
            if (++attempts < 3) throw SQLiteDatabaseLockedException("busy")
            "imported"
        }
        assertEquals("imported", result)
        assertEquals(3, attempts)
        assertEquals(1_500L, testScheduler.currentTime)
    }

    @Test
    fun persistentDatabaseLockStopsAfterBoundedRetries() = runTest {
        var attempts = 0
        val failure = SQLiteDatabaseLockedException("still busy")
        try {
            retryBoardImport { attempts++; throw failure }
            fail("Expected the unresolved lock to propagate")
        } catch (actual: SQLiteDatabaseLockedException) {
            assertSame(failure, actual)
        }
        assertEquals(4, attempts)
        assertEquals(3_500L, testScheduler.currentTime)
    }

    @Test
    fun invalidDataAndCancellationAreNotRetried() = runTest {
        for (failure in listOf(IllegalArgumentException("invalid chunk"), CancellationException("cancelled"))) {
            var attempts = 0
            try {
                retryBoardImport { attempts++; throw failure }
                fail("Expected original failure")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
            assertEquals(1, attempts)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun cancellationDuringBackoffDoesNotStartAnotherImport() = runTest {
        var attempts = 0
        val job = launch {
            retryBoardImport { attempts++; throw SQLiteDatabaseLockedException("busy") }
        }
        runCurrent()
        assertEquals(1, attempts)
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }
}
