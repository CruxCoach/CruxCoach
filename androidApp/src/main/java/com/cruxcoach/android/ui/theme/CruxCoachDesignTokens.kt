package com.cruxcoach.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Semantic colors whose meaning remains stable across individual surfaces. */
@Immutable
data class CruxCoachColorTokens(
    val brandAccent: Color,
    val onBrandAccent: Color,
    /** A quiet orange background, e.g. a selected chip; its label takes [onBrandAccentContainer]. */
    val brandAccentContainer: Color,
    val onBrandAccentContainer: Color,
    /** Status colours for text, icons and fills; labels on fills take the theme's onPrimary. */
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val positive: Color,
    val positiveContainer: Color,
    val onPositiveContainer: Color,
    val caution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
)

internal val LightCruxCoachColors = CruxCoachColorTokens(
    // Darker than the legacy light primary so white labels and orange text on light surfaces meet AA.
    brandAccent = BrandOrangeDeep,
    onBrandAccent = Color.White,
    brandAccentContainer = Color(0xFFFFE5D6),
    onBrandAccentContainer = Color(0xFF9A3500),
    success = Color(0xFF256B29),
    warning = Color(0xFF8A6100),
    error = Color(0xFFC62828),
    info = Color(0xFF1565C0),
    positive = Color(0xFF176B35),
    positiveContainer = Color(0xFFD9F5E1),
    onPositiveContainer = Color(0xFF0B3B1C),
    caution = Color(0xFF755700),
    cautionContainer = Color(0xFFFFE8A3),
    onCautionContainer = Color(0xFF332500),
)

internal val DarkCruxCoachColors = CruxCoachColorTokens(
    brandAccent = BrandOrange,
    onBrandAccent = DarkBackground,
    brandAccentContainer = Color(0xFF3D1E10),
    onBrandAccentContainer = BrandOrange,
    success = SuccessGreenBright,
    warning = WarningYellowBright,
    error = ErrorRedBright,
    info = InfoBlueBright,
    positive = Color(0xFF8EE8A5),
    positiveContainer = Color(0xFF163D23),
    onPositiveContainer = Color(0xFFC5F8D1),
    caution = Color(0xFFFFD166),
    cautionContainer = Color(0xFF493800),
    onCautionContainer = Color(0xFFFFE9AD),
)

internal val LocalCruxCoachColors = staticCompositionLocalOf { LightCruxCoachColors }

object CruxCoachSpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val xLarge = 24.dp
    val minimumTouchTarget = 48.dp
}

object CruxCoachShapes {
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(20.dp)
}

object CruxCoachMotion {
    const val quickMillis = 150
    const val standardMillis = 250
}

object CruxCoachDesign {
    val colors: CruxCoachColorTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalCruxCoachColors.current

    val spacing: CruxCoachSpacing = CruxCoachSpacing
    val shapes: CruxCoachShapes = CruxCoachShapes
    val motion: CruxCoachMotion = CruxCoachMotion
}
