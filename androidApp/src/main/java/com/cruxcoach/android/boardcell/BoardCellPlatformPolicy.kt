package com.cruxcoach.android.boardcell

/** API 28 has no FIPS/L2CAP mesh, but it may never bypass BoardCell serialization or WAL. */
object BoardCellPlatformPolicy {
    fun meshAvailable(apiLevel: Int): Boolean = apiLevel >= 29
    fun requiresSafetyBoundary(@Suppress("UNUSED_PARAMETER") apiLevel: Int): Boolean = true

    /**
     * Whether this device may start the BoardCell's one joinable playlist as
     * canonical mesh state.
     *
     * API 28 has no public BLE L2CAP CoC and therefore no FIPS identity, so it
     * can neither carry a playlist-host identity nor get a control command to
     * a controller. Answering "yes" here and failing later left the user with
     * an empty started session instead of the legacy GATT joinable path that
     * still works perfectly well on that platform — so the platform gate has
     * to be part of the decision, not a surprise inside it.
     */
    fun canStartCanonicalPlaylist(
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
        // A local-only playlist keeps its queue to itself, but the wall is
        // shared hardware: taking it from a running joinable playlist is a
        // question for the user, asked once per playlist.
        if (!manager.mayOverwriteSharedProjection(projection)) return false
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
