package com.cruxcoach.android.athlete.force

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** One force reading: kilograms and the device clock in microseconds. */
data class ForceSample(val kg: Double, val micros: Long)

/**
 * Tindeq Progressor BLE protocol, after Tindeq's public API description:
 * one service with a notify "data point" characteristic and a write
 * "control point" characteristic. Commands are single opcode bytes; data
 * frames are tag-length-value, and a weight frame carries repeated pairs of
 * little-endian float32 kilograms and uint32 microseconds. Pure, so the
 * parser can be tested without a device; every malformed frame is dropped
 * instead of throwing.
 */
object ProgressorProtocol {

    val SERVICE: UUID = UUID.fromString("7e4e1701-1ea6-40c9-9dcc-13d34ffead57")
    val DATA_POINT: UUID = UUID.fromString("7e4e1702-1ea6-40c9-9dcc-13d34ffead57")
    val CONTROL_POINT: UUID = UUID.fromString("7e4e1703-1ea6-40c9-9dcc-13d34ffead57")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Advertised name, e.g. "Progressor_1234". */
    const val NAME_PREFIX = "Progressor"

    const val CMD_TARE: Byte = 0x64
    const val CMD_START_WEIGHT: Byte = 0x65
    const val CMD_STOP_WEIGHT: Byte = 0x66
    const val CMD_ENTER_SLEEP: Byte = 0x6E
    const val CMD_GET_BATTERY: Byte = 0x6F

    const val RES_CMD_RESPONSE = 0x00
    const val RES_WEIGHT = 0x01
    const val RES_RFD_PEAK = 0x02
    const val RES_RFD_PEAK_SERIES = 0x03
    const val RES_LOW_POWER = 0x04

    /** Readings outside this range are sensor garbage, not climbing forces. */
    private const val MAX_ABS_KG = 500.0
    private const val SAMPLE_BYTES = 8

    fun command(opcode: Byte): ByteArray = byteArrayOf(opcode)

    sealed interface Frame {
        data class Weight(val samples: List<ForceSample>) : Frame
        /** Reply to a command (e.g. battery voltage); the meaning depends on the last command sent. */
        class CommandResponse(val payload: ByteArray) : Frame
        data object LowPower : Frame
        data class Other(val tag: Int) : Frame
    }

    /**
     * Parses one notification. Returns null for frames too short to carry a
     * header. A length byte longer than the frame is clipped to what arrived;
     * a trailing partial sample is ignored.
     */
    fun parse(bytes: ByteArray?): Frame? {
        if (bytes == null || bytes.size < 2) return null
        val tag = bytes[0].toInt() and 0xFF
        val declared = bytes[1].toInt() and 0xFF
        val available = (bytes.size - 2).coerceAtLeast(0)
        val length = minOf(declared, available)
        val payload = bytes.copyOfRange(2, 2 + length)
        return when (tag) {
            RES_WEIGHT -> Frame.Weight(weightSamples(payload))
            RES_CMD_RESPONSE -> Frame.CommandResponse(payload)
            RES_LOW_POWER -> Frame.LowPower
            else -> Frame.Other(tag)
        }
    }

    private fun weightSamples(payload: ByteArray): List<ForceSample> {
        val count = payload.size / SAMPLE_BYTES
        if (count == 0) return emptyList()
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<ForceSample>(count)
        repeat(count) { i ->
            val kg = buf.getFloat(i * SAMPLE_BYTES).toDouble()
            val micros = buf.getInt(i * SAMPLE_BYTES + 4).toLong() and 0xFFFF_FFFFL
            if (kg.isFinite() && kotlin.math.abs(kg) <= MAX_ABS_KG) out += ForceSample(kg, micros)
        }
        return out
    }

    /** Battery voltage in millivolts from a command response (uint32 LE), or null. */
    fun batteryMillivolts(payload: ByteArray): Int? {
        if (payload.size < 4) return null
        val mv = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
        return mv.takeIf { it in 2_000L..5_000L }?.toInt()
    }
}

/** What a pull shows: peak and the best mean over a window. Pure. */
object ForceAnalysis {

    const val WINDOW_MICROS = 5_000_000L

    fun peak(samples: List<ForceSample>): Double? = samples.maxOfOrNull { it.kg }

    /**
     * Highest mean force over any [windowMicros]-long stretch (time-weighted
     * by sample spacing is unnecessary at the device's steady rate, so it is
     * the plain mean of the samples inside the window). Null when the pull
     * is shorter than the window — a 3-s pull says nothing about 5 s.
     */
    fun bestWindowMean(samples: List<ForceSample>, windowMicros: Long = WINDOW_MICROS): Double? {
        val s = unwrap(samples)
        if (s.size < 2 || s.last().micros - s.first().micros < windowMicros) return null
        var best: Double? = null
        var start = 0
        var sum = 0.0
        for (end in s.indices) {
            sum += s[end].kg
            while (s[end].micros - s[start].micros > windowMicros) {
                sum -= s[start].kg
                start++
            }
            if (s[end].micros - s[start].micros >= windowMicros * 9 / 10) {
                val mean = sum / (end - start + 1)
                if (best == null || mean > best) best = mean
            }
        }
        return best
    }

    /** The device clock is a uint32 of microseconds; undo a wrap inside one recording. */
    fun unwrap(samples: List<ForceSample>): List<ForceSample> {
        if (samples.isEmpty()) return samples
        var offset = 0L
        var last = samples.first().micros
        return samples.map { x ->
            if (x.micros + offset < last - 1_000_000_000L) offset += 0x1_0000_0000L
            val t = x.micros + offset
            last = t
            x.copy(micros = t)
        }
    }
}
