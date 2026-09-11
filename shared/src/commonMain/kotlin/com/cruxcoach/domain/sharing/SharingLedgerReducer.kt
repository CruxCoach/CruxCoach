package com.cruxcoach.domain.sharing

/**
 * Reduces a set of ledger entries into a [SharingProjection].
 *
 * The reduction is **idempotent** and **order-independent**: applying the same
 * entries twice, or in any order, yields the same projection. That is what lets
 * the same code run over a live feed, a replay and a restored backup.
 *
 * It is also **fail-closed**. Anything the reducer cannot decide safely —
 * a bad signature, a sequence gap, two entries claiming one sequence, one id
 * carrying two different bodies, an epoch moving backwards, an entry kind this
 * build does not know — puts that relationship into
 * [RelationshipStatus.FAIL_CLOSED], which releases nothing. Silence is never
 * read as consent and a hole is never interpolated.
 *
 * A verified signature proves only that the bytes were not altered; it says
 * nothing about authority. So every entry is additionally checked against the
 * role its body requires: owner actions must carry [ownerNpub], and only the
 * peer may accept or decline on their own behalf. Without that, the owner could
 * sign the recipient's consent — consent forged by the party it exists to
 * constrain.
 */
class SharingLedgerReducer(
    private val verifier: LedgerSignatureVerifier,
    private val ownerNpub: String,
) {

    /**
     * Reduces one peer along the branch the resolver picks.
     *
     * This is the seam the whole authority model hangs on. Handing the reducer
     * every entry and letting it fold them in sequence order is what let the
     * losing branch of an offline fork decide somebody's access — the entries
     * were all there, so the last one written won. Selecting the history first,
     * with [AuthorityBranchResolver], is what makes the resolver's answer the
     * answer.
     */
    fun reduce(
        peer: PeerId,
        entries: List<SharingLedgerEntry>,
        acts: TrustedAttestations,
    ): RelationshipState {
        val mine = entries.filter { it.peer == peer }
        if (mine.isEmpty()) return RelationshipState(peer = peer, status = RelationshipStatus.PENDING)
        acts.failClosedReason?.let { return failed(peer, it) }
        val history = AuthorityBranchResolver.history(mine, acts)
        if (history.isEmpty()) {
            return failed(
                peer,
                "no branch of this relationship's history is authorised by a device the manifest vouches for",
            )
        }
        return reducePeer(peer, history)
    }

    /** The whole projection, one authorised branch per peer. */
    fun reduce(
        entries: List<SharingLedgerEntry>,
        acts: TrustedAttestations,
    ): SharingProjection = SharingProjection(
        relationships = entries
            .groupBy { it.peer }
            .mapValues { (peer, peerEntries) -> reduce(peer, peerEntries, acts) },
    )

    fun reduce(entries: List<SharingLedgerEntry>): SharingProjection =
        SharingProjection(
            relationships = entries
                .groupBy { it.peer }
                .mapValues { (peer, peerEntries) -> reducePeer(peer, peerEntries) },
        )

    private fun reducePeer(peer: PeerId, entries: List<SharingLedgerEntry>): RelationshipState {
        // 1. Collapse exact duplicates; a repeated id carrying different content
        //    means two different histories claim one identity.
        val byId = LinkedHashMap<LedgerEntryId, SharingLedgerEntry>()
        for (e in entries) {
            val existing = byId[e.id]
            if (existing != null) {
                if (existing != e) return failed(peer, "duplicate entry id ${e.id.value} with divergent content")
                continue
            }
            byId[e.id] = e
        }
        val unique = byId.values.toList()

        // 2. Every entry must be authentic before it is allowed to mean anything.
        unique.firstOrNull { !verifier.verify(it) }?.let {
            return failed(peer, "signature verification failed for entry ${it.id.value}")
        }

        // 3. Canonical order: sequence, then id.
        //
        // The old strict, gap-free requirement was a linear chain's invariant
        // and the DAG replaces it. Entries from independent scopes legitimately
        // share a number — a grant about a peer and a revocation about one of
        // their devices are numbered from the same parent — and a scope whose
        // branch lost leaves a gap behind. What actually guarantees integrity
        // is admission (a parent must exist and the sequence must follow it)
        // and branch selection, which is where causality is decided.
        val ordered = unique.sortedWith(compareBy({ it.policySequence }, { it.id.value }))

        // 4. Every entry must come from a signer the body actually authorises.
        ordered.firstOrNull { !isAuthorised(it, peer) }?.let {
            return failed(
                peer,
                "entry ${it.id.value} signed by ${it.signerNpub}, which is not authorised for ${bodyKind(it.body)}",
            )
        }

        // 5. Fold.
        var state = RelationshipState(peer = peer, status = RelationshipStatus.PENDING)
        for (e in ordered) {
            if (e.authorityGeneration < state.authorityGeneration) continue // superseded, not an error
            if (e.resourceEpoch < state.resourceEpoch) {
                return failed(peer, "resource epoch moved backwards at sequence ${e.policySequence}")
            }

            // The device generation is minted by a restore and by nothing else.
            // An entry claiming any other value is either stale or forged.
            val restore = e.body as? SharingLedgerBody.RestoreCompleted
            if (restore != null) {
                if (restore.newDeviceGeneration != e.deviceGeneration) {
                    return failed(
                        peer,
                        "restore at sequence ${e.policySequence} claims device generation " +
                            "${restore.newDeviceGeneration} in an envelope of ${e.deviceGeneration}",
                    )
                }
                if (e.deviceGeneration <= state.deviceGeneration) {
                    return failed(
                        peer,
                        "restore at sequence ${e.policySequence} does not advance the device generation",
                    )
                }
            } else if (e.deviceGeneration != state.deviceGeneration) {
                return failed(
                    peer,
                    "device generation ${e.deviceGeneration} at sequence ${e.policySequence} " +
                        "does not match the established ${state.deviceGeneration}",
                )
            }

            // A new authority generation starts from nothing. Inheriting the old
            // consent and devices would let re-establishing authority silently
            // re-grant everything the previous one had.
            if (e.authorityGeneration > state.authorityGeneration) {
                state = state.copy(
                    status = if (state.status.isTerminal) state.status else RelationshipStatus.PENDING,
                    offeredCategories = emptySet(),
                    consentedCategories = emptySet(),
                    pendingConsentCategories = emptySet(),
                    authorisedDevices = emptySet(),
                    // revokedDevices deliberately survives: a tombstone is not
                    // an artefact of the authority that issued it.
                )
            }

            state = state.copy(
                authorityGeneration = maxOf(state.authorityGeneration, e.authorityGeneration),
                resourceEpoch = maxOf(state.resourceEpoch, e.resourceEpoch),
                lastSequence = e.policySequence,
            )
            state = apply(state, e) ?: return failed(
                peer,
                "unreducible entry at sequence ${e.policySequence}",
            )
        }
        return state
    }

    /**
     * Who is allowed to say this.
     *
     * Only the peer may answer an offer on their own behalf; everything else is
     * the owner speaking about their own data. An [SharingLedgerBody.Unknown]
     * body has no known role, so it is refused here too and fails closed for the
     * same reason it does in [apply].
     */
    private fun isAuthorised(entry: SharingLedgerEntry, peer: PeerId): Boolean = when (entry.body) {
        // The peer answers for themselves, and proves a device is theirs.
        // If the owner could authorise a device, "every device is authorised
        // separately" would mean nothing — the owner would just name one.
        is SharingLedgerBody.RecipientAccepted,
        SharingLedgerBody.RecipientDeclined,
        is SharingLedgerBody.DeviceAuthorized,
        -> entry.signerNpub == peer.value

        is SharingLedgerBody.Unknown -> false

        // Revocation is deliberately *not* symmetric: withdrawing access is the
        // owner's, and must not need the cooperation of the device losing it.
        else -> entry.signerNpub == ownerNpub
    }

    private fun bodyKind(body: SharingLedgerBody): String = when (body) {
        is SharingLedgerBody.RecipientAccepted -> "a recipient acceptance"
        SharingLedgerBody.RecipientDeclined -> "a recipient decline"
        is SharingLedgerBody.DeviceAuthorized -> "a device authorisation"
        is SharingLedgerBody.Unknown -> "an unknown entry kind"
        else -> "an owner action"
    }

    /** Returns `null` when the entry cannot be reduced and must fail closed. */
    private fun apply(state: RelationshipState, e: SharingLedgerEntry): RelationshipState? {
        // A terminal relationship absorbs everything except an explicit purge
        // progression. Nothing revives a revoked, declined or purged grant.
        val terminalAbsorbs = state.status.isTerminal &&
            e.body !is SharingLedgerBody.PurgeRequested &&
            e.body !is SharingLedgerBody.PurgeCompleted &&
            e.body !is SharingLedgerBody.Unknown &&
            e.body !is SharingLedgerBody.ResourceEpochAdvanced
        if (terminalAbsorbs) return state

        return when (val body = e.body) {
            is SharingLedgerBody.PeerCircleAssigned -> state.copy(circle = body.circle)

            is SharingLedgerBody.RelationshipOffered -> state.copy(
                status = if (state.status == RelationshipStatus.ACCEPTED) state.status else RelationshipStatus.PENDING,
                offeredCategories = body.categories,
                pendingConsentCategories = body.categories - state.consentedCategories,
                expiresAt = body.expiresAt,
            )

            is SharingLedgerBody.RecipientAccepted -> {
                // A recipient can only ever consent to what was actually offered.
                val consented = body.categories intersect state.offeredCategories
                val devices = if (body.deviceId in state.revokedDevices) {
                    state.authorisedDevices
                } else {
                    state.authorisedDevices + body.deviceId
                }
                state.copy(
                    status = RelationshipStatus.ACCEPTED,
                    consentedCategories = consented,
                    pendingConsentCategories = state.offeredCategories - consented,
                    authorisedDevices = devices,
                )
            }

            SharingLedgerBody.RecipientDeclined -> state.copy(
                status = RelationshipStatus.DECLINED,
                consentedCategories = emptySet(),
                pendingConsentCategories = emptySet(),
                authorisedDevices = emptySet(),
            )

            is SharingLedgerBody.GrantChanged -> {
                // Narrowing is immediate and needs nobody's agreement; widening
                // only becomes pending, never released.
                val consented = state.consentedCategories intersect body.categories
                state.copy(
                    offeredCategories = body.categories,
                    consentedCategories = consented,
                    pendingConsentCategories = body.categories - consented,
                )
            }

            is SharingLedgerBody.DeviceAuthorized ->
                if (body.deviceId in state.revokedDevices) state // sticky tombstone
                else state.copy(authorisedDevices = state.authorisedDevices + body.deviceId)

            is SharingLedgerBody.DeviceRevoked -> state.copy(
                authorisedDevices = state.authorisedDevices - body.deviceId,
                revokedDevices = state.revokedDevices + body.deviceId,
            )

            is SharingLedgerBody.KeyDeliveryUnclear ->
                if (state.status == RelationshipStatus.ACCEPTED) {
                    state.copy(status = RelationshipStatus.DELIVERY_UNCLEAR)
                } else state

            is SharingLedgerBody.KeyDeliveryConfirmed ->
                if (state.status == RelationshipStatus.DELIVERY_UNCLEAR) {
                    state.copy(status = RelationshipStatus.ACCEPTED)
                } else state

            SharingLedgerBody.RelationshipRevoked -> state.copy(
                status = RelationshipStatus.REVOKED,
                consentedCategories = emptySet(),
                pendingConsentCategories = emptySet(),
                authorisedDevices = emptySet(),
            )

            SharingLedgerBody.PurgeRequested -> state.copy(status = RelationshipStatus.PURGE_PENDING)

            SharingLedgerBody.PurgeCompleted -> state.copy(status = RelationshipStatus.PURGED)

            is SharingLedgerBody.ResourceEpochAdvanced ->
                if (body.epoch < state.resourceEpoch) null
                else state.copy(resourceEpoch = body.epoch)

            is SharingLedgerBody.RestoreCompleted -> state.copy(
                deviceGeneration = body.newDeviceGeneration,
                // A restore never inherits device rights and never re-activates
                // a device the pre-restore history had revoked.
                authorisedDevices = emptySet(),
                awaitingRevokeSync = true,
            )

            is SharingLedgerBody.Unknown -> null
        }
    }

    private fun failed(peer: PeerId, reason: String) = RelationshipState(
        peer = peer,
        status = RelationshipStatus.FAIL_CLOSED,
        failClosedReason = reason,
    )
}
