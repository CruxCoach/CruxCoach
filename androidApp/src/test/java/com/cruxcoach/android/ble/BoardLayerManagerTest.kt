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
        // All four local slots remain available as a staging rack. The fourth
        // assignment cannot be transmitted until the foreign player leaves.
        assertEquals(3, manager.nextAvailableSlot(BoardBrand.QUANTUM))
        manager.assignPreview(layer(manager, 3))
        assertFalse(manager.hasControllerCapacityFor(3))
        assertFalse(manager.canProjectAll())
    }

    @Test fun `preview assignment is local only and preserves physical replacement state`() {
        val manager = BoardLayerManager(context)
        val original = layer(manager, 0)
        manager.beginProjection(original)
        manager.confirmProjection(original.planKey())

        val replacement = layer(manager, 0).copy(
            climbUuid = "20000000-0000-0000-0000-000000000001",
            routeUuid = "30000000-0000-0000-0000-000000000001",
        )
        manager.assignPreview(replacement)

        val staged = manager.state.value.layers.single()
        assertEquals(BoardLayerStatus.PREVIEW, staged.status)
        assertEquals(replacement.routeUuid, staged.routeUuid)
        assertEquals(original.routeUuid, staged.confirmedRouteUuid)
        assertEquals(1, manager.state.value.occupiedCount)
        assertTrue(manager.hasControllerCapacityFor(0))
        assertFalse(manager.removePreview(0))
    }

    @Test fun `controller refresh and hydration cannot replace a staged replacement`() {
        val manager = BoardLayerManager(context)
        val original = layer(manager, 0).copy(
            climbName = "Old climb",
            holds = listOf(BoardHold(10, 1), BoardHold(11, 2)),
        )
        manager.assignPreview(original)
        manager.confirmProjection(original.planKey())
        val replacement = layer(manager, 0, manager.defaultColor(1)).copy(
            climbUuid = "20000000-0000-0000-0000-000000000001",
            routeUuid = "30000000-0000-0000-0000-000000000001",
            climbName = "New climb",
            holds = listOf(BoardHold(20, 1), BoardHold(21, 2)),
        )
        manager.assignPreview(replacement)

        manager.reconcile(listOf(playerFor(original)))
        manager.hydrateControllerRoutes(mapOf(
            BoardLayerControllerRouteKey(original.routeUuid, original.userUuid) to
                BoardLayerRouteDetails(
                climbUuid = original.climbUuid,
                climbName = original.climbName,
                holds = original.holds,
                ),
        ))

        val layer = manager.state.value.layers.single()
        assertEquals(replacement.climbUuid, layer.climbUuid)
        assertEquals(replacement.routeUuid, layer.routeUuid)
        assertEquals(replacement.climbName, layer.climbName)
        assertEquals(replacement.holds, layer.holds)
        assertEquals(replacement.color, layer.color)
        assertEquals(original.routeUuid, layer.confirmedRouteUuid)
        assertEquals(original.climbName, layer.confirmedClimbName)
        assertEquals(original.holds, layer.confirmedHolds)
        assertEquals(original.color, layer.confirmedColor)
    }

    @Test fun `matching route with wrong physical color remains a replacement plan`() {
        val manager = BoardLayerManager(context)
        val planned = layer(manager, 0)
        manager.assignPreview(planned)
        val reportedColor = manager.defaultColor(1)

        manager.reconcile(listOf(playerFor(planned).copy(color = reportedColor and 0xffffff)))

        val layer = manager.state.value.layers.single()
        assertEquals(BoardLayerStatus.PREVIEW, layer.status)
        assertEquals(planned.color, layer.color)
        assertEquals(reportedColor, layer.confirmedColor)
        assertEquals(planned.holds, layer.confirmedHolds)
    }

    @Test fun `palette matches four unique eWalls controller colors`() {
        assertEquals(
            listOf(0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(), 0xFFFFFF00.toInt()),
            BoardLayerManager.LAYER_COLORS,
        )
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
        manager.state.value.layers.single().let { manager.removeOwned(it.planKey()) }
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

    @Test fun `conflict policy includes known foreign holds and fails closed for unknown routes`() {
        val known = ExternalBoardLayer(
            routeUuid = "known-route",
            userUuid = "foreign-user",
            color = managerColor(0),
            remainingSeconds = 10,
            holds = listOf(BoardHold(42, 1)),
        )
        val unknown = known.copy(routeUuid = "unknown-route", holds = null)
        val candidate = listOf(BoardHold(42, 2), BoardHold(99, 3))

        val knownAssessment = BoardLayerConflictPolicy.assess(
            candidate, emptyList(), listOf(known), replacingSlot = null,
        )
        assertEquals(1, knownAssessment.sharedHoldCount)
        assertEquals(0, knownAssessment.unknownLayerCount)

        val unknownAssessment = BoardLayerConflictPolicy.assess(
            candidate, emptyList(), listOf(unknown), replacingSlot = null,
        )
        assertEquals(0, unknownAssessment.sharedHoldCount)
        assertEquals(1, unknownAssessment.unknownLayerCount)
        assertFalse(unknownAssessment.canProveConflictFree)
    }

    @Test fun `empty resolved geometry remains unknown and blocks conflict proof`() {
        val manager = BoardLayerManager(context)
        val foreign = QuantumActivePlayer(
            routeId = "known-uuid-but-bad-frames",
            userId = "foreign-user",
            remainingSeconds = 10,
            color = 0x123456,
        )
        manager.reconcile(listOf(foreign))

        manager.hydrateControllerRoutes(
            mapOf(
                BoardLayerControllerRouteKey(foreign.routeId, foreign.userId) to
                    BoardLayerRouteDetails(
                    climbUuid = "local-climb",
                    climbName = "Malformed climb",
                    holds = emptyList(),
                    ),
            ),
        )

        val retained = manager.state.value.externalLayers.single()
        assertNull(retained.holds)
        assertEquals(
            1,
            BoardLayerConflictPolicy.assess(
                candidate = listOf(BoardHold(99, 1)),
                activeLayers = emptyList(),
                externalLayers = listOf(retained),
                replacingSlot = null,
            ).unknownLayerCount,
        )
    }

    @Test fun `replacement reserves planned and physical colors outside its own slot`() {
        val manager = BoardLayerManager(context)
        val original = layer(manager, 0, manager.defaultColor(0))
        manager.assignPreview(original)
        manager.confirmProjection(original.planKey())
        manager.assignPreview(layer(manager, 0, manager.defaultColor(1)))

        assertEquals(
            setOf(manager.defaultColor(0), manager.defaultColor(1)),
            manager.state.value.reservedLayerColors(),
        )
        assertTrue(manager.state.value.reservedLayerColors(replacingSlot = 0).isEmpty())
    }

    @Test fun `cancelling a replacement restores confirmed controller state without a write`() {
        val manager = BoardLayerManager(context)
        val original = layer(manager, 2).copy(
            climbName = "Still on the wall",
            holds = listOf(BoardHold(50, 1), BoardHold(51, 2)),
        )
        manager.assignPreview(original)
        manager.confirmProjection(original.planKey())
        val replacement = layer(manager, 2, manager.defaultColor(3)).copy(
            climbUuid = "20000000-0000-0000-0000-000000000002",
            routeUuid = "30000000-0000-0000-0000-000000000002",
            climbName = "Unsent replacement",
            holds = listOf(BoardHold(60, 1)),
        )
        manager.assignPreview(replacement)

        assertTrue(manager.cancelReplacement(original.slot))

        val restored = manager.state.value.layers.single()
        assertEquals(original.climbUuid, restored.climbUuid)
        assertEquals(original.routeUuid, restored.routeUuid)
        assertEquals(original.climbName, restored.climbName)
        assertEquals(original.color, restored.color)
        assertEquals(original.holds, restored.holds)
        assertEquals(BoardLayerStatus.CONFIRMED, restored.status)
        assertEquals(original.routeUuid, restored.confirmedRouteUuid)
        assertEquals(original.climbUuid, restored.confirmedClimbUuid)
        assertEquals(original.climbName, restored.confirmedClimbName)
        assertEquals(original.color, restored.confirmedColor)
        assertEquals(original.holds, restored.confirmedHolds)
        assertTrue(restored.controllerDetailsKnown)
        assertFalse(manager.cancelReplacement(original.slot))
    }

    @Test fun `stale completion cannot confirm fail or remove a replacement plan`() {
        val manager = BoardLayerManager(context)
        val original = layer(manager, 1)
        manager.assignPreview(original)
        val originalKey = original.planKey()
        assertTrue(manager.beginProjection(originalKey))

        val replacement = original.copy(
            climbUuid = "20000000-0000-0000-0000-000000000099",
            routeUuid = "30000000-0000-0000-0000-000000000099",
            planToken = java.util.UUID.randomUUID().toString(),
        )
        manager.assignPreview(replacement)

        val staleAssignment = replacement.copy(
            climbUuid = "20000000-0000-0000-0000-000000000100",
            routeUuid = "30000000-0000-0000-0000-000000000100",
            planToken = java.util.UUID.randomUUID().toString(),
        )

        assertFalse(manager.assignPreviewIfCurrent(staleAssignment, originalKey))
        assertFalse(manager.assignPreviewIfCurrent(staleAssignment, expectedCurrent = null))
        assertFalse(manager.confirmProjection(originalKey))
        assertFalse(manager.failProjection(originalKey))
        assertFalse(manager.removeOwned(originalKey))
        assertEquals(replacement.planKey(), manager.state.value.layers.single().planKey())
        assertEquals(BoardLayerStatus.PREVIEW, manager.state.value.layers.single().status)
    }

    @Test fun `owned direct route detail cannot hydrate a foreign duplicate UUID`() {
        val manager = BoardLayerManager(context)
        val owned = playerFor(layer(manager, 0))
        val foreign = owned.copy(
            userId = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff",
            color = 0x123456,
        )
        manager.reconcile(listOf(owned, foreign))

        manager.hydrateControllerRoutes(
            mapOf(
                BoardLayerControllerRouteKey(owned.routeId, owned.userId) to
                    BoardLayerRouteDetails(
                        climbUuid = owned.routeId,
                        climbName = "Owned community route",
                        holds = listOf(BoardHold(42, 1)),
                    ),
            ),
        )

        val local = manager.state.value.layers.single()
        val external = manager.state.value.externalLayers.single()
        assertEquals(listOf(BoardHold(42, 1)), local.confirmedHolds)
        assertTrue(local.controllerDetailsKnown)
        assertNull(external.holds)
        assertEquals(1, BoardLayerConflictPolicy.assess(
            candidate = listOf(BoardHold(99, 1)),
            activeLayers = manager.state.value.layers,
            externalLayers = manager.state.value.externalLayers,
            replacingSlot = local.slot,
        ).unknownLayerCount)
    }

    @Test fun `rack state is retained only for the same physical board and model`() {
        val manager = BoardLayerManager(context)
        val firstBoard = BoardLayerBoardIdentity("quantum:serial:first", 9201)
        manager.bindBoard(firstBoard)
        manager.assignPreview(layer(manager, 0))
        manager.reconcile(listOf(
            QuantumActivePlayer(
                routeId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                userId = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff",
                remainingSeconds = 12,
                color = 0x123456,
            ),
        ))

        manager.bindBoard(firstBoard)
        assertEquals(1, manager.state.value.layers.size)
        assertEquals(1, manager.state.value.externalLayers.size)

        manager.bindBoard(firstBoard.copy(productSizeId = 9202))
        assertTrue(manager.state.value.layers.isEmpty())
        assertTrue(manager.state.value.externalLayers.isEmpty())
        assertEquals(9202L, manager.state.value.board?.productSizeId)
    }

    @Test fun `disconnect retains roster but marks it as last known`() {
        val manager = BoardLayerManager(context)
        val foreign = QuantumActivePlayer(
            routeId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            userId = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff",
            remainingSeconds = 12,
            color = 0x123456,
        )
        manager.reconcile(listOf(foreign))
        manager.setQuantumSyncStatus(QuantumControllerSyncStatus.LIVE)

        manager.setQuantumSyncStatus(QuantumControllerSyncStatus.STALE)

        assertEquals(QuantumControllerSyncStatus.STALE, manager.state.value.quantumSyncStatus)
        assertEquals(foreign.routeId, manager.state.value.externalLayers.single().routeUuid)
    }

    @Test fun `later authoritative roster removes a climb sent by another client`() {
        val manager = BoardLayerManager(context)
        val foreign = QuantumActivePlayer(
            routeId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            userId = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff",
            remainingSeconds = 12,
            color = 0x123456,
        )
        manager.reconcile(listOf(foreign))
        assertEquals(1, manager.state.value.externalLayers.size)

        manager.reconcile(emptyList())

        assertTrue(manager.state.value.externalLayers.isEmpty())
    }

    private fun managerColor(slot: Int): Int = BoardLayerManager.LAYER_COLORS[slot]

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
