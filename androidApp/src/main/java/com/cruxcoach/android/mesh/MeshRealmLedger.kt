package com.cruxcoach.android.mesh

/** What the transport has to do for an acquisition, decided purely from lease state. */
internal sealed interface MeshAcquireOutcome {
    data class Activated(val realmId: MeshRealmId, val metadata: MeshRealmMetadata) : MeshAcquireOutcome

    data class Joined(
        val realmId: MeshRealmId,
        val metadata: MeshRealmMetadata,
        val references: Int,
    ) : MeshAcquireOutcome

    data class Denied(val denial: MeshRealmDenial, val active: MeshRealmId?) : MeshAcquireOutcome
}

internal sealed interface MeshReleaseOutcome {
    /** The owner still holds references on the realm. */
    data class Retained(val references: Int) : MeshReleaseOutcome

    /** The owner gave up its last reference; other owners keep the realm alive. */
    data class OwnerReleased(val realmId: MeshRealmId) : MeshReleaseOutcome

    /** The last reference of the last owner: the transport must end the realm. */
    data class Deactivated(val realmId: MeshRealmId) : MeshReleaseOutcome

    /** Nothing to release. A stale or duplicated release never touches another lease. */
    data object Unknown : MeshReleaseOutcome
}

/**
 * The complete, transport-free policy for concurrent and incompatible realms.
 *
 * The native node carries exactly one authenticated realm, so the ledger keeps
 * exactly one:
 *
 *  1. the same realm is shared and reference counted per owner;
 *  2. every different realm is incompatible while any lease remains, even if
 *     the requester already owns the live realm. Switching is an explicit
 *     release-then-acquire lifecycle operation and can never evict another
 *     feature behind its back;
 *  3. a realm may only be shared by owners that mean the same physical scope.
 */
internal class MeshRealmLedger {
    private var realmId: MeshRealmId? = null
    private var metadata: MeshRealmMetadata? = null
    private val references = linkedMapOf<MeshOwner, Int>()

    @Synchronized
    fun activeRealm(): MeshRealmId? = realmId

    @Synchronized
    fun activeMetadata(): MeshRealmMetadata? = metadata

    @Synchronized
    fun owners(): Set<MeshOwner> = references.keys.toSet()

    @Synchronized
    fun references(owner: MeshOwner, realmId: MeshRealmId): Int =
        if (this.realmId != realmId) 0 else references[owner] ?: 0

    @Synchronized
    fun acquire(
        owner: MeshOwner,
        realmId: MeshRealmId,
        metadata: MeshRealmMetadata,
    ): MeshAcquireOutcome {
        val active = this.realmId
        if (active == null) {
            this.realmId = realmId
            this.metadata = metadata
            references[owner] = 1
            return MeshAcquireOutcome.Activated(realmId, metadata)
        }
        if (active == realmId) {
            val current = this.metadata
            if (current != null && !current.isCompatibleWith(metadata)) {
                return MeshAcquireOutcome.Denied(MeshRealmDenial.METADATA_CONFLICT, active)
            }
            val merged = current?.mergedWith(metadata) ?: metadata
            this.metadata = merged
            val count = (references[owner] ?: 0) + 1
            references[owner] = count
            return MeshAcquireOutcome.Joined(realmId, merged, count)
        }
        return MeshAcquireOutcome.Denied(MeshRealmDenial.REALM_CONFLICT, active)
    }

    /** Undoes an [acquire] whose transport activation failed, leaving no lease behind. */
    @Synchronized
    fun rollback(owner: MeshOwner, realmId: MeshRealmId): MeshReleaseOutcome = release(owner, realmId)

    @Synchronized
    fun release(owner: MeshOwner, realmId: MeshRealmId): MeshReleaseOutcome {
        if (this.realmId != realmId) return MeshReleaseOutcome.Unknown
        val count = references[owner] ?: return MeshReleaseOutcome.Unknown
        if (count > 1) {
            references[owner] = count - 1
            return MeshReleaseOutcome.Retained(count - 1)
        }
        references.remove(owner)
        if (references.isNotEmpty()) return MeshReleaseOutcome.OwnerReleased(realmId)
        this.realmId = null
        this.metadata = null
        return MeshReleaseOutcome.Deactivated(realmId)
    }

    /** Drops every reference [owner] holds and reports the single resulting transition. */
    @Synchronized
    fun releaseAll(owner: MeshOwner): MeshReleaseOutcome {
        val realmId = this.realmId ?: return MeshReleaseOutcome.Unknown
        if (references.remove(owner) == null) return MeshReleaseOutcome.Unknown
        if (references.isNotEmpty()) return MeshReleaseOutcome.OwnerReleased(realmId)
        this.realmId = null
        this.metadata = null
        return MeshReleaseOutcome.Deactivated(realmId)
    }
}
