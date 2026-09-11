package com.cruxcoach.android.sharing

/** Shared by Android automation and the synthetic native endpoint. This only
 * prepares authenticated two-leaf connectivity, never accepts friendship or
 * selects data. Explicit discovery opt-in allows receiving these requests. */
internal object FriendshipTransportPreparation {
    fun run(port: LiveMarmotSnapshotPort, pending: List<PendingFriendshipView>) {
        if (pending.isNotEmpty() && !port.discoveryEnabled()) port.bootstrap()
        if (!port.discoveryEnabled()) return
        val peers = port.peers()
        // Existing sessions, including old device/epoch bindings, cannot be
        // silently replaced by another Welcome from the same account.
        peers.filter { candidate -> !candidate.accepted && peers.none { it.account == candidate.account && it.accepted } }
            .take(2).forEach { port.acceptInvitation(it.account, it.group) }
        pending.filter { request -> peers.none { it.account == request.peer } }.take(2).forEach {
            runCatching { port.invite(it.peer) } // unavailable discovery stays pending
        }
    }
}
