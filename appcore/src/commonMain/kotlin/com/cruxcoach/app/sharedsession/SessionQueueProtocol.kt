// Port of androidApp/.../ble/SessionQueueProtocol.kt and SessionGattUuids.kt (0e1bad0c6) to
// common Kotlin: the same opcodes, frame layouts, limits and decoders, without java.util.UUID,
// ByteBuffer or DataOutputStream. The iPhone only ever plays the participant, but the host-side
// encoders stay so Android's protocol tests run unchanged against this copy.
package com.cruxcoach.app.sharedsession

/** GATT UUIDs of the shared session (Android `SessionGattUuids`). */
internal object SessionGattUuids {
    /** Android `BoardBleUuids.CRUXCOACH_CLIMB_SHARING`. */
    const val SERVICE = "C1140B00-CC01-4000-8000-DEADC0AC0001"
    const val SESSION_INFO = "C1140B01-CC01-4000-8000-DEADC0AC0001"
    const val CURRENT_CLIMB = "C1140B02-CC01-4000-8000-DEADC0AC0001"
    const val QUEUE_STATE = "C1140B03-CC01-4000-8000-DEADC0AC0001"
    const val QUEUE_COMMAND = "C1140B04-CC01-4000-8000-DEADC0AC0001"
    const val QUEUE_EVENT = "C1140B05-CC01-4000-8000-DEADC0AC0001"
    const val PARTICIPANT_LIST = "C1140B06-CC01-4000-8000-DEADC0AC0001"
}

internal object SessionQueueProtocol {

    // --- Command opcodes (Client → Host) ---
    const val CMD_ADD: Byte = 0x01
    const val CMD_REMOVE: Byte = 0x02
    const val CMD_SET_CURRENT: Byte = 0x03
    const val CMD_NEXT: Byte = 0x04
    const val CMD_PREV: Byte = 0x05
    const val CMD_JOIN: Byte = 0x06
    const val CMD_LEAVE: Byte = 0x07
    const val CMD_MOVE: Byte = 0x08
    const val CMD_RESEND: Byte = 0x09

    // --- Event opcodes (Host → Client) ---
    const val EVT_ADDED: Byte = 0x01
    const val EVT_REMOVED: Byte = 0x02
    const val EVT_CURRENT: Byte = 0x03
    const val EVT_CLEARED: Byte = 0x04
    const val EVT_PARTICIPANT_JOINED: Byte = 0x05
    const val EVT_PARTICIPANT_LEFT: Byte = 0x06

    /** The host's rest phase travels as its own events (Android 2026-08-06); old clients ignore them. */
    const val EVT_REST_STARTED: Byte = 0x07
    const val EVT_REST_ENDED: Byte = 0x08
    const val EVT_COMMAND_RESULT: Byte = 0x09

    private const val COMMAND_CONTEXT_MARKER: Byte = -51 // 0xCD
    private const val COMMAND_CONTEXT_VERSION: Byte = 1

    // ===== Command encoding (Client → Host) =====

    /** Throws [IllegalArgumentException] for a climb id that is not a UUID, as Android's `UUID.fromString` does. */
    fun encodeAdd(climbUuid: String, angle: Int): ByteArray {
        val uuid = uuidBytes(climbUuid)
        val buf = ByteArray(18) // 1 cmd + 1 angle + 16 uuid
        buf[0] = CMD_ADD
        buf[1] = angle.coerceIn(0, 70).toByte()
        uuid.copyInto(buf, 2)
        return buf
    }

    fun encodeRemove(index: Int): ByteArray = byteArrayOf(CMD_REMOVE, index.toByte())

    fun encodeSetCurrent(index: Int): ByteArray = byteArrayOf(CMD_SET_CURRENT, index.toByte())

    fun encodeNext(): ByteArray = byteArrayOf(CMD_NEXT)

    fun encodePrev(): ByteArray = byteArrayOf(CMD_PREV)

    fun encodeJoin(displayName: String): ByteArray {
        val nameBytes = truncated(displayName, 20)
        val buf = ByteArray(2 + nameBytes.size)
        buf[0] = CMD_JOIN
        buf[1] = nameBytes.size.toByte()
        nameBytes.copyInto(buf, 2)
        return buf
    }

