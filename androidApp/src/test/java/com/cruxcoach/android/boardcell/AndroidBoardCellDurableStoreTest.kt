package com.cruxcoach.android.boardcell

import android.content.Context
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidBoardCellDurableStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs by lazy {
        context.getSharedPreferences("board_cell_safety_v2", Context.MODE_PRIVATE)
    }

    @Before fun setUp() { prefs.edit().clear().commit() }
    @After fun tearDown() { prefs.edit().clear().commit() }

    @Test fun `durable command deduplication is bounded`() {
        val store = AndroidBoardCellDurableStore(context)
        repeat(300) { index ->
            store.recordAck(BoardCommandAck(
                commandId = "command-${index.toString().padStart(4, '0')}",
                status = BoardCommandStatus.COMMITTED,
                cellId = BoardCellId("cell"),
                epoch = 1,
                controllerTerm = 1,
            ))
        }

        assertNull(store.commandAck("command-0000"))
        assertNotNull(store.commandAck("command-0299"))
        assertEquals(256, prefs.all.keys.count { it.startsWith("ack:") })
    }

    @Test fun `cell lookup returns exact valid snapshot and rejects ambiguous board bindings`() {
        val store = AndroidBoardCellDurableStore(context)
        val cell = BoardCellId("cell")
        val first = BoardCellSnapshot(
            cellId = cell,
            physicalBoardId = PhysicalBoardId("board-one"),
            epoch = 1,
            sequence = 2,
            controllerId = "controller",
            lineageId = "lineage",
            members = setOf("controller"),
        ).withComputedHash()
        store.persistSnapshot(first)

        assertEquals(first, store.snapshotForCell(cell))
        assertNull(store.snapshotForCell(BoardCellId("other-cell")))

        // A cell id is supposed to map to one physical board. Local storage
        // corruption must fail closed instead of depending on map iteration.
        store.persistSnapshot(first.copy(
            physicalBoardId = PhysicalBoardId("board-two"),
            stateHash = "",
        ).withComputedHash())
        assertNull(store.snapshotForCell(cell))
    }
}
