package com.cruxcoach.android.community

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.kilter.KilterClimbPublisher
import com.cruxcoach.android.data.kilter.KilterTokenStore
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.community.AutoNoteTemplate
import com.cruxcoach.domain.community.ClimbBounds
import com.cruxcoach.domain.community.ClimbEditorState
import com.cruxcoach.domain.community.buildCommunityClimbEvent
import com.cruxcoach.domain.community.encodeFrames
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val TAG = "ClimbPublisher"
private const val KIND_REPLACEABLE_PARAMETERIZED = 30078
private const val KIND_TEXT_NOTE = 1
private val APP_LINK_BASE: String =
    "https://${com.cruxcoach.android.BuildConfig.APP_LINK_HOST}/c/"

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
    /** Inputs the editor passes through when the user opted to also post
     *  a Kind-1 note alongside the Kind-30078 climb event. The template
     *  comes from the resolved string resource (locale-aware), so this
     *  layer doesn't depend on Android resources. */
    data class AutoNoteSpec(val template: String)

    data class Result(
        val nostrEventId: String,
        /**
         * `true` when the user isn't connected to Kilter and would have
         * benefitted from being so — i.e. the editor's "publish to
         * Kilter" setting is on but no token is present. Used by the UI
         * to show the "connect Kilter to also publish there" Snackbar.
         */
        val nudgeToConnectKilter: Boolean,
        /**
         * Outcome of the Kilter side of the publish, when the user has
         * Kilter publishing enabled AND a token. Null if Kilter wasn't
         * attempted (publish disabled, no token, or kilter orchestration
         * itself threw — in which case the editor sees "Kilter side
         * skipped, recoverable"). Lets the editor decide whether to
         * surface "synced/diverged/failed" Snackbar variants alongside
         * the existing connect-Kilter nudge. Pre-fix the editor
         * collapsed every non-Skipped("no-kilter-login") outcome into
         * a silent "✓ veröffentlicht" — the user navigated away
         * believing both destinations had succeeded.
         */
        val kilterOutcome: KilterClimbPublisher.Outcome? = null,
        /**
         * Outcome of the optional Kind-1 auto-note publish:
         *   - `null`  → user didn't opt into auto-note (template empty
         *               or toggle off in editor)
         *   - `true`  → ≥ 1 relay accepted the note
         *   - `false` → 0 relays accepted; the climb is up but the
         *               announcement didn't go through. Editor renders
         *               a distinct "Auto-Note didn't reach any relay"
         *               snackbar instead of the silent "✓ published".
         */
        val autoNotePublished: Boolean? = null,
    )

    suspend fun publish(
        uuid: String,
        layoutId: Long,
        boardBrand: BoardBrand,
        state: ClimbEditorState,
        sizeLabel: String,
        isEdit: Boolean = false,
        autoNote: AutoNoteSpec? = null,
    ): Result {
        val pubkey = nostrSigner.getPublicKeyHex()
        // Monotonic per d-tag (FEAT-039 audit BUG-1): a same-second re-publish
        // (fast typo-fix after publish) or a backward clock must still STRICTLY
        // advance, or "newest wins" diverges between the live-sub (applies on a
        // tie) and the Blossom chunk (skips on a tie). Clamp against the row's
        // last emitted created_at; persisted below via markClimbPublishedNostr.
        val createdAt = monotonicCreatedAtSeconds(
            System.currentTimeMillis() / 1000,
            boardRepository.getClimbCreatedAt(uuid),
        )

        // Brand is the climb's REAL board family, threaded in by the caller
        // — NOT re-derived from layoutId, which can't tell the Aurora-family
        // boards apart from Kilter (overlapping layout-ids). Drives both the
        // bounds skip below and the official-app-push skip in Step 2.
        val isMoonBoard = boardBrand == BoardBrand.MOONBOARD

        // Step 1: Nostr — mandatory. Failure throws; the user can retry
        // from the editor.
        // computeBounds resolves Aurora placement coordinates; MoonBoard
        // hold-ids aren't placement-ids (and could collide with low Kilter
        // placement-ids), so skip it — a null bounds tag is handled
        // gracefully by every subscriber.
        val bounds = if (isMoonBoard) null else computeBounds(state, boardBrand)
        val payload = buildCommunityClimbEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            uuid = uuid,
            layoutId = layoutId,
            sizeLabel = sizeLabel,
            state = state,
            brand = boardBrand,
            bounds = bounds,
        )
        val tags: Array<Array<String>> = payload.tags.map { it.toTypedArray() }.toTypedArray()
        val event = nostrSigner.signer.sign<Event>(
            createdAt = payload.createdAt,
            kind = KIND_REPLACEABLE_PARAMETERIZED,
            tags = tags,
            content = payload.content,
        )
        // Crash-safety pre-mark: promote the row into the retry queue
        // BEFORE the relay round-trip starts. If the process dies between
        // pool.sendEventWithStats accepting the event and the post-send
        // markClimbPublishedNostr below, the row stays in 'failed' (=
        // needs retry) and the next CommunityPublishRetryWorker tick
        // re-publishes; without this pre-mark a crash here would strand
        // the row at 'draft' forever, outside the retry filter.
        // Re-publish is idempotent — Kind-30078 is replaceable on
        // (pubkey, kind, d-tag) so the second event collapses with the
        // first on every relay.
        boardRepository.markClimbPublishInFlight(uuid)
        val (attempted, accepted) = pool.sendEventWithStats(event)
        Log.i(TAG, "publish uuid=$uuid d=${payload.dTag} attempted=$attempted accepted=$accepted")
        if (accepted == 0) {
            // Already 'failed' from the pre-mark above; markClimbPublishFailed
            // is idempotent so the second flip costs nothing and keeps the
            // call site readable.
            boardRepository.markClimbPublishFailed(uuid)
            throw IllegalStateException("No relay accepted the community-climb event (attempted=$attempted)")
        }
        boardRepository.markClimbPublishedNostr(
            uuid = uuid,
            nostrEventId = event.id,
            nostrDTag = payload.dTag,
            pubkey = pubkey,
            createdAtIso = java.time.Instant.ofEpochSecond(createdAt).toString(),
        )

        // Auto-Note: optional public Kind-1 announcement linking to the
        // climb. Best-effort — failure here is logged but doesn't fail
        // the overall publish (the climb is already on relays via the
        // 30078 above; the user can re-share manually). Runs after the
        // mandatory Kind-30078 succeeded so the naddr is durable.
        // `autoNotePublished` carries the relay-accepted-at-least-once
        // signal back to the editor; null means the user didn't opt in.
        val autoNotePublished: Boolean? = if (autoNote != null) {
            runCatching { publishKind1Note(payload.dTag, pubkey, state.name, boardBrand, autoNote) }
                .onFailure { Log.w(TAG, "auto-note publish threw — recoverable", it) }
                .getOrDefault(false)
        } else null

        // Step 2: Kilter — best-effort via the user's own account.
        // Detect "connected but publishing the climb still went only to
        // Nostr" so the UI can show the connect-Kilter hint. If the user
        // has explicitly disabled Kilter publishing in settings, we don't
        // nudge.
        //
        // The official-app (Kilter account) leg is Kilter-only. MoonBoard AND
        // the Aurora family (Tension/Grasshopper/Decoy/So iLL/Touchstone) are
        // CruxCoach-community-only — there is no CruxCoach→vendor-app publish for
        // them. Gate on the brand's own capability (KILTER-only) so an authored
        // Aurora climb is never pushed — mislabeled as Kilter — to the user's
        // Kilter account. The climb still went out over Nostr above.
        val publishToKilter = boardBrand.supportsOfficialAppPublish && userPreferences.kilterClimbPublishEnabled.first()
        val hasKilterToken = kilterTokenStore.getAccessToken() != null
        var nudgeToConnect = false
        var kilterOutcome: KilterClimbPublisher.Outcome? = null

        if (publishToKilter) {
            val boardSize = activeBoardSize()
            // Kilter's climbConcat uses hole_id (not placement_id) — see
            // BoardClimbParser.encodeClimbConcat docstring. Pull the
            // placementId → holeId map and let the encoder resolve.
            val pidToHoleId: Map<Int, Long> = runCatching { boardRepository.getAllPlacements() }
                .getOrDefault(emptyList())
                .associate { it.placementId.toInt() to it.holeId }
            val framesClimbConcat = BoardClimbParser.encodeClimbConcat(
                state.selectedHolds.entries
                    .sortedBy { it.key }
                    .map { com.cruxcoach.domain.board.BoardHold(it.key, it.value) },
                pidToHoleId,
            )
            runCatching {
                // Edit branches into update-climb/transaction. We only
                // route to update when the row was previously synced —
                // an edit on a row that never made it to Kilter (e.g.
                // user wasn't connected before) is functionally a fresh
                // create, and create-climb/transaction is idempotent on
                // climb_uuid anyway.
                val priorKilterStatus = boardRepository.getKilterPublishState(uuid)?.status
                // 'rejected' (CREATE 4xx) is terminal for the retry queue,
                // but an edit-republish is the legitimate way back in: the
                // edit may fix exactly what Kilter's validation refused
                // (name, content-policy). Downgrade to 'failed' so the
                // claim inside kilterPublisher can take the slot — and so
                // the retry worker picks the row up later if this attempt
                // is skipped (e.g. token expired right now). Without this,
                // claimKilterPublishSlot (NULL/'failed' only) returned
                // Skipped("slot-busy") forever and the edited climb could
                // never be mirrored short of delete + recreate.
                if (isEdit && priorKilterStatus == "rejected") {
                    Log.i(TAG, "rejected row edited — re-opening Kilter lane for $uuid")
                    boardRepository.markKilterPublishFailed(uuid, "re-eligible: user edit after rejection")
                }
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
                kilterOutcome = outcome
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
            kilterOutcome = kilterOutcome,
            autoNotePublished = autoNotePublished,
        )
    }

    private suspend fun activeBoardSize(): BoardSize? {
        val sizeId = userPreferences.boardProductSizeId.first()
        return runCatching { boardRepository.getProductSize(sizeId) }.getOrNull()
    }

    /** Convenience: drain all `sync_status='draft'` climbs and publish each.
     *
     *  - The pre-loop `getDraftClimbs()` is now wrapped: a SQLite read
     *    failure (lock contention, schema mid-migration) used to abort
     *    the batch with no published count. Now it's logged + the batch
     *    short-circuits to "0 published" so the caller still gets a
     *    sensible answer.
     *  - The per-row catch widens from `Exception` to `Throwable` so an
     *    `Error` subclass (e.g. OutOfMemoryError from BoardClimbParser
     *    on a corrupted hex string) doesn't skip the catch and propagate
     *    out — single-row failure shouldn't poison the whole batch.
     *    CancellationException is re-thrown explicitly so cooperative
     *    cancellation still works.
     */
    suspend fun publishAllPending(sizeLabel: String, layoutId: Long, boardBrand: BoardBrand): Int {
        val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
        val drafts = runCatching { boardRepository.getDraftClimbs(pubkey, boardBrand.wireValue) }
            .onFailure { Log.w(TAG, "publishAllPending: getDraftClimbs failed; batch aborted", it) }
            .getOrElse { return 0 }
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
                publish(row.uuid, layoutId, boardBrand, state, sizeLabel)
                published++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
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
    /**
     * Build + send the Kind-1 announcement. Encodes the just-published
     * climb as NIP-19 `naddr1…` (replaceable-event reference — survives
     * future edits because the d-tag is stable). The template's
     * `{npub_cruxcoach}` resolves to [NostrConfig.DEV_PUBKEY] (the
     * maintainer-bound brand pubkey baked into the build); a `p`-tag
     * pointing at it makes the mention surface in Amethyst's notifications
     * for the maintainer account, which doubles as a reach-multiplier for
     * CruxCoach climbs.
     */
    /**
     * Publish the optional Kind-1 announcement note. Returns:
     *   - `true`  → ≥ 1 relay accepted
     *   - `false` → 0 relays accepted (the note didn't land anywhere)
     *
     * The Kind-30078 climb has already been accepted by the time this
     * runs, so a `false` here is non-fatal — the user just doesn't get
     * the announcement reach they asked for. Returned to the caller so
     * the editor can surface a distinct "Auto-Note didn't go through"
     * snackbar instead of the silent "published!" pre-fix.
     */
    private suspend fun publishKind1Note(
        dTag: String,
        authorPubkeyHex: String,
        climbName: String,
        boardBrand: BoardBrand,
        spec: AutoNoteSpec,
    ): Boolean {
        val naddr = NAddress.create(
            kind = KIND_REPLACEABLE_PARAMETERIZED,
            pubKeyHex = authorPubkeyHex,
            dTag = dTag,
            relays = emptyList(),
        )
        val cruxcoachNpub = NostrConfig.DEV_PUBKEY.hexToByteArray().toNpub()
        val content = AutoNoteTemplate.render(
            template = spec.template,
            vars = mapOf(
                "name" to climbName,
                "naddr" to naddr,
                "npub_cruxcoach" to cruxcoachNpub,
                "cruxcoach_url" to "$APP_LINK_BASE$naddr",
            ),
        )
        // Tags: explicit hashtags (so #-search hits) + optional p-tag
        // mention (NIP-10 / Amethyst surfaces this as a reply-mention in
        // the recipient's notifications). The naddr embed in `content`
        // covers the climb-link side. The maintainer-mention is gated by
        // BuildConfig.AUTO_NOTE_PTAG_MAINTAINER so forks default to off
        // — every fork install would otherwise unconditionally amplify
        // whoever the fork's MAINTAINER_PUBKEY resolves to.
        val tagList = mutableListOf(
            arrayOf("t", com.cruxcoach.domain.community.boardHashtag(boardBrand)),
            arrayOf("t", "climbing"),
        )
        if (com.cruxcoach.android.BuildConfig.AUTO_NOTE_PTAG_MAINTAINER) {
            tagList.add(0, arrayOf("p", NostrConfig.DEV_PUBKEY))
        }
        val tags: Array<Array<String>> = tagList.toTypedArray()
        val noteEvent = nostrSigner.signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = KIND_TEXT_NOTE,
            tags = tags,
            content = content,
        )
        val (attempted, accepted) = pool.sendEventWithStats(noteEvent)
        if (accepted == 0 && attempted > 0) {
            // The Kind-30078 climb was already accepted by the time we
            // got here, so the user sees "published!" — but the auto-note
            // didn't reach any relay. Surface as `false` so the editor
            // can render a distinct snackbar.
            Log.w(TAG, "auto-note rejected by all relays attempted=$attempted")
        } else {
            Log.i(TAG, "auto-note attempted=$attempted accepted=$accepted")
        }
        return accepted > 0
    }

    private fun computeBounds(state: ClimbEditorState, boardBrand: BoardBrand): ClimbBounds? {
        val ids = state.selectedHolds.keys
        if (ids.isEmpty()) return null
        // Brand-scope placements: an Aurora climb's bounds must come from its own
        // board's placement coords, not Kilter's (the no-arg default).
        val all = runCatching { boardRepository.getAllPlacements(boardBrand.wireValue) }.getOrNull().orEmpty()
        if (all.isEmpty()) return null
        val coords = all.asSequence()
            .filter { it.placementId.toInt() in ids }
            .map { it.x.toInt() to it.y.toInt() }
            .toList()
        return ClimbBounds.fromCoords(coords)
    }
}
