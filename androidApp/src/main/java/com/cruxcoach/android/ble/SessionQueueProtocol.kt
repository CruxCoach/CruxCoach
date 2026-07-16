package com.cruxcoach.android.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Binary encoding/decoding for Session Queue GATT payloads.
 *
 * **Commands** (Client → Host, written to QUEUE_COMMAND):
 *   CMD_ADD, CMD_REMOVE, CMD_SET_CURRENT, CMD_NEXT, CMD_PREV, CMD_JOIN, CMD_LEAVE
 *
 * **Events** (Host → Client, notified via QUEUE_EVENT):
 *   EVT_ADDED, EVT_REMOVED, EVT_CURRENT, EVT_CLEARED, EVT_PARTICIPANT_JOINED/LEFT
 *   Each event fits in a single BLE notification (max 19 bytes).
 *
 * **Full State** (read via QUEUE_STATE characteristic, supports GATT Long Read for large queues):
 *   [1B currentIndex][1B itemCount][per item: 1B angle + 16B uuid]
 */
object SessionQueueProtocol {

    // --- Command opcodes (Client → Host) ---
    const val CMD_ADD: Byte = 0x01
    const val CMD_REMOVE: Byte = 0x02
    const val CMD_SET_CURRENT: Byte = 0x03
    const val CMD_NEXT: Byte = 0x04
    const val CMD_PREV: Byte = 0x05
    const val CMD_JOIN: Byte = 0x06
    const val CMD_LEAVE: Byte = 0x07
    const val CMD_MOVE: Byte = 0x08

    // --- Event opcodes (Host → Client) ---
    const val EVT_ADDED: Byte = 0x01
    const val EVT_REMOVED: Byte = 0x02
    const val EVT_CURRENT: Byte = 0x03
    const val EVT_CLEARED: Byte = 0x04
    const val EVT_PARTICIPANT_JOINED: Byte = 0x05
    const val EVT_PARTICIPANT_LEFT: Byte = 0x06

    // ===== Command encoding (Client → Host) =====

    fun encodeAdd(climbUuid: String, angle: Int): ByteArray {
        val uuid = UUID.fromString(normalizeUuid(climbUuid))
        val buf = ByteArray(18) // 1 cmd + 1 angle + 16 uuid
        buf[0] = CMD_ADD
        buf[1] = angle.coerceIn(0, 70).toByte()
        putUuid(buf, 2, uuid)
        return buf
    }

    fun encodeRemove(index: Int): ByteArray = byteArrayOf(CMD_REMOVE, index.toByte())

    fun encodeSetCurrent(index: Int): ByteArray = byteArrayOf(CMD_SET_CURRENT, index.toByte())

    fun encodeNext(): ByteArray = byteArrayOf(CMD_NEXT)

    fun encodePrev(): ByteArray = byteArrayOf(CMD_PREV)

    /**
     * The code extends the released `[cmd][nameLen][name]` shape, so a new
     * client can still join an older host. A new host deliberately rejects a
     * legacy join with no code; retaining that insecure direction would defeat
     * authorization.
     */
    fun encodeJoin(displayName: String, sessionCode: String): ByteArray {
        require(SessionJoinCode.isValid(sessionCode)) { "invalid session join code" }
        val nameBytes = displayName.toByteArray(Charsets.UTF_8).let {
            if (it.size > MAX_JOIN_NAME_BYTES) it.copyOf(MAX_JOIN_NAME_BYTES) else it
        }
        val codeBytes = sessionCode.toByteArray(Charsets.US_ASCII)
        val buf = ByteArray(2 + nameBytes.size + codeBytes.size)
        buf[0] = CMD_JOIN
        buf[1] = nameBytes.size.toByte()
        nameBytes.copyInto(buf, 2)
        codeBytes.copyInto(buf, 2 + nameBytes.size)
        return buf
    }

    fun encodeLeave(): ByteArray = byteArrayOf(CMD_LEAVE)

    fun encodeMove(from: Int, to: Int): ByteArray = byteArrayOf(CMD_MOVE, from.toByte(), to.toByte())

    // ===== Command decoding (Host reads from Client) =====

