package com.cruxcoach.app.kilter

import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.data.repository.RawAscent
import com.cruxcoach.data.repository.RawBid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for upload status and retry semantics: port of
 * Android's KilterUploadStatusTest onto the iOS engine. MockK stubs are
 * replaced by scripted ports with the same answers. Only synthetic data;
 * never writes to a Kilter account.
 */
class KilterUploadStatusTest {
    private class ScriptedApi : KilterUploadApi {
        var fetch: () -> KilterLogsOutcome = { KilterLogsOutcome(emptyList(), KilterFailure.NONE) }
        var upload: suspend (List<KilterLog>) -> KilterBulkOutcome = { ok }
        var deleteHeld: (String) -> KilterDeleteOutcome = { KilterDeleteOutcome.NOT_HELD }
        var delete: (String) -> KilterFailure = { KilterFailure.SERVER_ERROR }
        val uploads = ArrayList<List<KilterLog>>()
        var fetches = 0
        override suspend fun fetchLogs(): KilterLogsOutcome = fetch().also { fetches++ }
        override suspend fun uploadLogs(logs: List<KilterLog>): KilterBulkOutcome {
            uploads += logs
            return upload(logs)
        }
        override suspend fun deleteLogIfHeld(logUuid: String) = deleteHeld(logUuid)
        override suspend fun deleteLog(logUuid: String) = delete(logUuid)
    }

    private class ScriptedLogbook : KilterUploadLogbook {
        var ascents: () -> List<RawAscent> = { emptyList() }
        var bids: () -> List<RawBid> = { emptyList() }
        var mark: (String, Long) -> Boolean = { _, _ -> true }
        var deletions: List<String> = emptyList()
        var imported: Set<String> = emptySet()
        val marked = ArrayList<Pair<String, Long>>()
        override fun unsyncedAscents() = ascents()
        override fun unsyncedBids() = bids()
        override fun markAscentSyncedIfUnchanged(uuid: String, rowVersion: Long) = mark(uuid, rowVersion).also { marked += uuid to rowVersion }
        override fun markBidSyncedIfUnchanged(uuid: String, rowVersion: Long) = mark(uuid, rowVersion).also { marked += uuid to rowVersion }
        override fun runInTransaction(block: () -> Unit) = block()
        override fun pendingLogDeletions() = deletions
        override fun clearLogDeletion(logUuid: String) { deletions = deletions - logUuid }
        override fun logUuidsWithExternalIdPrefix(prefix: String) = imported
    }

    private class ScriptedCatalogue : KilterUploadCatalogue {
        var communityOnly: (Collection<String>) -> Set<String> = { emptySet() }
        var cruxcoach: (Collection<String>) -> Set<String> = { emptySet() }
        var existing: (Collection<String>) -> Set<String> = { emptySet() }
        override fun communityOnlyClimbUuids(uuids: Collection<String>) = communityOnly(uuids)
        override fun cruxcoachClimbUuids(uuids: Collection<String>) = cruxcoach(uuids)
        override fun existingClimbUuids(uuids: Collection<String>) = existing(uuids)
        override fun climbNames(uuids: Collection<String>) = emptyMap<String, String>()
    }

    private class Session : KilterUploadSession {
        var user = "test-user"
        var wall: KilterWallContext? = KilterWallContext("test-gym", "test-wall", "10")
        override fun userUuid() = user
        override fun hasCredentials() = true
        override fun wallContext() = wall
        override fun storeWallContext(context: KilterWallContext) { wall = context }
    }

    private val api = ScriptedApi()
    private val personal = ScriptedLogbook().apply { ascents = { listOf(ascent(0)) } }
    private val board = ScriptedCatalogue()
    private val session = Session()
    private val ledger = InMemoryKilterUploadLedger()
    private var push = true
    private var now = 1_000_000L
    private val engine = KilterUploadEngine(
        api, session, personal, board, ledger, KilterUploadDiagnostics(MemoryKeyValueStore(), "t") { now },
        KilterClimbIdTables.EMPTY, "0.2.4", 204_001, clock = { now }, pushEnabled = { push },
    )

