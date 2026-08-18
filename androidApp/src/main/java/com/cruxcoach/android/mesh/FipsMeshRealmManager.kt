package com.cruxcoach.android.mesh

import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.fips.FipsRealmContext
import com.cruxcoach.android.fips.FipsRealmKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** Binds the realm manager to the native FIPS runtime. Holds no policy of its own. */
internal class FipsMeshTransportPort(private val runtime: FipsMeshRuntime) : MeshTransportPort {
    override val localPeerId: String get() = runtime.localNpub
    override val authenticatedPeers: StateFlow<Set<String>> get() = runtime.directAuthenticatedPeers
    override val inbound: Flow<MeshInboundFrame> = runtime.messages
        .map { MeshInboundFrame(it.realmId, it.senderNpub, it.payload) }

    override fun acquireRuntime(owner: MeshOwner) = runtime.acquire(owner.value)

    override fun releaseRuntime(owner: MeshOwner) = runtime.release(owner.value)

    override fun activate(realmId: MeshRealmId, metadata: MeshRealmMetadata): Boolean =
        runtime.activateRealm(
            FipsRealmContext(
                realmId = realmId.value,
                boardCellId = metadata.boardCellId,
                kind = when (metadata.kind) {
                    MeshRealmKind.BOARD_CELL -> FipsRealmKind.BOARD_CELL
                    MeshRealmKind.COMPETITION -> FipsRealmKind.COMPETITION
                },
                meshName = metadata.displayName,
            ),
        )

    override fun end(realmId: MeshRealmId) = runtime.endRealm(realmId.value)

    override fun recycle(realmId: MeshRealmId, reason: String): Boolean =
        runtime.activeRealmId() == realmId.value && runtime.recycleIdleMeshTransport(reason)

    override fun settleMembership(realmId: MeshRealmId) = runtime.settleActiveMembership(realmId.value)

    override fun send(peer: String, payload: ByteArray): Boolean = runtime.send(peer, payload)
}

/**
 * The process-wide realm manager.
 *
 * Every feature talks to this; only this talks to [FipsMeshRuntime] about
 * realms, leases and application frames.
 */
@Singleton
class FipsMeshRealmManager @Inject constructor(runtime: FipsMeshRuntime) : MeshRealmManager {
    private val delegate: MeshRealmManager = DefaultMeshRealmManager(
        FipsMeshTransportPort(runtime),
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    override val activeRealm: StateFlow<MeshRealmId?> get() = delegate.activeRealm

    override suspend fun acquire(
        owner: MeshOwner,
        realmId: MeshRealmId,
        metadata: MeshRealmMetadata,
    ): MeshRealmSession = delegate.acquire(owner, realmId, metadata)

    override fun release(owner: MeshOwner, realmId: MeshRealmId) = delegate.release(owner, realmId)

    override fun releaseAll(owner: MeshOwner) = delegate.releaseAll(owner)

    override fun session(owner: MeshOwner): MeshRealmSession? = delegate.session(owner)
}
