package com.cruxcoach.android.community

import android.util.Log
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.CommunityClimbDeleteContext
import com.vitorpamplona.quartz.nip01Core.core.Event
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ClimbDeleter"
private const val KIND_REPLACEABLE_PARAMETERIZED = 30078
private const val KIND_DELETION = 5
private const val NAMESPACE_LABEL = "com.cruxcoach.climb"

/**
 * Deletes a CruxCoach-authored community climb across the two surfaces
 * we control (Nostr) and surfaces a hint about the one we don't (Kilter).
 *
 * The Nostr side is two-pronged because relays vary in which deletion
 * primitive they honour:
 *
 *  1. **Kind-30078 tombstone-replacement** — same `d`-tag as the
 *     original event, with a `["deleted","true"]` tag and
 *     `{"deleted":true}` content. Replaceable-event semantics (NIP-78
 *     §replaceable) say the newer event with the same (kind, pubkey,
 *     d-tag) supersedes the older. Relays that index by replaceable-
 *     key serve only this tombstone on subsequent REQs; older events
 *     drop out naturally.
 *  2. **Kind-5 NIP-09 deletion event** — references the original via
 *     `["a","30078:<pubkey>:<d-tag>"]`, plus an `["e",<eventId>]`
 *     pointer to the latest event id, and `["k","30078"]` so relays
 *     that filter Kind-5 by k-tag see it. NIP-09-honouring relays
 *     drop the original from their indices entirely.
 *
 * Both events also carry an `["L","com.cruxcoach.climb"]` label so the
 * subscriber's `#L`-filter catches them in a single REQ.
 *
 * Local DB is updated via [BoardRepository.markCommunityClimbDeleted]:
 * owner-locked SQL that flips `is_deleted=1, is_listed=0`,
 * sync_status='deleted', and bumps `created_at` to the tombstone time
 * so a future Live-Sub Original-Event from a non-deleting relay loses
 * the stale-event check.
 *
 * Kilter is intentionally NOT touched. Kilter's REST API has no
 * delete-climb endpoint (verified via reverse-engineering of the
 * official app — see ~/kilter-re/analysis/FINDINGS.md, §4); the
 * Kilter app itself only allows deleting drafts and only via PowerSync
 * (binary sync protocol we don't speak). The deleter surfaces the
 * Kilter publish status via [Outcome.kilterWasPublished] so the UI
 * can warn the user that they must remove the climb manually if they
 * also published it to Kilter.
 */
