package com.cruxcoach.android.sharing

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.AuthorityAttestation
import com.cruxcoach.domain.sharing.AuthorityAttestationAdmission
import com.cruxcoach.domain.sharing.AuthorityAttestationVerifier
import com.cruxcoach.domain.sharing.AuthorityEffect
import com.cruxcoach.domain.sharing.AuthorityLedger
import com.cruxcoach.domain.sharing.AuthorityScope
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceCapability
import com.cruxcoach.domain.sharing.AuthorityBranchResolver
import com.cruxcoach.domain.sharing.AuthorityDagValidation
import com.cruxcoach.domain.sharing.AuthorityPairing
import com.cruxcoach.domain.sharing.CircleBaselines
import com.cruxcoach.domain.sharing.CryptoEraseOutcome
import com.cruxcoach.domain.sharing.CryptoErasePipeline
import com.cruxcoach.domain.sharing.CryptoEraseStep
import com.cruxcoach.domain.sharing.DeviceManifestAdmission
import com.cruxcoach.domain.sharing.DeviceManifestBody
import com.cruxcoach.domain.sharing.DeviceManifestCodec
import com.cruxcoach.domain.sharing.DeviceManifestEntry
import com.cruxcoach.domain.sharing.DeviceManifestReducer
import com.cruxcoach.domain.sharing.DeviceManifestVerifier
import com.cruxcoach.domain.sharing.DeviceAuthorityState
import com.cruxcoach.domain.sharing.KeyHandle
import com.cruxcoach.domain.sharing.KeyScope
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.ManifestContext
import com.cruxcoach.domain.sharing.LedgerAdmission
import com.cruxcoach.domain.sharing.LedgerSignatureVerifier
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.OwnerPolicyAdmission
import com.cruxcoach.domain.sharing.OwnerPolicyCodec
import com.cruxcoach.domain.sharing.OwnerPolicyEntry
import com.cruxcoach.domain.sharing.OwnerPolicyReducer
import com.cruxcoach.domain.sharing.OwnerPolicyState
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.PeerPolicy
import com.cruxcoach.domain.sharing.RelationshipState
import com.cruxcoach.domain.sharing.AsyncLedgerCrypto
import com.cruxcoach.domain.sharing.RecoveryChallenge
import com.cruxcoach.domain.sharing.RequiredManifestContext
import com.cruxcoach.domain.sharing.RootRecoveryAuthorization
import com.cruxcoach.domain.sharing.RelationshipStatus
import com.cruxcoach.domain.sharing.SealedPayload
import com.cruxcoach.domain.sharing.SharingBackupDataKey
import com.cruxcoach.domain.sharing.SharingBackupPayload
import com.cruxcoach.domain.sharing.SharingBackupSealedItem
import com.cruxcoach.domain.sharing.SharingBackupTombstone
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCryptoException
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingKeyHandles
import com.cruxcoach.domain.sharing.SharingKeyVault
import com.cruxcoach.domain.sharing.SharingLedgerAdmission
import com.cruxcoach.domain.sharing.SharingLedgerCodec
import com.cruxcoach.domain.sharing.SharingLedgerEntry
import com.cruxcoach.domain.sharing.SharingLedgerReducer
import com.cruxcoach.domain.sharing.SharingPolicy
import com.cruxcoach.domain.sharing.SharingProjection
import com.cruxcoach.domain.sharing.TrustedAttestations
import com.cruxcoach.domain.sharing.WrappedKey

/**
 * Binds the FEAT-062 domain to the secure database.
 *
 * The ledger is the stored authority; the `sharing_relationship` row is a
 * *derived* projection, rewritten from the reduced ledger on every append. That
 * is what makes the projection retention-independent and repairable: losing it
 * costs nothing, because it can always be recomputed from the entries.
 */
