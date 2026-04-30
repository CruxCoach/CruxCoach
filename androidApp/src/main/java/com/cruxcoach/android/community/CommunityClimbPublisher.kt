package com.cruxcoach.android.community

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.kilter.KilterClimbPublisher
import com.cruxcoach.android.data.kilter.KilterTokenStore
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.community.ClimbBounds
import com.cruxcoach.domain.community.ClimbEditorState
import com.cruxcoach.domain.community.buildCommunityClimbEvent
import com.cruxcoach.domain.community.encodeFrames
import com.vitorpamplona.quartz.nip01Core.core.Event
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val TAG = "ClimbPublisher"
private const val KIND_REPLACEABLE_PARAMETERIZED = 30078

/**
 * Publishes a CruxCoach-authored climb. Two destinations, both signed
 * with the user's own keys:
 *
 *  1. Nostr Kind-30078 event (mandatory — source of truth for the
 *     CruxCoach community DB)
 *  2. Kilter's official server DB (best-effort, via the user's own
 *     Kilter account; skipped silently if not connected)
 *
 * No shared "CruxCoach service" identity — neither for Nostr signing
 * nor for Kilter pushes. Anonymity, multi-account aggregation and the
 * abuse-vector + ToS minefield they'd open are out of scope. Users who
 * want their climbs visible in the Kilter app connect their own
 * Kilter account.
 *
 * Returns an enriched [Result] so the UI can surface "connect Kilter"
 * hints when the user is publishing without a Kilter connection.
 */
