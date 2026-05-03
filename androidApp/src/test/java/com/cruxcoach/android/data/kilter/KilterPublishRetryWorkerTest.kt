package com.cruxcoach.android.data.kilter

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.CommunityClimbRow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Unit tests for [KilterPublishRetryWorker.doWork] gating + per-row
 * outcome handling. Real WorkManager isn't involved — Worker is
 * instantiated directly with mocked dependencies.
 *
 * Covers:
 *  - early-return when user opted out (publishEnabled = false)
 *  - early-return when no Kilter access token
 *  - early-return when getProductSize returns null (board metadata missing)
 *  - empty queue → success, no API calls
 *  - per-row Success → markKilterPublishSynced
 *  - per-row TransientError → markKilterPublishFailed (transient)
 *  - per-row PermanentError on UPDATE row → markKilterPublishDiverged
 *  - per-row PermanentError on CREATE row → markKilterPublishFailed (http=)
 *  - mid-batch NotAuthenticated → bail, return Result.success
 *  - per-row throw → caught, batch continues
 *  - all-transient → Result.retry (so WorkManager re-tries before next 6h tick)
 */
class KilterPublishRetryWorkerTest {

    private lateinit var apiClient: KilterApiClient
    private lateinit var tokenStore: KilterTokenStore
    private lateinit var prefs: UserPreferences
    private lateinit var repo: BoardRepository
    private lateinit var ctx: Context
    private lateinit var workerParams: WorkerParameters

    private val boardSize = BoardSize(
        id = 1L, productId = 1L, name = "12x12",
        edgeLeft = 1, edgeRight = 144, edgeBottom = 1, edgeTop = 156,
        imageFilename = null,
    )

    private fun row(uuid: String, kilterSyncedAt: Long? = null) = CommunityClimbRow(
        uuid = uuid,
        name = "Climb $uuid",
        setterUsername = null,
        description = "",
        framesText = "p1164r12p1233r15p1392r14",
        source = "nostr",
        syncStatus = "published_nostr",
        createdByPubkey = "pk",
        nostrEventId = "ev",
        nostrDTag = "d",
        framesHash = "fh",
        createdAt = null,
        moveCount = 3L,
        kilterSyncedAt = kilterSyncedAt,
    )

    private fun worker() = KilterPublishRetryWorker(
        ctx, workerParams, repo, apiClient, tokenStore, prefs,
    )

    @Before
    fun setup() {
        apiClient = mockk(relaxed = true)
        tokenStore = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        repo = mockk(relaxed = true)
        ctx = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)

