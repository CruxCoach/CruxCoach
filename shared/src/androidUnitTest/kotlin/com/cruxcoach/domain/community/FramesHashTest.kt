package com.cruxcoach.domain.community

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FramesHashTest {

    @Test
    fun deterministic_for_same_input() {
        val frames = "p1164r12p1233r13p1392r14"
        val a = FramesHash.of(frames, layoutId = 1)
        val b = FramesHash.of(frames, layoutId = 1)
        assertEquals(a, b)
    }

    @Test
    fun order_independent() {
        val a = FramesHash.of("p1164r12p1233r13p1392r14", layoutId = 1)
        val b = FramesHash.of("p1392r14p1164r12p1233r13", layoutId = 1)
        assertEquals(a, b, "frames_hash must be canonical (sorted by placement_id)")
    }

    @Test
    fun layoutId_is_load_bearing() {
        val frames = "p1164r12p1233r13p1392r14"
        val onLayout1 = FramesHash.of(frames, layoutId = 1)
        val onLayout2 = FramesHash.of(frames, layoutId = 2)
        assertNotEquals(
            onLayout1,
            onLayout2,
            "Same placements on different layouts must hash to different values",
        )
    }

    @Test
    fun different_holds_different_hash() {
        val a = FramesHash.of("p1164r12p1392r14", layoutId = 1)
        val b = FramesHash.of("p1164r13p1392r14", layoutId = 1) // role differs
        assertNotEquals(a, b)
    }

    @Test
    fun produces_hex_sha256() {
        val out = FramesHash.of("p1164r12p1392r14", layoutId = 1)
        assertEquals(64, out.length, "SHA-256 hex is 64 chars")
        assertEquals(true, out.all { it in "0123456789abcdef" })
    }
}
