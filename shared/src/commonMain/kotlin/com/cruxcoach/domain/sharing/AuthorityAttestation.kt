package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §11: the device-signed, parent-linked record of every administrative
 * act.
 *
 * The manifest says which of the owner's devices exist and what each may do.
 * This is what they actually did: one attestation per administrative change,
 * signed by the authoring device's own key and linked to the act it was built
 * on. Together they are the evidence [DeviceAuthorityResolver] decides from.
 *
 * ## Why this is a DAG where the manifest is a line
 *
 * The manifest has exactly one writer — the owner's root identity — so it can
 * demand a strictly linear chain and refuse a fork outright. This ledger has as
 * many writers as the owner has devices, and two of them go offline and both
 * decide something. Refusing whichever arrived second would put relay order in
 * charge of the outcome, which is the thing the resolver exists to eliminate.
 * So concurrent siblings are admitted, and the winner is derived from signed
 * evidence identically everywhere.
 */

/**
 * What an administrative act applies to.
 *
 * Acts sharing a scope compete; acts in different scopes never do. Which makes
 * an ambiguous encoding a privacy bug rather than a tidiness one: scopes are
 * compared by equality, so two different subjects that encode to one string are
 * put into one competition. A restrictive act about your videos could then be
 * beaten by a permissive act about somebody else's.
 *
 * ## Why it is length-prefixed
 *
 * Peer ids and object ids are chosen by other people. Joining them with a
 * separator that can appear inside them is exactly how a peer aims one scope at
 * another: an npub reading `npub1alice|category|VIDEOS` would, under a plain
 * join, land in the scope that decides Alice's videos. A length prefix makes
 * the separator harmless inside a field, because the reader is told how far the
 * field runs before it looks for one.
 *
 * ## Versioning, and what happens to the old encoding
 *
 * The value carries [VERSION]. A scope without it came from the earlier,
 * ambiguous encoding and is deliberately **not** repairable: the whole problem
 * is that the old string does not say which subject it meant. So a legacy scope
 * never equals a current one, decides nothing, and nothing new may be written
 * into it. That is the fail-closed answer; guessing would mean guessing about
 * who can see what.
 */
data class AuthorityScope(val value: String) {

    init { require(value.isNotBlank()) { "AuthorityScope must not be blank" } }

    /** False for the pre-versioned encoding. Such a scope grants nothing. */
    val isCurrentVersion: Boolean get() = value.startsWith(PREFIX)

    companion object {
        private const val VERSION = "ccscope.v2"
        private const val PREFIX = "$VERSION|"

        /**
         * `<version>|<n>:<field><n>:<field>…`
         *
         * The count comes first so a one-field scope cannot be a prefix of a
         * two-field one, and each field is preceded by its length in UTF-8
         * bytes, so no field content can be mistaken for structure.
         */
        private fun encode(vararg fields: String): AuthorityScope = AuthorityScope(
            buildString {
                append(PREFIX)
                append(fields.size)
                append(':')
                fields.forEach { field ->
                    append(field.encodeToByteArray().size)
                    append(':')
                    append(field)
                }
            },
        )

        fun peer(peer: PeerId): AuthorityScope = encode("peer", peer.value)

        fun category(peer: PeerId, category: SharingCategory): AuthorityScope =
            encode("category", peer.value, category.name)

        fun peerDevice(peer: PeerId, device: DeviceId): AuthorityScope =
            encode("peerDevice", peer.value, device.value)

        fun objectRule(peer: PeerId, objectId: ObjectId): AuthorityScope =
            encode("objectRule", peer.value, objectId.value)

        fun baseline(circle: SharingCircle, category: SharingCategory): AuthorityScope =
            encode("baseline", circle.name, category.name)

        /**
         * One of the owner's own devices: its enrolment, role and revocation.
         *
         * Separate from [estate] so two changes to *different* devices never
         * compete. Enrolling a tablet and revoking a phone are independent
         * facts; playing them against each other made whichever arrived second
         * disappear, which is a revocation being lost to an unrelated
         * enrolment.
         */
        fun ownerDevice(device: AuthorityDeviceId): AuthorityScope =
            encode("ownerDevice", device.value)

        /** The estate as a whole: rotation and sovereign reset. */
        fun estate(): AuthorityScope = encode("estate")
    }
}

/**
 * One administrative act, as signed by the device that performed it.
 *
 * There is deliberately no timestamp, no weight, no priority and no sequence
 * number the device chooses. Every field either is signed content or is looked
 * up in the manifest, so a device cannot make itself win by asserting that it
 * should. [capability] is what the act *requires*, not what the device claims
 * to hold — the holding is decided against the manifest at admission.
 */
