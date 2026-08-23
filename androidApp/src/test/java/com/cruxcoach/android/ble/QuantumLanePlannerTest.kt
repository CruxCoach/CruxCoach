package com.cruxcoach.android.ble

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The map from occurrences to lanes, and the things it refuses to do.
 *
 * Two of them carry the weight. Following the list never touches the wall: an
 * entry that is removed loses its preference and keeps its light. And a plan is
 * for one controller and one model, so walking to a different wall empties it
 * rather than offering a diode plan for holds that are not there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QuantumLanePlannerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var layers: BoardLayerManager
    private lateinit var planner: QuantumLanePlanner

    private val boardA = BoardLayerBoardIdentity("quantum:aa:bb", 9201)
    private val boardB = BoardLayerBoardIdentity("quantum:cc:dd", 9201)
    private val boardASmaller = BoardLayerBoardIdentity("quantum:aa:bb", 9202)

    @Before
    fun setUp() {
        context.getSharedPreferences("board_layer_identity", Context.MODE_PRIVATE)
            .edit().clear().commit()
        layers = BoardLayerManager(context)
        planner = QuantumLanePlanner(layers)
    }

    @Test
    fun `assigning is idempotent and a later change wins`() {
        planner.assign("e1", 1)
        planner.assign("e1", 1)

        assertEquals(1, planner.laneFor("e1"))

        planner.assign("e1", 3)
        assertEquals(3, planner.laneFor("e1"))
    }

    @Test
    fun `duplicate occurrences of one climb keep separate lanes`() {
        planner.assign("zombie-1", 0)
        planner.assign("zombie-2", 2)

        assertEquals(0, planner.laneFor("zombie-1"))
        assertEquals(2, planner.laneFor("zombie-2"))
    }

    @Test
    fun `removing an entry drops its lane preference and not its light`() {
        planner.assign("e1", 0)
        planner.noteSent(lane = 0, entryId = "e1", success = true)
        planner.assign("e2", 1)

        planner.retainEntries(setOf("e2"))

        assertNull(planner.laneFor("e1"))
        assertEquals(1, planner.laneFor("e2"))
        // The write record survives the list edit; that is what makes the lane
        // reportable as still lit for an entry nobody can point at any more.
        assertEquals("e1", planner.state.value.entryForLane(0))
        assertEquals(listOf(0), planner.orphanedLanes(setOf("e2")))
    }

    @Test
    fun `clearing the list orphans every written lane and removes none`() {
        planner.noteSent(0, "e1", success = true)
        planner.noteSent(2, "e2", success = true)

        planner.retainEntries(emptySet())

        assertEquals(listOf(0, 2), planner.orphanedLanes(emptySet()))
        assertEquals("e1", planner.state.value.entryForLane(0))
        assertEquals("e2", planner.state.value.entryForLane(2))
    }

    @Test
    fun `a refused write records nothing and clears the in-flight marker`() {
        planner.noteSent(0, "previous", success = true)
        planner.noteSending(0, "attempt")

        assertEquals(0, planner.state.value.sendingLane)
        assertEquals("attempt", planner.state.value.sendingEntryId)

        planner.noteSent(0, "attempt", success = false)

        assertNull(planner.state.value.sendingLane)
        // A lane that refused a write is still showing what it showed before.
        assertEquals("previous", planner.state.value.entryForLane(0))
    }

    @Test
    fun `a physical removal drops the label for that lane only`() {
        planner.noteSent(0, "e1", success = true)
        planner.noteSent(1, "e2", success = true)

        planner.noteRemoved(0)

        assertNull(planner.state.value.entryForLane(0))
        assertEquals("e2", planner.state.value.entryForLane(1))
    }

    @Test
    fun `the same board keeps the plan across a reconnect`() {
        layers.bindBoard(boardA)
        planner.syncBoard()
        planner.assign("e1", 2)

        // A disconnect resolves no identity at all; that is not a board change.
        planner.syncBoard(null)
        layers.bindBoard(boardA)
        planner.syncBoard()

        assertEquals(2, planner.laneFor("e1"))
    }

    @Test
    fun `a different controller empties the plan`() {
        layers.bindBoard(boardA)
        planner.syncBoard()
        planner.assign("e1", 2)
        planner.noteSent(2, "e1", success = true)

        layers.bindBoard(boardB)
        planner.syncBoard()

        assertNull(planner.laneFor("e1"))
        assertNull(planner.state.value.entryForLane(2))
    }

    @Test
    fun `the same controller on a different model empties the plan`() {
        // Placement ids only mean something within a model: the same preview
        // on the next size up is a different set of holds.
        layers.bindBoard(boardA)
        planner.syncBoard()
        planner.assign("e1", 0)

        layers.bindBoard(boardASmaller)
        planner.syncBoard()

        assertNull(planner.laneFor("e1"))
    }

    @Test
    fun `leaving the cell clears the plan and keeps the board binding`() {
        layers.bindBoard(boardA)
        planner.syncBoard()
        planner.assign("e1", 1)
        val boardKey = planner.state.value.boardKey

        planner.clear()

        assertNull(planner.laneFor("e1"))
        assertEquals(boardKey, planner.state.value.boardKey)
        assertTrue(planner.state.value.committed.isEmpty())
    }

    @Test
    fun `releasing a preference leaves everything else alone`() {
        planner.assign("e1", 0)
        planner.assign("e2", 1)

        planner.release("e1")

        assertNull(planner.laneFor("e1"))
        assertEquals(1, planner.laneFor("e2"))
    }
}
