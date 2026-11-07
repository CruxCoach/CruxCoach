package com.cruxcoach.android.boardscan

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The detector's promise: small spots of LED colour on a grey board are found where they are, with their colour;
 * a large painted area is not mistaken for light.
 */
class LedDetectorTest {

    private val w = 240
    private val h = 240

    private fun argb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    private fun frame(): IntArray = IntArray(w * h) { argb(90, 90, 90) }

    private fun disc(px: IntArray, cx: Int, cy: Int, rad: Int, color: Int) {
        for (y in cy - rad..cy + rad) for (x in cx - rad..cx + rad) {
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= rad * rad) px[y * w + x] = color
        }
    }

    @Test
    fun findsLitSpotsWithTheirColours() {
        val px = frame()
        disc(px, 40, 50, 4, argb(40, 255, 120))
        disc(px, 120, 60, 4, argb(40, 90, 255))
        disc(px, 200, 190, 4, argb(230, 40, 255))
        val blobs = LedDetector.detect(px, w, h)
        assertEquals(3, blobs.size)
        val expected = listOf(Triple(40.0, 50.0, LedDetector.hueOf(40, 255, 120)), Triple(120.0, 60.0, LedDetector.hueOf(40, 90, 255)),
            Triple(200.0, 190.0, LedDetector.hueOf(230, 40, 255)))
        for ((x, y, hue) in expected) {
            val b = blobs.minByOrNull { hypot(it.x - x, it.y - y) }!!
            assertTrue(hypot(b.x - x, b.y - y) < 1.0, "blob near ($x, $y)")
            assertTrue(hueDist(b.hue, hue) < 3.0, "hue ${b.hue} vs $hue")
        }
    }

    @Test
    fun ignoresALargePaintedArea() {
        val px = frame()
        for (y in 80 until 160) for (x in 80 until 160) px[y * w + x] = argb(220, 200, 40)
        disc(px, 30, 30, 4, argb(40, 255, 120))
        val blobs = LedDetector.detect(px, w, h)
        assertEquals(1, blobs.size)
        assertTrue(hypot(blobs[0].x - 30, blobs[0].y - 30) < 1.0)
    }

    @Test
    fun hueDistanceWrapsAround() {
        assertEquals(20.0, hueDist(350.0, 10.0), 1e-9)
        assertEquals(180.0, hueDist(0.0, 180.0), 1e-9)
    }
}
