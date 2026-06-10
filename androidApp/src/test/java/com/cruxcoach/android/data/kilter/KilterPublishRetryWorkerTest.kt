package com.cruxcoach.android.data.kilter

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.data.repository.KilterClaim
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 *  - per-row size resolution from the ROW's own context (not the active pref)
 *  - unresolvable row size → that row marked failed, batch continues
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
    private lateinit var activePubkeyResolver: ActivePubkeyResolver

    /** Active pubkey the worker resolves on every tick. Tests that need
     *  a different active pubkey (e.g. cross-pubkey isolation) override
     *  the resolver lambda per-test. */
    private val activePubkey = "pk"

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
        layoutId = 1L,
        boardBrand = "kilter",
    )

    private fun worker() = KilterPublishRetryWorker(
        ctx, workerParams, repo, apiClient, tokenStore, prefs, activePubkeyResolver,
    )

    @Before
    fun setup() {
        apiClient = mockk(relaxed = true)
        tokenStore = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        repo = mockk(relaxed = true)
        ctx = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
        // Resolver is a small `fun interface` defined alongside the
        // worker — a lambda is the simplest mock and avoids pulling
        // the Quartz-derived NostrSigner into the test classpath.
        activePubkeyResolver = ActivePubkeyResolver { activePubkey }

        // Sensible defaults: opted-in, has token, has board metadata.
        every { prefs.kilterClimbPublishEnabled } returns MutableStateFlow(true)
        every { prefs.kilterSyncEnabled } returns MutableStateFlow(true)
        every { tokenStore.getAccessToken() } returns "valid-token"
        // Per-row size resolution: every row's bbox-pinned source size
        // resolves to size 1 unless a test overrides it.
        every { repo.getProductSizeForClimbRender(any()) } returns 1
        every { repo.getProductSize(1) } returns boardSize
        // Placements seed: minimal mapping that covers the placement IDs
        // referenced by [row]'s framesText so encodeClimbConcat returns
        // a non-blank string and the row reaches the API mock instead of
        // bailing at the "frames empty on retry" guard.
        every { repo.getAllPlacements() } returns listOf(
            BoardPlacement(placementId = 1164L, holeId = 1164L, setId = 1L, x = 0L, y = 0L),
            BoardPlacement(placementId = 1233L, holeId = 1233L, setId = 1L, x = 1L, y = 0L),
            BoardPlacement(placementId = 1392L, holeId = 1392L, setId = 1L, x = 2L, y = 0L),
        )
        every { repo.getClimbsAwaitingKilterRetry(any()) } returns emptyList()
        // Default claim succeeds with no prior sync (CREATE branch).
        // Tests can override per-row to KilterClaim.Lost (slot busy) or
        // Won(syncedAt=non-null) (UPDATE branch).
        every { repo.claimKilterPublishSlot(any()) } returns KilterClaim.Won(previouslySyncedAtEpochSeconds = null)
    }

    // ── Early-return gating ─────────────────────────────────────────

    @Test
    fun returns_success_when_user_opted_out() = runTest {
        every { prefs.kilterClimbPublishEnabled } returns MutableStateFlow(false)
        val result = worker().doWork()
        assertTrue(result is ListenableWorker.Result.Success, "expected success-skip, got $result")
        coVerify(exactly = 0) { repo.getClimbsAwaitingKilterRetry(any()) }
    }

    @Test
    fun returns_success_when_no_kilter_token() = runTest {
        every { tokenStore.getAccessToken() } returns null
        val result = worker().doWork()
        assertTrue(result is ListenableWorker.Result.Success, "expected success-skip, got $result")
        coVerify(exactly = 0) { repo.getClimbsAwaitingKilterRetry(any()) }
    }

    @Test
    fun unresolvable_row_size_marks_that_row_failed_and_batch_continues() = runTest {
        // c1's size can't be resolved (no bounds row + no sizes synced for
        // its layout) → c1 alone is marked failed (stays queued for the
        // next tick); c2 still publishes. Pre-fix an unresolvable size
        // returned Result.retry() and stalled the WHOLE Kilter queue.
        every { repo.getClimbsAwaitingKilterRetry(any()) } returns listOf(row("c1"), row("c2"))
        every { repo.getProductSizeForClimbRender("c1") } returns null
        every { repo.getProductSizesForLayout(any()) } returns emptyList()
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.Success("c2")

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success,
            "expected success (per-row skip), got $result")
        coVerify(exactly = 1) {
            repo.markKilterPublishFailed("c1", match { it.contains("no product size") })
        }
        coVerify(exactly = 0) { apiClient.publishClimb(climbUuid = "c1", any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            repo.markKilterPublishSynced(uuid = "c2", via = "self", syncedAtEpochSeconds = any())
        }
    }

    @Test
    fun row_size_is_resolved_from_the_climbs_own_context_not_active_pref() = runTest {
        // A Homewall climb (layout 8) is queued while the active board is
        // something else entirely. The payload must carry the ROW's own
        // product size — uuid + edges of the Homewall size — not whatever
        // the active-board pref points at.
        val homewallSize = BoardSize(
            id = 25L, productId = 2L, name = "Homewall 10x12",
            edgeLeft = 4, edgeRight = 140, edgeBottom = 4, edgeTop = 152,
            imageFilename = null,
        )
        val homewallRow = row("hw1").copy(layoutId = 8L)
        every { repo.getClimbsAwaitingKilterRetry(any()) } returns listOf(homewallRow)
        every { repo.getProductSizeForClimbRender("hw1") } returns 25
        every { repo.getProductSize(25) } returns homewallSize
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.Success("hw1")

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success, "expected success, got $result")
        coVerify(exactly = 1) {
            apiClient.publishClimb(
                climbUuid = "hw1", name = any(), description = any(),
                framesClimbConcat = any(), productName = any(),
                productLayoutUuid = "25", angle = any(),
                edgeLeft = 4, edgeRight = 140, edgeBottom = 4, edgeTop = 152,
            )
        }
    }

    @Test
    fun returns_success_when_queue_is_empty() = runTest {
        val result = worker().doWork()
        assertTrue(result is ListenableWorker.Result.Success, "expected empty-queue success, got $result")
        coVerify(exactly = 0) { apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── Per-row outcomes ────────────────────────────────────────────

    @Test
    fun success_row_is_marked_synced() = runTest {
        every { repo.getClimbsAwaitingKilterRetry(any()) } returns listOf(row("c1"))
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
        every { repo.getClimbsAwaitingKilterRetry(any()) } returns listOf(row("c1"))
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
        // CAS-claim returns Won(non-null syncedAt) → UPDATE branch.
        // The row.kilterSyncedAt SELECT-snapshot is informational; the
        // worker now reads the authoritative value inside the same
        // transaction as the claim.
        every { repo.getClimbsAwaitingKilterRetry(any()) } returns listOf(row("c1", kilterSyncedAt = 100L))
        every { repo.claimKilterPublishSlot("c1") } returns KilterClaim.Won(previouslySyncedAtEpochSeconds = 100L)
        coEvery {
            apiClient.updateClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
        every { repo.getClimbsAwaitingKilterRetry(any()) } returns listOf(row("c1", kilterSyncedAt = null))
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.PermanentError("rejected", httpCode = 422)

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success, "expected success after permanent, got $result")
        // CREATE 4xx terminal → 'rejected' (drops out of retry queue).
        coVerify(exactly = 1) {
            repo.markKilterPublishRejected("c1", match { it.contains("http=422") })
        }
        coVerify(exactly = 0) { repo.markKilterPublishFailed(any(), any()) }
        coVerify(exactly = 0) { repo.markKilterPublishDiverged(any(), any()) }
    }

    @Test
    fun not_authenticated_mid_batch_aborts_with_success() = runTest {
        every { repo.getClimbsAwaitingKilterRetry(any()) } returns listOf(row("c1"), row("c2"))
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.NotAuthenticated

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success, "expected success-bail, got $result")
        // Bailed on first row — second row never attempted.
        coVerify(exactly = 1) { apiClient.publishClimb(climbUuid = "c1", any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { apiClient.publishClimb(climbUuid = "c2", any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun row_throw_is_caught_and_batch_continues() = runTest {
        every { repo.getClimbsAwaitingKilterRetry(any()) } returns listOf(row("c1"), row("c2"))
        coEvery {
            apiClient.publishClimb(climbUuid = "c1", any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("DB lock")
        coEvery {
            apiClient.publishClimb(climbUuid = "c2", any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
        every { repo.getClimbsAwaitingKilterRetry(any()) } returns listOf(row("c1"), row("c2"))
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.TransientError("oops")

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Retry, "expected retry when 100% transient, got $result")
    }

    @Test
    fun mixed_outcomes_return_success_not_retry() = runTest {
        // 1 transient + 1 permanent → not all-transient, so success.
        every { repo.getClimbsAwaitingKilterRetry(any()) } returns listOf(row("c1"), row("c2"))
        coEvery {
            apiClient.publishClimb(climbUuid = "c1", any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.TransientError("temp")
        coEvery {
            apiClient.publishClimb(climbUuid = "c2", any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.PermanentError("nope", httpCode = 422)

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success,
            "mixed outcomes shouldn't escalate to WorkManager retry, got $result")
    }

    // ── Pubkey scoping ──────────────────────────────────────────────────

    @Test
    fun returns_success_and_skips_query_when_no_pubkey_resolvable() = runTest {
        // Resolver returns null → signer not initialised yet
        // (pre-onboarding edge, key store genuinely empty): doWork must
        // succeed-skip without listing the queue, since no row could
        // possibly belong to a missing identity. The production
        // resolver wraps `getPublicKeyHex()` in runCatching and
        // collapses both blank and throwing branches to null, so this
        // single branch covers both failure modes from the worker's
        // perspective.
        activePubkeyResolver = ActivePubkeyResolver { null }
        val result = worker().doWork()
        assertTrue(result is ListenableWorker.Result.Success, "expected success-skip, got $result")
        coVerify(exactly = 0) { repo.getClimbsAwaitingKilterRetry(any()) }
    }

    @Test
    fun queue_is_filtered_by_active_pubkey() = runTest {
        // The active signer's pubkey must be threaded into the SQL
        // query so a backup-restore from another nsec or an identity-
        // switch on the same device cannot drain rows authored under
        // another identity onto the active Kilter account.
        activePubkeyResolver = ActivePubkeyResolver { "active-pk-abc" }
        every { repo.getClimbsAwaitingKilterRetry("active-pk-abc") } returns emptyList()

        worker().doWork()

        verify(exactly = 1) { repo.getClimbsAwaitingKilterRetry("active-pk-abc") }
    }
}
