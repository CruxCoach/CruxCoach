package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §5: the signed, append-only permission ledger.
 *
 * The ledger — not a relay, not a projection table, not the transport — is the
 * factual authority for who may see what. Every entry carries the four
 * coordinates that make reduction decidable without asking anybody:
 *
 *  - [SharingLedgerEntry.policySequence] — strictly increasing, gap-free per
 *    relationship, so a missing entry is detectable rather than invisible;
 *  - [SharingLedgerEntry.authorityGeneration] — bumped when authority is
 *    re-established, so entries from a superseded authority are ignored;
 *  - [SharingLedgerEntry.resourceEpoch] — bumped whenever category keys are
 *    rotated, so stale ciphertext cannot be read as current;
 *  - [SharingLedgerEntry.deviceGeneration] — minted fresh by a restore, so a
 *    restored install can never inherit the old install's device rights.
 */

/** Stable, content-independent identity of one ledger entry. */
data class LedgerEntryId(val value: String) {
    init { require(value.isNotBlank()) { "LedgerEntryId must not be blank" } }
}

/** One authorised device of one peer. Never a person, always a device. */
data class DeviceId(val value: String) {
    init { require(value.isNotBlank()) { "DeviceId must not be blank" } }
}

/** Visible lifecycle of one sharing relationship. */
enum class RelationshipStatus {
    /** Offered, not yet answered. Releases nothing. */
    PENDING,

    /** Recipient accepted. Consented categories may be released. */
    ACCEPTED,

    /** Recipient refused. Terminal. */
    DECLINED,

    /** Key delivery for a new or widened grant is not confirmed. */
    DELIVERY_UNCLEAR,

    /** Withdrawn by the owner. Terminal, never returns to accepted. */
    REVOKED,

    /** Local cleanup is scheduled but not finished. */
    PURGE_PENDING,

    /** Local cleanup finished. Terminal. */
    PURGED,

    /** The ledger could not be reduced safely. Releases nothing, ever. */
    FAIL_CLOSED,
    ;

    val isTerminal: Boolean
        get() = this == DECLINED || this == REVOKED || this == PURGE_PENDING ||
            this == PURGED || this == FAIL_CLOSED
}

/** What one ledger entry asserts. */
sealed interface SharingLedgerBody {

    /**
     * True for entries that must never leave the device.
     *
     * The recipient learns what they may see; they never learn which social
     * circle the owner filed them under.
     */
    val isLocalOnly: Boolean get() = false

    /**
     * Which circle the owner filed [PeerId] under. Owner-side only: this is the
     * social judgement the contract keeps private, so it is a separate,
     * local-only entry rather than a field of the offer that gets sent.
     */
    data class PeerCircleAssigned(val circle: SharingCircle) : SharingLedgerBody {
        override val isLocalOnly: Boolean get() = true
    }

    /** The owner offers a relationship covering [categories]. */
    data class RelationshipOffered(
        val categories: Set<SharingCategory>,
        /**
         * Optional, versioned in the model and deliberately **not** surfaced in
         * the v1 UI. Carried so a later release can enforce it without a
         * ledger-format migration.
         */
        val expiresAt: Long? = null,
    ) : SharingLedgerBody

    /** The recipient accepts [categories] on exactly one device. */
    data class RecipientAccepted(
        val categories: Set<SharingCategory>,
        val deviceId: DeviceId,
    ) : SharingLedgerBody

    /** The recipient refuses. */
    data object RecipientDeclined : SharingLedgerBody

    /** The owner sets the offered category set to exactly [categories]. */
    data class GrantChanged(val categories: Set<SharingCategory>) : SharingLedgerBody

    /**
     * One further device of the peer is authorised.
     *
     * Signed by the **peer**: this is them proving the device is theirs. An
     * owner-signed one would reduce "every device is authorised separately" to
     * the owner naming a device.
     */
    data class DeviceAuthorized(val deviceId: DeviceId) : SharingLedgerBody

