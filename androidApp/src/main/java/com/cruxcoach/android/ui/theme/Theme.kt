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

private val DarkColorScheme = darkColorScheme(
    primary = OrangeAccent,
    onPrimary = Color.Black,
    primaryContainer = Orange40,
    onPrimaryContainer = Orange80,
    secondary = SlateLight,
    onSecondary = Color.White,
    secondaryContainer = Slate40,
    onSecondaryContainer = Slate80,
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color.White,
    surfaceVariant = DarkCard,
    onSurfaceVariant = Slate80,
    error = ErrorRed,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Orange40,
    onPrimary = Color.White,
    primaryContainer = Orange80,
    onPrimaryContainer = Color.Black,
    secondary = Slate40,
    onSecondary = Color.White,
    secondaryContainer = Slate80,
    onSecondaryContainer = Color.Black,
    background = Color(0xFFF5F5F5),
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    error = ErrorRed,
    onError = Color.White
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