data class AuthorityAttestation(
    val id: LedgerEntryId,
    val scope: AuthorityScope,
    /** The permission entry this act authorises. */
    val subject: LedgerEntryId,
    val device: AuthorityDeviceId,
    /** The act this was built on; `null` only for the first in a scope. */
    val parent: LedgerEntryId?,
    /**
     * The whole manifest state this act was authored against — see
     * [ManifestContext].
     *
     * Signed, and the basis of the authority check. Without it a reader has
     * only "what does the manifest say now", which is a different question from
     * the one the signing device answered. A single entry could not say it
     * either: independent manifest scopes merge, so the state that stands is a
     * union of branches and one head names only one of them.
     *
     * A normal act binds the full frontier standing when it was written; an act
     * authorising a *manifest mutation* binds the frontier from **before** that
     * mutation, because the change cannot be its own justification.
     */
    val manifestContext: ManifestContext,
    val authorityGeneration: Long,
    val capability: DeviceCapability,
    val effect: AuthorityEffect,
    val signature: String,
) {
    fun toEvent(): AuthorityEvent = AuthorityEvent(
        eventId = id,
        parent = parent,
        authorityGeneration = authorityGeneration,
        device = device,
        effect = effect,
    )
}

/**
 * Checks an attestation's signature.
 *
 * [publicKey] is supplied by the caller from the *manifest*, never read off the
 * attestation: an attestation that carried its own key would let an attacker
 * supply both halves of the check.
 */
fun interface AuthorityAttestationVerifier {
    fun verify(attestation: AuthorityAttestation, publicKey: String): Boolean
}

/** Length-prefixed canonical bytes, so no two acts can share one form. */
object AuthorityAttestationCanonicalForm {

    private const val VERSION = "cc.sharing.authorityattestation.v1"

    /**
     * The value a first act contributes where a parent id would go.
     *
     * A control character, so no real id can collide with it, written as an
     * escape rather than a raw byte so this file stays text — see
     * SharingSourceHygieneTest.
     */
    private const val NO_PARENT = "\u0000none"

    /** The signature itself is excluded: it is the output, not an input. */
    fun bytes(attestation: AuthorityAttestation): ByteArray {
        val fields = listOf(
            VERSION,
            attestation.id.value,
            attestation.scope.value,
            attestation.subject.value,
            attestation.device.value,
            attestation.parent?.value ?: NO_PARENT,
            attestation.manifestContext.canonical,
            attestation.authorityGeneration.toString(),
            attestation.capability.name,
            attestation.effect.name,
        )
        return buildString {
            fields.forEach { field ->
                append(field.encodeToByteArray().size)
                append(':')
                append(field)
                append(',')
            }
        }.encodeToByteArray()
    }
}

/** Signs attestations with this device's key, and hands out the counterpart. */
class AuthorityAttestationSigner(private val crypto: LedgerCrypto) {

    /** `null` when this device cannot sign — never an unsigned act. */
    fun sign(attestation: AuthorityAttestation): AuthorityAttestation? {
        val signature = crypto.sign(crypto.hash(AuthorityAttestationCanonicalForm.bytes(attestation)))
            ?: return null
        return attestation.copy(signature = signature.toHex())
    }

    fun verifier(): AuthorityAttestationVerifier = AuthorityAttestationVerifier { attestation, publicKey ->
        val signature = attestation.signature.fromHexOrNull()
        signature != null && crypto.verify(
            signature,
            crypto.hash(AuthorityAttestationCanonicalForm.bytes(attestation)),
            publicKey,
        )
    }
}

/** The async counterpart, for a device key held behind an external signer. */
class AsyncAuthorityAttestationSigner(private val crypto: AsyncLedgerCrypto) {

    suspend fun sign(attestation: AuthorityAttestation): AuthorityAttestation? {
        val signature = crypto.signCanonical(
            crypto.hash(AuthorityAttestationCanonicalForm.bytes(attestation)),
        ) ?: return null
        return attestation.copy(signature = signature.toHex())
    }

    fun verifier(): AuthorityAttestationVerifier = AuthorityAttestationVerifier { attestation, publicKey ->
        val signature = attestation.signature.fromHexOrNull()
        signature != null && crypto.verify(
            signature,
            crypto.hash(AuthorityAttestationCanonicalForm.bytes(attestation)),
            publicKey,
        )
    }
}

/**
 * Decides whether an attestation may be written at all.
 *
 * Capability, generation and signature are all settled *here*, before storage,
 * rather than filtered at read time. The ledger is append-only: an act that
 * reaches the table cannot be taken back out, and "we will ignore it when we
 * read it" is a much weaker promise than "it never got in" — the read-time
 * filter is one refactor away from being forgotten, and the row would still be
 * sitting there as evidence the owner's device authorised something.
 *
 * Side-effect free by construction: given the stored acts, a candidate and the
 * reduced manifest, it returns a verdict and writes nothing.
 */