@Singleton
class CommunityClimbDeleter @Inject constructor(
    private val nostrSigner: NostrSigner,
    private val pool: NostrRelayPool,
    private val boardRepository: BoardRepository,
) {
    sealed interface Outcome {
        /** No row found locally for [uuid]. UI should treat this as
         *  "already gone — refresh". */
        object NotFound : Outcome
        /** Row exists but origin != 'cruxcoach' (Kilter-only climb).
         *  Delete is refused — Kilter rows are read-only on our end. */
        object NotOurClimb : Outcome
        /** Row exists, origin = 'cruxcoach', but the row's
         *  `created_by_pubkey` doesn't match the current signer. We
         *  can't sign a NIP-09 deletion on someone else's behalf. */
        object NotOwner : Outcome
        /** Local row was tombstoned and the deletion + replacement
         *  events were attempted on the relay pool. The user-visible
         *  hint about Kilter is governed by [kilterWasPublished].
         *  [accepted] = 0 means no relay confirmed; the local row is
         *  still flipped (best-effort decentralised delete; the user
         *  can republish the deletion later by re-tapping). */
        data class Done(
            val attempted: Int,
            val accepted: Int,
            val kilterWasPublished: Boolean,
        ) : Outcome
    }

    suspend fun delete(uuid: String): Outcome {
        val ctx = runCatching { boardRepository.getCommunityClimbDeleteContext(uuid) }
            .onFailure { Log.w(TAG, "delete: context lookup failed uuid=$uuid", it) }
            .getOrNull() ?: return Outcome.NotFound

        if (ctx.origin != "cruxcoach") {
            Log.w(TAG, "delete refused: origin=${ctx.origin} uuid=$uuid")
            return Outcome.NotOurClimb
        }

        val signer = runCatching { nostrSigner.getPublicKeyHex() }
            .onFailure { Log.w(TAG, "delete: pubkey resolve failed", it) }
            .getOrNull() ?: return Outcome.NotOwner

        val owner = ctx.createdByPubkey
        if (owner != null && owner != signer) {
            // `owner?.take(8)` instead of `owner.take(8)`: Kotlin 2.x
            // refuses the smart-cast on `ctx.createdByPubkey` because it's
            // a public API property declared in a different module
            // (BoardRepository.kt in :shared). The local val captures the
            // value cleanly, but the smart-cast restriction still
            // propagates — safe-call sidesteps it without re-reading.
            Log.w(
                TAG,
                "delete refused: signer=${signer.take(8)} owner=${owner?.take(8)} uuid=$uuid",
            )
            return Outcome.NotOwner
        }

        val dTag = ctx.nostrDTag
        if (dTag.isNullOrBlank()) {
            // Climb was never published — fall back to the local-only
            // delete path. saveDraft sets sync_status='draft' but
            // ClimbEditorViewModel.deleteDraft is the right surface
            // for those; the deleter targets published rows.
            Log.w(TAG, "delete: climb has no d-tag (never published) uuid=$uuid")
            return Outcome.NotFound
        }

        val tombstoneEpoch = System.currentTimeMillis() / 1000L
        val tombstoneIso = java.time.Instant.ofEpochSecond(tombstoneEpoch).toString()

        // 1) Tombstone-replacement — replaceable-event index pathway.
        val replaceTags: Array<Array<String>> = arrayOf(
            arrayOf("d", dTag),
            arrayOf("L", NAMESPACE_LABEL),
            arrayOf("deleted", "true"),
        )
        val replaceContent = "{\"deleted\":true,\"uuid\":\"$uuid\"}"
        val replaceEvent = nostrSigner.signer.sign<Event>(
            createdAt = tombstoneEpoch,
            kind = KIND_REPLACEABLE_PARAMETERIZED,
            tags = replaceTags,
            content = replaceContent,
        )
        val (replaceAttempted, replaceAccepted) = pool.sendEventWithStats(replaceEvent)
        Log.i(
            TAG,
            "tombstone-replacement uuid=$uuid attempted=$replaceAttempted accepted=$replaceAccepted",
        )

        // 2) Kind-5 NIP-09 deletion — for relays that drop the
        //    original event entirely on a valid Kind-5.
        val deletionTags = mutableListOf<Array<String>>().apply {
            add(arrayOf("a", "$KIND_REPLACEABLE_PARAMETERIZED:$signer:$dTag"))
            ctx.nostrEventId?.takeIf { it.isNotBlank() }?.let { add(arrayOf("e", it)) }
            add(arrayOf("k", KIND_REPLACEABLE_PARAMETERIZED.toString()))
            add(arrayOf("L", NAMESPACE_LABEL))
        }.toTypedArray()
        val deletionEvent = nostrSigner.signer.sign<Event>(
            createdAt = tombstoneEpoch,
            kind = KIND_DELETION,
            tags = deletionTags,
            content = "",
        )
        val (deleteAttempted, deleteAccepted) = pool.sendEventWithStats(deletionEvent)
        Log.i(
            TAG,
            "kind-5 deletion uuid=$uuid attempted=$deleteAttempted accepted=$deleteAccepted",
        )

        // 3) Local tombstone. Owner-locked in SQL so a misuse here
        //    cannot flip a foreign row even if the calling code is
        //    buggy. Best-effort relay delivery is independent: even
        //    if no relay accepted the events, the local row is still
        //    tombstoned so the user sees an immediate effect; OfflineQueueManager
        //    will retry the events on the next connection.
        runCatching {
            boardRepository.markCommunityClimbDeleted(
                uuid = uuid,
                pubkey = signer,
                tombstoneIso = tombstoneIso,
            )
        }.onFailure { Log.w(TAG, "local tombstone write failed uuid=$uuid", it) }

        // Aggregate the relay stats from the two events: "attempted"
        // is the relay count we tried; "accepted" is whichever event
        // got through to the most relays — a single accepted event
        // is enough for the public delete to be effective.
        val attempted = maxOf(replaceAttempted, deleteAttempted)
        val accepted = maxOf(replaceAccepted, deleteAccepted)
        val kilterWasPublished = ctx.kilterStatus == "synced"
        return Outcome.Done(
            attempted = attempted,
            accepted = accepted,
            kilterWasPublished = kilterWasPublished,
        )
    }
}
