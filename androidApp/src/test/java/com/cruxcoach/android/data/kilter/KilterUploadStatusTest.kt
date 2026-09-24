package com.cruxcoach.android.data.kilter

import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.RawAscent
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.*
import kotlinx.coroutines.async
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
        engine = KilterSyncEngine(api, tokens, board, personal, db, prefs, mockk(relaxed = true))
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

    @Test fun second_batch_failure_preserves_progress_and_reports_failure() = runTest {
        every { personal.getUnsyncedAscents() } returns (0..200).map(::ascent)
        coEvery { api.uploadLogs(any()) } returnsMany listOf(
            Result.success(Unit), Result.failure(KilterUploadException(500)),
        )
        val report = engine.syncBidirectional().getOrThrow()
        assertEquals(200, report.uploaded)
        assertTrue(report.uploadFailed)
        assertEquals(1, report.uploadStatus?.pending)
        assertEquals(500, report.uploadStatus?.httpStatus)
        coVerify(exactly = 2) { api.uploadLogs(any()) }
        verify(exactly = 200) { personal.markAscentSyncedIfUnchanged(any(), any()) }
        verify(exactly = 0) { personal.markAscentSyncedIfUnchanged("test-log-200", any()) }
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

}
