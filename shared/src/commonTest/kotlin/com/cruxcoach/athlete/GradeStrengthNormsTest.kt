package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.GradeStrengthNorms
import com.cruxcoach.athlete.logic.GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM
import com.cruxcoach.athlete.logic.GradeStrengthNorms.Metric.PULL_UP_1RM
import com.cruxcoach.athlete.logic.GradeStrengthNorms.NormSex.COMBINED
import com.cruxcoach.athlete.logic.GradeStrengthNorms.NormSex.FEMALE
import com.cruxcoach.athlete.logic.GradeStrengthNorms.NormSex.MALE
import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.model.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The strength norms (Power Company Climbing + Lattice Training, see [GradeStrengthNorms]): table values, the
 * comparison groups, the edges of the data and the owner's case that started the rewrite (7b, pull-up 1RM
 * ≈ 1.40 × body weight was told "typical for 6b").
 */
class GradeStrengthNormsTest {

    private fun band(metric: GradeStrengthNorms.Metric, difficulty: Double, sex: GradeStrengthNorms.NormSex) =
        assertNotNull(GradeStrengthNorms.band(metric, difficulty, sex), "band $metric $difficulty $sex")

    @Test
    fun difficultyMapsToVGradesThroughTheAppsAnchors() {
        val anchors = mapOf(
            11.0 to 0.0, 13.0 to 1.0, 15.0 to 2.0, 16.0 to 3.0, 18.0 to 4.0, 20.0 to 5.0, 22.0 to 6.0, 23.0 to 7.0,
            25.0 to 8.0, 26.0 to 9.0, 27.0 to 10.0, 28.0 to 11.0, 29.0 to 12.0, 30.0 to 13.0, 31.0 to 14.0,
        )
        anchors.forEach { (d, v) -> assertEquals(v, GradeStrengthNorms.vOf(d), 1e-9, "V of $d") }
        // In between: linear. 7b is V7.5, 6b+ V4.5, 5b V1.5.
        assertEquals("7b", LogbookSummaries.fontOf(24.0))
        assertEquals(7.5, GradeStrengthNorms.vOf(24.0), 1e-9)
        assertEquals(4.5, GradeStrengthNorms.vOf(19.0), 1e-9)
        assertEquals(1.5, GradeStrengthNorms.vOf(14.0), 1e-9)
        // Clamped outside the scale.
        assertEquals(0.0, GradeStrengthNorms.vOf(10.0), 1e-9)
        assertEquals(14.0, GradeStrengthNorms.vOf(33.0), 1e-9)
    }

    @Test
    fun centresAndSpreadsFollowTheTables() {
        // Men, hang: 0.998 + 0.0545·V; V4 ≈ 1.22, V8 ≈ 1.43 × body weight.
        assertEquals(121.6, band(FINGER_MAX_HANG_20MM, 18.0, MALE).center, 1e-6)
        assertEquals(143.4, band(FINGER_MAX_HANG_20MM, 25.0, MALE).center, 1e-6)
        // Women, pull-up: V8 ≈ 1.40; everyone, pull-up: V6 ≈ 1.43.
        assertEquals(140.28, band(PULL_UP_1RM, 25.0, FEMALE).center, 1e-6)
        assertEquals(143.12, band(PULL_UP_1RM, 22.0, COMBINED).center, 1e-6)
        // Everyone, hang, and men, pull-up, at 7a.
        assertEquals(131.68, band(FINGER_MAX_HANG_20MM, 22.0, COMBINED).center, 1e-6)
        assertEquals(145.88, band(PULL_UP_1RM, 22.0, MALE).center, 1e-6)

        fun spread(metric: GradeStrengthNorms.Metric, d: Double, sex: GradeStrengthNorms.NormSex) =
            band(metric, d, sex).let { b -> assertEquals(b.high - b.center, b.center - b.low, 1e-9); b.high - b.center }
        // Men, hang: 14 up to V5, 15 for V6–V9, 17 from V10; linear between whole V grades (6c+ = V5.5).
        assertEquals(14.0, spread(FINGER_MAX_HANG_20MM, 20.0, MALE), 1e-9)
        assertEquals(14.5, spread(FINGER_MAX_HANG_20MM, 21.0, MALE), 1e-9)
        assertEquals(15.0, spread(FINGER_MAX_HANG_20MM, 26.0, MALE), 1e-9)
        assertEquals(17.0, spread(FINGER_MAX_HANG_20MM, 27.0, MALE), 1e-9)
        // Women, hang: 12 up to V3, 16 from V4.
        assertEquals(12.0, spread(FINGER_MAX_HANG_20MM, 16.0, FEMALE), 1e-9)
        assertEquals(16.0, spread(FINGER_MAX_HANG_20MM, 18.0, FEMALE), 1e-9)
        // Everyone, hang: 15 up to V9, 17 from V10.
        assertEquals(15.0, spread(FINGER_MAX_HANG_20MM, 26.0, COMBINED), 1e-9)
        assertEquals(17.0, spread(FINGER_MAX_HANG_20MM, 27.0, COMBINED), 1e-9)
        // Pull-up: men 15 / 17, women 13, everyone 16 / 17.
        assertEquals(15.0, spread(PULL_UP_1RM, 26.0, MALE), 1e-9)
        assertEquals(17.0, spread(PULL_UP_1RM, 27.0, MALE), 1e-9)
        assertEquals(13.0, spread(PULL_UP_1RM, 20.0, FEMALE), 1e-9)
        assertEquals(16.0, spread(PULL_UP_1RM, 26.0, COMBINED), 1e-9)
        assertEquals(17.0, spread(PULL_UP_1RM, 27.0, COMBINED), 1e-9)
    }

