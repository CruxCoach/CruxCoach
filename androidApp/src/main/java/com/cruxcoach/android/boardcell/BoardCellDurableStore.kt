package com.cruxcoach.android.boardcell

/** Synchronous durability boundary: methods return only after stable storage. */
interface BoardCellDurableStore {
    fun persistSnapshot(snapshot: BoardCellSnapshot)
    fun clearSnapshot(boardId: PhysicalBoardId) = Unit
    fun persistSnapshotWithAck(snapshot: BoardCellSnapshot, ack: BoardCommandAck)
    fun persistIntent(intent: BoardWriteIntent)
    fun markPhysicalWriteSucceeded(intent: BoardWriteIntent)
    fun commit(snapshot: BoardCellSnapshot, intent: BoardWriteIntent, ack: BoardCommandAck)
    fun recordAck(ack: BoardCommandAck)
    fun discardIntent(boardId: PhysicalBoardId, commandId: String)
    fun pendingIntent(boardId: PhysicalBoardId): BoardWriteIntent?
    fun commandAck(commandId: String): BoardCommandAck?
}

object NoOpBoardCellDurableStore : BoardCellDurableStore {
    override fun persistSnapshot(snapshot: BoardCellSnapshot) = Unit
    override fun clearSnapshot(boardId: PhysicalBoardId) = Unit
    override fun persistSnapshotWithAck(snapshot: BoardCellSnapshot, ack: BoardCommandAck) = Unit
    override fun persistIntent(intent: BoardWriteIntent) = Unit
    override fun markPhysicalWriteSucceeded(intent: BoardWriteIntent) = Unit
    override fun commit(snapshot: BoardCellSnapshot, intent: BoardWriteIntent, ack: BoardCommandAck) = Unit
    override fun recordAck(ack: BoardCommandAck) = Unit
    override fun discardIntent(boardId: PhysicalBoardId, commandId: String) = Unit
    override fun pendingIntent(boardId: PhysicalBoardId): BoardWriteIntent? = null
    override fun commandAck(commandId: String): BoardCommandAck? = null
}
