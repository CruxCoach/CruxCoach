package com.cruxcoach.android.ui.paintbox

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.LedLab
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.MoonBoardLedMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The LED lab in PaintBox: the menu entry, the switches and the report of the last send. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class LedLabUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun reset() = LedLab.reset()

    private fun editor(): PaintBoxViewModel {
        val vm = mockk<PaintBoxViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(PaintBoxState(
            document = PaintDocument(board = PaintBoard("kilter", 1, 10)), loading = false,
            boardSize = BoardSize(10, 1, "Test board", 0, 100, 0, 100, null),
            placements = mapOf(1 to BoardPlacement(1, 1, 1, 30, 30)),
        ))
        every { vm.connection } returns MutableStateFlow(ConnectionState.CONNECTED)
        every { vm.moonLedMode } returns MutableStateFlow(MoonBoardLedMode.BOTH)
        every { vm.labBoard() } returns "Kilter Board · API 2"
        compose.setContent { MaterialTheme { PaintBoxScreen({}, {}, vm) } }
        return vm
    }

    private fun openLab() {
        compose.onNodeWithTag("paintbox_menu").performClick()
        compose.onNodeWithTag("paintbox_led_lab").performClick()
        compose.onNodeWithTag("paintbox_lab_dialog").assertIsDisplayed()
    }

    @Test fun `the menu opens the lab and every switch starts as in the release build`() {
        editor()
        compose.onNodeWithTag("paintbox_lab_report").assertDoesNotExist()
        openLab()
        compose.onNodeWithTag("paintbox_lab_board").assertTextEquals("Connected: Kilter Board · API 2")
        compose.onNodeWithTag("paintbox_lab_moon_gap_100").assertIsSelected()
        compose.onNodeWithTag("paintbox_lab_moon_gap_20").assertIsNotSelected()
        compose.onNodeWithTag("paintbox_lab_moon_gap_5").assertIsNotSelected()
        // The app's own LED setting, as it is.
        compose.onNodeWithTag("paintbox_lab_moon_leds_BOTH").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("paintbox_lab_api2_packets").performScrollTo().assertIsOff()
        compose.onNodeWithTag("paintbox_lab_quantum_messages").performScrollTo().assertIsOff()
        compose.onNodeWithTag("paintbox_lab_reset").assertIsNotEnabled()
        compose.onNodeWithText("Nothing sent yet.").performScrollTo().assertIsDisplayed()
        assertTrue(LedLab.switches.value.release)
    }

    @Test fun `the switches set the lab, the LED choice sets the app's setting, and one button brings the release back`() {
        val vm = editor()
        openLab()
        compose.onNodeWithTag("paintbox_lab_moon_gap_5").performScrollTo().performClick()
        compose.onNodeWithTag("paintbox_lab_api2_packets").performScrollTo().performClick()
        compose.onNodeWithTag("paintbox_lab_quantum_messages").performScrollTo().performClick()
        assertEquals(LedLab.Switches(moonGapMs = 5L, api2SeveralPackets = true, quantumSeveralMessages = true), LedLab.switches.value)
        compose.onNodeWithTag("paintbox_lab_quantum_messages").performScrollTo().assertIsOn()
        compose.onNodeWithTag("paintbox_lab_moon_gap_5").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("paintbox_lab_api2_packets").performScrollTo().assertIsOn()
        compose.onNodeWithTag("paintbox_lab_moon_leds_BELOW").performScrollTo().performClick()
        verify { vm.moonLedMode(MoonBoardLedMode.BELOW) }
        compose.onNodeWithTag("paintbox_lab_reset").assertIsEnabled().performClick()
        assertTrue(LedLab.switches.value.release)
        compose.onNodeWithTag("paintbox_lab_moon_gap_100").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("paintbox_lab_api2_packets").performScrollTo().assertIsOff()
        compose.onNodeWithTag("paintbox_lab_quantum_messages").performScrollTo().assertIsOff()
    }

    @Test fun `after a send its report stands on the screen and in the lab`() {
        val frame = BoardPacketEncoder(3).encodeClimb(listOf(1 to 0xff, 2 to 0xff, 3 to 0xff))
        var now = 0L
        LedLab.clock = { now }
        val recording = LedLab.begin()
        LedLab.frame(frame)
        frame.forEach { _ -> now += 40; LedLab.wrote(null) }
        LedLab.end(recording, BoardBrand.KILTER)
        editor()
        val bytes = frame.sumOf { it.size }
        compose.onNodeWithTag("paintbox_lab_report")
            .assertTextEquals("LEDs: 3 · Bytes: $bytes · Packets: 1 · Writes: ${frame.size} of ${frame.size} · " +
                "Time: 0.04 s · Write errors: none")
            .performClick()
        compose.onNodeWithTag("paintbox_lab_dialog").assertIsDisplayed()
        compose.onNodeWithTag("paintbox_lab_last_send").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Switches of this send: MoonBoard pause 100 ms, API 2 in several packets off, " +
            "Quantum in several messages off.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `a failed write and a refused send read as such`() {
        val frame = BoardPacketEncoder(3).encodeClimb((1..30).map { it to 0xff })
        var recording = LedLab.begin()
        LedLab.frame(frame)
        LedLab.wrote(null)
        LedLab.wrote(LedLab.FailureKind.NO_ANSWER)
        LedLab.end(recording, BoardBrand.KILTER)
        editor()
        compose.onNodeWithTag("paintbox_lab_report")
            .assertTextContains("Write errors: write 2 of ${frame.size}, no answer from the box within 5 s", substring = true)
        recording = LedLab.begin()
        LedLab.end(recording, BoardBrand.KILTER)
        compose.onNodeWithTag("paintbox_lab_report")
            .assertTextEquals("Nothing was written: the send was refused before its first write.")
        // What a Quantum controller refused the send with stands behind the numbers.
        recording = LedLab.begin()
        LedLab.frame(listOf(byteArrayOf(1, 0x43)))
        LedLab.wrote(null)
        LedLab.end(recording, BoardBrand.QUANTUM, boardAnswer = "ROUTE_IN_USE")
        compose.onNodeWithTag("paintbox_lab_report").assertTextContains("Answer of the board: ROUTE_IN_USE", substring = true)
    }
}
