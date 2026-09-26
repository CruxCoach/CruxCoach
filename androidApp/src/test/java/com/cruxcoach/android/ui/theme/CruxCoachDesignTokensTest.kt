package com.cruxcoach.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

class CruxCoachDesignTokensTest {
    @Test
    fun `semantic text pairs meet WCAG AA contrast`() {
        val pairs = listOf(
            LightCruxCoachColors.onBrandAccent to LightCruxCoachColors.brandAccent,
            LightCruxCoachColors.positive to Color.White,
            LightCruxCoachColors.onPositiveContainer to LightCruxCoachColors.positiveContainer,
            LightCruxCoachColors.caution to Color.White,
            LightCruxCoachColors.onCautionContainer to LightCruxCoachColors.cautionContainer,
            DarkCruxCoachColors.onBrandAccent to DarkCruxCoachColors.brandAccent,
            DarkCruxCoachColors.positive to DarkBackground,
            DarkCruxCoachColors.onPositiveContainer to DarkCruxCoachColors.positiveContainer,
            DarkCruxCoachColors.caution to DarkBackground,
            DarkCruxCoachColors.onCautionContainer to DarkCruxCoachColors.cautionContainer,
        )

        pairs.forEach { (foreground, background) ->
            val ratio = contrastRatio(foreground, background)
            assertTrue(ratio >= 4.5, "contrast was $ratio, expected at least 4.5")
        }
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val firstLuminance = first.luminance()
        val secondLuminance = second.luminance()
        return (max(firstLuminance, secondLuminance) + 0.05f) /
            (min(firstLuminance, secondLuminance) + 0.05f)
    }
}