    private fun ascent(i: Int) = RawAscent(
        uuid = "test-log-$i", climbUuid = "test-climb-$i", angle = 40,
        isMirror = false, attemptId = 1, bidCount = 2, quality = null,
        difficulty = null, isBenchmark = false, comment = null,
        climbedAt = "2026-09-01T12:00:00Z", synced = false,
    )

    private fun marks(uuid: String) = personal.marked.count { it.first == uuid }

    /** Kilter refuses a request as a whole when any row names a climb it does not know (live 2026-09-29). */
    private fun kilterRefuses(vararg badClimbIds: String) {
        val bad = badClimbIds.toSet()
        api.upload = { logs -> if (logs.any { it.climbUuid in bad }) status(500) else ok }
    }

    @Test fun successful_upload_marks_the_row() = runTest {
        val status = engine.upload()
        assertEquals(1, status.uploaded)
        assertFalse(status.failed)
        assertEquals(listOf("test-log-0" to 0L), personal.marked)
    }

    @Test fun total_failure_preserves_safe_http_status() = runTest {
        api.upload = { status(422) }
        val status = engine.upload()
        assertTrue(status.failed)
        assertEquals(422, status.httpStatus)
        assertEquals(1, status.pending)
        assertEquals(0, status.uploaded)
        assertTrue(personal.marked.isEmpty())
    }

    @Test fun a_row_refused_after_an_accepted_batch_does_not_block_and_is_held_once_proven() = runTest {
        personal.ascents = { (0..100).map(::ascent) }
        var call = 0
        api.upload = { if (call++ == 0) ok else status(500) }
        val report = engine.upload()
        assertEquals(100, report.uploaded)
        assertFalse(report.failed)
        // Refused last: nothing was accepted after it, so it is retried rather than held.
        assertEquals(1, report.pending)
        assertEquals(1, report.unconfirmed)
        assertEquals(0, report.rejectedByKilter)
        assertEquals(2, api.uploads.size)
        assertEquals(100, personal.marked.size)
        assertEquals(0, marks("test-log-100"))
        // Alone in the queue it waits for its retry instead of costing every trigger a request.
        personal.ascents = { listOf(ascent(100)) }
        assertEquals(1, engine.upload().unconfirmed)
        assertEquals(2, api.uploads.size)
        // With a new entry it goes first; Kilter accepts the new one after it: proven, held.
        personal.ascents = { listOf(ascent(100), ascent(101)) }
        kilterRefuses("test-climb-100")
        val proven = engine.upload()
        assertEquals(1, proven.rejectedByKilter)
        assertEquals(1, proven.uploaded)
        assertEquals(0, proven.pending)
        assertEquals(4, api.uploads.size)
    }

    @Test fun community_climb_logs_stay_local_and_do_not_block_the_rest() = runTest {
        val community = ascent(1).copy(uuid = "community-log", climbUuid = "community-climb")
        personal.ascents = { listOf(ascent(0), community) }
        board.communityOnly = { uuids -> uuids.filterTo(HashSet()) { it == "community-climb" } }
        val result = engine.upload()
        assertEquals(1, result.uploaded)
        assertEquals(0, result.pending)
        assertEquals(listOf(listOf("test-log-0")), api.uploads.map { logs -> logs.map { it.logUuid } })
        assertEquals(0, marks("community-log"))
    }

    @Test fun a_missing_wall_is_resolved_from_the_accounts_logs_or_blocks_the_upload() = runTest {
        // iOS creates no custom wall: without a log naming one, nothing goes up.
        session.wall = null
        api.fetch = { KilterLogsOutcome(listOf(KilterLog("other", climbUuid = "c")), KilterFailure.NONE) }
        val blocked = engine.upload()
        assertEquals(KilterUploadReason.WALL_CONTEXT, blocked.reason)
        assertTrue(blocked.failed)
        assertEquals(1, blocked.nextRetry)
        assertTrue(api.uploads.isEmpty())
        // A failed read is reported as what it is, never replaced by a guess.
        api.fetch = { KilterLogsOutcome(emptyList(), KilterFailure.SERVER_ERROR, 503) }
        assertEquals(KilterUploadReason.HTTP, engine.upload().reason)
        assertEquals(null, session.wall)
        // A log with a wall: stored once, and every row names it.
        api.fetch = {
            KilterLogsOutcome(listOf(KilterLog("other", climbUuid = "c", gymUuid = "g", wallUuid = "w", productLayoutUuid = "1")), KilterFailure.NONE)
        }
        assertEquals(1, engine.upload().uploaded)
        assertEquals("w", session.wall?.wallUuid)
        assertEquals("g", api.uploads.single().single().gymUuid)
        assertEquals(3, api.fetches, "the logbook read for the wall is reused for the run")
    }

