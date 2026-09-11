package com.cruxcoach.domain.sharing

/** What may happen to a candidate entry. */
sealed interface LedgerAdmission {
    /** Store it. */
    data object Accept : LedgerAdmission

    /** Byte-identical to something already stored: do nothing, report success. */
    data object AlreadyPresent : LedgerAdmission

    /** Do not store it, and change nothing. */
    data class Reject(val reason: String) : LedgerAdmission
}

/**
 * FEAT-062 §5: decides whether a candidate entry may be written at all.
 *
 * The reducer already fails closed on a ledger it cannot trust, but refusing to
 * *read* something is not the same as refusing to *store* it. An entry that is
 * persisted first and rejected afterwards poisons the relationship for good:
 * every later reduction sees the same bad entry and stays `FAIL_CLOSED`, and an
 * append-only ledger has no way to take it back out. One unsigned or
 * out-of-sequence inbound message would be enough.
 *
 * So admission runs first and is **side-effect free by construction**: it is
 * given the stored entries and the candidate, and returns a verdict. Nothing
 * here writes, and nothing downstream may write without it.
 */
object SharingLedgerAdmission {

    @Suppress("ReturnCount")
    fun check(
        existing: List<SharingLedgerEntry>,
        candidate: SharingLedgerEntry,
        verifier: LedgerSignatureVerifier,
        ownerNpub: String,
    ): LedgerAdmission {
        // The candidate must be about the relationship it is being filed under.
        existing.firstOrNull()?.let { first ->
            if (first.peer != candidate.peer) {
                return LedgerAdmission.Reject(
                    "entry is for ${candidate.peer.value}, but the ledger is for ${first.peer.value}",
                )
            }
        }

        // Authenticity before anything else: an unverifiable entry is not
        // evidence of anything and must never reach storage.
        if (!verifier.verify(candidate)) {
            return LedgerAdmission.Reject("signature does not verify for entry ${candidate.id.value}")
        }

        // An id already present must be byte-identical, or two histories are
        // claiming one identity.
        existing.firstOrNull { it.id == candidate.id }?.let { stored ->
            return if (stored == candidate) {
                LedgerAdmission.AlreadyPresent
            } else {
                LedgerAdmission.Reject("entry id ${candidate.id.value} already stored with divergent content")
            }
        }

        // The parent link, not a global next-sequence. Demanding "exactly the
        // next sequence" is what made the model decorative: two of the owner's
        // devices offline both build on the same entry and both claim the next
        // number, and the second to arrive was refused — so the branch that
        // reached the database first won. That is arrival order deciding who
        // can see what. Both branches are kept and the resolver picks.
        val parent = candidate.parent
        if (parent == null) {
            if (existing.isNotEmpty()) {
                return LedgerAdmission.Reject(
                    "entry ${candidate.id.value} claims to begin a relationship that already has a beginning",
                )
            }
            if (candidate.policySequence != 1L) {
                return LedgerAdmission.Reject(
                    "the first entry about a peer is sequence 1, not ${candidate.policySequence}",
                )
            }
        } else {
            if (parent == candidate.id) {
                return LedgerAdmission.Reject("entry ${candidate.id.value} cannot be its own parent")
            }
            val parentEntry = existing.firstOrNull { it.id == parent }
                ?: return LedgerAdmission.Reject(
                    "entry ${candidate.id.value} follows ${parent.value}, which is not stored",
                )
            // The sequence still has to follow its own parent, so it keeps
            // meaning something within a branch even though it is no longer
            // unique across them.
            if (candidate.policySequence != parentEntry.policySequence + 1L) {
                return LedgerAdmission.Reject(
                    "policy sequence ${candidate.policySequence} does not follow its parent's " +
                        "${parentEntry.policySequence}",
                )
            }
        }

        // Finally the whole thing has to reduce. This is what catches the role,
        // generation, epoch and unknown-kind rules without restating them here:
        // if the ledger with this entry appended would not be readable, the
        // entry does not go in.
        // Reduced along this candidate's own branch. Reducing the whole set
        // would judge the candidate by a *sibling* branch's state, which is a
        // different history and says nothing about whether this one is
        // readable.
        val branch = ancestryOf(candidate, existing)
        val reduced = SharingLedgerReducer(verifier, ownerNpub)
            .reduce(branch)
            .relationships[candidate.peer]
            ?: return LedgerAdmission.Reject("entry ${candidate.id.value} does not reduce to any state")

        reduced.failClosedReason?.let { return LedgerAdmission.Reject(it) }
        if (reduced.status == RelationshipStatus.FAIL_CLOSED) {
            return LedgerAdmission.Reject("entry ${candidate.id.value} would leave the relationship fail-closed")
        }

        return LedgerAdmission.Accept
    }

    /**
     * [candidate] and its ancestors, root first.
     *
     * Walked with a visited set: a parent chain that loops is malformed, and
     * looping for ever on malformed input is its own denial of service.
     */
    private fun ancestryOf(
        candidate: SharingLedgerEntry,
        existing: List<SharingLedgerEntry>,
    ): List<SharingLedgerEntry> {
        val byId = existing.associateBy { it.id }
        val chain = linkedSetOf(candidate.id)
        val out = mutableListOf(candidate)
        var cursor = candidate.parent
        while (cursor != null && chain.add(cursor)) {
            val next = byId[cursor] ?: break
            out += next
            cursor = next.parent
        }
        return out.reversed()
    }
}
