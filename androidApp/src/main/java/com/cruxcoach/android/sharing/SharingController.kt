package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.ObjectRuleKey
import com.cruxcoach.domain.sharing.SharingPolicyResolver

import com.cruxcoach.domain.sharing.AccessDecision
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.AsyncLedgerCrypto
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.AsyncAuthorityAttestationSigner
import com.cruxcoach.domain.sharing.AuthorityAttestation
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.AuthorityEffect
import com.cruxcoach.domain.sharing.AuthorityScope
import com.cruxcoach.domain.sharing.DeviceCapability
import com.cruxcoach.domain.sharing.AsyncDeviceManifestSigner
import com.cruxcoach.domain.sharing.AuthorityRecoveryPlanner
import com.cruxcoach.domain.sharing.BackupFreshness
import com.cruxcoach.domain.sharing.DeviceManifestBody
import com.cruxcoach.domain.sharing.DeviceManifestEntry
import com.cruxcoach.domain.sharing.NativeRotationPlan
import com.cruxcoach.domain.sharing.RecoveryCredentials
import com.cruxcoach.domain.sharing.RecoveryDecision
import com.cruxcoach.domain.sharing.RecoveryGate
import com.cruxcoach.domain.sharing.RecoveryPlan
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.DeviceAuthorityState
import com.cruxcoach.domain.sharing.AuthorityPairing
import com.cruxcoach.domain.sharing.SharingBackupPayload
import com.cruxcoach.domain.sharing.RecoveryChallenge
import com.cruxcoach.domain.sharing.RootRecoveryAuthorization
import com.cruxcoach.domain.sharing.CircleBaselines
import com.cruxcoach.domain.sharing.CryptoEraseStep
import com.cruxcoach.domain.sharing.DecisionSource
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.domain.sharing.EffectiveAccessResolver
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.ManifestContext
import com.cruxcoach.domain.sharing.NativeSharingGate
import com.cruxcoach.domain.sharing.NativeSharingGateState
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.OwnerPolicyBody
import com.cruxcoach.domain.sharing.OwnerPolicyEntry
import com.cruxcoach.domain.sharing.OwnerPolicyState
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingBackupEnvelope
import com.cruxcoach.domain.sharing.SharingBackupError
import com.cruxcoach.domain.sharing.SharingBackupReadResult
import com.cruxcoach.domain.sharing.SharingBackupWriteResult
import com.cruxcoach.domain.sharing.RelationshipState
import com.cruxcoach.domain.sharing.RelationshipStatus
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingLedgerBody
import com.cruxcoach.domain.sharing.SharingLedgerCodec
import com.cruxcoach.domain.sharing.SharingLedgerEntry
import com.cruxcoach.domain.sharing.SharingLedgerSigner
import com.cruxcoach.domain.sharing.SharingPolicy
import com.cruxcoach.domain.sharing.SharingRecoveryCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

/** Why a write did not happen. Each value maps to one visible message. */
enum class SharingWriteError {
    /** The identity cannot sign right now — an external signer, for instance. */
    SIGNER_UNAVAILABLE,

    /** The entry would not have been admissible. */
    REJECTED,

    /**
     * This device holds no authority to make this change.
     *
     * Distinct from [REJECTED] because it is not a bug: a read-only or fenced
     * device asking for something outside its role is the model working, and
     * the screen has to say so in those words rather than "something failed".
     */
    NOT_AUTHORISED,

    /**
     * A recovery preview is open and administrative writes are locked.
     *
     * Separate from [NOT_AUTHORISED] because the remedy is different: this
     * device is allowed to make the change, it just may not do so from a backup
     * it cannot show to be current.
     */
    RECOVERY_LOCKED,
    NATIVE_UNAVAILABLE,
    TRANSPORT_LIMIT,
    SESSION_UNAVAILABLE,
    DISCOVERY_REQUIRED,
    PEER_UNAVAILABLE,
}

/**
 * The outcome of a mutating call.
 *
 * Mutations used to return `Unit` and swallow a failed signature, so the UI
 * cleared the field and showed nothing while nothing had been written.
 */
sealed interface SharingWriteResult {
    /** Something was appended. */
    data object Ok : SharingWriteResult

    /** Nothing needed appending; the ledger already said this. */
    data object NoChange : SharingWriteResult

    data class Failed(val error: SharingWriteError) : SharingWriteResult

    val isSuccess: Boolean get() = this is Ok || this is NoChange
}

/** One category row as the detail screen shows it. */
data class CategoryRow(
    val category: SharingCategory,
    /** What the owner decided, and which rule decided it. */
    val policyDecision: AccessDecision,
    /** Whether it is actually released right now, and what is holding it. */
    val effectiveDecision: AccessDecision,
)

data class ObjectRuleRow(
    val objectId: ObjectId,
    val category: SharingCategory,
    val effect: AccessEffect,
)

data class DeviceRow(
    val device: DeviceId,
    val authorised: Boolean,
    val revoked: Boolean,
)

data class PeerSummary(
    val peer: PeerId,
    val circle: SharingCircle,
    val status: RelationshipStatus,
    val releasedCount: Int,
    val pendingConsentCount: Int,
)

data class PeerDetail(
    val peer: PeerId,
    val circle: SharingCircle,
    val status: RelationshipStatus,
    val categories: List<CategoryRow>,
    val objectRules: List<ObjectRuleRow>,
    val devices: List<DeviceRow>,
    val consentedCategories: Set<SharingCategory>,
    val pendingConsentCategories: Set<SharingCategory>,
    val awaitingRevokeSync: Boolean,
    val failClosedReason: String?,
    val expiresAt: Long? = null,
    val snapshotRole: SnapshotEndpointRole? = null,
)

data class SharingUiState(
    val gate: NativeSharingGateState,
    val baselines: CircleBaselines,
    val peers: List<PeerSummary>,
    val demoSeeded: Boolean = false,
    val nativeAvailable: Boolean = false,
)

/**
 * Everything the sharing screens are driven by.
 *
 * Reading is plain and synchronous: every rule the UI displays is decided here,
 * so it can be tested without Compose, Hilt or a dispatcher. The ViewModel is a
 * thin wrapper over this.
 *
 * Each mutating call appends one signed ledger entry and lets the projection be
 * recomputed from it. Nothing writes the projection directly, so the screen can
 * never show a state the ledger does not actually justify.
 *
 * ## Why the mutations suspend
 *
 * Signing may have to leave the process: an external NIP-55 signer shows its
 * own prompt and answers when the person says so. So every mutation suspends,
 * and — because the answer can take as long as it takes — every mutation holds
 * [mutex] for its whole duration.
 *
 * That serialisation is not tidiness. A mutation reads the next policy sequence
 * *before* signing and writes *after*, so two overlapping mutations would both
 * read the same sequence and the second write would either collide or,
 * worse, be admitted out of order. One at a time is the only shape that keeps
 * the sequence monotonic when the gap between reading it and using it is a
 * person tapping "approve" in another app.
 *
 * Every one of them takes that lock through [administrativeWrite], which is also
 * where a recovery preview stops local authorship — before anything is built, so
 * a refusal never costs a prompt. Listening is not authorship and does not go
 * through it: see [ingestPeerSignedEntry].
 */
