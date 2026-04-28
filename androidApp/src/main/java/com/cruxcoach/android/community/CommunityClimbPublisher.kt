package com.cruxcoach.android.community

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.kilter.BundledPublishResult
import com.cruxcoach.android.data.kilter.CruxCoachBundledPublishClient
import com.cruxcoach.android.data.kilter.KilterClimbPublisher
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
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
 * Top-level publish orchestrator for CruxCoach community climbs.
 *
 * Routes a freshly-saved local draft through one of three identity
 * modes — chosen from the user's settings:
 *
 * | Mode      | Nostr signing       | Kilter setter         |
 * |-----------|---------------------|-----------------------|
 * | A (def)   | own NostrSigner     | own Kilter account    |
 * | B (mixed) | own NostrSigner     | bundled (CruxCoach)   |
 * | C (anon)  | bundled (CruxCoach) | bundled (CruxCoach)   |
 * | D (mix2)  | bundled (CruxCoach) | own Kilter account    |
 *
 * Mode D ("anon Nostr but real Kilter") is unusual but follows from
 * keeping the two switches independent — we support it for symmetry.
 *
 * Each mode updates `nostr_publish_via` and `kilter_publish_via` on the
 * climbs row so the harvester + filters can tell the source apart.
 */
@Singleton
class CommunityClimbPublisher @Inject constructor(
    private val nostrSigner: NostrSigner,
    private val pool: NostrRelayPool,
    private val boardRepository: BoardRepository,
    private val kilterPublisher: KilterClimbPublisher,
    private val bundledClient: CruxCoachBundledPublishClient,
    private val userPreferences: UserPreferences,
) {
    /**
     * Publish a freshly-saved local draft.
     *
     * Returns the resulting Nostr event id on success (whichever signed
     * the event — own key or service). Throws if Nostr publishing fails,
     * regardless of mode — Nostr is the source of truth for the climb's
     * existence. Kilter side is best-effort; status flags get updated
     * but the caller doesn't see Kilter failures here.
     */
    suspend fun publish(
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        sizeLabel: String,
    ): String {
        val nostrViaBundled = userPreferences.nostrBundledSigningEnabled.first()
        val kilterViaBundled = userPreferences.kilterBundledFallbackEnabled.first()
        val boardSize = activeBoardSize()

        return when {
            nostrViaBundled && kilterViaBundled ->
                publishViaBundledBoth(uuid, layoutId, state, sizeLabel, boardSize)
            nostrViaBundled ->
                publishViaBundledNostrThenSelfKilter(uuid, layoutId, state, sizeLabel, boardSize)
            else ->
                publishViaSelfNostr(uuid, layoutId, state, sizeLabel, boardSize)
        }
    }

    // ── Path 1: own Nostr key (Mode A or B) ─────────────────────────

    private suspend fun publishViaSelfNostr(
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        sizeLabel: String,
        boardSize: BoardSize?,
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
        Log.i(TAG, "self-nostr uuid=$uuid d=${payload.dTag} attempted=$attempted accepted=$accepted")
        if (accepted == 0) {
            boardRepository.markClimbPublishFailed(uuid)
            throw IllegalStateException("No relay accepted the community-climb event (attempted=$attempted)")
        }

        boardRepository.markClimbPublishedNostr(
            uuid = uuid,
            nostrEventId = event.id,
            nostrDTag = payload.dTag,
            pubkey = pubkey,
            via = "self",
        )

        // Kilter side: own account if logged in, bundled if user opted
        // in, else skip. KilterClimbPublisher already handles all three.
        runCatching {
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

    // ── Path 2: bundled Nostr + maybe self Kilter (Mode D) ──────────

    private suspend fun publishViaBundledNostrThenSelfKilter(
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        sizeLabel: String,
        boardSize: BoardSize?,
    ): String {
        if (boardSize == null) {
            boardRepository.markClimbPublishFailed(uuid)
            throw IllegalStateException("No board size available for bundled publish")
        }
        val raw = buildRawClimbPayload(uuid, state, layoutId, sizeLabel, boardSize)
        val res = bundledClient.publish(
            mode = CruxCoachBundledPublishClient.Mode.NostrOnly,
            signedEvent = null,
            rawClimb = raw,
            clientAttestation = "placeholder-v1",
        )
        val nostrEventId = handleNostrBundledResponse(uuid, res)
        // Kilter side via the existing orchestrator. The bundled client
        // didn't push Kilter (mode was NostrOnly), but the user might be
        // logged in to Kilter for the self path. We can't pass the signed
        // event to the bundled-Kilter path because the server kept it —
        // but that's OK: KilterClimbPublisher.publish() only needs the
        // event for the bundled-Kilter case, and in this code path we've
        // already opted out of bundled-Kilter (would have used the
        // KilterAndNostr atomic call instead). So self-Kilter only here.
        runCatching {
            val framesClimbConcat = BoardClimbParser.encodeClimbConcat(
                state.selectedHolds.entries
                    .sortedBy { it.key }
                    .map { com.cruxcoach.domain.board.BoardHold(it.key, it.value) }
            )
            // Skip the bundled Kilter branch by passing a dummy event we
            // never use for self-only routing — KilterClimbPublisher tries
            // self first, falls through only if bundled-Kilter setting is
            // also on, which it isn't in this code path.
            kilterPublisher.publishSelfOnly(
                uuid = uuid,
                layoutId = layoutId,
                state = state,
                boardSize = boardSize,
                framesClimbConcat = framesClimbConcat,
            )
        }.onFailure { Log.w(TAG, "kilter publish (self-only) threw — recoverable", it) }
        return nostrEventId
    }

    // ── Path 3: bundled both, atomic (Mode C) ───────────────────────

    private suspend fun publishViaBundledBoth(
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        sizeLabel: String,
        boardSize: BoardSize?,
    ): String {
        if (boardSize == null) {
            boardRepository.markClimbPublishFailed(uuid)
            throw IllegalStateException("No board size available for bundled publish")
        }
        boardRepository.markKilterPublishPending(uuid)
        val raw = buildRawClimbPayload(uuid, state, layoutId, sizeLabel, boardSize)
        val res = bundledClient.publish(
            mode = CruxCoachBundledPublishClient.Mode.KilterAndNostr,
            signedEvent = null,
            rawClimb = raw,
            clientAttestation = "placeholder-v1",
        )
        val nostrEventId = handleNostrBundledResponse(uuid, res)
        // Kilter side from the same response.
        when (res) {
            is BundledPublishResult.Success -> {
                if (res.kilterStatus == "synced" || res.kilterStatus == "queued") {
                    boardRepository.markKilterPublishSynced(
                        uuid = uuid,
                        via = "cruxcoach",
                        syncedAtEpochSeconds = System.currentTimeMillis() / 1000,
                    )
                } else {
                    boardRepository.markKilterPublishFailed(
                        uuid,
                        "via=cruxcoach status=${res.kilterStatus} ${res.kilterError ?: ""}",
                    )
                }
            }
            else -> {
                // Already handled by handleNostrBundledResponse — both
                // halves failed together (Nostr failure throws above).
            }
        }
        return nostrEventId
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Convert a bundled-Nostr response into status-flag updates and
     *  return the resulting nostr_event_id, or throw if Nostr failed. */
    private fun handleNostrBundledResponse(
        uuid: String,
        result: BundledPublishResult,
    ): String = when (result) {
        is BundledPublishResult.Success -> {
            val eventId = result.nostrEventId
                ?: error("bundled service did not return a Nostr event id")
            val dTag = result.nostrDTag.orEmpty()
            val signerPubkey = result.nostrPubkey.orEmpty()
            boardRepository.markClimbPublishedNostr(
                uuid = uuid,
                nostrEventId = eventId,
                nostrDTag = dTag,
                pubkey = signerPubkey,
                via = "cruxcoach",
            )
            eventId
        }
        is BundledPublishResult.TransientError -> {
            boardRepository.markClimbPublishFailed(uuid)
            throw IllegalStateException("Bundled Nostr publish failed (transient): ${result.message}")
        }
        is BundledPublishResult.PermanentError -> {
            boardRepository.markClimbPublishFailed(uuid)
            throw IllegalStateException("Bundled Nostr publish rejected (HTTP ${result.httpCode}): ${result.message}")
        }
    }

    private fun buildRawClimbPayload(
        uuid: String,
        state: ClimbEditorState,
        layoutId: Long,
        sizeLabel: String,
        boardSize: BoardSize,
    ): CruxCoachBundledPublishClient.RawClimbPayload {
        val holds = state.selectedHolds.entries
            .sortedBy { it.key }
            .map { com.cruxcoach.domain.board.BoardHold(it.key, it.value) }
        return CruxCoachBundledPublishClient.RawClimbPayload(
            uuid = uuid,
            name = state.name,
            description = state.description,
            framesAurora = BoardClimbParser.encodeFrames(holds),
            framesClimbConcat = BoardClimbParser.encodeClimbConcat(holds),
            layoutId = layoutId,
            sizeLabel = sizeLabel,
            setterGradeId = state.setterGradeId,
            angle = state.angle,
            edgeLeft = boardSize.edgeLeft.toInt(),
            edgeRight = boardSize.edgeRight.toInt(),
            edgeBottom = boardSize.edgeBottom.toInt(),
            edgeTop = boardSize.edgeTop.toInt(),
            displayName = null,  // future: leaderboard_display_name pref
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
}
