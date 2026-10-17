package com.cruxcoach.app.ui

import com.cruxcoach.app.ble.BoardCleaningStorage
import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.FakeBleCentral
import com.cruxcoach.app.ble.LedHoldColors
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.domain.board.BoardHold
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "Clean holds" sheet's model over the real presenter and the fake central. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardCleaningScreenModelTest {
    private val id = "11111111-2222-3333-4444-555555555555"

    @Test fun `a sent problem is collected then lit stopped cleaned and kept across a restart`() = runTest {
        val central = FakeBleCentral { testScheduler.currentTime }
        val store = MemoryKeyValueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        // FixedClock(seconds): noon UTC, the same local date in the test zone.
        val presenter = BoardConnectionPresenter(central, store, FixedClock(1_791_115_200), dispatcher)
        val model = BoardCleaningScreenModel(presenter, dispatcher)
        runCurrent()
        assertFalse(model.currentState.connected)
        assertFalse(model.currentState.available)

        presenter.startScan()
        central.listener!!.onAdvertisement(id, "Kilter Board#A1@3", null, -60)
        runCurrent()
        presenter.connect(id)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(model.currentState.connected)
        assertTrue(model.currentState.ready)
        assertTrue(model.currentState.available)
        assertEquals("Kilter Board", model.currentState.boardName)
        assertEquals(0, model.currentState.holdCount)
        assertEquals(0, model.currentState.holdLimit, "API 3 lights every hold at once")
        assertFalse(model.currentState.sectioned)

        presenter.sendClimb(listOf(BoardHold(1, 12), BoardHold(2, 13)), mapOf(1 to 5, 2 to 8),
            LedHoldColors.kilterStandard().toRoleColorMap(), "kilter")
        runCurrent()
        assertEquals(2, model.currentState.holdCount)

        central.writes.clear()
        model.start()
        runCurrent()
        assertTrue(model.currentState.active)
        assertFalse(model.currentState.busy)
        assertFalse(model.currentState.error)
        assertTrue(central.writes.isNotEmpty())

        model.finish(markCleaned = false)
        runCurrent()
        assertFalse(model.currentState.active)
        assertEquals(2, model.currentState.holdCount, "stop keeps the collection")
        assertTrue(store.values.containsKey(BoardCleaningStorage.KEY), "the day survives an app restart")

        // A failed write is reported and keeps everything.
        central.writeSucceeds = false
        model.start()
        runCurrent()
        assertTrue(model.currentState.error)
        assertTrue(model.currentState.active, "stop/retry stay available until the board is restored")
        central.writeSucceeds = true
        model.finish(markCleaned = true)
        runCurrent()
        assertFalse(model.currentState.error)
        assertFalse(model.currentState.active)
        assertEquals(0, model.currentState.holdCount, "cleaned resets the collection")

        // Without a board nothing is attempted, and the sheet says so.
        presenter.disconnect()
        runCurrent()
        model.start()
        runCurrent()
        assertTrue(model.currentState.error)
        assertFalse(model.currentState.connected)
        model.close()
        presenter.dispose()
    }

    @Test fun `an open sheet follows the local date boundary every thirty seconds`() = runTest {
        val central = FakeBleCentral { testScheduler.currentTime }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = FixedClock(1_791_115_200)
        val presenter = BoardConnectionPresenter(central, MemoryKeyValueStore(), clock, dispatcher)
        val model = BoardCleaningScreenModel(presenter, dispatcher)
        presenter.startScan()
        central.listener!!.onAdvertisement(id, "Kilter Board#A1@3", null, -60)
        runCurrent()
        presenter.connect(id)
        advanceTimeBy(1_000); runCurrent()
        presenter.sendClimb(listOf(BoardHold(1, 12)), mapOf(1 to 5), LedHoldColors.kilterStandard().toRoleColorMap(), "kilter")
        runCurrent()
        assertEquals(1, model.currentState.holdCount)

        model.startDayRefresh()
        model.startDayRefresh() // idempotent
        runCurrent()
        clock.seconds += 24 * 60 * 60 // the next day
        advanceTimeBy(29_000); runCurrent()
        assertEquals(1, model.currentState.holdCount, "re-read every 30 s, not continuously")
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0, model.currentState.holdCount, "yesterday's holds are not today's collection")

        model.stopDayRefresh()
        model.close()
        presenter.dispose()
    }
}
