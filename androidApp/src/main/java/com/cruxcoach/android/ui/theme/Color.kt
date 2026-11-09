package com.cruxcoach.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.cruxcoach.domain.board.IntensityZone
import com.cruxcoach.domain.board.IntensityZones

// Primary: Orange accent for climbing energy
val Orange80 = Color(0xFFFFB74D)
val Orange40 = Color(0xFFE65100)

/** The bright brand orange: the dark theme's accent. On light surfaces it is too faint (2.8:1 on white). */
val BrandOrange = Color(0xFFFF6D00)

/** The light theme's accent: deep enough for orange text on light surfaces and white labels on it (≥ 4.5:1). */
val BrandOrangeDeep = Color(0xFFB83E00)

/**
 * The app's orange in the current theme: [BrandOrange] in the dark theme, [BrandOrangeDeep] in the
 * light one, so orange text and icons and labels on orange stay readable in both. Labels on it take
 * [CruxCoachColorTokens.onBrandAccent] (the theme's onPrimary).
 */
val OrangeAccent: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalCruxCoachColors.current.brandAccent

// Secondary: Slate/Stone tones
val Slate80 = Color(0xFFB0BEC5)
val Slate40 = Color(0xFF37474F)
val SlateLight = Color(0xFF546E7A)

// Background: Dark rock surfaces
val DarkSurface = Color(0xFF1A1A2E)
val DarkBackground = Color(0xFF121212)
val DarkCard = Color(0xFF1E1E30)

// Functional colors: the bright tones of the dark theme. Screens use the theme-aware
// [SuccessGreen], [WarningYellow], [ErrorRed] and [InfoBlue]; in the light theme they are deeper,
// so text and icons in them reach 4.5:1 on light surfaces and white labels on them as well.
val SuccessGreenBright = Color(0xFF4CAF50)
val WarningYellowBright = Color(0xFFFFC107)
val ErrorRedBright = Color(0xFFFF6E6A)
val InfoBlueBright = Color(0xFF42A5F5)

val SuccessGreen: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalCruxCoachColors.current.success
val WarningYellow: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalCruxCoachColors.current.warning
val ErrorRed: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalCruxCoachColors.current.error
val InfoBlue: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalCruxCoachColors.current.info

/**
 * A screen's own colour in the current theme: [dark] on dark surfaces, [light] on light ones, so
 * a colour that has to stand out (a ring, a tap target, text) reaches its contrast in both.
 */
@Composable
@ReadOnlyComposable
fun themedColor(dark: Long, light: Long): Color =
    Color(if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) dark else light)

/**
 * Dark or white text on [fill], whichever reads better: grade colours are bright and dark text
 * reads on most of them, white on the deep ones (elite violet).
 */
fun contentColorOn(fill: Color): Color = if (fill.luminance() > 0.19f) DarkBackground else Color.White

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
