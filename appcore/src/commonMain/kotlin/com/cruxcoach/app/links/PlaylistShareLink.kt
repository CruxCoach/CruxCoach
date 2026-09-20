package com.cruxcoach.app.links

import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder

/**
 * Shareable link for a list training plan: `https://<host>/l/<payload>`.
 *
 * Byte-exact port of the Android encoder/decoder. Version 1 links
 * (climbs + angles only) stay readable; version 2 adds ordered climb/rest
 * steps plus the list playback defaults:
 *
 * ```
 * [version:1=2][nameLen:1][name]
 * [order:1][advance:1][defaultRest:2][stepCount:1]
 * step climb: [type:1=0][angle:1][uuid:16]
 * step rest:  [type:1=1][seconds:2]
 * ```
 *
 * Every integer is big-endian, as `java.nio.ByteBuffer` writes them.
 */
object PlaylistShareLink {

    const val VERSION_CLIMBS_ONLY = 1
    const val VERSION_TRAINING_PLAN = 2
    private const val STEP_CLIMB = 0
    private const val STEP_REST = 1
    const val MAX_NAME_BYTES = 60
    const val MAX_STEPS = 100
    const val MAX_REST_SECONDS = 3_600
    const val MAX_ANGLE = 90

    data class SharedClimb(val climbUuid: String, val angle: Int)

    sealed class SharedStep {
        data class Climb(val climbUuid: String, val angle: Int) : SharedStep()
        data class Rest(val seconds: Int) : SharedStep()
    }

    data class SharedPlaylist(
        val name: String,
        val steps: List<SharedStep>,
        val order: ListPlaybackOrder = ListPlaybackOrder.LIST,
        val advance: ListPlaybackAdvance = ListPlaybackAdvance.MANUAL,
        val defaultRestSeconds: Int = 0,
    ) {
        val climbs: List<SharedClimb>
            get() = steps.mapNotNull { step -> (step as? SharedStep.Climb)?.let { SharedClimb(it.climbUuid, it.angle) } }
    }

    /** Legacy climbs-only encoder. Null when nothing is encodable. */
    fun build(name: String, climbs: List<SharedClimb>, host: String = DEFAULT_APP_LINK_HOST): String? {
        val encodable = climbs.mapNotNull { climb ->
            val uuid = parseUuidBytes(climb.climbUuid) ?: return@mapNotNull null
            uuid to climb.angle.coerceIn(0, MAX_ANGLE)
        }.take(MAX_STEPS)
        if (encodable.isEmpty()) return null

        val nameBytes = truncateName(name)
        val out = ByteWriter(3 + nameBytes.size + encodable.size * 17)
        out.put(VERSION_CLIMBS_ONLY)
        out.put(nameBytes.size)
        out.put(nameBytes)
        out.put(encodable.size)
        encodable.forEach { (uuid, angle) ->
            out.put(angle)
            out.put(uuid)
        }
        return linkFor(out.toByteArray(), host)
    }

    /** Full-fidelity training-plan encoder. Invalid climb UUIDs are skipped. */
    fun buildPlan(
        name: String,
        steps: List<SharedStep>,
        order: ListPlaybackOrder,
        advance: ListPlaybackAdvance,
        defaultRestSeconds: Int,
        host: String = DEFAULT_APP_LINK_HOST,
    ): String? {
        val encodable = steps.mapNotNull { step ->
            when (step) {
                is SharedStep.Climb -> {
                    val uuid = parseUuidBytes(step.climbUuid) ?: return@mapNotNull null
                    EncodedStep(uuid, step.angle.coerceIn(0, MAX_ANGLE), null)
                }
                is SharedStep.Rest -> EncodedStep(null, 0, step.seconds.coerceIn(0, MAX_REST_SECONDS))
            }
        }.take(MAX_STEPS)
        if (encodable.none { it.uuid != null }) return null

        val nameBytes = truncateName(name)
        val stepBytes = encodable.sumOf { if (it.uuid != null) 18 else 3 }
        val out = ByteWriter(7 + nameBytes.size + stepBytes)
        out.put(VERSION_TRAINING_PLAN)
        out.put(nameBytes.size)
        out.put(nameBytes)
        out.put(
            when (order) {
                ListPlaybackOrder.LIST -> 0
                ListPlaybackOrder.SHUFFLE -> 1
            }
        )
        out.put(
            when (advance) {
                ListPlaybackAdvance.MANUAL -> 0
                ListPlaybackAdvance.AFTER_SEND -> 1
                ListPlaybackAdvance.AFTER_LOG -> 2
            }
        )
        out.putShort(defaultRestSeconds.coerceIn(0, MAX_REST_SECONDS))
        out.put(encodable.size)
        encodable.forEach { step ->
            val uuid = step.uuid
            if (uuid != null) {
                out.put(STEP_CLIMB)
                out.put(step.angle)
                out.put(uuid)
            } else {
                out.put(STEP_REST)
                out.putShort(step.restSeconds ?: 0)
            }
        }
        return linkFor(out.toByteArray(), host)
    }

    /** Null on malformed or unsupported payloads. */
    fun parse(payload: String): SharedPlaylist? {
        val bytes = Base64Url.decode(payload) ?: return null
        if (bytes.size < 3) return null
        val reader = ByteReader(bytes)
        return when (reader.byte()) {
            VERSION_CLIMBS_ONLY -> parseV1(reader)
            VERSION_TRAINING_PLAN -> parseV2(reader)
            else -> null
        }
    }

