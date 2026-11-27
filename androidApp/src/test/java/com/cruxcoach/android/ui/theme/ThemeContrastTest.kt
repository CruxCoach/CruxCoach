package com.cruxcoach.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every text colour of both themes reaches 4.5:1 on the surfaces it sits on, outlines 3:1
 * (WCAG AA). Material fills in roles a scheme leaves out with lilac defaults; these tests also
 * catch a role that falls back to them.
 */
class ThemeContrastTest {

    private fun surfaces(scheme: ColorScheme) = mapOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surfaceVariant" to scheme.surfaceVariant,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
    )

    private fun texts(scheme: ColorScheme, tokens: CruxCoachColorTokens) = mapOf(
        "onSurface" to scheme.onSurface,
        "onSurfaceVariant" to scheme.onSurfaceVariant,
        "primary" to scheme.primary,
        "error" to scheme.error,
        "brandAccent" to tokens.brandAccent,
        "success" to tokens.success,
        "warning" to tokens.warning,
        "error token" to tokens.error,
        "info" to tokens.info,
    )

    private fun pairs(scheme: ColorScheme, tokens: CruxCoachColorTokens) = mapOf(
        "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
        "onPrimaryContainer/primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
        "onSecondary/secondary" to (scheme.onSecondary to scheme.secondary),
        "onSecondaryContainer/secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
        "onTertiary/tertiary" to (scheme.onTertiary to scheme.tertiary),
        "onTertiaryContainer/tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
        "onError/error" to (scheme.onError to scheme.error),
        "onErrorContainer/errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
        "inverseOnSurface/inverseSurface" to (scheme.inverseOnSurface to scheme.inverseSurface),
        "inversePrimary/inverseSurface" to (scheme.inversePrimary to scheme.inverseSurface),
        "onBrandAccent/brandAccent" to (tokens.onBrandAccent to tokens.brandAccent),
        "onBrandAccentContainer/brandAccentContainer" to (tokens.onBrandAccentContainer to tokens.brandAccentContainer),
        "onPrimary/success" to (scheme.onPrimary to tokens.success),
        "onPrimary/warning" to (scheme.onPrimary to tokens.warning),
        "onPrimary/error" to (scheme.onPrimary to tokens.error),
        "onPrimary/info" to (scheme.onPrimary to tokens.info),
    )

    private fun check(theme: String, scheme: ColorScheme, tokens: CruxCoachColorTokens) {
        val failures = buildList {
            surfaces(scheme).forEach { (surfaceName, surface) ->
                texts(scheme, tokens).forEach { (textName, text) ->
                    val ratio = contrast(text, surface)
                    if (ratio < 4.5f) add("$textName on $surfaceName: $ratio")
                }
                val outline = contrast(scheme.outline, surface)
                if (outline < 3f) add("outline on $surfaceName: $outline")
            }
            pairs(scheme, tokens).forEach { (name, pair) ->
                val ratio = contrast(pair.first, pair.second)
                if (ratio < 4.5f) add("$name: $ratio")
            }
        }
        assertTrue(failures.isEmpty(), "$theme theme below WCAG AA: $failures")
    }

    @Test fun `light theme text and outlines reach AA`() = check("light", LightColorScheme, LightCruxCoachColors)

    @Test fun `dark theme text and outlines reach AA`() = check("dark", DarkColorScheme, DarkCruxCoachColors)

    @Test fun `no role keeps Material's lilac default`() {
        // Material's baseline purple tones: a role left unset in either scheme shows one of them.
        val lilac = setOf(0xFF6750A4, 0xFFEADDFF, 0xFF625B71, 0xFFE8DEF8, 0xFF7D5260, 0xFFE7E0EC, 0xFF49454F, 0xFF79747E,
            0xFFCAC4D0, 0xFFD0BCFF, 0xFF381E72, 0xFF4F378B, 0xFFCCC2DC, 0xFF4A4458, 0xFF938F99, 0xFF211F26, 0xFF2B2930,
            0xFF36343B, 0xFF1D1B20, 0xFF0F0D13, 0xFFF3EDF7, 0xFFECE6F0, 0xFFE6E0E9, 0xFFF7F2FA, 0xFFFEF7FF, 0xFF141218)
            .map { Color(it) }.toSet()
        listOf("light" to LightColorScheme, "dark" to DarkColorScheme).forEach { (theme, scheme) ->
            val roles = with(scheme) {
                listOf(primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, onSecondary, secondaryContainer,
                    onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer, surfaceVariant,
                    onSurfaceVariant, surfaceTint, inverseSurface, inverseOnSurface, inversePrimary, outline, outlineVariant,
                    surfaceBright, surfaceDim, surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
                    surfaceContainerHigh, surfaceContainerHighest)
            }
            assertTrue(roles.none { it in lilac }, "$theme theme keeps a Material default: ${roles.filter { it in lilac }}")
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
    }
}
