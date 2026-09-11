package com.cruxcoach.android.sharing

/** A verified account-device leaf. Values come from MDK, never from message content. */
data class MarmotSnapshotLeaf(val account: String, val device: String)

/**
 * A stable native session fence, including incarnation, epoch and membership.
 * The future MDK adapter must hold its group lock throughout withSession/drain
 * callbacks, validate account-identity proofs, and forward invalidations before
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
    fun <T> withSession(peer: String, block: (MarmotSnapshotSession) -> T): T?

    /**
     * Synchronous local durable MDK handoff, under the same session fence.
     * Never ordinary chat or relay plaintext. Return means handoff only, not
     * recipient delivery. An exception/unclear result must not mint a retry.
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

/** The app's sole binding until the native integration gates have evidence. */
class BlockedMarmotSnapshotPort : MarmotSnapshotPort {
    override fun <T> withSession(peer: String, block: (MarmotSnapshotSession) -> T): T? = null
    override fun handoff(session: MarmotSnapshotSession, kind: Int, tags: List<List<String>>, content: String) =
        error("native sharing is unavailable")
    override fun drain(kind: Int, tags: List<List<String>>, consume: (MarmotSnapshotSession, String) -> Unit) = Unit
}
