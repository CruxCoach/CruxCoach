package com.cruxcoach.domain.spraywall.detect

import com.cruxcoach.domain.spraywall.detect.SyntheticWall.Panel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hold detector on synthetic walls with known holds (see [SyntheticWall]).
 * Thresholds sit a little below the measured values so a small tuning change
 * does not break the build, but a real regression does. Measured precision
 * and recall per scenario are in the FEAT-078 spec.
 */
class SprayHoldDetectorTest {
    private val default = 0.5f

    private fun measure(spec: SyntheticWall.Spec): SyntheticWall.Score {
        val wall = SyntheticWall.render(spec)
        return SyntheticWall.score(wall, SprayHoldDetector.detect(wall.image), default)
    }

    private fun assertAtLeast(spec: SyntheticWall.Spec, precision: Float, recall: Float) {
        val s = measure(spec)
        assertTrue(s.precision >= precision, "${spec.name}: precision ${s.precision} < $precision")
        assertTrue(s.recall >= recall, "${spec.name}: recall ${s.recall} < $recall (missed ${s.missedByColour})")
        assertEquals(0, s.onHoles, "${spec.name}: suggestions on T-nut holes")
    }

    @Test
    fun colourfulHoldsOnPlywood() = assertAtLeast(
        SyntheticWall.Spec("plywood", Panel.PLYWOOD, SyntheticWall.COLOURFUL, 120, seed = 1),
        precision = 0.93f, recall = 0.95f,
    )

    @Test
    fun blackAndWhiteHoldsInUnevenLight() = assertAtLeast(
        SyntheticWall.Spec(
            "uneven", Panel.PLYWOOD, SyntheticWall.COLOURFUL + listOf(SyntheticWall.BLACK, SyntheticWall.WHITE, SyntheticWall.GREY),
            180, wallWidthMeters = 3.6f, unevenLight = 0.9f, touching = 0.35f, seed = 2,
        ),
        precision = 0.93f, recall = 0.9f,
    )

    @Test
    fun paintedPanels() {
        val holds = SyntheticWall.COLOURFUL + listOf(SyntheticWall.BLACK, SyntheticWall.WHITE, SyntheticWall.GREY)
        // Black holds on a black panel and white ones on a white panel are the hard part.
        assertAtLeast(SyntheticWall.Spec("black", Panel.BLACK, holds, 120, unevenLight = 0.5f, seed = 3), precision = 0.93f, recall = 0.8f)
        assertAtLeast(SyntheticWall.Spec("white", Panel.WHITE, holds, 120, unevenLight = 0.5f, seed = 4), precision = 0.93f, recall = 0.8f)
    }

    @Test
    fun touchingHoldsAreSplit() = assertAtLeast(
        SyntheticWall.Spec("dense", Panel.PLYWOOD, SyntheticWall.COLOURFUL + SyntheticWall.BLACK, 260, wallWidthMeters = 3.0f, touching = 0.55f, unevenLight = 0.4f, seed = 6),
        precision = 0.92f, recall = 0.93f,
    )

    @Test
    fun hardLightBlurAndNoise() {
        val holds = SyntheticWall.COLOURFUL + listOf(SyntheticWall.BLACK, SyntheticWall.WHITE)
        assertAtLeast(SyntheticWall.Spec("shadows", Panel.PLYWOOD, holds, 150, unevenLight = 0.8f, shadow = 0.6f, seed = 11), precision = 0.9f, recall = 0.95f)
        assertAtLeast(SyntheticWall.Spec("blurry", Panel.PLYWOOD, holds, 150, wallWidthMeters = 3.0f, noise = 9f, blur = 1, seed = 12), precision = 0.93f, recall = 0.9f)
    }

    @Test
    fun tNutHolesAreNeverSuggested() {
        for (spec in listOf(
            SyntheticWall.Spec("bare plywood", Panel.PLYWOOD, SyntheticWall.COLOURFUL, 0, unevenLight = 0.6f, seed = 9),
            // Close up the holes are big enough to look like foot chips.
            SyntheticWall.Spec("bare white, close", Panel.WHITE, SyntheticWall.COLOURFUL, 0, wallWidthMeters = 1.6f, unevenLight = 0.6f, seed = 10),
        )) {
            val wall = SyntheticWall.render(spec)
            assertTrue(wall.holes.size > 40, "the scenario must show holes")
            val found = SprayHoldDetector.detect(wall.image).filter { it.confidence >= 0.2f }
            assertEquals(emptyList(), found, spec.name)
        }
    }

    @Test
    fun candidatesAreNormalisedAndSortedBestFirst() {
        val wall = SyntheticWall.render(SyntheticWall.Spec("portrait", Panel.PLYWOOD, SyntheticWall.COLOURFUL, 60, width = 384, height = 512, seed = 8))
        val found = SprayHoldDetector.detect(wall.image)
        assertTrue(found.isNotEmpty())
        for (c in found) {
            assertTrue(c.x in 0f..1f && c.y in 0f..1f, "centre $c")
            assertTrue(c.radius in SprayHoldDetector.MIN_RADIUS..SprayHoldDetector.MAX_RADIUS, "radius $c")
            assertTrue(c.confidence in 0f..1f, "confidence $c")
        }
        assertEquals(found.sortedByDescending { it.confidence }, found)
    }

    @Test
    fun aPlainPictureHasNoHolds() {
        val grey = RgbImage(200, 150, IntArray(200 * 150) { 0xFF808080.toInt() })
        assertEquals(emptyList(), SprayHoldDetector.detect(grey))
    }

    @Test
    fun threeBoxesApproximateTheGaussian() {
        // Three boxes whose variances add up to sigma².
        for (sigma in listOf(1f, 2.5f, 7f, 20f)) {
            val variance = Blur.boxRadii(sigma).sumOf { r -> ((2 * r + 1) * (2 * r + 1) - 1) / 12.0 }
            assertTrue(kotlin.math.abs(kotlin.math.sqrt(variance) - sigma) < 0.35f * sigma + 0.3f, "sigma $sigma -> ${kotlin.math.sqrt(variance)}")
        }
    }
}
