package com.cruxcoach.android.ui.sharing

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.sharing.PeerDetail
import com.cruxcoach.android.sharing.PeerIdParseError
import com.cruxcoach.android.sharing.PeerIdParseResult
import com.cruxcoach.android.sharing.SharingController
import com.cruxcoach.android.sharing.SharingBackupWriteOutcome
import com.cruxcoach.android.sharing.SharingRestoreOutcome
import com.cruxcoach.android.sharing.SharingWriteError
import com.cruxcoach.domain.sharing.SharingBackupError
import com.cruxcoach.android.sharing.SharingWriteResult
import com.cruxcoach.android.sharing.SharingPeerIdParser
import com.cruxcoach.android.sharing.SharingDemoData
import com.cruxcoach.android.sharing.DeviceEstate
import com.cruxcoach.android.sharing.SharingUiState
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.NativeSharingGate
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/**
 * Thin wrapper over [SharingController].
 *
 * All the rules live in the controller so they can be tested without Compose or
 * a dispatcher; this class only moves work off the main thread and republishes
 * the snapshot afterwards.
 */
@HiltViewModel
class SharingViewModel @Inject constructor(
    private val controller: SharingController,
) : ViewModel() {

    /**
     * Where the blocking work runs.
     *
     * A `var` only so a test can replace it: a mutation now parks inside an
     * external signer, and driving that step by step needs the work on the test
     * scheduler rather than on a real thread pool. Production never reassigns it.
     */
    @VisibleForTesting
    internal var ioContext: CoroutineContext = Dispatchers.IO

    private val _state = MutableStateFlow(
        SharingUiState(
            gate = NativeSharingGate.current(),
            baselines = com.cruxcoach.domain.sharing.CircleBaselines(),
            peers = emptyList(),
        )
    )
    val state: StateFlow<SharingUiState> = _state.asStateFlow()

    private val _estate = MutableStateFlow(
        DeviceEstate(
            devices = emptyList(),
            authorityGeneration = 0,
            thisDevice = null,
            canMutatePermissions = false,
            canAdministerDevices = false,
            canSovereignReset = false,
            canEnrolGenesis = false,
            administrativeWritesLocked = false,
            recoveryLockReason = null,
            failClosedReason = null,
        )
    )

    /** The owner's own devices, and what this one may currently do. */
    val estate: StateFlow<DeviceEstate> = _estate.asStateFlow()

    private val _detail = MutableStateFlow<PeerDetail?>(null)
    val detail: StateFlow<PeerDetail?> = _detail.asStateFlow()

    private val _continuous = MutableStateFlow<List<com.cruxcoach.android.sharing.ContinuousView>>(emptyList())
    val continuous = _continuous.asStateFlow()
    private val _pendingFriendships = MutableStateFlow<List<com.cruxcoach.android.sharing.PendingFriendshipView>>(emptyList())
    val pendingFriendships = _pendingFriendships.asStateFlow()
    fun cancelFriendshipRequest(peer: String) = mutateReporting { controller.cancelFriendshipRequest(peer) }
    private val _continuousRoles = MutableStateFlow<Map<PeerId, com.cruxcoach.android.sharing.SnapshotEndpointRole>>(emptyMap())
    val continuousRoles = _continuousRoles.asStateFlow()
    private val _continuousPreview = MutableStateFlow<Map<PeerId, List<com.cruxcoach.android.sharing.ContinuousRecord>>>(emptyMap())
    val continuousPreview = _continuousPreview.asStateFlow()
    private val _continuousPreviewScope = MutableStateFlow<com.cruxcoach.android.sharing.ContinuousScope?>(null)
    val continuousPreviewScope = _continuousPreviewScope.asStateFlow()
    init {
        viewModelScope.launch {
            controller.continuousChanges().collect {
                _continuous.value = withContext(ioContext) { controller.continuousViews() }
                _pendingFriendships.value = withContext(ioContext) { controller.pendingFriendships() }
                _continuousRoles.value = withContext(ioContext) { controller.continuousPeerRoles() }
            }
        }
    }
    private var previewGeneration = 0L
    fun clearContinuousPreview() { previewGeneration++; _continuousPreview.value = emptyMap(); _continuousPreviewScope.value = null }
    fun previewContinuous(peers: Set<PeerId>, scope: com.cruxcoach.android.sharing.ContinuousScope) = viewModelScope.launch {
        clearContinuousPreview()
        val requested = previewGeneration
        runCatching { withContext(ioContext) { peers.associateWith { controller.continuousPreview(it, scope) } } }
            .onSuccess { if (requested == previewGeneration) { _continuousPreviewScope.value = scope; _continuousPreview.value = it } }
            .onFailure { _writeError.value = SharingWriteError.TRANSPORT_LIMIT }
    }
    fun offerContinuous(peers: Set<PeerId>, scope: com.cruxcoach.android.sharing.ContinuousScope,
                        roles: Map<PeerId, com.cruxcoach.android.sharing.SnapshotEndpointRole>) = mutateReporting {
        controller.offerContinuous(peers, scope, roles)
    }
    fun acceptContinuous(id: String, server: Boolean, ownScope: com.cruxcoach.android.sharing.ContinuousScope = com.cruxcoach.android.sharing.ContinuousScope(emptySet(), "1970-01-01")) = mutateReporting {
        controller.acceptContinuous(id, if (server) com.cruxcoach.android.sharing.SnapshotEndpointRole.SERVER else com.cruxcoach.android.sharing.SnapshotEndpointRole.USER, ownScope)
    }
    fun endContinuous(id: String, pause: Boolean = false) = mutateReporting { controller.endContinuous(id, pause) }
    fun refreshContinuousView() = viewModelScope.launch {
        _continuous.value = withContext(ioContext) { controller.continuousViews() }
    }

    private val _snapshots = MutableStateFlow<List<com.cruxcoach.android.sharing.SharingSnapshotView>>(emptyList())
    val snapshots = _snapshots.asStateFlow()
    private val _snapshotText = MutableStateFlow<String?>(null)
    val snapshotText = _snapshotText.asStateFlow()
    private val _nativePeers = MutableStateFlow<List<com.cruxcoach.android.sharing.MarmotPeerStatus>>(emptyList())
    val nativePeers = _nativePeers.asStateFlow()
    private val _nativeRelays = MutableStateFlow(com.cruxcoach.android.sharing.MarmotRelayDefaults.urls)
    val nativeRelays = _nativeRelays.asStateFlow()
    private val _discoveryEnabled = MutableStateFlow(false)
    val discoveryEnabled = _discoveryEnabled.asStateFlow()
    private val _relayStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val relayStatus = _relayStatus.asStateFlow()
    private val _incomingPolicy = MutableStateFlow<com.cruxcoach.android.sharing.IncomingSharingPolicy?>(null)
    val incomingPolicy = _incomingPolicy.asStateFlow()
    private val _clockHealthy = MutableStateFlow(true)
    val clockHealthy = _clockHealthy.asStateFlow()
    private val _snapshotCapacity = MutableStateFlow(false)
    val snapshotCapacity = _snapshotCapacity.asStateFlow()
    fun recoverSharingTransport() = mutateReporting { controller.recoverSharingClock(archiveTransport = true) }
    fun recoverSharingClock() = mutateReporting { controller.recoverSharingClock() }
    fun collectExpiredSnapshots() = mutateReporting { controller.collectExpiredSnapshots() }
    suspend fun synchronizeWhileVisible() {
        if (!_signing.value && withContext(ioContext) { controller.discoveryEnabled() && controller.sharingClockHealthy() }) {
            withContext(ioContext) { controller.synchronizeContinuousAutomatically() }
            reload().join()
        }
    }
    fun bootstrapMarmot() = mutateReporting { controller.bootstrapMarmot() }
    fun configureMarmotRelays(text: String) = mutateReporting { controller.configureMarmotRelays(text.lines().map(String::trim).filter(String::isNotEmpty)) }
    fun connectMarmot(peer: PeerId, accept: Boolean, group: String? = null) = mutateReporting { controller.connectMarmot(peer, accept, group) }
    fun resetMarmotPeer(peer: PeerId) = mutateReporting { controller.resetMarmotPeer(peer) }
    fun acceptRemotePolicy(peer: PeerId, categories: Set<SharingCategory>) = mutateReporting { controller.acceptRemotePolicy(peer, categories) }


    fun pinSnapshotPeer(peer: PeerId, server: Boolean) = mutateReporting {
        controller.pinSnapshotPeer(peer, if (server) com.cruxcoach.android.sharing.SnapshotEndpointRole.SERVER
            else com.cruxcoach.android.sharing.SnapshotEndpointRole.USER)
    }
    fun setExpiry(peer: PeerId, expiresAt: Long?) = mutateReporting { controller.setExpiry(peer, expiresAt) }
    fun shareNoteSnapshot(peer: PeerId, text: String, server: Boolean, onSaved: () -> Unit) = viewModelScope.launch {
        val result = exclusively<SharingWriteResult?>(null) {
            withContext(ioContext) {
                controller.shareNoteSnapshot(peer, text, if (server) com.cruxcoach.android.sharing.SnapshotEndpointRole.SERVER
                    else com.cruxcoach.android.sharing.SnapshotEndpointRole.USER)
            }
        } ?: return@launch
        _writeError.value = (result as? SharingWriteResult.Failed)?.error
        if (result.isSuccess) onSaved()
        reload()
    }
    fun acceptSnapshot(id: String) = mutateReporting { controller.acceptSnapshot(id) }
    fun revokeSnapshot(id: String) = mutateReporting { controller.revokeSnapshot(id) }
    fun synchronizeSnapshots() = mutateReporting { controller.synchronizeSnapshots() }
    private var snapshotReadJob: Job? = null
    fun readSnapshot(id: String) {
        clearSnapshotText()
        snapshotReadJob = viewModelScope.launch {
            val expiry = _snapshots.value.firstOrNull { it.id == id }?.expiresAt ?: return@launch
            _snapshotText.value = withContext(ioContext) { controller.readSnapshot(id) }
            if (_snapshotText.value == null) _writeError.value = SharingWriteError.REJECTED
            kotlinx.coroutines.delay((expiry - System.currentTimeMillis()).coerceAtLeast(0))
            _snapshotText.value = null
        }
    }
    fun clearSnapshotText() { snapshotReadJob?.cancel(); snapshotReadJob = null; _snapshotText.value = null }

    private val _recoveryCode = MutableStateFlow<String?>(null)
    val recoveryCode: StateFlow<String?> = _recoveryCode.asStateFlow()

    private val _recoveryMessage = MutableStateFlow<RecoveryMessage?>(null)
    val recoveryMessage: StateFlow<RecoveryMessage?> = _recoveryMessage.asStateFlow()

    /** Demo seeding exists only in debug builds; release must never offer it. */
    val demoAvailable: Boolean = BuildConfig.DEBUG

    /**
     * What to tell the person after a recovery attempt.
     *
     * These were two values, and every attempt that did not apply became
     * `WRONG_CODE` — including a declined or cancelled signer prompt. Somebody
     * who typed their code correctly and then declined in Amber was told the
     * code was wrong, and would retype a correct code for as long as they had
     * patience. The refusals a person can act on are different actions, so they
     * are different messages.
     */
    enum class RecoveryMessage {
        /** The code does not match. Retyping it is the thing to do. */
        WRONG_CODE,

        /** The owner's signer refused, was cancelled, or is not wired. */
        SIGNER_REFUSED,

        /** Read-only: the backup could not be shown to be current. */
        PREVIEW_ONLY,

        /** Everything was proved and the write door still refused it. */
        REFUSED,

        RESTORED,
    }

    init { refresh() }

    fun refresh() = run { _state.value = _state.value; reload() }

    private fun reload(): kotlinx.coroutines.Job = viewModelScope.launch {
        clearSnapshotText()
        val snapshot = withContext(ioContext) { controller.snapshot() }
        _state.value = snapshot
        _estate.value = withContext(ioContext) { controller.deviceEstate() }
        _nativePeers.value = withContext(ioContext) { controller.nativePeers() }
        _nativeRelays.value = controller.nativeRelays()
        _discoveryEnabled.value = withContext(ioContext) { controller.discoveryEnabled() }
        _relayStatus.value = withContext(ioContext) { controller.nativeRelayStatus() }
        _clockHealthy.value = withContext(ioContext) { controller.sharingClockHealthy() }
        _snapshotCapacity.value = withContext(ioContext) { controller.snapshotCapacityReached() }
        _detail.value?.peer?.let { peer ->
            _detail.value = withContext(ioContext) { controller.peerDetail(peer) }
            _snapshots.value = withContext(ioContext) { controller.snapshotViews(peer) }
            _incomingPolicy.value = withContext(ioContext) { controller.incomingPolicy(peer) }
        }
    }

    private val _signing = MutableStateFlow(false)

    /**
     * True while a mutation is waiting for a signature.
     *
     * With an external signer that wait is a prompt in another app, so the
     * screens have to say something is happening and stop offering the same
     * action again.
     */
    val signing: StateFlow<Boolean> = _signing.asStateFlow()

    /**
     * Runs [block] as the one mutation in flight, or does nothing.
     *
     * This is what makes a double tap exactly once. The controller already
     * serialises writes, so a second tap could not corrupt anything — but it
     * would queue a second approval prompt behind the first and append a second
     * entry once both were approved, which is not what tapping twice on an
     * unresponsive button means.
     */
    private suspend fun <T> exclusively(fallback: T, block: suspend () -> T): T {
        if (!_signing.compareAndSet(expect = false, update = true)) return fallback
        return try {
            block()
        } finally {
            _signing.value = false
        }
    }

    private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
        exclusively(Unit) { withContext(ioContext) { block() } }
        reload()
    }

    fun openPeer(peer: PeerId) = viewModelScope.launch {
        _detail.value = withContext(ioContext) { controller.peerDetail(peer) }
        _snapshots.value = withContext(ioContext) { controller.snapshotViews(peer) }
        _incomingPolicy.value = withContext(ioContext) { controller.incomingPolicy(peer) }
    }

    fun closePeer() { _incomingPolicy.value = null; _detail.value = null; _snapshots.value = emptyList(); clearSnapshotText() }

    fun setBaseline(circle: SharingCircle, category: SharingCategory, granted: Boolean) =
        mutateReporting { controller.setBaseline(circle, category, granted) }

    fun setPeerRule(peer: PeerId, category: SharingCategory, effect: AccessEffect?) =
        mutateReporting { controller.setPeerRule(peer, category, effect) }

    fun setObjectRule(peer: PeerId, objectId: ObjectId, category: SharingCategory, effect: AccessEffect?) =
        mutateReporting { controller.setObjectRule(peer, objectId, category, effect) }

    /**
     * Adds an object exception. Returns false without writing when the id is
     * blank, so the field can keep what was typed next to the reason.
     */
    suspend fun addObjectRule(
        peer: PeerId,
        rawObjectId: String,
        category: SharingCategory,
        effect: AccessEffect,
    ): Boolean {
        val id = rawObjectId.trim()
        if (id.isEmpty()) {
            _objectRuleError.value = ObjectRuleError.EMPTY_ID
            return false
        }
        _objectRuleError.value = null
        val result = exclusively<SharingWriteResult?>(null) {
            withContext(ioContext) { controller.setObjectRule(peer, ObjectId(id), category, effect) }
        } ?: return false
        _writeError.value = (result as? SharingWriteResult.Failed)?.error
        reload().join()
        return result.isSuccess
    }

    fun removeObjectRule(peer: PeerId, objectId: ObjectId, category: SharingCategory) =
        mutateReporting { controller.setObjectRule(peer, objectId, category, null) }

    enum class ObjectRuleError { EMPTY_ID }

    private val _objectRuleError = MutableStateFlow<ObjectRuleError?>(null)
    val objectRuleError: StateFlow<ObjectRuleError?> = _objectRuleError.asStateFlow()

    fun clearObjectRuleError() { _objectRuleError.value = null }

    fun setPeerCircle(peer: PeerId, circle: SharingCircle, clearPersonalExceptions: Boolean = false) =
        mutateReporting { controller.setPeerCircle(peer, circle, clearPersonalExceptions) }

    private val _inviteError = MutableStateFlow<PeerIdParseError?>(null)
    val inviteError: StateFlow<PeerIdParseError?> = _inviteError.asStateFlow()

    /**
     * Invites whoever [raw] identifies — a typed `npub1…` or its hex.
     *
     * Nothing is written unless the input parses: an identity the signature
     * layer could never verify against would produce a relationship that can
     * never be accepted, so it is refused at the edge with a visible reason
     * rather than stored and discovered later.
     *
     * Returns whether the identity parsed, so the field can clear itself on
     * success and keep a rejected entry on screen next to the reason. The parse
     * is synchronous; only the append is not.
     */
    suspend fun invite(raw: String, circle: SharingCircle): Boolean =
        when (val parsed = SharingPeerIdParser.parse(raw)) {
            is PeerIdParseResult.Invalid -> {
                _inviteError.value = parsed.error
                _writeError.value = null
                false
            }
            is PeerIdParseResult.Valid -> {
                _inviteError.value = null
                // Awaited, so the field only clears once the ledger really has
                // the entry. A failed signature used to look like success.
                val result = exclusively<SharingWriteResult?>(null) {
                    withContext(ioContext) { controller.invite(parsed.peer, circle) }
                } ?: return false
                _writeError.value = (result as? SharingWriteResult.Failed)?.error
                reload().join()
                result.isSuccess
            }
        }

    fun clearInviteError() { _inviteError.value = null }

    private val _writeError = MutableStateFlow<SharingWriteError?>(null)

    /** Set when a write was refused — an external signer, for instance. */
    val writeError: StateFlow<SharingWriteError?> = _writeError.asStateFlow()

    fun clearWriteError() { _writeError.value = null }

    private fun mutateReporting(block: suspend () -> SharingWriteResult) = viewModelScope.launch {
        val result = exclusively<SharingWriteResult?>(null) {
            withContext(ioContext) { block() }
        } ?: return@launch
        _writeError.value = (result as? SharingWriteResult.Failed)?.error
        reload()
    }

    /**
     * Debug-only. A real acceptance is signed by the peer on their own device
     * and arrives over the transport, which is gated shut; this stands in for
     * that using a demo key the build actually holds.
     */
    suspend fun simulateAccept(peer: PeerId, categories: Set<SharingCategory>, device: DeviceId): Boolean {
        val ok = exclusively<Boolean?>(null) {
            withContext(ioContext) { controller.simulateAccept(peer, categories, device) }
        } ?: return false
        _writeError.value = if (ok) null else SharingWriteError.SIGNER_UNAVAILABLE
        reload().join()
        return ok
    }

    /** Debug-only, for the same reason as [simulateAccept]. */
    suspend fun simulateDecline(peer: PeerId): Boolean {
        val ok = exclusively<Boolean?>(null) {
            withContext(ioContext) { controller.simulateDecline(peer) }
        } ?: return false
        _writeError.value = if (ok) null else SharingWriteError.SIGNER_UNAVAILABLE
        reload().join()
        return ok
    }

    /** True when this build can stand in for [peer] — demo peers in debug only. */
    fun canSimulate(peer: PeerId): Boolean = controller.canSimulatePeer(peer)

    fun changeGrant(peer: PeerId, categories: Set<SharingCategory>) =
        mutateReporting { controller.changeGrant(peer, categories) }

    /**
     * Debug-only. Authorising a device is the peer's act, signed with the
     * peer's key; the owner has no way to do it and the release UI does not
     * offer it.
     */
    suspend fun simulateAuthorizeDevice(peer: PeerId, device: DeviceId): Boolean {
        val ok = exclusively<Boolean?>(null) {
            withContext(ioContext) { controller.simulateAuthorizeDevice(peer, device) }
        } ?: return false
        _writeError.value = if (ok) null else SharingWriteError.SIGNER_UNAVAILABLE
        reload().join()
        return ok
    }

    fun revokeDevice(peer: PeerId, device: DeviceId) =
        mutateReporting { controller.revokeDevice(peer, device) }

    fun revoke(peer: PeerId) = mutateReporting { controller.revoke(peer) }

    /**
     * Returns whether the purge ran. A failed one leaves the screen where it
     * is: navigating away would say "deleted" about data that is still there.
     */
    suspend fun purge(peer: PeerId): Boolean {
        val result = exclusively<SharingWriteResult?>(null) {
            withContext(ioContext) { controller.purge(peer) }
        } ?: return false
        _writeError.value = (result as? SharingWriteResult.Failed)?.error
        // Only a purge that actually happened closes the screen. A refused
        // signature leaves the person where they are, next to the reason.
        if (result.isSuccess) _detail.value = null
        reload().join()
        return result.isSuccess
    }

    /**
     * Replaces the recovery code on screen — but never while something is using
     * the old one.
     *
     * A backup is encrypted with the code that was showing when the export
     * started, and with an external signer that export sits waiting for a
     * prompt in another app. Rotating the code in that window left the file
     * sealed with the old code and the screen displaying the new one, with
     * nothing saying they differed: the person writes down what they can see,
     * and the file will not open with it. That is silent data loss discovered
     * only at a restore, which is the worst possible moment.
     *
     * So it takes the same turn as every other mutation. This is enforced here
     * rather than only by disabling the button, because a second tap, a
     * recomposition or any other caller reaches the view model directly.
     */
    fun newRecoveryCode() = viewModelScope.launch {
        val minted = exclusively<String?>(null) {
            withContext(ioContext) { controller.newRecoveryCode() }
        } ?: return@launch
        _recoveryCode.value = minted
        _recoveryMessage.value = null
    }

    /**
     * Takes this device back into sole authority, with the owner's root key.
     *
     * This used to call a controller entry point that appended a
     * `RestoreCompleted` per relationship and stopped: no root challenge, no
     * new authority generation, no re-enrolment, nothing fenced. So the one
     * screen action named "restore" was the only path in the feature that
     * performed none of what §11.8 says a restore is, while
     * [SharingController.recoverThisDevice] — which does all of it — was
     * reachable from nowhere.
     *
     * ## What this can and cannot prove
     *
     * With no backup file there is nothing to restore *from*, and the code is
     * one this process generated: `_recoveryCode` lives in memory and a lost
     * device's code cannot be known here, so it is not evidence of anything
     * historical. The credential that decides this is the **root signature**,
     * and the estate it establishes is the current one with this device as its
     * only authority — see §11.8. Recovering somebody's actual permissions
     * needs the file, where the code is checked cryptographically against the
     * envelope: [importBackupBytes].
     */
    fun restore(entered: String) = viewModelScope.launch {
        val expected = _recoveryCode.value
        if (expected == null) {
            _recoveryMessage.value = RecoveryMessage.WRONG_CODE
            return@launch
        }
        val outcome = exclusively<com.cruxcoach.android.sharing.SharingRecoveryOutcome?>(null) {
            withContext(ioContext) { controller.recoverThisDevice(entered, expected) }
        } ?: return@launch
        _recoveryMessage.value = messageFor(outcome)
        reload()
    }

    /**
     * The screen's version of why a recovery did not happen.
     *
     * A pure mapping over a closed set — no exception text and no free string
     * reaches a screen. `null` cannot occur for a non-applied outcome, and is
     * mapped to the least specific answer rather than to "wrong code", because
     * guessing wrong in *that* direction is what this replaced.
     */
    private fun messageFor(
        outcome: com.cruxcoach.android.sharing.SharingRecoveryOutcome,
    ): RecoveryMessage = when {
        outcome.applied -> RecoveryMessage.RESTORED
        else -> when (outcome.refusal) {
            com.cruxcoach.android.sharing.SharingRecoveryRefusal.WRONG_CODE ->
                RecoveryMessage.WRONG_CODE
            com.cruxcoach.android.sharing.SharingRecoveryRefusal.SIGNER_UNAVAILABLE ->
                RecoveryMessage.SIGNER_REFUSED
            com.cruxcoach.android.sharing.SharingRecoveryRefusal.PREVIEW_ONLY ->
                RecoveryMessage.PREVIEW_ONLY
            com.cruxcoach.android.sharing.SharingRecoveryRefusal.NO_DEVICE_IDENTITY,
            com.cruxcoach.android.sharing.SharingRecoveryRefusal.REJECTED,
            null,
            -> RecoveryMessage.REFUSED
        }
    }


    /**
     * Takes the estate back with the root key, destroying everything from
     * before.
     *
     * Uses the recovery code the person just generated or entered — a reset is
     * still a recovery, and still needs both credentials. Only the freshness
     * check is overridden, which is the whole point of it.
     */
    fun startSovereignReset() = viewModelScope.launch {
        val expected = _recoveryCode.value
        if (expected == null) {
            _recoveryMessage.value = RecoveryMessage.WRONG_CODE
            return@launch
        }
        val outcome = exclusively<com.cruxcoach.android.sharing.SharingRecoveryOutcome?>(null) {
            withContext(ioContext) {
                controller.recoverThisDevice(
                    entered = expected,
                    expected = expected,
                    sovereignReset = true,
                )
            }
        } ?: return@launch
        _recoveryMessage.value = messageFor(outcome)
        reload()
    }

    enum class BackupMessage { EXPORTED, RESTORED, NOTHING_TO_DO, FAILED }

    private val _backupMessage = MutableStateFlow<BackupMessage?>(null)
    val backupMessage: StateFlow<BackupMessage?> = _backupMessage.asStateFlow()

    private val _backupError = MutableStateFlow<SharingBackupError?>(null)
    val backupError: StateFlow<SharingBackupError?> = _backupError.asStateFlow()

    /**
     * Produces the encrypted backup bytes, or `null` with a visible reason.
     *
     * The recovery code is the one currently shown; without it there is
     * nothing to derive the file key from, and the code is never written into
     * the file.
     */
    suspend fun exportBackupBytes(): ByteArray? {
        val code = _recoveryCode.value
        if (code == null) {
            _backupMessage.value = BackupMessage.FAILED
            _backupError.value = SharingBackupError.INVALID_RECOVERY_CODE
            return null
        }
        val result = exclusively<SharingBackupWriteOutcome?>(null) {
            withContext(ioContext) { controller.exportBackup(code) }
        } ?: return null
        return when (result) {
            is SharingBackupWriteOutcome.Written -> {
                _backupMessage.value = BackupMessage.EXPORTED
                _backupError.value = null
                result.bytes
            }
            is SharingBackupWriteOutcome.Failed -> {
                _backupMessage.value = BackupMessage.FAILED
                _backupError.value = result.error
                null
            }
        }
    }

    /** Restores from [bytes] using the code the user typed. */
    /**
     * Restores from a file the person picked.
     *
     * Everything but the file, the code and an explicit reset comes from
     * inside: the generation from the payload's own signed manifest, the device
     * key from this install's identity. A screen that could supply either could
     * call any file current, or enrol a key this install does not hold.
     */
    suspend fun importBackupBytes(bytes: ByteArray, enteredCode: String, sovereignReset: Boolean = false) {
        val outcome = exclusively<com.cruxcoach.android.sharing.SharingRecoveryOutcome?>(null) {
            withContext(ioContext) { controller.importRecovery(bytes, enteredCode, sovereignReset) }
        } ?: return
        val previewed = !outcome.applied &&
            outcome.decision == com.cruxcoach.domain.sharing.RecoveryDecision.PREVIEW_ONLY
        // Held only while the offer is on screen. A preview leaves the person
        // on a dead end otherwise: permissions visible, nothing changeable, and
        // the one way forward on an unrelated screen with no hint that it is
        // the way forward.
        // Whatever offer was standing is over, and its file goes with it. This
        // used to overwrite or null the field and leave the old array in the
        // heap — a second import, or any import that resolved to something
        // other than a preview, silently kept a whole permission history alive.
        val superseded = pendingRetry
        pendingRetry = if (previewed) PendingRetry(bytes, enteredCode) else null
        discard(superseded)
        _previewRetryAvailable.value = previewed
        _backupMessage.value = when {
            outcome.applied -> BackupMessage.RESTORED
            previewed -> BackupMessage.NOTHING_TO_DO
            else -> BackupMessage.FAILED
        }
        // A failure used to leave this null, and the label for "no error" is
        // *the file is damaged* — so declining the prompt told the person their
        // backup was corrupt, about the one artefact they cannot replace.
        _backupError.value = when (outcome.refusal) {
            com.cruxcoach.android.sharing.SharingRecoveryRefusal.SIGNER_UNAVAILABLE ->
                SharingBackupError.SIGNER_UNAVAILABLE
            com.cruxcoach.android.sharing.SharingRecoveryRefusal.WRONG_CODE ->
                SharingBackupError.WRONG_RECOVERY_CODE
            else -> null
        }
        reload().join()
    }

    /**
     * The file and code a preview was made from, kept to retry with.
     *
     * **In process memory only.** Never a SavedStateHandle, never a file: a
     * recovery code plus a backup is a complete takeover of somebody's
     * permissions, and anything durable is one backup-agent or one crash-report
     * away from being somewhere else. It is cleared the moment the offer is
     * resolved, and a process death simply loses it — the person picks the file
     * again, which is the safe direction to fail in.
     *
     * The **bytes** are wiped on every way out, and "every" has been checked
     * against the list rather than asserted: cancel, the screen going away
     * ([onCleared]), a confirm that succeeds, a confirm that throws, a confirm
     * cancelled before it ever ran, and any later import that supersedes or
     * clears a standing offer. All of them go through [discard], which is also
     * what keeps a confirm from wiping the array it has just re-established.
     *
     * The **code** is a `String` and is not wiped, because it cannot be:
     * strings are immutable and interned, and `fill(0)` on the array behind one
     * is not something Kotlin offers. Saying it is cleared would be a claim
     * this cannot keep, so it says instead that only the bytes are.
     */
    private class PendingRetry(val bytes: ByteArray, val code: String)

    private var pendingRetry: PendingRetry? = null

    private val _previewRetryAvailable = MutableStateFlow(false)

    /** True while a read-only preview is offering the reset that gets past it. */
    val previewRetryAvailable: StateFlow<Boolean> = _previewRetryAvailable.asStateFlow()

    /**
     * The screen went away with a preview still open.
     *
     * Confirm and cancel both wipe the file; navigating back did not, and that
     * is the ordinary way a preview ends.
     */
    override fun onCleared() {
        cancelPreviewRetry()
        super.onCleared()
    }

    /** Withdraws the offer and forgets the file and code. Writes nothing. */
    fun cancelPreviewRetry() {
        val withdrawn = pendingRetry
        pendingRetry = null
        _previewRetryAvailable.value = false
        discard(withdrawn)
    }

    /**
     * Wipes a retry's file, unless that file is the one still being held.
     *
     * The exception matters: [confirmPreviewRetry] hands the *same* array to
     * [importBackupBytes], so a wipe that did not check identity could zero the
     * bytes of an offer that had just been re-established, and the retry would
     * then decrypt nothing.
     */
    private fun discard(retry: PendingRetry?) {
        if (retry == null) return
        if (retry === pendingRetry || retry.bytes === pendingRetry?.bytes) return
        retry.bytes.fill(0)
    }

    /**
     * Applies the sovereign reset the preview offered, once.
     *
     * The attempt is taken before the work starts, so a second tap — or a
     * recomposition — finds nothing to do. Running it twice would mint a second
     * generation and fence the device the first one just enrolled.
     */
    fun confirmPreviewRetry(): Job? {
        // Taken *before* the coroutine starts. Clearing it inside would leave a
        // window where a second tap finds the attempt still there and queues a
        // second reset — which would mint another generation and fence the
        // device the first one had just enrolled.
        val attempt = pendingRetry ?: return null
        pendingRetry = null
        _previewRetryAvailable.value = false
        return viewModelScope.launch {
            importBackupBytes(attempt.bytes, attempt.code, sovereignReset = true)
        }.also { job ->
            // Not a line after the call, and not a `finally` inside the
            // coroutine either. The attempt is taken out of the field before
            // the coroutine starts, so if it is cancelled before its first
            // resumption the body never runs at all — no `finally` fires, and
            // `onCleared` can no longer find the retry to wipe. Completion
            // fires in every one of those cases.
            job.invokeOnCompletion { discard(attempt) }
        }
    }

    @VisibleForTesting
    internal fun pendingRetryForTest(): Any? = pendingRetry

    /**
     * Puts a retry in place without going through a real preview.
     *
     * Only for asserting what happens to the bytes afterwards; producing a
     * genuine preview needs a signed backup whose freshness cannot be shown,
     * which says nothing extra about the wiping.
     */
    @VisibleForTesting
    internal fun seedPendingRetryForTest(bytes: ByteArray, code: String) {
        pendingRetry = PendingRetry(bytes, code)
        _previewRetryAvailable.value = true
    }

    fun clearBackupMessage() { _backupMessage.value = null; _backupError.value = null }

    /**
     * The one action a fresh install can take. Everything else is closed.
     *
     * Reports, like every other mutation. These three went through [mutate],
     * which takes a `suspend () -> Unit` — so each of them returned a
     * `SharingWriteResult` that was discarded at the call site. A refused
     * signer, a recovery preview and a device that may not administer all came
     * out looking exactly like success on the device screen, which is the one
     * screen where "did that work?" cannot be answered by looking at anything
     * else.
     */
    fun enrolGenesisDevice() = mutateReporting { controller.enrolGenesisDevice() }

    fun revokeOwnDevice(device: AuthorityDeviceId) =
        mutateReporting { controller.revokeOwnDevice(device) }

    fun changeDeviceRole(device: AuthorityDeviceId, role: DeviceRole) =
        mutateReporting { controller.changeDeviceRole(device, role) }

    /**
     * Enrols a device this build generated a key for.
     *
     * Debug only, and gated on [demoAvailable] rather than on the caller
     * remembering: a release build has no way to mint a device key it does not
     * already hold, so offering the action there would be a button that cannot
     * work.
     */
    fun enrolDemoDevice() {
        if (!demoAvailable) return
        mutate { SharingDemoData.enrolDemoDevice(controller) }
    }

    /** Debug builds only. A release build never reaches this. */
    fun seedDemoData() {
        if (!demoAvailable) return
        mutate { SharingDemoData.seed(controller) }
    }
}
