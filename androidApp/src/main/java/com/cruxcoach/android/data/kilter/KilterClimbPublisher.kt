package com.cruxcoach.android.data.kilter

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.community.ClimbEditorState
import com.vitorpamplona.quartz.nip01Core.core.Event
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Orchestrates the Kilter-side publish for CruxCoach-authored climbs.
 *
 * Decides between three paths in order:
 *
 * 1. **Self** — user is logged into Kilter → POST directly via
 *    [KilterApiClient.publishClimb]. Kilter `setter_uuid` is the user's
 *    own UUID, so attribution is clean.
 * 2. **Bundled** — user not logged in BUT enabled the bundled fallback
 *    in settings → POST to [CruxCoachBundledKilterClient]. Kilter's
 *    `setter_uuid` is the CruxCoach service account; the user's npub
 *    is preserved in the description.
 * 3. **Skip** — neither path is available (user opted out, or no Kilter
 *    account + bundled fallback off). The climb stays Nostr-only;
 *    `kilter_status` stays NULL.
 *
 * Failures don't propagate up to the publisher — we mark the row with
 * `kilter_status='failed'` and `kilter_error=<reason>` so the retry
 * worker (later FEAT) can pick them up. The Nostr publish has already
 * succeeded by the time this runs, so the user sees a published climb;
 * the Kilter half is best-effort.
 */
@Singleton
class KilterClimbPublisher @Inject constructor(
    private val apiClient: KilterApiClient,
    private val tokenStore: KilterTokenStore,
    private val bundledClient: CruxCoachBundledKilterClient,
    private val userPreferences: UserPreferences,
    private val boardRepository: BoardRepository,
) {
    /**
     * Publish (or attempt to publish) a CruxCoach-authored climb to Kilter.
     *
     * - `nostrEvent` is the already-signed Kind 30078 event for the climb.
     *   Used by the bundled path (the service verifies the signature) and
     *   gives us a stable handle even if local state mutates.
     * - `boardSize` is the active board's edge metadata. Required by Kilter's
     *   create-climb payload; we don't ship without it.
     *
     * Returns the chosen path's outcome — useful for tests and for
     * surfacing a Snackbar to the user. Side effects on the climbs row
     * are already applied by the time this returns.
     */
    suspend fun publish(
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        sizeLabel: String,
        boardSize: BoardSize?,
        nostrEvent: Event,
        framesClimbConcat: String,
    ): Outcome {
        if (!userPreferences.kilterClimbPublishEnabled.first()) {
            // User explicitly opted out — leave kilter_status NULL.
            return Outcome.Skipped(reason = "user-opted-out")
        }
        if (boardSize == null) {
            return Outcome.Skipped(reason = "no-board-size")
        }

        boardRepository.markKilterPublishPending(uuid)
        val productName = productNameFor(layoutId)

        // Path 1: user has a Kilter account → publish via their account.
        val hasOwnToken = tokenStore.getAccessToken() != null &&
            tokenStore.getUserUuid()?.isNotBlank() == true
        if (hasOwnToken) {
            val r = apiClient.publishClimb(
                climbUuid = uuid,
                name = state.name,
                description = state.description,
                framesClimbConcat = framesClimbConcat,
                productName = productName,
                edgeLeft = boardSize.edgeLeft.toInt(),
                edgeRight = boardSize.edgeRight.toInt(),
                edgeBottom = boardSize.edgeBottom.toInt(),
                edgeTop = boardSize.edgeTop.toInt(),
            )
            recordOutcome(uuid, via = "self", result = r)?.let { return it }
            // PermanentError or NotAuthenticated falls through to bundled
            // (the user's token might've expired and the bundled path is
            // a sensible fallback if they enabled it).
        }

        // Path 2: bundled fallback (only if user opted in).
        if (userPreferences.kilterBundledFallbackEnabled.first()) {
            val r = bundledClient.publishClimb(
                signedEvent = nostrEvent,
                layoutId = layoutId,
                sizeLabel = sizeLabel,
                edgeLeft = boardSize.edgeLeft.toInt(),
                edgeRight = boardSize.edgeRight.toInt(),
                edgeBottom = boardSize.edgeBottom.toInt(),
                edgeTop = boardSize.edgeTop.toInt(),
            )
            recordOutcome(uuid, via = "cruxcoach", result = r)?.let { return it }
        }

        // Both paths exhausted — neither succeeded.
        boardRepository.markKilterPublishFailed(uuid, "no-kilter-path-available")
        return Outcome.Failed("Nicht angemeldet bei Kilter und Bundled-Pfad deaktiviert")
    }

    /** Translate a [KilterPublishResult] into status-flag updates + an [Outcome].
     *  Returns null only when the result was non-success and the orchestrator
     *  should consider the next path. */
    private fun recordOutcome(
        uuid: String,
        via: String,
        result: KilterPublishResult,
    ): Outcome? = when (result) {
        is KilterPublishResult.Success -> {
            boardRepository.markKilterPublishSynced(
                uuid = uuid,
                via = via,
                syncedAtEpochSeconds = System.currentTimeMillis() / 1000,
            )
            Outcome.Synced(via = via)
        }
        is KilterPublishResult.NotAuthenticated -> {
            // Don't persist a "failed" status here — the caller may have a
            // fallback path. If it doesn't, the catch-all at the end of
            // publish() flips the row to failed.
            Log.i(TAG, "Kilter publish via=$via not authenticated; trying next path")
            null
        }
        is KilterPublishResult.TransientError -> {
            boardRepository.markKilterPublishFailed(uuid, "via=$via transient: ${result.message}")
            Outcome.Failed("Übertragung fehlgeschlagen — Versuch wird wiederholt")
        }
        is KilterPublishResult.PermanentError -> {
            // Server-side rejection — don't fall through to bundled (same
            // payload would just get rejected again). Mark + return.
            boardRepository.markKilterPublishFailed(
                uuid,
                "via=$via http=${result.httpCode}: ${result.message.take(200)}",
            )
            Outcome.Failed("Kilter hat den Climb abgelehnt (${result.httpCode})")
        }
    }

    /**
     * Best-effort layout_id → Kilter product_name mapping.
     *
     * Most CruxCoach users are on the Kilter Original (the only Kilter
     * board with a meaningful community DB). Other layouts fall through
     * to the same default — Kilter's server-side validates `product_name`
     * against the products table, and rejecting at that layer is cheaper
     * than maintaining a full mapping client-side.
     *
     * Future: pull product_name from the synced products bucket once we
     * mirror it locally. For v1, the constant is sufficient.
     */
    private fun productNameFor(layoutId: Long): String = "Kilter Board Original"

    sealed class Outcome {
        /** Kilter accepted the climb via the named path ('self' or 'cruxcoach'). */
        data class Synced(val via: String) : Outcome()
        /** Both paths failed; row marked `kilter_status='failed'`. */
        data class Failed(val message: String) : Outcome()
        /** Path not attempted (user opted out, or required state missing). */
        data class Skipped(val reason: String) : Outcome()
    }

    private companion object {
        const val TAG = "KilterClimbPublisher"
    }
}
