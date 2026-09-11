package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §11: acts are evidence, and evidence is re-checked every time it is
 * read.
 *
 * ## The hole this closes
 *
 * [AuthorityAttestationAdmission] verifies a signature once, on the way in, and
 * the row was then believed for ever. But the check happens in our process,
 * against a table anything with write access can reach: a restored backup, a
 * rooted device, a bug in our own code, a hand-edited database. Put a row in by
 * any of those routes and every read afterwards takes it as evidence that one
 * of the owner's devices authorised a permission change. "It was checked when
 * it was written" is a promise about a past that the file itself does not
 * record.
 *
 * So the signature is checked again on **every** read, against the public key
 * the *manifest* binds to that device — never one carried on the act, which
 * would let an attacker supply both halves of the check.
 *
 * ## Why a failure costs the whole estate
 *
 * Skipping the act would be the same truncation
 * [AuthorityDagValidation] exists to prevent: a history missing a restrictive
 * step is indistinguishable from one that never had it, so the older grant
 * underneath would quietly stand. Nothing is resolved instead, and nothing is
 * released.
 *
 * ## Why the type, and not just a function
 *
 * Everything that decides access takes [TrustedAttestations] rather than a
 * `List<AuthorityAttestation>`, and the only way to obtain one is [of], which
 * needs a verifier and the manifest to check against. A caller cannot reach the
 * resolver holding acts it has not had checked — not by forgetting, not by
 * refactoring, not by adding a new call site later. The manifest travels inside
 * the same value for the same reason: acts verified against one manifest and
 * then resolved against another is a mismatch nobody would see.
 */
class TrustedAttestations private constructor(
    /** The manifest the acts were checked against, and are resolved against. */
    val manifest: DeviceAuthorityState,
    /** Empty whenever [failClosedReason] is set: an unreadable estate decides nothing. */
    val acts: List<AuthorityAttestation>,
    /** Why these acts cannot be trusted, or null. */
    val failClosedReason: String?,
    /**
     * The role each act's author held **at the manifest point that act names**,
     * keyed by the entry it authorises.
     *
     * Established here, from evidence already checked, and it is what the
     * resolvers order by. Looking the rank up in the manifest as it stands
     * instead made an unrelated later change rewrite a settled decision: a
     * device that has since been revoked had its denials dropped — the
     * withdrawal disappears and the grant underneath stands again — and a
     * demotion changed which of two concurrent changes won, months after both
     * were signed. Ending a device's authority stops it saying anything new;
     * it does not un-say what it already said.
     */
    private val rolesBySubject: Map<LedgerEntryId, DeviceRole>,
) {

    /**
     * The role the act authorising [subject] was made under, or `null` if
     * nothing here vouches for it.
     *
     * `null` is the fail-closed answer and the resolvers drop the event: an
     * event whose author this reader cannot place must not be ranked at all.
     */
    fun roleFor(subject: LedgerEntryId): DeviceRole? = rolesBySubject[subject]

    /** The lookup every resolver over these acts is ordered by. */
    val roleOf: (AuthorityEvent) -> DeviceRole? get() = { roleFor(it.eventId) }

    companion object {

        fun of(
            acts: List<AuthorityAttestation>,
            manifest: DeviceAuthorityState,
            verifier: AuthorityAttestationVerifier,
        ): TrustedAttestations {
            val roles = mutableMapOf<LedgerEntryId, DeviceRole>()
            val reason = untrustworthy(acts, manifest, verifier, roles)
            return TrustedAttestations(
                manifest = manifest,
                acts = if (reason == null) acts else emptyList(),
                failClosedReason = reason,
                rolesBySubject = if (reason == null) roles.toMap() else emptyMap(),
            )
        }

        /**
         * Why these acts may not be trusted under [manifest], or null.
         *
         * The order is deliberate: an unreadable manifest binds no keys at all,
         * and a device it does not name has no key to check against, so both
         * are settled before any signature is looked at.
         */
        @Suppress("ReturnCount")
        private fun untrustworthy(
            acts: List<AuthorityAttestation>,
            manifest: DeviceAuthorityState,
            verifier: AuthorityAttestationVerifier,
            roles: MutableMap<LedgerEntryId, DeviceRole>,
        ): String? {
            if (acts.isEmpty()) return null
            manifest.failClosedReason?.let {
                return "the device manifest is not readable, so no act can be checked against it: $it"
            }

            acts.distinctBy { it.id }.forEach { act ->
                // The point in the manifest the device signed against. Not
                // "the manifest now": a demotion, a revocation or a recovery
                // moves that, and an act would stop verifying because the
                // estate moved on rather than because anything is wrong with
                // it. The signed head is the only thing that says what its
                // author could actually see.
                val asOf = manifest.at(act.manifestContext)
                asOf.failClosedReason?.let {
                    return "attestation ${act.id.value} was authored against a manifest context " +
                        "nobody can reconstruct: $it"
                }

                val record = asOf.devices[act.device]
                    ?: return "attestation ${act.id.value} claims device ${act.device.value}, " +
                        "which the manifest did not name at ${act.manifestContext.canonical}"

                val role = asOf.roleOf(act.device)
                    ?: return "attestation ${act.id.value} was authored by ${act.device.value}, " +
                        "which held no authority at ${act.manifestContext.canonical}"
                if (!role.capabilities.contains(act.capability)) {
                    return "attestation ${act.id.value} needs ${act.capability}, " +
                        "which device ${act.device.value} did not hold as $role"
                }

                if (act.authorityGeneration != asOf.authorityGeneration) {
                    return "attestation ${act.id.value} claims authority generation " +
                        "${act.authorityGeneration}, but its manifest head establishes " +
                        "${asOf.authorityGeneration}"
                }

                if (!verifier.verify(act, record.publicKey)) {
                    return "attestation ${act.id.value} does not verify under the key the manifest " +
                        "binds to ${act.device.value}"
                }

                // Kept, because this is what the act is ordered by. Every
                // other term the resolver uses is signed content; this one is
                // looked up, so it has to be looked up once, here, against the
                // point the act names — and never again against the estate as
                // it stands.
                roles[act.subject] = role
            }
            return null
        }
    }
}
