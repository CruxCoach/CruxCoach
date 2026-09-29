package com.cruxcoach.android.data.kilter

import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.RawAscent
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.*

/** Regression coverage for upload status and retry semantics.
 * Only synthetic data and mocked endpoints; never writes to a Kilter account.
 */
class KilterUploadStatusTest {
    private val api = mockk<KilterApiClient>(relaxed = true)
    private val tokens = mockk<KilterTokenStore>(relaxed = true)
    private val prefs = mockk<UserPreferences>(relaxed = true)
    private val personal = mockk<PersonalBoardRepository>(relaxed = true)
    private val board = mockk<BoardRepository>(relaxed = true)
    private val db = mockk<SecureDatabase>(relaxed = true)
    private lateinit var engine: KilterSyncEngine

    @Before fun setup() {
        every { prefs.kilterPushEnabled } returns flowOf(true)
        every { tokens.hasCredentials() } returns true
        every { tokens.getUserUuid() } returns "test-user"
        every { tokens.hasWallContext() } returns true
        every { tokens.getGymUuid() } returns "test-gym"
        every { tokens.getWallUuid() } returns "test-wall"
        every { tokens.getProductLayoutUuid() } returns "10"
        every { personal.runInTransaction(any()) } answers { firstArg<() -> Unit>()() }
        every { personal.markAscentSyncedIfUnchanged(any(), any()) } returns true
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0))
        every { personal.getUnsyncedBids() } returns emptyList()
        every { board.communityOnlyClimbUuids(any()) } returns emptySet()
        coEvery { api.fetchLogs() } returns Result.success(emptyList())
        coEvery { api.fetchLoggedClimbs() } returns Result.success(KilterLoggedClimbsResponse())
        coEvery { api.fetchOwnAuthoredClimbs() } returns Result.success(emptyList())
        coEvery { api.fetchCircuits() } returns Result.success(emptyList())
        coEvery { api.uploadLogs(any()) } returns Result.success(Unit)
        engine = KilterSyncEngine(
            api, tokens, board, personal, db, prefs, mockk(relaxed = true),
            dagger.Lazy { mockk<com.cruxcoach.android.data.PendingImports>(relaxed = true) },
        )
    }

    private fun ascent(i: Int) = RawAscent(
        uuid = "test-log-$i", climbUuid = "test-climb-$i", angle = 40,
        isMirror = false, attemptId = 1, bidCount = 2, quality = null,
        difficulty = null, isBenchmark = false, comment = null,
        climbedAt = "2026-09-01T12:00:00Z", synced = false,
    )

    @Test fun successful_upload_marks_the_row() = runTest {
        val report = engine.syncBidirectional().getOrThrow()
        assertEquals(1, report.uploaded)
        assertFalse(report.uploadFailed)
        verify(exactly = 1) { personal.markAscentSyncedIfUnchanged("test-log-0", 0) }
    }

    @Test fun total_failure_preserves_safe_http_status() = runTest {
        coEvery { api.uploadLogs(any()) } returns Result.failure(KilterUploadException(422))
        val report = engine.syncBidirectional().getOrThrow()
        assertTrue(report.uploadFailed)
        assertEquals(422, report.uploadStatus?.httpStatus)
        assertEquals(1, report.uploadStatus?.pending)
        assertEquals(0, report.uploaded)
        verify(exactly = 0) { personal.markAscentSyncedIfUnchanged(any(), any()) }
    }

    @Test fun a_row_refused_after_an_accepted_batch_is_held_back_instead_of_blocking() = runTest {
        every { personal.getUnsyncedAscents() } returns (0..200).map(::ascent)
        coEvery { api.uploadLogs(any()) } returnsMany listOf(
            Result.success(Unit), Result.failure(KilterUploadException(500)),
        )
        val report = engine.syncBidirectional().getOrThrow()
        assertEquals(200, report.uploaded)
        assertFalse(report.uploadFailed)
        assertEquals(0, report.uploadStatus?.pending)
        assertEquals(1, report.uploadStatus?.rejectedByKilter)
        coVerify(exactly = 2) { api.uploadLogs(any()) }
        verify(exactly = 200) { personal.markAscentSyncedIfUnchanged(any(), any()) }
        verify(exactly = 0) { personal.markAscentSyncedIfUnchanged("test-log-200", any()) }
        // Held back: the next run does not send it again.
        every { personal.getUnsyncedAscents() } returns listOf(ascent(200))
        val next = engine.uploadPendingLogs()
        assertEquals(1, next.rejectedByKilter)
        coVerify(exactly = 2) { api.uploadLogs(any()) }
    }

    @Test fun community_climb_logs_stay_local_and_do_not_block_the_rest() = runTest {
        val community = ascent(1).copy(uuid = "community-log", climbUuid = "community-climb")
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0), community)
        every { board.communityOnlyClimbUuids(any()) } answers {
            firstArg<Collection<String>>().filterTo(HashSet()) { it == "community-climb" }
        }
        val result = engine.uploadPendingLogs()
        assertEquals(1, result.uploaded)
        assertEquals(0, result.pending)
        coVerify(exactly = 1) { api.uploadLogs(match { logs -> logs.map { it.logUuid } == listOf("test-log-0") }) }
        verify(exactly = 0) { personal.markAscentSyncedIfUnchanged("community-log", any()) }
    }

    @Test fun missing_wall_is_reported_as_blocked() = runTest {
        every { tokens.hasWallContext() } returns false
        every { tokens.getGymUuid() } returns null
        every { api.resolveWallContextFromLogs(any()) } returns KilterApiClient.ResolveResult.Error("HTTP 503")
        coEvery { api.resolveWallContext() } returns KilterApiClient.ResolveResult.Error("HTTP 503")
        val report = engine.syncBidirectional().getOrThrow()
        assertEquals(0, report.uploaded)
        assertTrue(report.uploadFailed)
        coVerify(exactly = 0) { api.uploadLogs(any()) }
    }

    @Test fun missing_user_identity_requires_reauthentication() = runTest {
        every { tokens.getUserUuid() } returns null
        val report = engine.syncBidirectional().getOrThrow()
        assertEquals(0, report.uploaded)
        assertTrue(report.uploadFailed)
        coVerify(exactly = 0) { api.uploadLogs(any()) }
    }
    @Test fun disabled_upload_keeps_queue_and_makes_no_request() = runTest {
        every { prefs.kilterPushEnabled } returns flowOf(false)
        val result = engine.uploadPendingLogs()
        assertEquals(KilterUploadReason.DISABLED, result.reason)
        assertEquals(1, result.pending)
        coVerify(exactly = 0) { api.uploadLogs(any()) }
    }

    @Test fun concurrent_local_edit_stays_pending() = runTest {
        every { personal.markAscentSyncedIfUnchanged(any(), any()) } returns false
        val result = engine.uploadPendingLogs()
        assertEquals(1, result.uploaded)
        assertEquals(1, result.pending)
    }

    @Test fun authentication_rejection_requests_relogin() = runTest {
        coEvery { api.uploadLogs(any()) } returns Result.failure(KilterUploadException(401))
        val result = engine.uploadPendingLogs()
        assertEquals(KilterUploadReason.AUTHENTICATION, result.reason)
        assertTrue(engine.sessionExpired.value)
    }

    @Test fun network_errors_do_not_export_private_exception_text() = runTest {
        coEvery { api.uploadLogs(any()) } returns Result.failure(java.io.IOException("private account/token"))
        val result = engine.uploadPendingLogs()
        assertEquals(KilterUploadReason.NETWORK, result.reason)
        assertFalse(KilterUploadDiagnostics.diagnosticLine(result).contains("private"))
        assertEquals(1, result.pending)
    }

    @Test fun cancellation_does_not_mark_rows_or_become_a_failure_result() = runTest {
        coEvery { api.uploadLogs(any()) } throws kotlinx.coroutines.CancellationException("cancel")
        assertFailsWith<kotlinx.coroutines.CancellationException> { engine.uploadPendingLogs() }
        verify(exactly = 0) { personal.markAscentSyncedIfUnchanged(any(), any()) }
    }

    @Test fun opt_out_between_batches_stops_remaining_requests() = runTest {
        val push = kotlinx.coroutines.flow.MutableStateFlow(true)
        every { prefs.kilterPushEnabled } returns push
        every { personal.getUnsyncedAscents() } returns (0..200).map(::ascent)
        coEvery { api.uploadLogs(any()) } answers {
            push.value = false
            Result.success(Unit)
        }
        val result = engine.uploadPendingLogs()
        assertEquals(200, result.uploaded)
        assertEquals(1, result.pending)
        assertEquals(KilterUploadReason.DISABLED, result.reason)
        coVerify(exactly = 1) { api.uploadLogs(any()) }
    }

    @Test fun parallel_triggers_reread_queue_after_first_upload_finishes() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val remaining = java.util.concurrent.atomic.AtomicReference(listOf(ascent(0)))
        every { personal.getUnsyncedAscents() } answers { remaining.get() }
        every { personal.markAscentSyncedIfUnchanged(any(), any()) } answers {
            remaining.set(emptyList())
            true
        }
        coEvery { api.uploadLogs(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            Result.success(Unit)
        }
        val first = async { engine.uploadPendingLogs() }
        entered.await()
        // Enter on the IO context immediately: without the mutex this would
        // reach the held HTTP call before release, deterministically duplicating it.
        val second = async(kotlinx.coroutines.Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            engine.uploadPendingLogs()
        }
        release.complete(Unit)
        assertEquals(1, first.await().uploaded + second.await().uploaded)
        coVerify(exactly = 1) { api.uploadLogs(any()) }
    }

    @Test fun retry_uses_same_log_identity_and_marks_only_after_success() = runTest {
        coEvery { api.uploadLogs(any()) } returnsMany listOf(
            Result.failure(KilterUploadException(503)), Result.success(Unit))
        val first = engine.uploadPendingLogs()
        assertEquals(1, first.pending)
        verify(exactly = 0) { personal.markAscentSyncedIfUnchanged(any(), any()) }
        val second = engine.uploadPendingLogs()
        assertEquals(0, second.pending)
        assertFalse(second.failed)
        coVerify(exactly = 2) { api.uploadLogs(match { it.single().logUuid == "test-log-0" }) }
        verify(exactly = 1) { personal.markAscentSyncedIfUnchanged("test-log-0", 0) }
    }


    // ── Isolation, identity and duplicate protection (0.2.4) ─────────────

    /** Kilter refuses a request as a whole when any row names a climb it does not know (live 2026-09-29). */
    private fun kilterRefuses(vararg badClimbIds: String) {
        val bad = badClimbIds.toSet()
        coEvery { api.uploadLogs(any()) } answers {
            if (firstArg<List<KilterLog>>().any { it.climbUuid in bad }) Result.failure(KilterUploadException(500))
            else Result.success(Unit)
        }
    }

    @Test fun one_unknown_climb_among_many_is_isolated_and_everything_else_uploads() = runTest {
        every { personal.getUnsyncedAscents() } returns (0..399).map(::ascent)
        kilterRefuses("test-climb-137")
        val result = engine.uploadPendingLogs()
        assertEquals(399, result.uploaded)
        assertEquals(1, result.rejectedByKilter)
        assertEquals(0, result.pending)
        assertEquals(KilterUploadReason.NONE, result.reason)
        verify(exactly = 0) { personal.markAscentSyncedIfUnchanged("test-log-137", any()) }
        // 2 chunks + about two requests per halving of the refused one.
        assertTrue(result.requests <= 2 + 2 * 8, "requests=${result.requests}")
    }

    @Test fun the_other_case_of_a_compact_id_is_tried_once_when_the_index_misses_a_climb() = runTest {
        val lower = "0123456789abcdef0123456789abcdef"
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0).copy(climbUuid = lower))
        kilterRefuses(lower.uppercase())
        val result = engine.uploadPendingLogs()
        assertEquals(1, result.uploaded)
        assertEquals(2, result.requests)
        coVerify(exactly = 1) { api.uploadLogs(match { it.single().climbUuid == lower }) }
    }

    @Test fun cruxcoach_climbs_keep_their_lowercase_id_on_the_wire() = runTest {
        val own = "0123456789abcdef0123456789abcdef"
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0).copy(climbUuid = own))
        every { board.cruxcoachClimbUuids(any()) } answers { firstArg<Collection<String>>().filterTo(HashSet()) { it == own } }
        every { board.existingClimbUuids(any()) } returns emptySet()
        engine.uploadPendingLogs()
        coVerify(exactly = 1) { api.uploadLogs(match { it.single().climbUuid == own }) }
    }

    @Test fun a_500_outage_never_parks_rows_only_an_accepted_upload_proves_a_refusal() = runTest {
        var now = 1_000_000L
        engine.clock = { now }
        coEvery { api.uploadLogs(any()) } returns Result.failure(KilterUploadException(500))
        repeat(3) {
            val run = engine.uploadPendingLogs()
            assertEquals(KilterUploadReason.HTTP, run.reason)
            assertEquals(1, run.pending)
            assertEquals(0, run.rejectedByKilter)
            now += 2 * 60 * 60 * 1000L
        }
        // Kilter is back: another entry goes through, the bad one fails alone.
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0), ascent(1))
        kilterRefuses("test-climb-0")
        val recovered = engine.uploadPendingLogs()
        assertEquals(1, recovered.uploaded)
        assertEquals(1, recovered.rejectedByKilter)
        assertEquals(KilterUploadReason.NONE, recovered.reason)
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0))
        engine.uploadPendingLogs()
        // 3 outage runs + 3 requests to isolate the bad row; the held row costs the fifth run nothing.
        coVerify(exactly = 3 + 3) { api.uploadLogs(any()) }
    }

    @Test fun a_lone_failure_is_confirmed_by_an_upload_accepted_in_a_later_run() = runTest {
        var now = 1_000_000L
        engine.clock = { now }
        kilterRefuses("test-climb-0")
        assertEquals(0, engine.uploadPendingLogs().rejectedByKilter) // alone, no proof yet
        now += 60_000
        every { personal.getUnsyncedAscents() } returns listOf(ascent(1))
        assertEquals(1, engine.uploadPendingLogs().uploaded) // Kilter accepts uploads
        now += 60_000
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0))
        val third = engine.uploadPendingLogs()
        assertEquals(1, third.rejectedByKilter)
        assertEquals(0, third.pending)
    }

    @Test fun an_edit_releases_a_held_row() = runTest {
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0), ascent(1))
        kilterRefuses("test-climb-0")
        assertEquals(1, engine.uploadPendingLogs().rejectedByKilter)
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0).copy(bidCount = 3, rowVersion = 1))
        coEvery { api.uploadLogs(any()) } returns Result.success(Unit)
        assertEquals(1, engine.uploadPendingLogs().uploaded)
    }

    @Test fun transient_statuses_stop_the_run_without_splitting() = runTest {
        every { personal.getUnsyncedAscents() } returns (0..9).map(::ascent)
        coEvery { api.uploadLogs(any()) } returns Result.failure(KilterUploadException(503))
        val result = engine.uploadPendingLogs()
        assertEquals(1, result.requests)
        assertEquals(10, result.pending)
        assertEquals(503, result.httpStatus)
        assertEquals(0, result.rejectedByKilter)
    }

    @Test fun requests_per_run_are_bounded() = runTest {
        every { personal.getUnsyncedAscents() } returns (0..199).map(::ascent)
        coEvery { api.uploadLogs(any()) } returns Result.failure(KilterUploadException(500))
        val result = engine.uploadPendingLogs()
        assertEquals(40, result.requests)
        assertEquals(200, result.pending)
        assertEquals(KilterUploadReason.HTTP, result.reason)
    }

    @Test fun a_log_kilter_already_has_is_marked_without_posting_it_again() = runTest {
        // e.g. the response to an earlier upload was lost after Kilter stored it
        coEvery { api.fetchLogs() } returns Result.success(listOf(KilterLog(
            logUuid = "test-log-0", userUuid = "test-user", climbUuid = "test-climb-0", angle = 40,
            flashed = false, topped = true, attempts = 2, createdAt = "2026-09-01T12:00:00.000000Z")))
        val report = engine.syncBidirectional().getOrThrow()
        assertEquals(1, report.uploadStatus?.alreadyOnKilter)
        assertEquals(0, report.uploadStatus?.pending)
        coVerify(exactly = 0) { api.uploadLogs(any()) }
        verify(exactly = 1) { personal.markAscentSyncedIfUnchanged("test-log-0", 0) }
    }

    @Test fun an_edit_kilter_cannot_take_is_held_as_conflict_and_the_rest_uploads() = runTest {
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0), ascent(1))
        coEvery { api.fetchLogs() } returns Result.success(listOf(KilterLog(
            logUuid = "test-log-0", climbUuid = "test-climb-0", angle = 40, topped = true,
            attempts = 5, createdAt = "2026-09-01T12:00:00Z")))
        val result = engine.uploadPendingLogs()
        assertEquals(1, result.rejectedConflict)
        assertEquals(1, result.uploaded)
        assertEquals(0, result.pending)
        assertFalse(result.failed)
        coVerify(exactly = 1) { api.uploadLogs(match { logs -> logs.map { it.logUuid } == listOf("test-log-1") }) }
    }

    @Test fun the_same_ascent_under_another_uuid_is_not_uploaded_twice() = runTest {
        coEvery { api.fetchLogs() } returns Result.success(listOf(KilterLog(
            logUuid = "kilter-own-uuid", climbUuid = "test-climb-0", angle = 40, topped = true,
            attempts = 1, createdAt = "2026-09-01T12:00:00Z")))
        val result = engine.uploadPendingLogs()
        assertEquals(1, result.alreadyOnKilter)
        coVerify(exactly = 0) { api.uploadLogs(any()) }
    }

    @Test fun aurora_imported_entries_stay_local_until_the_user_opts_in() = runTest {
        val imported = ascent(1).copy(externalId = "aurora-json:ascent:0123", climbedAt = "2026-03-02T01:00:00Z")
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0), imported)
        val held = engine.uploadPendingLogs()
        assertEquals(1, held.heldImported)
        assertEquals(0, held.pending)
        coVerify(exactly = 1) { api.uploadLogs(match { logs -> logs.map { it.logUuid } == listOf("test-log-0") }) }

        // Opted in: an entry Kilter has for the same climb, angle and day is linked, not uploaded.
        every { personal.getUnsyncedAscents() } returns listOf(imported)
        coEvery { api.fetchLogs() } returns Result.success(listOf(KilterLog(
            logUuid = "migrated", climbUuid = "TEST-CLIMB-1", angle = 40, topped = true,
            createdAt = "2026-03-01T23:15:00Z")))
        engine.setImportedUploadEnabled(true)
        val linked = engine.uploadPendingLogs()
        assertEquals(1, linked.alreadyOnKilter)
        assertEquals(0, linked.heldImported)
        coVerify(exactly = 1) { api.uploadLogs(any()) }
        assertFalse(engine.importedUploadEnabled.first(), "the opt-in covers the entries present when given")
    }

    @Test fun an_unparseable_timestamp_is_held_without_a_request() = runTest {
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0).copy(climbedAt = "2026-09-01Z"))
        val result = engine.uploadPendingLogs()
        assertEquals(1, result.rejectedInvalid)
        assertEquals(0, result.pending)
        coVerify(exactly = 0) { api.uploadLogs(any()) }
    }

    @Test fun diagnostics_line_carries_counts_but_no_identifiers() {
        val line = KilterUploadDiagnostics.diagnosticLine(KilterUploadStatus(
            uploaded = 3, pending = 1, rejectedByKilter = 2, heldImported = 5, alreadyOnKilter = 4, requests = 7))
        assertTrue(line.contains("requests=7 rejectedKilter=2 rejectedConflict=0 rejectedInvalid=0 heldImported=5 alreadyOnKilter=4"))
    }

    @Test fun every_full_chunk_goes_out_before_a_refused_one_is_isolated() = runTest {
        every { personal.getUnsyncedAscents() } returns (0..599).map(::ascent)
        kilterRefuses("test-climb-5")
        val sizes = mutableListOf<Int>()
        coEvery { api.uploadLogs(any()) } answers {
            val logs = firstArg<List<KilterLog>>()
            sizes += logs.size
            if (logs.any { it.climbUuid == "test-climb-5" }) Result.failure(KilterUploadException(500)) else Result.success(Unit)
        }
        val result = engine.uploadPendingLogs()
        assertEquals(listOf(200, 200, 200), sizes.take(3))
        assertEquals(599, result.uploaded)
        assertEquals(1, result.rejectedByKilter)
    }

    @Test fun a_log_being_deleted_on_kilter_is_no_twin() = runTest {
        coEvery { api.fetchLogs() } returns Result.success(listOf(KilterLog(
            logUuid = "deleted-here", climbUuid = "test-climb-0", angle = 40, topped = true,
            createdAt = "2026-09-01T12:00:00Z")))
        every { personal.pendingLogDeletions() } returns listOf("deleted-here")
        val result = engine.uploadPendingLogs()
        assertEquals(0, result.alreadyOnKilter)
        assertEquals(1, result.uploaded)
    }

    @Test fun an_exact_twin_is_not_taken_by_a_looser_match_of_another_entry() = runTest {
        // Kilter has L (05-01); the export has E2 = exact twin of L and E1, a different send on 05-02.
        val e1 = ascent(1).copy(uuid = "e1", climbUuid = "test-climb-x", externalId = "aurora-json:ascent:1",
            climbedAt = "2026-05-02T10:00:00Z")
        val e2 = ascent(2).copy(uuid = "e2", climbUuid = "test-climb-x", externalId = "aurora-json:ascent:2",
            climbedAt = "2026-05-01T18:00:00Z")
        every { personal.getUnsyncedAscents() } returns listOf(e1, e2)
        coEvery { api.fetchLogs() } returns Result.success(listOf(KilterLog(
            logUuid = "L", climbUuid = "test-climb-x", angle = 40, topped = true, createdAt = "2026-05-01T18:00:00Z")))
        engine.setImportedUploadEnabled(true)
        val result = engine.uploadPendingLogs()
        assertEquals(1, result.alreadyOnKilter)
        assertEquals(1, result.uploaded)
        coVerify(exactly = 1) { api.uploadLogs(match { logs -> logs.map { it.logUuid } == listOf("e1") }) }
    }

    @Test fun a_logbook_read_before_another_upload_is_read_again() = runTest {
        every { personal.getUnsyncedAscents() } returns listOf(ascent(0))
        engine.uploadPendingLogs() // sends a request: a snapshot from before it is stale
        every { personal.getUnsyncedAscents() } returns listOf(ascent(1))
        engine.uploadPendingLogs(prefetchedLogs = emptyList(), snapshotGeneration = 0)
        coVerify(exactly = 2) { api.fetchLogs() }
    }
}