/**
 * Which manifest context an act is allowed to bind.
 *
 * ## Why a stale context is not something a signature can excuse
 *
 * An act's signature proves which frontier its author **declared**. It does not
 * prove that the author had not seen more. So a device revoked on an
 * independent branch can sign a perfectly well-formed act naming the frontier
 * from before that branch existed, and every structural check passes: the
 * context is readable, the device held authority at it, the capability fits.
 * Admitting that live would let a revoked device go on writing simply by
 * naming an older, partial view of the estate — and by choosing a parent inside
 * that view, it never even collides with the revocation.
 *
 * Nothing in the evidence can distinguish "was offline" from "saw the
 * revocation and is pretending otherwise". So the rule is not evidential but
 * positional: **anything written now binds the frontier standing now**. A
 * device that really was offline re-signs against what it finds on reconnect,
 * which costs it a round trip and costs nobody their privacy.
 */
sealed interface RequiredManifestContext {

    /** What every live write binds, local or inbound: the frontier standing now. */
    data class Exactly(val context: ManifestContext) : RequiredManifestContext

    /**
     * A context from the past, accepted only where the *whole file* has already
     * been authenticated against the owner's root key.
     *
     * This exists for one caller — restoring a backup, past a verified recovery
     * challenge — and it is a named seam rather than a flag precisely so that
     * it cannot be reached by a caller that merely wanted "inbound". A boolean
     * on a shared door is how the generic path quietly acquires the exception.
     */
    data object RootAuthenticatedHistoricalImport : RequiredManifestContext
}

object AuthorityAttestationAdmission {

    @Suppress("ReturnCount")
    fun check(
        existing: List<AuthorityAttestation>,
        candidate: AuthorityAttestation,
        manifest: DeviceAuthorityState,
        verifier: AuthorityAttestationVerifier,
        /**
         * The context this act is required to bind.
         *
         * The current manifest head for an ordinary act, and — for an act
         * authorising a *manifest mutation* — that entry's own parent. A change
         * to the manifest cannot be its own justification: checked against the
         * state it produces, an enrolment would be authorised by the device it
         * enrols, and a fork would be judged against a merge that only exists
         * because both sides of the fork were admitted.
         */
        requiredManifestContext: RequiredManifestContext = RequiredManifestContext.Exactly(
            ManifestContext.of(manifest.frontier),
        ),
    ): LedgerAdmission {
        // A manifest that did not reduce vouches for nothing at all. Every
        // device is unauthenticated until it can be read again.
        manifest.failClosedReason?.let {
            return LedgerAdmission.Reject("the device manifest is not readable: $it")
        }

        if (requiredManifestContext is RequiredManifestContext.Exactly &&
            candidate.manifestContext != requiredManifestContext.context
        ) {
            return LedgerAdmission.Reject(
                "attestation ${candidate.id.value} binds manifest context " +
                    "${candidate.manifestContext.canonical}, but it must be authored against " +
                    requiredManifestContext.context.canonical,
            )
        }
        val asOf = manifest.at(candidate.manifestContext)
        asOf.failClosedReason?.let {
            return LedgerAdmission.Reject("attestation ${candidate.id.value}: $it")
        }

        // A scope from the ambiguous encoding is not repairable: the old
        // string does not say which subject it meant, so anything written into
        // it would be written somewhere nobody can name.
        if (!candidate.scope.isCurrentVersion) {
            return LedgerAdmission.Reject(
                "attestation ${candidate.id.value} names a scope from the superseded encoding, " +
                    "which cannot be resolved unambiguously",
            )
        }

        // The device has to be one of ours, in a role that still carries
        // authority, and not fenced. `roleOf` is the single place all three of
        // those are decided.
        val role = asOf.roleOf(candidate.device)
            ?: return LedgerAdmission.Reject(
                "device ${candidate.device.value} held no authority at the manifest head this act names",
            )
        if (!role.capabilities.contains(candidate.capability)) {
            return LedgerAdmission.Reject(
                "device ${candidate.device.value} is $role and so lacks ${candidate.capability}",
            )
        }

        // Exactly the established generation. Lower means the device has not
        // seen a rotation and is fenced; higher means it is claiming an
        // authority that was never established.
        if (candidate.authorityGeneration != asOf.authorityGeneration) {
            return LedgerAdmission.Reject(
                "attestation ${candidate.id.value} claims authority generation " +
                    "${candidate.authorityGeneration}, but its manifest head establishes " +
                    "${asOf.authorityGeneration}",
            )
        }

        // Authenticity, against the key the manifest holds for that device.
        val publicKey = asOf.devices[candidate.device]?.publicKey
            ?: return LedgerAdmission.Reject("no public key on record for ${candidate.device.value}")
        if (!verifier.verify(candidate, publicKey)) {
            return LedgerAdmission.Reject("attestation ${candidate.id.value} does not verify")
        }

        // A re-used id must be byte-identical, or two acts claim one name and
        // the parent links stop meaning anything.
        existing.firstOrNull { it.id == candidate.id }?.let { stored ->
            return if (stored == candidate) {
                LedgerAdmission.AlreadyPresent
            } else {
                LedgerAdmission.Reject(
                    "attestation id ${candidate.id.value} is already stored with divergent content",
                )
            }
        }

        // One act per subject, globally. A second one is how a weaker claim
        // gets in as evidence of authority: the entry it names is already
        // stored and paired, so nothing downstream re-checks it, and the extra
        // row afterwards reads as a device having authorised something it
        // never did.
        existing.firstOrNull { it.subject == candidate.subject }?.let { stored ->
            return LedgerAdmission.Reject(
                "entry ${candidate.subject.value} is already authorised by attestation ${stored.id.value}",
            )
        }

        // The parent must be an act we already hold. Note what is *not*
        // required: that it be the head. Two devices building on one parent is
        // the normal concurrent case, and both are kept.
        candidate.parent?.let { parent ->
            if (parent == candidate.id) {
                return LedgerAdmission.Reject("attestation ${candidate.id.value} cannot be its own parent")
            }
            if (existing.none { it.id == parent }) {
                return LedgerAdmission.Reject(
                    "attestation ${candidate.id.value} follows ${parent.value}, which is not stored",
                )
            }
        }

        return LedgerAdmission.Accept
    }
}

