package com.cruxcoach.android.boardcell

import android.content.Context
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** SharedPreferences commit() is intentional: this is the WAL, not a UI preference. */
class AndroidBoardCellDurableStore(context: Context) : BoardCellDurableStore {
    private val prefs = context.getSharedPreferences("board_cell_safety_v2", Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type" }
    private val ackLock = Any()

    override fun persistSnapshot(snapshot: BoardCellSnapshot) {
        check(prefs.edit().putString(snapshotKey(snapshot.physicalBoardId), json.encodeToString(snapshot)).commit())
    }

    override fun persistSnapshotWithAck(snapshot: BoardCellSnapshot, ack: BoardCommandAck) = synchronized(ackLock) {
        val editor = prefs.edit().putString(snapshotKey(snapshot.physicalBoardId), json.encodeToString(snapshot))
        check(addBoundedAck(editor, ack).commit())
    }

    override fun persistIntent(intent: BoardWriteIntent) {
        check(prefs.edit().putString(intentKey(intent.physicalBoardId), json.encodeToString(intent)).commit())
    }

    override fun markPhysicalWriteSucceeded(intent: BoardWriteIntent) = persistIntent(
        intent.copy(state = BoardWriteIntentState.PHYSICAL_WRITE_SUCCEEDED))

    override fun commit(snapshot: BoardCellSnapshot, intent: BoardWriteIntent, ack: BoardCommandAck) = synchronized(ackLock) {
        val editor = prefs.edit()
            .putString(snapshotKey(snapshot.physicalBoardId), json.encodeToString(snapshot))
            .remove(intentKey(intent.physicalBoardId))
        check(addBoundedAck(editor, ack).commit())
    }

    override fun recordAck(ack: BoardCommandAck) = synchronized(ackLock) {
        check(addBoundedAck(prefs.edit(), ack).commit())
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

    private fun addBoundedAck(editor: android.content.SharedPreferences.Editor,
        ack: BoardCommandAck): android.content.SharedPreferences.Editor {
        val indexed = prefs.getString(ACK_INDEX, null)?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrNull()
        }.orEmpty()
        // V2 had no index and leaked one preference per command. Fold those
        // keys into the first V3 prune without needing a destructive migration.
        val unindexed = prefs.all.keys.asSequence().filter { it.startsWith("ack:") }
            .map { it.removePrefix("ack:") }.filterNot { it in indexed }.sorted().toList()
        val old = (unindexed + indexed).distinct()
        val next = (old.filterNot { it == ack.commandId } + ack.commandId).takeLast(MAX_ACKS)
        (old - next.toSet()).forEach { editor.remove(ackKey(it)) }
        return editor.putString(ackKey(ack.commandId), json.encodeToString(ack))
            .putString(ACK_INDEX, json.encodeToString(next))
    }

    private companion object {
        const val LOCAL_NODE_ID = "local_node_id"
        const val ACK_INDEX = "ack_index_v3"
        const val MAX_ACKS = 256
    }
}
