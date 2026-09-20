package com.cruxcoach.app.community

import com.cruxcoach.app.backup.EventSigner
import com.cruxcoach.app.links.DEFAULT_APP_LINK_HOST
import com.cruxcoach.app.nostr.Nip19
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrEvents
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.community.AutoNoteTemplate
import com.cruxcoach.domain.community.ClimbEditorState
import com.cruxcoach.domain.community.CommunityClimbTags
import com.cruxcoach.domain.community.boardHashtag
import com.cruxcoach.domain.community.buildCommunityClimbEvent
import com.cruxcoach.domain.community.communityClimbDTag

/** Nostr kinds the community-climb pipeline speaks. */
internal const val KIND_COMMUNITY_CLIMB = 30078
internal const val KIND_TEXT_NOTE = 1
internal const val KIND_DELETION = 5

/** Why a publish did not produce a live, durable climb event. */
enum class PublishFailure {
    NONE,

    /** No signer: the identity has not finished initialising. */
    NO_IDENTITY,

    /** Angle and grade are required at publish time — a grade-less event is
     *  accepted by every relay and then dropped at the door by every subscriber. */
    INCOMPLETE_DRAFT,

    /** The signer returned nothing, or what it returned did not verify. */
    SIGNING_FAILED,

    /** The event was signed and sent but no relay confirmed storing it. */
    NO_RELAY_ACCEPTED,
}

/**
 * Optional public Kind-1 announcement alongside the climb.
 *
 * [maintainerPubkeyHex] is what `{npub_cruxcoach}` resolves to. Android takes it
 * from a build constant; here the caller supplies it, and [mentionMaintainer]
 * gates the `p`-tag the same way Android's `AUTO_NOTE_PTAG_MAINTAINER` does — a
 * fork must not amplify someone else's account by default.
 */
class AutoNoteSpec(
    val template: String,
    val maintainerPubkeyHex: String? = null,
    val mentionMaintainer: Boolean = false,
    val appLinkHost: String = DEFAULT_APP_LINK_HOST,
)

/**
 * Outcome of one publish attempt.
 *
 * [attempted] and [accepted] are reported separately and never collapsed into a
 * boolean: "three relays dialled, one stored it" is a materially different state
 * from "three stored it", and the user is entitled to see which one happened.
 */
class CommunityPublishResult(
    val uuid: String,
    val dTag: String,
    val eventId: String,
    val attempted: Int,
    val accepted: Int,
    val failure: PublishFailure,
    /** Null when the user did not opt into an announcement note. */
    val autoNoteAttempted: Int?,
    val autoNoteAccepted: Int?,
) {
    val isPublished: Boolean get() = failure == PublishFailure.NONE
    /** True when the climb is live but did not reach every relay that was dialled. */
    val isPartial: Boolean get() = isPublished && accepted < attempted
}

/**
 * Publishes a CruxCoach-authored climb as a Kind-30078 replaceable event, signed
 * with the user's own key.
 *
 * Port of Android's `CommunityClimbPublisher` **without** its second
 * destination: pushing the climb into the user's Kilter account needs the Kilter
 * account work that is not part of this change, so this class never touches it
 * and never claims to have.
 */
