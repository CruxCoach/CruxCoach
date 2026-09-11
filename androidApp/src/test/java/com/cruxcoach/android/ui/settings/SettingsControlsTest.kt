package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.SyncInterval
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class SettingsControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `long label is the switch target and toggles exactly once`() {
        val checked = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp)) {
                    SettingsToggleRow(
                        "Eine lange deutsche Schalterbeschriftung", "Folgen bleiben hier lesbar.",
                        checked.value, { checked.value = it; calls++ },
                    )
                }
            }
        }
        compose.onNode(isToggleable()).assertIsOff().assertIsDisplayed()
        compose.onNodeWithText("Eine lange deutsche Schalterbeschriftung").performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun `all German sync choices remain reachable at narrow width without changing the value on entry`() {
        val interval = mutableStateOf(SyncInterval.MANUAL)
        var calls = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(240.dp)) {
                    BoardSyncSection(interval.value) { interval.value = it; calls++ }
                }
            }
        }
        compose.runOnIdle { assertEquals(0, calls) }
        listOf("Täglich", "Wöchentlich", "Manuell").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().performClick().assertIsSelected()
        }
        compose.runOnIdle { assertEquals(SyncInterval.MANUAL, interval.value); assertEquals(3, calls) }
    }

    @Test fun `disconnect off and on retains the custom duration across the new row`() {
        val seconds = mutableStateOf(107)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(260.dp).verticalScroll(rememberScrollState())) {
                    BleAutoDisconnectSection(seconds.value) { seconds.value = it }
                }
            }
        }
        compose.onNodeWithTag("ble_auto_disconnect_toggle").performScrollTo().performClick().assertIsOff()
        compose.runOnIdle { assertEquals(0, seconds.value) }
        compose.onNodeWithTag("ble_auto_disconnect_toggle").performClick().assertIsOn()
        compose.runOnIdle { assertEquals(107, seconds.value) }
    }

    @Test fun `backup without a key keeps its status and cannot opt in`() {
        var enables = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                    BackupSettingsSection(
                        state = BackupSettingsState(),
                        onSetBackupEnabled = { enables++ }, onSetInterval = {},
                        onRunBackupNow = {}, onTriggerRestore = {},
                    )
                }
            }
        }
        compose.onNode(isToggleable()).performScrollTo().assertIsNotEnabled().assertIsOff()
        compose.runOnIdle { assertEquals(0, enables) }
    }
}
