package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §2: the owner's own policy, as a signed append-only ledger.
 *
 * Circle baselines and per-person/per-object exceptions decide who sees what,
 * which makes them permissions, not settings. They were previously written
 * straight into mutable SQL rows — unsigned, unordered, with no history — so
 * anything able to write the database could widen every grant silently and
 * leave nothing to notice it by. They now travel the same route as the
 * relationship entries: signed, sequenced, reduced, fail-closed.
 *
 * Kept separate from [SharingLedgerEntry] because the scope is different. A
 * relationship entry is about one peer and is (in part) the peer's to write; an
 * owner-policy entry is global and is only ever the owner's.
 */
sealed interface OwnerPolicyBody {

    /** Grants or removes [category] for [circle]. */
    data class CircleBaselineSet(
        val circle: SharingCircle,
        val category: SharingCategory,
        val granted: Boolean,
    ) : OwnerPolicyBody

    /** Sets or clears (`effect == null`) a per-person exception. */
    data class PeerRuleSet(
        val peer: PeerId,
        val category: SharingCategory,
        val effect: AccessEffect?,
    ) : OwnerPolicyBody

    /** Sets or clears (`effect == null`) a per-object exception. */
    data class ObjectRuleSet(
        val peer: PeerId,
        val objectId: ObjectId,
        val category: SharingCategory,
        val effect: AccessEffect?,
    ) : OwnerPolicyBody

    /** An entry this build does not understand. Never skipped. */
    data class Unknown(val kind: String) : OwnerPolicyBody
}

/** One signed, append-only owner-policy entry. */
data class OwnerPolicyEntry(
    val id: LedgerEntryId,
    val policySequence: Long,
    /**
     * The entry this one was built on; `null` only for the first.
     *
     * The policy is a DAG for the same reason the relationship ledger is. Two
     * of the owner's devices offline both change the same baseline, and under a
     * single chain the second to arrive collided on the next sequence and was
     * discarded — so a denial made on a phone lost to a grant made on a laptop
     * because of network timing.
     */
    val parent: LedgerEntryId? = null,
    val authorityGeneration: Long,
    val signerNpub: String,
    val signature: String,
    val body: OwnerPolicyBody,
)

/**
 * The reduced owner policy.
 *
 * When [failClosedReason] is set every collection here is empty, so a policy
 * that could not be reduced grants nothing rather than falling back to whatever
 * was last believed.
 */
data class OwnerPolicyState(
    val baselines: CircleBaselines = CircleBaselines(),
    val peerRules: Map<PeerId, Map<SharingCategory, AccessEffect>> = emptyMap(),
    val objectRules: Map<PeerId, Map<ObjectRuleKey, AccessEffect>> = emptyMap(),
    val authorityGeneration: Long = 1,
    val lastSequence: Long = 0,
    val appliedCount: Int = 0,
    val failClosedReason: String? = null,
) {
    /**
     * Combines the owner's rules with the circles the relationship ledger
     * assigned, producing the policy [SharingPolicyResolver] consumes.
     */
    fun toPolicy(circles: Map<PeerId, SharingCircle>): SharingPolicy {
        val peers = (circles.keys + peerRules.keys + objectRules.keys).associateWith { peer ->
            PeerPolicy(
                circle = circles[peer] ?: SharingCircle.ALL_OTHER_USERS,
                categoryRules = peerRules[peer].orEmpty(),
                objectRules = objectRules[peer].orEmpty(),
            )
        }
        return SharingPolicy(baselines = baselines, peers = peers)
    }

    /**
     * Categories this policy grants [peer] *any* access to, in [circle].
     *
     * "Any" is deliberate: a category counts if the resolver would allow it
     * outright (a person allow, or a baseline of this circle or a wider one) or
     * if a single object inside it is allowed. A category whose only rule is a
     * deny is not offered — there would be nothing to consent to.
     *
     * This is what an offer should contain, so that consent is asked for
     * exactly what the owner has actually decided to share.
     */
    fun allowedCategoriesFor(peer: PeerId, circle: SharingCircle): Set<SharingCategory> {
        if (failClosedReason != null) return emptySet()
        val policy = toPolicy(mapOf(peer to circle))
        val objectAllowed = objectRules[peer].orEmpty()
            .filterValues { it == AccessEffect.ALLOW }
            .map { (key, _) -> key.category }
            .toSet()
        return SharingCategory.entries.filterTo(mutableSetOf()) { category ->
            category in objectAllowed ||
                SharingPolicyResolver.resolve(policy, peer, category).effect == AccessEffect.ALLOW
        }
    }

    /** The category an object exception was recorded against. */
    fun objectRuleCategory(peer: PeerId, objectId: ObjectId): SharingCategory? =
        objectRules[peer]?.keys?.filter { it.objectId == objectId }?.map { it.category }?.singleOrNull()
}

