package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §11: getting back in, and what that costs the devices already there.
 *
 * A restore is the one moment where somebody holding a backup and a recovery
 * code takes over an estate. Everything in this file follows from that single
 * fact: it needs both credentials, it mints a new authority generation, the
 * returning device becomes PRIMARY, and every device that was there before is
 * fenced until the owner deliberately says otherwise.
 *
 * ## Why a stale backup may only be read
 *
 * A backup is a snapshot. Restoring an old one re-establishes permissions that
 * have since been taken away, because the revocation is simply not in it. An
 * install that cannot show its backup is current therefore gets a preview it
 * can read and nothing it can write, until it either syncs or the owner
 * declares a sovereign reset with the root key. Guessing in the owner's favour
 * here hands access back to somebody they already removed, silently.
 *
 * Nothing in this file writes. It decides and it plans; the caller signs and
 * stores, so a declined signature leaves a plan and no consequences.
 */

/** How current the backup in hand is, relative to what this install knows. */
enum class BackupFreshness {
    /** At least as new as the last authority this install saw. */
    CURRENT,

    /** Older. It is missing changes that were made after it was taken. */
    STALE,

    /**
     * Nothing to compare against.
     *
     * Treated exactly like [STALE]. "We cannot tell" and "we know it is old"
     * warrant the same caution; only one of them is tempting to wave through.
     */
    UNKNOWN,
}

/** What the person restoring actually proved. */
data class RecoveryCredentials(
    /** The root identity signed the request — a NIP-55/46 signer, in practice. */
    val rootSigned: Boolean,
    /** The recovery code they typed matches the one the backup was sealed with. */
    val recoveryCodeMatches: Boolean,
) {
    /** Both, always. Either alone is a credential somebody else can hold too. */
    val complete: Boolean get() = rootSigned && recoveryCodeMatches
}

/** What may happen, given those credentials and that backup. */
enum class RecoveryDecision {
    /** Full restore: new generation, new PRIMARY, old devices fenced. */
    RESTORE,

    /** Read-only. The state can be shown; no administrative write is allowed. */
    PREVIEW_ONLY,

    /** Everything revoked and rotated. Only on an explicit root-signed request. */
    SOVEREIGN_RESET,

    /** Nothing at all. */
    REFUSED,
}

/** Decides which of those four a restore attempt is. Writes nothing. */
object RecoveryGate {

    fun decide(
        credentials: RecoveryCredentials,
        freshness: BackupFreshness,
        sovereignReset: Boolean = false,
    ): RecoveryDecision {
        // Both credentials first, and for every path. A sovereign reset is the
        // most destructive thing in the feature; it is emphatically not a way
        // around proving who you are.
        if (!credentials.complete) return RecoveryDecision.REFUSED

        if (sovereignReset) return RecoveryDecision.SOVEREIGN_RESET

        return when (freshness) {
            BackupFreshness.CURRENT -> RecoveryDecision.RESTORE
            BackupFreshness.STALE, BackupFreshness.UNKNOWN -> RecoveryDecision.PREVIEW_ONLY
        }
    }

    /**
     * Compares the backup's authority generation with the last one this install
     * saw.
     *
     * [knownGeneration] `null` means there is nothing to compare against, which
     * is [BackupFreshness.UNKNOWN] rather than "fine" — a fresh install cannot
     * distinguish a current backup from a year-old one, and treating the second
     * as the first is exactly the mistake that restores revoked access.
     */
    fun freshness(backupGeneration: Long, knownGeneration: Long?): BackupFreshness = when {
        knownGeneration == null -> BackupFreshness.UNKNOWN
        backupGeneration >= knownGeneration -> BackupFreshness.CURRENT
        else -> BackupFreshness.STALE
    }
}

/**
 * What a restore intends to do to the native transport.
 *
 * [performed] is deliberately separate from [planned] and is always false while
 * the native gate is shut. Recording a rotation that did not happen would be a
 * claim the next reader has no way to check, and the whole feature is built so
 * that everything it asserts can be re-derived from evidence.
 */
