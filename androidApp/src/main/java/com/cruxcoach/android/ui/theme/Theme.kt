package com.cruxcoach.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.cruxcoach.android.data.DarkModeSetting

// Every role is set: Material's defaults are lilac and would show through on chips, segments,
// menus and sheets. Text roles reach 4.5:1 on the surfaces they sit on, outlines 3:1.
internal val DarkColorScheme = darkColorScheme(
    primary = BrandOrange,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF6B2A00),
    onPrimaryContainer = Color(0xFFFFDBC8),
    inversePrimary = BrandOrangeDeep,
    secondary = Slate80,
    onSecondary = Slate40,
    secondaryContainer = Slate40,
    onSecondaryContainer = Color.White,
    tertiary = Orange80,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF5C3A00),
    onTertiaryContainer = Color(0xFFFFDDB3),
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color.White,
    surfaceVariant = DarkCard,
    onSurfaceVariant = Slate80,
    surfaceTint = BrandOrange,
    inverseSurface = Color(0xFFE6E6EA),
    inverseOnSurface = DarkSurface,
    error = ErrorRedBright,
    onError = Color.Black,
    // Deep enough that orange and error-red labels on it reach 4.5:1, and no louder than the other containers.
    errorContainer = Color(0xFF5E1410),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A9AA2),
    outlineVariant = Color(0xFF3C3C52),
    scrim = Color.Black,
    surfaceBright = Color(0xFF34344A),
    surfaceDim = DarkBackground,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkCard,
    surfaceContainerHigh = Color(0xFF26263A),
    surfaceContainerHighest = Color(0xFF303045),
)

internal val LightColorScheme = lightColorScheme(
    primary = BrandOrangeDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC8),
    onPrimaryContainer = Color(0xFF3A1300),
    inversePrimary = Orange80,
    secondary = Slate40,
    onSecondary = Color.White,
    // Deep enough that a selected chip, segment or bottom-bar item stands out from the
    // surface, light enough for onSurfaceVariant (4.7:1) and the orange on cards (4.8:1).
    secondaryContainer = Color(0xFFC8D3D9),
    onSecondaryContainer = Color(0xFF1B2328),
    tertiary = Color(0xFF8A5100),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF2C1700),
    background = Color(0xFFF5F5F5),
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFECEFF1),
    onSurfaceVariant = Color(0xFF4A5A62),
    surfaceTint = BrandOrangeDeep,
    inverseSurface = Color(0xFF2E3133),
    inverseOnSurface = Color(0xFFF0F1F1),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6F7B82),
    outlineVariant = Color(0xFFC5CED3),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDCDCDC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE8E8E8),
)

@Composable
fun CruxCoachTheme(
    darkModeSetting: DarkModeSetting = DarkModeSetting.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (darkModeSetting) {
        DarkModeSetting.DARK -> true
        DarkModeSetting.LIGHT -> false
        DarkModeSetting.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalCruxCoachColors provides if (darkTheme) DarkCruxCoachColors else LightCruxCoachColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