/**
 * Reduces owner-policy entries, with the same fail-closed discipline as the
 * relationship reducer: bad signature, a signer that is not the owner, a
 * sequence gap, two entries on one sequence, one id with two bodies, or an
 * entry kind this build does not know all produce a policy that grants nothing.
 */
class OwnerPolicyReducer(
    private val verifier: (OwnerPolicyEntry) -> Boolean,
    private val ownerNpub: String,
) {

    /**
     * Reduces along the branch the resolver picks.
     *
     * The same seam the relationship ledger has, and for the same reason:
     * handing every entry to the fold and letting sequence order decide is what
     * let the losing side of an offline fork set a baseline. The acts carry the
     * ordering terms — device, generation, and an effect derived from the signed
     * body — so no device can win by mislabelling its own change.
     */
    fun reduce(
        entries: List<OwnerPolicyEntry>,
        acts: TrustedAttestations,
    ): OwnerPolicyState {
        if (entries.isEmpty()) return reduce(emptyList())
        acts.failClosedReason?.let { return OwnerPolicyState(failClosedReason = it) }
        val history = AuthorityBranchResolver.policyHistory(entries, acts)
        if (history.isEmpty()) {
            return OwnerPolicyState(
                failClosedReason =
                    "no branch of the owner policy is authorised by a device the manifest vouches for",
            )
        }
        return reduce(history)
    }

    fun reduce(entries: List<OwnerPolicyEntry>): OwnerPolicyState {
        // 1. Collapse exact duplicates; one id with two bodies is two histories.
        val byId = LinkedHashMap<LedgerEntryId, OwnerPolicyEntry>()
        for (e in entries) {
            val existing = byId[e.id]
            if (existing != null) {
                if (existing != e) return failed("duplicate entry id ${e.id.value} with divergent content")
                continue
            }
            byId[e.id] = e
        }
        val unique = byId.values.toList()
        if (unique.isEmpty()) return OwnerPolicyState()

        // 2. Authenticity, then authority.
        unique.firstOrNull { !verifier(it) }?.let {
            return failed("signature verification failed for entry ${it.id.value}")
        }
        unique.firstOrNull { it.signerNpub != ownerNpub }?.let {
            return failed("owner policy entry ${it.id.value} signed by ${it.signerNpub}, not the owner")
        }

        // 3. Canonical order: sequence, then id.
        //
        // Strict, gap-free numbering was a linear chain's invariant and the DAG
        // replaces it. A baseline and a per-peer rule are different subjects,
        // numbered from the same parent, so they legitimately share a number;
        // and a scope whose branch lost leaves a gap. Integrity comes from
        // admission and branch selection instead.
        val ordered = unique.sortedWith(compareBy({ it.policySequence }, { it.id.value }))

        // 4. Fold.
        var state = OwnerPolicyState()
        var applied = 0
        for (e in ordered) {
            if (e.authorityGeneration < state.authorityGeneration) continue // superseded

            // A new authority starts from nothing: inheriting the previous
            // baselines would let re-establishing authority silently re-grant
            // everything the old one had.
            if (e.authorityGeneration > state.authorityGeneration) {
                state = OwnerPolicyState(authorityGeneration = e.authorityGeneration)
            }

            state = apply(state, e.body)
                ?: return failed("unreducible owner policy entry at sequence ${e.policySequence}")
            state = state.copy(lastSequence = e.policySequence)
            applied++
        }
        return state.copy(appliedCount = applied)
    }

    private fun apply(state: OwnerPolicyState, body: OwnerPolicyBody): OwnerPolicyState? = when (body) {
        is OwnerPolicyBody.CircleBaselineSet ->
            state.copy(baselines = state.baselines.withCategory(body.circle, body.category, body.granted))

        is OwnerPolicyBody.PeerRuleSet -> {
            val current = state.peerRules[body.peer].orEmpty()
            val next = if (body.effect == null) current - body.category else current + (body.category to body.effect)
            state.copy(peerRules = state.peerRules + (body.peer to next))
        }

        is OwnerPolicyBody.ObjectRuleSet -> {
            val current = state.objectRules[body.peer].orEmpty()
            val next = if (body.effect == null) {
                current - ObjectRuleKey(body.objectId, body.category)
            } else {
                current + (ObjectRuleKey(body.objectId, body.category) to body.effect)
            }
            state.copy(objectRules = state.objectRules + (body.peer to next))
        }

        is OwnerPolicyBody.Unknown -> null
    }

    private fun failed(reason: String) = OwnerPolicyState(failClosedReason = reason)
}