class SharingController(
    private val repository: SecureDbSharingRepository,
    private val signer: AsyncSharingLedgerSigner,
    private val ownerPolicySigner: AsyncOwnerPolicySigner,
    private val ownerNpub: String,
    /**
     * Debug-only source of peer signing keys, so the demo can produce genuinely
     * peer-signed acceptances. `null` in release, where an acceptance can only
     * be ingested after the peer signed it on their own device.
     */
    private val peerSimulator: PeerSimulator? = null,
    /**
     * Signs and verifies the backup envelope. The same identity as [signer];
     * kept separate only so a test can drive it without a vault.
     */
    private val backupCrypto: AsyncLedgerCrypto? = null,
    /**
     * This install's own device, as the manifest names it.
     *
     * `null` means no device identity is wired, and then nothing administrative
     * can be authored at all. That is deliberate: the alternative is writing
     * unattributed permission changes, which is the state this model removes.
     */
    private val authorityDevice: AuthorityDeviceId? = null,
    /** Signs this device's acts. Useless without [authorityDevice], and vice versa. */
    private val attestationSigner: AsyncAuthorityAttestationSigner? = null,
    /**
     * Signs manifest entries with the owner's **root** identity.
     *
     * Not the device key: the manifest is what says which devices exist at all,
     * so a device that could write it would be able to promote itself.
     */
    private val manifestSigner: AsyncDeviceManifestSigner? = null,
    /**
     * This device's public key, for the case where the manifest does not name
     * it yet — a fresh install about to recover onto an existing estate.
     */
    private val authorityDevicePublicKey: String? = null,
    /**
     * The owner's **root** identity, for the recovery challenge.
     *
     * Separate from [manifestSigner] because the challenge is not a manifest
     * entry: it is a statement that this person, holding this code, asked for
     * this recovery on this device.
     */
    private val rootCrypto: AsyncLedgerCrypto? = null,
    private val gate: NativeSharingGateState = NativeSharingGate.current(),
    private val random: SecureRandom = SecureRandom(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val snapshotExchange: SharingSnapshotExchange? = null,
    private val marmot: AndroidMarmotFactory? = null,
    private val policyTransport: SharingPolicyTransport? = null,
    private val continuous: ContinuousSharingExchange? = null,
    private val onContinuousChange: () -> Unit = {},
) {
    private val transportAvailable get() = gate.isLive || snapshotExchange?.nativeAvailable == true

    /** Held for the whole of every mutation, signing included. */
    private val mutex = Mutex()

    /**
     * The counterparts of [signer] and [ownerPolicySigner].
     *
     * A signature is checked against the entry it belongs to before that entry
     * is persisted. That is not paranoia about our own code: the signing
     * identity is read live, so if it changed between the entry being built and
     * the signature coming back — an identity switch, a signer that fell back
     * to a local key — the answer is a valid signature by the *wrong* key.
     * Storing it would fail the whole relationship closed on the next read.
     */
    private val entryVerifier by lazy { signer.verifier() }
    private val policyVerifier by lazy { ownerPolicySigner.verifier() }
    private val attestationVerifier by lazy { attestationSigner?.verifier() }

    /**
     * Whether this device may do [capability] right now, per the manifest.
     *
     * Asked *before* anything is signed. Prompting somebody to approve a change
     * that will then be refused teaches them the prompt is noise, and with an
     * external signer that prompt is a whole other app.
     */
    private fun isAuthorised(capability: DeviceCapability): Boolean {
        if (!repository.isCurrentAccount()) return false
        val device = authorityDevice ?: return false
        if (attestationSigner == null) return false
        if (repository.administrativeWritesLocked()) return false
        return repository.loadDeviceAuthority().can(device, capability)
    }

    /**
     * What an act must say to authorise [body], or null if the body is the
     * peer's own and needs no act from one of our devices.
     *
     * Derived by [AuthorityPairing], the same function the repository checks
     * against before writing. Computing it twice, in two places, is how the
     * two would drift into disagreeing — and a disagreement here is an entry
     * the controller signs and the repository then refuses.
     */
    private fun requiredFor(peer: PeerId, body: SharingLedgerBody): AuthorityPairing.Required? =
        AuthorityPairing.requiredFor(peer, body)

    /**
     * Everything one user action will write, built and signed before any of it
     * is stored.
     *
     * A user action is often several entries — a baseline change is a policy
     * entry plus a `GrantChanged` for every relationship it reaches — and with
     * an external signer each is a separate approval prompt. Collecting them
     * here means a decline at the third prompt leaves the first two unwritten,
     * instead of a policy that changed while the screen reported failure.
     *
     * Sequences are handed out from here rather than read per entry, because
     * two entries for the same peer in one action have to be consecutive and
     * neither is stored yet.
     */
    private inner class Batch {
        val policy = mutableListOf<OwnerPolicyEntry>()
        val entries = mutableListOf<SharingLedgerEntry>()
        val attestations = mutableListOf<AuthorityAttestation>()
        private val sequences = mutableMapOf<PeerId, Long>()

        /**
         * The act each scope currently ends at, so acts written in one action
         * chain to each other rather than all claiming the same parent.
         *
         * Seeded from the stored winner the first time a scope is touched:
         * nothing in this batch is written yet, so the repository still
         * describes the world as it was before the action began.
         */
        private val heads = mutableMapOf<AuthorityScope, LedgerEntryId?>()

        /**
         * The entry each peer's branch currently ends at.
         *
         * Seeded from the *authorised* head rather than from whatever has the
         * highest sequence: building on a losing branch would extend it, and
         * the resolver would then have to choose between two branches this
         * device itself had grown.
         */
        private val entryHeads = mutableMapOf<PeerId, LedgerEntryId?>()

        fun parentFor(peer: PeerId): LedgerEntryId? =
            entryHeads.getOrElse(peer) { repository.authorisedHead(peer)?.id }

        fun advance(peer: PeerId, entry: SharingLedgerEntry) {
            entryHeads[peer] = entry.id
        }

        fun nextSequence(peer: PeerId): Long {
            val next = sequences[peer]
                ?: ((repository.authorisedHead(peer)?.policySequence ?: 0L) + 1L)
            sequences[peer] = next + 1
            return next
        }

        /** `false` when the entry was not signed, or does not verify. */
        suspend fun sign(peer: PeerId, body: SharingLedgerBody): Boolean {
            val signed = prepare(
                peer, body, sequence = nextSequence(peer), parent = parentFor(peer),
            ) ?: return false
            advance(peer, signed)
            val required = requiredFor(peer, body) ?: run {
                // The peer's own body: no act, and none is wanted.
                entries += signed
                return true
            }
            return add(signed, required)
        }

        /**
         * Records a signed entry together with the act that authorises it.
         *
         * Both or neither: an entry with no act behind it is a change nobody
         * signed for, and there is no point collecting one without the other.
         */
        suspend fun add(entry: SharingLedgerEntry, required: AuthorityPairing.Required): Boolean {
            val scope = required.scope
            val act = attest(
                entry.id, scope, required.effect, required.capability,
                heads[scope] ?: storedHead(scope),
            ) ?: return false
            entries += entry
            attestations += act
            heads[scope] = act.id
            return true
        }

        /** The same, for an owner-policy entry rather than a relationship one. */
        suspend fun addPolicy(entry: OwnerPolicyEntry, required: AuthorityPairing.Required): Boolean {
            val scope = required.scope
            val act = attest(
                entry.id, scope, required.effect, required.capability,
                heads[scope] ?: storedHead(scope),
            ) ?: return false
            policy += entry
            attestations += act
            heads[scope] = act.id
            return true
        }

        suspend fun signPolicy(body: OwnerPolicyBody): Boolean {
            val head = policy.lastOrNull() ?: repository.authorisedPolicyHead()
            val unsigned = OwnerPolicyEntry(LedgerEntryId(newEntryId()), (head?.policySequence ?: 0L) + 1,
                head?.id, repository.loadOwnerPolicyState().authorityGeneration, ownerNpub, "", body)
            val signed = ownerPolicySigner.sign(unsigned) ?: return false
            if (!policyVerifier(signed)) return false
            val required = AuthorityPairing.requiredFor(body) ?: return false
            return addPolicy(signed, required)
        }

        private fun storedHead(scope: AuthorityScope): LedgerEntryId? =
            repository.currentAuthority()[scope]?.id

        val isEmpty: Boolean get() = policy.isEmpty() && entries.isEmpty()
    }

    /**
     * Signs one act for [subject], or `null` if this device would not or could
     * not sign it.
     *
     * Verified against the manifest's key for this device before it is handed
     * back. The signing identity is read live, so a signature that came back
     * from the wrong key — an identity switch, a signer that fell back to a
     * local key — would be a valid signature that admission then refuses,
     * failing the whole action instead of this one act.
     */
    private suspend fun attest(
        subject: LedgerEntryId,
        scope: AuthorityScope,
        effect: AuthorityEffect,
        capability: DeviceCapability,
        parent: LedgerEntryId?,
    ): AuthorityAttestation? {
        val device = authorityDevice ?: return null
        val signer = attestationSigner ?: return null
        // A second line, not the first one. [administrativeWrite] already
        // refused before this call was built, which is what keeps the person
        // from being prompted at all — the entry this act authorises is signed
        // before the act exists, so a check that lived only here would arrive
        // one prompt too late. Kept because anything that reaches signing
        // without coming through that entry has to fail closed too.
        if (repository.administrativeWritesLocked()) return null
        val manifest = repository.loadDeviceAuthority()
        if (!manifest.can(device, capability)) return null

        val signed = signer.sign(
            AuthorityAttestation(
                id = LedgerEntryId(newEntryId()),
                scope = scope,
                subject = subject,
                device = device,
                parent = parent,
                // The whole manifest state this device is signing against.
                // For an act authorising a manifest mutation this is the same
                // frontier as before that mutation, because the entry is
                // written on top of exactly this state.
                manifestContext = ManifestContext.of(manifest.frontier),
                authorityGeneration = manifest.authorityGeneration,
                capability = capability,
                effect = effect,
                signature = "",
            ),
        ) ?: return null

        val publicKey = manifest.devices[device]?.publicKey ?: return null
        return signed.takeIf { attestationVerifier?.verify(it, publicKey) == true }
    }

    /**
     * Stores a fully signed action, or nothing.
     *
     * Reaching here means every signature is in hand and verified, so the only
     * way this fails is an entry the ledger will not admit — a broken invariant
     * of ours, reported rather than half-applied.
     *
     * The lock is read again here, immediately before the write. [attest]
     * already refused to sign under it, but signing an external signature can
     * take as long as the person takes to approve it, and a preview may have
     * been opened in between. Every mutation on this class runs inside [mutex],
     * so nothing else in *this* install can interleave — the second read is
     * about the gap between deciding and writing within one call, not about a
     * concurrent caller. Cheap, and the alternative is trusting a decision that
     * may be minutes old.
     */
    private fun commit(batch: Batch): SharingWriteResult = when {
        batch.isEmpty -> SharingWriteResult.NoChange
        repository.administrativeWritesLocked() ->
            SharingWriteResult.Failed(SharingWriteError.RECOVERY_LOCKED)
        repository.commitAll(batch.policy, batch.entries, batch.attestations) -> SharingWriteResult.Ok
        else -> SharingWriteResult.Failed(SharingWriteError.REJECTED)
    }

    /**
     * The single entry every locally authored administrative change comes
     * through: takes [mutex], refuses while a recovery preview is open, and
     * only then runs [body].
     *
     * The refusal has to happen *here*, before anything is built. A permission
     * change signs its ledger entry, and a policy change its policy entry,
     * before the act that authorises either exists — so a check further down,
     * on the act-signing path or at the write door, arrives after the person has
     * already been sent to their signer. [restore] was the sharpest case: one
     * prompt per relationship in [signDeviceGenerations], every one of them for
     * an entry then dropped with `RECOVERY_LOCKED`. A refusal that costs a
     * prompt teaches people the prompt is noise, and with an external signer
     * that prompt is a whole other app.
     *
     * Not everything on this class comes through here, and deliberately.
     * [ingestPeerSignedEntry] and the debug peer simulations carry the *peer's*
     * signature rather than any authority of ours; [exportBackup] authors
     * nothing; and [recover] is the path that resolves a preview, so blocking it
     * would make the lock permanent.
     */
    private suspend fun administrativeWrite(
        body: suspend () -> SharingWriteResult,
    ): SharingWriteResult = mutex.withLock {
        if (repository.administrativeWritesLocked()) {
            return@withLock SharingWriteResult.Failed(SharingWriteError.RECOVERY_LOCKED)
        }
        body().also { result ->
            if (result.isSuccess && transportAvailable) runCatching {
                prepareFriendshipTransport()
                policyTransport?.synchronize()
                snapshotExchange?.synchronize()
                prepareFriendshipTransport()
                continuous?.synchronize()
                onContinuousChange()
            }
        }
    }

    /**
     * The one failure that is not the signer's fault, told apart from it.
     *
     * A batch that produced nothing can mean two different things — this device
     * may not do it, or the person declined the prompt — and the screen says
     * something different for each.
     */
    private fun notSigned(capability: DeviceCapability = DeviceCapability.MUTATE_PERMISSIONS) = when {
        repository.administrativeWritesLocked() ->
            SharingWriteResult.Failed(SharingWriteError.RECOVERY_LOCKED)
        isAuthorised(capability) -> SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE)
        else -> SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED)
    }

    // ------------------------------------------------------------- reading

    fun snapshot(): SharingUiState {
        val policy = repository.loadPolicy()
        val projection = repository.loadProjection()
        return SharingUiState(
            gate = gate,
            nativeAvailable = snapshotExchange?.nativeAvailable == true,
            baselines = policy.baselines,
            peers = projection.relationships.values
                .sortedBy { it.peer.value }
                .map { state -> summarise(policy, state) },
        )
    }

    private fun summarise(policy: SharingPolicy, state: RelationshipState) = PeerSummary(
        peer = state.peer,
        circle = state.circle,
        status = state.status,
        releasedCount = SharingCategory.entries.count { category ->
            effective(policy, state, category).effect == AccessEffect.ALLOW
        },
        pendingConsentCount = state.pendingConsentCategories.size,
    )

    fun peerDetail(peer: PeerId): PeerDetail? {
        val policy = repository.loadPolicy()
        val state = repository.loadProjection().relationships[peer] ?: return null
        val peerPolicy = policy.peers[peer]

        return PeerDetail(
            peer = peer,
            circle = state.circle,
            status = state.status,
            categories = SharingCategory.entries.map { category ->
                CategoryRow(
                    category = category,
                    policyDecision = com.cruxcoach.domain.sharing.SharingPolicyResolver
                        .resolve(policy, peer, category),
                    effectiveDecision = effective(policy, state, category),
                )
            },
            objectRules = peerPolicy?.objectRules.orEmpty().map { (key, effect) ->
                ObjectRuleRow(key.objectId, key.category, effect)
            }.sortedBy { it.objectId.value },
            devices = deviceRows(state),
            consentedCategories = state.consentedCategories,
            pendingConsentCategories = state.pendingConsentCategories,
            awaitingRevokeSync = state.awaitingRevokeSync,
            failClosedReason = state.failClosedReason,
            expiresAt = state.expiresAt,
            snapshotRole = snapshotExchange?.pinnedRole(peer.value),
        )
    }

    private fun categoryOf(peer: PeerId, objectId: ObjectId): SharingCategory =
        repository.objectRuleCategory(peer, objectId) ?: SharingCategory.VIDEOS

    private fun deviceRows(state: RelationshipState): List<DeviceRow> =
        (state.authorisedDevices + state.revokedDevices)
            .distinct()
            .sortedBy { it.value }
            .map { DeviceRow(it, it in state.authorisedDevices, it in state.revokedDevices) }

    /**
     * Evaluated from the *owner's* seat: the question the screen answers is
     * "does this peer get it", so the peer's own authorised device is used as
     * the reading device, and the relationship's own epoch as the local one.
     */
    private fun effective(policy: SharingPolicy, state: RelationshipState, category: SharingCategory) =
        EffectiveAccessResolver.resolve(
            policy = policy,
            relationship = state,
            peer = state.peer,
            category = category,
            objectId = null,
            device = state.authorisedDevices.firstOrNull(),
            localResourceEpoch = state.resourceEpoch,
            nowEpochMillis = nowEpochMillis(),
        )

    /**
     * A body-only preview of [peer]'s entries, with local-only bodies filtered.
     *
     * **This is not a wire format and nothing sends it.** It deliberately omits
     * the entry id, sequence, generations, signer and signature, so it could
     * not be transmitted even if a transport existed — which it does not
     * (§4). It exists for one purpose: asserting that bodies which must never
     * leave the device, such as the owner's private circle assignment, are
     * filtered out of anything peer-facing.
     */
    fun nonTransmittableBodyPreview(peer: PeerId): List<String> =
        repository.loadLedger(peer).filterNot { it.body.isLocalOnly }.map { entry ->
            val (kind, payload) = SharingLedgerCodec.encodeBody(entry.body)
            "$kind$payload"
        }

    private suspend fun snapshotWrite(body: suspend () -> SharingWriteResult): SharingWriteResult = administrativeWrite {
        try { body() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: MarmotTransportFailure) { nativeFailure(failure) }
        catch (_: Exception) { SharingWriteResult.Failed(SharingWriteError.REJECTED) }
    }
    private fun nativeFailure(failure: MarmotTransportFailure) = SharingWriteResult.Failed(when (failure.code) {
        "native_output_limit", "native_storage_quota", "native_ingest_quota", "payload_or_lifetime", "relay_payload_limit", "peer_quota" -> SharingWriteError.TRANSPORT_LIMIT
        "history_pending", "session_not_stable", "session_retired", "stale_fence", "invitation_pending", "no_session", "reopen_required", "native_ingest_failed" -> SharingWriteError.SESSION_UNAVAILABLE
        "discovery_not_enabled" -> SharingWriteError.DISCOVERY_REQUIRED
        "peer_discovery_missing", "peer_inbox_missing", "peer_relays_not_configured", "valid_keypackage_missing" -> SharingWriteError.PEER_UNAVAILABLE
        else -> SharingWriteError.REJECTED
    })

    suspend fun pinSnapshotPeer(peer: PeerId, role: SnapshotEndpointRole): SharingWriteResult = snapshotWrite {
        if (!isAuthorised(DeviceCapability.MUTATE_PERMISSIONS)) return@snapshotWrite notSigned()
        if (snapshotExchange?.pinPeer(peer.value, role) == true) SharingWriteResult.Ok
        else SharingWriteResult.Failed(SharingWriteError.REJECTED)
    }

    fun snapshotViews(peer: PeerId): List<SharingSnapshotView> =
        runCatching { snapshotExchange?.views()?.filter { it.peer == peer.value }.orEmpty() }.getOrDefault(emptyList())

    fun readSnapshot(id: String): String? = runCatching { snapshotExchange?.read(id) }.getOrNull()

    suspend fun shareNoteSnapshot(peer: PeerId, text: String, peerRole: SnapshotEndpointRole): SharingWriteResult = snapshotWrite {
        if (!isAuthorised(DeviceCapability.MUTATE_PERMISSIONS)) return@snapshotWrite notSigned()
        if (!transportAvailable) return@snapshotWrite SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE)
        val exchange = snapshotExchange ?: return@snapshotWrite SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE)
        val bytes = text.encodeToByteArray()
        try {
            if (text.isBlank() || bytes.size > SharingSnapshotExchange.MAX_CONTENT_BYTES) {
                return@snapshotWrite SharingWriteResult.Failed(SharingWriteError.REJECTED)
            }
            val state = repository.loadProjection().relationships[peer]
                ?: return@snapshotWrite SharingWriteResult.Failed(SharingWriteError.REJECTED)
            if (!exchange.pinPeer(peer.value, peerRole)) return@snapshotWrite SharingWriteResult.Failed(SharingWriteError.REJECTED)
            val item = "snapshot-draft:${newEntryId()}"
            val handle = com.cruxcoach.domain.sharing.SharingKeyHandles.ownerObject(ObjectId(item))
            val key = repository.createDataKey(handle, state.resourceEpoch)
            repository.storeSealedItem(item, SharingCategory.PRIVATE_NOTES, handle, key, bytes, state.resourceEpoch)
            val expiresAt = minOf(nowEpochMillis() + 86_400_000L, state.expiresAt ?: Long.MAX_VALUE)
            val id = runCatching { exchange.offer(peer.value, item, state.resourceEpoch, expiresAt) }.getOrNull()
            if (id == null) {
                repository.discardSnapshotDraft(handle)
                SharingWriteResult.Failed(SharingWriteError.REJECTED)
            } else SharingWriteResult.Ok // Post-commit synchronization is handled by administrativeWrite.
        } finally { bytes.fill(0) }
    }

    suspend fun acceptSnapshot(id: String): SharingWriteResult = snapshotWrite {
        val exchange = snapshotExchange ?: return@snapshotWrite SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE)
        if (!exchange.accept(id)) SharingWriteResult.Failed(SharingWriteError.REJECTED)
        else SharingWriteResult.Ok
    }

    suspend fun revokeSnapshot(id: String): SharingWriteResult = snapshotWrite {
        val exchange = snapshotExchange ?: return@snapshotWrite SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE)
        if (!exchange.revoke(id)) SharingWriteResult.Failed(SharingWriteError.REJECTED)
        else SharingWriteResult.Ok
    }

    suspend fun synchronizeSnapshots(): SharingWriteResult = mutex.withLock {
        if (!transportAvailable) SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE)
        else runCatching {
            val exchange = snapshotExchange ?: return@withLock SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE)
            prepareFriendshipTransport()
            marmot?.port?.refresh()
            prepareFriendshipTransport()
            policyTransport?.synchronize()
            exchange.synchronize(refreshTransport = marmot == null)
            continuous?.synchronize()
            SharingWriteResult.Ok
        }.getOrElse { if (it is MarmotTransportFailure) nativeFailure(it) else SharingWriteResult.Failed(SharingWriteError.REJECTED) }
    }

    fun pendingFriendships(): List<PendingFriendshipView> = continuous?.pendingRequests().orEmpty()
    suspend fun cancelFriendshipRequest(peer: String): SharingWriteResult = administrativeWrite {
        continuous?.cancelRequest(peer); SharingWriteResult.Ok
    }
    private fun prepareFriendshipTransport() {
        val adapter = marmot ?: return
        FriendshipTransportPreparation.run(adapter.port, continuous?.pendingRequests().orEmpty())
    }
    fun continuousViews(): List<ContinuousView> = runCatching { continuous?.views().orEmpty() }.getOrDefault(emptyList())
    fun continuousPeerRoles(): Map<PeerId, SnapshotEndpointRole> = repository.loadProjection().relationships.keys.associateWith {
        snapshotExchange?.pinnedRole(it.value) ?: SnapshotEndpointRole.USER
    }
    fun continuousChanges(): kotlinx.coroutines.flow.Flow<Unit> = continuous?.changes() ?: kotlinx.coroutines.flow.emptyFlow()
    fun continuousHasWork(): Boolean = runCatching { continuous?.hasWork() == true || discoveryEnabled() }.getOrDefault(false)
    fun continuousPreview(peer: PeerId, scope: ContinuousScope): List<ContinuousRecord> {
        val policy = repository.loadPolicy().peers[peer]
        return continuous?.preview(scope).orEmpty().filter {
            policy?.objectRules?.get(ObjectRuleKey(ObjectId(it.id), it.category)) != AccessEffect.DENY
        }
    }

    /** A reviewed selection of current peers; future circle members are not
     * subscribed. Existing snapshots and category consent grant no subscription. */
    suspend fun offerContinuous(peers: Set<PeerId>, scope: ContinuousScope, roles: Map<PeerId, SnapshotEndpointRole>): SharingWriteResult = administrativeWrite {
        val exchange = continuous ?: return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE)
        if (!isAuthorised(DeviceCapability.MUTATE_PERMISSIONS) || peers.isEmpty() || peers.size > ContinuousCodec.MAX_ACTIVE ||
            !ContinuousCodec.valid(scope)) return@administrativeWrite notSigned()
        val states = repository.loadProjection().relationships
        if (roles.keys != peers)
            return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.SESSION_UNAVAILABLE)
        try { peers.forEach { continuousPreview(it, scope) } } catch (_: IllegalArgumentException) {
            return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.TRANSPORT_LIMIT)
        }
        val parentJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        exchange.selectionTransaction {
            kotlinx.coroutines.runBlocking(parentJob ?: kotlin.coroutines.EmptyCoroutineContext) {
                for (peer in peers) {
                    if (snapshotExchange?.pinPeer(peer.value, roles.getValue(peer)) != true)
                        return@runBlocking SharingWriteResult.Failed(SharingWriteError.REJECTED)
                    for (category in scope.categories) {
                        if (SharingPolicyResolver.resolve(repository.loadPolicy(), peer, category).isAllowed) continue
                        val result = appendPolicy(OwnerPolicyBody.PeerRuleSet(peer, category, AccessEffect.ALLOW), onlyPeer = peer)
                        if (!result.isSuccess) return@runBlocking result
                    }
                    val result = offerLocked(peer, states[peer]?.circle ?: SharingCircle.FRIENDS, emptySet())
                    if (!result.isSuccess) return@runBlocking result
                    if (exchange.offer(peer.value, scope) == null)
                        return@runBlocking SharingWriteResult.Failed(SharingWriteError.REJECTED)
                }
                SharingWriteResult.Ok
            }
        }
    }
    suspend fun acceptContinuous(id: String, ownerRole: SnapshotEndpointRole,
        ownScope: ContinuousScope = ContinuousScope(emptySet(), "1970-01-01")): SharingWriteResult = administrativeWrite {
        val view = continuous?.views()?.firstOrNull { it.offer.id == id && !it.outgoing && it.status == "INVITED" }
            ?: return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.REJECTED)
        val parentJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        continuous.selectionTransaction {
            kotlinx.coroutines.runBlocking(parentJob ?: kotlin.coroutines.EmptyCoroutineContext) {
                val peer = PeerId(view.offer.owner)
                if (!ContinuousCodec.valid(ownScope) || ownerRole != view.offer.ownerRole || snapshotExchange?.pinPeer(peer.value, ownerRole) != true)
                    return@runBlocking SharingWriteResult.Failed(SharingWriteError.REJECTED)
                // Only this account's outgoing policy is changed by its selection.
                for (category in ownScope.categories) {
                    val result = appendPolicy(OwnerPolicyBody.PeerRuleSet(peer, category, AccessEffect.ALLOW), onlyPeer = peer)
                    if (!result.isSuccess) return@runBlocking result
                }
                val result = offerLocked(peer, repository.loadProjection().relationships[peer]?.circle ?: SharingCircle.FRIENDS, emptySet())
                if (!result.isSuccess) return@runBlocking result
                if (!continuous.accept(id, ownScope)) return@runBlocking SharingWriteResult.Failed(SharingWriteError.REJECTED)
                SharingWriteResult.Ok
            }
        }
    }
    suspend fun endContinuous(id: String, pause: Boolean = false): SharingWriteResult = administrativeWrite {
        if (continuous?.end(id, pause) == true) { onContinuousChange(); SharingWriteResult.Ok }
        else SharingWriteResult.Failed(SharingWriteError.REJECTED)
    }

    /** Called off the UI thread by lifecycle/reconnect/WorkManager. Only prior
     * discovery/request opt-in permits connectivity preparation. Friendship
     * acceptance and an interactive signer fallback never occur here. */
    suspend fun synchronizeContinuousAutomatically(): Boolean = mutex.withLock {
        val adapter = marmot ?: return@withLock false
        if (!continuousHasWork()) return@withLock true
        try {
            val success = adapter.withoutInteractiveSigning {
                prepareFriendshipTransport()
                adapter.port.refresh()
                prepareFriendshipTransport()
                policyTransport?.synchronize()
                continuous?.synchronize()
                !adapter.unattendedSignerRequired && adapter.port.relaySummary().values.any { it == "accepted" }
            }
            continuous?.automationResult(if (adapter.unattendedSignerRequired) "NEEDS_OPEN" else if (!success) "OFFLINE" else null)
            success
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            continuous?.automationResult(if (adapter.unattendedSignerRequired) "NEEDS_OPEN" else
                if (failure is MarmotTransportFailure && failure.code in setOf("native_storage_quota", "native_ingest_quota", "source_quota", "native_output_limit")) "CAPACITY" else "RETRY")
            false
        }
    }

    fun discoveryEnabled(): Boolean = runCatching { marmot?.port?.discoveryEnabled() == true }.getOrDefault(false)
    fun sharingClockHealthy(): Boolean = repository.sharingClockHealthy()
    fun snapshotCapacityReached(): Boolean = snapshotExchange?.atCapacity() == true
    suspend fun collectExpiredSnapshots(): SharingWriteResult = mutex.withLock {
        snapshotExchange?.collectExpired(); SharingWriteResult.Ok
    }
    suspend fun recoverSharingClock(archiveTransport: Boolean = false): SharingWriteResult = administrativeWrite {
        if (!isAuthorised(DeviceCapability.MUTATE_PERMISSIONS)) return@administrativeWrite notSigned()
        continuous?.endAll()
        val batch = Batch()
        for ((peer, state) in repository.loadProjection().relationships) {
            if (!state.status.isTerminal && state.offeredCategories.isNotEmpty() &&
                !batch.sign(peer, SharingLedgerBody.GrantChanged(emptySet()))) return@administrativeWrite notSigned()
        }
        val result = if (batch.isEmpty) SharingWriteResult.Ok else commit(batch)
        if (!result.isSuccess) result
        else if (!repository.recoverSharingClock()) SharingWriteResult.Failed(SharingWriteError.REJECTED)
        else try {
            // Rehydrate a native engine poisoned by an earlier rolled-back
            // operation. Actual disposal must succeed before archiving it.
            marmot?.port?.shutdown()
            continuous?.discardRetiredPayloads()
            if (!archiveTransport || marmot?.archiveAfterWithdrawal() == true) SharingWriteResult.Ok
            else SharingWriteResult.Failed(SharingWriteError.REJECTED)
        } catch (failure: MarmotTransportFailure) { nativeFailure(failure) }
    }

    fun nativePeers(): List<MarmotPeerStatus> = runCatching { marmot?.port?.peers().orEmpty() }.getOrDefault(emptyList())
    fun nativeRelays(): List<String> = marmot?.relayPool() ?: MarmotRelayDefaults.urls
    fun nativeRelayStatus(): Map<String, String> = runCatching { marmot?.port?.relaySummary().orEmpty() }.getOrDefault(emptyMap())
    fun incomingPolicy(peer: PeerId): IncomingSharingPolicy? = runCatching { if (continuous?.views()?.any { it.offer.owner == peer.value || it.offer.recipient == peer.value } == true) null else policyTransport?.incoming(peer.value) }.getOrNull()
    suspend fun bootstrapMarmot(): SharingWriteResult = snapshotWrite {
        val adapter = marmot ?: return@snapshotWrite SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE)
        if (!isAuthorised(DeviceCapability.MUTATE_PERMISSIONS)) return@snapshotWrite notSigned()
        adapter.port.bootstrap(); SharingWriteResult.Ok
    }
    suspend fun configureMarmotRelays(relays: List<String>): SharingWriteResult = snapshotWrite {
        if (!isAuthorised(DeviceCapability.MUTATE_PERMISSIONS)) return@snapshotWrite notSigned()
        val adapter = marmot ?: return@snapshotWrite SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE)
        adapter.configureRelays(relays); SharingWriteResult.Ok
    }
    suspend fun connectMarmot(peer: PeerId, accept: Boolean, group: String? = null): SharingWriteResult = snapshotWrite {
        if (!isAuthorised(DeviceCapability.MUTATE_PERMISSIONS)) return@snapshotWrite notSigned()
        val adapter = marmot ?: return@snapshotWrite SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE)
        if (repository.loadProjection().relationships[peer] == null) {
            val created = offerLocked(peer, SharingCircle.ALL_OTHER_USERS, emptySet())
            if (!created.isSuccess) return@snapshotWrite created
        }
        if (accept) adapter.port.acceptInvitation(peer.value, group) else adapter.port.invite(peer.value)
        SharingWriteResult.Ok
    }
    suspend fun resetMarmotPeer(peer: PeerId): SharingWriteResult = snapshotWrite {
        if (!isAuthorised(DeviceCapability.MUTATE_PERMISSIONS)) return@snapshotWrite notSigned()
        val adapter = marmot ?: return@snapshotWrite SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE)
        adapter.port.resetPeer(peer.value); SharingWriteResult.Ok
    }
    suspend fun acceptRemotePolicy(peer: PeerId, categories: Set<SharingCategory>): SharingWriteResult = snapshotWrite {
        if (policyTransport?.accept(peer.value, categories) == true) SharingWriteResult.Ok
        else SharingWriteResult.Failed(SharingWriteError.REJECTED)
    }

    // ------------------------------------------------------------- writing

    /**
     * A baseline reaches every relationship in that circle **or a narrower
     * one**, because baselines inherit downwards.
     */
    suspend fun setBaseline(
        circle: SharingCircle,
        category: SharingCategory,
        granted: Boolean,
    ): SharingWriteResult = administrativeWrite {
        appendPolicy(OwnerPolicyBody.CircleBaselineSet(circle, category, granted)) { peerCircle ->
            peerCircle.rank >= circle.rank
        }
    }

    suspend fun setPeerRule(
        peer: PeerId,
        category: SharingCategory,
        effect: AccessEffect?,
    ): SharingWriteResult = administrativeWrite {
        appendPolicy(OwnerPolicyBody.PeerRuleSet(peer, category, effect), onlyPeer = peer)
    }

    suspend fun setObjectRule(
        peer: PeerId,
        objectId: ObjectId,
        category: SharingCategory,
        effect: AccessEffect?,
    ): SharingWriteResult = administrativeWrite {
        appendPolicy(OwnerPolicyBody.ObjectRuleSet(peer, objectId, category, effect), onlyPeer = peer)
    }

    /**
     * Files [peer] in [circle] locally, then offers [categories].
     *
     * Two entries, not one: the circle is the owner's private social judgement
     * and never leaves the device, while the offer is what the peer receives.
     */
    suspend fun offer(
        peer: PeerId,
        circle: SharingCircle,
        categories: Set<SharingCategory>,
    ): SharingWriteResult = administrativeWrite { offerLocked(peer, circle, categories) }

    /**
     * Both entries are signed before either is written.
     *
     * With an external signer each signature is its own approval prompt, so
     * asking for the second one after storing the first would leave a peer
     * filed in a circle with no offer at all if the person walked away — a
     * relationship that exists and grants nothing, with no way to tell it from
     * a deliberate one.
     */
    private suspend fun offerLocked(
        peer: PeerId,
        circle: SharingCircle,
        categories: Set<SharingCategory>,
    ): SharingWriteResult {
        val batch = Batch()
        if (!batch.sign(peer, SharingLedgerBody.PeerCircleAssigned(circle))) return notSigned()
        if (!batch.sign(peer, SharingLedgerBody.RelationshipOffered(categories))) return notSigned()
        return commit(batch)
    }

    /**
     * Invites [peer] into [circle], offering what the policy already allows.
     *
     * There is no category picker, and offering nothing was the wrong default:
     * consent is clamped to what was offered, so an empty offer produced a
     * relationship that could be accepted and still release nothing, for ever.
     */
    suspend fun invite(peer: PeerId, circle: SharingCircle): SharingWriteResult = administrativeWrite {
        offerLocked(peer, circle, repository.loadOwnerPolicyState().allowedCategoriesFor(peer, circle))
    }

    /**
     * Moves [peer] into [circle] and re-syncs their offer.
     *
     * The circle decides which baselines reach them, so changing it changes
     * what the policy allows — leaving the offer behind would reproduce exactly
     * the mismatch this synchronisation exists to prevent.
     */
    suspend fun setPeerCircle(peer: PeerId, circle: SharingCircle, clearPersonalExceptions: Boolean = false): SharingWriteResult = administrativeWrite {
        val batch = Batch()
        var policy = repository.loadOwnerPolicyState()
        if (clearPersonalExceptions) {
            for (category in policy.peerRules[peer].orEmpty().keys)
                if (!batch.signPolicy(OwnerPolicyBody.PeerRuleSet(peer, category, null))) return@administrativeWrite notSigned()
            for (key in policy.objectRules[peer].orEmpty().keys)
                if (!batch.signPolicy(OwnerPolicyBody.ObjectRuleSet(peer, key.objectId, key.category, null))) return@administrativeWrite notSigned()
            policy = policy.copy(peerRules = policy.peerRules - peer, objectRules = policy.objectRules - peer)
        }
        if (!batch.sign(peer, SharingLedgerBody.PeerCircleAssigned(circle))) return@administrativeWrite notSigned()
        if (!planOfferSync(batch, policy, onlyPeer = peer, movedTo = peer to circle)) return@administrativeWrite notSigned()
        // No partial clearing (especially of a DENY) if any later signature is refused.
        commit(batch)
    }

    /**
     * Ingests an acceptance the **peer** signed.
     *
     * The owner cannot produce this: the reducer requires the signer to be the
     * peer, which is the whole point of consent. In release the entry has to
     * arrive already signed, so this is the only door; with the native
     * transport gated shut nothing walks through it yet, and a caller who has
     * no peer-signed entry simply cannot accept.
     */
    suspend fun ingestPeerSignedEntry(entry: SharingLedgerEntry): Boolean =
        mutex.withLock { ingestLocked(entry) }

    /**
     * Takes the same lock as an owner mutation: an inbound entry claims an
     * exact next sequence, and one arriving while a local change is mid-signing
     * would be checked against a projection that is about to move.
     */
    private fun ingestLocked(entry: SharingLedgerEntry): Boolean {
        if (entry.signerNpub != entry.peer.value) return false
        // Everything else — the cryptographic signature, the role the body
        // requires, the exact next sequence, the generations and the reduced
        // outcome — is decided by admission *before* anything is written, so a
        // rejected entry leaves no row behind to poison later reductions.
        return repository.tryAppendEntry(entry)
    }

    /** True when this build can stand in for [peer] — debug demo peers only. */
    fun canSimulatePeer(peer: PeerId): Boolean = peerSimulator?.cryptoFor(peer) != null

    /**
     * Debug-only: signs an acceptance **as the peer**, using a demo key this
     * build actually holds. Returns false in release, where no such key exists.
     */
    suspend fun simulateAccept(peer: PeerId, categories: Set<SharingCategory>, device: DeviceId): Boolean =
        simulateAsPeer(peer, SharingLedgerBody.RecipientAccepted(categories, device))

    /** Debug-only counterpart of [simulateAccept]. */
    suspend fun simulateDecline(peer: PeerId): Boolean =
        simulateAsPeer(peer, SharingLedgerBody.RecipientDeclined)

    /**
     * Debug-only: authorises a device **as the peer**.
     *
     * Authorising a device is the peer proving that this device is theirs, so
     * there is no owner-signed version of it — otherwise "every device is
     * authorised separately" would just mean the owner naming any device.
     */
    suspend fun simulateAuthorizeDevice(peer: PeerId, device: DeviceId): Boolean =
        simulateAsPeer(peer, SharingLedgerBody.DeviceAuthorized(device))

    /**
     * The peer's key is held in this process, so this path is genuinely local —
     * but it still goes through the same lock and the same admission, so a demo
     * acceptance cannot slip past a mutation that is waiting on a signer.
     */
    private suspend fun simulateAsPeer(peer: PeerId, body: SharingLedgerBody): Boolean = mutex.withLock {
        val crypto = peerSimulator?.cryptoFor(peer) ?: return@withLock false
        val current = repository.loadProjection().relationships[peer]
        // A reply answers a specific entry, so it hangs off the branch that
        // stands rather than off whatever has the highest sequence.
        val head = repository.authorisedHead(peer)
        val unsigned = SharingLedgerEntry(
            id = LedgerEntryId(newEntryId()),
            peer = peer,
            policySequence = (head?.policySequence ?: 0L) + 1L,
            parent = head?.id,
            authorityGeneration = current?.authorityGeneration ?: 1L,
            resourceEpoch = current?.resourceEpoch ?: 1L,
            deviceGeneration = current?.deviceGeneration ?: 1L,
            signerNpub = peer.value,
            signature = "",
            body = body,
        )
        val signed = SharingLedgerSigner(crypto).sign(unsigned) ?: return@withLock false
        ingestLocked(signed)
    }

    suspend fun changeGrant(peer: PeerId, categories: Set<SharingCategory>): SharingWriteResult =
        administrativeWrite { appended(append(peer, SharingLedgerBody.GrantChanged(categories))) }

    /** Millisecond Unix expiry; shortening is immediate, extension needs fresh consent. */
    suspend fun setExpiry(peer: PeerId, expiresAt: Long?): SharingWriteResult = administrativeWrite {
        if (expiresAt != null && expiresAt <= nowEpochMillis()) {
            return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.REJECTED)
        }
        val state = repository.loadProjection().relationships[peer]
            ?: return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.REJECTED)
        if (state.status.isTerminal) return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.REJECTED)
        if (state.expiresAt == expiresAt) return@administrativeWrite SharingWriteResult.NoChange
        appended(append(peer, SharingLedgerBody.RelationshipOffered(state.offeredCategories, expiresAt)))
    }

    suspend fun revokeDevice(peer: PeerId, device: DeviceId): SharingWriteResult =
        administrativeWrite { appended(append(peer, SharingLedgerBody.DeviceRevoked(device))) }

    suspend fun revoke(peer: PeerId): SharingWriteResult =
        administrativeWrite {
            continuous?.endPeer(peer.value)
            appended(append(peer, SharingLedgerBody.RelationshipRevoked))
        }

    suspend fun markDeliveryUnclear(peer: PeerId, device: DeviceId): SharingWriteResult =
        administrativeWrite { appended(append(peer, SharingLedgerBody.KeyDeliveryUnclear(device))) }

    suspend fun confirmDelivery(peer: PeerId, device: DeviceId): SharingWriteResult =
        administrativeWrite { appended(append(peer, SharingLedgerBody.KeyDeliveryConfirmed(device))) }

    /**
     * Records the removal, then deletes everything this device holds about the
     * relationship.
     *
     * The order matters and so does the guard: the delete is irreversible, so
     * it must not happen when the ledger cannot even record that it did. One
     * that ran without an entry would leave a relationship whose history says
     * it is still active and whose data is gone.
     *
     * This removes **local relationship data**. It does not end the recipient's
     * cryptographic access — that needs a key wrapped for them, which no live
     * path has ever created — and it deliberately leaves the owner's own
     * content keys and ciphertexts alone. The screen says as much before asking
     * for confirmation.
     */
    suspend fun purge(peer: PeerId, onStep: (CryptoEraseStep) -> Unit = {}): SharingWriteResult =
        administrativeWrite {
            continuous?.endPeer(peer.value)
            if (!append(peer, SharingLedgerBody.PurgeRequested)) return@administrativeWrite notSigned()
            val outcome = repository.purgeLocalRelationshipData(peer, onStep)
            if (outcome.completed) {
                SharingWriteResult.Ok
            } else {
                SharingWriteResult.Failed(SharingWriteError.REJECTED)
            }
        }

    // -------------------------------------------------------------- backup

    /**
     * Writes an encrypted permission backup.
     *
     * The exported data keys live in the payload for exactly as long as it
     * takes to encrypt it, then they are zeroized. The recovery code is used to
     * derive the file key and is never written into the file.
     */
    suspend fun exportBackup(recoveryCode: String): SharingBackupWriteOutcome = mutex.withLock {
        if (!repository.isCurrentAccount()) return@withLock SharingBackupWriteOutcome.Failed(SharingBackupError.WRONG_IDENTITY)
        val crypto = backupCrypto
            ?: return@withLock SharingBackupWriteOutcome.Failed(SharingBackupError.SIGNER_UNAVAILABLE)
        val payload = runCatching { repository.collectBackupPayload() }.getOrElse {
            return@withLock SharingBackupWriteOutcome.Failed(SharingBackupError.WRONG_IDENTITY)
        }
        try {
            // The envelope is signed by the same identity as the ledgers, which
            // for an external signer means one more approval prompt — with the
            // exported data keys sitting in memory until it comes back, hence
            // the `finally`.
            when (val written = SharingBackupEnvelope.write(payload, recoveryCode, ownerNpub, crypto)) {
                is SharingBackupWriteResult.Written -> if (repository.isCurrentAccount()) SharingBackupWriteOutcome.Written(written.bytes)
                    else { written.bytes.fill(0); SharingBackupWriteOutcome.Failed(SharingBackupError.WRONG_IDENTITY) }
                is SharingBackupWriteResult.Failed -> SharingBackupWriteOutcome.Failed(written.error)
            }
        } finally {
            payload.zeroizeKeys()
        }
    }

    // importBackup is gone. It read a file, took the code at face value and
    // restored outright — no root signature, no freshness check, no fencing,
    // no epoch rotation. A stolen backup plus its code was a takeover and an
    // old one silently re-granted withdrawn access. importRecovery is the only
    // way in, and it is a recovery.

    // ------------------------------------------------------------- restore

    fun newRecoveryCode(): String =
        SharingRecoveryCode.fromEntropy(ByteArray(20).also { random.nextBytes(it) })

    /**
     * Mints a fresh **device generation** for every relationship, retiring the
     * recipient-side authorisations issued under the old one, and leaves each
     * relationship fail-closed until revocations have been re-synced.
     *
     * ## This is not a recovery, and used to be called one
     *
     * It was named `restore`, and the recovery screen called it. It does none
     * of what §11.8 says a restore is: no root challenge, no new authority
     * generation, no re-enrolment of the returning device, nothing fenced. It
     * appends a `RestoreCompleted` per relationship and stops — which is a
     * device-generation bump, a useful operation with an honest name, and a
     * catastrophic one under a dishonest one.
     *
     * The recovery doors are [recoverThisDevice] and [importRecovery], which is
     * what `SharingRecoveryApiSurfaceTest` has always said. Nothing in the
     * product calls this; it is kept because the operation itself is real and
     * because several tests exercise the `ADMINISTER_DEVICES` requirement
     * through it. **Do not wire it to anything a person would read as
     * "restore".**
     */
    suspend fun advanceDeviceGenerations(
        entered: String,
        expected: String,
    ): SharingWriteResult = administrativeWrite {
        if (!SharingRecoveryCode.isValid(entered)) {
            return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.REJECTED)
        }
        if (SharingRecoveryCode.normalise(entered) != SharingRecoveryCode.normalise(expected)) {
            return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.REJECTED)
        }

        val states = repository.loadProjection().relationships
        if (states.isEmpty()) return@administrativeWrite SharingWriteResult.NoChange

        val generations = signDeviceGenerations(states) { repository.authorisedHead(it) }
            ?: return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE)

        // Each generation entry carries the act that authorises it, chained
        // per scope. Recovery is device administration, so the requirement is
        // ADMINISTER_DEVICES — which this device must actually hold.
        val acts = mutableListOf<AuthorityAttestation>()
        val heads = mutableMapOf<AuthorityScope, LedgerEntryId?>()
        val stored = repository.currentAuthority()
        generations.forEach { entry ->
            val required = AuthorityPairing.requiredFor(entry.peer, entry.body)
                ?: return@forEach
            val act = attest(
                subject = entry.id,
                scope = required.scope,
                effect = required.effect,
                capability = required.capability,
                parent = heads.getOrElse(required.scope) { stored[required.scope]?.id },
            ) ?: return@administrativeWrite notSigned(DeviceCapability.ADMINISTER_DEVICES)
            acts += act
            heads[required.scope] = act.id
        }

        if (!repository.commitAll(emptyList(), generations, acts)) {
            return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.REJECTED)
        }
        SharingWriteResult.Ok
    }

    /**
     * Signs a fresh device generation for each of [states]. Writes nothing.
     *
     * `null` as soon as one signature is refused, so the caller stores none of
     * them. With an external signer this is one approval prompt per
     * relationship, and abandoning it halfway used to leave some peers on a new
     * device generation and some on the old one — which is precisely the state
     * a restore exists to make impossible.
     */
    @Suppress("UnusedPrivateMember")
    private suspend fun signDeviceGenerations(
        states: Map<PeerId, RelationshipState>,
        headFor: (PeerId) -> SharingLedgerEntry?,
    ): List<SharingLedgerEntry>? {
        return states.map { (peer, state) ->
            val next = state.deviceGeneration + 1
            // Built on the branch that stands. A parentless entry would claim
            // to begin a relationship that already has a beginning.
            val head = headFor(peer)
            val entry = signer.sign(
                SharingLedgerEntry(
                    id = LedgerEntryId(newEntryId()),
                    peer = peer,
                    policySequence = (head?.policySequence ?: 0L) + 1L,
                    parent = head?.id,
                    authorityGeneration = state.authorityGeneration,
                    resourceEpoch = state.resourceEpoch,
                    deviceGeneration = next,
                    signerNpub = ownerNpub,
                    signature = "",
                    body = SharingLedgerBody.RestoreCompleted(next),
                )
            ) ?: return null
            if (!entryVerifier.verify(entry)) return null
            entry
        }
    }

    /**
     * A code-only recovery for the device that is already configured here.
     *
     * Takes neither the device key nor the generation. The key comes from the
     * manifest when this device is already enrolled and from
     * [authorityDevicePublicKey] otherwise — both keys this install actually
     * holds. The generation is the one this install currently establishes: a
     * caller that could name one could ask the owner to sign for an estate that
     * does not exist, and the repository would then be checking the
     * authorisation against a number the caller chose.
     */
    suspend fun recoverThisDevice(
        entered: String,
        expected: String,
        sovereignReset: Boolean = false,
    ): SharingRecoveryOutcome {
        val device = authorityDevice
        val authority = repository.loadDeviceAuthority()
        val key = device?.let { authority.devices[it]?.publicKey }
            ?: authorityDevicePublicKey
            ?: device?.value
            ?: ""
        return recover(
            entered = entered,
            expected = expected,
            newDevicePublicKey = key,
            backupGeneration = syncedGeneration() ?: authority.authorityGeneration,
            sovereignReset = sovereignReset,
        )
    }

    // ---------------------------------------------------- device administration

    /**
     * Enrols another of the owner's devices.
     *
     * Two signatures, deliberately. The device signature says which device
     * asked; the **root** signature on the manifest entry says it was allowed.
     * A device able to write the manifest on its own could promote itself, so
     * only the root identity ever signs there.
     */
    suspend fun enrolDevice(
        device: AuthorityDeviceId,
        publicKey: String,
        role: DeviceRole,
    ): SharingWriteResult = administrativeWrite {
        appendManifest(DeviceManifestBody.DeviceEnrolled(device, publicKey, role))
    }

    suspend fun changeDeviceRole(device: AuthorityDeviceId, role: DeviceRole): SharingWriteResult =
        administrativeWrite { appendManifest(DeviceManifestBody.DeviceRoleChanged(device, role)) }

    /**
     * Revokes one of the owner's own devices, leaving the others alone.
     *
     * Named apart from [revokeDevice], which revokes a *peer's* device. The two
     * appear a few lines apart on the screen and mean opposite things.
     */
    suspend fun revokeOwnDevice(device: AuthorityDeviceId): SharingWriteResult =
        administrativeWrite { appendManifest(DeviceManifestBody.DeviceRevoked(device)) }

    /**
     * Signs one manifest entry and the act that asked for it, and stores both
     * or neither.
     */
    private suspend fun appendManifest(body: DeviceManifestBody): SharingWriteResult {
        if (!isAuthorised(DeviceCapability.ADMINISTER_DEVICES)) {
            return notSigned(DeviceCapability.ADMINISTER_DEVICES)
        }
        val signer = manifestSigner ?: return notSigned(DeviceCapability.ADMINISTER_DEVICES)
        val manifest = repository.loadDeviceAuthority()

        val entry = signer.sign(
            DeviceManifestEntry(
                id = LedgerEntryId(newEntryId()),
                manifestSequence = repository.nextManifestSequence(),
                authorityGeneration = manifest.authorityGeneration,
                parent = manifest.head,
                signerNpub = ownerNpub,
                signature = "",
                body = body,
            ),
        ) ?: return SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE)

        val required = AuthorityPairing.requiredFor(body)
            ?: return SharingWriteResult.Failed(SharingWriteError.REJECTED)
        val act = attest(
            subject = entry.id,
            scope = required.scope,
            effect = required.effect,
            capability = required.capability,
            parent = repository.currentAuthority()[required.scope]?.id,
        ) ?: return SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE)

        return if (repository.commitRecovery(listOf(entry), emptyList(), listOf(act))) {
            SharingWriteResult.Ok
        } else {
            SharingWriteResult.Failed(SharingWriteError.REJECTED)
        }
    }


    /**
     * Enrols this install's own device as the first `PRIMARY`.
     *
     * Without this the model is unreachable. Enrolling needs
     * `ADMINISTER_DEVICES`, which only devices the manifest names hold, and a
     * fresh install names none — so every administrative path would be closed
     * for ever on a release build, with the person able to read their
     * permissions and change nothing.
     *
     * Genesis is therefore the one entry the **root** identity authorises
     * alone, because at that moment there is by construction no device that
     * could. It is refused once a manifest exists, so it cannot be used to mint
     * a second primary: after that, enrolling is an ordinary act-paired change.
     *
     * The key is this install's own. Enrolling one it does not hold would write
     * a manifest it cannot then act under.
     */
    suspend fun enrolGenesisDevice(): SharingWriteResult = administrativeWrite {
        val device = authorityDevice
            ?: return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED)
        val publicKey = authorityDevicePublicKey
            ?: return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED)
        val signer = manifestSigner
            ?: return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED)
        // Only ever the first entry. A manifest that already exists has devices
        // that can authorise the next one properly.
        if (repository.loadDeviceManifest().isNotEmpty()) {
            return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED)
        }

        val entry = signer.sign(
            DeviceManifestEntry(
                id = LedgerEntryId(newEntryId()),
                manifestSequence = 1,
                authorityGeneration = 1,
                parent = null,
                signerNpub = ownerNpub,
                signature = "",
                body = DeviceManifestBody.DeviceEnrolled(device, publicKey, DeviceRole.PRIMARY),
            ),
        ) ?: return@administrativeWrite SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE)

        // One entry, one transaction. A half-written genesis on an append-only
        // ledger is not something a later action can tidy up.
        val applied = repository.commitGenesisManifest(entry)
        if (applied) SharingWriteResult.Ok else SharingWriteResult.Failed(SharingWriteError.REJECTED)
    }

    /** The device list as the screen shows it. */
    fun deviceEstate(): DeviceEstate {
        val manifest = repository.loadDeviceAuthority()
        val rows = manifest.devices.values
            .sortedBy { it.device.value }
            .map { record ->
                val effective = manifest.roleOf(record.device)
                DeviceEstateRow(
                    device = record.device,
                    role = record.role,
                    // Read off the *effective* role, so a fenced or revoked
                    // device shows an empty list rather than the capabilities
                    // its recorded role would otherwise imply.
                    capabilities = effective?.capabilities.orEmpty(),
                    fenced = record.fenced,
                    revoked = record.role == DeviceRole.REVOKED,
                    isThisDevice = record.device == authorityDevice,
                    enrolledInGeneration = record.enrolledInGeneration,
                )
            }
        return DeviceEstate(
            devices = rows,
            authorityGeneration = manifest.authorityGeneration,
            thisDevice = authorityDevice,
            canMutatePermissions = isAuthorised(DeviceCapability.MUTATE_PERMISSIONS),
            canAdministerDevices = isAuthorised(DeviceCapability.ADMINISTER_DEVICES),
            canSovereignReset = isAuthorised(DeviceCapability.SOVEREIGN_RESET),
            // Offered only where it is the way out: no manifest at all, and an
            // identity to enrol.
            canEnrolGenesis = manifest.devices.isEmpty() &&
                authorityDevice != null &&
                authorityDevicePublicKey != null &&
                manifestSigner != null &&
                !repository.administrativeWritesLocked(),
            administrativeWritesLocked = repository.administrativeWritesLocked(),
            recoveryLockReason = repository.recoveryLockReason(),
            failClosedReason = manifest.failClosedReason,
        )
    }

    /**
     * Restoring from a file, as the SAF path actually does it.
     *
     * The same gate as every other recovery. Picking a file and typing a code
     * used to restore outright — no root signature, no freshness check, no
     * fencing, no epoch rotation — so a stolen backup plus its code was a
     * complete takeover, and an *old* backup plus its code silently re-granted
     * access that had since been withdrawn. The file is one credential, not
     * three.
     *
     * The payload is written first, because it *is* the history the recovery
     * then acts on: the manifest and acts it carries are what the new
     * generation is minted against. Both land in the same call chain, and a
     * refused or abandoned signature leaves neither.
     */
    suspend fun importRecovery(
        bytes: ByteArray,
        entered: String,
        sovereignReset: Boolean = false,
    ): SharingRecoveryOutcome {
        // Nothing about the recovery comes from the caller but the file, the
        // code, and whether the person deliberately asked for a reset. A UI
        // that could supply the generation could call any file current, and one
        // that could supply the device key could enrol a key this install does
        // not hold.
        val newDevicePublicKey = authorityDevice
            ?.let { repository.loadDeviceAuthority().devices[it]?.publicKey }
            ?: authorityDevicePublicKey
            ?: return refusedRecovery(SharingRecoveryRefusal.NO_DEVICE_IDENTITY)
        val crypto = backupCrypto
            ?: return refusedRecovery(SharingRecoveryRefusal.SIGNER_UNAVAILABLE)
        // Reading only verifies; nothing is written by looking.
        val payload = when (val read = SharingBackupEnvelope.read(bytes, entered, ownerNpub, crypto)) {
            // A file that will not open is, from here, the code not matching
            // it: the envelope checks the code cryptographically and cannot
            // tell a wrong code from a tampered file, and neither should the
            // message.
            is SharingBackupReadResult.Failed -> return refusedRecovery(SharingRecoveryRefusal.WRONG_CODE)
            is SharingBackupReadResult.Ok -> read.payload
        }
        try {
            // Decided before anything is written. A stale file gets a preview
            // and the payload stays out entirely — restoring it "just to look"
            // is how a withdrawn permission comes back.
            // The generation is the one the payload's own manifest establishes
            // — restoreBackupPayload refuses a file that claims otherwise — and
            // never a number a caller passed in.
            val backupGeneration = payload.authorityGeneration

            // Even a preview is a decision about somebody's data, and it locks
            // writes. So the root identity signs for it before it happens, and
            // a refusal leaves no lock and no claim.
            if (!SharingRecoveryCode.isValid(entered)) {
                return refusedRecovery(SharingRecoveryRefusal.WRONG_CODE)
            }
            if (manifestSigner == null || rootCrypto == null) {
                return refusedRecovery(SharingRecoveryRefusal.SIGNER_UNAVAILABLE)
            }
            // Asked once, here. The proof travels; the core re-checks that it
            // is a proof of this recovery rather than asking again.
            val proof = signRootChallenge(newDevicePublicKey, entered, backupGeneration, sovereignReset)
                ?: return refusedRecovery(SharingRecoveryRefusal.SIGNER_UNAVAILABLE)
            val credentials = RecoveryCredentials(rootSigned = true, recoveryCodeMatches = true)
            // Holding a manifest is not evidence of being up to date.
            //
            // It says only that *this device* once had an estate at this
            // generation. A backup taken at the same number can still be
            // missing every revocation made since, because nothing has ever
            // synced: the native transport is gated shut, so no install can
            // currently show it has seen another device's changes at all.
            //
            // Until there is a real signed sync record to point at, a file
            // import is a preview. The number the file carries and the number
            // this install happens to hold are the same kind of evidence —
            // neither is a witness that the two are in step.
            val freshness = syncedGeneration()
                ?.let { RecoveryGate.freshness(backupGeneration, it) }
                ?: BackupFreshness.UNKNOWN
            if (RecoveryGate.decide(credentials, freshness, sovereignReset) ==
                RecoveryDecision.PREVIEW_ONLY
            ) {
                repository.setAdministrativeWritesLocked(
                    locked = true,
                    reason = "the backup could not be shown to be current",
                    previewGeneration = backupGeneration,
                )
                return SharingRecoveryOutcome(
                    RecoveryDecision.PREVIEW_ONLY,
                    AuthorityRecoveryPlanner.plan(
                        repository.loadDeviceAuthority(),
                        repository.loadProjection().relationships,
                        authorityDevice ?: AuthorityDeviceId("unconfigured"),
                        newDevicePublicKey,
                        RecoveryDecision.PREVIEW_ONLY,
                    ),
                    applied = false,
                    refusal = SharingRecoveryRefusal.PREVIEW_ONLY,
                )
            }

            // Everything is planned and signed *before* anything is written,
            // and then the payload and the estate land in one transaction.
            // Restoring the payload first meant declining a later prompt left
            // the backup written and the estate not — an install carrying
            // somebody's whole permission history with no device able to
            // change any of it.
            return recover(
                entered = entered,
                expected = entered,
                newDevicePublicKey = newDevicePublicKey,
                backupGeneration = backupGeneration,
                sovereignReset = sovereignReset,
                rootProof = proof,
                payload = payload,
            )
        } finally {
            payload.zeroizeKeys()
        }
    }

    /**
     * The last authority generation this install can *prove* it saw synced.
     *
     * Always null today: the transport that would produce such a record is
     * gated shut, so there is nothing signed to read. Kept as the one place
     * that has to change when sync arrives, rather than leaving the local
     * manifest standing in for evidence it is not.
     */
    private fun syncedGeneration(): Long? = null

    private fun refusedRecovery(
        refusal: SharingRecoveryRefusal = SharingRecoveryRefusal.REJECTED,
    ) = SharingRecoveryOutcome(
        RecoveryDecision.REFUSED,
        AuthorityRecoveryPlanner.plan(
            repository.loadDeviceAuthority(),
            emptyMap(),
            authorityDevice ?: AuthorityDeviceId("unconfigured"),
            "",
            RecoveryDecision.REFUSED,
        ),
        applied = false,
        refusal = refusal,
    )

    // -------------------------------------------------------- recovery

    /**
     * Takes an estate back: a restore, a read-only preview, or a sovereign
     * reset, decided from what was actually proved.
     *
     * The whole change is signed before any of it is written, and then written
     * in one transaction. Half a recovery is worse than none — an install
     * carrying a new authority generation with no enrolled device holds no
     * authority at all and cannot enrol one either, so the person would be
     * locked out of their own permissions by the act of recovering them.
     *
     * The device that recovers enrols **itself**. Taking the returning device
     * as a parameter would let an install write a manifest naming a key it does
     * not hold, which reduces to an estate nobody can act in and no device can
     * repair.
     *
     * **Private, and every term of it comes from here.** It was public, with
     * `rootAlreadyVerified`, the payload, the device key, the generation and a
     * signing cut-off all chosen by the caller — so any screen could run the
     * restore and sovereign-reset path with the challenge switched off. The two
     * ways in are [recoverThisDevice] and [importRecovery]; each verifies the
     * challenge itself and hands the proof of that down, and there is no
     * boolean anywhere that could stand in for it.
     */
    @Suppress("LongParameterList", "ReturnCount")
    private suspend fun recover(
        entered: String,
        expected: String,
        newDevicePublicKey: String,
        backupGeneration: Long,
        sovereignReset: Boolean = false,
        /** Non-null only where the challenge above actually verified. */
        rootProof: RootRecoveryAuthorization? = null,
        /** Present only for an import: written in the same transaction as the estate. */
        payload: SharingBackupPayload? = null,
    ): SharingRecoveryOutcome = mutex.withLock {
        val newDevice = authorityDevice
            ?: return@withLock SharingRecoveryOutcome(
                RecoveryDecision.REFUSED,
                AuthorityRecoveryPlanner.plan(
                    repository.loadDeviceAuthority(), emptyMap(),
                    AuthorityDeviceId("unconfigured"), "", RecoveryDecision.REFUSED,
                ),
                applied = false,
                refusal = SharingRecoveryRefusal.NO_DEVICE_IDENTITY,
            )
        val codeMatches = SharingRecoveryCode.isValid(entered) &&
            SharingRecoveryCode.normalise(entered) == SharingRecoveryCode.normalise(expected)
        val signer = manifestSigner
        val crypto = rootCrypto

        // Planned against the authority the payload brings, when there is one:
        // a fresh install holds nothing, and planning against nothing would
        // rotate a generation that does not exist.
        val manifest = payload?.let { repository.projectedAuthority(it) }
            ?: repository.loadDeviceAuthority()
        val relationships = payload?.let { repository.projectedRestoreState(it) }
            ?: repository.loadProjection().relationships

        // Everything that can be decided here is decided here, *before* the
        // request leaves the process.
        //
        // The code used to be folded into `rootSigned` only after the challenge
        // came back, so mistyping it still sent the person to another app — to
        // authorise a takeover of their own estate, for an attempt that had
        // already been decided against. A prompt that cannot lead anywhere
        // teaches people that prompts are noise, and this is the one prompt in
        // the feature that must never become noise. The same goes for a signer
        // this install does not have: no signature is needed to discover that.
        if (!codeMatches || signer == null || crypto == null) {
            return@withLock SharingRecoveryOutcome(
                RecoveryDecision.REFUSED,
                AuthorityRecoveryPlanner.plan(
                    manifest = manifest,
                    relationships = relationships,
                    newDevice = newDevice,
                    newDevicePublicKey = newDevicePublicKey,
                    decision = RecoveryDecision.REFUSED,
                ),
                applied = false,
                refusal = if (!codeMatches) {
                    SharingRecoveryRefusal.WRONG_CODE
                } else {
                    SharingRecoveryRefusal.SIGNER_UNAVAILABLE
                },
            )
        }

        // A real signature over a domain-separated challenge bound to this
        // request, verified against the owner's key. A wired signer proves
        // nothing about whether the person holding it agreed, and a signature
        // the owner made for some other purpose must not be replayable here.
        // Either the caller above already asked and is holding the claim, or we
        // ask now. Either way the repository re-derives the challenge from the
        // claim's own terms and checks it against the key it was built with —
        // nothing here is taken on trust, and a claim about one recovery does
        // not verify for another.
        val authorization = rootProof?.takeIf {
            it.devicePublicKey == newDevicePublicKey &&
                it.backupGeneration == backupGeneration &&
                it.sovereignReset == sovereignReset
        } ?: signRootChallenge(newDevicePublicKey, expected, backupGeneration, sovereignReset)
        val credentials = RecoveryCredentials(
            rootSigned = authorization != null,
            // Checked above; reaching here means it matched.
            recoveryCodeMatches = true,
        )
        val freshness = RecoveryGate.freshness(
            backupGeneration = backupGeneration,
            knownGeneration = manifest.authorityGeneration.takeIf { it > 0 },
        )
        val decision = RecoveryGate.decide(credentials, freshness, sovereignReset)
        val plan = AuthorityRecoveryPlanner.plan(
            manifest = manifest,
            relationships = relationships,
            newDevice = newDevice,
            newDevicePublicKey = newDevicePublicKey,
            decision = decision,
        )

        if (plan.manifestBodies.isEmpty()) {
            // A preview locks writes; a refusal leaves whatever lock was there.
            if (decision == RecoveryDecision.PREVIEW_ONLY) {
                repository.setAdministrativeWritesLocked(
                    locked = true,
                    reason = "the backup could not be shown to be current",
                    previewGeneration = backupGeneration,
                )
            }
            return@withLock SharingRecoveryOutcome(
                decision,
                plan,
                applied = false,
                // Which credential was missing, not merely that one was. A
                // declined prompt and a mistyped code are different things to
                // do something about.
                // A wrong code cannot reach here any more — the gate above
                // returns before the signer is asked — so what is left is a
                // preview, or a root signature that was declined.
                refusal = if (decision == RecoveryDecision.PREVIEW_ONLY) {
                    SharingRecoveryRefusal.PREVIEW_ONLY
                } else {
                    SharingRecoveryRefusal.SIGNER_UNAVAILABLE
                },
            )
        }

        val entries = signManifest(plan, manifest, signer, payload)
            ?: return@withLock SharingRecoveryOutcome(
                decision, plan, applied = false,
                refusal = SharingRecoveryRefusal.SIGNER_UNAVAILABLE,
            )
        // Signed under the generation the manifest above is establishing, and
        // by the device that manifest is enrolling — the only one that will
        // hold authority once this lands.
        val epochs = signEpochAdvances(plan, newDevice, entries.last(), payload)
            ?: return@withLock SharingRecoveryOutcome(
                decision, plan, applied = false,
                refusal = SharingRecoveryRefusal.SIGNER_UNAVAILABLE,
            )

        // Root-authorised: the challenge above verified. That is what lets the
        // manifest entries land without a device act — no device the outgoing
        // manifest vouches for is available to produce one, which is the whole
        // situation a recovery exists for.
        // The claim is what opens both doors, and the repository is what
        // believes it — after checking the signature itself and spending the
        // attempt nonce, so neither door opens twice on one answer.
        val granted = authorization
            ?: return@withLock SharingRecoveryOutcome(
                decision, plan, applied = false,
                refusal = SharingRecoveryRefusal.SIGNER_UNAVAILABLE,
            )
        // Two doors, and each derives for itself what the authorisation has to
        // have agreed to: the file's own signed generation for an import, this
        // install's current one for a code-only recovery. Neither takes the
        // device key from here — the repository knows which device it is.
        val applied = if (payload != null) {
            repository.commitRootRecoveryFromBackup(payload, entries, epochs.first, epochs.second, granted)
        } else {
            repository.commitRootRecovery(entries, epochs.first, epochs.second, granted)
        }
        SharingRecoveryOutcome(
            decision,
            plan,
            applied = applied,
            refusal = if (applied) null else SharingRecoveryRefusal.REJECTED,
        )
    }

    /**
     * Asks the root identity to sign this exact request, once, and returns the
     * claim.
     *
     * `null` on a refusal, a timeout, or a signature that does not verify here
     * — all three mean the same thing, which is that nothing may be written.
     * The local check is a courtesy that fails fast; the check that *decides*
     * happens at the repository, against the key it was built with.
     *
     * The nonce is fresh for every attempt and is signed over, so the answer
     * authorises this attempt and no other. Asked exactly once per flow: the
     * claim is what travels, so nobody is prompted twice for one recovery.
     */
    private suspend fun signRootChallenge(
        newDevicePublicKey: String,
        recoveryCode: String,
        backupGeneration: Long,
        sovereignReset: Boolean,
    ): RootRecoveryAuthorization? {
        val crypto = rootCrypto ?: return null
        val digest = crypto.hash(SharingRecoveryCode.normalise(recoveryCode).encodeToByteArray())
            .joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }
        val nonce = newEntryId()
        val hash = crypto.hash(
            RecoveryChallenge.bytes(
                ownerNpub = ownerNpub,
                recoveryCodeDigest = digest,
                newDevicePublicKey = newDevicePublicKey,
                backupGeneration = backupGeneration,
                sovereignReset = sovereignReset,
                attemptNonce = nonce,
            ),
        )
        val signature = crypto.signCanonical(hash) ?: return null
        if (!crypto.verify(signature, hash, ownerNpub)) return null
        return RootRecoveryAuthorization(
            ownerNpub = ownerNpub,
            devicePublicKey = newDevicePublicKey,
            recoveryCodeDigest = digest,
            backupGeneration = backupGeneration,
            sovereignReset = sovereignReset,
            attemptNonce = nonce,
            signature = signature,
        )
    }

    /**
     * Signs the manifest entries a plan calls for, in order, storing none.
     *
     * The rotation is authored under the *old* generation and the enrolment
     * under the new one, because that is what the reducer requires: a rotation
     * says where authority is going, everything else says where it already is.
     *
     * A signer that stops answering partway — the person walked away from the
     * prompt — returns null from here and the whole recovery is abandoned with
     * nothing written. That used to be simulated by a caller-supplied cut-off
     * index, which meant a public parameter existed for no reason but a test.
     */
    private suspend fun signManifest(
        plan: RecoveryPlan,
        manifest: DeviceAuthorityState,
        signer: AsyncDeviceManifestSigner?,
        /** Present for an import: its manifest is not written yet but will be. */
        payload: SharingBackupPayload? = null,
    ): List<DeviceManifestEntry>? {
        if (signer == null) return null
        // Numbered after the manifest the payload brings, not after the one on
        // disk. On a fresh install the disk holds nothing, and the recovery
        // would claim sequence 1 — which the payload's own genesis already has.
        val stored = repository.loadDeviceManifest()
        val projected = (stored + payload?.deviceManifest.orEmpty())
            .distinctBy { it.id }
            .sortedBy { it.manifestSequence }
        var sequence = (projected.maxOfOrNull { it.manifestSequence } ?: 0L) + 1L
        var parent = projected.lastOrNull()?.id ?: manifest.head
        val signed = mutableListOf<DeviceManifestEntry>()
        plan.manifestBodies.forEachIndexed { index, body ->
            val entry = signer.sign(
                DeviceManifestEntry(
                    id = LedgerEntryId(newEntryId()),
                    manifestSequence = sequence,
                    authorityGeneration = when (body) {
                        is DeviceManifestBody.AuthorityRotated,
                        DeviceManifestBody.SovereignReset,
                        -> manifest.authorityGeneration
                        else -> plan.newGeneration
                    },
                    parent = parent,
                    signerNpub = ownerNpub,
                    signature = "",
                    body = body,
                ),
            ) ?: return null
            signed += entry
            parent = entry.id
            sequence += 1
        }
        return signed
    }

    /**
     * Signs the epoch advances, the device-generation bump and — for a
     * sovereign reset — the revocations, with the act authorising each.
     *
     * The acts are attested by the **returning** device under the generation
     * the recovery is establishing, because that is the only device the
     * manifest will vouch for once this lands.
     */
    private suspend fun signEpochAdvances(
        plan: RecoveryPlan,
        newDevice: AuthorityDeviceId,
        manifestHead: DeviceManifestEntry,
        /** Present for an import: nothing is written yet, so the head is projected. */
        payload: SharingBackupPayload? = null,
    ): Pair<List<SharingLedgerEntry>, List<AuthorityAttestation>>? {
        val signer = attestationSigner ?: return null
        val entries = mutableListOf<SharingLedgerEntry>()
        val acts = mutableListOf<AuthorityAttestation>()
        val sequences = mutableMapOf<PeerId, Long>()
        // One head per scope, seeded from the act that currently stands there.
        // A single chain across every peer made each act claim to supersede an
        // unrelated peer's — which the resolver reads as "that decision was
        // seen and built upon", silently retiring it.
        val heads = mutableMapOf<AuthorityScope, LedgerEntryId?>()
        val stored = repository.currentAuthority()

        // Where each peer's branch currently ends, advanced as we go. A
        // recovery writes several entries per peer; each has to follow the one
        // before it, or the second claims to begin a relationship that already
        // has a beginning.
        val entryHeads = mutableMapOf<PeerId, LedgerEntryId?>()

        suspend fun one(peer: PeerId, body: SharingLedgerBody, epoch: Long, generation: Long): Boolean {
            val storedHead = payload?.let { repository.projectedHead(it, peer) }
                ?: repository.authorisedHead(peer)
            val head = entryHeads.getOrElse(peer) { storedHead?.id }
            val sequence = sequences.getOrElse(peer) { (storedHead?.policySequence ?: 0L) + 1L }
            sequences[peer] = sequence + 1
            val entry = signer.let {
                SharingLedgerEntry(
                    id = LedgerEntryId(newEntryId()),
                    peer = peer,
                    policySequence = sequence,
                    parent = head,
                    authorityGeneration = plan.newGeneration,
                    resourceEpoch = epoch,
                    deviceGeneration = generation,
                    signerNpub = ownerNpub,
                    signature = "",
                    body = body,
                )
            }
            val signedEntry = this.signer.sign(entry) ?: return false
            if (!entryVerifier.verify(signedEntry)) return false

            val required = AuthorityPairing.requiredFor(peer, body) ?: return false
            val scope = required.scope
            val act = signer.sign(
                AuthorityAttestation(
                    id = LedgerEntryId(newEntryId()),
                    scope = scope,
                    subject = signedEntry.id,
                    device = newDevice,
                    parent = heads.getOrElse(scope) { stored[scope]?.id },
                    // The manifest this recovery is establishing, not the one
                    // that was there: the returning device does not exist in
                    // that one. A recovery's entries are a chain, so its
                    // frontier is the one entry it ends at.
                    manifestContext = ManifestContext.of(listOf(manifestHead.id)),
                    authorityGeneration = plan.newGeneration,
                    capability = required.capability,
                    effect = required.effect,
                    signature = "",
                ),
            ) ?: return false
            entries += signedEntry
            acts += act
            heads[scope] = act.id
            entryHeads[peer] = signedEntry.id
            return true
        }

        // Order is load-bearing. Only a RestoreCompleted may mint a device
        // generation, so it goes first; every entry after it is checked against
        // the generation it established, and one written before it would be
        // rejected as stale.
        plan.resourceEpochs.forEach { (peer, epoch) ->
            val generation = plan.deviceGenerations[peer] ?: 1L
            val before = repository.loadProjection().relationships[peer]?.resourceEpoch ?: 1L
            if (!one(peer, SharingLedgerBody.RestoreCompleted(generation), before, generation)) return null
            if (!one(peer, SharingLedgerBody.ResourceEpochAdvanced(epoch), epoch, generation)) return null
            if (peer in plan.revokedPeers) {
                if (!one(peer, SharingLedgerBody.RelationshipRevoked, epoch, generation)) return null
            }
        }
        // The head is only used to prove the manifest was written first.
        check(manifestHead.body is DeviceManifestBody.DeviceEnrolled) {
            "a recovery must end by enrolling the returning device"
        }
        return entries to acts
    }

    // ----------------------------------------------------------- internals

    /**
     * Appends one signed owner-policy entry. There is no unsigned path: an
     * identity that cannot sign changes no permission at all.
     */
    private suspend fun appendPolicy(
        body: OwnerPolicyBody,
        onlyPeer: PeerId? = null,
        affects: (SharingCircle) -> Boolean = { true },
    ): SharingWriteResult {
        if (!isAuthorised(DeviceCapability.MUTATE_PERMISSIONS)) return notSigned()
        val state = repository.loadOwnerPolicyState()
        // Built on the branch that stands, not on whatever has the highest
        // number: extending a losing branch would grow it and leave the
        // resolver choosing between two branches this device itself drew.
        val policyHead = repository.authorisedPolicyHead()
        val unsigned = OwnerPolicyEntry(
            id = LedgerEntryId(newEntryId()),
            policySequence = (policyHead?.policySequence ?: 0L) + 1L,
            parent = policyHead?.id,
            authorityGeneration = state.authorityGeneration,
            signerNpub = ownerNpub,
            signature = "",
            body = body,
        )
        val signed = ownerPolicySigner.sign(unsigned)
            ?: return SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE)
        // Checked against the entry it belongs to before it is stored: a
        // signature by an identity that changed while we waited is a valid
        // signature by the wrong key, and storing it would fail the policy
        // closed on the next read.
        if (!policyVerifier(signed)) {
            return SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE)
        }

        val batch = Batch()
        // Scope and effect come from the body itself: a baseline change
        // competes with other changes to that baseline, and a per-peer rule
        // with other rules about that peer and category. Lumping them all into
        // one "estate" scope made unrelated policy changes conflict.
        val required = AuthorityPairing.requiredFor(signed.body)
            ?: return SharingWriteResult.Failed(SharingWriteError.REJECTED)
        if (!batch.addPolicy(signed, required)) return notSigned()
        // The offers follow from the policy this entry establishes, so they are
        // planned against the *projected* policy rather than the stored one —
        // the entry is deliberately not written yet, because a declined offer
        // prompt must leave the policy alone.
        if (!planOfferSync(batch, repository.projectedOwnerPolicyState(signed), onlyPeer, affects)) {
            return notSigned()
        }
        return commit(batch)
    }

    /**
     * Adds to [batch] a `GrantChanged` for every relationship whose offer
     * [policy] no longer matches. Signs them; writes nothing.
     *
     * Widening lands in `pendingConsentCategories` and waits for a fresh
     * peer-signed acceptance; narrowing drops out of `consentedCategories`
     * immediately, because taking access away needs nobody's agreement.
     *
     * Terminal relationships are left alone — a revoked or purged one is not
     * re-offered anything — and an offer that already matches adds nothing, so
     * the ledger does not grow on a no-op.
     *
     * [policy] is the state the action is establishing, not the stored one, and
     * [movedTo] names a peer whose circle the same action is changing. Both
     * exist because nothing has been written yet: planning against what is on
     * disk would compute the offers the *old* policy implies.
     *
     * `false` when a signature was refused. The caller stores nothing then, so
     * the two ledgers cannot end up describing different policies.
     */
    private suspend fun planOfferSync(
        batch: Batch,
        policy: OwnerPolicyState,
        onlyPeer: PeerId? = null,
        affects: (SharingCircle) -> Boolean = { true },
        movedTo: Pair<PeerId, SharingCircle>? = null,
    ): Boolean {
        repository.loadProjection().relationships.values.forEach { state ->
            // Friendships carry their own mutually signed reception authority.
            // Do not generate a competing legacy category-consent invitation.
            if (continuous?.hasFriendshipPeer(state.peer.value) == true) return@forEach
            if (state.status.isTerminal) return@forEach
            if (onlyPeer != null && state.peer != onlyPeer) return@forEach

            val circle = if (movedTo?.first == state.peer) movedTo.second else state.circle
            if (!affects(circle)) return@forEach

            val desired = policy.allowedCategoriesFor(state.peer, circle)
            if (desired == state.offeredCategories) return@forEach

            if (!batch.sign(state.peer, SharingLedgerBody.GrantChanged(desired))) return false
        }
        return true
    }

    private fun appended(ok: Boolean): SharingWriteResult =
        if (ok) SharingWriteResult.Ok else notSigned()

    /**
     * `false` when the entry could not be signed or attested, and so was not
     * written.
     *
     * Goes through a batch rather than [SecureDbSharingRepository.appendEntry]
     * so the entry and the act that authorises it land in one transaction. A
     * permission change with no signed device behind it is, on the next read,
     * indistinguishable from one nobody ever authorised.
     */
    private suspend fun append(
        peer: PeerId,
        body: SharingLedgerBody,
        deviceGeneration: Long? = null,
    ): Boolean {
        val batch = Batch()
        val signed = prepare(
            peer,
            body,
            sequence = batch.nextSequence(peer),
            deviceGeneration = deviceGeneration,
            parent = batch.parentFor(peer),
        ) ?: return false
        val required = requiredFor(peer, body)
        if (required == null) {
            batch.entries += signed
        } else if (!batch.add(signed, required)) {
            return false
        }
        return repository.commitAll(batch.policy, batch.entries, batch.attestations)
    }

    /**
     * Builds and signs one entry without storing it.
     *
     * Separated from [append] because signing is now the slow part: where two
     * entries have to land together, both are signed first and only then
     * written, so an abandoned approval leaves no half-finished pair behind.
     *
     * `null` when the entry was not signed, or when what came back does not
     * verify against the entry it claims to belong to — an unsigned or
     * wrongly-signed entry in an append-only ledger is worse than a missing one.
     */
    private suspend fun prepare(
        peer: PeerId,
        body: SharingLedgerBody,
        sequence: Long? = null,
        deviceGeneration: Long? = null,
        parent: LedgerEntryId? = null,
    ): SharingLedgerEntry? {
        val current = repository.loadProjection().relationships[peer]
        val unsigned = SharingLedgerEntry(
            id = LedgerEntryId(newEntryId()),
            peer = peer,
            policySequence = sequence ?: repository.nextSequence(peer),
            parent = parent,
            authorityGeneration = current?.authorityGeneration ?: 1L,
            resourceEpoch = current?.resourceEpoch ?: 1L,
            deviceGeneration = deviceGeneration ?: current?.deviceGeneration ?: 1L,
            signerNpub = ownerNpub,
            signature = "",
            body = body,
        )
        val signed = signer.sign(unsigned) ?: return null
        return signed.takeIf { entryVerifier.verify(it) }
    }

    private fun newEntryId(): String =
        ByteArray(16).also { random.nextBytes(it) }
            .joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    companion object {
        /** Categories in the fixed order the UI lists them. */
        val CATEGORY_ORDER: List<SharingCategory> = SharingCategory.entries.toList()

        /** Sources the UI renders as "not released" rather than "denied". */
        val HOLDING_SOURCES: Set<DecisionSource> = setOf(
            DecisionSource.RELATIONSHIP_PENDING_CONSENT,
            DecisionSource.CONSENT_EXPANSION_PENDING,
            DecisionSource.DELIVERY_UNCLEAR,
            DecisionSource.AWAITING_REVOKE_SYNC,
            DecisionSource.RESOURCE_EPOCH_STALE,
        )
    }
}

