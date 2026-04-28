package com.cruxcoach.android.community

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.kilter.KilterClimbPublisher
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardClimbParser
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
 * Publishes a [com.cruxcoach.domain.community.ClimbEditorState] as a
 * Kind-30078 community-climb event (FEAT-003 §4). The event payload is
 * built deterministically by [buildCommunityClimbEvent], signed with the
 * user's Nostr key, and broadcast via [NostrRelayPool].
 *
 * After successful publish the local DB row is flipped to
 * `sync_status = 'published_nostr'` so the next browse picks up the
 * event id + d-tag for cross-references.
 */
@Singleton
class CommunityClimbPublisher @Inject constructor(
    private val nostrSigner: NostrSigner,
    private val pool: NostrRelayPool,
    private val boardRepository: BoardRepository,
    private val kilterPublisher: KilterClimbPublisher,
    private val userPreferences: UserPreferences,
) {
    /**
     * Publish a freshly-saved local draft. Returns the published event's
     * `id` on success; throws if no relay accepted.
     *
     * Caller is expected to have already saved the climb as a draft via
     * [BoardRepository.insertLocalDraft] — this method only handles the
     * Nostr publish step and updates the local sync_status afterwards.
     */
    suspend fun publish(
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        sizeLabel: String,
    ): String {
        val pubkey = nostrSigner.getPublicKeyHex()
        val createdAt = System.currentTimeMillis() / 1000

        val payload = buildCommunityClimbEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            uuid = uuid,
            layoutId = layoutId,
            sizeLabel = sizeLabel,
            state = state,
        )

        val tags: Array<Array<String>> = payload.tags.map { it.toTypedArray() }.toTypedArray()
        val event = nostrSigner.signer.sign<Event>(
            createdAt = payload.createdAt,
            kind = KIND_REPLACEABLE_PARAMETERIZED,
            tags = tags,
            content = payload.content,
        )

        val (attempted, accepted) = pool.sendEventWithStats(event)
        Log.i(
            TAG,
            "publish uuid=$uuid d=${payload.dTag} attempted=$attempted accepted=$accepted",
        )
        if (accepted == 0) {
            boardRepository.markClimbPublishFailed(uuid)
            throw IllegalStateException("No relay accepted the community-climb event (attempted=$attempted)")
        }

        boardRepository.markClimbPublishedNostr(
            uuid = uuid,
            nostrEventId = event.id,
            nostrDTag = payload.dTag,
        )

        // Best-effort Kilter mirror. Runs after Nostr success so the Nostr
        // path is the source of truth — Kilter publishing failures don't
        // unwind the published Nostr event. The orchestrator decides which
        // path (self / bundled / skip) and updates kilter_status flags
        // accordingly. Doesn't throw — a failed Kilter mirror is queued
        // for retry, the climb is still considered "published".
        runCatching {
            val boardSize = withBoardSize(layoutId)
            val framesClimbConcat = BoardClimbParser.encodeClimbConcat(
                state.selectedHolds.entries
                    .sortedBy { it.key }
                    .map { com.cruxcoach.domain.board.BoardHold(it.key, it.value) }
            )
            kilterPublisher.publish(
                uuid = uuid,
                layoutId = layoutId,
                state = state,
                sizeLabel = sizeLabel,
                boardSize = boardSize,
                nostrEvent = event,
                framesClimbConcat = framesClimbConcat,
            )
        }.onFailure { Log.w(TAG, "kilter publish orchestration threw — recoverable", it) }

        return event.id
    }

    /** Resolve the active board size for the layout. Returns null if
     *  the device hasn't synced board metadata yet — the Kilter publish
     *  is then skipped (we can't fill `edge_*` fields without it). */
    private suspend fun withBoardSize(layoutId: Long): com.cruxcoach.data.repository.BoardSize? {
        // Pull the user's current board product size; the layout id
        // selected in the editor is already filtered through the same
        // setting, so they're consistent. We don't know the per-layout
        // dimensions from layoutId alone — userPreferences.boardProductSizeId
        // is the authoritative pointer.
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
                // `display_difficulty` carries the setter grade for local drafts
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
}
