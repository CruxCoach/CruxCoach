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
        // Honour the user's persistent-connection intent — `kilterSyncEnabled`
        // is the canonical "I want CruxCoach connected to my Kilter account"
        // signal. A leftover token from a failed `importLogs(oneTimeOnly=true)`
        // run (network flake before `tokenStore.clear()` ran) must not turn
        // into a silent auto-push channel.
        if (!userPreferences.kilterSyncEnabled.first()) {
            return Outcome.Skipped("not-persistently-connected")
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

        // Atomic CAS-claim of the publish slot — replaces the pre-fix
        // markKilterPublishPending + caller-supplied op. The CAS prevents
        // a race with KilterPublishRetryWorker (or a future second
        // trigger-path) where two flows would each read kilter_status
        // independently and both attempt the API call. The claim's
        // pre-state `previouslySyncedAtEpochSeconds` is the authoritative
        // CREATE-vs-UPDATE signal — caller's Op is now informational only.
        val claim = boardRepository.claimKilterPublishSlot(uuid)
        if (claim is com.cruxcoach.data.repository.KilterClaim.Lost) {
            Log.i(TAG, "$op via=self: claim lost (another flow holds slot for $uuid); skip")
            return Outcome.Skipped("slot-busy")
        }
        val isUpdate = (claim as com.cruxcoach.data.repository.KilterClaim.Won)
            .previouslySyncedAtEpochSeconds != null
        // Publish-time angle: editor's current angle (always set by the
        // editor's seedAngle init from prefs), with a 40° fallback for
        // the rare null-state case so we never send a missing required
        // field.
        val publishAngle = state.angle ?: 40
        // productLayoutUuid is Kilter's API identifier of the (product,
        // size) variant — empirically equal to our productSizeId stringified
        // for the layouts we ship (Kilter Original sizes 7/8/10/14/27/28,
        // Homewall sizes 17/18/19/21..29). Stored as a numeric string in
        // the API.
        val productLayoutUuid = boardSize.id.toString()
        val r = if (isUpdate) {
            apiClient.updateClimb(
                climbUuid = uuid,
                name = state.name,
                description = state.description,
                framesClimbConcat = framesClimbConcat,
                productName = productNameFor(layoutId),
                productLayoutUuid = productLayoutUuid,
                angle = publishAngle,
                edgeLeft = boardSize.edgeLeft.toInt(),
                edgeRight = boardSize.edgeRight.toInt(),
                edgeBottom = boardSize.edgeBottom.toInt(),
                edgeTop = boardSize.edgeTop.toInt(),
            )
        } else {
            apiClient.publishClimb(
                climbUuid = uuid,
                name = state.name,
                description = state.description,
                framesClimbConcat = framesClimbConcat,
                productName = productNameFor(layoutId),
                productLayoutUuid = productLayoutUuid,
                angle = publishAngle,
                edgeLeft = boardSize.edgeLeft.toInt(),
                edgeRight = boardSize.edgeRight.toInt(),
                edgeBottom = boardSize.edgeBottom.toInt(),
                edgeTop = boardSize.edgeTop.toInt(),
            )
        }
        // Wrap the post-API mark sequence in try/catch: a SQLite throw
        // here (lock contention, disk-full, mid-migration) used to leave
        // the row stuck in 'pending' forever — the retry worker's
        // queue-criterion only matches NULL/'failed', so a 'pending'
        // row was a permanent stranding. Catch + best-effort downgrade
        // to 'failed' so the next retry tick re-attempts (or surfaces
        // the failure cleanly).
        val opEnum = if (isUpdate) com.cruxcoach.data.repository.KilterPublishOp.UPDATE
                     else com.cruxcoach.data.repository.KilterPublishOp.CREATE
        val attemptedAt = System.currentTimeMillis()
        return try {
            when (r) {
                is KilterPublishResult.Success -> {
                    boardRepository.markKilterPublishSynced(
                        uuid = uuid,
                        via = "self",
                        syncedAtEpochSeconds = attemptedAt / 1000,
                    )
                    recordAttempt(uuid, attemptedAt, opEnum,
                        com.cruxcoach.data.repository.KilterPublishOutcomeKind.SUCCESS, null, null)
                    Outcome.Synced
                }
                is KilterPublishResult.NotAuthenticated -> {
                    Log.i(TAG, "$op via=self: token expired mid-call; deferring to retry worker")
                    boardRepository.markKilterPublishFailed(uuid, "token expired")
                    recordAttempt(uuid, attemptedAt, opEnum,
                        com.cruxcoach.data.repository.KilterPublishOutcomeKind.AUTH, null, "token expired")
                    Outcome.Failed(appContext.getString(R.string.kilter_publish_session_expired))
                }
                is KilterPublishResult.TransientError -> {
                    boardRepository.markKilterPublishFailed(uuid, "transient: ${r.message}")
                    recordAttempt(uuid, attemptedAt, opEnum,
                        com.cruxcoach.data.repository.KilterPublishOutcomeKind.TRANSIENT, null, r.message)
                    Outcome.Failed(appContext.getString(R.string.kilter_publish_transient))
                }
                is KilterPublishResult.RateLimited -> {
                    // Same UI surface as transient — the retry worker handles
                    // the actual backoff. mark "rate-limited:" so logcat /
                    // kilter_error column distinguishes it.
                    boardRepository.markKilterPublishFailed(uuid, "rate-limited: ${r.message}")
                    recordAttempt(uuid, attemptedAt, opEnum,
                        com.cruxcoach.data.repository.KilterPublishOutcomeKind.RATE_LIMITED, 429, r.message)
                    Outcome.Failed(appContext.getString(R.string.kilter_publish_transient))
                }
                is KilterPublishResult.PermanentError -> {
                    // For UPDATE on an already-published row a 4xx most likely
                    // means Kilter refuses edits at this layer. Mark
                    // 'diverged' (no further retries, banner in UI). For
                    // CREATE a 4xx is a terminal 'rejected' — payload-level
                    // refusal that no amount of retry will fix. The branch
                    // is keyed off the CAS-claim's pre-state, not the
                    // caller's Op argument (the Op is informational now;
                    // see the claim comment above).
                    if (isUpdate) {
                        boardRepository.markKilterPublishDiverged(
                            uuid,
                            "http=${r.httpCode}: ${r.message.take(200)}",
                        )
                        recordAttempt(uuid, attemptedAt, opEnum,
                            com.cruxcoach.data.repository.KilterPublishOutcomeKind.PERMANENT,
                            r.httpCode, r.message)
                        Outcome.Diverged(
                            appContext.getString(R.string.kilter_publish_diverged_with_code, r.httpCode),
                        )
                    } else {
                        boardRepository.markKilterPublishRejected(
                            uuid,
                            "http=${r.httpCode}: ${r.message.take(200)}",
                        )
                        recordAttempt(uuid, attemptedAt, opEnum,
                            com.cruxcoach.data.repository.KilterPublishOutcomeKind.PERMANENT,
                            r.httpCode, r.message)
                        Outcome.Failed(
                            appContext.getString(R.string.kilter_publish_rejected_with_code, r.httpCode),
                        )
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$op via=self: post-API mark threw for uuid=$uuid; downgrading to 'failed'", e)
            // Best-effort: try to leave the row in a state the retry
            // worker can pick up. If even this throws there's nothing
            // more we can do — the row stays in 'pending' but it's
            // logged for triage.
            runCatching {
                boardRepository.markKilterPublishFailed(
                    uuid,
                    "post-api mark threw: ${e.message?.take(200) ?: e::class.simpleName}",
                )
            }.onFailure { Log.w(TAG, "downgrade-to-failed also threw for uuid=$uuid", it) }
            Outcome.Failed(appContext.getString(R.string.kilter_publish_transient))
        }
    }

    /**
     * layout_id → Kilter product_name mapping. The Kilter API resolves
     * placement IDs against the product named here — sending "Original"
     * for a Homewall climb means the API can't find any of the climb's
     * placement IDs in the product's set table and returns 500. Names
     * match the strings AuroraImporter.KILTER_LAYOUT_NAMES uses on the
     * import side (1 = Original, 8 = Homewall); other layouts default
     * to Original and let the server reject (we don't ship support for
     * any third Kilter product yet).
     */
    private fun productNameFor(layoutId: Long): String = when (layoutId) {
        com.cruxcoach.android.data.BoardConstants.KILTER_HOMEWALL_LAYOUT.toLong() -> "Kilter Board Homewall"
        else -> "Kilter Board Original"
    }

    /** Best-effort audit-trail write — wrapped in runCatching so a SQLite
     *  hiccup at this point doesn't tip the publisher's main result. The
     *  worst case is a missing row in the history; the live status
     *  columns are still authoritative. */
    private fun recordAttempt(
        uuid: String,
        attemptedAtMs: Long,
        op: com.cruxcoach.data.repository.KilterPublishOp,
        outcome: com.cruxcoach.data.repository.KilterPublishOutcomeKind,
        httpCode: Int?,
        errorExcerpt: String?,
    ) {
        runCatching {
            boardRepository.recordKilterPublishAttempt(
                climbUuid = uuid,
                attemptedAtMs = attemptedAtMs,
                op = op,
                via = "self",
                outcome = outcome,
                httpCode = httpCode,
                errorExcerpt = errorExcerpt?.take(200),
            )
        }.onFailure { Log.w(TAG, "recordKilterPublishAttempt threw for uuid=$uuid", it) }
    }

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