    @Test fun missing_user_identity_requires_reauthentication() = runTest {
        session.user = ""
        val report = engine.upload()
        assertEquals(0, report.uploaded)
        assertTrue(report.failed)
        assertEquals(KilterUploadReason.AUTHENTICATION, report.reason)
        assertTrue(api.uploads.isEmpty())
    }

    @Test fun disabled_upload_keeps_queue_and_makes_no_request() = runTest {
        push = false
        val result = engine.upload()
        assertEquals(KilterUploadReason.DISABLED, result.reason)
        assertEquals(1, result.pending)
        assertTrue(api.uploads.isEmpty())
    }

    @Test fun concurrent_local_edit_stays_pending() = runTest {
        personal.mark = { _, _ -> false }
        val result = engine.upload()
        assertEquals(1, result.uploaded)
        assertEquals(1, result.pending)
    }

    @Test fun authentication_rejection_requests_relogin() = runTest {
        api.upload = { status(401) }
        val result = engine.upload()
        assertEquals(KilterUploadReason.AUTHENTICATION, result.reason)
        assertTrue(engine.sessionExpired.value)
        engine.clearSessionExpired()
        assertFalse(engine.sessionExpired.value)
    }

    @Test fun network_errors_keep_the_queue_and_carry_no_detail() = runTest {
        api.upload = { KilterBulkOutcome(0, KilterFailure.OFFLINE, mayHaveReachedKilter = false) }
        val result = engine.upload()
        assertEquals(KilterUploadReason.NETWORK, result.reason)
        assertEquals(1, result.pending)
        assertTrue(ledger.doubtfulRequests().isEmpty(), "a request that never left leaves no doubt")
    }

    @Test fun cancellation_does_not_mark_rows_or_become_a_failure_result() = runTest {
        api.upload = { throw kotlinx.coroutines.CancellationException("cancel") }
        assertFailsWith<kotlinx.coroutines.CancellationException> { engine.upload() }
        assertTrue(personal.marked.isEmpty())
        assertEquals(1, ledger.doubtfulRequests().size, "the note taken before the request survives")
    }

    @Test fun opt_out_between_batches_stops_remaining_requests() = runTest {
        personal.ascents = { (0..100).map(::ascent) }
        api.upload = {
            push = false
            ok
        }
        val result = engine.upload()
        assertEquals(100, result.uploaded)
        assertEquals(1, result.pending)
        assertEquals(KilterUploadReason.DISABLED, result.reason)
        assertEquals(1, api.uploads.size)
    }