    fun decodeCommand(data: ByteArray): SessionCommand? {
        if (data.isEmpty()) return null
        return when (data[0]) {
            CMD_ADD -> {
                if (data.size < 18) return null
                val angle = data[1].toInt() and 0xFF
                val uuid = getUuid(data, 2)
                SessionCommand.Add(uuid.toString().replace("-", "").uppercase(), angle)
            }
            CMD_REMOVE -> {
                if (data.size < 2) return null
                SessionCommand.Remove(data[1].toInt() and 0xFF)
            }
            CMD_SET_CURRENT -> {
                if (data.size < 2) return null
                SessionCommand.SetCurrent(data[1].toInt() and 0xFF)
            }
            CMD_NEXT -> SessionCommand.Next
            CMD_PREV -> SessionCommand.Prev
            CMD_JOIN -> {
                if (data.size < 2) return null
                val nameLen = (data[1].toInt() and 0xFF).coerceAtMost(data.size - 2)
                val name = String(data, 2, nameLen, Charsets.UTF_8)
                val codeOffset = 2 + nameLen
                val sessionCode = if (data.size == codeOffset + SessionJoinCode.CODE_LENGTH) {
                    String(data, codeOffset, SessionJoinCode.CODE_LENGTH, Charsets.US_ASCII)
                } else {
                    ""
                }
                SessionCommand.Join(name, sessionCode)
            }
            CMD_LEAVE -> SessionCommand.Leave
            CMD_MOVE -> {
                if (data.size < 3) return null
                SessionCommand.Move(data[1].toInt() and 0xFF, data[2].toInt() and 0xFF)
            }
            else -> null
        }
    }

    // ===== Event encoding (Host → Client, via notifications) =====

    fun encodeEventAdded(index: Int, climbUuid: String, angle: Int): ByteArray {
        val uuid = UUID.fromString(normalizeUuid(climbUuid))
        val buf = ByteArray(19) // 1 evt + 1 index + 1 angle + 16 uuid
        buf[0] = EVT_ADDED
        buf[1] = index.toByte()
        buf[2] = angle.coerceIn(0, 70).toByte()
        putUuid(buf, 3, uuid)
        return buf
    }

    fun encodeEventRemoved(index: Int): ByteArray = byteArrayOf(EVT_REMOVED, index.toByte())

    fun encodeEventCurrent(index: Int): ByteArray = byteArrayOf(EVT_CURRENT, index.toByte())

    fun encodeEventCleared(): ByteArray = byteArrayOf(EVT_CLEARED)