class CommunityPublisher(
    private val boardRepository: BoardRepository,
    private val relay: CommunityRelay,
    private val signer: EventSigner,
    private val hashing: Hashing,
    private val clock: WallClock,
    private val pubkeyProvider: () -> String?,
) {
    suspend fun publish(
        uuid: String,
        layoutId: Long,
        boardBrand: BoardBrand,
        state: ClimbEditorState,
        sizeLabel: String,
        autoNote: AutoNoteSpec? = null,
    ): CommunityPublishResult {
        val pubkey = pubkeyProvider()
        if (pubkey.isNullOrBlank()) return failed(uuid, "", PublishFailure.NO_IDENTITY)
        // Both are hard preconditions of the event builder. Checking here keeps
        // the failure a value rather than an exception, and makes "the editor
        // let an incomplete draft through" a distinct, reportable state.
        if (state.angle == null || state.setterGradeId == null) {
            return failed(uuid, communityClimbDTag(pubkey, uuid), PublishFailure.INCOMPLETE_DRAFT)
        }

        // Strictly monotonic per d-tag: publish, edit and delete share one
        // replaceable key, so a same-second re-publish or a backward clock must
        // still advance or "newest wins" resolves differently on each path.
        val createdAt = CommunityEventTime.monotonicCreatedAtSeconds(
            clock.epochSeconds(),
            boardRepository.getClimbCreatedAt(uuid),
        )
        val bounds = CommunityClimbBounds.of(boardRepository, state)
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

        val event = signer.sign(createdAt, KIND_COMMUNITY_CLIMB, payload.tags, payload.content)
        // Nothing unsigned, mis-signed or signed by a different key ever leaves
        // this method: the id must bind this body and the signature must verify
        // under the pubkey the d-tag names.
        if (event == null || event.pubkey != pubkey || event.kind != KIND_COMMUNITY_CLIMB ||
            !NostrEvents.verify(hashing, event)
        ) {
            return failed(uuid, payload.dTag, PublishFailure.SIGNING_FAILED)
        }

        // Crash safety: promote the row into the retry set BEFORE the round trip.
        // A process death between a relay accepting and the post-send flip would
        // otherwise strand the row at 'draft', outside every retry filter.
        // Re-publishing is idempotent — the event is replaceable on (pubkey, kind, d-tag).
        boardRepository.markClimbPublishInFlight(uuid)
        val stats = relay.publish(event)
        if (stats.accepted == 0) {
            boardRepository.markClimbPublishFailed(uuid)
            return CommunityPublishResult(
                uuid = uuid,
                dTag = payload.dTag,
                eventId = event.id,
                attempted = stats.attempted,
                accepted = 0,
                failure = PublishFailure.NO_RELAY_ACCEPTED,
                autoNoteAttempted = null,
                autoNoteAccepted = null,
            )
        }
        boardRepository.markClimbPublishedNostr(
            uuid = uuid,
            nostrEventId = event.id,
            nostrDTag = payload.dTag,
            pubkey = pubkey,
            createdAtIso = CommunityEventTime.isoFromEpochSeconds(createdAt),
        )

        // The announcement runs only after the mandatory climb event is durable,
        // so the naddr it links to already exists. Its failure is not the
        // climb's failure — it is reported separately.
        val note = autoNote?.let { publishAutoNote(payload.dTag, pubkey, state.name, boardBrand, createdAt, it) }
        return CommunityPublishResult(
            uuid = uuid,
            dTag = payload.dTag,
            eventId = event.id,
            attempted = stats.attempted,
            accepted = stats.accepted,
            failure = PublishFailure.NONE,
            autoNoteAttempted = note?.attempted,
            autoNoteAccepted = note?.accepted,
        )
    }

    private suspend fun publishAutoNote(
        dTag: String,
        pubkey: String,
        climbName: String,
        boardBrand: BoardBrand,
        createdAt: Long,
        spec: AutoNoteSpec,
    ): com.cruxcoach.app.backup.PublishStats? {
        val naddr = Nip19.encodeClimbNaddr(pubkey, dTagUuid(dTag) ?: return null) ?: return null
        val maintainerNpub = spec.maintainerPubkeyHex?.let { Nip19.encodeNpub(it) }.orEmpty()
        val content = AutoNoteTemplate.render(
            template = spec.template,
            vars = mapOf(
                "name" to climbName,
                "board" to boardBrand.displayName,
                "naddr" to naddr,
                "npub_cruxcoach" to maintainerNpub,
                "cruxcoach_url" to "https://${spec.appLinkHost}/c/$naddr",
            ),
        )
        val tags = mutableListOf<List<String>>()
        // Off by default: an unconditional mention would make every fork amplify
        // whichever account its build constant names.
        if (spec.mentionMaintainer && !spec.maintainerPubkeyHex.isNullOrBlank()) {
            tags += listOf("p", spec.maintainerPubkeyHex)
        }
        tags += listOf("t", boardHashtag(boardBrand))
        tags += listOf("t", "climbing")
        val note = signer.sign(createdAt, KIND_TEXT_NOTE, tags, content) ?: return null
        if (note.pubkey != pubkey || !NostrEvents.verify(hashing, note)) return null
        return relay.publish(note)
    }

    private fun failed(uuid: String, dTag: String, failure: PublishFailure) = CommunityPublishResult(
        uuid = uuid,
        dTag = dTag,
        eventId = "",
        attempted = 0,
        accepted = 0,
        failure = failure,
        autoNoteAttempted = null,
        autoNoteAccepted = null,
    )

    private fun dTagUuid(dTag: String): String? {
        val parts = dTag.split(":")
        return if (parts.size >= 4 && parts[0] == "cruxcoach" && parts[1] == "climb") parts.last() else null
    }
}

