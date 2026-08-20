package com.cruxcoach.android.boardcell

/** API 28 has no FIPS/L2CAP mesh, but it may never bypass BoardCell serialization or WAL. */
object BoardCellPlatformPolicy {
    fun meshAvailable(apiLevel: Int): Boolean = apiLevel >= 29
    /** API 28 remains a local Board/CruxRelay client, never a group node. */
    fun sharedPlaylistAvailable(apiLevel: Int): Boolean = meshAvailable(apiLevel)
    /** The pre-FIPS GATT shared-session state machine has no supported platform. */
    fun legacyGattPlaylistAvailable(@Suppress("UNUSED_PARAMETER") apiLevel: Int): Boolean = false
    fun requiresSafetyBoundary(@Suppress("UNUSED_PARAMETER") apiLevel: Int): Boolean = true

    /**
     * Whether this device takes part in the BoardCell's shared playlist.
     *
     * API 28 has no public BLE L2CAP CoC and therefore no FIPS identity, so it
     * cannot be a cell member and cannot get a command to a controller under
     * its own name; it takes part as a GATT leaf of a gateway instead. Being
     * an active cell member is the whole condition — there is nothing else to
     * start, join or be admitted to.
     */
    fun participatesInSharedPlaylist(
        apiLevel: Int,
        cellIsActive: Boolean,
        localIsCellMember: Boolean,
    ): Boolean = meshAvailable(apiLevel) && cellIsActive && localIsCellMember
}

fun interface BoardCellWriteGateway {
    suspend fun project(projection: BoardProjection, boardWrite: suspend () -> Boolean): Boolean
}

object ActiveBoardCellWriteGateway : BoardCellWriteGateway {
    override suspend fun project(projection: BoardProjection, boardWrite: suspend () -> Boolean): Boolean {
        val manager = BoardCellManager.current ?: return false
        // No consent question here any more. It existed because a cell member
        // could be outside the shared playlist and take the wall from it;
        // membership now *is* participation, so a member lighting a climb is
        // simply that group using its own board.
        // Not the technical controller: the projection travels as a request so
        // it is serialized with everybody else's and becomes visible canonical
        // state, instead of being dropped because this device cannot write.
        if (manager.canSendViaMesh()) return manager.sendProjectionRequest(projection) != null
        return when (manager.project(projection, boardWrite = boardWrite)) {
            is ProjectionResult.Committed, is ProjectionResult.Duplicate -> true
            else -> false
        }
    }
}
