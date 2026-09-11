package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §11: what an act is allowed to *claim* about the change it
 * authorises.
 *
 * ## The hole this closes
 *
 * An attestation carries a `capability`, and admission checked that value
 * against the authoring device's role. Both halves of that check are things a
 * device supplies: a `READ_ONLY` device could write `capability = READ` — which
 * its role genuinely allows — pass the check, and attach the act to a
 * `GrantChanged`. The ledger would then hold a permission change whose only
 * authority was an act that claimed to be a read.
 *
 * So the scope, effect and capability are **derived from the signed body of the
 * entry being authorised**, and the act has to match. A device cannot influence
 * the requirement: it is a pure function of content the owner's key has already
 * signed. The value on the attestation is then a *claim to be checked* rather
 * than a fact to be trusted.
 *
 * ## Peer consent is not owner authority
 *
 * A body the peer authored — their acceptance, their refusal, their own device
 * — needs no owner-device act. It is carried by the peer's signature, and
 * demanding an owner act for it would mean the owner authorising somebody
 * else's decision, which is the opposite of what consent is. Those bodies
 * return `null`, and the ledger's own signer rules still apply to them.
 */
object AuthorityPairing {

    /** What a body demands of whatever act claims to authorise it. */
    data class Required(
        val scope: AuthorityScope,
        val effect: AuthorityEffect,
        val capability: DeviceCapability,
    )

    sealed interface Verdict {
        data class Paired(val act: AuthorityAttestation) : Verdict
        data class Refused(val reason: String) : Verdict
    }

    /**
     * `null` when the body is the peer's rather than the owner's, and so needs
     * no act from one of the owner's devices.
     */
    fun requiredFor(peer: PeerId, body: SharingLedgerBody): Required? = when (body) {
        // --- the peer's own, carried by the peer's signature
        is SharingLedgerBody.RecipientAccepted,
        SharingLedgerBody.RecipientDeclined,
        is SharingLedgerBody.DeviceAuthorized,
        -> null

        // --- about one of the peer's devices
        is SharingLedgerBody.DeviceRevoked -> Required(
            AuthorityScope.peerDevice(peer, body.deviceId),
            AuthorityEffect.RESTRICTIVE,
            DeviceCapability.MUTATE_PERMISSIONS,
        )

        is SharingLedgerBody.KeyDeliveryUnclear -> Required(
            AuthorityScope.peerDevice(peer, body.deviceId),
            AuthorityEffect.RESTRICTIVE,
            DeviceCapability.MUTATE_PERMISSIONS,
        )

        is SharingLedgerBody.KeyDeliveryConfirmed -> Required(
            AuthorityScope.peerDevice(peer, body.deviceId),
            AuthorityEffect.PERMISSIVE,
            DeviceCapability.MUTATE_PERMISSIONS,
        )

        // --- about the relationship as a whole
        is SharingLedgerBody.PeerCircleAssigned,
        is SharingLedgerBody.RelationshipOffered,
        is SharingLedgerBody.GrantChanged,
        -> Required(
            AuthorityScope.peer(peer),
            AuthorityEffect.PERMISSIVE,
            DeviceCapability.MUTATE_PERMISSIONS,
        )

        SharingLedgerBody.RelationshipRevoked -> Required(
            AuthorityScope.peer(peer),
            AuthorityEffect.RESTRICTIVE,
            DeviceCapability.MUTATE_PERMISSIONS,
        )

        SharingLedgerBody.PurgeRequested, SharingLedgerBody.PurgeCompleted -> Required(
            AuthorityScope.peer(peer),
            AuthorityEffect.RESTRICTIVE,
            DeviceCapability.MUTATE_PERMISSIONS,
        )

        // --- recovery, which is device administration
        is SharingLedgerBody.ResourceEpochAdvanced, is SharingLedgerBody.RestoreCompleted -> Required(
            AuthorityScope.peer(peer),
            AuthorityEffect.RESTRICTIVE,
            DeviceCapability.ADMINISTER_DEVICES,
        )

        // An unreadable body may be anything at all, so it may only be
        // authorised by a device that could have authorised anything.
        is SharingLedgerBody.Unknown -> Required(
            AuthorityScope.peer(peer),
            AuthorityEffect.RESTRICTIVE,
            DeviceCapability.SOVEREIGN_RESET,
        )
    }

