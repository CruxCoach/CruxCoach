package com.cruxcoach.app.foodvision

import com.cruxcoach.athlete.logic.VisionCapability
import com.cruxcoach.athlete.logic.VisionSupport
import com.cruxcoach.athlete.logic.VisionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionDeviceTest {
    private fun phone(ram: Long, dot: Boolean = true, fp16: Boolean = true, cores: Int = 6, engine: Boolean = true) =
        IosDeviceFacts(physicalMemoryBytes = ram, dotProduct = dot, halfPrecision = fp16, activeProcessors = cores, engineBuilt = engine)

    @Test
    fun `iPhones get Android's tiers by memory and instruction set`() {
        // ProcessInfo.physicalMemory of a 6 GB and an 8 GB iPhone, and a 4 GB one.
        assertEquals(VisionSupport.Supported(VisionTier.SMALL), VisionDevice.assess(phone(5_920_000_000)))
        assertEquals(VisionModels.SMALL, VisionDevice.target(phone(5_920_000_000)))
        assertEquals(VisionModels.LARGE, VisionDevice.target(phone(7_980_000_000)))
        assertEquals(VisionDevice.LOW_RAM, VisionDevice.supportCode(phone(3_900_000_000)))
        assertNull(VisionDevice.target(phone(3_900_000_000)))
        // The engine is compiled for dot product and FP16: a missing feature never loads it.
        assertEquals(VisionDevice.CPU, VisionDevice.supportCode(phone(7_980_000_000, dot = false)))
        assertEquals(VisionDevice.CPU, VisionDevice.supportCode(phone(7_980_000_000, fp16 = false)))
        assertEquals(setOf("asimddp", "asimdhp"), VisionDevice.facts(phone(1)).cpuFeatures)
        assertTrue(VisionDevice.facts(phone(1)).arm64)
        assertFalse(VisionDevice.facts(phone(1)).lowRamDevice)
    }

    @Test
    fun `a build without the engine offers no model on any phone`() {
        val big = phone(7_980_000_000, engine = false)
        assertEquals(VisionDevice.NO_ENGINE, VisionDevice.supportCode(big))
        assertNull(VisionDevice.target(big))
        assertEquals(VisionDevice.SUPPORTED, VisionDevice.supportCode(big.copy(engineBuilt = true)))
    }

    @Test
    fun `threads follow Android's rule of about the big cores`() {
        assertEquals(3, VisionDevice.inferenceThreads(6))
        assertEquals(2, VisionDevice.inferenceThreads(4))
        assertEquals(2, VisionDevice.inferenceThreads(2))
        assertEquals(4, VisionDevice.inferenceThreads(8))
        assertEquals(4, VisionDevice.inferenceThreads(10))
    }

    @Test
    fun `headroom leaves out the memory-mapped weights`() {
        val margin = 300L * 1024 * 1024
        assertEquals(2_400_000_000L - 1_280_835_840L + margin, VisionDevice.headroomBytes(VisionModels.SMALL))
        assertEquals(3_900_000_000L - 2_740_937_888L + margin, VisionDevice.headroomBytes(VisionModels.LARGE))
    }

    @Test
    fun `the engine envelope is read like Android's VisionOutcome`() {
        val ok = VisionOutcome.parse(
            """{"ok":true,"error":"","text":"{\"items\":[{\"name_de\":\"Käse\"}]}","promptTokens":314,"outputTokens":70,"imageMs":42302,"generateMs":2055}""",
        )
        assertTrue(ok.ok)
        assertNull(ok.error)
        assertEquals("""{"items":[{"name_de":"Käse"}]}""", ok.text)
        assertEquals(314, ok.promptTokens)
        assertEquals(70, ok.outputTokens)
        assertEquals(42_302L, ok.imageMs)
        assertEquals(2_055L, ok.generateMs)

        val failed = VisionOutcome.parse("""{"ok":false,"error":"invalid_image"}""")
        assertFalse(failed.ok)
        assertEquals("invalid_image", failed.error)
        assertEquals("", failed.text)

        assertEquals(VisionOutcome(false, VisionOutcome.BAD_REPLY, ""), VisionOutcome.parse("not json"))
        assertEquals(VisionOutcome(false, VisionOutcome.BAD_REPLY, ""), VisionOutcome.parse("[1,2]"))
        assertFalse(VisionOutcome.parse("{}").ok)
    }

    @Test
    fun `the probe estimate uses the shared rule`() {
        // The CI simulator run above: 42 s for the image, 2 s for 70 tokens.
        val estimate = VisionCapability.estimateFullRunMs(42_302, 2_055, 70)
        assertEquals(42_302L + (2_055.0 / 70 * 160).toLong(), estimate)
        assertEquals(VisionCapability.ProbeVerdict.SLOW, VisionCapability.verdict(estimate))
    }
}