data class NativeRotationPlan(
    val planned: Boolean,
    val performed: Boolean = false,
    val reason: String = "",
)

/**
 * Everything a recovery would change, computed before anything is signed.
 *
 * A plan is inert. The caller signs the manifest bodies and the epoch entries
 * and stores them in one transaction, so a person who abandons the signing
 * prompt halfway leaves an install that is exactly as it was.
 */
data class RecoveryPlan(
    val decision: RecoveryDecision,
    /** The generation that will hold afterwards; unchanged for a preview. */
    val newGeneration: Long,
    /** In order. The rotation or reset first, then the new device's enrolment. */
    val manifestBodies: List<DeviceManifestBody>,
    val fenced: Set<AuthorityDeviceId>,
    /** Relationships whose grant this recovery withdraws. Reset only. */
    val revokedPeers: Set<PeerId>,
    val resourceEpochs: Map<PeerId, Long>,
    val deviceGenerations: Map<PeerId, Long>,
    val nativeRotation: NativeRotationPlan,
    /** True while nothing administrative may be written at all. */
    val administrativeWritesLocked: Boolean,
)

/** Turns a [RecoveryDecision] into the concrete changes it implies. */
object AuthorityRecoveryPlanner {

    private const val NATIVE_GATE_REASON =
        "the native transport is gated shut, so recipient-side key rotation is planned and not performed"

    fun plan(
        manifest: DeviceAuthorityState,
        relationships: Map<PeerId, RelationshipState>,
        newDevice: AuthorityDeviceId,
        newDevicePublicKey: String,
        decision: RecoveryDecision,
    ): RecoveryPlan = when (decision) {
        RecoveryDecision.PREVIEW_ONLY, RecoveryDecision.REFUSED -> locked(manifest, decision)
        RecoveryDecision.RESTORE -> rotate(
            manifest, relationships, newDevice, newDevicePublicKey, decision,
            opening = DeviceManifestBody.AuthorityRotated(
                generation = manifest.authorityGeneration + 1,
                fenceExisting = true,
            ),
            revokePeers = false,
        )
        RecoveryDecision.SOVEREIGN_RESET -> rotate(
            manifest, relationships, newDevice, newDevicePublicKey, decision,
            opening = DeviceManifestBody.SovereignReset,
            revokePeers = true,
        )
    }

    /** A plan that changes nothing and says so. */
    private fun locked(manifest: DeviceAuthorityState, decision: RecoveryDecision) = RecoveryPlan(
        decision = decision,
        newGeneration = manifest.authorityGeneration,
        manifestBodies = emptyList(),
        fenced = emptySet(),
        revokedPeers = emptySet(),
        resourceEpochs = emptyMap(),
        deviceGenerations = emptyMap(),
        nativeRotation = NativeRotationPlan(planned = false),
        administrativeWritesLocked = true,
    )

    private fun rotate(
        manifest: DeviceAuthorityState,
        relationships: Map<PeerId, RelationshipState>,
        newDevice: AuthorityDeviceId,
        newDevicePublicKey: String,
        decision: RecoveryDecision,
        opening: DeviceManifestBody,
        revokePeers: Boolean,
    ): RecoveryPlan {
        val newGeneration = manifest.authorityGeneration + 1
        return RecoveryPlan(
            decision = decision,
            newGeneration = newGeneration,
            // Order is load-bearing. The rotation is authored under the old
            // generation and fences everything; the enrolment then arrives under
            // the new one, so the returning device is the only one holding
            // authority the moment the manifest is read again.
            manifestBodies = listOf(
                opening,
                DeviceManifestBody.DeviceEnrolled(newDevice, newDevicePublicKey, DeviceRole.PRIMARY),
            ),
            // Everything that was there, minus the device doing the restoring —
            // which is a new enrolment anyway, so this only matters when a
            // device is recovering onto an identity it already had.
            fenced = manifest.devices.keys - newDevice,
            revokedPeers = if (revokePeers) relationships.keys.toSet() else emptySet(),
            // Both epochs move for every relationship. The resource epoch
            // retires the data keys the lost device could still open; the device
            // generation retires the recipient-side authorisations that were
            // issued to it.
            resourceEpochs = relationships.mapValues { (_, state) -> state.resourceEpoch + 1 },
            deviceGenerations = relationships.mapValues { (_, state) -> state.deviceGeneration + 1 },
            nativeRotation = NativeRotationPlan(
                planned = true,
                performed = false,
                reason = NATIVE_GATE_REASON,
            ),
            administrativeWritesLocked = false,
        )
    }
}