    fun requiredFor(body: OwnerPolicyBody): Required? = when (body) {
        is OwnerPolicyBody.CircleBaselineSet -> Required(
            AuthorityScope.baseline(body.circle, body.category),
            if (body.granted) AuthorityEffect.PERMISSIVE else AuthorityEffect.RESTRICTIVE,
            DeviceCapability.MUTATE_PERMISSIONS,
        )

        is OwnerPolicyBody.PeerRuleSet -> Required(
            AuthorityScope.category(body.peer, body.category),
            // Clearing a rule widens access back to the baseline, so it is not
            // restrictive; only an explicit DENY is.
            if (body.effect == AccessEffect.DENY) AuthorityEffect.RESTRICTIVE else AuthorityEffect.PERMISSIVE,
            DeviceCapability.MUTATE_PERMISSIONS,
        )

        is OwnerPolicyBody.ObjectRuleSet -> Required(
            AuthorityScope.objectRule(body.peer, body.objectId),
            if (body.effect == AccessEffect.DENY) AuthorityEffect.RESTRICTIVE else AuthorityEffect.PERMISSIVE,
            DeviceCapability.MUTATE_PERMISSIONS,
        )

        is OwnerPolicyBody.Unknown -> Required(
            AuthorityScope.estate(),
            AuthorityEffect.RESTRICTIVE,
            DeviceCapability.SOVEREIGN_RESET,
        )
    }

    /**
     * A change about one device belongs to that device's own scope.
     *
     * Only the estate-wide moves — a rotation, a sovereign reset — are about
     * the estate as a whole. Putting every device change in one scope made
     * enrolling a tablet and revoking a phone compete, so one of them vanished.
     */
    fun requiredFor(body: DeviceManifestBody): Required? = when (body) {
        is DeviceManifestBody.DeviceEnrolled -> Required(
            AuthorityScope.ownerDevice(body.device),
            AuthorityEffect.PERMISSIVE,
            DeviceCapability.ADMINISTER_DEVICES,
        )

        is DeviceManifestBody.DeviceRoleChanged -> Required(
            AuthorityScope.ownerDevice(body.device),
            // A role change can go either way, and the direction is what
            // decides against a concurrent revocation. Read off the signed
            // body: anything short of the authority PRIMARY carries is a
            // reduction, and a reduction must not lose to a promotion.
            if (body.role == DeviceRole.PRIMARY) AuthorityEffect.PERMISSIVE else AuthorityEffect.RESTRICTIVE,
            DeviceCapability.ADMINISTER_DEVICES,
        )

        is DeviceManifestBody.DeviceRevoked -> Required(
            AuthorityScope.ownerDevice(body.device),
            AuthorityEffect.RESTRICTIVE,
            DeviceCapability.ADMINISTER_DEVICES,
        )

        is DeviceManifestBody.AuthorityRotated -> Required(
            AuthorityScope.estate(),
            AuthorityEffect.RESTRICTIVE,
            DeviceCapability.ADMINISTER_DEVICES,
        )

        DeviceManifestBody.SovereignReset -> Required(
            AuthorityScope.estate(),
            AuthorityEffect.RESTRICTIVE,
            DeviceCapability.SOVEREIGN_RESET,
        )

        is DeviceManifestBody.Unknown -> Required(
            AuthorityScope.estate(),
            AuthorityEffect.RESTRICTIVE,
            DeviceCapability.SOVEREIGN_RESET,
        )
    }

    /**
     * Finds the one act that authorises [subject], or says why none does.
     *
     * Exactly one, deliberately. Two acts for one entry would let a second,
     * weaker act ride along on a valid one and sit in the ledger as evidence
     * that a device authorised something it never did.
     */
    @Suppress("ReturnCount")
    fun pair(
        required: Required,
        subject: LedgerEntryId,
        acts: List<AuthorityAttestation>,
    ): Verdict {
        val forSubject = acts.filter { it.subject == subject }
        if (forSubject.isEmpty()) {
            return Verdict.Refused("no attestation authorises entry ${subject.value}")
        }
        if (forSubject.size > 1) {
            return Verdict.Refused(
                "entry ${subject.value} must be authorised by exactly one attestation, found ${forSubject.size}",
            )
        }

        val act = forSubject.single()
        if (!act.scope.isCurrentVersion) {
            return Verdict.Refused(
                "attestation ${act.id.value} names a scope from the superseded encoding",
            )
        }
        if (act.scope != required.scope) {
            return Verdict.Refused(
                "attestation ${act.id.value} names scope ${act.scope.value}, " +
                    "but the signed body requires ${required.scope.value}",
            )
        }
        if (act.effect != required.effect) {
            return Verdict.Refused(
                "attestation ${act.id.value} claims ${act.effect}, but the signed body is ${required.effect}",
            )
        }
        if (act.capability != required.capability) {
            return Verdict.Refused(
                "attestation ${act.id.value} claims capability ${act.capability}, " +
                    "but the signed body requires ${required.capability}",
            )
        }
        return Verdict.Paired(act)
    }
}