/**
 * Supplies signing keys for simulated peers.
 *
 * Only a debug build has an implementation that returns anything: a demo peer
 * is a real keypair this build generated, so its acceptance is a real
 * signature the reducer accepts on its own merits — no verifier is weakened to
 * make the demo work.
 */
fun interface PeerSimulator {
    fun cryptoFor(peer: PeerId): LedgerCrypto?
}

/** Outcome of writing a permission backup. */
sealed interface SharingBackupWriteOutcome {
    data class Written(val bytes: ByteArray) : SharingBackupWriteOutcome
    data class Failed(val error: SharingBackupError) : SharingBackupWriteOutcome
}

/** Outcome of restoring one. */
sealed interface SharingRestoreOutcome {
    data object Restored : SharingRestoreOutcome
    data object NothingToDo : SharingRestoreOutcome
    data class Failed(val error: SharingBackupError) : SharingRestoreOutcome
}

/** What a recovery decided, planned, and whether it actually landed. */
/**
 * Why a recovery did not happen, in terms a screen can act on.
 *
 * [RecoveryDecision] says what was decided, not what went wrong: `REFUSED`
 * covers a mistyped code, a signer that declined and a signer that is not
 * wired, and those are three different things for the person holding the
 * phone. Without this the view model had only `applied == false` and said
 * "that code is not correct" to all of them — sending somebody whose prompt
 * they had just declined back to retype the one thing that was already right.
 *
 * Deliberately a closed set of reasons rather than a message: nothing derived
 * from an exception reaches a screen.
 */
