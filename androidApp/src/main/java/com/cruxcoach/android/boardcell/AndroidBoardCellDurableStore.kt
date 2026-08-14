package com.cruxcoach.android.boardcell

import android.content.Context
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** SharedPreferences commit() is intentional: this is the WAL, not a UI preference. */
class AndroidBoardCellDurableStore(context: Context) : BoardCellDurableStore {
    private val prefs = context.getSharedPreferences("board_cell_safety_v2", Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type" }

    override fun persistSnapshot(snapshot: BoardCellSnapshot) {
        check(prefs.edit().putString(snapshotKey(snapshot.physicalBoardId), json.encodeToString(snapshot)).commit())
    }

    override fun persistSnapshotWithAck(snapshot: BoardCellSnapshot, ack: BoardCommandAck) {
        check(prefs.edit()
            .putString(snapshotKey(snapshot.physicalBoardId), json.encodeToString(snapshot))
            .putString(ackKey(ack.commandId), json.encodeToString(ack))
            .commit())
    }

    override fun persistIntent(intent: BoardWriteIntent) {
        check(prefs.edit().putString(intentKey(intent.physicalBoardId), json.encodeToString(intent)).commit())
    }

    override fun markPhysicalWriteSucceeded(intent: BoardWriteIntent) = persistIntent(
        intent.copy(state = BoardWriteIntentState.PHYSICAL_WRITE_SUCCEEDED))

    override fun commit(snapshot: BoardCellSnapshot, intent: BoardWriteIntent, ack: BoardCommandAck) {
        check(prefs.edit()
            .putString(snapshotKey(snapshot.physicalBoardId), json.encodeToString(snapshot))
            .remove(intentKey(intent.physicalBoardId))
            .putString(ackKey(ack.commandId), json.encodeToString(ack))
            .commit())
    }

    override fun recordAck(ack: BoardCommandAck) {
        check(prefs.edit().putString(ackKey(ack.commandId), json.encodeToString(ack)).commit())
    }

    override fun discardIntent(boardId: PhysicalBoardId, commandId: String) {
        val current = pendingIntent(boardId)
        if (current?.commandId == commandId) check(prefs.edit().remove(intentKey(boardId)).commit())
    }

    override fun pendingIntent(boardId: PhysicalBoardId): BoardWriteIntent? = decode(prefs.getString(intentKey(boardId), null))
    override fun commandAck(commandId: String): BoardCommandAck? = decode(prefs.getString(ackKey(commandId), null))
    fun snapshot(boardId: PhysicalBoardId): BoardCellSnapshot? = decode(prefs.getString(snapshotKey(boardId), null))

    fun localFallbackNodeId(): String {
        prefs.getString(LOCAL_NODE_ID, null)?.let { return it }
        val created = "local-${UUID.randomUUID()}"
        check(prefs.edit().putString(LOCAL_NODE_ID, created).commit())
        return prefs.getString(LOCAL_NODE_ID, created)!!
    }

    private inline fun <reified T> decode(value: String?): T? = value?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }
    private fun snapshotKey(board: PhysicalBoardId) = "snapshot:${board.value}"
    private fun intentKey(board: PhysicalBoardId) = "intent:${board.value}"
    private fun ackKey(commandId: String) = "ack:$commandId"

    private companion object { const val LOCAL_NODE_ID = "local_node_id" }
}
