package com.cruxcoach.android.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose

/**
 * The realm manager itself: leases, routing and lifecycle, with the radio
 * behind [MeshTransportPort].
 *
 * Everything feature specific lives above this class (which protocol, which
 * payload) and everything radio specific below it, so neither side needs to
 * know about the other.
 */
internal class DefaultMeshRealmManager(
    private val port: MeshTransportPort,
    scope: CoroutineScope,
) : MeshRealmManager {
    private val lock = Any()
    private val ledger = MeshRealmLedger()
    private val router = MeshMessageRouter()
    private val sessions = linkedMapOf<MeshOwner, RealmSession>()
    private val _activeRealm = MutableStateFlow<MeshRealmId?>(null)
    override val activeRealm: StateFlow<MeshRealmId?> = _activeRealm.asStateFlow()

    init {
        scope.launch {
            port.inbound.collect { frame ->
                // A single failing handler must never retire the process-wide
                // inbound pipeline; that would silently deafen every realm.
                try {
                    router.route(ledger.activeRealm(), frame)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    MeshDebugLog.warning("router", "route_failed",
                        "sender" to MeshDebugLog.id(frame.sender),
                        "transportRealm" to MeshDebugLog.id(frame.realmId),
                        "error" to (failure.message ?: failure.javaClass.simpleName))
                }
            }
        }
        scope.launch {
            port.authenticatedPeers.collect { peers ->
                synchronized(lock) { sessions.values.toList() }.forEach { it.publishPeers(peers) }
            }
        }
    }

    override suspend fun acquire(
        owner: MeshOwner,
        realmId: MeshRealmId,
        metadata: MeshRealmMetadata,
    ): MeshRealmSession {
        val session = synchronized(lock) {
            when (val outcome = ledger.acquire(owner, realmId, metadata)) {
                is MeshAcquireOutcome.Denied -> {
                    MeshDebugLog.warning("realm", "acquire_denied", "owner" to owner.value,
                        "realm" to MeshDebugLog.id(realmId.value), "denial" to outcome.denial,
                        "active" to MeshDebugLog.id(outcome.active?.value))
                    throw MeshRealmUnavailableException(outcome.denial, realmId, outcome.active)
                }
                is MeshAcquireOutcome.Joined -> {
                    port.acquireRuntime(owner)
                    MeshDebugLog.event("realm", "lease_joined", "owner" to owner.value,
                        "realm" to MeshDebugLog.id(realmId.value), "references" to outcome.references,
                        "owners" to ledger.owners().size)
                    sessions.getOrPut(owner) { RealmSession(realmId, owner) }
                }
                is MeshAcquireOutcome.Activated -> activateLocked(owner, realmId, metadata)
            }
        }
        session.publishPeers(port.authenticatedPeers.value)
        return session
    }

    /** Must hold [lock]. Throws with the lease rolled back when the radio refuses. */
    private fun activateLocked(
        owner: MeshOwner,
        realmId: MeshRealmId,
        metadata: MeshRealmMetadata,
    ): RealmSession {
        // The runtime only starts for a registered logical owner, so the lease
        // has to exist before activation is even attempted.
        port.acquireRuntime(owner)
        val activated = runCatching { port.activate(realmId, metadata) }.getOrDefault(false)
        if (!activated) {
            ledger.rollback(owner, realmId)
            _activeRealm.value = ledger.activeRealm()
            port.releaseRuntime(owner)
            MeshDebugLog.warning("realm", "activate_failed", "owner" to owner.value,
                "realm" to MeshDebugLog.id(realmId.value), "kind" to metadata.kind)
            throw MeshRealmUnavailableException(MeshRealmDenial.TRANSPORT_UNAVAILABLE, realmId,
                ledger.activeRealm())
        }
        _activeRealm.value = realmId
        MeshDebugLog.event("realm", "activated",
            "owner" to owner.value, "realm" to MeshDebugLog.id(realmId.value),
            "kind" to metadata.kind, "cell" to MeshDebugLog.id(metadata.boardCellId))
        return RealmSession(realmId, owner).also { sessions[owner] = it }
    }

    override fun release(owner: MeshOwner, realmId: MeshRealmId) {
        applyRelease(owner, ledgerOutcome = { ledger.release(owner, realmId) },
            requested = realmId, reason = "released")
    }

    override fun releaseAll(owner: MeshOwner) {
        applyRelease(owner, ledgerOutcome = { ledger.releaseAll(owner) },
            requested = null, reason = "released all")
    }

    override fun session(owner: MeshOwner): MeshRealmSession? = synchronized(lock) {
        sessions[owner]?.takeIf { it.isLive }
    }

    private fun applyRelease(
        owner: MeshOwner,
        ledgerOutcome: () -> MeshReleaseOutcome,
        requested: MeshRealmId?,
        reason: String,
    ) {
        synchronized(lock) {
            when (val outcome = ledgerOutcome()) {
                is MeshReleaseOutcome.Unknown -> MeshDebugLog.event("realm", "release_ignored",
                    "owner" to owner.value, "realm" to MeshDebugLog.id(requested?.value),
                    "active" to MeshDebugLog.id(ledger.activeRealm()?.value))
                is MeshReleaseOutcome.Retained -> MeshDebugLog.event("realm", "lease_retained",
                    "owner" to owner.value, "realm" to MeshDebugLog.id(requested?.value),
                    "references" to outcome.references)
                is MeshReleaseOutcome.OwnerReleased -> {
                    retireLocked(owner, reason)
                    port.releaseRuntime(owner)
                    MeshDebugLog.event("realm", "owner_released", "owner" to owner.value,
                        "realm" to MeshDebugLog.id(outcome.realmId.value),
                        "remainingOwners" to ledger.owners().size)
                }
                is MeshReleaseOutcome.Deactivated -> {
                    retireLocked(owner, reason)
                    port.releaseRuntime(owner)
                    port.end(outcome.realmId)
                    _activeRealm.value = null
                    MeshDebugLog.event("realm", "deactivated", "owner" to owner.value,
                        "realm" to MeshDebugLog.id(outcome.realmId.value))
                }
            }
        }
    }

    /** Must hold [lock]. */
    private fun retireLocked(owner: MeshOwner, reason: String) {
        val session = sessions.remove(owner) ?: return
        session.retire(reason)
        router.unregister(session)
    }

    private inner class RealmSession(
        override val realmId: MeshRealmId,
        override val owner: MeshOwner,
    ) : MeshRealmSession {
        private val inbox = MutableSharedFlow<MeshEnvelope>(extraBufferCapacity = 64)
        private val peers = MutableStateFlow<Set<String>>(emptySet())

        @Volatile
        private var live = true

        val isLive: Boolean get() = live

        override val localPeerId: String get() = if (live) port.localPeerId else ""
        override val authenticatedPeers: StateFlow<Set<String>> = peers.asStateFlow()
        override val incoming: Flow<MeshEnvelope> = inbox.asSharedFlow()

        override fun subscribe(protocol: String): Flow<MeshEnvelope> = callbackFlow {
            if (!live) {
                close()
                return@callbackFlow
            }
            val handler = object : MeshMessageRouter.Handler {
                override suspend fun deliver(envelope: MeshEnvelope) {
                    inbox.emit(envelope)
                    trySend(envelope)
                }
            }
            if (!router.register(this@RealmSession, realmId, protocol, handler)) {
                close()
                return@callbackFlow
            }
            awaitClose { router.unregister(this@RealmSession, protocol, handler) }
        }

        override fun send(peer: String, protocol: String, payload: ByteArray): Boolean {
            val blocked = when {
                !live -> "session closed"
                peer.isBlank() -> "no peer"
                !MeshProtocols.isKnown(protocol) -> "protocol outside catalogue"
                ledger.activeRealm() != realmId -> "realm no longer active"
                else -> null
            }
            if (blocked != null) {
                MeshDebugLog.warning("realm", "send_blocked", "owner" to owner.value,
                    "realm" to MeshDebugLog.id(realmId.value), "protocol" to protocol,
                    "peer" to MeshDebugLog.id(peer), "bytes" to payload.size, "reason" to blocked)
                return false
            }
            return port.send(peer, MeshWireCodec.encode(realmId, protocol, payload))
        }

        override fun recycleTransport(reason: String): Boolean =
            live && ledger.activeRealm() == realmId && port.recycle(realmId, reason)

        override fun settleMembership() {
            if (live && ledger.activeRealm() == realmId) port.settleMembership(realmId)
        }

        override fun close() = release(owner, realmId)

        fun publishPeers(value: Set<String>) {
            if (live) peers.value = value
        }

        fun retire(reason: String) {
            live = false
            peers.value = emptySet()
            MeshDebugLog.event("realm", "session_retired", "owner" to owner.value,
                "realm" to MeshDebugLog.id(realmId.value), "reason" to reason)
        }
    }
}
