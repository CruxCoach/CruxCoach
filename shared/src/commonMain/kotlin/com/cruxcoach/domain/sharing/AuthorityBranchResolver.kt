package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §11: which branch of a history actually decides access.
 *
 * ## Why this exists
 *
 * The resolver used to decide which *act* stood, while the projection was
 * folded along a single linear chain that never consulted it. That made the
 * model decorative, and worse: two of the owner's devices could not both record
 * a decision, because the second to arrive collided on the next sequence and
 * was thrown away. Arrival order decided who could see what.
 *
 * ## Only same-scope conflicts are conflicts
 *
 * Resolving every act in one pool was the other half of the same mistake. A
 * revocation about *one* of a peer's devices would delete an unrelated grant,
 * because restrictive-beats-permissive was applied across subjects that never
 * competed. That rule exists to settle two changes to the *same* thing.
 *
 * So [DeviceAuthorityResolver] is applied **per scope**: each scope resolves
 * its own winner, the winner's causal chain within that scope is its effective
 * history, and the scopes are then unioned. Two baselines, a grant and a device
 * revocation, two revocations of different devices — all independent, all kept.
 * Two changes to one baseline, or one device, still leave exactly one standing.
 *
 * ## How an entry becomes an event
 *
 * Every owner-administrative entry is paired with exactly one act, and that act
 * carries the terms the ordering needs: which device, under which generation,
 * with which effect. The effect is derived from the signed body by
 * [AuthorityPairing], so a device cannot win by calling its grant a revocation.
 *
 * A peer-authored entry — their acceptance, their refusal, their own device —
 * has no owner act and cannot be ranked. It is not a competing claim about the
 * owner's authority either: it answers whatever it was built on, so it rides
 * the branch its parent is on and heads nothing by itself.
 *
 * ## Why the acts arrive as [TrustedAttestations]
 *
 * Because nothing here can check a signature, and something has to. Taking a
 * plain list would mean every call site was one forgotten check away from
 * resolving rows nobody signed; taking the checked type means the check has
 * already happened, against the manifest that travels with it.
 */
object AuthorityBranchResolver {

    /**
     * The entry that ends the branch that stands **in [scope]**, or null.
     *
     * A scope's causality is its own: an entry about a peer's laptop does not
     * supersede one about their phone just because it came later.
     */
    fun head(
        entries: List<SharingLedgerEntry>,
        acts: TrustedAttestations,
        scope: AuthorityScope,
    ): SharingLedgerEntry? {
        if (validate(entries, acts) != null) return null
        val byId = entries.distinctBy { it.id }.associateBy { it.id }
        val events = AuthorityDagValidation.eventsFor(acts.acts, scope).filter { byId.containsKey(it.eventId) }
        if (events.isEmpty()) return null
        val winner = DeviceAuthorityResolver.resolve(events, acts.manifest, acts.roleOf) ?: return null
        return byId[winner.eventId]
    }

    /** The one check both this and [AuthorityLedger] run before deciding. */
    private fun validate(entries: List<SharingLedgerEntry>, acts: TrustedAttestations): String? {
        acts.failClosedReason?.let { return it }
        // Only the acts about *these* entries. The caller holds every act the
        // install has, including the other ledger's, and those name subjects
        // this one has never heard of. Act parents are same-scope and a scope
        // belongs to one ledger, so nothing relevant is left out — a parent
        // whose subject is missing still fails the check below.
        val mine = acts.acts.filter { it.subject in entries.map { e -> e.id }.toSet() }
        return AuthorityDagValidation.check(
            acts = mine,
            knownSubjects = entries.map { it.id }.toSet(),
            requirements = entries.mapNotNull { e ->
                AuthorityPairing.requiredFor(e.peer, e.body)?.let { e.id to it }
            }.toMap(),
        ) { entries.associate { e -> e.id to (e.parent to e.policySequence) } }
    }

    private fun validatePolicy(entries: List<OwnerPolicyEntry>, acts: TrustedAttestations): String? {
        acts.failClosedReason?.let { return it }
        val mine = acts.acts.filter { it.subject in entries.map { e -> e.id }.toSet() }
        return AuthorityDagValidation.check(
            acts = mine,
            knownSubjects = entries.map { it.id }.toSet(),
            requirements = entries.mapNotNull { e ->
                AuthorityPairing.requiredFor(e.body)?.let { e.id to it }
            }.toMap(),
        ) { entries.associate { e -> e.id to (e.parent to e.policySequence) } }
    }

