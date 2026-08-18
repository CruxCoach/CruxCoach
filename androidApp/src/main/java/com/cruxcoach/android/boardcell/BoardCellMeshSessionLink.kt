package com.cruxcoach.android.boardcell

import com.cruxcoach.android.fips.FipsDebugLog
import com.cruxcoach.android.mesh.MeshEnvelope
import com.cruxcoach.android.mesh.MeshProtocols
import com.cruxcoach.android.mesh.MeshRealmSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Binds the BoardCell wire to whichever realm session BoardCell currently
 * holds, and to the `boardcell/v1` protocol only.
 *
 * The wire keeps its narrow [AuthenticatedMeshLink] contract; realm changes
 * are a rebind here instead of a state check in every send path. With no
 * session bound, the link is inert: nothing sends, nobody is authenticated and
 * there is no active realm — which is exactly what a torn-down cell means.
 */
class BoardCellMeshSessionLink : AuthenticatedMeshLink {
    private val bound = MutableStateFlow<MeshRealmSession?>(null)

    val session: MeshRealmSession? get() = bound.value

    /** Board cell frames of the bound realm, following every rebind. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val incoming: Flow<MeshEnvelope> = bound.flatMapLatest { session ->
        session?.subscribe(MeshProtocols.BOARD_CELL) ?: emptyFlow()
    }

    fun bind(value: MeshRealmSession?) {
        if (bound.value === value) return
        FipsDebugLog.event("boardcell", "mesh_link_bound",
            "realm" to FipsDebugLog.id(value?.realmId?.value),
            "owner" to (value?.owner?.value ?: "-"))
        bound.value = value
    }

    override val localNpub: String get() = bound.value?.localPeerId.orEmpty()

    override fun send(authenticatedPeerNpub: String, payload: ByteArray): Boolean =
        bound.value?.send(authenticatedPeerNpub, MeshProtocols.BOARD_CELL, payload) == true

    override fun directAuthenticatedPeers(): Set<String> =
        bound.value?.authenticatedPeers?.value.orEmpty()

    override fun activeRealmId(): String? = bound.value?.realmId?.value
}
