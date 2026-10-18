package com.cruxcoach.app.ui

import com.cruxcoach.app.ble.MAX_LAYER_IDENTITIES
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.ble.QuantumControllerSyncStatus
import com.cruxcoach.app.quantum.BoardLayerState
import com.cruxcoach.app.quantum.QuantumClimbSnapshot
import com.cruxcoach.app.quantum.QuantumLayerController
import com.cruxcoach.app.quantum.QuantumLayerUiPolicy
import com.cruxcoach.app.quantum.QuantumLayerVisualState
import com.cruxcoach.app.quantum.QuantumSendState
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One of the four numbered Quantum layers (Android's layer rack row). */
class QuantumLayerRowUi(
    /** 0-based; Swift shows [number]. */
    val slot: Int,
    val number: Int,
    /** free | planned | sending | onBoard | replacing | failed | unknown | foreign */
    val stateCode: String,
    /** Opaque ARGB of the local plan (or the foreign route); 0 when none. */
    val plannedArgb: Long,
    /** Opaque ARGB the controller still reports; 0 when none. */
    val confirmedArgb: Long,
    val climbName: String,
    /** What is still physically lit while a replacement is planned; empty otherwise. */
    val confirmedClimbName: String,
    val isCurrentClimb: Boolean,
    /** An own plan that can be transmitted. */
    val canSend: Boolean,
    /** An own layer: dropped locally, or removed from the wall after a confirmation. */
    val canRemove: Boolean,
    /** Removal turns a lit route off; Swift asks first (Android quantum_layer_remove_live_*). */
    val removeIsLive: Boolean,
    val canCancelReplacement: Boolean,
)

/** One diode on the wall picture; [argbs] holds one colour, or one per layer sharing the diode. */
class QuantumWallHoldUi(val placementId: Int, val x: Float, val y: Float, val argbs: List<Long>)

class QuantumLayersScreenState(
    /** The climb is a Quantum climb with holds. */
    val hasClimb: Boolean,
    val climbName: String,
    /** Connected to a Quantum controller. */
    val connected: Boolean,
    /** unsynced | syncing | live | stale */
    val wallCode: String,
    val activeCount: Int,
    val capacity: Int,
    val foreignCount: Int,
    val foreignUnknownCount: Int,
    val rows: List<QuantumLayerRowUi>,
    /** 0-based slot the lamp would use; -1 while blocked. */
    val suggestedSlot: Int,
    val suggestedArgb: Long,
    val suggestionUsesExistingSlot: Boolean,
    /** Android string key of why no layer can be suggested; empty when one can. */
    val suggestionBlockKey: String,
    /** Android string key of why "send all" would be refused; empty when ready or no plans. */
    val sendAllBlockKey: String,
    val hasPlans: Boolean,
    /** Holds this climb shares with what is (or is planned to be) on the wall. */
    val sharedHoldCount: Int,
    val sending: Boolean,
    val success: Boolean,
    /** Android string key of the last failure; empty when none. */
    val errorKey: String,
    /** False while a joined shared session owns the board. */
    val managementAllowed: Boolean,
    /** Bundle-relative image of the climb's model, normalized coordinates below are over it (square). */
    val boardImagePaths: List<String>,
    /** The climb's holds in role colours; empty while one of the layers is exactly this climb. */
    val climbHolds: List<QuantumWallHoldUi>,
    /** Android's projection overlay: every own layer's holds, a ring segment per layer colour. */
    val layerHolds: List<QuantumWallHoldUi>,
)

/**
 * The Quantum layer rack for one climb (Android's detail rack and lamp): four
 * numbered layers of this installation, other apps' routes read-only, and the
 * lamp that lights the climb in its own or the first free conflict-free layer.
 */
class QuantumLayersScreenModel internal constructor(
    private val controller: QuantumLayerController,
    private val boardRepository: BoardRepository,
    private val localManagementAllowed: () -> Boolean,
    private val io: CoroutineDispatcher,
    main: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val climb = MutableStateFlow<QuantumClimbSnapshot?>(null)

    /** The climb's model and its diode coordinates, for the wall picture. */
    private class Wall(val model: QuantumBoardModel, val placements: Map<Int, BoardPlacement>)

    private var wall: Wall? = null

    val currentState: QuantumLayersScreenState
        get() = map(controller.layers.state.value, controller.send.value, climb.value)

    fun watch(onState: (QuantumLayersScreenState) -> Unit): Subscription {
        val job = scope.launch {
            combine(controller.layers.state, controller.send, climb, controller.connectionState) { layers, send, current, _ ->
                map(layers, send, current)
            }.collect { onState(it) }
        }
        return Subscription { job.cancel() }
    }

    /** The climb the rack is about; anything but a Quantum climb with holds leaves it empty. */
    fun setClimb(uuid: String, angle: Int) {
        scope.launch {
            val loaded = try {
                withContext(io) {
                    val row = boardRepository.getClimbByUuid(uuid, angle) ?: return@withContext null
                    if (BoardBrand.fromWire(row.boardBrand) != BoardBrand.QUANTUM) return@withContext null
                    // Same holds the detail shows: Quantum climbs are a single frame.
                    val holds = BoardClimbParser.parseMultiFrames(row.frames).firstOrNull().orEmpty()
                    val wallOfClimb = QuantumBoardModel.fromLayoutId(row.layoutId)?.let { m ->
                        Wall(
                            m,
                            boardRepository.getPlacementsForLayout(m.productSizeId.toInt(), m.layoutId.toInt(), BoardBrand.QUANTUM.wireValue)
                                .associateBy { it.placementId.toInt() },
                        )
                    }
                    QuantumClimbSnapshot(row.uuid, row.name, angle, row.layoutId, row.difficultyAverage, holds) to wallOfClimb
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // Both on the main thread, wall first: the next state already maps over it.
            wall = loaded?.second
            climb.value = loaded?.first
        }
    }

    /** The detail lamp. */
    fun light() {
        climb.value?.let(controller::light)
    }

    /** Stage this climb in the recommended layer without sending (Android "Select recommendation"). */
    fun assignSuggested() {
        val current = climb.value ?: return
        val summary = summary(controller.layers.state.value, current)
        val slot = summary.suggestedSlot ?: return
        controller.assign(current, slot, summary.suggestedColor)
    }

    fun sendLayer(slot: Int) = controller.sendLayer(slot)
    fun sendAll() = controller.sendAll()
    fun removeLayer(slot: Int) = controller.remove(slot)
    fun cancelReplacement(slot: Int) = controller.cancelReplacement(slot)
    fun dismissResult() = controller.dismissResult()

    fun close() = scope.cancel()

    private fun summary(layers: BoardLayerState, current: QuantumClimbSnapshot?) = QuantumLayerUiPolicy.summarize(
        layers, current?.uuid, current?.holds.orEmpty().mapTo(mutableSetOf()) { it.placementId },
    )

    private fun map(layers: BoardLayerState, send: QuantumSendState, current: QuantumClimbSnapshot?): QuantumLayersScreenState {
        val summary = summary(layers, current)
        val connection = controller.connectionState.value
        val connected = connection.connection != ConnectionState.DISCONNECTED &&
            connection.connection != ConnectionState.CONNECTING &&
            connection.connectedBoard?.boardBrand == BoardBrand.QUANTUM
        val placements = current?.holds.orEmpty().mapTo(mutableSetOf()) { it.placementId }
        return QuantumLayersScreenState(
            hasClimb = current != null && current.holds.isNotEmpty(),
            climbName = current?.name ?: "",
            connected = connected,
            wallCode = when (layers.quantumSyncStatus) {
                QuantumControllerSyncStatus.UNSYNCED -> "unsynced"
                QuantumControllerSyncStatus.SYNCING -> "syncing"
                QuantumControllerSyncStatus.LIVE -> "live"
                QuantumControllerSyncStatus.STALE -> "stale"
            },
            activeCount = summary.activeCount,
            capacity = MAX_LAYER_IDENTITIES,
            foreignCount = summary.foreignCount,
            foreignUnknownCount = summary.foreignUnknownCount,
            rows = summary.slots.map { slot ->
                val layer = slot.layer
                QuantumLayerRowUi(
                    slot = slot.slot,
                    number = slot.slot + 1,
                    stateCode = stateCode(slot.visualState),
                    plannedArgb = slot.plannedColor?.let(::argb) ?: 0L,
                    confirmedArgb = slot.confirmedColor?.let(::argb) ?: 0L,
                    climbName = slot.climbName ?: "",
                    confirmedClimbName = if (slot.visualState == QuantumLayerVisualState.REPLACING) slot.confirmedClimbName ?: "" else "",
                    isCurrentClimb = slot.currentClimb,
                    canSend = layer != null && layer.holds.isNotEmpty() &&
                        slot.visualState in setOf(QuantumLayerVisualState.PLANNED, QuantumLayerVisualState.REPLACING, QuantumLayerVisualState.FAILED),
                    canRemove = layer != null,
                    removeIsLive = layer?.confirmedRouteUuid != null,
                    canCancelReplacement = slot.visualState == QuantumLayerVisualState.REPLACING,
                )
            },
            suggestedSlot = summary.suggestedSlot ?: -1,
            suggestedArgb = summary.suggestedColor?.let(::argb) ?: 0L,
            suggestionUsesExistingSlot = summary.suggestionUsesExistingSlot,
            suggestionBlockKey = summary.suggestionBlock?.let(QuantumLayerController::blockKey) ?: "",
            sendAllBlockKey = summary.sendAllBlock?.let(QuantumLayerController::blockKey) ?: "",
            hasPlans = layers.layers.isNotEmpty(),
            // Android measures against every other layer than the one this climb already uses.
            sharedHoldCount = QuantumLayerUiPolicy.knownSharedHoldCount(
                layers, placements, current?.let { c -> layers.layers.firstOrNull { it.climbUuid == c.uuid }?.slot },
            ),
            sending = send.sending,
            success = send.success,
            errorKey = send.error ?: "",
            managementAllowed = localManagementAllowed(),
            boardImagePaths = current?.let { wall }?.let {
                com.cruxcoach.app.render.boardImageCandidatePaths(BoardBrand.QUANTUM, it.model.productSizeId, it.model.layoutId)
            } ?: emptyList(),
            climbHolds = climbHolds(layers, current),
            layerHolds = layerHolds(layers, current),
        )
    }

    private fun point(placementId: Int): com.cruxcoach.app.render.BoardPoint? {
        val w = wall ?: return null
        val placement = w.placements[placementId] ?: return null
        return com.cruxcoach.app.render.QuantumRender.normalized(w.model, placement.x, placement.y)
            .takeIf(com.cruxcoach.app.render.QuantumRender::isVisible)
    }

    /** Android hides the climb's own rings when a layer projects exactly its holds. */
    private fun climbHolds(layers: BoardLayerState, current: QuantumClimbSnapshot?): List<QuantumWallHoldUi> {
        if (current == null) return emptyList()
        val placements = current.holds.map { it.placementId }.toSet()
        if (layers.layers.any { layer -> layer.holds.map { it.placementId }.toSet() == placements }) return emptyList()
        val colors = com.cruxcoach.app.render.LedHoldColors.standardFor(BoardBrand.QUANTUM)
        return current.holds.mapNotNull { hold ->
            point(hold.placementId)?.let { QuantumWallHoldUi(hold.placementId, it.x, it.y, listOf(colors.argbForRole(hold.roleId))) }
        }
    }

    private fun layerHolds(layers: BoardLayerState, current: QuantumClimbSnapshot?): List<QuantumWallHoldUi> {
        if (current == null) return emptyList()
        val colorsByPlacement = LinkedHashMap<Int, MutableList<Long>>()
        layers.layers.sortedBy { it.slot }.forEach { layer ->
            layer.holds.forEach { colorsByPlacement.getOrPut(it.placementId) { ArrayList() } += argb(layer.color) }
        }
        return colorsByPlacement.mapNotNull { (placementId, colors) ->
            point(placementId)?.let { QuantumWallHoldUi(placementId, it.x, it.y, colors.distinct()) }
        }
    }

    private companion object {
        fun argb(color: Int): Long = color.toLong() and 0xffffffffL

        fun stateCode(state: QuantumLayerVisualState): String = when (state) {
            QuantumLayerVisualState.FREE -> "free"
            QuantumLayerVisualState.PLANNED -> "planned"
            QuantumLayerVisualState.SENDING -> "sending"
            QuantumLayerVisualState.ON_BOARD -> "onBoard"
            QuantumLayerVisualState.REPLACING -> "replacing"
            QuantumLayerVisualState.FAILED -> "failed"
            QuantumLayerVisualState.UNKNOWN -> "unknown"
            QuantumLayerVisualState.FOREIGN -> "foreign"
        }
    }
}
