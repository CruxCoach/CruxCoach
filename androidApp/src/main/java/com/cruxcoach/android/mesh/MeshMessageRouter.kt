package com.cruxcoach.android.mesh

/** Why a frame was or was not delivered. Every non-delivery is logged with the same fields. */
internal enum class MeshRouteResult {
    DELIVERED,

    /** Not a mesh envelope of this version at all. */
    UNDECODABLE,

    /** The envelope claims a different realm than the transport received it on. */
    TRANSPORT_REALM_MISMATCH,

    /** A well-formed envelope of a realm this node is not currently carrying. */
    FOREIGN_REALM,

    /** A protocol outside this build's closed catalogue. Never offered to a decoder. */
    UNKNOWN_PROTOCOL,

    /** A known protocol nobody has registered for in this realm. */
    NO_HANDLER,
}

/**
 * Dispatch by registered (realm, protocol) handler instead of by "whichever
 * feature manages to decode the bytes".
 *
 * The transport stays feature agnostic: it hands up authenticated frames, and
 * routing decides — with the realm the frame physically arrived on, the realm
 * the envelope claims and the live realm all having to agree.
 */
internal class MeshMessageRouter {
    fun interface Handler {
        suspend fun deliver(envelope: MeshEnvelope)
    }

    private data class Key(val session: Any, val protocol: String)
    private data class Registration(val realmId: MeshRealmId, val handler: Handler)

    private val registrations = linkedMapOf<Key, Registration>()

    /** Registers [session] as a handler; unknown protocols are refused at registration. */
    @Synchronized
    fun register(
        session: Any,
        realmId: MeshRealmId,
        protocol: String,
        handler: Handler,
    ): Boolean {
        if (!MeshProtocols.isKnown(protocol)) {
            MeshDebugLog.warning("router", "register_rejected", "protocol" to protocol,
                "realm" to MeshDebugLog.id(realmId.value), "reason" to "protocol outside catalogue")
            return false
        }
        registrations[Key(session, protocol)] = Registration(realmId, handler)
        MeshDebugLog.event("router", "handler_registered", "protocol" to protocol,
            "realm" to MeshDebugLog.id(realmId.value), "handlers" to registrations.size)
        return true
    }

    @Synchronized
    fun unregister(session: Any) {
        val removed = registrations.keys.filter { it.session === session }
        removed.forEach(registrations::remove)
        if (removed.isNotEmpty()) MeshDebugLog.event("router", "handler_unregistered",
            "protocols" to removed.joinToString { it.protocol }, "handlers" to registrations.size)
    }

    @Synchronized
    fun protocols(realmId: MeshRealmId): Set<String> = registrations.entries
        .filter { it.value.realmId == realmId }.mapTo(linkedSetOf()) { it.key.protocol }

    @Synchronized
    private fun handlersFor(realmId: MeshRealmId, protocol: String): List<Handler> =
        registrations.entries
            .filter { it.key.protocol == protocol && it.value.realmId == realmId }
            .map { it.value.handler }

    suspend fun route(activeRealm: MeshRealmId?, frame: MeshInboundFrame): MeshRouteResult {
        val decoded = MeshWireCodec.decode(frame.payload)
            ?: return drop(MeshRouteResult.UNDECODABLE, frame, null, null, activeRealm)
        if (decoded.realmId.value != frame.realmId) {
            return drop(MeshRouteResult.TRANSPORT_REALM_MISMATCH, frame, decoded.realmId,
                decoded.protocol, activeRealm)
        }
        if (activeRealm == null || activeRealm != decoded.realmId) {
            return drop(MeshRouteResult.FOREIGN_REALM, frame, decoded.realmId, decoded.protocol,
                activeRealm)
        }
        if (!MeshProtocols.isKnown(decoded.protocol)) {
            return drop(MeshRouteResult.UNKNOWN_PROTOCOL, frame, decoded.realmId, decoded.protocol,
                activeRealm)
        }
        val handlers = handlersFor(decoded.realmId, decoded.protocol)
        if (handlers.isEmpty()) {
            return drop(MeshRouteResult.NO_HANDLER, frame, decoded.realmId, decoded.protocol,
                activeRealm)
        }
        val envelope = MeshEnvelope(decoded.realmId, frame.sender, decoded.protocol, decoded.payload)
        handlers.forEach { it.deliver(envelope) }
        MeshDebugLog.event("router", "frame_routed", "sender" to MeshDebugLog.id(frame.sender),
            "realm" to MeshDebugLog.id(decoded.realmId.value), "protocol" to decoded.protocol,
            "handlers" to handlers.size, "bytes" to decoded.payload.size)
        return MeshRouteResult.DELIVERED
    }

    private fun drop(
        result: MeshRouteResult,
        frame: MeshInboundFrame,
        envelopeRealm: MeshRealmId?,
        protocol: String?,
        activeRealm: MeshRealmId?,
    ): MeshRouteResult {
        MeshDebugLog.warning("router", "frame_dropped", "reason" to result,
            "sender" to MeshDebugLog.id(frame.sender),
            "transportRealm" to MeshDebugLog.id(frame.realmId),
            "envelopeRealm" to MeshDebugLog.id(envelopeRealm?.value),
            "activeRealm" to MeshDebugLog.id(activeRealm?.value),
            "protocol" to protocol, "bytes" to frame.payload.size)
        return result
    }
}
