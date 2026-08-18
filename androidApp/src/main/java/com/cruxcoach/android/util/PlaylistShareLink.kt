package com.cruxcoach.android.util

import com.cruxcoach.android.BuildConfig
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/**
 * Shareable app link for a list training plan: `https://<host>/l/<payload>`.
 *
 * Version 1 links (climbs + angles only) remain readable. Version 2 adds
 * ordered climb/rest steps plus the list playback defaults:
 *
 * ```
 * [version:1=2][nameLen:1][name]
 * [order:1][advance:1][defaultRest:2][stepCount:1]
 * step climb: [type:1=0][angle:1][uuid:16]
 * step rest:  [type:1=1][seconds:2]
 * ```
 */
object PlaylistShareLink {

    private const val VERSION_CLIMBS_ONLY = 1
    private const val VERSION_TRAINING_PLAN = 2
    private const val STEP_CLIMB = 0
    private const val STEP_REST = 1
    private const val MAX_NAME_BYTES = 60
    private const val MAX_STEPS = 100
    private const val MAX_REST_SECONDS = 3_600
    private const val MAX_ANGLE = 90

    data class SharedPlaylist(
        val name: String,
        val steps: List<SharedStep>,
        val order: ListPlaybackOrder = ListPlaybackOrder.LIST,
        val advance: ListPlaybackAdvance = ListPlaybackAdvance.MANUAL,
        val defaultRestSeconds: Int = 0,
    ) {
        val climbs: List<SharedClimb>
            get() = steps.mapNotNull { step ->
                (step as? SharedStep.Climb)?.let { SharedClimb(it.climbUuid, it.angle) }
            }
    }

    data class SharedClimb(val climbUuid: String, val angle: Int)

    sealed interface SharedStep {
        data class Climb(val climbUuid: String, val angle: Int) : SharedStep
        data class Rest(val seconds: Int) : SharedStep
    }

    /** Legacy climbs-only encoder kept for call-site and test compatibility. */
    fun build(name: String, climbs: List<SharedClimb>): String? {
        val encodable = climbs.mapNotNull { climb ->
            val uuid = parseUuid(climb.climbUuid) ?: return@mapNotNull null
            uuid to climb.angle.coerceIn(0, MAX_ANGLE)
        }.take(MAX_STEPS)
        if (encodable.isEmpty()) return null

        val nameBytes = truncateName(name)
        val buffer = ByteBuffer.allocate(3 + nameBytes.size + encodable.size * 17)
        buffer.put(VERSION_CLIMBS_ONLY.toByte())
        buffer.put(nameBytes.size.toByte())
        buffer.put(nameBytes)
        buffer.put(encodable.size.toByte())
        encodable.forEach { (uuid, angle) ->
            buffer.put(angle.toByte())
            buffer.putLong(uuid.mostSignificantBits)
            buffer.putLong(uuid.leastSignificantBits)
        }
        return linkFor(buffer.array())
    }

    /** Full-fidelity training-plan encoder. Invalid climb UUIDs are skipped. */
    fun buildPlan(
        name: String,
        steps: List<SharedStep>,
        order: ListPlaybackOrder,
        advance: ListPlaybackAdvance,
        defaultRestSeconds: Int,
    ): String? {
        val encodable = steps.mapNotNull { step ->
            when (step) {
                is SharedStep.Climb -> {
                    val uuid = parseUuid(step.climbUuid) ?: return@mapNotNull null
                    EncodedStep.Climb(uuid, step.angle.coerceIn(0, MAX_ANGLE))
                }
                is SharedStep.Rest -> EncodedStep.Rest(step.seconds.coerceIn(0, MAX_REST_SECONDS))
            }
        }.take(MAX_STEPS)
        if (encodable.none { it is EncodedStep.Climb }) return null

        val nameBytes = truncateName(name)
        val stepBytes = encodable.sumOf { if (it is EncodedStep.Climb) 18 else 3 }
        val buffer = ByteBuffer.allocate(7 + nameBytes.size + stepBytes)
        buffer.put(VERSION_TRAINING_PLAN.toByte())
        buffer.put(nameBytes.size.toByte())
        buffer.put(nameBytes)
        buffer.put(
            when (order) {
                ListPlaybackOrder.LIST -> 0
                ListPlaybackOrder.SHUFFLE -> 1
            }.toByte()
        )
        buffer.put(
            when (advance) {
                ListPlaybackAdvance.MANUAL -> 0
                ListPlaybackAdvance.AFTER_SEND -> 1
                ListPlaybackAdvance.AFTER_LOG -> 2
            }.toByte()
        )
        buffer.putShort(defaultRestSeconds.coerceIn(0, MAX_REST_SECONDS).toShort())
        buffer.put(encodable.size.toByte())
        encodable.forEach { step ->
            when (step) {
                is EncodedStep.Climb -> {
                    buffer.put(STEP_CLIMB.toByte())
                    buffer.put(step.angle.toByte())
                    buffer.putLong(step.uuid.mostSignificantBits)
                    buffer.putLong(step.uuid.leastSignificantBits)
                }
                is EncodedStep.Rest -> {
                    buffer.put(STEP_REST.toByte())
                    buffer.putShort(step.seconds.toShort())
                }
            }
        }
        return linkFor(buffer.array())
    }

