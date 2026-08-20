package com.cruxcoach.android.ble

import android.content.Context
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.QuantumActivePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardLayerManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearIdentity() {
        context.getSharedPreferences("board_layer_identity", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun `identities are stable per installation and distinct per slot`() {
        val first = BoardLayerManager(context)
        val ids = (0 until 4).map(first::identityForSlot)
        val second = BoardLayerManager(context)
        assertEquals(4, ids.toSet().size)
        assertEquals(ids, (0 until 4).map(second::identityForSlot))
        assertFalse(ids.any { it == "00000000-0000-0000-0000-000000000000" })
        assertTrue(ids.all { java.util.UUID.fromString(it).version() == 4 })
        assertEquals(
            BoardLayerManager.deriveFipsSafeUuid("same"),
            BoardLayerManager.deriveFipsSafeUuid("same"),
        )
    }

    @Test fun `Quantum has four slots while all other boards stay single layer`() {
        val manager = BoardLayerManager(context)
        assertEquals(4, manager.capabilities(BoardBrand.QUANTUM).maxLayers)
        assertTrue(manager.capabilities(BoardBrand.QUANTUM).independentRemoval)
        BoardBrand.entries.filterNot { it == BoardBrand.QUANTUM }.forEach {
            assertEquals(1, manager.capabilities(it).maxLayers)
            assertFalse(manager.capabilities(it).independentRemoval)
        }
    }

    @Test fun `four owned layers exhaust capacity and replacing owned slot stays possible`() {
        val manager = BoardLayerManager(context)
        repeat(4) { manager.beginProjection(layer(manager, it)) }
        assertNull(manager.nextAvailableSlot(BoardBrand.QUANTUM))
        assertEquals(2, manager.nextAvailableSlot(BoardBrand.QUANTUM, preferred = 2))
        assertEquals(4, manager.state.value.layers.size)
    }

    @Test fun `foreign controller player consumes capacity but is never claimed or removed`() {
        val manager = BoardLayerManager(context)
        repeat(3) { manager.beginProjection(layer(manager, it)) }
        manager.reconcile(listOf(
            *(0 until 3).map { playerFor(layer(manager, it)) }.toTypedArray(),
            QuantumActivePlayer(
                routeId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                userId = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff",
                remainingSeconds = 12,
                color = 0x123456,
            ),
        ))
        assertEquals(1, manager.state.value.externalLayers.size)
        assertEquals(4, manager.state.value.occupiedCount)
        assertNull(manager.nextAvailableSlot(BoardBrand.QUANTUM))
    }

    @Test fun `turn off intermediate snapshot preserves sending transaction then confirms it`() {
        val manager = BoardLayerManager(context)
        val layer = layer(manager, 0)
        manager.beginProjection(layer)
        manager.reconcile(emptyList())
        assertEquals(BoardLayerStatus.SENDING, manager.state.value.layers.single().status)
        manager.reconcile(listOf(playerFor(layer)))
        val confirmed = manager.state.value.layers.single()
        assertEquals(BoardLayerStatus.CONFIRMED, confirmed.status)
        assertEquals(layer.color, confirmed.color)
    }

    @Test fun `reconnect reconstructs installation owned route without treating it as foreign`() {
        val first = BoardLayerManager(context)
        val player = playerFor(layer(first, 3))
        val afterRestart = BoardLayerManager(context)
        afterRestart.reconcile(listOf(player))
        val recovered = afterRestart.state.value.layers.single()
        assertEquals(3, recovered.slot)
        assertEquals(player.routeId, recovered.routeUuid)
        assertEquals(0xff000000.toInt() or player.color, recovered.color)
        assertTrue(recovered.ownedByThisInstallation)
        assertTrue(afterRestart.state.value.externalLayers.isEmpty())
    }

    @Test fun `used colors are excluded but removal frees them`() {
        val manager = BoardLayerManager(context)
        val firstColor = manager.defaultColor(0)
        manager.beginProjection(layer(manager, 0, firstColor))
        assertFalse(firstColor in manager.availableColors())
        manager.removeOwned(0)
        assertTrue(firstColor in manager.availableColors())
        assertNotEquals(manager.defaultColor(0), manager.defaultColor(1))
    }

    @Test fun `overlap policy ignores replaced slot and catches every other shared diode`() {
        val manager = BoardLayerManager(context)
        val first = layer(manager, 0).copy(holds = listOf(BoardHold(1, 1), BoardHold(2, 2)))
        val second = layer(manager, 1).copy(holds = listOf(BoardHold(3, 1), BoardHold(4, 2)))
        val candidate = listOf(BoardHold(1, 1), BoardHold(3, 2), BoardHold(5, 3))
        assertEquals(2, BoardLayerConflictPolicy.sharedHoldCount(candidate, listOf(first, second), null))
        assertEquals(1, BoardLayerConflictPolicy.sharedHoldCount(candidate, listOf(first, second), 0))
        assertEquals(1, BoardLayerConflictPolicy.sharedHoldCount(candidate, listOf(first, second), 1))
    }

    private fun layer(manager: BoardLayerManager, slot: Int, color: Int = manager.defaultColor(slot)) =
        BoardClimbLayer(
            slot = slot,
            climbUuid = "00000000-0000-0000-0000-${(slot + 1).toString().padStart(12, '0')}",
            routeUuid = "10000000-0000-0000-0000-${(slot + 1).toString().padStart(12, '0')}",
            climbName = "Climb ${slot + 1}",
            angle = 40,
            userUuid = manager.identityForSlot(slot),
            color = color,
            holds = listOf(BoardHold(slot + 1, 12)),
            status = BoardLayerStatus.PREVIEW,
        )

    private fun playerFor(layer: BoardClimbLayer) = QuantumActivePlayer(
        routeId = layer.routeUuid,
        userId = layer.userUuid,
        remainingSeconds = 0,
        color = layer.color and 0xffffff,
    )
}