/**
 * The challenge a recovery's root signature is over.
 *
 * Domain-separated and bound to what is actually being asked for. Without the
 * binding, a signature the owner produced for one purpose — a relay auth, an
 * ordinary ledger entry, an earlier preview — could be replayed as consent to a
 * sovereign reset. The recovery code hash is included so a signature obtained
 * without the code cannot be paired with a code obtained without the signature.
 *
 * `signerPresent != rootSigned`. Having a signer wired says nothing about
 * whether the person holding it agreed; only a signature over this challenge
 * does, and only if it verifies against the owner's key.
 */
/**
 * A claim that the owner authorised one particular recovery, and the signature
 * that is supposed to prove it.
 *
 * ## It asserts nothing
 *
 * Deliberately inert: every field is the caller's to fill in, including the
 * signature, and holding one means nothing at all. Three rounds of this design
 * tried to make a *token* that could only be minted honestly — first a private
 * constructor, then a companion factory, then a factory that verified — and
 * each time the minting path stayed reachable from the whole module, most
 * recently because the factory took the verifier as a parameter and a caller
 * could hand it one that always says yes.
 *
 * So the type stopped trying. Verification happens at the side-effect boundary,
 * against a verifier bound when the repository was constructed, which no
 * individual caller can choose. What travels is the claim; what decides is the
 * component that owns the key.
 */
data class RootRecoveryAuthorization(
    val ownerNpub: String,
    val devicePublicKey: String,
    val recoveryCodeDigest: String,
    val backupGeneration: Long,
    val sovereignReset: Boolean,
    /** Fresh per attempt, signed, and spendable once. */
    val attemptNonce: String,
    val signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is RootRecoveryAuthorization &&
                ownerNpub == other.ownerNpub &&
                devicePublicKey == other.devicePublicKey &&
                recoveryCodeDigest == other.recoveryCodeDigest &&
                backupGeneration == other.backupGeneration &&
                sovereignReset == other.sovereignReset &&
                attemptNonce == other.attemptNonce &&
                signature.contentEquals(other.signature)
            )

    override fun hashCode(): Int =
        listOf(ownerNpub, devicePublicKey, recoveryCodeDigest, backupGeneration, sovereignReset, attemptNonce)
            .hashCode() * 31 + signature.contentHashCode()

    /** Never the signature or the digest: this ends up in logs. */
    override fun toString(): String =
        "RootRecoveryAuthorization(generation=$backupGeneration, reset=$sovereignReset, redacted)"
}

object RecoveryChallenge {

    private const val VERSION = "cc.sharing.recoverychallenge.v2"

    /**
     * Length-prefixed, so no field's content can be mistaken for structure.
     *
     * [attemptNonce] is fresh for every attempt and makes the signature
     * single-use: without it, one signature over "this file, this code, this
     * generation" stays valid for ever, so a signature captured once is a
     * standing licence to redo the recovery. The version moved to v2 when it
     * was added — a v1 signature does not verify here, which is the fail-closed
     * answer for evidence that never bound an attempt.
     */
    fun bytes(
        ownerNpub: String,
        recoveryCodeDigest: String,
        newDevicePublicKey: String,
        backupGeneration: Long,
        sovereignReset: Boolean,
        attemptNonce: String,
    ): ByteArray {
        val fields = listOf(
            VERSION,
            ownerNpub,
            recoveryCodeDigest,
            newDevicePublicKey,
            backupGeneration.toString(),
            if (sovereignReset) "sovereign-reset" else "restore",
            attemptNonce,
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
