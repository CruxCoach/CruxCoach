package com.cruxcoach.android.data.kilter

import android.content.Context
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.KilterClaim
import com.cruxcoach.domain.community.ClimbEditorState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [KilterClimbPublisher] — the orchestration layer between
 * KilterApiClient (HTTP) and BoardRepository (DB markers). Covers the
 * five Outcome branches:
 *  - Skipped("user-opted-out")
 *  - Skipped("no-board-size")
 *  - Skipped("no-kilter-login")
 *  - Synced (Success path → markKilterPublishSynced)
 *  - Failed (NotAuthenticated / TransientError / PermanentError on CREATE)
 *  - Diverged (PermanentError on UPDATE)
 *
 * No real HTTP — KilterApiClient is mocked. Strings are resolved through
 * the injected Context; mockk's relaxed mode returns "" by default which
 * is enough for branch identification.
 */
class KilterClimbPublisherTest {

    private val uuid = "abc12345"
    private val layoutId = 1L
    private val state = ClimbEditorState(
        selectedHolds = mapOf(1164 to 12, 1233 to 13, 1392 to 14),
        name = "Test Climb",
        description = "x",
        setterGradeId = 22,
        angle = 40,
    )
    private val boardSize = BoardSize(
        id = 1L, productId = 1L, name = "12x12",
        edgeLeft = 1, edgeRight = 144, edgeBottom = 1, edgeTop = 156,
        imageFilename = null,
    )
    private val framesConcat = "p1164r12p1233r15p1392r14"

    private lateinit var apiClient: KilterApiClient
    private lateinit var tokenStore: KilterTokenStore
    private lateinit var prefs: UserPreferences
    private lateinit var repo: BoardRepository
    private lateinit var ctx: Context
    private lateinit var publisher: KilterClimbPublisher

    @Before
    fun setup() {
        apiClient = mockk(relaxed = true)
        tokenStore = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        repo = mockk(relaxed = true)
        ctx = mockk(relaxed = true)
        every { ctx.getString(any()) } returns ""
        every { ctx.getString(any(), *anyVararg()) } returns ""
        every { prefs.kilterClimbPublishEnabled } returns MutableStateFlow(true)
        every { prefs.kilterSyncEnabled } returns MutableStateFlow(true)
        every { tokenStore.getAccessToken() } returns "valid-token"
        every { tokenStore.getUserUuid() } returns "user-uuid"
        // Default: claim succeeds with no prior sync (CREATE branch).
        // Tests can override to KilterClaim.Lost for slot-busy path or
        // to Won(syncedAtEpochSeconds) for the UPDATE branch.
        every { repo.claimKilterPublishSlot(any()) } returns KilterClaim.Won(previouslySyncedAtEpochSeconds = null)
        publisher = KilterClimbPublisher(ctx, apiClient, tokenStore, prefs, repo)
    }

    // ── Skipped paths ───────────────────────────────────────────────

