package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ContinuousSharingAutomationTest {
    @Test fun no_consent_no_network_work() = runBlocking {
        var calls = 0
        assertTrue(ContinuousSyncTask({ false }, { calls++; false }).run())
        assertEquals(0, calls)
    }
    @Test fun offline_and_failures_retry_without_claiming_delivery() = runBlocking {
        assertFalse(ContinuousSyncTask({ true }, { false }).run())
        assertFalse(ContinuousSyncTask({ true }, { error("synthetic provider detail must not be logged") }).run())
        assertTrue(ContinuousSyncTask({ true }, { true }).run())
    }
    @Test fun cancellation_is_not_converted_to_success() = runBlocking {
        assertFailsWith<CancellationException> { ContinuousSyncTask({ true }, { throw CancellationException() }).run() }; Unit
    }
    @Test fun actual_repository_mutations_notify_after_commit_and_unsubscribe() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            SecureDatabase.Schema.create(driver)
            val db = SecureDatabase(driver)
            val repository = PersonalBoardRepositoryImpl(db)
            var wakes = 0
            val subscription = ContinuousSourceChanges(db) { wakes++ }
            db.transaction {
                repository.saveClimbNote("a", "one")
                repository.saveClimbNote("b", "two")
                assertEquals(0, wakes, "no pre-commit export")
            }
            assertEquals(1, wakes, "one coalesced notification for one source transaction")
            assertFails { db.transaction { repository.saveClimbNote("rollback", "no"); error("rollback") } }
            assertEquals(1, wakes)
            subscription.close()
            repository.saveClimbNote("a", "three")
            assertEquals(1, wakes)
            assertEquals(3L, db.continuousSharingQueries.sourceVersion().executeAsOne().revision)
        }
    }
    @Test fun background_port_work_and_a_concurrent_view_use_the_same_lock_order() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            SecureDatabase.Schema.create(driver)
            val database = SecureDatabase(driver)
            val coordination = SharingSessionCoordination.forDatabase(database)
            val port = Any()
            val enteredPort = java.util.concurrent.CountDownLatch(1)
            val viewAttempt = java.util.concurrent.CountDownLatch(1)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r).apply { isDaemon = true } }
            try {
                val background = executor.submit<Boolean> {
                    SharingSessionCoordination.withTransport(database, { work -> synchronized(port) { work() } }) {
                        enteredPort.countDown()
                        check(viewAttempt.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        Thread.sleep(25)
                        synchronized(coordination) { true } // e.g. policy/source callback
                    }
                }
                val view = executor.submit<Boolean> {
                    check(enteredPort.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    viewAttempt.countDown()
                    synchronized(coordination) { synchronized(port) { true } }
                }
                assertTrue(background.get(3, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(view.get(3, java.util.concurrent.TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

}
