package com.cruxcoach.android.mesh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One authenticated frame as the transport hands it up, still opaque bytes. */
class MeshInboundFrame(val realmId: String, val sender: String, val payload: ByteArray)

/**
 * Everything the realm manager needs from the radio, and nothing else.
 *
 * Keeping this narrow is what lets the whole realm policy, routing and
 * lifecycle be exercised on a plain JVM without a native node or a radio.
 */
internal interface MeshTransportPort {
    /** This node's authenticated identity, blank while no realm runs. */
    val localPeerId: String

    /** Directly authenticated peers of the realm the transport currently carries. */
    val authenticatedPeers: StateFlow<Set<String>>

    /** Authenticated application frames, tagged with the realm they arrived on. */
    val inbound: Flow<MeshInboundFrame>

    /** Keeps the process-wide runtime alive for a logical owner. Idempotent. */
    fun acquireRuntime(owner: MeshOwner)

    fun releaseRuntime(owner: MeshOwner)

    /** Makes [realmId] the realm the radio carries. False when radio or platform refuse. */
    fun activate(realmId: MeshRealmId, metadata: MeshRealmMetadata): Boolean

    fun end(realmId: MeshRealmId)

    /** Rebuilds the transport generation of [realmId] without changing leases. */
    fun recycle(realmId: MeshRealmId, reason: String): Boolean

    /** Lets the transport leave join mode once membership is canonical. */
    fun settleMembership(realmId: MeshRealmId)

    fun send(peer: String, payload: ByteArray): Boolean
}
