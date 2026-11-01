package com.cruxcoach.domain.spraywall.detect

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegPipelineTest {
    @Test
    fun tilesOverlapAndStayInside() {
        // The reference case: a 1024×768 working image in 2×2 tiles with 15 % overlap gives 589×442 tiles.
        val tiles = SegPipeline.tiles(1024, 768, 2, 2, 0.15)
        assertEquals(
            listOf(SegTile(0, 0, 589, 442), SegTile(435, 0, 1024, 442), SegTile(0, 326, 589, 768), SegTile(435, 326, 1024, 768)),
            tiles,
        )
        assertEquals(listOf(SegTile(0, 0, 640, 480)), SegPipeline.tiles(640, 480, 1, 1, 0.15))
        val three = SegPipeline.tiles(1536, 1152, 3, 3, 0.15)
        assertEquals(9, three.size)
        assertTrue(three.all { it.x0 >= 0 && it.y0 >= 0 && it.x1 <= 1536 && it.y1 <= 1152 })
        // As in the reference, the floored step can leave the last pixel column out.
        assertTrue(three.maxOf { it.x1 } >= 1535)
    }

    @Test
    fun workingSizeAndRounding() {
        assertEquals(771 to 1024, SegPipeline.workingSize(1920, 2550, 1024))
        assertEquals(1024 to 768, SegPipeline.workingSize(4000, 3000, 1024))
        assertEquals(2, SegPipeline.roundHalfToEven(2.5))
        assertEquals(4, SegPipeline.roundHalfToEven(3.5))
        assertEquals(589, SegPipeline.roundHalfToEven(588.8))
        assertEquals(3, SegPipeline.roundHalfUp(2.5))
    }

    @Test
    fun decodeKeepsQueriesAboveTheFloorInTilePixels() {
        val tile = SegTile(100, 50, 300, 250)
        val outputs = SegOutputs(
            boxes = floatArrayOf(0.5f, 0.5f, 0.2f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f),
            logits = floatArrayOf(logit(0.9f), -5f, logit(0.2f), -5f),
            queries = 2,
            classes = 2,
        )
        val found = SegPipeline.decode(outputs, tile, SegSettings(threshold = 0.6f, floor = 0.3f))
        assertEquals(1, found.size)
        val d = found.single()
        assertEquals(0.9f, d.score, 1e-4f)
        assertEquals(100 + 0.4f * 200, d.x0, 1e-3f)
        assertEquals(50 + 0.45f * 200, d.y0, 1e-3f)
        assertEquals(100 + 0.6f * 200, d.x1, 1e-3f)
        assertNull(d.outline)
    }

    @Test
    fun aDiscMaskBecomesARoundRing() {
        val grid = 78
        val masks = FloatArray(grid * grid) { i ->
            val x = i % grid + 0.5f
            val y = i / grid + 0.5f
            10f - hypot(x - 39f, y - 39f)
        }
        // The box around the disc, normalised: centre 0.5, side 20/78.
        val side = 20f / grid
        val ring = MaskOutline.trace(masks, 0, grid, grid, 0.5f - side / 2, 0.5f - side / 2, 0.5f + side / 2, 0.5f + side / 2, 312f, 312f, SegSettings())
        assertNotNull(ring)
        assertTrue(ring.size / 2 in 8..150, "points: ${ring.size / 2}")
        // A disc of radius R in a 2R box: the equal-area radius is 2R/√π, so the ring sits at √π/2 ≈ 0.886 of it.
        val distances = (ring.indices step 2).map { hypot(ring[it], ring[it + 1]) }
        assertTrue(distances.all { abs(it - sqrt(PI.toFloat()) / 2) < 0.08f }, "distances $distances")
    }

    @Test
    fun anEmptyMaskHasNoRing() {
        val masks = FloatArray(78 * 78) { -3f }
        assertNull(MaskOutline.trace(masks, 0, 78, 78, 0.4f, 0.4f, 0.6f, 0.6f, 312f, 312f, SegSettings()))
    }

    @Test
    fun mergeKeepsTheBestOfOverlappingBoxes() {
        val a = SegDetection(0f, 0f, 10f, 10f, 0.9f, null)
        val b = SegDetection(1f, 1f, 11f, 11f, 0.7f, null)
        val c = SegDetection(50f, 50f, 60f, 60f, 0.65f, null)
        val merged = SegPipeline.merge(listOf(listOf(a, c), listOf(b)), 0.5f)
        assertEquals(listOf(a, c), merged)
    }

    @Test
    fun candidatesUseTheEqualAreaRadius() {
        val d = SegDetection(100f, 100f, 140f, 110f, 0.8f, floatArrayOf(1f, 0f, 0f, 1f, -1f, 0f))
        val c = SegPipeline.candidates(listOf(d), 1000, 500).single()
        assertEquals(0.12f, c.x, 1e-5f)
        assertEquals(0.21f, c.y, 1e-5f)
        assertEquals(sqrt(400f / PI.toFloat()) / 1000f, c.radius, 1e-6f)
        assertEquals(listOf(1f, 0f, 0f, 1f, -1f, 0f), c.outline)
    }

    @Test
    fun resamplingKeepsAFlatColourAndTheSize() {
        val image = RgbImage(40, 30, IntArray(40 * 30) { 0xFF336699.toInt() })
        for (kind in SegSettings.Resampler.entries) {
            val small = Resampling.resize(image, 13, 7, kind)
            assertEquals(13, small.width)
            assertEquals(7, small.height)
            assertTrue(small.argb.all { it == 0xFF336699.toInt() }, "$kind")
        }
    }

    @Test
    fun inputTensorIsNormalisedNchw() {
        val image = RgbImage(8, 8, IntArray(64) { 0xFFFF0000.toInt() })
        val settings = SegSettings(input = 64, longSide = 128)
        val tensor = SegPipeline.inputTensor(image, SegTile(0, 0, 8, 8), settings)
        assertEquals(3 * 64 * 64, tensor.size)
        assertEquals((1f - 0.485f) / 0.229f, tensor[0], 1e-4f)
        assertEquals((0f - 0.456f) / 0.224f, tensor[64 * 64], 1e-4f)
        assertEquals((0f - 0.406f) / 0.225f, tensor[2 * 64 * 64], 1e-4f)
    }

    @Test
    fun nearestSetMatchesTheSpiral() {
        val random = kotlin.random.Random(7)
        repeat(200) {
            val w = random.nextInt(1, 30)
            val h = random.nextInt(1, 30)
            val filled = BooleanArray(w * h) { random.nextInt(100) < 3 }
            val x = random.nextInt(w)
            val y = random.nextInt(h)
            filled[y * w + x] = false
            assertEquals(spiral(filled, w, h, x, y), MaskOutline.nearestSet(filled, w, h, x, y))
        }
    }

    /** The reference's square spiral, for comparison. */
    private fun spiral(filled: BooleanArray, w: Int, h: Int, x: Int, y: Int): Pair<Int, Int>? {
        for (r in 1 until maxOf(w, h)) {
            for (dy in -r..r) {
                for (dx in -r..r) {
                    if (maxOf(abs(dx), abs(dy)) != r) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    if (filled[ny * w + nx]) return nx to ny
                }
            }
        }
        return null
    }

    @Test
    fun seamAwareMergeKeepsTheWholeCopy() {
        // Two tiles overlap at x 40..60. Tile 0 sees the hold cut at its right edge (x1 = 60), tile 1 sees it whole.
        val tiles = listOf(SegTile(0, 0, 60, 100), SegTile(40, 0, 100, 100))
        val cut = SegDetection(45f, 10f, 59f, 30f, 0.9f, null)
        val whole = SegDetection(45f, 10f, 70f, 30f, 0.7f, null)
        val merged = SegPipeline.merge(listOf(listOf(cut), listOf(whole)), tiles, 100, 100, 0.3f)
        assertEquals(listOf(whole), merged)
        // The plain merge keeps the better score.
        assertEquals(listOf(cut), SegPipeline.merge(listOf(listOf(cut), listOf(whole)), 0.3f))
    }

    private fun logit(p: Float): Float = ln(p / (1 - p))
}