    @Test
    fun publish_returns_skipped_when_user_disabled_publish() = runTest {
        every { prefs.kilterClimbPublishEnabled } returns MutableStateFlow(false)
        val outcome = publisher.publish(uuid, layoutId, state, boardSize, framesConcat)
        assertEquals(KilterClimbPublisher.Outcome.Skipped("user-opted-out"), outcome)
        coVerify(exactly = 0) { apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        // Slot-claim should not happen either when user opted out.
        coVerify(exactly = 0) { repo.claimKilterPublishSlot(any()) }
    }

    @Test
    fun publish_returns_skipped_when_slot_claim_lost() = runTest {
        every { repo.claimKilterPublishSlot(uuid) } returns KilterClaim.Lost
        val outcome = publisher.publish(uuid, layoutId, state, boardSize, framesConcat)
        assertEquals(KilterClimbPublisher.Outcome.Skipped("slot-busy"), outcome)
        // No API call when another flow holds the slot.
        coVerify(exactly = 0) { apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { apiClient.updateClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun publish_returns_skipped_when_board_size_unknown() = runTest {
        val outcome = publisher.publish(uuid, layoutId, state, boardSize = null, framesConcat)
        assertEquals(KilterClimbPublisher.Outcome.Skipped("no-board-size"), outcome)
        coVerify(exactly = 0) { apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun publish_returns_skipped_when_no_access_token() = runTest {
        every { tokenStore.getAccessToken() } returns null
        val outcome = publisher.publish(uuid, layoutId, state, boardSize, framesConcat)
        assertEquals(KilterClimbPublisher.Outcome.Skipped("no-kilter-login"), outcome)
        coVerify(exactly = 0) { apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun publish_returns_skipped_when_user_uuid_blank() = runTest {
        every { tokenStore.getUserUuid() } returns ""
        val outcome = publisher.publish(uuid, layoutId, state, boardSize, framesConcat)
        assertEquals(KilterClimbPublisher.Outcome.Skipped("no-kilter-login"), outcome)
    }

    // ── Synced path ─────────────────────────────────────────────────

    @Test
    fun publish_marks_synced_on_success() = runTest {
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.Success(uuid)

        val outcome = publisher.publish(uuid, layoutId, state, boardSize, framesConcat)

        assertEquals(KilterClimbPublisher.Outcome.Synced, outcome)
        // CAS-claim transitions the slot to 'pending'; markKilterPublishSynced
        // closes it. Pre-fix the publisher called markKilterPublishPending
        // explicitly — it's now subsumed by claimKilterPublishSlot.
        coVerify(exactly = 1) { repo.claimKilterPublishSlot(uuid) }
        coVerify(exactly = 1) {
            repo.markKilterPublishSynced(
                uuid = uuid, via = "self", syncedAtEpochSeconds = any(),
            )
        }
    }

    // ── Failed paths ────────────────────────────────────────────────

    @Test
    fun publish_returns_failed_when_token_expired_mid_call() = runTest {
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.NotAuthenticated

        val outcome = publisher.publish(uuid, layoutId, state, boardSize, framesConcat)

        assertTrue(outcome is KilterClimbPublisher.Outcome.Failed,
            "expected Failed, got $outcome")
        coVerify(exactly = 1) { repo.markKilterPublishFailed(uuid, "token expired") }
    }

    @Test
    fun publish_returns_failed_on_transient_error() = runTest {
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.TransientError("network glitch")

        val outcome = publisher.publish(uuid, layoutId, state, boardSize, framesConcat)

        assertTrue(outcome is KilterClimbPublisher.Outcome.Failed,
            "expected Failed, got $outcome")
        coVerify(exactly = 1) {
            repo.markKilterPublishFailed(uuid, match { it.startsWith("transient: ") })
        }
    }

    @Test
    fun publish_returns_failed_on_permanent_error_for_create() = runTest {
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.PermanentError("nope", httpCode = 422)

        val outcome = publisher.publish(uuid, layoutId, state, boardSize, framesConcat)

        assertTrue(outcome is KilterClimbPublisher.Outcome.Failed,
            "expected Failed (CREATE 4xx), got $outcome")
        // CREATE 4xx → 'rejected' terminal state (not 'failed', which
        // would keep the row in the retry queue forever).
        coVerify(exactly = 1) {
            repo.markKilterPublishRejected(uuid, match { it.contains("http=422") })
        }
        coVerify(exactly = 0) { repo.markKilterPublishFailed(any(), any()) }
        coVerify(exactly = 0) { repo.markKilterPublishDiverged(any(), any()) }
    }

    // ── Diverged path (UPDATE 4xx) ──────────────────────────────────
    // The UPDATE/CREATE branch is now driven by KilterClaim.Won
    // .previouslySyncedAtEpochSeconds (non-null = previously Kilter-synced
    // → use update-climb), not by which entry-point publisher.publish vs
    // publisher.update the caller invoked.

    @Test
    fun update_returns_diverged_on_permanent_error() = runTest {
        every { repo.claimKilterPublishSlot(uuid) } returns KilterClaim.Won(previouslySyncedAtEpochSeconds = 100L)
        coEvery {
            apiClient.updateClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.PermanentError("cannot edit", httpCode = 409)

        val outcome = publisher.update(uuid, layoutId, state, boardSize, framesConcat)

        assertTrue(outcome is KilterClimbPublisher.Outcome.Diverged,
            "expected Diverged for UPDATE 4xx, got $outcome")
        coVerify(exactly = 1) {
            repo.markKilterPublishDiverged(uuid, match { it.contains("http=409") })
        }
        coVerify(exactly = 0) { repo.markKilterPublishFailed(any(), any()) }
    }

    @Test
    fun update_marks_synced_on_success() = runTest {
        every { repo.claimKilterPublishSlot(uuid) } returns KilterClaim.Won(previouslySyncedAtEpochSeconds = 100L)
        coEvery {
            apiClient.updateClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.Success(uuid)

        val outcome = publisher.update(uuid, layoutId, state, boardSize, framesConcat)

        assertEquals(KilterClimbPublisher.Outcome.Synced, outcome)
        coVerify(exactly = 1) {
            repo.markKilterPublishSynced(uuid = uuid, via = "self", syncedAtEpochSeconds = any())
        }
    }

    @Test
    fun no_prior_sync_uses_create_endpoint() = runTest {
        // Default claim returns Won(null) → CREATE branch.
        coEvery {
            apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.Success(uuid)

        publisher.publish(uuid, layoutId, state, boardSize, framesConcat)

        coVerify(exactly = 1) { apiClient.publishClimb(climbUuid = uuid, name = any(), description = any(), framesClimbConcat = any(), productName = any(), productLayoutUuid = any(), angle = any(), edgeLeft = any(), edgeRight = any(), edgeBottom = any(), edgeTop = any()) }
        coVerify(exactly = 0) { apiClient.updateClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun previously_synced_uses_update_endpoint_even_via_publish_entrypoint() = runTest {
        // Claim says "this uuid was synced before" → UPDATE branch fires
        // even though the call entered via publisher.publish() (not
        // publisher.update()). Caller-supplied entrypoint is informational.
        every { repo.claimKilterPublishSlot(uuid) } returns KilterClaim.Won(previouslySyncedAtEpochSeconds = 999L)
        coEvery {
            apiClient.updateClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns KilterPublishResult.Success(uuid)

        publisher.publish(uuid, layoutId, state, boardSize, framesConcat)

        coVerify(exactly = 1) { apiClient.updateClimb(climbUuid = uuid, name = any(), description = any(), framesClimbConcat = any(), productName = any(), productLayoutUuid = any(), angle = any(), edgeLeft = any(), edgeRight = any(), edgeBottom = any(), edgeTop = any()) }
        coVerify(exactly = 0) { apiClient.publishClimb(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
