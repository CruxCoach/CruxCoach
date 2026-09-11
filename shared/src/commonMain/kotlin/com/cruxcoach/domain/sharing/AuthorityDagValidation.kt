package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §11: the one check both the current authority and the branch
 * projection run before they decide anything.
 *
 * ## Why it is shared
 *
 * `AuthorityLedger.winners` and `AuthorityBranchResolver` answer two halves of
 * the same question — which act stands, and which entries it makes effective.
 * Validating separately, or in one and not the other, is how they would come to
 * disagree: the screen would show an estate the projection does not reflect,
 * and nothing anywhere would notice.
 *
 * ## Why a violation costs everything
 *
 * Every failure here is a history that cannot be reconstructed the same way
 * twice — a missing subject, an act pointing into another scope, a cycle, a
 * sequence that does not follow its parent. Resolving whatever is left would
 * hand somebody a **truncated** history, which has exactly the shape of a
 * revocation that was quietly dropped. So the answer is nothing at all: an
 * empty fail-closed projection and no current authority. Refusing to answer is
 * visible; answering from half a history is not.
 */
object AuthorityDagValidation {

    /**
     * `null` when the DAG is well formed, otherwise why it is not.
     *
     * [knownSubjects] is every entry id an act may legitimately name.
     * [entryShape] is looked up lazily, because the current-authority path
     * holds only the acts and has nothing to say about entry structure.
     */
    @Suppress("ReturnCount")
    fun check(
        acts: List<AuthorityAttestation>,
        knownSubjects: Set<LedgerEntryId>,
        /**
         * What each subject's signed body requires of the act naming it.
         *
         * Empty for callers that hold only the acts. Where it is supplied, the
         * act has to say exactly that — scope, effect and capability — or a
         * device could authorise a grant with an act that claimed to be a read.
         */
        requirements: Map<LedgerEntryId, AuthorityPairing.Required> = emptyMap(),
        entryShape: () -> Map<LedgerEntryId, Pair<LedgerEntryId?, Long>> = { emptyMap() },
    ): String? {
        val distinct = acts.distinctBy { it.id }

        // One act per subject, and a subject that exists. An act naming an
        // entry nobody stored vouches for nothing; two acts for one entry is
        // how a weaker claim rides in on a valid one.
        val bySubject = mutableMapOf<LedgerEntryId, AuthorityAttestation>()
        distinct.forEach { act ->
            if (!act.scope.isCurrentVersion) {
                return "attestation ${act.id.value} names a scope from the superseded encoding"
            }
            if (act.subject !in knownSubjects) {
                return "attestation ${act.id.value} names subject ${act.subject.value}, which is not stored"
            }
            bySubject.put(act.subject, act)?.let {
                return "entry ${act.subject.value} is authorised by more than one attestation"
            }
            requirements[act.subject]?.let { required ->
                if (act.scope != required.scope ||
                    act.effect != required.effect ||
                    act.capability != required.capability
                ) {
                    return "attestation ${act.id.value} does not say what entry " +
                        "${act.subject.value}'s signed body requires"
                }
            }
        }

        // And the other half of "exactly one": the missing half. An
        // administrative entry with no act at all was invisible to a check that
        // only ever walked the acts, so a revocation whose act was dropped in
        // transit — or deleted from the database — simply stopped counting, and
        // the grant it withdrew went on standing. A history missing its last
        // restrictive step cannot be told apart from one that never had it.
        //
        // Only the owner's own administrative bodies are in [requirements]; the
        // peer's acceptance and their own device are carried by their signature
        // and need no act from us.
        requirements.forEach { (subject, required) ->
            if (subject !in bySubject) {
                return "entry ${subject.value} is a ${required.effect} change in scope " +
                    "${required.scope.value} that no attestation authorises"
            }
        }

        val byId = distinct.associateBy { it.id }

        distinct.forEach { act ->
            val parent = act.parent ?: return@forEach
            val parentAct = byId[parent]
                ?: return "attestation ${act.id.value} follows ${parent.value}, which is not stored"
            // A scope's causality is its own. An act claiming to follow one in
            // another scope would import an unrelated subject's ordering into
            // this one, which is how a device revocation would come to
            // supersede a grant.
            if (parentAct.scope != act.scope) {
                return "attestation ${act.id.value} follows an attestation in another scope"
            }
        }

        // No cycles. A loop is not merely malformed: every act in it looks
        // superseded, so the scope would silently resolve to nothing.
        distinct.forEach { act ->
            val seen = mutableSetOf(act.id)
            var cursor = act.parent
            while (cursor != null) {
                if (!seen.add(cursor)) return "the attestation chain through ${act.id.value} contains a cycle"
                cursor = byId[cursor]?.parent
            }
        }

        // Entries: a parent that exists, no cycles, and a sequence that follows
        // it. Two entries forking on one parent legitimately share a number —
        // that is what a fork is — so the number is checked against the parent
        // rather than against its siblings.
        val shape = entryShape()
        shape.forEach { (id, parentAndSequence) ->
            val (parent, sequence) = parentAndSequence
            if (parent == null) {
                if (sequence != 1L) return "entry ${id.value} begins a history at sequence $sequence, not 1"
                return@forEach
            }
            val parentShape = shape[parent]
                ?: return "entry ${id.value} follows ${parent.value}, which is not stored"
            if (sequence != parentShape.second + 1L) {
                return "entry ${id.value} has sequence $sequence, which does not follow its parent's " +
                    "${parentShape.second}"
            }
        }
        shape.keys.forEach { id ->
            val seen = mutableSetOf(id)
            var cursor = shape[id]?.first
            while (cursor != null) {
                if (!seen.add(cursor)) return "the entry chain through ${id.value} contains a cycle"
                cursor = shape[cursor]?.first
            }
        }

        return null
    }

    /**
     * The events a scope's acts describe, keyed by the entries they authorise.
     *
     * The parent is the **signed** one: the act names the act it was built on,
     * and that act names its subject. Walking the entry chain for a "nearest
     * ancestor that shares a scope" instead was a guess assembled from unsigned
     * structure — entries interleave scopes, so which ancestor came nearest
     * depended on what unrelated changes happened to be written in between.
     */
    fun eventsFor(
        acts: List<AuthorityAttestation>,
        scope: AuthorityScope,
    ): List<AuthorityEvent> {
        val byId = acts.distinctBy { it.id }.associateBy { it.id }
        return acts.distinctBy { it.id }.filter { it.scope == scope }.map { act ->
            AuthorityEvent(
                eventId = act.subject,
                parent = act.parent?.let { byId[it]?.subject },
                authorityGeneration = act.authorityGeneration,
                device = act.device,
                effect = act.effect,
            )
        }
    }
}