/**
 * Reduces stored attestations to the act that stands in each scope.
 *
 * The whole of the decision is [DeviceAuthorityResolver] — the same function
 * live reduction, replay and restore all call. This adds only the grouping:
 * acts compete within a scope and never across one.
 */
object AuthorityLedger {

    /**
     * [knownSubjects] filters out acts whose entry is not (or is no longer)
     * stored.
     *
     * An orphan act vouches for nothing. Left in, it would head a scope and be
     * offered as the parent for the next act, chaining real decisions onto a
     * claim about an entry that does not exist — and after a purge, an act
     * about the deleted relationship would still be sitting in the heads.
     * `null` means "do not filter", for callers that only hold the acts.
     */
    fun winners(
        attestations: TrustedAttestations,
        knownSubjects: Set<LedgerEntryId>?,
    ): Map<AuthorityScope, AuthorityAttestation> {
        // Nothing is filtered out any more. An act whose subject is missing
        // used to be dropped and the rest resolved, which is a *truncated*
        // history — the same shape as a revocation quietly disappearing. It
        // fails the whole thing closed instead.
        if (knownSubjects != null &&
            AuthorityDagValidation.check(attestations.acts, knownSubjects) != null
        ) {
            return emptyMap()
        }
        return winners(attestations)
    }

    fun winners(attestations: TrustedAttestations): Map<AuthorityScope, AuthorityAttestation> {
        // An act nobody's key vouches for decides nothing, and takes the rest
        // with it — see TrustedAttestations.
        if (attestations.failClosedReason != null) return emptyMap()
        // Legacy scopes are dropped rather than resolved. Grouping by an
        // ambiguous string would put two different subjects into one
        // competition, which is the bug the versioning exists to close.
        val distinct = attestations.acts.distinctBy { it.id }.filter { it.scope.isCurrentVersion }
        val bySubject = distinct.associateBy { it.subject }
        return distinct.map { it.scope }.distinct().mapNotNull { scope ->
            // Events keyed by subject and linked by the *signed* act parent, so
            // this and the branch projection order the same acts the same way.
            val events = AuthorityDagValidation.eventsFor(distinct, scope)
            // Ordered by the role each author held where it signed, never by
            // the role its device holds now — see TrustedAttestations.roleOf.
            val winner = DeviceAuthorityResolver.resolve(events, attestations.manifest, attestations.roleOf)
                ?: return@mapNotNull null
            val act = bySubject[winner.eventId] ?: return@mapNotNull null
            scope to act
        }.toMap()
    }

    /** The act that stands in one scope, or `null` if none carries authority. */
    fun winner(
        attestations: TrustedAttestations,
        scope: AuthorityScope,
    ): AuthorityAttestation? = winners(attestations)[scope]
}