class SecureDbSharingRepository(
    private val database: SecureDatabase,
    private val vault: SharingKeyVault,
    private val verifier: LedgerSignatureVerifier,
    private val ownerPolicyVerifier: (OwnerPolicyEntry) -> Boolean,
    private val ownerNpub: String,
    /**
     * Verifies device-manifest entries.
     *
     * Defaults to refusing everything rather than accepting everything: an
     * install that has not wired a manifest verifier has no device authority,
     * which locks the administrative paths instead of opening them.
     */
    private val deviceManifestVerifier: DeviceManifestVerifier = DeviceManifestVerifier { false },
    /**
     * Verifies device attestations. Refuses everything by default, for the same
     * reason as [deviceManifestVerifier]: no wiring means no device authority.
     */
    private val attestationVerifier: AuthorityAttestationVerifier =
        AuthorityAttestationVerifier { _, _ -> false },
    /**
     * The owner's root key, for checking a recovery authorisation.
     *
     * Bound here, at construction, and never taken per call. That is the whole
     * point: the previous three attempts all left the *minting* reachable from
     * anywhere in the module — most recently because the factory took the
     * verifier as an argument, so a caller could hand it one that always says
     * yes. The component that performs the write is the one that owns the key,
     * and an individual caller cannot choose it. A test swaps it here, through
     * the constructor, which is the one place it is meant to be swapped.
     *
     * Null refuses every recovery, as an unwired verifier should.
     */
    private val rootRecoveryVerifier: AsyncLedgerCrypto? = null,
    /**
     * The device identity this install actually holds.
     *
     * Bound here, never taken per commit. It used to arrive as a nullable
     * `expectedDevicePublicKey` argument, which meant the caller chose what the
     * authorisation was checked against — and passing `null` meant "any key
     * will do", so an authorisation the owner signed for one device could enrol
     * another. A recovery may only ever enrol the device that is running it.
     *
     * Unwired refuses every recovery, as it should: an install that cannot say
     * which device it is cannot be the device a recovery re-enrols.
     */
    private val localDeviceIdentity: () -> DeviceIdentity? = { null },
) {

    private val queries get() = database.sharingQueries
    private val reducer = SharingLedgerReducer(verifier, ownerNpub)
    private val ownerPolicyReducer = OwnerPolicyReducer(ownerPolicyVerifier, ownerNpub)

    /**
     * The policy the authorised branch establishes.
     *
     * Goes through the resolver for the same reason the relationship
     * projection does: folding every entry in sequence order let a losing
     * offline branch set a baseline.
     */
    private fun reducedOwnerPolicy(): OwnerPolicyState {
        estateFailure()?.let { return OwnerPolicyState(failClosedReason = it) }
        return ownerPolicyReducer.reduce(loadOwnerPolicyLedger(), trustedAttestations())
    }

    /**
     * Why the stored acts do not form a readable DAG, or null.
     *
     * Only this class holds every ledger at once, so only it can tell an act
     * that belongs to the *other* ledger from one that belongs nowhere. A
     * per-ledger projection filters to its own subjects and cannot; if it
     * carried on regardless, an orphan act would leave one ledger releasing
     * data while the estate as a whole was unreadable.
     *
     * The requirements are combined for the same reason, and it is the sharper
     * half: a relationship revocation whose act went missing must not leave the
     * owner policy granting away, and a policy denial whose act went missing
     * must not leave the relationships open. The two ledgers are one estate.
     */
    private fun estateFailure(): String? {
        val trusted = trustedAttestations()
        trusted.failClosedReason?.let { return it }
        return AuthorityDagValidation.check(
            acts = trusted.acts,
            knownSubjects = knownSubjects(),
            requirements = administrativeRequirements(),
        )
    }

    /**
     * The stored acts, each checked against the key the manifest binds to the
     * device that claims to have signed it.
     *
     * Re-checked on every read rather than trusted because admission checked it
     * once: the row is in a file, and a file can be written by a restored
     * backup, a rooted device or a bug in our own code. Nothing downstream can
     * reach the resolver without coming through here — see
     * [com.cruxcoach.domain.sharing.TrustedAttestations].
     */
    private fun trustedAttestations(): TrustedAttestations =
        TrustedAttestations.of(loadAttestations(), loadDeviceAuthority(), attestationVerifier)

    /**
     * What every stored entry demands of the act authorising it, both ledgers.
     *
     * A body the peer authored contributes nothing: their acceptance is carried
     * by their own signature, and demanding an owner act for it would mean the
     * owner authorising somebody else's consent.
     */
    private fun administrativeRequirements(): Map<LedgerEntryId, AuthorityPairing.Required> =
        buildMap {
            loadWholeLedger().forEach { entry ->
                AuthorityPairing.requiredFor(entry.peer, entry.body)?.let { put(entry.id, it) }
            }
            loadOwnerPolicyLedger().forEach { entry ->
                AuthorityPairing.requiredFor(entry.body)?.let { put(entry.id, it) }
            }
        }
    private val deviceManifestReducer =
        DeviceManifestReducer(deviceManifestVerifier, ownerNpub, attestationVerifier)

    // --------------------------------------------------------------- ledger

    fun loadLedger(peer: PeerId): List<SharingLedgerEntry> =
        queries.selectLedgerForPeer(peer.value).executeAsList().map { row ->
            SharingLedgerEntry(
                id = LedgerEntryId(row.entry_id),
                peer = PeerId(row.peer_npub),
                policySequence = row.policy_sequence,
                parent = row.parent_entry_id?.let { LedgerEntryId(it) },
                authorityGeneration = row.authority_generation,
                resourceEpoch = row.resource_epoch,
                deviceGeneration = row.device_generation,
                signerNpub = row.signer_npub,
                signature = row.signature,
                body = SharingLedgerCodec.decodeBody(row.body_kind, row.body_json),
            )
        }

    /** Every peer's entries at once — what the estate-wide check reads. */
    fun loadWholeLedger(): List<SharingLedgerEntry> =
        queries.selectWholeLedger().executeAsList().map { row ->
            SharingLedgerEntry(
                id = LedgerEntryId(row.entry_id),
                peer = PeerId(row.peer_npub),
                policySequence = row.policy_sequence,
                parent = row.parent_entry_id?.let { LedgerEntryId(it) },
                authorityGeneration = row.authority_generation,
                resourceEpoch = row.resource_epoch,
                deviceGeneration = row.device_generation,
                signerNpub = row.signer_npub,
                signature = row.signature,
                body = SharingLedgerCodec.decodeBody(row.body_kind, row.body_json),
            )
        }

    fun nextSequence(peer: PeerId): Long =
        (authorisedHead(peer)?.policySequence ?: 0L) + 1L

    /**
     * The entry the authorised branch ends at, or null if none is authorised.
     *
     * What a new entry has to build on. Extending the branch with the highest
     * sequence instead would grow whichever branch happened to be written last,
     * which is the arrival-order dependence this whole shape removes.
     */
    fun authorisedHead(peer: PeerId): SharingLedgerEntry? =
        // The tail of the authorised *history*, not just the last act-bearing
        // entry. A peer's reply cannot head a branch on its own, but it is
        // still the last thing that happened — numbering the owner's next entry
        // from before it would hand two entries one sequence.
        AuthorityBranchResolver.tip(loadLedger(peer), trustedAttestations())

    /**
     * Side-effect-free verdict on whether [entry] may be stored.
     *
     * Exposed so a caller can decide *before* anything is written; nothing in
     * this class writes an entry without going through it first.
     */
    fun admit(entry: SharingLedgerEntry): LedgerAdmission =
        SharingLedgerAdmission.check(loadLedger(entry.peer), entry, verifier, ownerNpub)

    /**
     * Appends one entry and rewrites the projection for that peer.
     *
     * Hard failure on an invalid entry, deliberately: this is the local path,
     * where the app builds the entry itself, so a rejection means a broken
     * invariant in our own code rather than a hostile message. Silently
     * dropping it would hide that.
     *
     * A purged peer is not resurrected: its tombstone is checked first, so a
     * replayed pre-purge entry cannot quietly recreate an active relationship.
     */
    fun appendEntry(entry: SharingLedgerEntry) {
        if (isPurged(entry.peer)) return
        // Every door, not just the inbound one. A local caller that could write
        // an unattested entry is the same hole as a remote one — the row looks
        // identical afterwards, and nothing can tell them apart.
        requirePairingOrThrow(entry)
        when (val verdict = admit(entry)) {
            is LedgerAdmission.AlreadyPresent -> return
            is LedgerAdmission.Reject -> error("refusing to store ledger entry: ${verdict.reason}")
            is LedgerAdmission.Accept -> persist(entry)
        }
    }

    /**
     * Appends only if admissible, reporting the outcome instead of throwing.
     *
     * This is the door for anything that did not originate here. An entry that
     * fails admission leaves no row and no projection change, so one bad
     * inbound message cannot permanently fail-close the relationship.
     */
    fun tryAppendEntry(entry: SharingLedgerEntry): Boolean {
        if (isPurged(entry.peer)) return false
        // An owner-signed entry arriving from outside with no act behind it is
        // what a replayed pre-revocation message looks like. Only the peer's
        // own bodies come through here unauthorised, and their authority is
        // their own signature.
        AuthorityPairing.requiredFor(entry.peer, entry.body)?.let { required ->
            val verdict = AuthorityPairing.pair(required, entry.id, loadAttestations())
            if (verdict is AuthorityPairing.Verdict.Refused) {
                lastInboundRejection = verdict.reason
                return false
            }
        }
        return when (admit(entry)) {
            is LedgerAdmission.AlreadyPresent -> true
            is LedgerAdmission.Reject -> false
            is LedgerAdmission.Accept -> { persist(entry); true }
        }
    }

    /** Why the last inbound entry was refused, or null. Diagnostics only. */
    var lastInboundRejection: String? = null
        private set

    /** Why the last import was refused, or null. Diagnostics only. */
    var lastRestoreRejection: String? = null
        private set

    private fun persist(entry: SharingLedgerEntry) {
        val (kind, payload) = SharingLedgerCodec.encodeBody(entry.body)
        queries.transaction {
            ensureRelationshipRow(entry.peer)
            queries.insertLedgerEntry(
                entry_id = entry.id.value,
                peer_npub = entry.peer.value,
                policy_sequence = entry.policySequence,
                parent_entry_id = entry.parent?.value,
                authority_generation = entry.authorityGeneration,
                resource_epoch = entry.resourceEpoch,
                device_generation = entry.deviceGeneration,
                signer_npub = entry.signerNpub,
                signature = entry.signature,
                body_kind = kind,
                body_json = payload,
                received_at = 0L,
            )
        }
        rewriteProjection(entry.peer)
    }

    /**
     * The relationship as the *authorised* branch says it is.
     *
     * Goes through [com.cruxcoach.domain.sharing.AuthorityBranchResolver], so
     * the projection on disk is the one the resolver picked rather than
     * whatever happened to be written last. Reducing every entry in sequence
     * order is what let a losing offline branch decide somebody's access.
     */
    private fun reduceAuthorised(peer: PeerId): RelationshipState {
        estateFailure()?.let {
            return RelationshipState(
                peer = peer,
                status = RelationshipStatus.FAIL_CLOSED,
                failClosedReason = it,
            )
        }
        return reducer.reduce(peer, loadLedger(peer), trustedAttestations())
    }

    private fun ensureRelationshipRow(peer: PeerId) {
        if (queries.selectRelationship(peer.value).executeAsOneOrNull() != null) return
        queries.insertRelationship(
            peer_npub = peer.value,
            status = "PENDING",
            circle = SharingCircle.ALL_OTHER_USERS.name,
            offered_categories = "",
            consented_categories = "",
            pending_consent_categories = "",
            resource_epoch = 1,
            authority_generation = 1,
            device_generation = 1,
            last_sequence = 0,
            expires_at = null,
            awaiting_revoke_sync = 0,
            fail_closed_reason = null,
            updated_at = 0L,
        )
    }

    private fun rewriteProjection(peer: PeerId) {
        val state = reduceAuthorised(peer)
        queries.transaction {
            queries.updateRelationship(
                status = state.status.name,
                circle = state.circle.name,
                offered_categories = state.offeredCategories.joinToString(",") { it.name },
                consented_categories = state.consentedCategories.joinToString(",") { it.name },
                pending_consent_categories = state.pendingConsentCategories.joinToString(",") { it.name },
                resource_epoch = state.resourceEpoch,
                authority_generation = state.authorityGeneration,
                device_generation = state.deviceGeneration,
                last_sequence = state.lastSequence,
                expires_at = state.expiresAt,
                awaiting_revoke_sync = if (state.awaitingRevokeSync) 1L else 0L,
                fail_closed_reason = state.failClosedReason,
                updated_at = 0L,
                peer_npub = peer.value,
            )
            state.authorisedDevices.forEach { device ->
                queries.upsertDevice(peer.value, device.value, 1L, state.deviceGeneration, 0L, null)
            }
            state.revokedDevices.forEach { device ->
                queries.upsertDevice(peer.value, device.value, 0L, state.deviceGeneration, null, 0L)
                queries.insertTombstone(peer.value, "DEVICE_REVOKED", device.value, 0L)
            }
        }
    }

    /** Reduces straight from the stored ledger — never from the projection row. */
    fun loadProjection(): SharingProjection {
        val peers = queries.selectAllRelationships().executeAsList().map { PeerId(it.peer_npub) }
        val states = mutableMapOf<PeerId, RelationshipState>()
        peers.forEach { peer -> states[peer] = reduceAuthorised(peer) }
        return SharingProjection(states)
    }

    // --------------------------------------------------------------- policy

    fun loadOwnerPolicyLedger(): List<OwnerPolicyEntry> =
        queries.selectOwnerPolicyLedger().executeAsList().map { row ->
            OwnerPolicyEntry(
                id = LedgerEntryId(row.entry_id),
                policySequence = row.policy_sequence,
                parent = row.parent_entry_id?.let { LedgerEntryId(it) },
                authorityGeneration = row.authority_generation,
                signerNpub = row.signer_npub,
                signature = row.signature,
                body = OwnerPolicyCodec.decodeBody(row.body_kind, row.body_json),
            )
        }

    /**
     * The number a new policy entry would claim.
     *
     * Taken from the entry the *authorised* history ends at, not from the
     * highest number on disk. A long abandoned branch has the higher numbers,
     * and following it left every later entry hanging off a branch nobody
     * chose — pushing the numbering forward for ever while the winner stayed
     * where it was.
     */
    fun nextOwnerPolicySequence(): Long = (authorisedPolicyHead()?.policySequence ?: 0L) + 1L

    /** Reduced straight from the ledger — the projection rows are only a cache. */
    fun loadOwnerPolicyState(): OwnerPolicyState = reducedOwnerPolicy()

    /**
     * The policy entry the authorised branch ends at, or null if none is.
     *
     * What a new policy entry has to build on.
     */
    fun authorisedPolicyHead(): OwnerPolicyEntry? =
        AuthorityBranchResolver.policyTip(loadOwnerPolicyLedger(), trustedAttestations())

    /**
     * Appends one signed owner-policy entry and rewrites the projection cache
     * in the same transaction, so the rows on disk can never describe a policy
     * the ledger does not.
     */
    fun admitOwnerPolicy(entry: OwnerPolicyEntry): LedgerAdmission =
        OwnerPolicyAdmission.check(loadOwnerPolicyLedger(), entry, ownerPolicyVerifier, ownerNpub)

    /** Reports rather than throwing, for anything that did not originate here. */
    fun tryAppendOwnerPolicyEntry(entry: OwnerPolicyEntry): Boolean =
        when (admitOwnerPolicy(entry)) {
            is LedgerAdmission.AlreadyPresent -> true
            is LedgerAdmission.Reject -> false
            is LedgerAdmission.Accept -> { persistOwnerPolicy(entry); true }
        }

    /** Hard failure: a local entry that is refused is our own broken invariant. */
    fun appendOwnerPolicyEntry(entry: OwnerPolicyEntry) {
        when (val verdict = admitOwnerPolicy(entry)) {
            is LedgerAdmission.AlreadyPresent -> return
            is LedgerAdmission.Reject -> error("refusing to store owner policy entry: ${verdict.reason}")
            is LedgerAdmission.Accept -> persistOwnerPolicy(entry)
        }
    }

    private fun persistOwnerPolicy(entry: OwnerPolicyEntry) {
        val (kind, payload) = OwnerPolicyCodec.encodeBody(entry.body)
        queries.transaction {
            queries.insertOwnerPolicyEntry(
                entry_id = entry.id.value,
                policy_sequence = entry.policySequence,
                parent_entry_id = entry.parent?.value,
                authority_generation = entry.authorityGeneration,
                signer_npub = entry.signerNpub,
                signature = entry.signature,
                body_kind = kind,
                body_json = payload,
                received_at = 0L,
            )
            rewriteOwnerPolicyProjectionLocked()
        }
    }

    /** Rebuilds the cache rows from the reduced ledger. Caller holds the transaction. */
    private fun rewriteOwnerPolicyProjectionLocked() {
        val state = reducedOwnerPolicy()
        queries.deleteAllBaselines()
        queries.deleteAllPeerRules()
        queries.deleteAllObjectRules()
        if (state.failClosedReason != null) return // a fail-closed policy caches nothing

        SharingCircle.entries.forEach { circle ->
            state.baselines.explicitFor(circle).forEach { category ->
                queries.insertBaseline(circle.name, category.name)
            }
        }
        // The rule tables carry a foreign key to the relationship row, so only
        // peers that actually have one are cached. The ledger stays the
        // authority either way — `loadPolicy` never reads these rows.
        val known = queries.selectAllRelationships().executeAsList().map { it.peer_npub }.toSet()
        state.peerRules.forEach { (peer, rules) ->
            if (peer.value !in known) return@forEach
            rules.forEach { (category, effect) ->
                queries.upsertPeerRule(peer.value, category.name, effect.name)
            }
        }
        state.objectRules.forEach { (peer, rules) ->
            if (peer.value !in known) return@forEach
            rules.forEach { (objectId, categoryAndEffect) ->
                queries.upsertObjectRule(
                    peer.value, objectId.value,
                    categoryAndEffect.first.name, categoryAndEffect.second.name,
                )
            }
        }
    }

    /**
     * The effective policy: owner rules from the owner-policy ledger, combined
     * with the circles the relationship ledger assigned.
     */
    fun loadPolicy(): SharingPolicy {
        val circles = queries.selectAllRelationships().executeAsList()
            .associate { PeerId(it.peer_npub) to SharingCircle.valueOf(it.circle) }
        return loadOwnerPolicyState().toPolicy(circles)
    }

    /** The category an object exception was recorded against. */
    fun objectRuleCategory(peer: PeerId, objectId: ObjectId): SharingCategory? =
        loadOwnerPolicyState().objectRuleCategory(peer, objectId)

    // ------------------------------------------------------------ key vault

    fun createDataKey(handle: KeyHandle, resourceEpoch: Long = 1L): WrappedKey {
        val key = vault.createDataKey(handle)
        queries.upsertWrappedKey(handle.scope.name, handle.id, resourceEpoch, key.wrappedBytes, 0L)
        return key
    }

    /** The stored wrapped key, or `null` once it has been destroyed. */
    fun readWrappedKey(handle: KeyHandle, resourceEpoch: Long = 1L): WrappedKey? =
        queries.selectWrappedKey(handle.scope.name, handle.id, resourceEpoch)
            .executeAsOneOrNull()
            ?.let { WrappedKey(handle, it.wrapped_key) }

    fun destroyKey(handle: KeyHandle) {
        vault.destroy(handle)
        queries.deleteWrappedKeysFor(handle.scope.name, handle.id)
    }

    fun storeSealedItem(
        itemId: String,
        category: SharingCategory,
        handle: KeyHandle,
        key: WrappedKey,
        plaintext: ByteArray,
        resourceEpoch: Long = 1L,
    ) {
        val sealed = vault.seal(key, plaintext, aad = handle.aad())
        queries.insertSealedItem(
            item_id = itemId,
            category = category.name,
            key_scope = handle.scope.name,
            key_id = handle.id,
            resource_epoch = resourceEpoch,
            ciphertext = sealed.bytes,
            created_at = 0L,
        )
    }

    /** `null` when the item is gone, or when its key has been destroyed. */
    @Suppress("TooGenericExceptionCaught")
    fun readSealedItem(itemId: String, key: WrappedKey): ByteArray? {
        val row = queries.selectSealedItem(itemId).executeAsOneOrNull() ?: return null
        val handle = KeyHandle(KeyScope.valueOf(row.key_scope), row.key_id)
        return try {
            vault.open(key, SealedPayload(row.ciphertext), aad = handle.aad())
        } catch (e: Exception) {
            null
        }
    }

    // --------------------------------------------------------------- backup

    fun countTombstones(): Long = queries.countTombstones().executeAsOne()

    /**
     * Everything a backup carries, with the data keys unwrapped.
     *
     * The caller must zeroize the payload's keys as soon as they are written —
     * inside the envelope they are protected only by its own encryption.
     */
    fun collectBackupPayload(): SharingBackupPayload {
        val relationships = queries.selectAllRelationships().executeAsList().map { PeerId(it.peer_npub) }
        val keys = queries.selectAllWrappedKeys().executeAsList().mapNotNull { row ->
            val handle = KeyHandle(KeyScope.valueOf(row.key_scope), row.key_id)
            vault.exportForBackup(WrappedKey(handle, row.wrapped_key))?.let { raw ->
                SharingBackupDataKey(handle.scope, handle.id, row.resource_epoch, raw)
            }
        }
        return SharingBackupPayload(
            deviceManifest = loadDeviceManifest(),
            attestations = loadAttestations(),
            authorityGeneration = loadDeviceAuthority().authorityGeneration,
            administrativeWritesLocked = administrativeWritesLocked(),
            ownerNpub = ownerNpub,
            relationshipEntries = relationships.flatMap { loadLedger(it) },
            ownerPolicyEntries = loadOwnerPolicyLedger(),
            tombstones = queries.selectAllTombstones().executeAsList()
                .map { SharingBackupTombstone(it.peer_npub, it.kind, it.subject) },
            dataKeys = keys,
            sealedItems = queries.selectAllSealedItems().executeAsList().map {
                SharingBackupSealedItem(
                    itemId = it.item_id,
                    category = SharingCategory.valueOf(it.category),
                    keyScope = KeyScope.valueOf(it.key_scope),
                    keyId = it.key_id,
                    resourceEpoch = it.resource_epoch,
                    ciphertext = it.ciphertext,
                )
            },
        )
    }

    /**
     * Writes a validated backup into the sharing state, inside the caller's
     * transaction.
     *
     * Everything is checked before anything is written: every entry goes
     * through the same admission the ordinary write path uses, and tombstones
     * land first so a purged peer cannot be resurrected by its own history.
     * Data keys are re-wrapped under this device's own Keystore and the raw
     * material is zeroized.
     *
     * It opens no transaction of its own: a recovery has to write the payload
     * and the estate together, so the transaction belongs to whoever is doing
     * both. `false` without writing anything if any entry is inadmissible.
     */
    @Suppress("ReturnCount")
    private fun restoreBackupPayloadWithin(
        payload: SharingBackupPayload,
        /**
         * Filled with the handles this attempt minted, so its caller can undo
         * them.
         *
         * Passed in rather than kept on the instance: an instance field is
         * shared by every concurrent attempt, so one failing would destroy keys
         * another had just created. The list belongs to the attempt.
         */
        minted: MutableList<KeyHandle>,
    ): Boolean {
        lastRestoreRejection = null
        if (payload.ownerNpub != ownerNpub) {
            lastRestoreRejection = "the backup belongs to another identity"
            return false
        }

        // The manifest comes back first and is validated the same way it was
        // written: every entry through the same admission. Without it the acts
        // have no keys to check against and the entries have no authority.
        val manifestToInsert = mutableListOf<DeviceManifestEntry>()
        run {
            val seen = loadDeviceManifest().toMutableList()
            payload.deviceManifest.sortedBy { it.manifestSequence }.forEach { entry ->
                when (val v = DeviceManifestAdmission.check(seen, entry, deviceManifestVerifier, ownerNpub)) {
                    is LedgerAdmission.Reject -> {
                        lastRestoreRejection = "manifest ${entry.id.value}: ${v.reason}"
                        return false
                    }
                    is LedgerAdmission.AlreadyPresent -> Unit
                    is LedgerAdmission.Accept -> { seen += entry; manifestToInsert += entry }
                }
            }
        }
        // What the file claims about its own authority has to be what its own
        // manifest establishes. The freshness gate — the thing that decides
        // preview versus restore — reads this number, so a file free to state
        // any generation could talk its way past a stale-backup check with a
        // number nobody signed.
        // The acts are checked against the manifest the restore is
        // establishing, not the empty one this install currently has.
        val restoredAuthority = deviceManifestReducer.reduce(
            loadDeviceManifest() + manifestToInsert,
            (loadAttestations() + payload.attestations).distinctBy { it.id },
        )
        if (restoredAuthority.failClosedReason != null) {
            lastRestoreRejection = "restored manifest: ${restoredAuthority.failClosedReason}"
            return false
        }
        if (payload.authorityGeneration != restoredAuthority.authorityGeneration) {
            lastRestoreRejection =
                "the backup claims authority generation ${payload.authorityGeneration}, " +
                    "but its own manifest establishes ${restoredAuthority.authorityGeneration}"
            return false
        }
        val actsToInsert = mutableListOf<AuthorityAttestation>()
        run {
            val seen = loadAttestations().toMutableList()
            // Parents before children. The acts are stored ordered by id, which
            // is random, so a child can easily precede the act it was built on
            // — and admission requires the parent to be there already. Ordering
            // by "can this be admitted yet" rather than by id also means a
            // payload whose chain is broken is refused instead of half-applied.
            val pending = payload.attestations.toMutableList()
            while (pending.isNotEmpty()) {
                val ready = pending.filter { act ->
                    act.parent == null || seen.any { it.id == act.parent }
                }
                if (ready.isEmpty()) {
                    lastRestoreRejection = "the backup's act chain is broken"
                    return false
                }
                ready.forEach { act ->
                    when (
                        val v =
                            AuthorityAttestationAdmission.check(
                                seen,
                                act,
                                restoredAuthority,
                                attestationVerifier,
                                // The file's acts were authored against the
                                // head *that* install had, not ours.
                                // The one caller allowed a context from the
                                // past: the whole file has already been
                                // authenticated against the owner's root key.
                                RequiredManifestContext.RootAuthenticatedHistoricalImport,
                            )
                    ) {
                        is LedgerAdmission.Reject -> {
                            lastRestoreRejection = "act ${act.id.value}: ${v.reason}"
                            return false
                        }
                        is LedgerAdmission.AlreadyPresent -> Unit
                        is LedgerAdmission.Accept -> { seen += act; actsToInsert += act }
                    }
                }
                pending.removeAll(ready)
            }
        }
        // Every entry the backup carries must still be paired by an act the
        // backup also carries. Retention-independent: the pairing is checked
        // against the file's own contents, not against anything this install
        // happened to keep.
        val allActs = loadAttestations() + actsToInsert
        payload.relationshipEntries.forEach { entry ->
            val required = AuthorityPairing.requiredFor(entry.peer, entry.body) ?: return@forEach
            val v = AuthorityPairing.pair(required, entry.id, allActs)
            if (v is AuthorityPairing.Verdict.Refused) {
                lastRestoreRejection = "pairing ${entry.id.value}: ${v.reason}"
                return false
            }
        }
        payload.ownerPolicyEntries.forEach { entry ->
            val required = AuthorityPairing.requiredFor(entry.body) ?: return@forEach
            val v = AuthorityPairing.pair(required, entry.id, allActs)
            if (v is AuthorityPairing.Verdict.Refused) {
                lastRestoreRejection = "pairing ${entry.id.value}: ${v.reason}"
                return false
            }
        }

        // Validate first, against the state each entry would actually see.
        val relationshipsByPeer = payload.relationshipEntries.groupBy { it.peer }
        val purgedPeers = payload.tombstones.filter { it.kind == "PURGED" }.map { it.peerNpub }.toSet()
        // Admission runs against what is *already stored* plus what this file
        // has contributed so far, so a re-run of the same backup reports
        // AlreadyPresent instead of colliding on a unique sequence.
        relationshipsByPeer.forEach { (peer, entries) ->
            if (peer.value in purgedPeers) return@forEach
            val seen = loadLedger(peer).toMutableList()
            entries.sortedBy { it.policySequence }.forEach { entry ->
                when (val v = SharingLedgerAdmission.check(seen, entry, verifier, ownerNpub)) {
                    is LedgerAdmission.Reject -> {
                        lastRestoreRejection = "entry ${entry.id.value}: ${v.reason}"
                        return false
                    }
                    is LedgerAdmission.AlreadyPresent -> Unit
                    is LedgerAdmission.Accept -> seen += entry
                }
            }
        }
        val storedPolicy = loadOwnerPolicyLedger()
        val seenPolicy = storedPolicy.toMutableList()
        val policyToInsert = mutableListOf<OwnerPolicyEntry>()
        payload.ownerPolicyEntries.sortedBy { it.policySequence }.forEach { entry ->
            when (OwnerPolicyAdmission.check(seenPolicy, entry, ownerPolicyVerifier, ownerNpub)) {
                is LedgerAdmission.Reject -> return false
                is LedgerAdmission.AlreadyPresent -> Unit
                is LedgerAdmission.Accept -> { seenPolicy += entry; policyToInsert += entry }
            }
        }

        run {
            // Tombstones first: a purged peer must be unable to come back even
            // if its own pre-purge entries are in the same file.
            payload.tombstones.forEach {
                queries.insertTombstone(it.peerNpub, it.kind, it.subject, 0L)
            }
            manifestToInsert.forEach { persistDeviceManifest(it) }
            actsToInsert.forEach { persistAttestation(it) }
            // A backup taken during a recovery preview restores as locked. The
            // preview is a property of the *estate* it was taken from, and an
            // install that forgot it would write from a backup that was already
            // known not to be current.
            if (payload.administrativeWritesLocked) {
                queries.upsertRecoveryState(
                    admin_writes_locked = 1L,
                    reason = "restored from a backup taken during a recovery preview",
                    preview_generation = payload.authorityGeneration,
                )
            }
            policyToInsert.forEach { entry ->
                val (kind, body) = OwnerPolicyCodec.encodeBody(entry.body)
                queries.insertOwnerPolicyEntry(
                    entry_id = entry.id.value,
                    policy_sequence = entry.policySequence,
                    parent_entry_id = entry.parent?.value,
                    authority_generation = entry.authorityGeneration,
                    signer_npub = entry.signerNpub,
                    signature = entry.signature,
                    body_kind = kind,
                    body_json = body,
                    received_at = 0L,
                )
            }
            payload.sealedItems.forEach {
                queries.insertSealedItem(
                    item_id = it.itemId,
                    category = it.category.name,
                    key_scope = it.keyScope.name,
                    key_id = it.keyId,
                    resource_epoch = it.resourceEpoch,
                    ciphertext = it.ciphertext,
                    created_at = 0L,
                )
            }
        }

        // Re-wrap the data keys under this device's own Keystore.
        //
        // A key store can refuse — the device is locked, the entry was evicted,
        // the hardware said no — and the honest answer is to fail the whole
        // recovery. Carrying on would report success while some data keys were
        // never re-wrapped: the restored history would say the person has
        // access to their notes and the notes would not open. Because the
        // recovery is one transaction, failing here costs nothing.
        //
        // Re-wrapping mints a Keystore alias per handle, and a Keystore entry is
        // not part of the SQL transaction: rolling the database back leaves the
        // alias behind. So the handles this recovery created are recorded and
        // destroyed if it fails — and *only* those. An alias the install
        // already had is somebody's live data key, and destroying it would
        // erase content the recovery never touched.
        payload.dataKeys.forEach { backupKey ->
            val handle = KeyHandle(backupKey.scope, backupKey.id)
            val alreadyHeld = readWrappedKey(handle, backupKey.resourceEpoch) != null
            val rewrapped = try {
                vault.importFromBackup(handle, backupKey.dataKey)
            } catch (e: SharingCryptoException) {
                lastRestoreRejection = "a data key could not be re-wrapped on this device"
                throw BatchRejected("a data key could not be re-wrapped on this device")
            }
            if (!alreadyHeld) minted += handle
            queries.upsertWrappedKey(
                handle.scope.name, handle.id, backupKey.resourceEpoch, rewrapped.wrappedBytes, 0L,
            )
        }

        // Relationship entries go through the ordinary door, so the projection
        // is rebuilt exactly as it would be for live entries — but a refusal
        // here fails the whole import.
        //
        // Its result used to be dropped. The validation pass above and this
        // write pass do not check quite the same things — a peer this install
        // purged is invisible to the first and refused by the second — so a
        // backup could restore everything *around* such an entry and report
        // success, leaving a history with a hole in it that nothing downstream
        // can see.
        relationshipsByPeer.forEach { (_, entries) ->
            entries.sortedBy { it.policySequence }.forEach { entry ->
                if (!tryAppendEntry(entry)) {
                    lastRestoreRejection =
                        "entry ${entry.id.value} was refused on the way in: " +
                            (lastInboundRejection ?: "inadmissible")
                    throw BatchRejected(lastRestoreRejection!!)
                }
            }
        }
        rewriteOwnerPolicyProjection()
        return true
    }

    private fun rewriteOwnerPolicyProjection() = queries.transaction { rewriteOwnerPolicyProjectionLocked() }

    // ------------------------------------------------------ whole actions

    /** Thrown inside a batch to roll the whole thing back. Never escapes. */
    private class BatchRejected(override val message: String) : RuntimeException(message)

    /**
     * Stores everything one user action produced, or none of it.
     *
     * Several things a person does are more than one signed entry — a baseline
     * change is a policy entry plus a `GrantChanged` per relationship it
     * reaches. Writing those one at a time was safe while signing was instant
     * and local; with an external signer each is its own approval prompt, and a
     * decline halfway used to leave the earlier entries behind.
     *
     * Callers sign and verify every entry first, so reaching here means the
     * only remaining failure is an inadmissible entry — our own broken
     * invariant. That rolls the transaction back and reports `false` rather
     * than throwing, because a half-applied user action is the one outcome
     * worth going out of our way to make impossible.
     *
     * The policy entries go first: a `GrantChanged` is computed from the policy
     * the same action is establishing.
     */
    fun commitAll(
        policyEntries: List<OwnerPolicyEntry>,
        relationshipEntries: List<SharingLedgerEntry>,
        attestations: List<AuthorityAttestation> = emptyList(),
    ): Boolean = runBatch {
        // The single door for anything this install authors itself, so the
        // preview lock is enforced here as well as at the controller. Two
        // callers — purge and restore — reach it without going through
        // `commit(batch)`, and a guard that only covered the common path would
        // be a guard with two ways round it.
        //
        // Recovery does not come through here: it has its own root-authorised
        // doors, which is what releases the lock in the first place.
        if (administrativeWritesLocked()) {
            throw BatchRejected("a recovery preview is open, so this install authors nothing")
        }
        // The acts go first. An entry whose authority is refused must not exist
        // even briefly: a permission change with no signed device behind it is
        // indistinguishable, on the next read, from one nobody ever authorised.
        // Pairing first, over the whole batch, before a single row is written.
        // An entry whose authority is refused must not exist even briefly.
        relationshipEntries.forEach { requirePairing(it, attestations) }
        policyEntries.forEach { requirePairing(it, attestations) }
        attestations.forEach { act ->
            when (val verdict = admitAttestation(act)) {
                is LedgerAdmission.AlreadyPresent -> Unit
                is LedgerAdmission.Reject -> throw BatchRejected(verdict.reason)
                is LedgerAdmission.Accept -> persistAttestation(act)
            }
        }
        policyEntries.forEach { entry ->
            when (val verdict = admitOwnerPolicy(entry)) {
                is LedgerAdmission.AlreadyPresent -> Unit
                is LedgerAdmission.Reject -> throw BatchRejected(verdict.reason)
                is LedgerAdmission.Accept -> persistOwnerPolicy(entry)
            }
        }
        relationshipEntries.forEach { entry -> admitAndPersist(entry) }
    }

    // commitRestore is gone, and so is a public restoreBackupPayload. Either
    // was a payload-plus-code shortcut past the recovery gate: a file and a
    // code would write, with no root challenge, no freshness decision and no
    // fencing. commitRecoveryFromBackup is the only door, and it takes signed
    // plans that only the gate can produce.

    private fun admitAndPersist(entry: SharingLedgerEntry) {
        if (isPurged(entry.peer)) throw BatchRejected("peer is purged")
        when (val verdict = admit(entry)) {
            is LedgerAdmission.AlreadyPresent -> Unit
            is LedgerAdmission.Reject -> throw BatchRejected(verdict.reason)
            is LedgerAdmission.Accept -> persist(entry)
        }
    }

    /**
     * Runs [body] in one transaction, rolled back whole if anything refuses.
     *
     * Admission inside the transaction is deliberate: entries in one action
     * build on each other — an invitation's offer claims the sequence after its
     * circle assignment — so each has to be checked against a ledger that
     * already contains the ones before it.
     */
    /**
     * Why the last batch was rolled back, or null if it was not.
     *
     * Kept because "nothing was saved" with no reason is the hardest kind of
     * failure to act on — for a person reading the screen and for whoever has
     * to work out why afterwards.
     */
    var lastBatchRejection: String? = null
        private set

    private fun runBatch(body: () -> Unit): Boolean = try {
        queries.transaction { body() }
        lastBatchRejection = null
        true
    } catch (rejected: BatchRejected) {
        lastBatchRejection = rejected.message
        false
    }

    /**
     * The policy that *would* hold if [pending] were appended, computed without
     * writing it.
     *
     * A baseline change decides what each relationship is offered, so the offer
     * entries have to be built against the new policy — but they have to be
     * built before the policy is stored, or a declined offer prompt would leave
     * the policy changed.
     */
    fun projectedOwnerPolicyState(pending: OwnerPolicyEntry): OwnerPolicyState =
        // The authorised branch plus the entry about to extend it, folded
        // directly. Going through the resolver here would ask it to rank an
        // entry whose act does not exist yet — it is signed after this — and
        // the answer would be "nothing is authorised", which is not what the
        // caller is asking.
        ownerPolicyReducer.reduce(
            AuthorityBranchResolver.policyHistory(
                loadOwnerPolicyLedger(),
                trustedAttestations(),
            ) + pending,
        )

    /**
     * The relationship states a restore of [payload] would produce, computed
     * without writing anything. `null` if the payload is inadmissible.
     *
     * Mirrors the validation [restoreBackupPayload] does, for the same reason
     * it does it: to decide before touching the database. Any drift between the
     * two shows up as a rejected entry inside [commitRestore], which rolls back.
     */
    @Suppress("ReturnCount")
    fun projectedRestoreState(payload: SharingBackupPayload): Map<PeerId, RelationshipState>? {
        if (payload.ownerNpub != ownerNpub) return null
        val projectedManifest = deviceManifestReducer.reduce(
            (loadDeviceManifest() + payload.deviceManifest).distinctBy { it.id }
                .sortedBy { it.manifestSequence },
            (loadAttestations() + payload.attestations).distinctBy { it.id },
        )
        val projectedActs = (loadAttestations() + payload.attestations).distinctBy { it.id }

        val purgedPeers = payload.tombstones.filter { it.kind == "PURGED" }.map { it.peerNpub }.toSet()
        val fromPayload = payload.relationshipEntries.groupBy { it.peer }
        val states = mutableMapOf<PeerId, RelationshipState>()

        (loadProjection().relationships.keys + fromPayload.keys).forEach { peer ->
            if (peer.value in purgedPeers || isPurged(peer)) return@forEach
            val seen = loadLedger(peer).toMutableList()
            fromPayload[peer].orEmpty().sortedBy { it.policySequence }.forEach { entry ->
                when (val v = SharingLedgerAdmission.check(seen, entry, verifier, ownerNpub)) {
                    is LedgerAdmission.Reject -> {
                        lastRestoreRejection = "projection ${entry.id.value}: ${v.reason}"
                        return null
                    }
                    is LedgerAdmission.AlreadyPresent -> Unit
                    is LedgerAdmission.Accept -> seen += entry
                }
            }
            // Reduced against the authority the *payload* carries, not this
            // install's. A fresh restore target holds no acts and no manifest,
            // so reducing against its own state found no authorised branch and
            // called every relationship fail-closed — the file was refused for
            // lacking authority it was itself bringing.
            states[peer] = reducer.reduce(
                peer,
                seen,
                TrustedAttestations.of(projectedActs, projectedManifest, attestationVerifier),
            )
        }
        return states
    }

    /**
     * The entry a peer's branch would end at once [payload] is applied.
     *
     * What a restore's own entries have to build on. Asking this install's
     * stored head instead returns nothing mid-restore — the payload is not
     * written yet — so the entry came out parentless and was refused for
     * claiming to begin a relationship that already had a beginning.
     */
    /** The manifest a restore of [payload] would establish, computed dry. */
    fun projectedAuthority(payload: SharingBackupPayload): DeviceAuthorityState =
        deviceManifestReducer.reduce(
            (loadDeviceManifest() + payload.deviceManifest).distinctBy { it.id }
                .sortedBy { it.manifestSequence },
            (loadAttestations() + payload.attestations).distinctBy { it.id },
        )

    fun projectedHead(payload: SharingBackupPayload, peer: PeerId): SharingLedgerEntry? {
        val manifest = deviceManifestReducer.reduce(
            (loadDeviceManifest() + payload.deviceManifest).distinctBy { it.id }
                .sortedBy { it.manifestSequence },
            (loadAttestations() + payload.attestations).distinctBy { it.id },
        )
        val acts = (loadAttestations() + payload.attestations).distinctBy { it.id }
        val entries = (loadLedger(peer) + payload.relationshipEntries.filter { it.peer == peer })
            .distinctBy { it.id }
        return AuthorityBranchResolver
            .history(entries, TrustedAttestations.of(acts, manifest, attestationVerifier))
            .lastOrNull()
    }

    /** The sequence a peer's next entry would claim after [restored] is applied. */
    fun projectedNextSequence(restored: Map<PeerId, RelationshipState>, peer: PeerId): Long =
        maxOf(restored[peer]?.lastSequence ?: 0L, nextSequence(peer) - 1L) + 1L

    // ------------------------------------------------------ device manifest

    fun loadDeviceManifest(): List<DeviceManifestEntry> =
        queries.selectDeviceManifest().executeAsList().map { row ->
            DeviceManifestEntry(
                id = LedgerEntryId(row.entry_id),
                manifestSequence = row.manifest_sequence,
                authorityGeneration = row.authority_generation,
                parent = row.parent_entry_id?.let { LedgerEntryId(it) },
                signerNpub = row.signer_npub,
                signature = row.signature,
                body = DeviceManifestCodec.decodeBody(row.body_kind, row.body_json),
            )
        }

    /**
     * The number a new manifest entry would claim.
     *
     * One past the tip of the **authorised** history, not past the highest
     * number on disk. A losing branch can be longer than the winning one — two
     * role changes beaten by a single revocation — and following its numbering
     * leaves every later entry hanging off a branch nobody chose, pushing the
     * count forward for ever while the winner stays where it is. The same rule
     * the other two ledgers already number by.
     */
    fun nextManifestSequence(): Long {
        val head = loadDeviceAuthority().head ?: return 1L
        return (loadDeviceManifest().firstOrNull { it.id == head }?.manifestSequence ?: 0L) + 1L
    }

    /** Reduced straight from the entries — there is no second opinion on disk. */
    fun loadDeviceAuthority(): DeviceAuthorityState =
        deviceManifestReducer.reduce(loadDeviceManifest(), loadAttestations())

    fun admitDeviceManifest(entry: DeviceManifestEntry): LedgerAdmission =
        DeviceManifestAdmission.check(
            loadDeviceManifest(),
            entry,
            deviceManifestVerifier,
            ownerNpub,
            loadAttestations(),
            attestationVerifier,
        )

    /** Reports rather than throwing, for anything that did not originate here. */
    fun tryAppendDeviceManifestEntry(entry: DeviceManifestEntry): Boolean =
        when (admitDeviceManifest(entry)) {
            is LedgerAdmission.AlreadyPresent -> true
            is LedgerAdmission.Reject -> false
            is LedgerAdmission.Accept -> { persistDeviceManifest(entry); true }
        }

    /** Hard failure: a local entry that is refused is our own broken invariant. */
    fun appendDeviceManifestEntry(entry: DeviceManifestEntry) {
        when (val verdict = admitDeviceManifest(entry)) {
            is LedgerAdmission.AlreadyPresent -> return
            is LedgerAdmission.Reject -> error("refusing to store manifest entry: ${verdict.reason}")
            is LedgerAdmission.Accept -> persistDeviceManifest(entry)
        }
    }

    private fun persistDeviceManifest(entry: DeviceManifestEntry) {
        val (kind, payload) = DeviceManifestCodec.encodeBody(entry.body)
        queries.insertDeviceManifestEntry(
            entry_id = entry.id.value,
            manifest_sequence = entry.manifestSequence,
            authority_generation = entry.authorityGeneration,
            parent_entry_id = entry.parent?.value,
            signer_npub = entry.signerNpub,
            signature = entry.signature,
            body_kind = kind,
            body_json = payload,
            received_at = 0L,
        )
    }

    // ------------------------------------------------- authority attestations

    fun loadAttestations(): List<AuthorityAttestation> =
        queries.selectAttestations().executeAsList().map { row ->
            AuthorityAttestation(
                id = LedgerEntryId(row.attestation_id),
                scope = AuthorityScope(row.scope),
                subject = LedgerEntryId(row.subject_entry_id),
                device = AuthorityDeviceId(row.device_id),
                parent = row.parent_attestation_id?.let { LedgerEntryId(it) },
                // A row with no readable context binds nothing, which the
                // trust check refuses — see migration 22.
                manifestContext = row.manifest_context?.let { ManifestContext.parse(it) }
                    ?: ManifestContext.genesis,
                authorityGeneration = row.authority_generation,
                capability = DeviceCapability.valueOf(row.capability),
                effect = AuthorityEffect.valueOf(row.effect),
                signature = row.signature,
            )
        }

    /**
     * The act that stands in each scope, decided by the shared resolver.
     *
     * Gated on the same estate-wide check the projections run, so the screen
     * and the projection cannot disagree about whether the estate is readable.
     */
    fun currentAuthority(): Map<AuthorityScope, AuthorityAttestation> {
        if (estateFailure() != null) return emptyMap()
        return AuthorityLedger.winners(trustedAttestations(), knownSubjects())
    }

    /**
     * Every entry id an act could legitimately name.
     *
     * An act whose subject is missing — never stored, or purged since — vouches
     * for nothing, and leaving it in the heads would let the next act chain
     * onto a claim about an entry that does not exist.
     */
    private fun knownSubjects(): Set<LedgerEntryId> =
        queries.selectAllLedgerEntryIds().executeAsList().map { LedgerEntryId(it) }.toSet() +
            loadDeviceManifest().map { it.id } +
            loadOwnerPolicyLedger().map { it.id }

    fun admitAttestation(
        candidate: AuthorityAttestation,
        /** Manifest entries landing in the same batch, not yet on disk. */
        pendingManifest: List<DeviceManifestEntry> = emptyList(),
    ): LedgerAdmission =
        AuthorityAttestationAdmission.check(
            loadAttestations(),
            candidate,
            // Reduced with the batch's own manifest entries and this act, so a
            // recovery's acts can name the head it is establishing: the
            // returning device does not exist in the manifest that was there a
            // moment ago, and the entries have not landed yet.
            if (pendingManifest.isEmpty()) {
                loadDeviceAuthority()
            } else {
                deviceManifestReducer.reduce(
                    loadDeviceManifest() + pendingManifest,
                    (loadAttestations() + candidate).distinctBy { it.id },
                )
            },
            attestationVerifier,
            // Always exact. There used to be an `inbound` flag here that
            // waived it, on the reasoning that an act from elsewhere was
            // authored against whatever frontier that install had. But a
            // signature proves only which frontier its author *declared*, not
            // that the author had not seen more — so the flag let a device
            // revoked on an independent branch keep writing by naming the
            // frontier from before that branch. Historical contexts are
            // accepted only where the whole file is root-authenticated; see
            // RequiredManifestContext.
            requiredManifestContext = RequiredManifestContext.Exactly(
                requiredManifestContextFor(candidate.subject, pendingManifest),
            ),
        )

    /**
     * The manifest head an act about [subject] has to name.
     *
     * A change to the manifest cannot be its own justification, so an act
     * authorising one binds the frontier from **before** that change. Checked
     * against the state it produces, an enrolment would be authorised by the
     * device it enrols, and a fork would be judged against a merge that exists
     * only because both sides were let in. Everything else binds the whole
     * frontier standing now — every branch of it, because independent manifest
     * scopes merge and one head names only one of them.
     */
    private fun requiredManifestContextFor(
        subject: LedgerEntryId,
        pendingManifest: List<DeviceManifestEntry> = emptyList(),
    ): ManifestContext {
        val stored = loadDeviceAuthority()
        val entries = loadDeviceManifest() + pendingManifest
        entries.firstOrNull { it.id == subject }?.let { mutated ->
            // The estate before this entry. It is not stored yet at admission
            // time, so the frontier standing now is exactly that — except that
            // a batch may bring several manifest entries, and each later one
            // sees the ones before it.
            val earlier = pendingManifest.takeWhile { it.id != mutated.id }
            if (earlier.isEmpty()) return ManifestContext.of(stored.frontier)
            val followed = earlier.mapNotNull { it.parent }.toSet()
            return ManifestContext.of(
                (stored.frontier + earlier.map { it.id }).filterNot { it in followed },
            )
        }
        // An ordinary act binds the frontier that stands once the batch lands.
        // A recovery writes its manifest and the acts that go with it together,
        // and those acts speak for the estate it is establishing — the
        // returning device does not exist in the one that was there a moment
        // ago.
        if (pendingManifest.isEmpty()) return ManifestContext.of(stored.frontier)
        val followed = pendingManifest.mapNotNull { it.parent }.toSet()
        return ManifestContext.of(
            (stored.frontier + pendingManifest.map { it.id }).filterNot { it in followed },
        )
    }

    fun tryAppendAttestation(candidate: AuthorityAttestation): Boolean =
        when (admitAttestation(candidate)) {
            is LedgerAdmission.AlreadyPresent -> true
            is LedgerAdmission.Reject -> false
            is LedgerAdmission.Accept -> { persistAttestation(candidate); true }
        }

    fun appendAttestation(candidate: AuthorityAttestation) {
        when (val verdict = admitAttestation(candidate)) {
            is LedgerAdmission.AlreadyPresent -> return
            is LedgerAdmission.Reject -> error("refusing to store attestation: ${verdict.reason}")
            is LedgerAdmission.Accept -> persistAttestation(candidate)
        }
    }

    private fun persistAttestation(a: AuthorityAttestation) {
        queries.insertAttestation(
            attestation_id = a.id.value,
            scope = a.scope.value,
            subject_entry_id = a.subject.value,
            device_id = a.device.value,
            parent_attestation_id = a.parent?.value,
            manifest_context = a.manifestContext.canonical,
            authority_generation = a.authorityGeneration,
            capability = a.capability.name,
            effect = a.effect.name,
            signature = a.signature,
            received_at = 0L,
        )
    }

    // --------------------------------------------------------- recovery lock

    /**
     * Whether a recovery has locked administrative writes.
     *
     * Kept on disk rather than in memory because a lock a restart forgets is
     * not a lock — and forgetting it is precisely how a stale backup ends up
     * writing after all.
     */
    fun administrativeWritesLocked(): Boolean =
        queries.selectRecoveryState().executeAsOneOrNull()?.admin_writes_locked == 1L

    fun recoveryLockReason(): String? =
        queries.selectRecoveryState().executeAsOneOrNull()?.reason

    fun setAdministrativeWritesLocked(locked: Boolean, reason: String?, previewGeneration: Long?) {
        // Upsert rather than update: an install that never previewed a backup
        // has no row, and an UPDATE that quietly matches nothing would leave
        // the lock unset while reporting success.
        queries.upsertRecoveryState(
            admin_writes_locked = if (locked) 1L else 0L,
            reason = reason,
            preview_generation = previewGeneration,
        )
    }

    /**
     * Applies a whole recovery, or none of it.
     *
     * The manifest entries, the epoch entries and the acts that authorise them
     * go in one transaction with the lock release. Half a recovery is worse
     * than none: an install carrying a new generation with no enrolled device
     * holds no authority at all, and cannot enrol one either.
     */
    /**
     * A whole recovery — the backup's own history and the estate it
     * establishes — in one transaction.
     *
     * Restoring the payload separately meant declining a later prompt left the
     * backup written and the estate not: an install carrying somebody's entire
     * permission history with no device able to change any of it, and nothing
     * on screen saying that had happened. Everything is signed before this is
     * called, so the only remaining failure is a refused entry, and that rolls
     * the payload back with it.
     */
    /**
     * A file import: the payload and the estate it establishes, in one
     * transaction.
     *
     * The generation the authorisation must name is the one the *file's own
     * signed manifest* establishes — `restoreBackupPayload` refuses a file that
     * claims otherwise — so a legitimate authorisation for one backup cannot be
     * paired with a different one.
     */
    internal fun commitRootRecoveryFromBackup(
        payload: SharingBackupPayload,
        manifestEntries: List<DeviceManifestEntry>,
        relationshipEntries: List<SharingLedgerEntry>,
        attestations: List<AuthorityAttestation>,
        rootAuthorised: RootRecoveryAuthorization,
    ): Boolean {
        // Local to this attempt. On the instance it would be shared by every
        // concurrent recovery, so one failing would destroy keys another had
        // just minted.
        val minted = mutableListOf<KeyHandle>()
        val applied = runBatch {
            authoriseRootRecovery(
                rootAuthorised,
                manifestEntries,
                baseGeneration = payload.authorityGeneration,
            )
            if (!restoreBackupPayloadWithin(payload, minted)) {
                throw BatchRejected(lastRestoreRejection ?: "the backup was refused")
            }
            applyRecovery(manifestEntries, relationshipEntries, attestations, rootAuthorised = true)
        }
        if (!applied) {
            // The transaction rolled back, but the Keystore did not. Anything
            // this attempt minted has to go, or a failed recovery leaves keys
            // behind that nothing references and nothing will ever clean up.
            minted.forEach { runCatching { vault.destroy(it) } }
        }
        return applied
    }

    /**
     * The estate's very first manifest entry, which no device could have asked
     * for because none exists yet.
     *
     * Its own door, rather than the recovery one with a flag set: a genesis is
     * not a recovery, and giving it the recovery path meant the act-waiving
     * boolean had a second, entirely ordinary caller — which is how such a flag
     * stops looking like something to be careful about.
     */
    internal fun commitGenesisManifest(entry: DeviceManifestEntry): Boolean = runBatch {
        // The same second look `commitAll` takes, for the same reason: the
        // controller refused before anything was signed, but signing can take
        // as long as the person takes to approve it and a preview may have been
        // opened in between. Establishing the first primary is the change that
        // decides who may make every other change, so it is the last door that
        // should be the one without a guard.
        if (administrativeWritesLocked()) {
            throw BatchRejected("a recovery preview is open, so this install authors nothing")
        }
        if (entry.parent != null || loadDeviceManifest().isNotEmpty()) {
            throw BatchRejected("a genesis begins a history; this estate already has one")
        }
        when (val verdict = admitDeviceManifest(entry)) {
            is LedgerAdmission.AlreadyPresent -> Unit
            is LedgerAdmission.Reject -> throw BatchRejected(verdict.reason)
            is LedgerAdmission.Accept -> persistDeviceManifest(entry)
        }
    }

    /**
     * Why this authorisation does not authorise *this* mutation, or null.
     *
     * The signature says what the owner agreed to. It says nothing at all about
     * what the caller then went on to write, so every term of the agreement is
     * checked against the actual batch here — same device, same generation,
     * same kind of recovery, same enrolment — before the attempt is spent.
     *
     * [baseGeneration] is what the authorisation's generation must equal: the
     * file's own signed manifest for an import, and the generation this install
     * currently establishes for a code-only recovery. Neither is a number a
     * caller supplies.
     */
    @Suppress("ReturnCount")
    private fun rootRecoveryRefusal(
        authorization: RootRecoveryAuthorization,
        manifestEntries: List<DeviceManifestEntry>,
        baseGeneration: Long,
    ): String? {
        val crypto = rootRecoveryVerifier ?: return "no root verifier is wired"
        val identity = localDeviceIdentity()
            ?: return "this install holds no device identity, so no recovery can re-enrol it"

        if (authorization.ownerNpub != ownerNpub) return "the authorisation names another owner"
        if (authorization.devicePublicKey != identity.publicKey) {
            return "the authorisation is for a device this install does not hold"
        }
        if (authorization.backupGeneration != baseGeneration) {
            return "the authorisation is for generation ${authorization.backupGeneration}, " +
                "but this recovery is against $baseGeneration"
        }

        // The opening body is what makes a recovery a reset or a restore, and
        // the owner agreed to one of the two. Reading the flag off the
        // authorisation while writing the other is the whole attack.
        val opening = manifestEntries.firstOrNull()?.body
            ?: return "a recovery writes at least a rotation and an enrolment"
        val opensAReset = opening is DeviceManifestBody.SovereignReset
        if (opensAReset != authorization.sovereignReset) {
            return if (authorization.sovereignReset) {
                "the owner agreed to a sovereign reset, but these entries do not perform one"
            } else {
                "these entries perform a sovereign reset the owner did not agree to"
            }
        }
        if (!opensAReset && opening !is DeviceManifestBody.AuthorityRotated) {
            return "a recovery opens with a rotation or a sovereign reset, not ${opening::class.simpleName}"
        }
        (opening as? DeviceManifestBody.AuthorityRotated)?.let {
            if (it.generation != baseGeneration + 1) {
                return "a rotation must advance $baseGeneration by one, not to ${it.generation}"
            }
        }

        // Exactly one enrolment, and it has to be this device with this key.
        val enrolments = manifestEntries.mapNotNull { it.body as? DeviceManifestBody.DeviceEnrolled }
        val enrolled = enrolments.singleOrNull()
            ?: return "a recovery enrols exactly one device, not ${enrolments.size}"
        if (enrolled.device != identity.device || enrolled.publicKey != identity.publicKey) {
            return "a recovery may only enrol the device that is running it"
        }

        if (queries.selectRecoveryAttempt(authorization.attemptNonce).executeAsOneOrNull() != null) {
            return "this authorisation has already been spent"
        }
        val hash = crypto.hash(
            RecoveryChallenge.bytes(
                ownerNpub = authorization.ownerNpub,
                recoveryCodeDigest = authorization.recoveryCodeDigest,
                newDevicePublicKey = authorization.devicePublicKey,
                backupGeneration = authorization.backupGeneration,
                sovereignReset = authorization.sovereignReset,
                attemptNonce = authorization.attemptNonce,
            ),
        )
        if (!crypto.verify(authorization.signature, hash, ownerNpub)) {
            return "the authorisation is not signed by the owner"
        }
        return null
    }

    /**
     * Checks everything and spends the attempt, inside the caller's
     * transaction.
     *
     * The nonce is consumed last, after every semantic check has passed, and in
     * the same transaction as the writes — so a later rollback leaves it
     * unspent and a success persists it.
     */
    private fun authoriseRootRecovery(
        authorization: RootRecoveryAuthorization,
        manifestEntries: List<DeviceManifestEntry>,
        baseGeneration: Long,
    ) {
        rootRecoveryRefusal(authorization, manifestEntries, baseGeneration)?.let {
            throw BatchRejected("the recovery authorisation does not authorise this recovery: $it")
        }
        queries.consumeRecoveryAttempt(authorization.attemptNonce, 0L)
    }

    /**
     * The ordinary device-paired batch. No root authorisation, and no way to
     * ask for one: a caller wanting the historical path has to go to the door
     * that demands the owner's answer.
     */
    internal fun commitRecovery(
        manifestEntries: List<DeviceManifestEntry>,
        relationshipEntries: List<SharingLedgerEntry>,
        attestations: List<AuthorityAttestation>,
    ): Boolean = runBatch {
        applyRecovery(manifestEntries, relationshipEntries, attestations, rootAuthorised = false)
    }

    /**
     * A code-only recovery: a rotation and this device's re-enrolment, with no
     * file behind it.
     *
     * The generation the authorisation must name is the one this install
     * currently establishes — read here, never passed in.
     */
    internal fun commitRootRecovery(
        manifestEntries: List<DeviceManifestEntry>,
        relationshipEntries: List<SharingLedgerEntry>,
        attestations: List<AuthorityAttestation>,
        rootAuthorised: RootRecoveryAuthorization,
    ): Boolean = runBatch {
        authoriseRootRecovery(
            rootAuthorised,
            manifestEntries,
            baseGeneration = loadDeviceAuthority().authorityGeneration,
        )
        applyRecovery(manifestEntries, relationshipEntries, attestations, rootAuthorised = true)
    }

    /** The recovery's own writes. Always called inside a transaction. */
    private fun applyRecovery(
        manifestEntries: List<DeviceManifestEntry>,
        relationshipEntries: List<SharingLedgerEntry>,
        attestations: List<AuthorityAttestation>,
        rootAuthorised: Boolean,
    ) {
        // The ordinary device-paired batch is a local administrative write and
        // the preview lock stops it here as well as at the controller —
        // enrolling a device, changing a role and revoking one all arrive
        // through this door. A root-authorised recovery is exactly the thing
        // that resolves a preview, so it is never refused by it.
        if (!rootAuthorised && administrativeWritesLocked()) {
            throw BatchRejected("a recovery preview is open, so this install authors nothing")
        }
        if (!rootAuthorised) manifestEntries.forEach { requirePairing(it, attestations) }
        relationshipEntries.forEach { requirePairing(it, attestations) }
        // Acts first, and the order is load-bearing now. An ordinary manifest
        // mutation is only readable once the act asking for it is on record, so
        // storing the entry first would fail the manifest closed mid-batch and
        // refuse the act that was about to fix it. Each act is still judged at
        // the manifest head it names — the entry it authorises has not landed
        // yet, which is exactly the parent snapshot it must be judged against.
        attestations.forEach { act ->
            when (val verdict = admitAttestation(act, manifestEntries)) {
                is LedgerAdmission.AlreadyPresent -> Unit
                is LedgerAdmission.Reject -> throw BatchRejected(verdict.reason)
                is LedgerAdmission.Accept -> persistAttestation(act)
            }
        }
        manifestEntries.forEach { entry ->
            when (val verdict = admitDeviceManifest(entry)) {
                is LedgerAdmission.AlreadyPresent -> Unit
                is LedgerAdmission.Reject -> throw BatchRejected(verdict.reason)
                is LedgerAdmission.Accept -> persistDeviceManifest(entry)
            }
        }
        relationshipEntries.forEach { entry -> admitAndPersist(entry) }
        // Only a root-authorised recovery that actually succeeds releases the
        // lock, and it does so inside this transaction: a preview, a refusal or
        // an abandoned signature leaves it exactly where it was. The ordinary
        // device-paired batch comes through here too, and must not clear a lock
        // it had nothing to do with.
        if (rootAuthorised) {
            queries.upsertRecoveryState(admin_writes_locked = 0L, reason = null, preview_generation = null)
        }
    }

    /**
     * Refuses an owner-signed entry that no act authorises.
     *
     * The requirement is derived from the entry's own signed body, so a device
     * cannot influence what its act has to say: it is a function of content the
     * owner's key already signed. A body the *peer* authored — their acceptance,
     * their refusal, their own device — needs no owner act, because it is
     * carried by the peer's signature instead.
     */
    /**
     * The single guard every public write goes through.
     *
     * Throws rather than returning a verdict because the local paths treat a
     * refusal as a broken invariant of our own, exactly as they treat an
     * inadmissible entry.
     */
    private fun requirePairingOrThrow(entry: SharingLedgerEntry) {
        val required = AuthorityPairing.requiredFor(entry.peer, entry.body) ?: return
        val verdict = AuthorityPairing.pair(required, entry.id, loadAttestations())
        if (verdict is AuthorityPairing.Verdict.Refused) {
            error("refusing to store ledger entry: ${verdict.reason}")
        }
    }

    private fun requirePairing(entry: SharingLedgerEntry, acts: List<AuthorityAttestation>) {
        val required = AuthorityPairing.requiredFor(entry.peer, entry.body) ?: return
        when (val verdict = AuthorityPairing.pair(required, entry.id, acts)) {
            is AuthorityPairing.Verdict.Paired -> Unit
            is AuthorityPairing.Verdict.Refused -> throw BatchRejected(verdict.reason)
        }
    }

    private fun requirePairing(entry: OwnerPolicyEntry, acts: List<AuthorityAttestation>) {
        val required = AuthorityPairing.requiredFor(entry.body) ?: return
        when (val verdict = AuthorityPairing.pair(required, entry.id, acts)) {
            is AuthorityPairing.Verdict.Paired -> Unit
            is AuthorityPairing.Verdict.Refused -> throw BatchRejected(verdict.reason)
        }
    }

    /**
     * Manifest entries pair against an act **whenever a device could have
     * produced one**.
     *
     * The exception is narrow and load-bearing: an entry written when no device
     * in the current manifest holds the required capability cannot be paired,
     * because there is nothing to pair with. That is exactly two situations —
     * the very first enrolment on a fresh install, and a recovery that has just
     * fenced every device — and both are authorised instead by the owner's
     * **root** signature, which the manifest admission verifies independently.
     *
     * This is not a bypass a device can reach for. A device cannot revoke the
     * whole estate to unlock it: revoking is itself an administrative act that
     * needs the capability, and the last remaining primary cannot revoke itself
     * (the screen refuses, and revoking others still leaves it holding the
     * capability). Reaching the unpaired state requires the root key, which
     * sits above the device layer by construction — the device model constrains
     * *devices*, and root compromise is a different threat with a different
     * answer.
     */
    private fun requirePairing(entry: DeviceManifestEntry, acts: List<AuthorityAttestation>) {
        val required = AuthorityPairing.requiredFor(entry.body) ?: return
        val before = loadDeviceAuthority()
        val someDeviceCould = before.failClosedReason == null &&
            before.devices.keys.any { before.can(it, required.capability) }
        if (!someDeviceCould) return
        when (val verdict = AuthorityPairing.pair(required, entry.id, acts)) {
            is AuthorityPairing.Verdict.Paired -> Unit
            is AuthorityPairing.Verdict.Refused -> throw BatchRejected(verdict.reason)
        }
    }

    // ---------------------------------------------------------------- purge

    private fun isPurged(peer: PeerId): Boolean =
        queries.selectTombstones(peer.value).executeAsList().any { it.kind == "PURGED" }

    /** Thrown to roll a half-finished removal back. Never escapes. */
    private object PurgeIncomplete : RuntimeException() {
        private fun readResolve(): Any = PurgeIncomplete
    }

    /**
     * Deletes everything this device holds **about one relationship**: its
     * ledger, its projection row, its devices, and a sticky tombstone so it
     * cannot come back. The tombstone is written *before* the delete, so it is
     * already durable when the cascade runs.
     *
     * It runs through the crypto-erase pipeline, keys first, because the order
     * is part of the contract even where a step currently has nothing to do —
     * wiring one up later must not be able to forget it.
     *
     * ## All of it, or none of it
     *
     * The whole removal is one transaction, and that is not tidiness. The
     * tombstone used to be written before the pipeline ran and was left behind
     * when it failed — and destroying a Keystore alias is a call into the
     * platform that really can fail: a key invalidated by a lock-screen change,
     * a locked user, a vendor keystore that throws.
     *
     * A tombstone is sticky by design and every write door refuses a peer
     * carrying one, so what was left was a relationship with no way out: rows,
     * ledger and ciphertext all still on the device, a failure on screen, and
     * the retry refused by the marker the failed attempt had itself left. The
     * person is told their data could not be deleted, and from then on nobody
     * can delete it.
     *
     * So a removal that cannot finish records nothing and can simply be asked
     * for again. Keystore aliases already destroyed do not come back — nothing
     * outside the database can be rolled back — but those belong to the peer
     * being removed, which is the safe direction, and the retry destroys the
     * rest.
     *
     * ## What can still escape, and why it is left alone
     *
     * The caller reads the returned outcome and nothing else, so it is worth
     * being exact about what this does *not* return through.
     *
     * Every step of the pipeline is caught by the pipeline itself, including
     * the row deletion, so a database error there is reported. The tombstone
     * insert is `INSERT OR IGNORE`, so running this twice does not collide.
     * What is no longer under a catch is the transaction's **commit**, which
     * happens after the pipeline has finished: before this was one transaction,
     * each statement committed inside a step.
     *
     * That window is deliberately not papered over with a `catch (Throwable)`.
     * A commit failure still rolls the whole thing back, so the two things that
     * matter hold either way — nothing is marked removed that was not, and the
     * removal can be asked for again. Wrapping it would only change how the
     * screen renders a failure it cannot currently be shown, and it could not
     * be pinned by a test without fabricating a driver whose commit fails,
     * which would assert the mock rather than the behaviour.
     *
     * ## What it deliberately does not touch
     *
     * The owner's content keys and ciphertexts. This used to destroy every
     * category key plus the object keys the removed person had rules on, which
     * was a serious mistake: those keys are not the recipient's. A category key
     * encrypts *the owner's* videos and an object key *the owner's* object, so
     * removing Alice destroyed the owner's data and every other relationship's
     * access to the same category along with it.
     *
     * Ending a **recipient's** cryptographic access is a different operation
     * and is not implemented here — see [relationshipScopedKeys].
     */
    fun purgeLocalRelationshipData(
        peer: PeerId,
        onStep: (CryptoEraseStep) -> Unit = {},
    ): CryptoEraseOutcome {
        var outcome = CryptoEraseOutcome(completed = false, failedStep = null, keysDestroyed = false)
        try {
            queries.transaction {
                queries.insertTombstone(peer.value, "PURGED", peer.value, 0L)
                outcome = CryptoErasePipeline.run { step ->
                    onStep(step)
                    when (step) {
                        CryptoEraseStep.DESTROY_KEYS -> destroyRelationshipKeys(peer)
                        CryptoEraseStep.DATABASE_ROWS -> queries.deleteRelationship(peer.value)
                        // The remaining surfaces have no store of their own in
                        // this slice. They are kept in the sequence rather than
                        // removed, so wiring one up later cannot forget to run
                        // it in the right order.
                        else -> Unit
                    }
                }
                // The pipeline reports a failed step rather than throwing, so
                // without this the transaction would commit the tombstone of a
                // removal that did not happen.
                if (!outcome.completed) throw PurgeIncomplete
            }
        } catch (_: PurgeIncomplete) {
            // Rolled back. `outcome` already says which step failed.
        }
        return outcome
    }

    private fun destroyRelationshipKeys(peer: PeerId) {
        relationshipScopedKeys(peer).forEach { handle ->
            vault.destroy(handle)
            queries.deleteWrappedKeysFor(handle.scope.name, handle.id)
            queries.deleteSealedItemsFor(handle.scope.name, handle.id)
        }
    }

    /**
     * The key material that belongs to [peer] alone, and so may be destroyed
     * when [peer] is removed.
     *
     * Found by asking the tables what is actually there rather than by
     * recomputing what *ought* to be there from the policy. The policy is
     * exactly what the old code consulted, and it was the wrong question: it
     * named the owner's category and object keys, which are not the
     * recipient's. What a removal may destroy is a property of the key's own
     * name — [SharingKeyHandles.belongsTo] — so that is what is asked.
     *
     * Sealed items are scanned as well as wrapped keys. A ciphertext can
     * outlive its key row, and leaving one behind under a destroyed key would
     * be an unopenable orphan that a backup would still carry.
     */
    private fun relationshipScopedKeys(peer: PeerId): List<KeyHandle> {
        val fromKeys = queries.selectAllWrappedKeys().executeAsList()
            .map { KeyHandle(KeyScope.valueOf(it.key_scope), it.key_id) }
        val fromSealed = queries.selectAllSealedItems().executeAsList()
            .map { KeyHandle(KeyScope.valueOf(it.key_scope), it.key_id) }
        return (fromKeys + fromSealed)
            .distinct()
            .filter { SharingKeyHandles.belongsTo(it, peer) }
    }
}
