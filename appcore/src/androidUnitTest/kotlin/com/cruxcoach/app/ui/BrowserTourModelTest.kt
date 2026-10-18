package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserTourModelTest {
    private val store = MemoryKeyValueStore()

    @Test
    fun `the tour is off until it is started`() {
        val tour = BrowserTourModel(store)
        assertFalse(tour.isActive)
        assertEquals(BrowserTourModel.STEP_INACTIVE, tour.step)
    }

    @Test
    fun `the steps run in Android's order and end`() {
        val tour = BrowserTourModel(store)
        tour.start(replay = false)
        val seen = mutableListOf(tour.step)
        repeat(10) {
            tour.advance(from = tour.step)
            seen += tour.step
        }
        assertEquals(
            listOf("board", "connect", "angle", "filter", "open", "project", "log", "logbook", "entry", "done"),
            seen.distinct(),
        )
        assertFalse(tour.isActive, "the tour has to end on its own")
    }

    @Test
    fun `a repeated action cannot skip a step`() {
        val tour = BrowserTourModel(store)
        tour.start(replay = false)
        tour.advance(from = "board")
        tour.advance(from = "board")
        assertEquals("connect", tour.step)
        tour.advance(from = "connect")
        assertEquals("angle", tour.step)
        // The same action again: the tour must not jump ahead.
        tour.advance(from = "connect")
        assertEquals("angle", tour.step)
    }

    @Test
    fun `no board here means the connect step is never offered again`() {
        val tour = BrowserTourModel(store)
        tour.start(replay = false)
        tour.advance(from = "board")
        assertEquals("connect", tour.step)
        tour.deferBluetooth()
        assertEquals("angle", tour.step)

        tour.skip()
        val again = BrowserTourModel(store)
        again.start(replay = false)
        assertEquals("board", again.step, "the board picker is always the first step")
        again.advance(from = "board")
        assertEquals("angle", BrowserTourModel(store).step, "a second start must skip Bluetooth")

        // Replaying from settings offers it again on purpose.
        val replayed = BrowserTourModel(store)
        replayed.start(replay = true)
        replayed.advance(from = "board")
        assertEquals("connect", replayed.step)
    }

    @Test
    fun `deferring Bluetooth on the board step keeps the board step`() {
        val tour = BrowserTourModel(store)
        tour.start(replay = false)
        tour.deferBluetooth()
        assertEquals("board", tour.step)
        tour.advance(from = "board")
        assertEquals("angle", tour.step)
    }

    @Test
    fun `the step survives a restart, and a logged climb is remembered`() {
        val tour = BrowserTourModel(store)
        tour.start(replay = false)
        tour.advance(from = "board")
        tour.advance(from = "connect")
        tour.logged("climb-42")

        val restarted = BrowserTourModel(store)
        assertEquals("logbook", restarted.step)
        assertEquals("climb-42", restarted.loggedEntryUuid)
        assertTrue(restarted.isActive)
    }

    @Test
    fun `an unknown stored step is treated as no tour`() {
        store.values[BrowserTourModel.KEY_STEP] = "whatever"
        assertEquals(BrowserTourModel.STEP_INACTIVE, BrowserTourModel(store).step)
    }
}
