package com.cruxcoach.app.kilter

import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the upload keeps across app starts on iOS, and the in-process follow-up runs. */
@OptIn(ExperimentalCoroutinesApi::class)
class KilterUploadPersistenceTest {
    private val store = MemoryKeyValueStore()

    @Test fun the_ledger_survives_a_restart_per_identity_and_bounded() = runTest {
        val ledger = KeyValueKilterUploadLedger(store, "abc")
        val rejection = KilterUploadRejection(
            logUuid = "log-1", fingerprint = 42, appVersionCode = 204_001, httpStatus = 500, atMs = 7,
            confirmed = false, retryAtMs = 99, unproven = 2, climbKey = "k", wireId = "W",
        )
        ledger.saveRejections(listOf(rejection))
        ledger.saveLearnedWireIds(mapOf("k" to "K"))
        ledger.saveLastOutcome(KilterUploadLastOutcome(conflicts = listOf("c"), invalid = listOf("i")))
        ledger.saveDoubtfulRequests(listOf(KilterDoubtfulRequest(5, listOf(KilterDoubtfulRow("log-2", 3, ascent = false)))))
        ledger.setImportedUploadEnabled(true)

        val restarted = KeyValueKilterUploadLedger(store, "abc")
        assertEquals(listOf(rejection), restarted.rejections())
        assertEquals(mapOf("k" to "K"), restarted.learnedWireIds())
        assertEquals(listOf("c"), restarted.lastOutcome().conflicts)
        assertEquals("log-2", restarted.doubtfulRequests().single().rows.single().logUuid)
        assertTrue(restarted.importedUploadEnabled())

        // Another identity sees nothing of it.
        val other = KeyValueKilterUploadLedger(store, "xyz")
        assertTrue(other.rejections().isEmpty())
        assertFalse(other.importedUploadEnabled())

        // Bounded like Android's preferences.
        restarted.saveRejections((1..1200).map { rejection.copy(logUuid = "r$it") })
        assertEquals(KilterUploadLedger.MAX_REJECTIONS, restarted.rejections().size)
        assertEquals("r1200", restarted.rejections().last().logUuid, "the newest decisions are kept")
        restarted.saveDoubtfulRequests((1..50).map { KilterDoubtfulRequest(it.toLong(), emptyList()) })
        assertEquals(KilterUploadLedger.MAX_DOUBTFUL_REQUESTS, restarted.doubtfulRequests().size)

        restarted.clear()
        assertTrue(restarted.rejections().isEmpty())
        assertTrue(restarted.learnedWireIds().isEmpty())
        assertFalse(restarted.importedUploadEnabled())
        assertTrue(store.values.keys.none { it.startsWith("kilter_abc_upload_") }, store.values.keys.toString())
    }

    @Test fun an_unreadable_ledger_reads_as_empty_which_retries_rather_than_holds() = runTest {
        store.putString("kilter_abc_upload_rejections", "{not json")
        store.putString("kilter_abc_upload_in_doubt", "[1,2")
        val ledger = KeyValueKilterUploadLedger(store, "abc")
        assertTrue(ledger.rejections().isEmpty())
        assertTrue(ledger.doubtfulRequests().isEmpty())
    }

    @Test fun the_diagnostics_history_is_bounded_expires_and_survives_a_restart() = runTest {
        var now = 10L * 24 * 60 * 60 * 1000
        val diagnostics = KilterUploadDiagnostics(store, "abc") { now }
        repeat(25) { diagnostics.record(KilterUploadStatus(uploaded = it, timestampMs = now)) }
        assertEquals(24, diagnostics.latest.value?.uploaded)
        assertEquals(20, diagnostics.snapshot().lines().size)

        val restarted = KilterUploadDiagnostics(store, "abc") { now }
        assertEquals(24, restarted.latest.value?.uploaded, "the last status shows after a restart")
        now += 8L * 24 * 60 * 60 * 1000
        assertEquals("", KilterUploadDiagnostics(store, "abc") { now }.snapshot(), "older than a week: gone")
        assertEquals(null, KilterUploadDiagnostics(store, "abc") { now }.latest.value)
        restarted.clear()
        assertEquals(null, restarted.latest.value)
    }

    @Test fun the_tokens_keep_the_wall_context_until_sign_out() {
        val secrets = object : com.cruxcoach.app.platform.SecretStore {
            val items = HashMap<String, ByteArray>()
            override fun read(name: String) = items[name]
            override fun write(name: String, value: ByteArray): Boolean { items[name] = value; return true }
            override fun delete(name: String) = items.remove(name) != null
        }
        val tokens = KilterTokens(secrets, store, "abc")
        assertEquals(null, tokens.wallContext())
        tokens.storeWallContext(KilterWallContext("g", "w", "1"))
        assertEquals("w", KilterTokens(secrets, store, "abc").wallContext()?.wallUuid)
        assertEquals(null, KilterTokens(secrets, store, "other").wallContext())
        tokens.store("a", "r", 100)
        assertTrue(tokens.hasCredentials())
        tokens.clear()
        assertFalse(tokens.hasCredentials())
        assertEquals(null, tokens.wallContext(), "the next account must not inherit this board")
    }

    private fun TestScope.scheduler(started: MutableList<Int>): CoroutineKilterUploadRetryScheduler =
        CoroutineKilterUploadRetryScheduler(backgroundScope) { attempt -> started += attempt }

    @Test fun follow_up_runs_start_after_their_delay_and_a_chain_is_kept_or_moved_on() = runTest {
        val started = ArrayList<Int>()
        val retries = scheduler(started)
        retries.schedule(1, replace = false)
        assertEquals(1, retries.scheduledAttempt)
        advanceTimeBy(2 * 60_000L - 1)
        runCurrent()
        assertTrue(started.isEmpty())
        // An app start or a new entry leaves the chain's back-off as it is.
        retries.schedule(1, replace = false)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(1), started)
        assertEquals(0, retries.scheduledAttempt)

        // The run of the chain moves it on.
        retries.schedule(2, replace = true)
        retries.schedule(3, replace = true)
        advanceTimeBy(15 * 60_000L)
        runCurrent()
        assertEquals(listOf(1, 3), started, "a replaced timer never fires")

        // Past the last delay nothing is scheduled; cancel stops a pending run and a wake-up.
        retries.schedule(KilterUploadRetryScheduler.DELAYS_MIN.size + 1, replace = true)
        assertEquals(0, retries.scheduledAttempt)
        retries.schedule(1, replace = true)
        retries.wakeAfter(60_000)
        retries.cancel()
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertEquals(listOf(1, 3), started)

        retries.wakeAfter(30_000)
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(listOf(1, 3, 0), started, "a wake-up run carries no attempt")
    }

    @Test fun a_run_that_schedules_the_next_one_is_not_cancelled_by_it() = runTest {
        val finished = ArrayList<Int>()
        lateinit var retries: CoroutineKilterUploadRetryScheduler
        retries = CoroutineKilterUploadRetryScheduler(backgroundScope) { attempt ->
            // As the engine does at the end of a follow-up run: schedule, then return the status.
            retries.schedule(attempt + 1, replace = true)
            kotlinx.coroutines.yield()
            finished += attempt
        }
        retries.schedule(1, replace = true)
        advanceTimeBy(2 * 60_000L + 1)
        runCurrent()
        assertEquals(listOf(1), finished)
        assertEquals(2, retries.scheduledAttempt)
    }
}
