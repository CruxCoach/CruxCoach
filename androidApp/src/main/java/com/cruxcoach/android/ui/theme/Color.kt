package com.cruxcoach.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.cruxcoach.domain.board.IntensityZone
import com.cruxcoach.domain.board.IntensityZones

// Primary: Orange accent for climbing energy
val Orange80 = Color(0xFFFFB74D)
val Orange40 = Color(0xFFE65100)
val OrangeAccent = Color(0xFFFF6D00)

// Secondary: Slate/Stone tones
val Slate80 = Color(0xFFB0BEC5)
val Slate40 = Color(0xFF37474F)
val SlateLight = Color(0xFF546E7A)

// Background: Dark rock surfaces
val DarkSurface = Color(0xFF1A1A2E)
val DarkBackground = Color(0xFF121212)
val DarkCard = Color(0xFF1E1E30)

// Functional colors
val SuccessGreen = Color(0xFF4CAF50)
val WarningYellow = Color(0xFFFFC107)
val ErrorRed = Color(0xFFEF5350)
val InfoBlue = Color(0xFF42A5F5)

// Grade difficulty colors
val GradeEasy = Color(0xFF66BB6A)
val GradeMedium = Color(0xFFFFA726)
val GradeHard = Color(0xFFEF5350)
val GradeElite = Color(0xFFAB47BC)

/**
 * Returns a color based on V-Grade, so the same grade always gets the same color.
 * Converts Kilter difficulty_average → V-Grade number → color band.
 *   V0-V2  (4 – 5+)    → green
 *   V3-V5  (6a – 6c)   → orange
 *   V6-V8  (7a – 7b+)  → red
 *   V9+    (7c+)        → purple
 */
fun gradeColorForDifficulty(difficultyAverage: Double): Color {
    // Use the same mapping as KilterGradeMapper.DIFFICULTY_TO_VSCALE
    val vNum = when {
        difficultyAverage < 13 -> 0
        difficultyAverage < 15 -> 1
        difficultyAverage < 16 -> 2
        difficultyAverage < 18 -> 3
        difficultyAverage < 20 -> 4
        difficultyAverage < 22 -> 5
        difficultyAverage < 23 -> 6
        difficultyAverage < 24 -> 7
        difficultyAverage < 26 -> 8
        difficultyAverage < 27 -> 9
        difficultyAverage < 28 -> 10
        else -> 11
    }
    return when {
        vNum <= 2 -> GradeEasy
        vNum <= 5 -> GradeMedium
        vNum <= 8 -> GradeHard
        else -> GradeElite
    }
}

/**
 * Returns a personalized zone color (green/orange/red) based on the user's
 * intensity zones. Falls back to green if zones are null.
 */
fun zoneColorForDifficulty(difficultyAverage: Double, zones: IntensityZones?): Color {
    if (zones == null) return GradeEasy
    return when (zones.classify(difficultyAverage)) {
        IntensityZone.WARMUP -> GradeEasy
        IntensityZone.OPTIMAL -> GradeMedium
        IntensityZone.LIMIT -> GradeHard
    }
}

// Session type colors
val SessionStrength = Color(0xFFE53935)
val SessionPower = Color(0xFFFF6D00)
val SessionVolume = Color(0xFF43A047)
val SessionTechnique = Color(0xFF1E88E5)
val SessionDeload = Color(0xFF7E57C2)
val SessionRest = Color(0xFF757575)

// Phase colors
val PhaseBase = Color(0xFF66BB6A)
val PhaseStrength = Color(0xFFE53935)
val PhasePower = Color(0xFFFF6D00)
val PhasePerformance = Color(0xFFFFD600)
val PhaseDeload = Color(0xFF7E57C2)
