package com.cruxcoach.android.sharing

/** An authenticated account/permission-device pair. The live adapter binds it
 * to an actual MDK leaf using verified account and device possession proofs;
 * an unverified device field in a message is never sufficient. */
data class MarmotSnapshotLeaf(val account: String, val device: String)

/**
 * A stable native session fence, including incarnation, epoch and membership.
 * The live MDK adapter holds its group lock throughout withSession/drain
 * callbacks, validates account-identity proofs, and forwards invalidations before
 * further application reads. The binding must change irreversibly on group
 * replacement, epoch/member changes or retraction of any admitted app event;
 * temporarily unavailable state must return no session. The local leaf must
 * belong to the currently enrolled local authority device, never an account
 * projection substituted for a device credential. This DTO alone supplies no authentication.
 */
data class MarmotSnapshotSession(
    val local: MarmotSnapshotLeaf,
    val peer: MarmotSnapshotLeaf,
    val binding: String,
    val members: Set<MarmotSnapshotLeaf>,
)

interface MarmotSnapshotPort {
    /** Runtime capability, never an assertion of historical device-test evidence. */
    val nativeAvailable: Boolean get() = false
    /** Exact-content handoffs are durably idempotent only on the native adapter. */
    val durableIdempotentHandoff: Boolean get() = false
    fun refresh() = Unit
    /** Native canonical retirement is distinct from an unavailable session. */
    fun bindingRetired(binding: String): Boolean = false
    /** Retire only continuous operations made obsolete by committed app state. */
    fun discardSupersededContinuous(retain: (String) -> Boolean) = Unit
    fun discardContinuousInbox(retain: (String) -> Boolean) = Unit
    /** Hold current DB authorization through publish. Reconnect alone must
     * never publish queued private data after the local policy changes. */
    fun flush(kind: Int, tags: List<List<String>>, authorize: (MarmotSnapshotSession, String, () -> Unit) -> Unit) = Unit
    fun <T> withSession(peer: String, block: (MarmotSnapshotSession) -> T): T?

    /**
     * Synchronous local durable MDK handoff, under the same session fence.
     * Never ordinary chat or relay plaintext. Return means handoff only, not
     * recipient delivery. An unclear result may only retry the identical operation
     * on a port declaring durableIdempotentHandoff; it must not mint a new send.
     * Do not reenter application callbacks from handoff: the application may
     * hold its database transaction while checking authorization and handing off.
     */
    fun handoff(session: MarmotSnapshotSession, kind: Int, tags: List<List<String>>, content: String)

    /**
     * Delivers only validated, converged inner events of the requested kind and
     * exact tags, with authenticated sender equal to session.peer. A callback
     * must commit application state before the adapter acknowledges its inbox.
     * No retention-limited timeline or lossy broadcast is a substitute.
     */
    fun drain(kind: Int, tags: List<List<String>>, consume: (MarmotSnapshotSession, String) -> Unit)
}

/** Used by unsupported hosts and explicit tests, never as the Android live binding. */
class BlockedMarmotSnapshotPort : MarmotSnapshotPort {
    override fun <T> withSession(peer: String, block: (MarmotSnapshotSession) -> T): T? = null
    override fun handoff(session: MarmotSnapshotSession, kind: Int, tags: List<List<String>>, content: String) =
        error("native sharing is unavailable")
    override fun drain(kind: Int, tags: List<List<String>>, consume: (MarmotSnapshotSession, String) -> Unit) = Unit
}

/** One lock ordering for services over the same database: coordination, native
 * session, then short DB transactions. A port callback can safely consult roles. */
internal object SharingSessionCoordination {
    private val locks = java.util.WeakHashMap<com.cruxcoach.db.secure.SecureDatabase, Any>()
    fun forDatabase(database: com.cruxcoach.db.secure.SecureDatabase): Any = synchronized(locks) {
        locks.getOrPut(database) { Any() }
    }
    fun <T> withTransport(database: com.cruxcoach.db.secure.SecureDatabase,
                          exclusive: (() -> T) -> T, work: () -> T): T =
        synchronized(forDatabase(database)) { exclusive(work) }

}