        // Sensible defaults: opted-in, has token, has board metadata.
        every { prefs.kilterClimbPublishEnabled } returns MutableStateFlow(true)
        every { prefs.boardProductSizeId } returns MutableStateFlow(1)
        every { tokenStore.getAccessToken() } returns "valid-token"
        every { repo.getProductSize(1) } returns boardSize
        every { repo.getClimbsAwaitingKilterRetry() } returns emptyList()
    }

    // ── Early-return gating ─────────────────────────────────────────

    @Test
    fun returns_success_when_user_opted_out() = runTest {
        every { prefs.kilterClimbPublishEnabled } returns MutableStateFlow(false)
        val result = worker().doWork()
        assertTrue(result is ListenableWorker.Result.Success, "expected success-skip, got $result")
        coVerify(exactly = 0) { repo.getClimbsAwaitingKilterRetry() }
    }

    @Test
    fun returns_success_when_no_kilter_token() = runTest {
        every { tokenStore.getAccessToken() } returns null
        val result = worker().doWork()
        assertTrue(result is ListenableWorker.Result.Success, "expected success-skip, got $result")
        coVerify(exactly = 0) { repo.getClimbsAwaitingKilterRetry() }
    }

    @Test
    fun returns_retry_when_board_metadata_unavailable() = runTest {
        every { repo.getClimbsAwaitingKilterRetry() } returns listOf(row("c1"))
        every { repo.getProductSize(any()) } returns null
        val result = worker().doWork()
        assertTrue(result is ListenableWorker.Result.Retry,
            "expected retry until board metadata loads, got $result")
    }

    @Test
    fun returns_success_when_queue_is_empty() = runTest {
        val result = worker().doWork()
        assertTrue(result is ListenableWorker.Result.Success, "expected empty-queue success, got $result")
        coVerify(exactly = 0) { apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── Per-row outcomes ────────────────────────────────────────────

    @Test
    fun success_row_is_marked_synced() = runTest {
        every { repo.getClimbsAwaitingKilterRetry() } returns listOf(row("c1"))
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.Success("c1")

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success, "expected success, got $result")
        coVerify(exactly = 1) {
            repo.markKilterPublishSynced(uuid = "c1", via = "self", syncedAtEpochSeconds = any())
        }
        coVerify(exactly = 0) { repo.markKilterPublishFailed(any(), any()) }
    }

    @Test
    fun transient_row_is_marked_failed_with_transient_prefix() = runTest {
        every { repo.getClimbsAwaitingKilterRetry() } returns listOf(row("c1"))
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.TransientError("net glitch")

        val result = worker().doWork()

        // Sole-row all-transient escalates to retry.
        assertTrue(result is ListenableWorker.Result.Retry, "expected retry, got $result")
        coVerify(exactly = 1) {
            repo.markKilterPublishFailed("c1", match { it.startsWith("retry transient: ") })
        }
    }

    @Test
    fun permanent_error_on_update_row_is_marked_diverged() = runTest {
        // kilterSyncedAt non-null → row was previously synced → use UPDATE
        every { repo.getClimbsAwaitingKilterRetry() } returns listOf(row("c1", kilterSyncedAt = 100L))
        coEvery {
            apiClient.updateClimb(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.PermanentError("nope", httpCode = 409)

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success, "expected success after diverged, got $result")
        coVerify(exactly = 1) {
            repo.markKilterPublishDiverged("c1", match { it.contains("http=409") })
        }
        coVerify(exactly = 0) { repo.markKilterPublishFailed(any(), any()) }
    }

    @Test
    fun permanent_error_on_create_row_is_marked_failed() = runTest {
        // kilterSyncedAt null → never synced → use CREATE
        every { repo.getClimbsAwaitingKilterRetry() } returns listOf(row("c1", kilterSyncedAt = null))
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.PermanentError("rejected", httpCode = 422)

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success, "expected success after permanent, got $result")
        coVerify(exactly = 1) {
            repo.markKilterPublishFailed("c1", match { it.contains("http=422") })
        }
        coVerify(exactly = 0) { repo.markKilterPublishDiverged(any(), any()) }
    }

    @Test
    fun not_authenticated_mid_batch_aborts_with_success() = runTest {
        every { repo.getClimbsAwaitingKilterRetry() } returns listOf(row("c1"), row("c2"))
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.NotAuthenticated

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success, "expected success-bail, got $result")
        // Bailed on first row — second row never attempted.
        coVerify(exactly = 1) { apiClient.publishClimb(climbUuid = "c1", any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { apiClient.publishClimb(climbUuid = "c2", any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun row_throw_is_caught_and_batch_continues() = runTest {
        every { repo.getClimbsAwaitingKilterRetry() } returns listOf(row("c1"), row("c2"))
        coEvery {
            apiClient.publishClimb(climbUuid = "c1", any(), any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("DB lock")
        coEvery {
            apiClient.publishClimb(climbUuid = "c2", any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.Success("c2")

        val result = worker().doWork()

        // c1 threw → marked failed; c2 succeeded → marked synced. Batch
        // didn't abort.
        assertTrue(result is ListenableWorker.Result.Success, "expected success after row-throw, got $result")
        coVerify(exactly = 1) {
            repo.markKilterPublishFailed("c1", match { it.startsWith("row threw") })
        }
        coVerify(exactly = 1) {
            repo.markKilterPublishSynced(uuid = "c2", via = "self", syncedAtEpochSeconds = any())
        }
    }

    @Test
    fun all_transient_returns_retry_for_workmanager_backoff() = runTest {
        every { repo.getClimbsAwaitingKilterRetry() } returns listOf(row("c1"), row("c2"))
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.TransientError("oops")

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Retry, "expected retry when 100% transient, got $result")
    }

    @Test
    fun mixed_outcomes_return_success_not_retry() = runTest {
        // 1 transient + 1 permanent → not all-transient, so success.
        every { repo.getClimbsAwaitingKilterRetry() } returns listOf(row("c1"), row("c2"))
        coEvery {
            apiClient.publishClimb(climbUuid = "c1", any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.TransientError("temp")
        coEvery {
            apiClient.publishClimb(climbUuid = "c2", any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.PermanentError("nope", httpCode = 422)

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success,
            "mixed outcomes shouldn't escalate to WorkManager retry, got $result")
    }
}
