package com.cruxcoach.engine

import com.cruxcoach.domain.engine.ProfileClassifier
import com.cruxcoach.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileClassifierTest {

    private val classifier = ProfileClassifier()

    private fun makeProfile(
        grade: String = "V6",
        weightKg: Double = 70.0,
        climbingYears: Double = 3.0
    ) = UserProfile(
        id = 1, name = "Test", age = 30, weightKg = weightKg,
        heightCm = 175.0, maxBoulderGrade = grade, climbingYears = climbingYears,
        sessionsPerWeek = 3, createdAt = "2026-01-01", updatedAt = "2026-01-01"
    )

    private fun makeAssessment(
        hangKg: Double? = null,
        pullupKg: Double? = null,
        pushReps: Int? = null,
        coreSec: Int? = null,
        flexibility: Int = 3
    ) = Assessment(
        id = 1, userId = 1, date = "2026-01-01",
        maxHang20mmKg = hangKg, weightedPullupKg = pullupKg,
        pushUpMaxReps = pushReps, coreHoldSec = coreSec,
        flexibilityScore = flexibility
    )

    @Test
    fun strongClimber_highFingerStrengthScore() {
        // V8 climber with 150% BW hang → at benchmark → score ~5.0
        // 105kg hang / 70kg BW = 150% → V8 benchmark is 150% → 5.0
        val profile = makeProfile(grade = "V8", weightKg = 70.0)
        val assessment = makeAssessment(hangKg = 105.0) // 150% BW
        val result = classifier.classify(assessment, profile)

        // At benchmark = 5.0
        assertEquals(5.0f, result.fingerStrength, 0.5f)
    }

    @Test
    fun aboveBenchmarkFingerStrength_highScore() {
        // V6 climber with 130% BW benchmark, but has 150% → above benchmark
        val profile = makeProfile(grade = "V6", weightKg = 70.0)
        val assessment = makeAssessment(hangKg = 105.0) // 150% BW, benchmark is 130%
        val result = classifier.classify(assessment, profile)

        assertTrue(result.fingerStrength > 5.0f, "Should be above average: ${result.fingerStrength}")
    }

    @Test
    fun weakFingerButHighGrade_highTechniqueScore() {
        // V8 climber but only V4-level finger strength → high technique
        val profile = makeProfile(grade = "V8", weightKg = 70.0)
        // V8 benchmark = 150%, but only 77% BW (54kg/70kg) → low finger score
        val assessment = makeAssessment(hangKg = 54.0, pullupKg = 5.0)
        val result = classifier.classify(assessment, profile)

        assertTrue(result.technique >= 7.0f, "Technique should be high: ${result.technique}")
    }

    @Test
    fun beginnerGrade_beginnerLevel() {
        val profile = makeProfile(grade = "V2")
        val assessment = makeAssessment(hangKg = 50.0, pushReps = 15)
        val result = classifier.classify(assessment, profile)

        assertEquals(ClimbingLevel.BEGINNER, result.overallLevel)
    }

    @Test
    fun intermediateGrade_intermediateLevel() {
        val profile = makeProfile(grade = "V5")
        val assessment = makeAssessment()
        val result = classifier.classify(assessment, profile)

        assertEquals(ClimbingLevel.INTERMEDIATE, result.overallLevel)
    }

    @Test
    fun advancedGrade_advancedLevel() {
        val profile = makeProfile(grade = "V9")
        val assessment = makeAssessment()
        val result = classifier.classify(assessment, profile)

        assertEquals(ClimbingLevel.ADVANCED, result.overallLevel)
    }

    @Test
    fun eliteGrade_eliteLevel() {
        val profile = makeProfile(grade = "V12")
        val assessment = makeAssessment()
        val result = classifier.classify(assessment, profile)

        assertEquals(ClimbingLevel.ELITE, result.overallLevel)
    }

    @Test
    fun allMinimumValues_scoresNearOne() {
        val profile = makeProfile(grade = "V10", weightKg = 80.0)
        val assessment = makeAssessment(
            hangKg = 10.0,    // Very low: 12.5% BW vs 170% benchmark
            pullupKg = 0.0,   // Zero added weight vs 55% benchmark
            pushReps = 5,     // Below 10 → 1.0
            coreSec = 15,     // Below 30 → 1.0
            flexibility = 1   // Minimum → 2.0
        )
        val result = classifier.classify(assessment, profile)

        assertTrue(result.fingerStrength <= 2.0f, "Finger: ${result.fingerStrength}")
        assertTrue(result.upperBodyPush <= 2.0f, "Push: ${result.upperBodyPush}")
        assertTrue(result.coreStrength <= 2.0f, "Core: ${result.coreStrength}")
    }

    @Test
    fun boardDataPresent_influencesPowerAndEndurance() {
        val profile = makeProfile(grade = "V6")
        val assessment = makeAssessment()
        val boardAnalysis = BoardAnalysisResult(
            boardType = "KILTER", maxGrade = "V7", comfortGrade = "V5",
            totalSends = 100, powerScore = 8.5f, enduranceScore = 7.0f
        )

        val result = classifier.classify(assessment, profile, boardAnalysis)

        assertEquals(8.5f, result.power)
        assertEquals(7.0f, result.powerEndurance)
    }

    @Test
    fun noBoardData_defaultPowerAndEndurance() {
        val profile = makeProfile(grade = "V6")
        val assessment = makeAssessment()

        val result = classifier.classify(assessment, profile, boardAnalysis = null)

        // Without board data, power estimated from grade, endurance defaults to 5.0
        assertEquals(5.0f, result.powerEndurance)
        assertTrue(result.power > 0f)
    }

    @Test
    fun pushScoreMapping() {
        assertEquals(9.0f, classifier.calculatePushScore(makeAssessment(pushReps = 40)))
        assertEquals(7.0f, classifier.calculatePushScore(makeAssessment(pushReps = 30)))
        assertEquals(5.0f, classifier.calculatePushScore(makeAssessment(pushReps = 20)))
        assertEquals(3.0f, classifier.calculatePushScore(makeAssessment(pushReps = 10)))
        assertEquals(1.0f, classifier.calculatePushScore(makeAssessment(pushReps = 5)))
    }

    @Test
    fun coreScoreMapping() {
        assertEquals(9.0f, classifier.calculateCoreScore(makeAssessment(coreSec = 120)))
        assertEquals(7.0f, classifier.calculateCoreScore(makeAssessment(coreSec = 90)))
        assertEquals(5.0f, classifier.calculateCoreScore(makeAssessment(coreSec = 60)))
        assertEquals(3.0f, classifier.calculateCoreScore(makeAssessment(coreSec = 30)))
        assertEquals(1.0f, classifier.calculateCoreScore(makeAssessment(coreSec = 15)))
    }

    @Test
    fun flexibilityScaling() {
        // 1-5 self-assessment scales to 2-10
        assertEquals(2.0f, classifier.calculateFlexibilityScore(makeAssessment(flexibility = 1)))
        assertEquals(6.0f, classifier.calculateFlexibilityScore(makeAssessment(flexibility = 3)))
        assertEquals(10.0f, classifier.calculateFlexibilityScore(makeAssessment(flexibility = 5)))
    }

    @Test
    fun allScoresWithinRange() {
        val profile = makeProfile(grade = "V6", weightKg = 70.0)
        val assessment = makeAssessment(
            hangKg = 91.0, pullupKg = 17.5, pushReps = 25,
            coreSec = 75, flexibility = 3
        )
        val result = classifier.classify(assessment, profile)

        val scores = listOf(
            result.fingerStrength, result.upperBodyPull, result.upperBodyPush,
            result.coreStrength, result.power, result.powerEndurance,
            result.flexibility, result.technique
        )
        for (score in scores) {
            assertTrue(score in 1.0f..10.0f, "Score out of range: $score")
        }
    }
}