    /** Null on malformed or unsupported payloads. */
    fun parse(payload: String): SharedPlaylist? {
        val bytes = try {
            Base64.getUrlDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size < 3) return null
        val buffer = ByteBuffer.wrap(bytes)
        return when (buffer.get().toInt() and 0xFF) {
            VERSION_CLIMBS_ONLY -> parseV1(buffer)
            VERSION_TRAINING_PLAN -> parseV2(buffer)
            else -> null
        }
    }

    private fun parseV1(buffer: ByteBuffer): SharedPlaylist? {
        val name = readName(buffer) ?: return null
        if (buffer.remaining() < 1) return null
        val count = buffer.get().toInt() and 0xFF
        if (count == 0 || count > MAX_STEPS || buffer.remaining() != count * 17) return null
        val steps = (0 until count).map {
            val angle = buffer.get().toInt() and 0xFF
            if (angle > MAX_ANGLE) return null
            SharedStep.Climb(UUID(buffer.long, buffer.long).toString().lowercase(), angle)
        }
        return SharedPlaylist(name, steps)
    }

    private fun parseV2(buffer: ByteBuffer): SharedPlaylist? {
        val name = readName(buffer) ?: return null
        if (buffer.remaining() < 5) return null
        val order = when (buffer.get().toInt() and 0xFF) {
            0 -> ListPlaybackOrder.LIST
            1 -> ListPlaybackOrder.SHUFFLE
            else -> return null
        }
        val advance = when (buffer.get().toInt() and 0xFF) {
            0 -> ListPlaybackAdvance.MANUAL
            1 -> ListPlaybackAdvance.AFTER_SEND
            2 -> ListPlaybackAdvance.AFTER_LOG
            else -> return null
        }
        val defaultRest = buffer.short.toInt() and 0xFFFF
        if (defaultRest > MAX_REST_SECONDS) return null
        val count = buffer.get().toInt() and 0xFF
        if (count == 0 || count > MAX_STEPS) return null
        val steps = ArrayList<SharedStep>(count)
        repeat(count) {
            if (!buffer.hasRemaining()) return null
            when (buffer.get().toInt() and 0xFF) {
                STEP_CLIMB -> {
                    if (buffer.remaining() < 17) return null
                    val angle = buffer.get().toInt() and 0xFF
                    if (angle > MAX_ANGLE) return null
                    steps.add(SharedStep.Climb(UUID(buffer.long, buffer.long).toString().lowercase(), angle))
                }
                STEP_REST -> {
                    if (buffer.remaining() < 2) return null
                    val seconds = buffer.short.toInt() and 0xFFFF
                    if (seconds > MAX_REST_SECONDS) return null
                    steps.add(SharedStep.Rest(seconds))
                }
                else -> return null
            }
        }
        if (buffer.hasRemaining() || steps.none { it is SharedStep.Climb }) return null
        return SharedPlaylist(name, steps, order, advance, defaultRest)
    }

    private fun readName(buffer: ByteBuffer): String? {
        if (!buffer.hasRemaining()) return null
        val length = buffer.get().toInt() and 0xFF
        if (length > MAX_NAME_BYTES || buffer.remaining() < length) return null
        val bytes = ByteArray(length).also { buffer.get(it) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun truncateName(name: String): ByteArray {
        val out = ByteArrayOutputStream(MAX_NAME_BYTES)
        var index = 0
        while (index < name.length) {
            val codePoint = name.codePointAt(index)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            if (out.size() + bytes.size > MAX_NAME_BYTES) break
            out.write(bytes)
            index += Character.charCount(codePoint)
        }
        return out.toByteArray()
    }

    private fun linkFor(bytes: ByteArray): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return "https://${BuildConfig.APP_LINK_HOST}/l/$payload"
    }

    private fun parseUuid(raw: String): UUID? {
        val bare = raw.replace("-", "")
        if (bare.length != 32 || !bare.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val hyphenated = "${bare.substring(0, 8)}-${bare.substring(8, 12)}-" +
            "${bare.substring(12, 16)}-${bare.substring(16, 20)}-${bare.substring(20)}"
        return runCatching { UUID.fromString(hyphenated) }.getOrNull()
    }

    private sealed interface EncodedStep {
        data class Climb(val uuid: UUID, val angle: Int) : EncodedStep
        data class Rest(val seconds: Int) : EncodedStep
    }
}