    fun encodeLeave(): ByteArray = byteArrayOf(CMD_LEAVE)

    fun encodeMove(from: Int, to: Int): ByteArray = byteArrayOf(CMD_MOVE, from.toByte(), to.toByte())

    fun encodeResend(): ByteArray = byteArrayOf(CMD_RESEND)

    fun encodeCommand(command: SessionCommand): ByteArray = when (command) {
        is SessionCommand.Add -> encodeAdd(command.climbUuid, command.angle)
        is SessionCommand.Remove -> encodeRemove(command.index)
        is SessionCommand.SetCurrent -> encodeSetCurrent(command.index)
        SessionCommand.Next -> encodeNext()
        SessionCommand.Prev -> encodePrev()
        is SessionCommand.Join -> encodeJoin(command.displayName)
        SessionCommand.Leave -> encodeLeave()
        is SessionCommand.Move -> encodeMove(command.from, command.to)
        SessionCommand.Resend -> encodeResend()
    }

    /**
     * Appends the extension older 0.2.2 hosts ignore after decoding the legacy
     * prefix. New hosts use it for idempotency and semantic rebase.
     */
    fun encodeCommandRequest(requestId: Long, command: SessionCommand, context: SessionCommandContext): ByteArray {
        val out = ArrayList<Byte>(64)
        encodeCommand(command).forEach { out.add(it) }
        out.add(COMMAND_CONTEXT_MARKER)
        out.add(COMMAND_CONTEXT_VERSION)
        putLong(out, requestId)
        putInt(out, context.sessionId)
        listOf(context.subject, context.before, context.after, context.expectedCurrent, context.expectedTarget)
            .forEach { ref ->
                if (ref == null) {
                    out.add(0)
                } else {
                    out.add(1)
                    uuidBytes(ref.climbUuid).forEach { out.add(it) }
                    out.add(ref.angle.coerceIn(0, 255).toByte())
                    out.add(ref.occurrence.coerceIn(0, 255).toByte())
                    out.add(ref.totalAtBase.coerceIn(0, 255).toByte())
                }
            }
        return out.toByteArray()
    }

    // ===== Command decoding (Host reads from Client) =====

    fun decodeCommand(data: ByteArray): SessionCommand? {
        if (data.isEmpty()) return null
        return when (data[0]) {
            CMD_ADD -> {
                if (data.size < 18) return null
                SessionCommand.Add(uuidString(data, 2), data[1].toInt() and 0xFF)
            }
            CMD_REMOVE -> if (data.size < 2) null else SessionCommand.Remove(data[1].toInt() and 0xFF)
            CMD_SET_CURRENT -> if (data.size < 2) null else SessionCommand.SetCurrent(data[1].toInt() and 0xFF)
            CMD_NEXT -> SessionCommand.Next
            CMD_PREV -> SessionCommand.Prev
            CMD_JOIN -> {
                if (data.size < 2) return null
                val nameLen = (data[1].toInt() and 0xFF).coerceAtMost(data.size - 2)
                SessionCommand.Join(data.decodeToString(2, 2 + nameLen))
            }
            CMD_LEAVE -> SessionCommand.Leave
            CMD_MOVE -> if (data.size < 3) null else
                SessionCommand.Move(data[1].toInt() and 0xFF, data[2].toInt() and 0xFF)
            CMD_RESEND -> SessionCommand.Resend
            else -> null
        }
    }

    fun decodeCommandRequest(data: ByteArray): SessionCommandRequest? {
        val command = decodeCommand(data) ?: return null
        val offset = legacyCommandSize(command, data) ?: return SessionCommandRequest(null, command, null)
        if (data.size <= offset || data[offset] != COMMAND_CONTEXT_MARKER) {
            return SessionCommandRequest(null, command, null)
        }
        val reader = Reader(data, offset + 1)
        return runCatching {
            require(reader.byte() == COMMAND_CONTEXT_VERSION)
            val requestId = reader.long()
            val sessionId = reader.int()
            fun readRef(): SessionItemRef? {
                if (reader.byte().toInt() == 0) return null
                val uuid = uuidString(data, reader.skip(16))
                return SessionItemRef(uuid, reader.byte().toInt() and 0xff,
                    reader.byte().toInt() and 0xff, reader.byte().toInt() and 0xff)
            }
            val context = SessionCommandContext(sessionId, readRef(), readRef(), readRef(), readRef(), readRef())
            require(!reader.hasRemaining())
            SessionCommandRequest(requestId, command, context)
        }.getOrNull()
    }

