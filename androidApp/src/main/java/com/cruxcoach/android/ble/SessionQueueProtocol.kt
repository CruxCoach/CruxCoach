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
 * **Full State** (read via QUEUE_STATE characteristic, supports GATT Long Read):
 *   [1B currentIndex][1B page][1B pageCount][1B itemsInPage][per item: 1B angle + 16B uuid]
 *
 *   Paged, because one attribute cannot carry a whole playlist. GATT caps an
 *   attribute value at 512 bytes and a notification at ATT_MTU-3 (509 here),
 *   which is 29 items — while the generator builds sessions of up to 38. The
 *   frame used to be a flat list with a single count byte, so anything larger
 *   arrived truncated, failed the length check on the participant's side and
 *   was dropped without a word: the joiner saw the host, the participant count
 *   and an empty queue.
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

    /**
     * Rest phase, added 2026-08-06.
     *
     * A rest is not a queue position — it is a phase during which the queue
     * already points at the upcoming climb. Participants therefore only ever
     * received [EVT_CURRENT] and jumped straight to that climb while the host
     * was still counting down. Measured on two devices: the host showed
     * "Pause 0:26 · next DA REAL 6A+" while the participant showed DA REAL
     * 6A+ ready to climb, both labelled "2 of 3".
     *
     * [QueueItem.restAfterSeconds] deliberately stays off the wire — the
     * 17-byte frame and old-client compatibility were good reasons and still
     * are. What the old reasoning got wrong is the sentence "rest timers are
     * personal pacing, not shared board state": the host drives the wall, so
     * during its rest the NEXT climb is already lit. That makes the rest very
     * much shared state, and it is the phase that has to travel, not the
     * per-item duration.
     *
     * New opcodes rather than a wider frame: both decoders answer `null` on
     * anything unknown, so a 0.2.2-or-older participant ignores these and
     * behaves exactly as it does today.
     */
    const val EVT_REST_STARTED: Byte = 0x07
    const val EVT_REST_ENDED: Byte = 0x08

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

    fun encodeJoin(displayName: String, memberNpub: String? = null): ByteArray {
        val nameBytes = displayName.toByteArray(Charsets.UTF_8).let {
            if (it.size > 20) it.copyOf(20) else it
        }
        val npub = memberNpub?.encodeToByteArray()?.take(100)?.toByteArray()
        val buf = ByteArray(2 + nameBytes.size + if (npub == null) 0 else 1 + npub.size)
        buf[0] = CMD_JOIN
        buf[1] = nameBytes.size.toByte()
        nameBytes.copyInto(buf, 2)
        if (npub != null) {
            buf[2 + nameBytes.size] = npub.size.toByte()
            npub.copyInto(buf, 3 + nameBytes.size)
        }
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
                val offset = 2 + nameLen
                val npub = if (data.size > offset) {
                    val length = data[offset].toInt() and 0xff
                    if (length > 0 && data.size >= offset + 1 + length)
                        String(data, offset + 1, length, Charsets.UTF_8) else null
                } else null
                SessionCommand.Join(name, npub)
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

    /**
     * Two bytes for the countdown: a rest can legitimately run to 60 minutes
     * (the editor's own upper bound), which does not fit in one.
     */
    fun encodeEventRestStarted(remainingSeconds: Int, nextIndex: Int): ByteArray {
        val clamped = remainingSeconds.coerceIn(0, 0xFFFF)
        return byteArrayOf(
            EVT_REST_STARTED,
            ((clamped shr 8) and 0xFF).toByte(),
            (clamped and 0xFF).toByte(),
            nextIndex.coerceIn(0, 0xFF).toByte(),
        )
    }

    fun encodeEventRestEnded(): ByteArray = byteArrayOf(EVT_REST_ENDED)

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
            EVT_REST_STARTED -> {
                if (data.size < 4) return null
                val seconds = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                SessionEvent.RestStarted(seconds, data[3].toInt() and 0xFF)
            }
            EVT_REST_ENDED -> SessionEvent.RestEnded
            else -> null
        }
    }

    // ===== Full queue state (for GATT Read / initial sync) =====

    /** Items per page — sized for a notification (ATT_MTU-3 = 509 here). */
    const val QUEUE_STATE_PAGE_SIZE = 29

    private const val QUEUE_STATE_HEADER = 4
    private const val QUEUE_STATE_ITEM = 17

    /** One page of a queue. [page] is 0-based, [pageCount] is at least 1. */
    data class QueueStatePage(
        val currentIndex: Int,
        val page: Int,
        val pageCount: Int,
        val items: List<QueueItem>,
    )

    /** How many pages [itemCount] items need. An empty queue still sends one. */
    fun queueStatePageCount(itemCount: Int): Int =
        if (itemCount == 0) 1 else (itemCount + QUEUE_STATE_PAGE_SIZE - 1) / QUEUE_STATE_PAGE_SIZE

    fun encodeQueueState(currentIndex: Int, items: List<QueueItem>, page: Int = 0): ByteArray {
        val pageCount = queueStatePageCount(items.size)
        val slice = items.drop(page * QUEUE_STATE_PAGE_SIZE).take(QUEUE_STATE_PAGE_SIZE)
        val buf = ByteArray(QUEUE_STATE_HEADER + slice.size * QUEUE_STATE_ITEM)
        buf[0] = currentIndex.toByte()
        buf[1] = page.toByte()
        buf[2] = pageCount.toByte()
        buf[3] = slice.size.toByte()
        slice.forEachIndexed { i, item ->
            val offset = QUEUE_STATE_HEADER + i * QUEUE_STATE_ITEM
            buf[offset] = item.angle.coerceIn(0, 70).toByte()
            val uuid = UUID.fromString(normalizeUuid(item.climbUuid))
            putUuid(buf, offset + 1, uuid)
        }
        return buf
    }

    fun decodeQueueState(data: ByteArray): QueueStatePage? {
        if (data.size < QUEUE_STATE_HEADER) return null
        val currentIndex = data[0].toInt() and 0xFF
        val page = data[1].toInt() and 0xFF
        val pageCount = data[2].toInt() and 0xFF
        val count = data[3].toInt() and 0xFF
        if (pageCount == 0 || page >= pageCount) return null
        if (data.size < QUEUE_STATE_HEADER + count * QUEUE_STATE_ITEM) return null
        val items = (0 until count).map { i ->
            val offset = QUEUE_STATE_HEADER + i * QUEUE_STATE_ITEM
            val angle = data[offset].toInt() and 0xFF
            val uuid = getUuid(data, offset + 1)
            QueueItem(uuid.toString().replace("-", "").uppercase(), angle)
        }
        return QueueStatePage(currentIndex, page, pageCount, items)
    }

    // ===== Session info (for GATT Read) =====

    fun encodeSessionInfo(hostName: String, participantCount: Int,
        physicalBoardId: String? = null, boardCellId: String? = null): ByteArray {
        val nameBytes = hostName.toByteArray(Charsets.UTF_8).let {
            if (it.size > 20) it.copyOf(20) else it
        }
        val physical = physicalBoardId?.encodeToByteArray()?.take(255)?.toByteArray()
        val cell = boardCellId?.encodeToByteArray()?.take(255)?.toByteArray()
        val extension = if (physical != null && cell != null) 3 + physical.size + cell.size else 0
        val buf = ByteArray(2 + nameBytes.size + extension)
        buf[0] = participantCount.toByte()
        buf[1] = nameBytes.size.toByte()
        nameBytes.copyInto(buf, 2)
        if (physical != null && cell != null) {
            var offset = 2 + nameBytes.size
            buf[offset++] = SESSION_SCOPE_MARKER
            buf[offset++] = physical.size.toByte(); physical.copyInto(buf, offset); offset += physical.size
            buf[offset++] = cell.size.toByte(); cell.copyInto(buf, offset)
        }
        return buf
    }

    /**
     * The host is leaving. [migrate] true means the group carries on and the
     * first participant takes over; false means the playlist is over for
     * everyone.
     *
     * The migrating form is the historical two-byte sentinel (participant
     * count 0). The final form appends a flag byte, which a client that
     * predates it simply does not read — so an older participant falls back to
     * migration, which is the safe way to be wrong.
     */
    fun encodeSessionEnded(migrate: Boolean): ByteArray =
        if (migrate) byteArrayOf(0, 0)
        else byteArrayOf(0, 0, SESSION_END_FINAL_FLAG)

    /** Whether a session-ended sentinel means "over", not "hand over". */
    fun isFinalSessionEnd(data: ByteArray): Boolean =
        data.size >= 3 && data[0].toInt() == 0 && data[2] == SESSION_END_FINAL_FLAG

    private const val SESSION_END_FINAL_FLAG: Byte = 1
    private const val SESSION_SCOPE_MARKER: Byte = -68 // 0xBC

    fun decodeSessionInfo(data: ByteArray): SessionInfo? {
        if (data.size < 2) return null
        val count = data[0].toInt() and 0xFF
        val nameLen = (data[1].toInt() and 0xFF).coerceAtMost(data.size - 2)
        val name = String(data, 2, nameLen, Charsets.UTF_8)
        var physical: String? = null
        var cell: String? = null
        var offset = 2 + nameLen
        if (data.size > offset && data[offset++] == SESSION_SCOPE_MARKER && data.size > offset) {
            val physicalLen = data[offset++].toInt() and 0xff
            if (data.size >= offset + physicalLen + 1) {
                physical = String(data, offset, physicalLen, Charsets.UTF_8); offset += physicalLen
                val cellLen = data[offset++].toInt() and 0xff
                if (data.size >= offset + cellLen) cell = String(data, offset, cellLen, Charsets.UTF_8)
            }
        }
        return SessionInfo(name, count, physical, cell)
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
    data class Join(val displayName: String, val memberNpub: String? = null) : SessionCommand()
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

    /**
     * The host has begun a planned rest and will resume in
     * [remainingSeconds]. [nextIndex] is the climb the queue already points
     * at — sent alongside so a participant that missed the preceding
     * [CurrentChanged] still shows the right "up next".
     *
     * Seconds remaining, not an absolute deadline: the two clocks are not
     * synchronised and a wall-clock skew of even a few seconds would show
     * one side a countdown that ends at the wrong moment.
     */
    data class RestStarted(val remainingSeconds: Int, val nextIndex: Int) : SessionEvent()

    /** The rest is over — either it ran out or somebody skipped it. */
    data object RestEnded : SessionEvent()
}

/**
 * One queue entry. [restAfterSeconds] is HOST-LOCAL playlist metadata
 * ("rest this long after completing the climb") — it is deliberately NOT
 * wire-encoded: the GATT frame stays 17 bytes (angle + uuid), old clients
 * remain compatible, and rest timers are personal pacing, not shared board
 * state. Participants (and a migrated host) therefore see 0 here.
 */
data class QueueItem(
    val climbUuid: String,
    val angle: Int,
    val restAfterSeconds: Int = 0,
)
data class SessionInfo(val hostName: String, val participantCount: Int,
    val physicalBoardId: String? = null, val boardCellId: String? = null)
