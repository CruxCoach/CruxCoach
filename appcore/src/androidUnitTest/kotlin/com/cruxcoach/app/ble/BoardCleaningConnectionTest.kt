package com.cruxcoach.app.ble

import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of Android's BoardCleaningConnectionTest: the real controller and
 * encoders over the fake central, with acknowledged writes. No radio or
 * controller hardware is claimed by these tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardCleaningConnectionTest {
    private val kilter = DiscoveredBoard("Test board", "123", 3, "id-kilter", -40, BoardBrand.KILTER)
    private val moon = DiscoveredBoard("MoonBoard A", "", 0, "id-moon", -50, BoardBrand.MOONBOARD)
    private val holds = listOf(BoardHold(1, 12), BoardHold(2, 13))

    private class Rig(scope: TestScope) {
        val central = FakeBleCentral { scope.testScheduler.currentTime }
        val controller = BoardConnectionController(
            central, scope.backgroundScope, FixedClock(1_000),
            cleaning = BoardCleaningSession(today = { "2026-10-10" }),
        )

        fun bytes(): ByteArray = central.writes.flatMap { it.bytes }.toByteArray()
    }

    private fun TestScope.connected(board: DiscoveredBoard): Rig {
        val rig = Rig(this)
        rig.controller.connect(board)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, rig.controller.state.value.connection)
        return rig
    }

    @Test fun `cleaning fences every normal write and restores exact previous packet`() = runTest {
        val rig = connected(kilter)
        val controller = rig.controller
        assertEquals(BoardSendResult.OK, controller.sendClimb(holds, mapOf(1 to 5, 2 to 8)))
        val previous = rig.bytes()
        assertEquals(2, controller.cleaningState.value.holdCount)
        rig.central.writes.clear()
        assertFalse(controller.startCleaning("another-controller"))
        assertTrue(rig.central.writes.isEmpty())
        assertTrue(controller.startCleaning(kilter.identifier))
        // The cleaning frame: the collected LEDs in the cleaning colour.
        val expected = BoardPacketEncoder(3, 2).encodeClimb(listOf(5 to BoardPacketEncoder.COLOR_CLEANING, 8 to BoardPacketEncoder.COLOR_CLEANING))
        assertEquals(expected.flatMap { it.toList() }, rig.bytes().toList())
        val count = rig.central.writes.size
        assertEquals(BoardSendResult.CLEANING_ACTIVE, controller.sendClimb(holds, mapOf(1 to 99, 2 to 100)))
        assertEquals(BoardSendResult.CLEANING_ACTIVE, controller.clearBoard())
        assertEquals(count, rig.central.writes.size)
        assertEquals(2, controller.cleaningState.value.holdCount)
        rig.central.writes.clear()
        assertTrue(controller.finishCleaning(kilter.identifier, markCleaned = false))
        assertEquals(previous.toList(), rig.bytes().toList())
        assertEquals(2, controller.cleaningState.value.holdCount)
    }

    @Test fun `failed transmissions do not enter the daily collection`() = runTest {
        val rig = connected(kilter)
        rig.central.writeSucceeds = false
        assertEquals(BoardSendResult.WRITE_FAILED, rig.controller.sendClimb(holds, mapOf(1 to 5, 2 to 8)))
        assertEquals(0, rig.controller.cleaningState.value.holdCount)
        rig.central.writeSucceeds = true
        assertEquals(BoardSendResult.OK, rig.controller.sendClimb(holds, mapOf(1 to 5)))
        assertEquals(1, rig.controller.cleaningState.value.holdCount, "an unmapped hold is not lit, so not collected")
    }

    @Test fun `MoonBoard dual LEDs count holds once and restore original roles and LED mode`() = runTest {
        val rig = connected(moon)
        val controller = rig.controller
        assertEquals(BoardSendResult.OK, controller.sendMoonBoardClimb("p12r42p24r44", MoonBoardVariant.MOONBOARD_2016, MoonBoardLedMode.BOTH))
        val original = rig.bytes()
        assertEquals(2, controller.cleaningState.value.holdCount)
        rig.central.writes.clear()
        assertTrue(controller.startCleaning(moon.identifier))
        assertEquals("l#P1,P33#", rig.bytes().decodeToString())
        rig.central.writes.clear()
        assertTrue(controller.finishCleaning(moon.identifier, markCleaned = true))
        assertEquals(original.toList(), rig.bytes().toList())
        assertEquals(0, controller.cleaningState.value.holdCount)
    }

    @Test fun `a disconnect ends cleaning and an older controller cleans in sections`() = runTest {
        val rig = connected(kilter)
        rig.controller.sendClimb(holds, mapOf(1 to 5, 2 to 8))
        assertTrue(rig.controller.startCleaning(kilter.identifier))
        assertTrue(rig.controller.cleaningState.value.active)
        rig.controller.disconnect()
        runCurrent()
        assertFalse(rig.controller.cleaningState.value.active)
        assertFalse(rig.controller.cleaningState.value.available)
        assertFalse(rig.controller.startCleaning(kilter.identifier), "nothing is lit without a link")

        // API 2 with two LEDs per hold: Android's 500-LED budget, 250 holds per section.
        val legacy = connected(kilter.copy(apiLevel = 2, identifier = "id-legacy"))
        assertEquals(250, legacy.controller.cleaningState.value.holdLimit)
        val moonRig = connected(moon)
        assertEquals(Int.MAX_VALUE, moonRig.controller.cleaningState.value.holdLimit)
    }

    @Test fun `active cleaning keeps the link from idling out`() = runTest {
        val rig = connected(kilter)
        rig.controller.autoDisconnectSeconds = 60
        assertTrue(rig.controller.idleDisconnectArmed)
        rig.controller.sendClimb(holds, mapOf(1 to 5, 2 to 8))
        assertTrue(rig.controller.startCleaning(kilter.identifier))
        assertFalse(rig.controller.idleDisconnectArmed, "the lit holds must stay until the user finishes")
        assertTrue(rig.controller.finishCleaning(kilter.identifier, markCleaned = false))
        assertTrue(rig.controller.idleDisconnectArmed)
    }
}