/**
 * FEAT-062 §2: decides whether an owner-policy entry may be written at all.
 *
 * The symmetric counterpart of [SharingLedgerAdmission], and it exists for the
 * same reason. Refusing an entry when the policy is *read* is fail-closed but
 * permanent: an append-only ledger cannot drop it again, so a single unsigned
 * or out-of-sequence row would leave the whole policy granting nothing for
 * good. This runs first and is side-effect free by construction.
 */
object OwnerPolicyAdmission {

    @Suppress("ReturnCount")
    fun check(
        existing: List<OwnerPolicyEntry>,
        candidate: OwnerPolicyEntry,
        verifier: (OwnerPolicyEntry) -> Boolean,
        ownerNpub: String,
    ): LedgerAdmission {
        if (!verifier(candidate)) {
            return LedgerAdmission.Reject("signature does not verify for owner entry ${candidate.id.value}")
        }
        if (candidate.signerNpub != ownerNpub) {
            return LedgerAdmission.Reject(
                "owner policy entry ${candidate.id.value} signed by ${candidate.signerNpub}, not the owner",
            )
        }

        existing.firstOrNull { it.id == candidate.id }?.let { stored ->
            return if (stored == candidate) {
                LedgerAdmission.AlreadyPresent
            } else {
                LedgerAdmission.Reject("owner entry id ${candidate.id.value} already stored with divergent content")
            }
        }

        // The parent link, not a global next-sequence. Demanding "exactly the
        // next" is what let arrival order decide a baseline: two devices both
        // build on the same entry and both claim the next number, and refusing
        // the second made the first to reach the database win. Both are kept
        // and the resolver settles it.
        val parent = candidate.parent
        if (parent == null) {
            if (existing.isNotEmpty()) {
                return LedgerAdmission.Reject(
                    "owner policy entry ${candidate.id.value} claims to begin a policy that already began",
                )
            }
            if (candidate.policySequence != 1L) {
                return LedgerAdmission.Reject(
                    "the first owner policy entry is sequence 1, not ${candidate.policySequence}",
                )
            }
        } else {
            if (parent == candidate.id) {
                return LedgerAdmission.Reject("owner policy entry ${candidate.id.value} cannot be its own parent")
            }
            val parentEntry = existing.firstOrNull { it.id == parent }
                ?: return LedgerAdmission.Reject(
                    "owner policy entry ${candidate.id.value} follows ${parent.value}, which is not stored",
                )
            if (candidate.policySequence != parentEntry.policySequence + 1L) {
                return LedgerAdmission.Reject(
                    "owner policy sequence ${candidate.policySequence} does not follow its parent's " +
                        "${parentEntry.policySequence}",
                )
            }
        }

        val establishedGeneration = existing.maxOfOrNull { it.authorityGeneration } ?: candidate.authorityGeneration
        if (candidate.authorityGeneration < establishedGeneration) {
            return LedgerAdmission.Reject(
                "owner policy entry ${candidate.id.value} carries superseded authority generation " +
                    "${candidate.authorityGeneration}, established is $establishedGeneration",
            )
        }

        // Whatever the rules above missed, the reduction catches: an unknown
        // kind, or anything else that would leave the policy fail-closed.
        // Reduced along this candidate's own branch. Judging it by a sibling
        // branch's state would be judging it by a different history.
        val reduced = OwnerPolicyReducer(verifier, ownerNpub).reduce(ancestryOf(candidate, existing))
        reduced.failClosedReason?.let { return LedgerAdmission.Reject(it) }

        return LedgerAdmission.Accept
    }

    /**
     * [candidate] and its ancestors, root first.
     *
     * Walked with a visited set: a looping parent chain is malformed, and
     * looping for ever on malformed input is its own denial of service.
     */
    private fun ancestryOf(
        candidate: OwnerPolicyEntry,
        existing: List<OwnerPolicyEntry>,
    ): List<OwnerPolicyEntry> {
        val byId = existing.associateBy { it.id }
        val seen = linkedSetOf(candidate.id)
        val out = mutableListOf(candidate)
        var cursor = candidate.parent
        while (cursor != null && seen.add(cursor)) {
            val next = byId[cursor] ?: break
            out += next
            cursor = next.parent
        }
        return out.reversed()
    }
}
