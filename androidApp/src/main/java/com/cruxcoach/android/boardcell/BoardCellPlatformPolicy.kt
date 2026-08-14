package com.cruxcoach.android.boardcell

/** API 28 has no FIPS/L2CAP mesh, but it may never bypass BoardCell serialization or WAL. */
object BoardCellPlatformPolicy {
    fun meshAvailable(apiLevel: Int): Boolean = apiLevel >= 29
    fun requiresSafetyBoundary(@Suppress("UNUSED_PARAMETER") apiLevel: Int): Boolean = true
}

fun interface BoardCellWriteGateway {
    suspend fun project(projection: BoardProjection, boardWrite: suspend () -> Boolean): Boolean
}

object ActiveBoardCellWriteGateway : BoardCellWriteGateway {
    override suspend fun project(projection: BoardProjection, boardWrite: suspend () -> Boolean): Boolean {
        return when (BoardCellManager.current?.project(projection, boardWrite = boardWrite)) {
            is ProjectionResult.Committed, is ProjectionResult.Duplicate -> true
            else -> false
        }
    }
}