    /**
     * One device loses access. Sticky: it is never re-authorised, however often
     * the peer signs it again.
     *
     * Signed by the **owner**, deliberately not symmetric with
     * [DeviceAuthorized]: withdrawing access must not need the cooperation of
     * the device losing it.
     */
    data class DeviceRevoked(val deviceId: DeviceId) : SharingLedgerBody

    /** Key delivery to [deviceId] could not be confirmed. */
    data class KeyDeliveryUnclear(val deviceId: DeviceId) : SharingLedgerBody

    /** Key delivery to [deviceId] is confirmed. */
    data class KeyDeliveryConfirmed(val deviceId: DeviceId) : SharingLedgerBody

    /** The whole relationship is withdrawn. */
    data object RelationshipRevoked : SharingLedgerBody

    /** Local cleanup is requested. */
    data object PurgeRequested : SharingLedgerBody

    /** Local cleanup finished, keys destroyed first. */
    data object PurgeCompleted : SharingLedgerBody

    /** Category keys were rotated; ciphertext of older epochs is unreadable. */
    data class ResourceEpochAdvanced(val epoch: Long) : SharingLedgerBody

    /** A backup was restored; a fresh device generation is minted. */
    data class RestoreCompleted(val newDeviceGeneration: Long) : SharingLedgerBody

    /**
     * An entry this build does not understand. Never skipped: an unreadable
     * entry means the reduction is incomplete, and incomplete fails closed.
     */
    data class Unknown(val kind: String) : SharingLedgerBody
}

/** One signed, append-only ledger entry. */
data class SharingLedgerEntry(
    val id: LedgerEntryId,
    val peer: PeerId,
    val policySequence: Long,
    /**
     * The entry this one was built on; `null` only for the first about a peer.
     *
     * The ledger is a DAG, not a line. Two of the owner's devices go offline
     * and both decide something about the same person: under a single chain the
     * second to arrive collided on the next sequence and was discarded, so
     * whichever reached the database first won. That is arrival order deciding
     * who can see what, which is the thing the resolver exists to eliminate.
     * Both branches are stored, and [AuthorityBranchResolver] picks the one
     * that stands.
     */
    val parent: LedgerEntryId? = null,
    val authorityGeneration: Long,
    val resourceEpoch: Long,
    val deviceGeneration: Long,
    val signerNpub: String,
    val signature: String,
    val body: SharingLedgerBody,
)

/**
 * Verifies an entry's signature against its signer.
 *
 * Kept as a seam so the domain stays pure: the real implementation goes through
 * the app's existing Nostr signer abstraction. A reducer built without a
 * verifier that actually checks anything is a reducer that fails closed.
 */
fun interface LedgerSignatureVerifier {
    fun verify(entry: SharingLedgerEntry): Boolean
}

/** Reduced, per-peer state of the ledger. */
data class RelationshipState(
    val peer: PeerId,
    val status: RelationshipStatus,
    val circle: SharingCircle = SharingCircle.ALL_OTHER_USERS,
    val offeredCategories: Set<SharingCategory> = emptySet(),
    val consentedCategories: Set<SharingCategory> = emptySet(),
    val pendingConsentCategories: Set<SharingCategory> = emptySet(),
    val authorisedDevices: Set<DeviceId> = emptySet(),
    /** Sticky. A device listed here is never authorised again. */
    val revokedDevices: Set<DeviceId> = emptySet(),
    val resourceEpoch: Long = 1,
    val authorityGeneration: Long = 1,
    val deviceGeneration: Long = 1,
    val lastSequence: Long = 0,
    val expiresAt: Long? = null,
    /** A restored install stays closed until revocations have been re-synced. */
    val awaitingRevokeSync: Boolean = false,
    val failClosedReason: String? = null,
)

/** The reduced projection of the whole ledger. */
data class SharingProjection(
    val relationships: Map<PeerId, RelationshipState> = emptyMap(),
)