    private fun parseV1(reader: ByteReader): SharedPlaylist? {
        val name = readName(reader) ?: return null
        if (reader.remaining < 1) return null
        val count = reader.byte()
        if (count == 0 || count > MAX_STEPS || reader.remaining != count * 17) return null
        val steps = ArrayList<SharedStep>(count)
        repeat(count) {
            val angle = reader.byte()
            if (angle > MAX_ANGLE) return null
            steps.add(SharedStep.Climb(formatUuid(reader.bytes(16)), angle))
        }
        return SharedPlaylist(name, steps)
    }

    private fun parseV2(reader: ByteReader): SharedPlaylist? {
        val name = readName(reader) ?: return null
        if (reader.remaining < 5) return null
        val order = when (reader.byte()) {
            0 -> ListPlaybackOrder.LIST
            1 -> ListPlaybackOrder.SHUFFLE
            else -> return null
        }
        val advance = when (reader.byte()) {
            0 -> ListPlaybackAdvance.MANUAL
            1 -> ListPlaybackAdvance.AFTER_SEND
            2 -> ListPlaybackAdvance.AFTER_LOG
            else -> return null
        }
        val defaultRest = reader.short()
        if (defaultRest > MAX_REST_SECONDS) return null
        val count = reader.byte()
        if (count == 0 || count > MAX_STEPS) return null
        val steps = ArrayList<SharedStep>(count)
        repeat(count) {
            if (reader.remaining < 1) return null
            when (reader.byte()) {
                STEP_CLIMB -> {
                    if (reader.remaining < 17) return null
                    val angle = reader.byte()
                    if (angle > MAX_ANGLE) return null
                    steps.add(SharedStep.Climb(formatUuid(reader.bytes(16)), angle))
                }
                STEP_REST -> {
                    if (reader.remaining < 2) return null
                    val seconds = reader.short()
                    if (seconds > MAX_REST_SECONDS) return null
                    steps.add(SharedStep.Rest(seconds))
                }
                else -> return null
            }
        }
        // Trailing bytes mean the frame is not the one we wrote.
        if (reader.remaining > 0 || steps.none { it is SharedStep.Climb }) return null
        return SharedPlaylist(name, steps, order, advance, defaultRest)
    }

    private fun readName(reader: ByteReader): String? {
        if (reader.remaining < 1) return null
        val length = reader.byte()
        if (length > MAX_NAME_BYTES || reader.remaining < length) return null
        return reader.bytes(length).decodeToString()
    }

    /** UTF-8 truncation on a code-point boundary, never mid-character. */
    internal fun truncateName(name: String): ByteArray {
        val out = ArrayList<Byte>(MAX_NAME_BYTES)
        var index = 0
        while (index < name.length) {
            val high = name[index]
            val charCount = if (high.isHighSurrogate() && index + 1 < name.length &&
                name[index + 1].isLowSurrogate()
            ) 2 else 1
            val bytes = name.substring(index, index + charCount).encodeToByteArray()
            if (out.size + bytes.size > MAX_NAME_BYTES) break
            bytes.forEach { out.add(it) }
            index += charCount
        }
        return out.toByteArray()
    }

    private fun linkFor(bytes: ByteArray, host: String): String = "https://$host/l/${Base64Url.encode(bytes)}"

    /** Bare or hyphenated 32 hex digits → the 16 raw bytes; null otherwise. */
    internal fun parseUuidBytes(raw: String): ByteArray? {
        val bare = raw.replace("-", "")
        if (bare.length != 32) return null
        val out = ByteArray(16)
        for (i in 0 until 16) {
            val hi = hexValue(bare[i * 2])
            val lo = hexValue(bare[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Lowercase 8-4-4-4-12, the spelling `java.util.UUID.toString()` produces. */
    internal fun formatUuid(bytes: ByteArray): String {
        val hex = StringBuilder(36)
        for ((index, b) in bytes.withIndex()) {
            if (index == 4 || index == 6 || index == 8 || index == 10) hex.append('-')
            val value = b.toInt() and 0xFF
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return hex.toString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private const val HEX = "0123456789abcdef"

    private class EncodedStep(val uuid: ByteArray?, val angle: Int, val restSeconds: Int?)
}

/** Forks override the host; it drives both the encoder and the parser. */
const val DEFAULT_APP_LINK_HOST = "cruxcoach.org"

/** Big-endian writer with the fixed capacity the frame was sized for. */
internal class ByteWriter(capacity: Int) {
    private val buffer = ByteArray(capacity)
    private var position = 0

    fun put(value: Int) {
        buffer[position++] = value.toByte()
    }

    fun put(bytes: ByteArray) {
        bytes.copyInto(buffer, position)
        position += bytes.size
    }

    fun putShort(value: Int) {
        buffer[position++] = ((value ushr 8) and 0xFF).toByte()
        buffer[position++] = (value and 0xFF).toByte()
    }

    fun toByteArray(): ByteArray = if (position == buffer.size) buffer else buffer.copyOf(position)
}

internal class ByteReader(private val buffer: ByteArray) {
    private var position = 0

    val remaining: Int get() = buffer.size - position

    fun byte(): Int = buffer[position++].toInt() and 0xFF

    fun short(): Int = (byte() shl 8) or byte()

    fun bytes(count: Int): ByteArray {
        val out = buffer.copyOfRange(position, position + count)
        position += count
        return out
    }
}