    private fun legacyCommandSize(command: SessionCommand, data: ByteArray): Int? = when (command) {
        is SessionCommand.Add -> 18
        is SessionCommand.Remove, is SessionCommand.SetCurrent -> 2
        SessionCommand.Next, SessionCommand.Prev, SessionCommand.Leave, SessionCommand.Resend -> 1
        is SessionCommand.Move -> 3
        is SessionCommand.Join -> if (data.size >= 2) 2 + (data[1].toInt() and 0xff) else null
    }

    // ===== Event encoding (Host → Client, via notifications) =====

    fun encodeEventAdded(index: Int, climbUuid: String, angle: Int): ByteArray {
        val uuid = uuidBytes(climbUuid)
        val buf = ByteArray(19) // 1 evt + 1 index + 1 angle + 16 uuid
        buf[0] = EVT_ADDED
        buf[1] = index.toByte()
        buf[2] = angle.coerceIn(0, 70).toByte()
        uuid.copyInto(buf, 3)
        return buf
    }

    fun encodeEventRemoved(index: Int): ByteArray = byteArrayOf(EVT_REMOVED, index.toByte())

    fun encodeEventCurrent(index: Int): ByteArray = byteArrayOf(EVT_CURRENT, index.toByte())

    fun encodeEventCleared(): ByteArray = byteArrayOf(EVT_CLEARED)

    fun encodeEventParticipantJoined(name: String): ByteArray = nameEvent(EVT_PARTICIPANT_JOINED, name)

    fun encodeEventParticipantLeft(name: String): ByteArray = nameEvent(EVT_PARTICIPANT_LEFT, name)

    private fun nameEvent(opcode: Byte, name: String): ByteArray {
        val nameBytes = truncated(name, 18)
        val buf = ByteArray(2 + nameBytes.size)
        buf[0] = opcode
        buf[1] = nameBytes.size.toByte()
        nameBytes.copyInto(buf, 2)
        return buf
    }

    /** Two bytes for the countdown: a rest may run to 60 minutes. */
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

    fun encodeEventCommandResult(requestId: Long, result: SessionCommandResult): ByteArray {
        val out = ArrayList<Byte>(10)
        out.add(EVT_COMMAND_RESULT)
        putLong(out, requestId)
        out.add(
            when (result) {
                SessionCommandResult.COMMITTED -> 0
                SessionCommandResult.CONFLICT -> 1
                SessionCommandResult.FAILED -> 2
            }.toByte(),
        )
        return out.toByteArray()
    }

    // ===== Event decoding (Client reads from Host notifications) =====

    fun decodeEvent(data: ByteArray): SessionEvent? {
        if (data.isEmpty()) return null
        return when (data[0]) {
            EVT_ADDED -> {
                if (data.size < 19) return null
                SessionEvent.Added(data[1].toInt() and 0xFF, uuidString(data, 3), data[2].toInt() and 0xFF)
            }
            EVT_REMOVED -> if (data.size < 2) null else SessionEvent.Removed(data[1].toInt() and 0xFF)
            EVT_CURRENT -> if (data.size < 2) null else SessionEvent.CurrentChanged(data[1].toInt() and 0xFF)
            EVT_CLEARED -> SessionEvent.Cleared
            EVT_PARTICIPANT_JOINED -> {
                if (data.size < 2) return null
                val nameLen = (data[1].toInt() and 0xFF).coerceAtMost(data.size - 2)
                SessionEvent.ParticipantJoined(data.decodeToString(2, 2 + nameLen))
            }
            EVT_PARTICIPANT_LEFT -> {
                if (data.size < 2) return null
                val nameLen = (data[1].toInt() and 0xFF).coerceAtMost(data.size - 2)
                SessionEvent.ParticipantLeft(data.decodeToString(2, 2 + nameLen))
            }
            EVT_REST_STARTED -> {
                if (data.size < 4) return null
                val seconds = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                SessionEvent.RestStarted(seconds, data[3].toInt() and 0xFF)
            }
            EVT_REST_ENDED -> SessionEvent.RestEnded
            EVT_COMMAND_RESULT -> if (data.size == 10) {
                val reader = Reader(data, 1)
                val requestId = reader.long()
                when (reader.byte().toInt()) {
                    0 -> SessionEvent.CommandResult(requestId, SessionCommandResult.COMMITTED)
                    1 -> SessionEvent.CommandResult(requestId, SessionCommandResult.CONFLICT)
                    2 -> SessionEvent.CommandResult(requestId, SessionCommandResult.FAILED)
                    else -> null
                }
            } else {
                null
            }
            else -> null
        }
    }