@Singleton
class CommunityClimbPublisher @Inject constructor(
    private val nostrSigner: NostrSigner,
    private val pool: NostrRelayPool,
    private val boardRepository: BoardRepository,
    private val kilterPublisher: KilterClimbPublisher,
    private val kilterTokenStore: KilterTokenStore,
    private val userPreferences: UserPreferences,
) {
    /** Outcome of the overall publish. Kilter side is captured here so
     *  callers can decide whether to nudge the user about connecting. */
    data class Result(
        val nostrEventId: String,
        /**
         * `true` when the user isn't connected to Kilter and would have
         * benefitted from being so — i.e. the editor's "publish to
         * Kilter" setting is on but no token is present. Used by the UI
         * to show the "connect Kilter to also publish there" Snackbar.
         */
        val nudgeToConnectKilter: Boolean,
    )

    suspend fun publish(
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        sizeLabel: String,
        isEdit: Boolean = false,
    ): Result {
        val pubkey = nostrSigner.getPublicKeyHex()
        val createdAt = System.currentTimeMillis() / 1000

        // Step 1: Nostr — mandatory. Failure throws; the user can retry
        // from the editor.
        val bounds = computeBounds(state)
        val payload = buildCommunityClimbEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            uuid = uuid,
            layoutId = layoutId,
            sizeLabel = sizeLabel,
            state = state,
            bounds = bounds,
        )
        val tags: Array<Array<String>> = payload.tags.map { it.toTypedArray() }.toTypedArray()
        val event = nostrSigner.signer.sign<Event>(
            createdAt = payload.createdAt,
            kind = KIND_REPLACEABLE_PARAMETERIZED,
            tags = tags,
            content = payload.content,
        )
        val (attempted, accepted) = pool.sendEventWithStats(event)
        Log.i(TAG, "publish uuid=$uuid d=${payload.dTag} attempted=$attempted accepted=$accepted")
        if (accepted == 0) {
            boardRepository.markClimbPublishFailed(uuid)
            throw IllegalStateException("No relay accepted the community-climb event (attempted=$attempted)")
        }
        boardRepository.markClimbPublishedNostr(
            uuid = uuid,
            nostrEventId = event.id,
            nostrDTag = payload.dTag,
            pubkey = pubkey,
        )

        // Step 2: Kilter — best-effort via the user's own account.
        // Detect "connected but publishing the climb still went only to
        // Nostr" so the UI can show the connect-Kilter hint. If the user
        // has explicitly disabled Kilter publishing in settings, we don't
        // nudge.
        val publishToKilter = userPreferences.kilterClimbPublishEnabled.first()
        val hasKilterToken = kilterTokenStore.getAccessToken() != null
        var nudgeToConnect = false

        if (publishToKilter) {
            val boardSize = activeBoardSize()
            val framesClimbConcat = BoardClimbParser.encodeClimbConcat(
                state.selectedHolds.entries
                    .sortedBy { it.key }
                    .map { com.cruxcoach.domain.board.BoardHold(it.key, it.value) }
            )
            runCatching {
                // Edit branches into update-climb/transaction. We only
                // route to update when the row was previously synced —
                // an edit on a row that never made it to Kilter (e.g.
                // user wasn't connected before) is functionally a fresh
                // create, and create-climb/transaction is idempotent on
                // climb_uuid anyway.
                val priorKilterStatus = boardRepository.getKilterPublishState(uuid)?.status
                val outcome = if (isEdit && priorKilterStatus == "synced") {
                    kilterPublisher.update(
                        uuid = uuid,
                        layoutId = layoutId,
                        state = state,
                        boardSize = boardSize,
                        framesClimbConcat = framesClimbConcat,
                    )
                } else {
                    kilterPublisher.publish(
                        uuid = uuid,
                        layoutId = layoutId,
                        state = state,
                        boardSize = boardSize,
                        framesClimbConcat = framesClimbConcat,
                    )
                }
                if (outcome is KilterClimbPublisher.Outcome.Skipped &&
                    outcome.reason == "no-kilter-login" &&
                    !hasKilterToken
                ) {
                    nudgeToConnect = true
                }
            }.onFailure { Log.w(TAG, "kilter orchestration threw — recoverable", it) }
        }

        return Result(
            nostrEventId = event.id,
            nudgeToConnectKilter = nudgeToConnect,
        )
    }

    private suspend fun activeBoardSize(): BoardSize? {
        val sizeId = userPreferences.boardProductSizeId.first()
        return runCatching { boardRepository.getProductSize(sizeId) }.getOrNull()
    }

    /** Convenience: drain all `sync_status='draft'` climbs and publish each. */
    suspend fun publishAllPending(sizeLabel: String, layoutId: Long): Int {
        val drafts = boardRepository.getDraftClimbs()
        var published = 0
        for (row in drafts) {
            val state = ClimbEditorState(
                selectedHolds = parseHolds(row.framesText),
                name = row.name,
                description = row.description,
                setterGradeId = null,
                angle = null,
            )
            try {
                publish(row.uuid, layoutId, state, sizeLabel)
                published++
            } catch (e: Exception) {
                Log.w(TAG, "draft publish failed uuid=${row.uuid}", e)
            }
        }
        return published
    }

    private fun parseHolds(framesText: String): Map<Int, Int> {
        val holds = com.cruxcoach.domain.board.BoardClimbParser.parseFrames(framesText)
        return holds.associate { it.placementId to it.roleId }
    }

    /**
     * Resolve placement coords for the editor's selected holds → bounding
     * box. Mirrors ClimbCreatorRepository.computeBounds — same single
     * source of truth for what `bounds` means at publish time. Returns
     * null when placements aren't loaded yet (publish path falls through
     * to no `bounds` tag in the Nostr event; subscribers handle that
     * gracefully).
     */
    private fun computeBounds(state: ClimbEditorState): ClimbBounds? {
        val ids = state.selectedHolds.keys
        if (ids.isEmpty()) return null
        val all = runCatching { boardRepository.getAllPlacements() }.getOrNull().orEmpty()
        if (all.isEmpty()) return null
        val coords = all.asSequence()
            .filter { it.placementId.toInt() in ids }
            .map { it.x.toInt() to it.y.toInt() }
            .toList()
        return ClimbBounds.fromCoords(coords)
    }
}
