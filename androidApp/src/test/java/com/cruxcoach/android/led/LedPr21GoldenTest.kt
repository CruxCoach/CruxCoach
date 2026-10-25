package com.cruxcoach.android.led

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The owner's rule for the contributed effects: integrate them, but they must look
 * exactly as contributed. `led/pr21-led-golden.txt` was written by
 * [LedGoldenFixtures] on the PR #21 commit f949f16fd: per effect and fixed input
 * frame a hash of every frame for a fixed set of ticks, the pacing table, and the
 * four-role frames in full. Any change to colours, colour order, brightness steps,
 * periods, the chaotic sequence, the rainbow direction or the pacing fails here.
 */
class LedPr21GoldenTest {

    @Test
    fun theEffectsAreBitForBitTheContributedOnes() {
        val expected = requireNotNull(javaClass.getResource("/led/pr21-led-golden.txt")) {
            "golden file missing"
        }.readText()
        val engine = LedAnimationEngine(FakeLedFrameSink(), MutableStateFlow(LedEffectSettings()), UnconfinedTestDispatcher())
        val actual = LedGoldenFixtures.golden { animation, leds -> engine.frameIntervalMillis(animation, leds) }
        assertEquals(expected, actual)
    }
}
