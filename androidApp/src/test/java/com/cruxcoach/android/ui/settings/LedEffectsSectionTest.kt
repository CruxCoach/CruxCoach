package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.led.LedEffectStatus
import com.cruxcoach.android.led.LedEffectsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The LED effects card: live choice, flashing notice, Android's "Remove animations", tempo. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class LedEffectsSectionTest {
    @get:Rule val compose = createComposeRule()

    private val state = mutableStateOf(LedEffectsState(status = LedEffectStatus.OFF))
    private val selected = mutableListOf<String>()
    private var noticeSeen = 0

    private fun show() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                    LedEffectsSection(
                        state = state.value,
                        onSelect = { selected += it; state.value = state.value.copy(effectId = it) },
                        onKeepRunningChange = { state.value = state.value.copy(keepRunningInBackground = it) },
                        onStrobeNoticeSeen = { noticeSeen++; state.value = state.value.copy(strobeNoticeSeen = true) },
                    )
                }
            }
        }
    }

    @Test fun `a gentle effect applies at once, with no tempo to set`() {
        show()
        compose.onNodeWithTag("led_effect_pulse").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("pulse"), selected) }
        compose.onNodeWithTag("led_effect_pulse").assertIsSelected()
        compose.onNodeWithText("Jeder Griff wird in seiner Farbe heller und wieder dunkler.").assertIsDisplayed()
        compose.onNodeWithText("Tempo").assertDoesNotExist()
    }

    @Test fun `a strobe asks once before it is turned on`() {
        show()
        compose.onNodeWithTag("led_effect_two_phase_strobe").performScrollTo().performClick()
        compose.onNodeWithText("Blinkendes Licht").assertIsDisplayed()
        compose.runOnIdle { assertTrue("nothing flashes before the notice is confirmed", selected.isEmpty()) }
        compose.onNodeWithTag("led_strobe_notice_confirm").performClick()
        compose.runOnIdle {
            assertEquals(listOf("two_phase_strobe"), selected)
            assertEquals(1, noticeSeen)
        }
        compose.onNodeWithTag("led_effect_chaotic_strobe").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("two_phase_strobe", "chaotic_strobe"), selected) }
    }

    @Test fun `with Android's remove animations a strobe warns every time but is not locked`() {
        state.value = LedEffectsState(systemAnimationsRemoved = true, strobeNoticeSeen = true)
        show()
        compose.onNodeWithTag("led_effect_chaotic_strobe").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Den Effekt trotzdem einschalten?", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
        compose.onNodeWithTag("led_strobe_notice_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("chaotic_strobe"), selected) }
        compose.onNodeWithTag("led_effects_remove_animations_note").assertIsDisplayed()

        compose.onNodeWithTag("led_effect_two_phase_strobe").performScrollTo().performClick()
        compose.onNodeWithText("Den Effekt trotzdem einschalten?", substring = true).assertIsDisplayed()
    }

    @Test fun `the status says what happens at the wall`() {
        state.value = LedEffectsState(effectId = "rainbow_wave", status = LedEffectStatus.NO_BOARD)
        show()
        compose.onNodeWithText("Verbinde ein Kilter Board, um den Effekt zu sehen.").assertIsDisplayed()
        state.value = state.value.copy(status = LedEffectStatus.RUNNING)
        compose.onNodeWithText("Läuft gerade am Board.").assertIsDisplayed()
    }
}