    @Test
    fun sexWithoutOwnDataComparesWithEveryone() {
        assertEquals(COMBINED, GradeStrengthNorms.NormSex.of(null))
        assertEquals(COMBINED, GradeStrengthNorms.NormSex.of(Sex.OTHER))
        assertEquals(MALE, GradeStrengthNorms.NormSex.of(Sex.MALE))
        assertEquals(FEMALE, GradeStrengthNorms.NormSex.of(Sex.FEMALE))
        // Men and women differ, most at easy grades.
        assertTrue(band(PULL_UP_1RM, 18.0, MALE).center > band(PULL_UP_1RM, 18.0, FEMALE).center + 15.0)
    }

    @Test
    fun womensHangAboveV10AndBelowV2UsesEveryonesLine() {
        listOf(13.0, 14.0, 28.0, 30.0).forEach { d ->
            val women = band(FINGER_MAX_HANG_20MM, d, FEMALE)
            val all = band(FINGER_MAX_HANG_20MM, d, COMBINED)
            assertEquals(all.low, women.low, 1e-9, "low at $d")
            assertEquals(all.center, women.center, 1e-9, "centre at $d")
            assertEquals(all.high, women.high, 1e-9, "high at $d")
        }
        // Inside V2–V10 the women's own line: 0.941 + 0.0599·V.
        assertEquals(154.0, band(FINGER_MAX_HANG_20MM, 27.0, FEMALE).center, 1e-6)
        assertEquals(106.08, band(FINGER_MAX_HANG_20MM, 15.0, FEMALE).center, 1e-6)
    }

    @Test
    fun womensPullUpIsFlatAboveV10() {
        assertEquals(149.5, band(PULL_UP_1RM, 27.0, FEMALE).center, 1e-6)
        listOf(27.5, 28.0, 29.0, 30.0).forEach { d -> assertEquals(150.0, band(PULL_UP_1RM, d, FEMALE).center, 1e-9, "at $d") }
        // Men keep rising.
        assertTrue(band(PULL_UP_1RM, 30.0, MALE).center > band(PULL_UP_1RM, 28.0, MALE).center)
    }

    @Test
    fun normsSpeakForV0ToV13AndMarkThinData() {
        assertNull(GradeStrengthNorms.band(FINGER_MAX_HANG_20MM, 10.9, COMBINED))
        assertNull(GradeStrengthNorms.band(PULL_UP_1RM, 30.1, MALE))
        assertNotNull(GradeStrengthNorms.band(FINGER_MAX_HANG_20MM, 11.0, COMBINED))
        assertNotNull(GradeStrengthNorms.band(PULL_UP_1RM, 30.0, MALE))
        // Below V2 (extrapolated) for everyone.
        listOf(11.0, 13.0, 14.0).forEach { d -> assertTrue(band(FINGER_MAX_HANG_20MM, d, MALE).thinData, "men at $d") }
        assertFalse(band(FINGER_MAX_HANG_20MM, 15.0, MALE).thinData)
        // Women above V10, everyone above V11.
        assertFalse(band(FINGER_MAX_HANG_20MM, 27.0, FEMALE).thinData)
        assertTrue(band(FINGER_MAX_HANG_20MM, 28.0, FEMALE).thinData)
        assertTrue(band(PULL_UP_1RM, 28.0, FEMALE).thinData)
        assertFalse(band(PULL_UP_1RM, 28.0, MALE).thinData)
        assertFalse(band(PULL_UP_1RM, 28.0, COMBINED).thinData)
        assertTrue(band(PULL_UP_1RM, 29.0, MALE).thinData)
        assertTrue(band(FINGER_MAX_HANG_20MM, 30.0, COMBINED).thinData)
    }

