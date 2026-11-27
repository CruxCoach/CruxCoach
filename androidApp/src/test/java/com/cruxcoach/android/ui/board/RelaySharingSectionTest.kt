package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.CruxRelayState
import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.android.ui.common.effectsOnTestThread
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class RelaySharingSectionTest {
    // The stored answers come from DataStore on another thread.
    @get:Rule val compose = createComposeRule(effectsOnTestThread())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = createTestUserPreferences(scope)
    private val relay = mockk<CruxRelayManager>(relaxed = true)

    @Volatile private var seenRead = false
    @Volatile private var manualStartRead = false

    @After fun tearDown() {
        scope.cancel()
    }

    @Test fun `before the first share the switch is off and the card shows the disclosure`() {
        // A fresh install: neither relay_disclosure_seen nor relay_manual_start_v022 is stored.
        render()

        compose.onNodeWithTag("relay_share_switch").assertIsOff()
        compose.onNodeWithText(string(R.string.relay_disclosure_text)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.relay_status_waiting)).assertDoesNotExist()

        compose.onNodeWithTag("relay_share_switch").performClick()
        verify(exactly = 1) { relay.setSharingEnabled(true) }
    }

    @Test fun `after the first share the switch is on and waits for the board`() {
        // What switching it on stores (CruxRelayManager.setSharingEnabled).
        runBlocking {
            preferences.setRelayDisclosureSeen()
            preferences.setRelayManualStart(false)
        }
        render()

        compose.onNodeWithTag("relay_share_switch").assertIsOn()
        compose.onNodeWithText(string(R.string.relay_status_waiting)).assertIsDisplayed()
    }

    @Test fun `after a stop from the notification the card says the next connection shares and offers share again`() {
        runBlocking {
            preferences.setRelayDisclosureSeen()
            preferences.setRelayManualStart(false)
        }
        render(CruxRelayState(stoppedForConnection = true))

        compose.onNodeWithTag("relay_share_switch").assertIsOn()
        compose.onNodeWithText(string(R.string.relay_status_stopped_for_car)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.relay_status_waiting)).assertDoesNotExist()
        compose.onNodeWithTag("relay_share_again").performClick()
        verify(exactly = 1) { relay.requestEnable() }
    }

    private fun render(state: CruxRelayState = CruxRelayState()) {
        every { relay.state } returns MutableStateFlow(state)
        every { relay.disclosureSeen } returns
            preferences.relayDisclosureSeen.onEach { seenRead = true }
        every { relay.manualStart } returns
            preferences.relayManualStart.onEach { manualStartRead = true }
        compose.setContent {
            MaterialTheme { RelaySharingSection(singleConnectBoard(), RelayShareViewModel(relay)) }
        }
        // Assert on the stored answers, not on the "not read yet" values the card starts with.
        compose.waitUntil(WAIT_MS) { seenRead && manualStartRead }
        compose.waitForIdle()
    }

    private fun singleConnectBoard() = DiscoveredBoard(
        displayName = "Kilter Board",
        serial = "123",
        apiLevel = 3,
        address = "00:11:22:33:44:55",
        rssi = -40,
        boardBrand = BoardBrand.KILTER,
        advertisesWhileConnected = false,
    )

    private fun string(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
