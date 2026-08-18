package com.cruxcoach.android.mesh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The authenticated transport scope a frame belongs to.
 *
 * A realm is a physical fact — "these radios share one authenticated mesh" —
 * and is deliberately not a feature: BoardCell and Competitions ride the same
 * realm and are separated by [MeshProtocols], never by realm identity.
 */
@JvmInline
value class MeshRealmId(val value: String) {
    init { require(value.isNotBlank()) { "a mesh realm id must not be blank" } }
    override fun toString(): String = value
}

/**
 * A logical feature lease on a realm.
 *
 * Owners are reference counted and are never conflated with realms. Several
 * owners may share one realm; switching realms requires explicit release.
 */
@JvmInline
value class MeshOwner(val value: String) {
    init { require(value.isNotBlank()) { "a mesh owner must not be blank" } }
    override fun toString(): String = value
}

/** The process-wide catalogue of lease identities, so owner strings stay unique and greppable. */
object MeshOwners {
    val BOARD_CELL = MeshOwner("board-cell")
    val NEARBY_BOARD_CELL = MeshOwner("nearby-board-cell")

    /** A GATT-admitted participant that has the cell's scope but no board of its own. */
    val PARTICIPANT = MeshOwner("board-cell-participant")
    val HANDOVER = MeshOwner("board-cell-handover")
    val SESSION = MeshOwner("session")
    fun competition(compId: String): MeshOwner = MeshOwner("competition:$compId")
}

/**
 * Every application protocol this build speaks on a mesh realm.
 *
 * The catalogue is closed on purpose: a frame tagged with anything else is
 * dropped at the router instead of being offered to whichever feature happens
 * to decode it successfully.
 */
object MeshProtocols {
    const val BOARD_CELL = "boardcell/v1"
    const val COMPETITION = "competition/v1"
    const val MAX_LENGTH = 32

    val known: Set<String> = setOf(BOARD_CELL, COMPETITION)

    fun isKnown(protocol: String): Boolean = protocol in known

    fun isWellFormed(protocol: String): Boolean = protocol.length in 1..MAX_LENGTH &&
        protocol.all { it in 'a'..'z' || it in '0'..'9' || it == '/' || it == '.' || it == '-' }
}

/** What a realm physically is. Kept separate from the protocols spoken inside it. */
enum class MeshRealmKind { BOARD_CELL, COMPETITION }

/**
 * The realm's own description, shared by every owner of that realm.
 *
 * It describes the scope, not the acquiring feature: a competition joining the
 * board's realm passes the board's metadata and separates itself by protocol.
 */
data class MeshRealmMetadata(
    val kind: MeshRealmKind,
    val boardCellId: String,
    val displayName: String? = null,
) {
    init { require(boardCellId.isNotBlank()) { "a mesh realm must name its board cell" } }

    /** Two owners may share a realm only when they mean the same physical scope. */
    fun isCompatibleWith(other: MeshRealmMetadata): Boolean =
        kind == other.kind && boardCellId == other.boardCellId

    /** A later owner may contribute a display name, never redefine the scope. */
    fun mergedWith(other: MeshRealmMetadata): MeshRealmMetadata =
        copy(displayName = other.displayName ?: displayName)
}

/**
 * One authenticated application frame, already resolved to realm and protocol.
 *
 * [equals]/[hashCode] are written out because a generated data-class equality
 * would compare [payload] by identity, and two envelopes carrying the same
 * bytes are the same frame.
 */
data class MeshEnvelope(
    val realmId: MeshRealmId,
    val sender: String,
    val protocol: String,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other || (other is MeshEnvelope &&
        realmId == other.realmId && sender == other.sender && protocol == other.protocol &&
        payload.contentEquals(other.payload))

    override fun hashCode(): Int {
        var result = realmId.hashCode()
        result = 31 * result + sender.hashCode()
        result = 31 * result + protocol.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "MeshEnvelope(realm=$realmId, protocol=$protocol, bytes=${payload.size})"
}

/** Why an acquisition could not be served. Every value is an explicit, tested policy outcome. */
enum class MeshRealmDenial {
    /** Another owner still holds a different realm; the caller must release it first. */
    REALM_CONFLICT,

    /** The realm is live but describes a different physical scope than requested. */
    METADATA_CONFLICT,

    /** Radio, permissions or platform refused to carry the realm at all. */
    TRANSPORT_UNAVAILABLE,
}

class MeshRealmUnavailableException(
    val denial: MeshRealmDenial,
    val requested: MeshRealmId,
    val active: MeshRealmId?,
) : IllegalStateException(
    "mesh realm $requested unavailable: $denial" + (active?.let { " (active realm $it)" } ?: ""),
)

/**
 * One owner's live view of one realm.
 *
 * Sends are strictly realm specific and refuse as soon as the realm is no
 * longer the live one; [incoming] only ever carries envelopes of this realm
 * and of the protocols this session registered.
 */
interface MeshRealmSession {
    val realmId: MeshRealmId
    val owner: MeshOwner

    /** This node's authenticated identity inside the realm, blank while transport is down. */
    val localPeerId: String

    /** Directly authenticated peers of this realm; empty once the session is closed. */
    val authenticatedPeers: StateFlow<Set<String>>

    /** Every envelope routed to this session, across all protocols it registered. */
    val incoming: Flow<MeshEnvelope>

    /** Registers this session as a handler for [protocol] and returns its filtered view. */
    fun subscribe(protocol: String): Flow<MeshEnvelope>

    fun send(peer: String, protocol: String, payload: ByteArray): Boolean

    /** Rebuilds this realm's transport generation without giving up the lease. */
    fun recycleTransport(reason: String): Boolean

    /** Reports that canonical membership settled, so transport can enter steady state. */
    fun settleMembership()

    /** Releases exactly one reference of this owner on this realm. */
    fun close()
}

/**
 * Reference counted, realm-scoped access to the single authenticated mesh
 * transport this process can run.
 */
interface MeshRealmManager {
    /** The one realm the transport currently carries, or null while idle. */
    val activeRealm: StateFlow<MeshRealmId?>

    /**
     * Takes (or shares) [realmId] for [owner].
     *
     * @throws MeshRealmUnavailableException when the policy or the transport refuses.
     */
    suspend fun acquire(
        owner: MeshOwner,
        realmId: MeshRealmId,
        metadata: MeshRealmMetadata,
    ): MeshRealmSession

    /** Drops one reference of [owner] on [realmId]; unknown leases are ignored. */
    fun release(owner: MeshOwner, realmId: MeshRealmId)

    /** Drops every reference [owner] holds, on every realm. */
    fun releaseAll(owner: MeshOwner)

    /** The live session of [owner] on the active realm, if it still holds one. */
    fun session(owner: MeshOwner): MeshRealmSession?
}

/** For call sites that treat an unavailable realm as an ordinary fallback, not an error. */
suspend fun MeshRealmManager.acquireOrNull(
    owner: MeshOwner,
    realmId: MeshRealmId,
    metadata: MeshRealmMetadata,
): MeshRealmSession? = try {
    acquire(owner, realmId, metadata)
} catch (denied: MeshRealmUnavailableException) {
    MeshDebugLog.warning(
        "realm", "acquire_denied", "owner" to owner.value, "realm" to MeshDebugLog.id(realmId.value),
        "denial" to denied.denial, "active" to MeshDebugLog.id(denied.active?.value),
    )
    null
}