    @Test
    fun typicalRangeGivesGradesNotOnePrediction() {
        // A man hanging 1.40 × body weight: common from 6c (V5) to 7c+ (V10).
        val manHang = assertNotNull(GradeStrengthNorms.typicalRange(FINGER_MAX_HANG_20MM, 140.0, MALE))
        assertEquals(20.0, manHang.lowDifficulty)
        assertEquals(27.0, manHang.highDifficulty)
        assertFalse(manHang.atLowerEnd || manHang.atUpperEnd || manHang.thinData)
        // A woman hanging 1.25: 6a+ (V3.5) to 7b (V7.5).
        val womanHang = assertNotNull(GradeStrengthNorms.typicalRange(FINGER_MAX_HANG_20MM, 125.0, FEMALE))
        assertEquals(17.0, womanHang.lowDifficulty)
        assertEquals(24.0, womanHang.highDifficulty)
        // A man pulling 1.50: 6a+ (V3.5) to 8a (V11) – pull-up strength separates grades poorly.
        val manPull = assertNotNull(GradeStrengthNorms.typicalRange(PULL_UP_1RM, 150.0, MALE))
        assertEquals(17.0, manPull.lowDifficulty)
        assertEquals(28.0, manPull.highDifficulty)
        assertFalse(manPull.thinData)
        // Low values reach the bottom of the scale, high ones the top; both ends rest on few values.
        val low = assertNotNull(GradeStrengthNorms.typicalRange(FINGER_MAX_HANG_20MM, 100.0, COMBINED))
        assertEquals(11.0, low.lowDifficulty)
        assertEquals(16.0, low.highDifficulty)
        assertTrue(low.atLowerEnd && !low.atUpperEnd && low.thinData)
        val high = assertNotNull(GradeStrengthNorms.typicalRange(FINGER_MAX_HANG_20MM, 175.0, COMBINED))
        assertEquals(28.0, high.lowDifficulty)
        assertEquals(30.0, high.highDifficulty)
        assertTrue(high.atUpperEnd && !high.atLowerEnd && high.thinData)
        // Outside the band at every grade: no range.
        assertNull(GradeStrengthNorms.typicalRange(FINGER_MAX_HANG_20MM, 70.0, COMBINED))
    }

    @Test
    fun ownersPullUpAt7bIsWithinTheUsualRange() {
        // Body weight + 12 kg for 6 reps ≈ 1.40 × body weight (Epley); the old line said "typical for 6b".
        val atGrade = band(PULL_UP_1RM, 24.0, MALE)
        assertEquals(GradeStrengthNorms.Position.WITHIN, GradeStrengthNorms.position(PULL_UP_1RM, 24.0, 140.0, MALE))
        // … at the low end of the band.
        assertTrue(140.0 - atGrade.low < (atGrade.high - atGrade.low) / 4, "$atGrade")
        val range = assertNotNull(GradeStrengthNorms.typicalRange(PULL_UP_1RM, 140.0, MALE))
        assertEquals(13.0, range.lowDifficulty) // 5a
        assertEquals(25.0, range.highDifficulty) // 7b+
        assertTrue(24.0 in range.lowDifficulty..range.highDifficulty)
    }

    @Test
    fun positionAndPlausibilityAtTheAthletesGrade() {
        // Everyone at 7a, hang: 116.7–146.7 %.
        assertEquals(GradeStrengthNorms.Position.BELOW, GradeStrengthNorms.position(FINGER_MAX_HANG_20MM, 22.0, 110.0, COMBINED))
        assertEquals(GradeStrengthNorms.Position.WITHIN, GradeStrengthNorms.position(FINGER_MAX_HANG_20MM, 22.0, 138.0, COMBINED))
        assertEquals(GradeStrengthNorms.Position.ABOVE, GradeStrengthNorms.position(FINGER_MAX_HANG_20MM, 22.0, 150.0, COMBINED))
        assertNull(GradeStrengthNorms.position(FINGER_MAX_HANG_20MM, 31.0, 150.0, COMBINED))
        // Everyone at 6b, hang: centre 120.3 %, 2.5 SD = 37.5.
        assertEquals(GradeStrengthNorms.Plausibility.UNUSUALLY_HIGH, GradeStrengthNorms.plausibility(FINGER_MAX_HANG_20MM, 18.0, 200.0, COMBINED))
        assertEquals(GradeStrengthNorms.Plausibility.PLAUSIBLE, GradeStrengthNorms.plausibility(FINGER_MAX_HANG_20MM, 18.0, 150.0, COMBINED))
        assertEquals(GradeStrengthNorms.Plausibility.UNUSUALLY_LOW, GradeStrengthNorms.plausibility(FINGER_MAX_HANG_20MM, 18.0, 80.0, COMBINED))
        // Outside the norms' grades nothing is flagged.
        assertEquals(GradeStrengthNorms.Plausibility.PLAUSIBLE, GradeStrengthNorms.plausibility(FINGER_MAX_HANG_20MM, 32.0, 300.0, COMBINED))
    }
}