    // ===== Full queue state (paged; GATT read gives page 0) =====

    /** Items per page — sized for a notification (ATT_MTU-3 = 509 on Android). */
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
            uuidBytes(item.climbUuid).copyInto(buf, offset + 1)
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
            QueueItem(uuidString(data, offset + 1), data[offset].toInt() and 0xFF)
        }
        return QueueStatePage(currentIndex, page, pageCount, items)
    }

    // ===== Session info =====

    fun encodeSessionInfo(hostName: String, participantCount: Int, awaitingExplicitSend: Boolean = false): ByteArray {
        val nameBytes = truncated(hostName, 20)
        val buf = ByteArray(3 + nameBytes.size)
        buf[0] = participantCount.toByte()
        buf[1] = nameBytes.size.toByte()
        nameBytes.copyInto(buf, 2)
        buf[2 + nameBytes.size] = if (awaitingExplicitSend) 1 else 0
        return buf
    }

    /** Host leaving: [migrate] hands over (two-byte sentinel), false ends it for everyone. */
    fun encodeSessionEnded(migrate: Boolean): ByteArray =
        if (migrate) byteArrayOf(0, 0) else byteArrayOf(0, 0, SESSION_END_FINAL_FLAG)

    /** Whether a session-ended sentinel means "over", not "hand over". */
    fun isFinalSessionEnd(data: ByteArray): Boolean =
        data.size >= 3 && data[0].toInt() == 0 && data[2] == SESSION_END_FINAL_FLAG

    private const val SESSION_END_FINAL_FLAG: Byte = 1

    fun decodeSessionInfo(data: ByteArray): SessionInfo? {
        if (data.size < 2) return null
        val count = data[0].toInt() and 0xFF
        val nameLen = (data[1].toInt() and 0xFF).coerceAtMost(data.size - 2)
        val name = data.decodeToString(2, 2 + nameLen)
        val flags = data.getOrNull(2 + nameLen)?.toInt() ?: 0
        return SessionInfo(name, count, awaitingExplicitSend = flags and 1 != 0)
    }

    // ===== Participant list =====

    fun encodeParticipantList(names: List<String>): ByteArray {
        val encoded = names.map { truncated(it, 20) }
        val buf = ByteArray(1 + encoded.sumOf { 1 + it.size })
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
            names.add(data.decodeToString(offset + 1, offset + 1 + nameLen))
            offset += 1 + nameLen
        }
        return names
    }

    // ===== Byte helpers =====

    /** Android copies the first N UTF-8 bytes, even mid-character; decoding then substitutes U+FFFD. */
    private fun truncated(text: String, max: Int): ByteArray =
        text.encodeToByteArray().let { if (it.size > max) it.copyOf(max) else it }

    private fun putLong(out: MutableList<Byte>, value: Long) {
        for (shift in 56 downTo 0 step 8) out.add((value shr shift).toByte())
    }

    private fun putInt(out: MutableList<Byte>, value: Int) {
        for (shift in 24 downTo 0 step 8) out.add((value shr shift).toByte())
    }

    /** Big-endian reader that fails (throws) past the end, like ByteBuffer's BufferUnderflowException. */
    private class Reader(private val data: ByteArray, private var position: Int) {
        fun byte(): Byte {
            if (position >= data.size) throw IndexOutOfBoundsException()
            return data[position++]
        }

        fun long(): Long {
            var value = 0L
            repeat(8) { value = (value shl 8) or (byte().toLong() and 0xFF) }
            return value
        }

        fun int(): Int {
            var value = 0
            repeat(4) { value = (value shl 8) or (byte().toInt() and 0xFF) }
            return value
        }

        /** Returns the start of the skipped range. */
        fun skip(count: Int): Int {
            if (position + count > data.size) throw IndexOutOfBoundsException()
            val start = position
            position += count
            return start
        }

        fun hasRemaining(): Boolean = position < data.size
    }

    /** 16 big-endian bytes of a UUID given with or without hyphens. */
    fun uuidBytes(text: String): ByteArray =
        ClimbUuidBytes.parse(text) ?: throw IllegalArgumentException("Invalid UUID string: $text")

    /** Upper case without hyphens: the form Android's decoders produce. */
    private fun uuidString(data: ByteArray, offset: Int): String = ClimbUuidBytes.hex(data, offset).uppercase()
}