    fun encodeEventParticipantJoined(name: String): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8).let {
            if (it.size > 18) it.copyOf(18) else it
        }
        val buf = ByteArray(2 + nameBytes.size)
        buf[0] = EVT_PARTICIPANT_JOINED
        buf[1] = nameBytes.size.toByte()
        nameBytes.copyInto(buf, 2)
        return buf
    }

    fun encodeEventParticipantLeft(name: String): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8).let {
            if (it.size > 18) it.copyOf(18) else it
        }
        val buf = ByteArray(2 + nameBytes.size)
        buf[0] = EVT_PARTICIPANT_LEFT
        buf[1] = nameBytes.size.toByte()
        nameBytes.copyInto(buf, 2)
        return buf
    }

    // ===== Event decoding (Client reads from Host notifications) =====

    fun decodeEvent(data: ByteArray): SessionEvent? {
        if (data.isEmpty()) return null
        return when (data[0]) {
            EVT_ADDED -> {
                if (data.size < 19) return null
                val index = data[1].toInt() and 0xFF
                val angle = data[2].toInt() and 0xFF
                val uuid = getUuid(data, 3)
                SessionEvent.Added(index, uuid.toString().replace("-", "").uppercase(), angle)
            }
            EVT_REMOVED -> {
                if (data.size < 2) return null
                SessionEvent.Removed(data[1].toInt() and 0xFF)
            }
            EVT_CURRENT -> {
                if (data.size < 2) return null
                SessionEvent.CurrentChanged(data[1].toInt() and 0xFF)
            }
            EVT_CLEARED -> SessionEvent.Cleared
            EVT_PARTICIPANT_JOINED -> {
                if (data.size < 2) return null
                val nameLen = (data[1].toInt() and 0xFF).coerceAtMost(data.size - 2)
                SessionEvent.ParticipantJoined(String(data, 2, nameLen, Charsets.UTF_8))
            }
            EVT_PARTICIPANT_LEFT -> {
                if (data.size < 2) return null
                val nameLen = (data[1].toInt() and 0xFF).coerceAtMost(data.size - 2)
                SessionEvent.ParticipantLeft(String(data, 2, nameLen, Charsets.UTF_8))
            }
            else -> null
        }
    }

    // ===== Full queue state (for GATT Read / initial sync) =====

    fun encodeQueueState(currentIndex: Int, items: List<QueueItem>): ByteArray {
        val buf = ByteArray(2 + items.size * 17)
        buf[0] = currentIndex.toByte()
        buf[1] = items.size.toByte()
        items.forEachIndexed { i, item ->
            val offset = 2 + i * 17
            buf[offset] = item.angle.coerceIn(0, 70).toByte()
            val uuid = UUID.fromString(normalizeUuid(item.climbUuid))
            putUuid(buf, offset + 1, uuid)
        }
        return buf
    }

    fun decodeQueueState(data: ByteArray): Pair<Int, List<QueueItem>>? {
        if (data.size < 2) return null
        val currentIndex = data[0].toInt() and 0xFF
        val count = data[1].toInt() and 0xFF
        if (data.size < 2 + count * 17) return null
        val items = (0 until count).map { i ->
            val offset = 2 + i * 17
            val angle = data[offset].toInt() and 0xFF
            val uuid = getUuid(data, offset + 1)
            QueueItem(uuid.toString().replace("-", "").uppercase(), angle)
        }
        return currentIndex to items
    }

    // ===== Session info (for GATT Read) =====

    fun encodeSessionInfo(hostName: String, participantCount: Int): ByteArray {
        val nameBytes = hostName.toByteArray(Charsets.UTF_8).let {
            if (it.size > 20) it.copyOf(20) else it
        }
        val buf = ByteArray(2 + nameBytes.size)
        buf[0] = participantCount.toByte()
        buf[1] = nameBytes.size.toByte()
        nameBytes.copyInto(buf, 2)
        return buf
    }

    fun decodeSessionInfo(data: ByteArray): SessionInfo? {
        if (data.size < 2) return null
        val count = data[0].toInt() and 0xFF
        val nameLen = (data[1].toInt() and 0xFF).coerceAtMost(data.size - 2)
        val name = String(data, 2, nameLen, Charsets.UTF_8)
        return SessionInfo(name, count)
    }

    // ===== Participant list (for GATT Read) =====

    fun encodeParticipantList(names: List<String>): ByteArray {
        val encoded = names.map { name ->
            name.toByteArray(Charsets.UTF_8).let { if (it.size > 20) it.copyOf(20) else it }
        }
        val totalSize = 1 + encoded.sumOf { 1 + it.size }
        val buf = ByteArray(totalSize)
        buf[0] = names.size.toByte()
        var offset = 1
        encoded.forEach { nameBytes ->
            buf[offset] = nameBytes.size.toByte()
            nameBytes.copyInto(buf, offset + 1)
            offset += 1 + nameBytes.size
        }
        return buf
    }

    fun decodeParticipantList(data: ByteArray): List<String>? {
        if (data.isEmpty()) return null
        val count = data[0].toInt() and 0xFF
        val names = mutableListOf<String>()
        var offset = 1
        repeat(count) {
            if (offset >= data.size) return names
            val nameLen = (data[offset].toInt() and 0xFF).coerceAtMost(data.size - offset - 1)
            names.add(String(data, offset + 1, nameLen, Charsets.UTF_8))
            offset += 1 + nameLen
        }
        return names
    }

    // ===== UUID helpers =====

    private fun putUuid(buf: ByteArray, offset: Int, uuid: UUID) {
        val bb = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        bb.putLong(uuid.mostSignificantBits)
        bb.putLong(uuid.leastSignificantBits)
        bb.array().copyInto(buf, offset)
    }

    private fun getUuid(data: ByteArray, offset: Int): UUID {
        val bb = ByteBuffer.wrap(data, offset, 16).order(ByteOrder.BIG_ENDIAN)
        return UUID(bb.long, bb.long)
    }

    /** Ensures a UUID string has hyphens for UUID.fromString(). */
    private fun normalizeUuid(s: String): String {
        if (s.contains('-')) return s
        if (s.length != 32) return s
        return "${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}"
    }
}

// ===== Data types =====

sealed class SessionCommand {
    data class Add(val climbUuid: String, val angle: Int) : SessionCommand()
    data class Remove(val index: Int) : SessionCommand()
    data class SetCurrent(val index: Int) : SessionCommand()
    data object Next : SessionCommand()
    data object Prev : SessionCommand()
    data class Join(val displayName: String, val sessionCode: String) : SessionCommand()
    data object Leave : SessionCommand()
    data class Move(val from: Int, val to: Int) : SessionCommand()
}

sealed class SessionEvent {
    data class Added(val index: Int, val climbUuid: String, val angle: Int) : SessionEvent()
    data class Removed(val index: Int) : SessionEvent()
    data class CurrentChanged(val index: Int) : SessionEvent()
    data object Cleared : SessionEvent()
    data class ParticipantJoined(val name: String) : SessionEvent()
    data class ParticipantLeft(val name: String) : SessionEvent()
}

data class QueueItem(val climbUuid: String, val angle: Int)
data class SessionInfo(val hostName: String, val participantCount: Int)

private const val MAX_JOIN_NAME_BYTES = 12
