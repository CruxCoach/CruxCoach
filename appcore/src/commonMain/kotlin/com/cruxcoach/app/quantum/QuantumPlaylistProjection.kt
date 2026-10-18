package com.cruxcoach.app.quantum

import com.cruxcoach.app.ble.BoardSendResult
import com.cruxcoach.app.ble.hasCompleteQuantumLedMapping
import com.cruxcoach.app.ble.hasConfirmableQuantumDiodeCount
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Port of Android `SessionQueueManager.sendQuantumPlaylistLayer`: a private
 * playlist's current climb goes through the same stable layer identities as
 * the climb detail. Only the single unambiguous slot is used — the climb's own
 * layer or the first free one; a full, unknown or conflicting wall needs an
 * explicit choice in the detail rack and never evicts another climber.
 */
internal class QuantumPlaylistProjection(
    private val layers: BoardLayerManager,
    private val link: QuantumLink,
    private val boardRepository: BoardRepository,
    private val io: CoroutineDispatcher,
) {
    /** Android sends the queue's climb under one lock: an advancing playlist never interleaves two projections. */
    private val lock = Mutex()

    /** True only once the controller confirmed the projection. */
    suspend fun send(climbUuid: String, climbName: String, layoutId: Long, angle: Int, holds: List<BoardHold>): Boolean =
        lock.withLock { sendLocked(climbUuid, climbName, layoutId, angle, holds) }

    private suspend fun sendLocked(climbUuid: String, climbName: String, layoutId: Long, angle: Int, holds: List<BoardHold>): Boolean {
        if (!hasConfirmableQuantumDiodeCount(holds)) return false
        val connection = link.connection.value
        if (connection.connectedBoard?.boardBrand != BoardBrand.QUANTUM) return false
        val expectedBoard = QuantumRoster.connectedLayerBoard(connection) ?: return false
        val model = connection.quantumModel ?: return false
        if (layoutId != model.layoutId) return false
        layers.bindBoard(expectedBoard)

        // The controller is shared mutable state: fresh truth before allocating capacity.
        if (!QuantumRoster.refreshAndHydrate(layers, link, boardRepository, io)) return false
        val ledMap = withContext(io) {
            boardRepository.getPlacementLedMap(expectedBoard.productSizeId.toInt(), BoardBrand.QUANTUM.wireValue)
        }
        if (!layers.isBoundTo(expectedBoard) || !hasCompleteQuantumLedMapping(holds, ledMap)) return false

        val existing = layers.layerForClimb(climbUuid)
        val slot = existing?.slot ?: layers.nextAvailableSlot(BoardBrand.QUANTUM) ?: return false
        val expectedCurrent = layers.state.value.layers.firstOrNull { it.slot == slot }?.planKey()
        if (!layers.hasControllerCapacityFor(slot)) return false
        val rack = layers.state.value
        if (!BoardLayerConflictPolicy.assess(holds, rack.layers, rack.externalLayers, slot).canProveConflictFree) return false

        val color = existing?.color ?: layers.availableColors().firstOrNull() ?: return false
        val routeUuid = withContext(io) { boardRepository.getQuantumExternalRouteUuid(climbUuid) } ?: climbUuid
        val layer = BoardClimbLayer(
            slot = slot,
            climbUuid = climbUuid,
            routeUuid = routeUuid,
            climbName = climbName,
            angle = angle,
            userUuid = layers.identityForSlot(slot),
            color = color,
            holds = holds,
            status = BoardLayerStatus.PREVIEW,
        )
        if (!layers.assignPreviewIfCurrent(layer, expectedCurrent)) return false
        val expectedPlan = layer.planKey()

        // Catalogue hydration took disk time: pull truth again at the write boundary
        // and repeat every safety check, so another client cannot slip in between.
        if (!QuantumRoster.refreshAndHydrate(layers, link, boardRepository, io)) {
            layers.failProjection(expectedPlan)
            return false
        }
        val expectedPlayers = link.controller.value.players
        val current = layers.state.value
        if (!layers.hasControllerCapacityFor(slot) ||
            !current.matchesQuantumPlayers(expectedPlayers) ||
            layer.color in current.reservedLayerColors(replacingSlot = slot) ||
            !BoardLayerConflictPolicy.assess(holds, current.layers, current.externalLayers, slot).canProveConflictFree
        ) {
            layers.failProjection(expectedPlan)
            return false
        }
        if (!layers.beginProjection(expectedPlan)) return false
        val result = link.sendClimb(holds, ledMap, routeUuid, layer.userUuid, color, expectedPlayers, expectedBoard)
        val sent = result == BoardSendResult.OK
        if (sent) layers.confirmProjection(expectedPlan) else layers.failProjection(expectedPlan)
        return sent
    }
}