/** Why a delete did not tombstone the climb. */
enum class DeleteOutcome {
    /** Nothing locally for this uuid — the caller should just refresh. */
    NOT_FOUND,

    /** The row is not CruxCoach-authored. Catalogue rows are read-only here. */
    NOT_OUR_CLIMB,

    /** The row belongs to a different key; we cannot sign a deletion for it. */
    NOT_OWNER,

    /** Both deletion events were sent and the local row was tombstoned. */
    DONE,

    /** The relay-side deletion is permanent but the local write threw, so the
     *  climb is still visible on this device. The UI has to say so. */
    LOCAL_TOMBSTONE_FAILED,
}

class CommunityDeleteResult(
    val outcome: DeleteOutcome,
    val attempted: Int,
    val accepted: Int,
    /** The climb also lives in the user's Kilter account; Kilter has no delete
     *  endpoint, so the UI must tell them to remove it there by hand. */
    val kilterWasPublished: Boolean,
    /** A claimed Kilter climb was un-claimed back to a re-claimable import
     *  instead of being tombstoned. */
    val revertedToKilterImport: Boolean,
)

/**
 * Deletes a CruxCoach-authored climb from the surfaces we control.
 *
 * Two events, because relays honour different deletion primitives: a Kind-30078
 * tombstone replacement on the same d-tag (replaceable-index relays then serve
 * only the tombstone) and a NIP-09 Kind-5 (index-honouring relays drop the
 * original outright). Both ride the same `L` namespace as the climb they delete,
 * or a subscriber would see the climb on one namespace and miss the deletion on
 * the other.
 *
 * Kilter is deliberately untouched: its API has no delete-climb endpoint.
 */