enum class SharingRecoveryRefusal {
    /** The code does not match. */
    WRONG_CODE,

    /** The owner's signer declined, was cancelled, or is not wired. */
    SIGNER_UNAVAILABLE,

    /** Read-only: the backup could not be shown to be current. */
    PREVIEW_ONLY,

    /** This install holds no device identity to recover onto. */
    NO_DEVICE_IDENTITY,

    /** Everything was signed and the write door still refused it. */
    REJECTED,
}

data class SharingRecoveryOutcome(
    val decision: RecoveryDecision,
    val plan: RecoveryPlan,
    /** False when nothing was written — a preview, a refusal, or a declined prompt. */
    val applied: Boolean,
    /** Why, when [applied] is false. Null exactly when it is true. */
    val refusal: SharingRecoveryRefusal? = null,
) {
    val nativeRotation: NativeRotationPlan get() = plan.nativeRotation
}

/** One of the owner's devices, as the device screen shows it. */
data class DeviceEstateRow(
    val device: AuthorityDeviceId,
    /** What the manifest recorded. May differ from what it currently grants. */
    val role: DeviceRole,
    /** What it actually holds right now. Empty for a fenced or revoked device. */
    val capabilities: Set<DeviceCapability>,
    val fenced: Boolean,
    val revoked: Boolean,
    val isThisDevice: Boolean,
    val enrolledInGeneration: Long,
) {
    /** True when the row needs explaining rather than just listing. */
    val carriesAuthority: Boolean get() = capabilities.isNotEmpty()
}

/** Everything the device and recovery screens are driven by. */
data class DeviceEstate(
    val devices: List<DeviceEstateRow>,
    val authorityGeneration: Long,
    val thisDevice: AuthorityDeviceId?,
    val canMutatePermissions: Boolean,
    val canAdministerDevices: Boolean,
    val canSovereignReset: Boolean,
    /** True while the only thing that can unblock this install is a genesis. */
    val canEnrolGenesis: Boolean,
    val administrativeWritesLocked: Boolean,
    val recoveryLockReason: String?,
    val failClosedReason: String?,
) {
    /**
     * True when no device is enrolled at all.
     *
     * Told apart from an ordinary empty list because the remedy is completely
     * different: an install in this state changes nothing until the owner
     * enrols a device with their root key, and the screen has to say so
     * instead of showing a blank page that looks like a loading failure.
     */
    val needsEnrolment: Boolean get() = devices.isEmpty()
}
