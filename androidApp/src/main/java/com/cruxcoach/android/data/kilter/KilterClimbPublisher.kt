package com.cruxcoach.android.data.kilter

import android.content.Context
import android.util.Log
import com.cruxcoach.android.R
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.community.ClimbEditorState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Posts CruxCoach-authored climbs to Kilter's official server DB via the
 * user's own account. Each post uses the same `climb_uuid` that the
 * Nostr Kind-30078 event already carries, so when the daily Kilter-API
 * harvest re-pulls the climb our Blossom blob deduplicates by uuid.
 *
 * Failures are persisted to `kilter_status` / `kilter_error` and picked
 * up by [KilterPublishRetryWorker] every 6 hours. The user-visible
 * publish flow doesn't fail on Kilter errors — Nostr is the source of
 * truth and was already accepted by the time this runs.
 *
 * Self-account-only by design: when the user has no Kilter login,
 * climbs stay Nostr/Blossom-only. There's no shared CruxCoach service
 * account doing it on their behalf — that would be the textbook
 * Kilter-anti-abuse trigger and a Trademark/ToS minefield. Users who
 * want their climbs in the Kilter app connect their own account.
 */
@Singleton
class KilterClimbPublisher @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val apiClient: KilterApiClient,
    private val tokenStore: KilterTokenStore,
    private val userPreferences: UserPreferences,
    private val boardRepository: BoardRepository,
) {
    /**
     * Publish to Kilter via the user's own account. Returns an [Outcome]
     * for tests / Snackbar hints; side effects on the climbs row are
     * already applied by the time this returns.
     */
    suspend fun publish(
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        boardSize: BoardSize?,
        framesClimbConcat: String,
    ): Outcome = submit(
        op = Op.CREATE,
        uuid = uuid,
        layoutId = layoutId,
        state = state,
        boardSize = boardSize,
        framesClimbConcat = framesClimbConcat,
    )

    /**
     * Update an already-Kilter-synced climb after a CruxCoach edit.
     * Calls `update-climb/transaction` instead of `create-climb/`. If
     * Kilter's UI semantics rule out updates on published rows the
     * server returns 4xx; we map that to `kilter_status='diverged'` so
     * the retry worker drops the row and the UI surfaces a banner.
     */
    suspend fun update(
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        boardSize: BoardSize?,
        framesClimbConcat: String,
    ): Outcome = submit(
        op = Op.UPDATE,
        uuid = uuid,
        layoutId = layoutId,
        state = state,
        boardSize = boardSize,
        framesClimbConcat = framesClimbConcat,
    )

    private enum class Op { CREATE, UPDATE }

    private suspend fun submit(
        op: Op,
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        boardSize: BoardSize?,
        framesClimbConcat: String,
    ): Outcome {
        if (!userPreferences.kilterClimbPublishEnabled.first()) {
            return Outcome.Skipped("user-opted-out")
        }
        if (boardSize == null) {
            return Outcome.Skipped("no-board-size")
        }
        val hasOwnToken = tokenStore.getAccessToken() != null &&
            tokenStore.getUserUuid()?.isNotBlank() == true
        if (!hasOwnToken) {
            // No login → stay Nostr-only. Caller decides whether to
            // surface a "connect Kilter to mirror" hint in the UI.
            return Outcome.Skipped("no-kilter-login")
        }

        boardRepository.markKilterPublishPending(uuid)
        val r = when (op) {
            Op.CREATE -> apiClient.publishClimb(
                climbUuid = uuid,
                name = state.name,
                description = state.description,
                framesClimbConcat = framesClimbConcat,
                productName = productNameFor(layoutId),
                edgeLeft = boardSize.edgeLeft.toInt(),
                edgeRight = boardSize.edgeRight.toInt(),
                edgeBottom = boardSize.edgeBottom.toInt(),
                edgeTop = boardSize.edgeTop.toInt(),
            )
            Op.UPDATE -> apiClient.updateClimb(
                climbUuid = uuid,
                name = state.name,
                description = state.description,
                framesClimbConcat = framesClimbConcat,
                productName = productNameFor(layoutId),
                edgeLeft = boardSize.edgeLeft.toInt(),
                edgeRight = boardSize.edgeRight.toInt(),
                edgeBottom = boardSize.edgeBottom.toInt(),
                edgeTop = boardSize.edgeTop.toInt(),
            )
        }
        return when (r) {
            is KilterPublishResult.Success -> {
                boardRepository.markKilterPublishSynced(
                    uuid = uuid,
                    via = "self",
                    syncedAtEpochSeconds = System.currentTimeMillis() / 1000,
                )
                Outcome.Synced
            }
            is KilterPublishResult.NotAuthenticated -> {
                Log.i(TAG, "$op via=self: token expired mid-call; deferring to retry worker")
                boardRepository.markKilterPublishFailed(uuid, "token expired")
                Outcome.Failed(appContext.getString(R.string.kilter_publish_session_expired))
            }
            is KilterPublishResult.TransientError -> {
                boardRepository.markKilterPublishFailed(uuid, "transient: ${r.message}")
                Outcome.Failed(appContext.getString(R.string.kilter_publish_transient))
            }
            is KilterPublishResult.PermanentError -> {
                // For UPDATE on an already-published row a 4xx most likely
                // means Kilter refuses edits at this layer. Mark
                // 'diverged' (no further retries, banner in UI) instead
                // of 'failed' (which the worker keeps poking).
                if (op == Op.UPDATE) {
                    boardRepository.markKilterPublishDiverged(
                        uuid,
                        "http=${r.httpCode}: ${r.message.take(200)}",
                    )
                    Outcome.Diverged(
                        appContext.getString(R.string.kilter_publish_diverged_with_code, r.httpCode),
                    )
                } else {
                    boardRepository.markKilterPublishFailed(
                        uuid,
                        "http=${r.httpCode}: ${r.message.take(200)}",
                    )
                    Outcome.Failed(
                        appContext.getString(R.string.kilter_publish_rejected_with_code, r.httpCode),
                    )
                }
            }
        }
    }

    /**
     * Best-effort layout_id → Kilter product_name mapping. Most CruxCoach
     * users are on the Kilter Original; other layouts fall through to
     * the same default and let Kilter's server-side validate.
     */
    private fun productNameFor(layoutId: Long): String = "Kilter Board Original"

    sealed class Outcome {
        /** Kilter accepted the climb. */
        data object Synced : Outcome()
        /** Self-publish failed; row marked `kilter_status='failed'`. */
        data class Failed(val message: String) : Outcome()
        /** Update was rejected by Kilter for semantic reasons (cannot
         *  edit published row). Row marked `kilter_status='diverged'`,
         *  retry worker won't poke it again. */
        data class Diverged(val message: String) : Outcome()
        /** Path not attempted (user opted out, no board size, no login). */
        data class Skipped(val reason: String) : Outcome()
    }

    private companion object {
        const val TAG = "KilterClimbPublisher"
    }
}