/** UUID text ⇄ bytes without java.util.UUID. */
internal object ClimbUuidBytes {
    private const val HEX = "0123456789abcdef"

    /**
     * Canonical 8-4-4-4-12 or 32 plain hex digits, any case. Android's `UUID.fromString` is more
     * lenient with short groups, which no climb id uses.
     */
    fun parse(text: String): ByteArray? {
        val digits = when (text.length) {
            32 -> text
            36 -> {
                if (text[8] != '-' || text[13] != '-' || text[18] != '-' || text[23] != '-') return null
                text.replace("-", "")
            }
            else -> return null
        }
        if (digits.length != 32) return null
        val out = ByteArray(16)
        for (i in 0 until 16) {
            val hi = HEX.indexOf(digits[2 * i].lowercaseChar())
            val lo = HEX.indexOf(digits[2 * i + 1].lowercaseChar())
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 32 lower-case hex digits of [data] at [offset]. */
    fun hex(data: ByteArray, offset: Int): String {
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            val b = data[offset + i].toInt() and 0xFF
            sb.append(HEX[b shr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    /** java.util.UUID.toString(): lower case 8-4-4-4-12. */
    fun hyphenated(data: ByteArray, offset: Int): String {
        val h = hex(data, offset)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}

// ===== Data types (Kotlin-only; Swift sees the facade) =====

internal sealed class SessionCommand {
    data class Add(val climbUuid: String, val angle: Int) : SessionCommand()
    data class Remove(val index: Int) : SessionCommand()
    data class SetCurrent(val index: Int) : SessionCommand()
    data object Next : SessionCommand()
    data object Prev : SessionCommand()
    data class Join(val displayName: String) : SessionCommand()
    data object Leave : SessionCommand()
    data class Move(val from: Int, val to: Int) : SessionCommand()
    data object Resend : SessionCommand()
}

internal sealed class SessionEvent {
    data class Added(val index: Int, val climbUuid: String, val angle: Int) : SessionEvent()
    data class Removed(val index: Int) : SessionEvent()
    data class CurrentChanged(val index: Int) : SessionEvent()
    data object Cleared : SessionEvent()
    data class ParticipantJoined(val name: String) : SessionEvent()
    data class ParticipantLeft(val name: String) : SessionEvent()

    /** Seconds remaining, not a deadline: the two phones' clocks are not synchronised. */
    data class RestStarted(val remainingSeconds: Int, val nextIndex: Int) : SessionEvent()
    data object RestEnded : SessionEvent()
    data class CommandResult(val requestId: Long, val result: SessionCommandResult) : SessionEvent()
}

internal data class SessionItemRef(
    val climbUuid: String,
    val angle: Int,
    val occurrence: Int,
    val totalAtBase: Int,
)

internal data class SessionCommandContext(
    val sessionId: Int,
    val subject: SessionItemRef? = null,
    val before: SessionItemRef? = null,
    val after: SessionItemRef? = null,
    val expectedCurrent: SessionItemRef? = null,
    val expectedTarget: SessionItemRef? = null,
)

internal data class SessionCommandRequest(
    val requestId: Long?,
    val command: SessionCommand,
    val context: SessionCommandContext?,
)

internal enum class SessionCommandResult { COMMITTED, CONFLICT, FAILED }

/** One queue entry as it travels: rest-after metadata stays host-local (Android `QueueItem`). */
internal data class QueueItem(
    val climbUuid: String,
    val angle: Int,
    val restAfterSeconds: Int = 0,
)

internal data class SessionInfo(
    val hostName: String,
    val participantCount: Int,
    val awaitingExplicitSend: Boolean = false,
)
