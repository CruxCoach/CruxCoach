package com.cruxcoach.domain.spraywall

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HoldOutlineTest {
    private fun ellipse(points: Int, a: Float = 1.2f, b: Float = 0.7f): List<Float> =
        (0 until points).flatMap { i ->
            val t = 2 * PI * i / points
            listOf((a * cos(t)).toFloat(), (b * sin(t)).toFloat())
        }

    @Test
    fun encodeAndDecodeKeepTheShape() {
        val ring = ellipse(20)
        val back = HoldOutline.decode(HoldOutline.encode(ring))
        assertNotNull(back)
        assertEquals(ring.size, back.size)
        ring.zip(back).forEach { (a, b) -> assertTrue(abs(a - b) <= 1f / 62 + 1e-6f, "$a vs $b") }
    }

    @Test
    fun fitDropsPointsButKeepsTheOutline() {
        val ring = ellipse(120)
        val fitted = HoldOutline.fit(ring)
        assertNotNull(fitted)
        assertTrue(fitted.size / 2 in HoldOutline.MIN_POINTS..HoldOutline.STORED_POINTS, "points ${fitted.size / 2}")
        // Every kept point is still on the ellipse.
        for (i in fitted.indices step 2) {
            val x = fitted[i] / 1.2f
            val y = fitted[i + 1] / 0.7f
            assertEquals(1f, hypot(x, y), 0.01f)
        }
        assertEquals(ellipse(10), HoldOutline.fit(ellipse(10)))
    }

    @Test
    fun badRingsAreNoShape() {
        assertNull(HoldOutline.fit(listOf(0f, 1f, 1f, 0f)))
        assertNull(HoldOutline.fit(listOf(0f, 9f, 1f, 0f, 0f, -1f)))
        assertNull(HoldOutline.decode("!!"))
        assertNull(HoldOutline.decode(""))
    }

    @Test
    fun outlinesTravelWithTheHoldsOverNostr() {
        val holds = listOf(
            SprayHold("w", 1, 1, 0.25f, 0.5f, 0.02f, ellipse(12)),
            SprayHold("w", 1, 2, 0.75f, 0.5f, 0.03f),
        )
        val back = SprayWallNostr.decodeHolds(SprayWallNostr.encodeHolds(holds), "w", 1)
        assertNotNull(back)
        assertEquals(2, back.size)
        val ring = back[0].outline
        assertNotNull(ring)
        assertEquals(24, ring.size)
        assertNull(back[1].outline)
        // A version without outlines still reads, and a broken outline only costs that hold its shape.
        assertEquals(2, SprayWallNostr.decodeHolds("1,2500,5000,200;2,7500,5000,300", "w", 1)?.size)
        assertNull(SprayWallNostr.decodeHolds("1,2500,5000,200,!!", "w", 1)?.single()?.outline)
    }

    @Test
    fun manyHoldsStillFitOneEvent() {
        val holds = (1..SprayHoldEditorState.MAX_HOLDS).map { SprayHold("w", 1, it.toLong(), 0.5f, 0.5f, 0.02f, ellipse(40)) }
        val text = SprayWallNostr.encodeHolds(holds)
        assertTrue(text.length <= 52_000, "length ${text.length}")
        assertEquals(holds.size, SprayWallNostr.decodeHolds(text, "w", 1)?.size)
    }

    @Test
    fun acceptingASuggestionKeepsItsShape() {
        val ring = ellipse(8)
        val state = SprayHoldEditorState(holds = emptyList())
            .withSuggestions(listOf(SpraySuggestion(0.5f, 0.5f, 0.03f, 0.9f, ring)), 1f)
        val accepted = state.accept(state.suggestions.single())
        assertEquals(ring, accepted.holds.single().outline)
        assertEquals(ring, state.acceptVisible().holds.single().outline)
        // Changing only the shape is a change to save.
        val stored = listOf(SprayHold("w", 1, 7, 0.5f, 0.5f, 0.03f))
        val edited = listOf(EditableHold(7, 0.5f, 0.5f, 0.03f, ring))
        assertEquals(1, SprayHoldDiff.between(stored, edited).changed.size)
    }
}