    /**
     * The whole authorised history: every scope's winning chain, unioned.
     *
     * Ordered by sequence then id — canonical, so every device folds the same
     * list in the same order — with each peer reply emitted straight after the
     * entry it answers.
     */
    fun history(
        entries: List<SharingLedgerEntry>,
        acts: TrustedAttestations,
    ): List<SharingLedgerEntry> {
        // A malformed DAG decides nothing. Resolving what is left would hand
        // somebody a truncated history, which looks exactly like a revocation
        // that was quietly dropped.
        if (validate(entries, acts) != null) return emptyList()
        val byId = entries.distinctBy { it.id }.associateBy { it.id }
        val actBySubject = acts.acts.distinctBy { it.id }.associateBy { it.subject }
        val actById = acts.acts.distinctBy { it.id }.associateBy { it.id }

        val included = linkedSetOf<LedgerEntryId>()
        // Each scope decides its own branch, and nothing else's. The chain is
        // walked along the *signed* act parents.
        actBySubject.values
            .filter { byId.containsKey(it.subject) }
            .map { it.scope }
            .distinct()
            .sortedBy { it.value }
            .forEach { scope ->
                var cursor = head(entries, acts, scope)
                val seen = mutableSetOf<LedgerEntryId>()
                while (cursor != null && seen.add(cursor.id)) {
                    included += cursor.id
                    cursor = actBySubject[cursor.id]?.parent
                        ?.let { actById[it] }?.subject?.let { byId[it] }
                }
            }

        // A peer's reply is carried by their own signature and answers the
        // entry it was built on. Transitively, because a peer can reply to
        // their own reply — accept, then authorise a second device.
        val replies = mutableListOf<SharingLedgerEntry>()
        var grew = true
        while (grew) {
            grew = false
            byId.values.forEach { entry ->
                if (entry.id in included || actBySubject.containsKey(entry.id)) return@forEach
                val parent = entry.parent ?: return@forEach
                if (parent !in included) return@forEach
                included += entry.id
                replies += entry
                grew = true
            }
        }

        val repliesByParent = replies.groupBy { it.parent }
        val ordered = mutableListOf<SharingLedgerEntry>()
        val emitted = mutableSetOf<LedgerEntryId>()
        fun emit(entry: SharingLedgerEntry) {
            if (!emitted.add(entry.id)) return
            ordered += entry
            repliesByParent[entry.id]?.sortedBy { it.id.value }?.forEach { emit(it) }
        }
        included.mapNotNull { byId[it] }
            .filter { it.id !in replies.map { reply -> reply.id }.toSet() }
            .sortedWith(compareBy({ it.policySequence }, { it.id.value }))
            .forEach { emit(it) }
        return ordered
    }

    /**
     * The authorised owner-policy history, every scope's winning chain unioned.
     *
     * Same rule as the relationship ledger: a baseline and a per-peer rule are
     * different subjects and never compete, while two changes to one baseline
     * do. The policy has no peer replies, so this is only the union.
     */
    fun policyHistory(
        entries: List<OwnerPolicyEntry>,
        acts: TrustedAttestations,
    ): List<OwnerPolicyEntry> {
        if (validatePolicy(entries, acts) != null) return emptyList()
        val byId = entries.distinctBy { it.id }.associateBy { it.id }
        val actBySubject = acts.acts.distinctBy { it.id }.associateBy { it.subject }
        val actById = acts.acts.distinctBy { it.id }.associateBy { it.id }

        val included = linkedSetOf<LedgerEntryId>()
        actBySubject.values
            .filter { byId.containsKey(it.subject) }
            .map { it.scope }
            .distinct()
            .sortedBy { it.value }
            .forEach { scope ->
                val events = AuthorityDagValidation.eventsFor(acts.acts, scope)
                    .filter { byId.containsKey(it.eventId) }
                if (events.isEmpty()) return@forEach
                var cursor = DeviceAuthorityResolver.resolve(events, acts.manifest, acts.roleOf)
                    ?.let { byId[it.eventId] }
                val seen = mutableSetOf<LedgerEntryId>()
                while (cursor != null && seen.add(cursor.id)) {
                    included += cursor.id
                    cursor = actBySubject[cursor.id]?.parent
                        ?.let { actById[it] }?.subject?.let { byId[it] }
                }
            }

        return included.mapNotNull { byId[it] }
            .sortedWith(compareBy({ it.policySequence }, { it.id.value }))
    }

    // The old "nearest ancestor that happens to share a scope" helpers are
    // gone. Causality is what a device signed, not what the entry chain
    // implies — see AuthorityDagValidation.eventsFor.

    /**
     * The entry a new one should build on: the deepest the history reaches.
     *
     * Continuing the *losing* branch — the one with the highest sequence — is
     * how a long abandoned branch keeps pushing the numbering forward while the
     * winner stays where it is.
     */
    fun tip(
        entries: List<SharingLedgerEntry>,
        acts: TrustedAttestations,
    ): SharingLedgerEntry? = history(entries, acts).lastOrNull()

    fun policyTip(
        entries: List<OwnerPolicyEntry>,
        acts: TrustedAttestations,
    ): OwnerPolicyEntry? = policyHistory(entries, acts).lastOrNull()
}
