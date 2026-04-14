package com.cruxcoach.domain.board

import kotlin.test.Test
import kotlin.test.assertEquals

class FramesBinaryCodecTest {

    private fun roundtrip(text: String) {
        val encoded = FramesBinaryCodec.encode(text)
        val decoded = FramesBinaryCodec.decode(encoded)
        assertEquals(text, decoded, "Roundtrip failed for: $text")
    }

    @Test
    fun emptyString() {
        roundtrip("")
        assertEquals(0, FramesBinaryCodec.encode("").size)
    }

    @Test
    fun singleHold() {
        roundtrip("p1234r14")
    }

    @Test
    fun boulderTenHolds() {
        roundtrip("p1091r15p1096r15p1163r12p1200r14p1205r14p1280r13p1350r12p1400r15p1450r14p1500r12")
    }

    @Test
    fun routeMultiFrameWithRemovals() {
        roundtrip("p100r42p200r45,x100p300r43,x200p400r44")
    }

    @Test
    fun routeTwoFrames() {
        roundtrip("p500r42p600r43,p700r44p800r45")
    }

    @Test
    fun maxPlacementId() {
        // Max placement ID around 16000
        roundtrip("p16000r12p15999r15")
    }

    @Test
    fun placementIdZero() {
        roundtrip("p0r12")
    }

    @Test
    fun allBoulderRoleIds() {
        roundtrip("p100r12p200r13p300r14p400r15")
    }

    @Test
    fun allRouteRoleIds() {
        roundtrip("p100r42p200r43p300r44p400r45")
    }

    @Test
    fun largeClimb80Holds() {
        val holds = (1..80).joinToString("") { "p${1000 + it}r${12 + (it % 4)}" }
        roundtrip(holds)
    }

    @Test
    fun multiFrameFiveFrames() {
        val frames = (0..4).joinToString(",") { i ->
            val base = 100 + i * 100
            if (i == 0) "p${base}r42p${base + 50}r43"
            else "x${base - 100}p${base}r42p${base + 50}r43"
        }
        roundtrip(frames)
    }

    @Test
    fun singleFrameCompactSize() {
        // 5 holds × 3 bytes = 15 bytes (vs ~40 chars TEXT)
        val text = "p1091r15p1096r15p1163r12p1200r14p1205r14"
        val encoded = FramesBinaryCodec.encode(text)
        assertEquals(15, encoded.size)
    }

    @Test
    fun multiFrameHasHeader() {
        val text = "p100r42p200r45,x100p300r43"
        val encoded = FramesBinaryCodec.encode(text)
        // Header: 1 (magic) + 1 (count) + 2*2 (frame lengths) = 6
        // Frame 1: 2 holds = 6 bytes, Frame 2: 2 entries = 6 bytes
        assertEquals(18, encoded.size)
        assertEquals(0xFF.toByte(), encoded[0]) // magic byte
        assertEquals(2.toByte(), encoded[1]) // frame count
    }
}