class CommunityDeleter(
    private val boardRepository: BoardRepository,
    private val relay: CommunityRelay,
    private val signer: EventSigner,
    private val hashing: Hashing,
    private val clock: WallClock,
    private val pubkeyProvider: () -> String?,
) {
    suspend fun delete(uuid: String): CommunityDeleteResult {
        val context = boardRepository.getCommunityClimbDeleteContext(uuid)
            ?: return result(DeleteOutcome.NOT_FOUND)
        if (context.origin != "cruxcoach") return result(DeleteOutcome.NOT_OUR_CLIMB)

        val signerPubkey = pubkeyProvider()
        if (signerPubkey.isNullOrBlank()) return result(DeleteOutcome.NOT_OWNER)
        val owner = context.createdByPubkey
        if (owner != null && owner != signerPubkey) return result(DeleteOutcome.NOT_OWNER)

        // The d-tag is a pure function of author and uuid. A restored row can
        // legitimately lack the stored tag (the backup blob does not carry it),
        // and origin plus ownership are already proven, so rebuild rather than refuse.
        val dTag = context.nostrDTag?.takeIf { it.isNotBlank() } ?: communityClimbDTag(signerPubkey, uuid)

        // The tombstone must strictly exceed the climb's last emit, or it loses
        // the replaceable-index race under a same-second delete.
        val tombstoneEpoch = CommunityEventTime.monotonicCreatedAtSeconds(
            clock.epochSeconds(),
            boardRepository.getClimbCreatedAt(uuid),
        )
        val tombstoneIso = CommunityEventTime.isoFromEpochSeconds(tombstoneEpoch)
        val namespace = if (BoardBrand.fromWire(context.boardBrand) == BoardBrand.KILTER) {
            CommunityClimbTags.NS_CLIMB
        } else {
            CommunityClimbTags.NS_CLIMB_V2
        }

        val replacement = sign(
            createdAt = tombstoneEpoch,
            kind = KIND_COMMUNITY_CLIMB,
            pubkey = signerPubkey,
            tags = listOf(
                listOf("d", dTag),
                listOf("L", namespace),
                listOf("l", CommunityClimbTags.LABEL_CLIMB, namespace),
                listOf("deleted", "true"),
            ),
            content = """{"deleted":true,"uuid":"$uuid"}""",
        )
        val deletionTags = mutableListOf(listOf("a", "$KIND_COMMUNITY_CLIMB:$signerPubkey:$dTag"))
        context.nostrEventId?.takeIf { it.isNotBlank() }?.let { deletionTags += listOf("e", it) }
        deletionTags += listOf("k", KIND_COMMUNITY_CLIMB.toString())
        deletionTags += listOf("L", namespace)
        deletionTags += listOf("l", CommunityClimbTags.LABEL_CLIMB, namespace)
        val deletion = sign(
            createdAt = tombstoneEpoch,
            kind = KIND_DELETION,
            pubkey = signerPubkey,
            tags = deletionTags,
            content = "",
        )
        if (replacement == null || deletion == null) {
            // Neither event is publishable, so nothing was deleted anywhere. The
            // local row must stay visible rather than pretend the climb is gone.
            return result(DeleteOutcome.NOT_OWNER)
        }

        val replacementStats = relay.publish(replacement)
        val deletionStats = relay.publish(deletion)

        // A claimed Kilter climb goes back to being a re-claimable import; a
        // native CruxCoach climb is tombstoned. Either way the relay-side
        // deletion above already happened.
        val revertToImport = context.kilterAuthorUuid != null
        val localFailed = try {
            if (revertToImport) {
                boardRepository.revertClaimedKilterClimb(uuid = uuid, pubkey = signerPubkey)
                boardRepository.clearLocalClimbGrade(uuid)
            } else {
                // Owner-locked in SQL, so a bug here still cannot flip a foreign row.
                boardRepository.markCommunityClimbDeleted(uuid, signerPubkey, tombstoneIso)
            }
            false
        } catch (e: Exception) {
            true
        }

        // A single accepted event makes the public delete effective, so take the
        // better of the two rather than summing them.
        return CommunityDeleteResult(
            outcome = if (localFailed) DeleteOutcome.LOCAL_TOMBSTONE_FAILED else DeleteOutcome.DONE,
            attempted = maxOf(replacementStats.attempted, deletionStats.attempted),
            accepted = maxOf(replacementStats.accepted, deletionStats.accepted),
            kilterWasPublished = context.kilterStatus == "synced",
            revertedToKilterImport = revertToImport && !localFailed,
        )
    }

    private fun sign(
        createdAt: Long,
        kind: Int,
        pubkey: String,
        tags: List<List<String>>,
        content: String,
    ): NostrEvent? {
        val event = signer.sign(createdAt, kind, tags, content) ?: return null
        if (event.pubkey != pubkey || event.kind != kind) return null
        return event.takeIf { NostrEvents.verify(hashing, it) }
    }

    private fun result(outcome: DeleteOutcome) = CommunityDeleteResult(
        outcome = outcome,
        attempted = 0,
        accepted = 0,
        kilterWasPublished = false,
        revertedToKilterImport = false,
    )
}
