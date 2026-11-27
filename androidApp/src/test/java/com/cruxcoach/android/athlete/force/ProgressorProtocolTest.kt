package com.cruxcoach.android.athlete.force

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressorProtocolTest {

    private fun weightFrame(vararg samples: Pair<Float, Long>, declaredLength: Int? = null): ByteArray {
        val payload = ByteBuffer.allocate(samples.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { (kg, us) -> payload.putFloat(kg); payload.putInt(us.toInt()) }
        val body = payload.array()
        return byteArrayOf(ProgressorProtocol.RES_WEIGHT.toByte(), (declaredLength ?: body.size).toByte()) + body
    }

    @Test
    fun `weight frame yields every sample in order`() {
        val frame = ProgressorProtocol.parse(weightFrame(12.5f to 1_000L, 13.0f to 13_500L, 13.25f to 26_000L))
        assertIs<ProgressorProtocol.Frame.Weight>(frame)
        assertEquals(listOf(12.5, 13.0, 13.25), frame.samples.map { it.kg })
        assertEquals(listOf(1_000L, 13_500L, 26_000L), frame.samples.map { it.micros })
    }

    @Test
    fun `timestamps are unsigned`() {
        val frame = ProgressorProtocol.parse(weightFrame(5f to 0xFFFF_FF00L))
        assertIs<ProgressorProtocol.Frame.Weight>(frame)
        assertEquals(0xFFFF_FF00L, frame.samples.single().micros)
    }

    @Test
    fun `truncated frames keep only whole samples`() {
        val full = weightFrame(10f to 1L, 11f to 2L)
        val cut = full.copyOf(full.size - 3) // second sample incomplete, length byte still says 16
        val frame = ProgressorProtocol.parse(cut)
        assertIs<ProgressorProtocol.Frame.Weight>(frame)
        assertEquals(listOf(10.0), frame.samples.map { it.kg })
    }

    @Test
    fun `too short or empty input is ignored`() {
        assertNull(ProgressorProtocol.parse(null))
        assertNull(ProgressorProtocol.parse(byteArrayOf()))
        assertNull(ProgressorProtocol.parse(byteArrayOf(0x01)))
        val empty = ProgressorProtocol.parse(byteArrayOf(0x01, 0x00))
        assertIs<ProgressorProtocol.Frame.Weight>(empty)
        assertTrue(empty.samples.isEmpty())
    }

    @Test
    fun `garbage readings are dropped`() {
        val frame = ProgressorProtocol.parse(weightFrame(Float.NaN to 1L, 9_999f to 2L, 20f to 3L, Float.POSITIVE_INFINITY to 4L))
        assertIs<ProgressorProtocol.Frame.Weight>(frame)
        assertEquals(listOf(20.0), frame.samples.map { it.kg })
    }

    @Test
    fun `other tags are recognised`() {
        assertIs<ProgressorProtocol.Frame.LowPower>(ProgressorProtocol.parse(byteArrayOf(0x04, 0x00)))
        assertEquals(ProgressorProtocol.Frame.Other(0x7F), ProgressorProtocol.parse(byteArrayOf(0x7F, 0x00)))
        val reply = ProgressorProtocol.parse(byteArrayOf(0x00, 0x04, 0x68, 0x10, 0x00, 0x00))
        assertIs<ProgressorProtocol.Frame.CommandResponse>(reply)
        assertEquals(4200, ProgressorProtocol.batteryMillivolts(reply.payload))
    }

    @Test
    fun `battery voltage outside a plausible range is not reported`() {
        assertNull(ProgressorProtocol.batteryMillivolts(byteArrayOf(0x01, 0x00, 0x00, 0x00)))
        assertNull(ProgressorProtocol.batteryMillivolts(byteArrayOf(0x68, 0x10)))
    }

    @Test
    fun `commands are single opcode bytes`() {
        assertEquals(listOf<Byte>(0x64), ProgressorProtocol.command(ProgressorProtocol.CMD_TARE).toList())
        assertEquals(listOf<Byte>(0x65), ProgressorProtocol.command(ProgressorProtocol.CMD_START_WEIGHT).toList())
        assertEquals(listOf<Byte>(0x66), ProgressorProtocol.command(ProgressorProtocol.CMD_STOP_WEIGHT).toList())
    }

    @Test
    fun `peak and best five second mean of a pull`() {
        // 80 Hz for 7 s: ramp to 40 kg in the first second, hold 40, dip to 30 in the last second.
        val samples = (0 until 560).map { i ->
            val t = i * 12_500L
            val kg = when {
                t < 1_000_000L -> 40.0 * t / 1_000_000L
                t > 6_000_000L -> 30.0
                else -> 40.0
            }
            ForceSample(kg, t)
        }
        assertEquals(40.0, ForceAnalysis.peak(samples))
        val mean = ForceAnalysis.bestWindowMean(samples)!!
        assertTrue(mean in 39.5..40.0, "mean $mean")
    }

    @Test
    fun `a pull shorter than the window gives no mean`() {
        val samples = (0 until 200).map { ForceSample(30.0, it * 12_500L) } // 2.5 s
        assertNull(ForceAnalysis.bestWindowMean(samples))
        assertEquals(30.0, ForceAnalysis.peak(samples))
    }

    @Test
    fun `clock wrap inside a recording is undone`() {
        val start = 0xFFFF_FFFFL - 1_000_000L
        val raw = (0 until 600).map { i -> ForceSample(25.0, (start + i * 12_500L) and 0xFFFF_FFFFL) }
        val unwrapped = ForceAnalysis.unwrap(raw)
        assertTrue(unwrapped.zipWithNext().all { (a, b) -> b.micros > a.micros })
        assertEquals(25.0, ForceAnalysis.bestWindowMean(raw))
    }
}