    @Test fun parallel_triggers_reread_queue_after_first_upload_finishes() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var remaining = listOf(ascent(0))
        personal.ascents = { remaining }
        personal.mark = { _, _ ->
            remaining = emptyList()
            true
        }
        api.upload = {
            entered.complete(Unit)
            release.await()
            ok
        }
        val first = async { engine.upload() }
        entered.await()
        // Enter at once: without the mutex this would reach the held call before release.
        val second = async(start = CoroutineStart.UNDISPATCHED) { engine.upload() }
        release.complete(Unit)
        assertEquals(1, first.await().uploaded + second.await().uploaded)
        assertEquals(1, api.uploads.size)
    }

    @Test fun retry_uses_same_log_identity_and_marks_only_after_success() = runTest {
        var call = 0
        api.upload = { if (call++ == 0) status(503) else ok }
        val first = engine.upload()
        assertEquals(1, first.pending)
        assertTrue(personal.marked.isEmpty())
        val second = engine.upload()
        assertEquals(0, second.pending)
        assertFalse(second.failed)
        assertEquals(2, api.uploads.count { it.single().logUuid == "test-log-0" })
        assertEquals(listOf("test-log-0" to 0L), personal.marked)
    }

    // ── Isolation, identity and duplicate protection (0.2.4) ─────────────

    @Test fun one_unknown_climb_among_many_is_isolated_and_everything_else_uploads() = runTest {
        personal.ascents = { (0..399).map(::ascent) }
        kilterRefuses("test-climb-137")
        val result = engine.upload()
        assertEquals(399, result.uploaded)
        assertEquals(1, result.rejectedByKilter)
        assertEquals(0, result.pending)
        assertEquals(KilterUploadReason.NONE, result.reason)
        assertEquals(0, marks("test-log-137"))
        // 4 chunks + about two requests per halving of the refused one.
        assertTrue(result.requests <= 4 + 2 * 7, "requests=${result.requests}")
    }

    @Test fun the_other_case_of_a_compact_id_is_tried_once_when_the_index_misses_a_climb() = runTest {
        val lower = "0123456789abcdef0123456789abcdef"
        personal.ascents = { listOf(ascent(0).copy(climbUuid = lower)) }
        kilterRefuses(lower.uppercase())
        val result = engine.upload()
        assertEquals(1, result.uploaded)
        assertEquals(2, result.requests)
        assertEquals(1, api.uploads.count { it.single().climbUuid == lower })
    }

    @Test fun cruxcoach_climbs_keep_their_lowercase_id_on_the_wire() = runTest {
        val own = "0123456789abcdef0123456789abcdef"
        personal.ascents = { listOf(ascent(0).copy(climbUuid = own)) }
        board.cruxcoach = { uuids -> uuids.filterTo(HashSet()) { it == own } }
        engine.upload()
        assertEquals(own, api.uploads.single().single().climbUuid)
    }

    @Test fun a_500_outage_never_parks_rows_only_an_accepted_upload_proves_a_refusal() = runTest {
        api.upload = { status(500) }
        val first = engine.upload()
        assertEquals(KilterUploadReason.HTTP, first.reason)
        repeat(3) {
            now += 25 * 60 * 60 * 1000L
            // Only the unproven row is left: retried after its pause, reported, never held.
            val run = engine.upload()
            assertEquals(1, run.pending)
            assertEquals(1, run.unconfirmed)
            assertEquals(0, run.rejectedByKilter)
        }
        // Kilter is back: the suspect goes first and fails alone, another entry goes through after it.
        personal.ascents = { listOf(ascent(0), ascent(1)) }
        kilterRefuses("test-climb-0")
        val recovered = engine.upload()
        assertEquals(1, recovered.uploaded)
        assertEquals(1, recovered.rejectedByKilter)
        assertEquals(KilterUploadReason.NONE, recovered.reason)
        personal.ascents = { listOf(ascent(0)) }
        engine.upload()
        // 4 outage runs + the suspect and the new entry; the held row costs the last run nothing.
        assertEquals(4 + 2, api.uploads.size)
    }

    @Test fun a_lone_failure_is_proven_only_by_an_upload_accepted_after_it_in_the_same_run() = runTest {
        kilterRefuses("test-climb-0")
        assertEquals(0, engine.upload().rejectedByKilter) // alone, no proof yet
        now += 60_000
        personal.ascents = { listOf(ascent(1)) }
        assertEquals(1, engine.upload().uploaded) // Kilter accepts uploads, but not after the refusal
        now += 60_000
        personal.ascents = { listOf(ascent(0)) }
        val third = engine.upload()
        assertEquals(0, third.rejectedByKilter, "an upload accepted in another run proves nothing about this row")
        assertEquals(1, third.unconfirmed)
        personal.ascents = { listOf(ascent(0), ascent(2)) }
        val fourth = engine.upload()
        assertEquals(1, fourth.rejectedByKilter)
        assertEquals(0, fourth.pending)
    }

    @Test fun an_entry_refused_alone_on_schedule_three_times_is_listed_as_unknown_and_still_tried() = runTest {
        kilterRefuses("test-climb-0")
        engine.upload()
        assertEquals(KilterNotUploadedReason.RETRY_LATER, engine.notUploadedEntries().single().reason)
        repeat(2) {
            now += 25 * 60 * 60 * 1000L
            engine.upload()
        }
        assertEquals(KilterNotUploadedReason.NOT_ON_KILTER, engine.notUploadedEntries().single().reason)
        now += 25 * 60 * 60 * 1000L
        val run = engine.upload()
        assertEquals(1, run.requests, "never held without proof: still tried")
        assertEquals(0, run.rejectedByKilter)
        assertEquals(1, run.unconfirmed)
    }

    @Test fun a_failed_logbook_read_reports_login_or_kilters_status_not_an_internal_error() = runTest {
        api.fetch = { KilterLogsOutcome(emptyList(), KilterFailure.NOT_SIGNED_IN, 401) }
        assertEquals(KilterUploadReason.AUTHENTICATION, engine.upload().reason)
        api.fetch = { KilterLogsOutcome(emptyList(), KilterFailure.SERVER_ERROR, 500) }
        val down = engine.upload()
        assertEquals(KilterUploadReason.HTTP, down.reason)
        assertEquals(500, down.httpStatus)
        api.fetch = { KilterLogsOutcome(emptyList(), KilterFailure.TIMEOUT) }
        assertEquals(KilterUploadReason.TIMEOUT, engine.upload().reason)
        assertTrue(api.uploads.isEmpty())
    }

    @Test fun an_offline_run_does_not_count_rows_held_with_proof_as_pending() = runTest {
        personal.ascents = { listOf(ascent(0), ascent(1)) }
        kilterRefuses("test-climb-0")
        assertEquals(1, engine.upload().rejectedByKilter)
        personal.ascents = { listOf(ascent(0), ascent(2)) }
        api.fetch = { KilterLogsOutcome(emptyList(), KilterFailure.OFFLINE) }
        val offline = engine.upload()
        assertEquals(KilterUploadReason.NETWORK, offline.reason)
        assertEquals(1, offline.pending)
        assertEquals(1, offline.rejectedByKilter)
    }

    @Test fun an_edit_releases_a_held_row() = runTest {
        personal.ascents = { listOf(ascent(0), ascent(1)) }
        kilterRefuses("test-climb-0")
        assertEquals(1, engine.upload().rejectedByKilter)
        personal.ascents = { listOf(ascent(0).copy(bidCount = 3, rowVersion = 1)) }
        api.upload = { ok }
        assertEquals(1, engine.upload().uploaded)
    }

    @Test fun transient_statuses_stop_the_run_without_splitting() = runTest {
        personal.ascents = { (0..9).map(::ascent) }
        api.upload = { status(503) }
        val result = engine.upload()
        assertEquals(1, result.requests)
        assertEquals(10, result.pending)
        assertEquals(503, result.httpStatus)
        assertEquals(0, result.rejectedByKilter)
    }

    @Test fun requests_per_run_are_bounded() = runTest {
        personal.ascents = { (0..199).map(::ascent) }
        api.upload = { status(500) }
        val result = engine.upload()
        assertEquals(40, result.requests)
        assertEquals(200, result.pending)
        assertEquals(KilterUploadReason.HTTP, result.reason)
    }

    @Test fun a_log_kilter_already_has_is_marked_without_posting_it_again() = runTest {
        // e.g. the response to an earlier upload was lost after Kilter stored it
        api.fetch = {
            KilterLogsOutcome(listOf(KilterLog(
                logUuid = "test-log-0", userUuid = "test-user", climbUuid = "test-climb-0", angle = 40,
                flashed = false, topped = true, attempts = 2, createdAt = "2026-09-01T12:00:00.000000Z",
            )), KilterFailure.NONE)
        }
        val status = engine.upload()
        assertEquals(1, status.alreadyOnKilter)
        assertEquals(0, status.pending)
        assertTrue(api.uploads.isEmpty())
        assertEquals(listOf("test-log-0" to 0L), personal.marked)
    }

    @Test fun an_edit_kilter_cannot_take_is_held_as_conflict_and_the_rest_uploads() = runTest {
        personal.ascents = { listOf(ascent(0), ascent(1)) }
        api.fetch = {
            KilterLogsOutcome(listOf(KilterLog(
                logUuid = "test-log-0", climbUuid = "test-climb-0", angle = 40, topped = true,
                attempts = 5, createdAt = "2026-09-01T12:00:00Z",
            )), KilterFailure.NONE)
        }
        val result = engine.upload()
        assertEquals(1, result.rejectedConflict)
        assertEquals(1, result.uploaded)
        assertEquals(0, result.pending)
        assertFalse(result.failed)
        assertEquals(listOf(listOf("test-log-1")), api.uploads.map { logs -> logs.map { it.logUuid } })
    }

    @Test fun the_same_ascent_under_another_uuid_is_not_uploaded_twice() = runTest {
        api.fetch = {
            KilterLogsOutcome(listOf(KilterLog(
                logUuid = "kilter-own-uuid", climbUuid = "test-climb-0", angle = 40, topped = true,
                attempts = 1, createdAt = "2026-09-01T12:00:00Z",
            )), KilterFailure.NONE)
        }
        val result = engine.upload()
        assertEquals(1, result.alreadyOnKilter)
        assertTrue(api.uploads.isEmpty())
    }

    @Test fun aurora_imported_entries_stay_local_until_the_user_opts_in() = runTest {
        val imported = ascent(1).copy(externalId = "aurora-json:ascent:0123", climbedAt = "2026-03-02T01:00:00Z")
        personal.ascents = { listOf(ascent(0), imported) }
        val held = engine.upload()
        assertEquals(1, held.heldImported)
        assertEquals(0, held.pending)
        assertEquals(listOf(listOf("test-log-0")), api.uploads.map { logs -> logs.map { it.logUuid } })

        // Opted in: an entry Kilter has for the same climb, angle and day is linked, not uploaded.
        personal.ascents = { listOf(imported) }
        api.fetch = {
            KilterLogsOutcome(listOf(KilterLog(
                logUuid = "migrated", climbUuid = "TEST-CLIMB-1", angle = 40, topped = true,
                createdAt = "2026-03-01T23:15:00Z",
            )), KilterFailure.NONE)
        }
        engine.setImportedUploadEnabled(true)
        val linked = engine.upload()
        assertEquals(1, linked.alreadyOnKilter)
        assertEquals(0, linked.heldImported)
        assertEquals(1, api.uploads.size)
        assertFalse(engine.importedUploadEnabled(), "the opt-in covers the entries present when given")
    }

    @Test fun an_unparseable_timestamp_is_held_without_a_request() = runTest {
        personal.ascents = { listOf(ascent(0).copy(climbedAt = "2026-09-01Z")) }
        val result = engine.upload()
        assertEquals(1, result.rejectedInvalid)
        assertEquals(0, result.pending)
        assertTrue(api.uploads.isEmpty())
    }

    @Test fun diagnostics_line_carries_counts_but_no_identifiers() {
        val line = KilterUploadDiagnostics.diagnosticLine(KilterUploadStatus(
            uploaded = 3, pending = 1, rejectedByKilter = 2, heldImported = 5, alreadyOnKilter = 4, requests = 7))
        assertTrue(line.contains("requests=7 rejectedKilter=2 rejectedConflict=0 rejectedInvalid=0 heldImported=5 alreadyOnKilter=4"))
    }

    @Test fun every_full_chunk_goes_out_before_a_refused_one_is_isolated() = runTest {
        personal.ascents = { (0..599).map(::ascent) }
        kilterRefuses("test-climb-5")
        val result = engine.upload()
        assertEquals(List(6) { 100 }, api.uploads.take(6).map { it.size })
        assertEquals(599, result.uploaded)
        assertEquals(1, result.rejectedByKilter)
    }

    @Test fun a_log_being_deleted_on_kilter_is_no_twin() = runTest {
        api.fetch = {
            KilterLogsOutcome(listOf(KilterLog(
                logUuid = "deleted-here", climbUuid = "test-climb-0", angle = 40, topped = true,
                createdAt = "2026-09-01T12:00:00Z",
            )), KilterFailure.NONE)
        }
        personal.deletions = listOf("deleted-here")
        val result = engine.upload()
        assertEquals(0, result.alreadyOnKilter)
        assertEquals(1, result.uploaded)
    }

    @Test fun an_exact_twin_is_not_taken_by_a_looser_match_of_another_entry() = runTest {
        // Kilter has L (05-01); the export has E2 = exact twin of L and E1, a different send on 05-02.
        val e1 = ascent(1).copy(uuid = "e1", climbUuid = "test-climb-x", externalId = "aurora-json:ascent:1",
            climbedAt = "2026-05-02T10:00:00Z")
        val e2 = ascent(2).copy(uuid = "e2", climbUuid = "test-climb-x", externalId = "aurora-json:ascent:2",
            climbedAt = "2026-05-01T18:00:00Z")
        personal.ascents = { listOf(e1, e2) }
        api.fetch = {
            KilterLogsOutcome(listOf(KilterLog(
                logUuid = "L", climbUuid = "test-climb-x", angle = 40, topped = true, createdAt = "2026-05-01T18:00:00Z",
            )), KilterFailure.NONE)
        }
        engine.setImportedUploadEnabled(true)
        val result = engine.upload()
        assertEquals(1, result.alreadyOnKilter)
        assertEquals(1, result.uploaded)
        assertEquals(listOf(listOf("e1")), api.uploads.map { logs -> logs.map { it.logUuid } })
    }

    @Test fun a_failure_without_an_answer_is_told_apart_from_one_that_never_reached_kilter() {
        // Never sent: nothing can be on Kilter.
        for (code in listOf(-1009, -1003, -1004, -1006, -1020, -1200, -1202)) {
            assertFalse(KilterUploadFailure.mayHaveReachedKilter("NSURLErrorDomain:$code"), "$code")
        }
        // Sent, answer missing (connection lost, timed out, cancelled, unknown): Kilter may have written it.
        for (detail in listOf("NSURLErrorDomain:-1005", "NSURLErrorDomain:-1001", "NSURLErrorDomain:-999", "", "redirect to a non-https URL")) {
            assertTrue(KilterUploadFailure.mayHaveReachedKilter(detail), detail)
        }
    }

    @Test fun a_timeout_is_reported_as_such_and_the_request_is_noted_as_doubtful() = runTest {
        api.upload = { KilterBulkOutcome(0, KilterFailure.TIMEOUT, mayHaveReachedKilter = true) }
        val result = engine.upload()
        assertEquals(KilterUploadReason.TIMEOUT, result.reason)
        assertEquals(1, result.pending)
        assertEquals(1, result.nextRetry)
        assertEquals(1, ledger.doubtfulRequests().size)
        assertTrue(KilterUploadDiagnostics.diagnosticLine(result).endsWith("replaced=0 autoRetry=1"))
    }

    // ── iOS specifics ─────────────────────────────────────────────────

    @Test fun deletions_go_out_also_when_nothing_is_left_to_upload() = runTest {
        personal.ascents = { emptyList() }
        personal.deletions = listOf("gone-1", "gone-2")
        api.delete = { uuid -> if (uuid == "gone-1") KilterFailure.NONE else KilterFailure.SERVER_ERROR }
        val status = engine.upload()
        assertEquals(KilterUploadReason.NONE, status.reason)
        assertEquals(listOf("gone-2"), personal.deletions, "a failed deletion keeps its tombstone for the next run")
        assertTrue(api.uploads.isEmpty())
    }

    @Test fun a_busy_catalogue_fails_the_run_without_a_request() = runTest {
        board.cruxcoach = { throw IllegalStateException("database is locked") }
        val status = engine.upload()
        assertEquals(KilterUploadReason.INTERNAL, status.reason)
        assertEquals(1, status.nextRetry)
        assertTrue(api.uploads.isEmpty())
    }

    @Test fun clearing_the_ledger_forgets_decisions_history_and_the_opt_in() = runTest {
        kilterRefuses("test-climb-0")
        personal.ascents = { listOf(ascent(0), ascent(1)) }
        engine.upload()
        engine.setImportedUploadEnabled(true)
        assertTrue(ledger.rejections().isNotEmpty())
        engine.clearUploadLedger()
        assertTrue(ledger.rejections().isEmpty())
        assertFalse(engine.importedUploadEnabled())
        assertEquals(null, engine.uploadStatus.value)
        assertEquals("", engine.diagnosticSnapshot())
    }

    private companion object {
        val ok = KilterBulkOutcome(200, KilterFailure.NONE, mayHaveReachedKilter = true)
        fun status(code: Int) = KilterBulkOutcome(code, KilterFailure.NONE, mayHaveReachedKilter = true)
    }
}
