package com.cruxcoach.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * What a screen may ask of the system bars. The theme owns the bars: it sets them from its
 * colours, and a screen that is dark in both themes (the year in review) asks here for dark bars
 * while it shows. No screen writes the bars itself, so none has to remember and restore them, and
 * the theme setting them again cannot undo a screen's request.
 */
@Stable
class SystemBarRequests internal constructor() {
    private var darkRequests by mutableIntStateOf(0)

    /** Whether a screen on show wants dark bars with light icons. */
    val darkWanted: Boolean get() = darkRequests > 0

    /** Asks for dark bars; the returned function takes the request back. */
    fun requestDark(): () -> Unit {
        darkRequests++
        var open = true
        return {
            if (open) {
                open = false
                darkRequests--
            }
        }
    }
}

/** Null outside [CruxCoachTheme], where nobody sets the bars (previews, tests of one screen). */
val LocalSystemBarRequests = staticCompositionLocalOf<SystemBarRequests?> { null }

/** The window's own navigation bar, kept while the theme has it dark. */
private class OwnNavigationBar(val color: Int, val lightIcons: Boolean, val contrastEnforced: Boolean?)

/**
 * Sets the bars: the status bar from the theme, and both bars dark with light icons while a screen
 * asks for that. The theme does not style the navigation bar itself, so it keeps what the window
 * had when the dark bars began and gives exactly that back when they end.
 *
 * The colours only reach the bars below Android 15. From Android 15 on the window ignores them and
 * the bars are see-through, so [DarkSystemBarAreas] darkens what lies behind them.
 */
@Suppress("DEPRECATION") // Below Android 15 the bars take their colours from these window properties.
@Composable
private fun SystemBars(darkTheme: Boolean, themeBackground: Color, requests: SystemBarRequests) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val darkBars = requests.darkWanted
    val ownNavigationBar = remember { arrayOfNulls<OwnNavigationBar>(1) }
    SideEffect {
        val window = (view.context as Activity).window
        val bars = WindowCompat.getInsetsController(window, view)
        window.statusBarColor = (if (darkBars) DarkBackground else themeBackground).toArgb()
        bars.isAppearanceLightStatusBars = !darkTheme && !darkBars
        if (darkBars) {
            if (ownNavigationBar[0] == null) {
                ownNavigationBar[0] = OwnNavigationBar(
                    color = window.navigationBarColor,
                    lightIcons = bars.isAppearanceLightNavigationBars,
                    contrastEnforced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced else null,
                )
            }
            window.navigationBarColor = DarkSurface.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
            bars.isAppearanceLightNavigationBars = false
        } else {
            ownNavigationBar[0]?.let { own ->
                window.navigationBarColor = own.color
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) own.contrastEnforced?.let { window.isNavigationBarContrastEnforced = it }
                bars.isAppearanceLightNavigationBars = own.lightIcons
                ownNavigationBar[0] = null
            }
        }
    }
}

/**
 * From Android 15 on the window is edge to edge and ignores the bar colours: behind the bars shows
 * what the app draws there, and that is the theme's background, because the screens keep out of
 * those areas. A dark page between a light status bar strip and a light navigation bar strip had
 * its light icons on light ground. So while a screen asks for dark bars, these two strips cover
 * the areas behind the bars with the dark page's colours, the ones the window gets below Android 15.
 *
 * They are drawn above the content, are there only while the request stands, and take no touches.
 * [CruxCoachTheme] puts them next to its content, so it has to be the root of the window's content
 * (it is: MainActivity's `setContent`), where siblings lie on top of each other.
 */
@Composable
private fun DarkSystemBarAreas(requests: SystemBarRequests) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || !requests.darkWanted) return
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsTopHeight(WindowInsets.systemBars).background(DarkBackground))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsBottomHeight(WindowInsets.systemBars).background(DarkSurface))
    }
}

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

    val systemBars = remember { SystemBarRequests() }
    SystemBars(darkTheme, colorScheme.background, systemBars)

    CompositionLocalProvider(
        LocalCruxCoachColors provides if (darkTheme) DarkCruxCoachColors else LightCruxCoachColors,
        LocalSystemBarRequests provides systemBars,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
        ) {
            content()
            DarkSystemBarAreas(systemBars)
        }
    }
}
