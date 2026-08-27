package com.cruxcoach.android.ble

import android.content.Context
import androidx.annotation.ColorInt
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.QuantumActivePlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Controller-neutral description of one climb projection layer. */
data class BoardClimbLayer(
    val slot: Int,
    val climbUuid: String,
    val routeUuid: String,
    val climbName: String,
    val angle: Int,
    val userUuid: String,
    @ColorInt val color: Int,
    val holds: List<BoardHold>,
    val status: BoardLayerStatus,
    val ownedByThisInstallation: Boolean = true,
    /** Route currently reported for this installation identity by the
     * controller.  It deliberately stays separate from [routeUuid]: assigning
     * a new preview must not pretend that the old physical projection has
     * already disappeared. */
    val confirmedRouteUuid: String? = null,
    @ColorInt val confirmedColor: Int? = null,
    /** Local catalogue identity for [confirmedRouteUuid]. These fields describe
     * controller truth and must never overwrite the staged climb above: during
     * a replacement the old physical route and the new plan coexist. */
    val confirmedClimbUuid: String? = null,
    val confirmedClimbName: String? = null,
    /** Null means the controller route could not be resolved locally. */
    val confirmedHolds: List<BoardHold>? = null,
    /** False for a controller layer reconstructed only from its wire UUID.
     * Until the local catalogue resolves that UUID, its occupied holds are
     * unknown and another projection must not assume it is conflict-free. */
    val controllerDetailsKnown: Boolean = true,
    /** Process-local compare-and-set token. BLE work suspends while another
     * detail/playlist surface can replace the same numbered slot; completions
     * may mutate only the exact plan that started the operation. */
    val planToken: String = UUID.randomUUID().toString(),
)

data class BoardLayerPlanKey(val slot: Int, val planToken: String)

fun BoardClimbLayer.planKey(): BoardLayerPlanKey = BoardLayerPlanKey(slot, planToken)

enum class BoardLayerStatus { PREVIEW, SENDING, CONFIRMED, FAILED }

data class ExternalBoardLayer(
    val routeUuid: String,
    val userUuid: String,
    @ColorInt val color: Int,
    val remainingSeconds: Int,
    val climbUuid: String? = null,
    val climbName: String? = null,
    /** Null means unknown, not an empty climb. */
    val holds: List<BoardHold>? = null,
)

data class BoardLayerRouteDetails(
    val climbUuid: String,
    val climbName: String,
    val holds: List<BoardHold>,
)

/** Route readback is player-scoped. An installation-owned player may use the
 * narrow direct-UUID fallback, while a foreign player reporting the same UUID
 * must remain unknown unless it has the official vendor bridge. */
data class BoardLayerControllerRouteKey(
    val routeUuid: String,
    val userUuid: String,
)

/**
 * The physical board a rack belongs to.
 *
 * A layer is a diode plan for one controller. Carrying the model as well as the
 * identity matters because the addresses are only meaningful within a model:
 * the same preview on the next size up is not a smaller version of the climb,
 * it is a different set of holds.
 */
data class BoardLayerBoardIdentity(
    val physicalBoardId: String,
    val productSizeId: Long,
)

data class BoardLayerState(
    val brand: BoardBrand? = null,
    /** Null before the first connection: nothing is staged for anything yet. */
    val board: BoardLayerBoardIdentity? = null,
    val layers: List<BoardClimbLayer> = emptyList(),
    val externalLayers: List<ExternalBoardLayer> = emptyList(),
    /** Freshness of the controller roster; layers are retained as last-known
     * state across a disconnect but must no longer be labelled live. */
    val quantumSyncStatus: QuantumControllerSyncStatus = QuantumControllerSyncStatus.UNSYNCED,
) {
    /** Physical controller occupancy. PREVIEW layers are local-only. */
    val occupiedCount: Int
        get() = layers.count { it.confirmedRouteUuid != null } + externalLayers.size
    val assignedCount: Int get() = layers.size
}

/** Prove that hydrated rack state still describes the exact physical players
 * used for the next coexistence preflight. Countdown values are intentionally
 * ignored by [quantumPlayersMatch]. */
fun BoardLayerState.matchesQuantumPlayers(players: List<QuantumActivePlayer>): Boolean {
    val represented = buildList {
        layers.forEach { layer ->
            val route = layer.confirmedRouteUuid ?: return@forEach
            add(
                QuantumActivePlayer(
                    routeId = route,
                    userId = layer.userUuid,
                    remainingSeconds = 0,
                    color = layer.confirmedColor ?: layer.color,
                ),
            )
        }
        externalLayers.forEach { layer ->
            add(
                QuantumActivePlayer(
                    routeId = layer.routeUuid,
                    userId = layer.userUuid,
                    remainingSeconds = 0,
                    color = layer.color,
                ),
            )
        }
    }
    return quantumPlayersMatch(players, represented)
}

/**
 * Process-wide state for the physical board's independent projection layers.
 *
 * Layer identities are random per installation and deliberately unrelated to
 * accounts, Nostr keys or vendor profiles.  They are persisted because the
 * Quantum controller retains projections after disconnect; a reconnect must
 * still be able to identify and remove only this installation's own slots.
 */
@Singleton
class BoardLayerManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val installationId: String = preferences.getString(KEY_INSTALLATION_ID, null)
        ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_INSTALLATION_ID, it).apply()
        }
    private val identities = List(MAX_LAYER_IDENTITIES) { slot ->
        deriveFipsSafeUuid("cruxcoach-board-layer-v1|$installationId|$slot")
    }

    private val _state = MutableStateFlow(BoardLayerState())
    val state: StateFlow<BoardLayerState> = _state.asStateFlow()

    fun capabilities(brand: BoardBrand?): BoardLayerCapabilities = BoardLayerCapabilities(
        maxLayers = brand?.maxSimultaneousClimbs ?: 1,
        independentRemoval = brand?.supportsIndependentClimbLayers == true,
        selectableColors = brand?.supportsIndependentClimbLayers == true,
    )

    fun identityForSlot(slot: Int): String = identities[slot.coerceIn(0, identities.lastIndex)]

    /** Trust-boundary predicate used by the BLE transport before any scoped
     * Quantum mutation. A syntactically valid UUID is not proof of ownership. */
    fun ownsIdentity(userUuid: String): Boolean =
        identities.any { it.equals(userUuid, ignoreCase = true) }

    fun nextAvailableSlot(brand: BoardBrand, preferred: Int? = null): Int? {
        val max = brand.maxSimultaneousClimbs
        val used = _state.value.layers.filter { it.ownedByThisInstallation }.mapTo(mutableSetOf()) { it.slot }
        if (preferred != null && preferred in used) return preferred
        if (preferred != null && preferred in 0 until max) return preferred
        return (0 until max).firstOrNull { it !in used }
    }

    fun defaultColor(slot: Int): Int = LAYER_COLORS[slot.mod(LAYER_COLORS.size)]

    fun availableColors(replacingSlot: Int? = null): List<Int> =
        LAYER_COLORS.filterNot { it in _state.value.reservedLayerColors(replacingSlot) }

    /** Assign or replace a local layer without touching BLE/controller state. */
    fun assignPreview(layer: BoardClimbLayer) {
        _state.update { current ->
            assignPreview(current, layer, current.layers.firstOrNull { it.slot == layer.slot })
        }
    }

    /**
     * Compare-and-set assignment for work that suspended before it produced a
     * preview. Null means the slot was empty when the work began. A stale detail
     * or playlist task must never overwrite a newer plan in the same slot.
     */
    fun assignPreviewIfCurrent(
        layer: BoardClimbLayer,
        expectedCurrent: BoardLayerPlanKey?,
    ): Boolean {
        var matched = false
        _state.update { current ->
            matched = false
            val previous = current.layers.firstOrNull { it.slot == layer.slot }
            val currentMatches = if (expectedCurrent == null) {
                previous == null
            } else {
                previous?.planKey() == expectedCurrent
            }
            if (!currentMatches) current else {
                matched = true
                assignPreview(current, layer, previous)
            }
        }
        return matched
    }

    private fun assignPreview(
        current: BoardLayerState,
        layer: BoardClimbLayer,
        previous: BoardClimbLayer?,
    ): BoardLayerState = current.copy(
        brand = BoardBrand.QUANTUM,
        layers = (current.layers.filterNot { it.slot == layer.slot } +
            layer.copy(
                status = BoardLayerStatus.PREVIEW,
                confirmedRouteUuid = previous?.confirmedRouteUuid,
                confirmedColor = previous?.confirmedColor,
                confirmedClimbUuid = previous?.confirmedClimbUuid,
                confirmedClimbName = previous?.confirmedClimbName,
                confirmedHolds = previous?.confirmedHolds,
                controllerDetailsKnown = previous?.controllerDetailsKnown ?: true,
            )).sortedBy { it.slot },
    )

    fun beginProjection(layer: BoardClimbLayer): Boolean {
        assignPreview(layer)
        return beginProjection(layer.planKey())
    }

    fun beginProjection(expected: BoardLayerPlanKey): Boolean = updateOwned(expected) {
        it.copy(status = BoardLayerStatus.SENDING)
    }

    fun confirmProjection(expected: BoardLayerPlanKey): Boolean = updateOwned(expected) {
        it.copy(
            status = BoardLayerStatus.CONFIRMED,
            confirmedRouteUuid = it.routeUuid,
            confirmedColor = it.color,
            confirmedClimbUuid = it.climbUuid,
            confirmedClimbName = it.climbName,
            confirmedHolds = it.holds,
            controllerDetailsKnown = true,
        )
    }

    fun failProjection(expected: BoardLayerPlanKey): Boolean =
        updateOwned(expected) { it.copy(status = BoardLayerStatus.FAILED) }

    fun removeOwned(expected: BoardLayerPlanKey): Boolean {
        var matched = false
        _state.update { current ->
            matched = false
            current.copy(layers = current.layers.filterNot { layer ->
                (layer.planKey() == expected).also { if (it) matched = true }
            })
        }
        return matched
    }

    /** Remove an unsent assignment. A physically active identity is removed
     * only through BoardBleConnection.removeQuantumLayer first. */
    fun removePreview(slot: Int): Boolean {
        val layer = _state.value.layers.firstOrNull { it.slot == slot } ?: return false
        if (layer.confirmedRouteUuid != null) return false
        removeOwned(layer.planKey())
        return true
    }

    /** Discard a staged replacement without touching the controller. */
    fun cancelReplacement(slot: Int): Boolean {
        val layer = _state.value.layers.firstOrNull { it.slot == slot } ?: return false
        val confirmedRoute = layer.confirmedRouteUuid ?: return false
        val isReplacement = !confirmedRoute.equals(layer.routeUuid, ignoreCase = true) ||
            layer.confirmedColor != layer.color
        if (!isReplacement) return false
        updateOwned(layer.planKey()) {
            it.copy(
                climbUuid = it.confirmedClimbUuid ?: confirmedRoute,
                routeUuid = confirmedRoute,
                climbName = it.confirmedClimbName ?: confirmedRoute.take(8),
                color = it.confirmedColor ?: it.color,
                holds = it.confirmedHolds.orEmpty(),
                status = BoardLayerStatus.CONFIRMED,
                controllerDetailsKnown = it.confirmedHolds?.isNotEmpty() == true,
            )
        }
        return true
    }

    /** Whether activating this identity can fit without displacing anyone.
     * Replacing an identity already present on the controller never consumes
     * an additional place. */
    fun hasControllerCapacityFor(slot: Int, brand: BoardBrand = BoardBrand.QUANTUM): Boolean {
        val layer = _state.value.layers.firstOrNull { it.slot == slot }
        return layer?.confirmedRouteUuid != null || _state.value.occupiedCount < brand.maxSimultaneousClimbs
    }

    fun canProjectAll(brand: BoardBrand = BoardBrand.QUANTUM): Boolean {
        val state = _state.value
        val newIdentities = state.layers.count { it.confirmedRouteUuid == null }
        return state.occupiedCount + newIdentities <= brand.maxSimultaneousClimbs
    }

    fun clearLocalState() {
        _state.value = BoardLayerState(brand = _state.value.brand, board = _state.value.board)
    }

    /**
     * Attach the rack to the board it is for.
     *
     * The rack is process-wide because the controller retains projections
     * across a disconnect, and a reconnect has to be able to recognise and
     * remove this installation's own slots. That is only true of the *same*
     * board. Carry the previews to a different controller and they are a plan
     * for holds that are not there — and on the same model they would happily
     * send, which is worse than showing nothing.
     *
     * So: same board, keep everything. No board at all — a disconnect, or a
     * connection that has not resolved its size yet — keep it too, because
     * that is a reconnect in progress and not a board change. A different
     * board drops the local previews and the reconciled foreign layers, which
     * describe a controller this device is no longer talking to.
     */
    fun bindBoard(identity: BoardLayerBoardIdentity?) {
        if (identity == null) return
        _state.update { current ->
            if (current.board == identity) current
            else BoardLayerState(brand = current.brand, board = identity)
        }
    }

    fun setQuantumSyncStatus(status: QuantumControllerSyncStatus) {
        _state.update { it.copy(quantumSyncStatus = status) }
    }

    /** Whether the rack's contents were staged for [identity]. */
    fun isBoundTo(identity: BoardLayerBoardIdentity?): Boolean =
        identity != null && _state.value.board == identity

    /** Merge an authoritative Quantum snapshot without claiming foreign slots. */
    fun reconcile(players: List<QuantumActivePlayer>) {
        val byUser = players.associateBy { it.userId.lowercase() }
        val ownedIds = identities.mapTo(mutableSetOf()) { it.lowercase() }
        _state.update { current ->
            val owned = current.layers.mapNotNull { layer ->
                val player = byUser[layer.userUuid.lowercase()]
                // TURN_OFF_USER produces an intermediate snapshot before the
                // activation. Preserve the transaction placeholder until the
                // sender either confirms or fails it.
                if (player == null) {
                    return@mapNotNull if (layer.status == BoardLayerStatus.CONFIRMED) {
                        null
                    } else {
                        layer.copy(
                            confirmedRouteUuid = null,
                            confirmedColor = null,
                            confirmedClimbUuid = null,
                            confirmedClimbName = null,
                            confirmedHolds = null,
                            controllerDetailsKnown = true,
                        )
                    }
                }
                val reportedColor = player.color.asOpaqueArgb()
                val routeMatchesPlan = player.routeId.equals(layer.routeUuid, ignoreCase = true)
                val tupleMatchesPlan = routeMatchesPlan && reportedColor == layer.color
                layer.copy(
                    // Never replace the planned colour or climb with readback.
                    // The controller may still be showing the previous route.
                    status = if (tupleMatchesPlan) BoardLayerStatus.CONFIRMED else layer.status,
                    confirmedRouteUuid = player.routeId,
                    confirmedColor = reportedColor,
                    confirmedClimbUuid = layer.climbUuid.takeIf { routeMatchesPlan },
                    confirmedClimbName = layer.climbName.takeIf { routeMatchesPlan }
                        ?: player.routeId.take(8),
                    confirmedHolds = layer.holds.takeIf { routeMatchesPlan },
                    controllerDetailsKnown = routeMatchesPlan,
                )
            }.toMutableList()
            val representedUsers = owned.mapTo(mutableSetOf()) { it.userUuid.lowercase() }
            players.filter { it.userId.lowercase() in ownedIds && it.userId.lowercase() !in representedUsers }
                .forEach { player ->
                    val slot = identities.indexOfFirst { it.equals(player.userId, ignoreCase = true) }
                    if (slot >= 0) owned += BoardClimbLayer(
                        slot = slot,
                        climbUuid = player.routeId,
                        routeUuid = player.routeId,
                        climbName = player.routeId.take(8),
                        angle = 0,
                        userUuid = player.userId,
                        color = player.color.asOpaqueArgb(),
                        holds = emptyList(),
                        status = BoardLayerStatus.CONFIRMED,
                        confirmedRouteUuid = player.routeId,
                        confirmedColor = player.color.asOpaqueArgb(),
                        confirmedClimbName = player.routeId.take(8),
                        confirmedHolds = null,
                        controllerDetailsKnown = false,
                    )
                }
            val previousExternal = current.externalLayers.associateBy {
                BoardLayerControllerRouteKey(it.routeUuid, it.userUuid).normalized()
            }
            val external = players.filterNot { it.userId.lowercase() in ownedIds }.map { player ->
                val previous = previousExternal[
                    BoardLayerControllerRouteKey(player.routeId, player.userId).normalized()
                ]
                ExternalBoardLayer(
                    routeUuid = player.routeId,
                    userUuid = player.userId,
                    color = player.color.asOpaqueArgb(),
                    remainingSeconds = player.remainingSeconds,
                    // A countdown refresh for the same exact controller player
                    // must not erase catalogue knowledge while the next disk
                    // lookup is in flight (or when two UI observers reconcile
                    // the same snapshot concurrently).
                    climbUuid = previous?.climbUuid,
                    climbName = previous?.climbName,
                    holds = previous?.holds,
                )
            }
            current.copy(
                brand = BoardBrand.QUANTUM,
                layers = owned.sortedBy { it.slot },
                externalLayers = external,
            )
        }
    }

    /** Add local catalogue knowledge to a fresh controller snapshot.
     * Matching requires both route and controller user, and applies only to
     * layers still present, so a slow DB lookup can neither resurrect a player
     * nor transfer an owned player's direct-UUID proof to a foreign duplicate. */
    fun hydrateControllerRoutes(
        detailsByPlayer: Map<BoardLayerControllerRouteKey, BoardLayerRouteDetails>,
    ) {
        // A resolved UUID is not enough to prove geometry. Blank or malformed
        // frame strings parse to no holds; treating that as a known empty climb
        // would make conflict checks fail open. Keep such routes unknown.
        val normalized = detailsByPlayer
            .filterValues { it.holds.isNotEmpty() }
            .mapKeys { (key, _) -> key.normalized() }
        _state.update { current ->
            current.copy(
                layers = current.layers.map { layer ->
                    val route = layer.confirmedRouteUuid ?: return@map layer
                    val details = normalized[
                        BoardLayerControllerRouteKey(route, layer.userUuid).normalized()
                    ] ?: return@map layer
                    val physicalIsPlan = route.equals(layer.routeUuid, ignoreCase = true)
                    layer.copy(
                        // A reconstructed layer has no separate pending plan,
                        // so catalogue hydration may name both sides. During a
                        // replacement only the confirmed side is enriched.
                        climbUuid = if (physicalIsPlan) details.climbUuid else layer.climbUuid,
                        climbName = if (physicalIsPlan) details.climbName else layer.climbName,
                        holds = if (physicalIsPlan) details.holds else layer.holds,
                        confirmedClimbUuid = details.climbUuid,
                        confirmedClimbName = details.climbName,
                        confirmedHolds = details.holds,
                        controllerDetailsKnown = true,
                    )
                },
                externalLayers = current.externalLayers.map { layer ->
                    val details = normalized[
                        BoardLayerControllerRouteKey(layer.routeUuid, layer.userUuid).normalized()
                    ] ?: return@map layer
                    layer.copy(
                        climbUuid = details.climbUuid,
                        climbName = details.climbName,
                        holds = details.holds,
                    )
                },
            )
        }
    }

    fun layerForClimb(climbUuid: String): BoardClimbLayer? =
        _state.value.layers.firstOrNull { it.climbUuid == climbUuid }

    private fun updateOwned(
        expected: BoardLayerPlanKey,
        transform: (BoardClimbLayer) -> BoardClimbLayer,
    ): Boolean {
        var matched = false
        _state.update { current ->
            matched = false
            current.copy(layers = current.layers.map { layer ->
                if (layer.planKey() == expected) {
                    matched = true
                    transform(layer)
                } else {
                    layer
                }
            })
        }
        return matched
    }

    companion object {
        const val MAX_LAYER_IDENTITIES = 4
        /** The four unique BLE colours produced by eWalls 2.0.14's six
         * swatches after COLOR_TO_BLE normalization. The two extra UI
         * swatches collapse to magenta/cyan and therefore cannot identify an
         * additional controller player. */
        val LAYER_COLORS = listOf(
            0xFF00FF00.toInt(), // eWalls green
            0xFF00FFFF.toInt(), // eWalls blue/cyan
            0xFFFF00FF.toInt(), // eWalls red/magenta
            0xFFFFFF00.toInt(), // eWalls ochre/yellow
        )
        private const val PREFS = "board_layer_identity"
        private const val KEY_INSTALLATION_ID = "installation_uuid_v1"

        /** SHA-256 derivation; UUID.nameUUIDFromBytes is UUIDv3/MD5 and is
         * intentionally forbidden in the FIPS build. */
        internal fun deriveFipsSafeUuid(name: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(name.encodeToByteArray()).copyOf(16)
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val buffer = ByteBuffer.wrap(bytes)
            return UUID(buffer.long, buffer.long).toString()
        }
    }

    /** Quantum broadcasts 24-bit RGB; Compose and the layer palette use opaque ARGB. */
    private fun Int.asOpaqueArgb(): Int = 0xff000000.toInt() or (this and 0x00ffffff)

    private fun BoardLayerControllerRouteKey.normalized() = BoardLayerControllerRouteKey(
        routeUuid = routeUuid.lowercase(),
        userUuid = userUuid.lowercase(),
    )
}

data class BoardLayerCapabilities(
    val maxLayers: Int,
    val independentRemoval: Boolean,
    val selectableColors: Boolean,
)

/** Colours reserved by staged plans and by routes still physically lit.
 * The target slot is excluded because its TURN_OFF_USER precedes activation. */
fun BoardLayerState.reservedLayerColors(replacingSlot: Int? = null): Set<Int> = buildSet {
    layers.filterNot { it.slot == replacingSlot }.forEach { layer ->
        add(layer.color)
        layer.confirmedColor?.let { add(it) }
    }
    externalLayers.forEach { add(it.color) }
}

object BoardLayerConflictPolicy {
    data class Assessment(val sharedHoldCount: Int, val unknownLayerCount: Int) {
        val canProveConflictFree: Boolean get() = sharedHoldCount == 0 && unknownLayerCount == 0
    }

    fun assess(
        candidate: List<BoardHold>,
        activeLayers: List<BoardClimbLayer>,
        externalLayers: List<ExternalBoardLayer>,
        replacingSlot: Int?,
    ): Assessment {
        val own = activeLayers.filterNot { it.slot == replacingSlot }
            .filter { it.confirmedRouteUuid != null }
        val occupied = own.mapNotNull(BoardClimbLayer::confirmedHolds)
            .flatten().mapTo(mutableSetOf(), BoardHold::placementId)
        externalLayers.mapNotNull(ExternalBoardLayer::holds)
            .flatten().mapTo(occupied, BoardHold::placementId)
        val unknown = own.count { !it.controllerDetailsKnown || it.confirmedHolds == null } +
            externalLayers.count { it.holds == null }
        return Assessment(
            sharedHoldCount = candidate.count { it.placementId in occupied },
            unknownLayerCount = unknown,
        )
    }

    fun assessPlacements(
        candidate: Set<Int>,
        activeLayers: List<BoardClimbLayer>,
        externalLayers: List<ExternalBoardLayer>,
        replacingSlot: Int?,
    ): Assessment {
        val own = activeLayers.filterNot { it.slot == replacingSlot }
            .filter { it.confirmedRouteUuid != null }
        val occupied = own.mapNotNull(BoardClimbLayer::confirmedHolds)
            .flatten().mapTo(mutableSetOf(), BoardHold::placementId)
        externalLayers.mapNotNull(ExternalBoardLayer::holds)
            .flatten().mapTo(occupied, BoardHold::placementId)
        return Assessment(
            sharedHoldCount = candidate.count { it in occupied },
            unknownLayerCount = own.count {
                !it.controllerDetailsKnown || it.confirmedHolds == null
            } + externalLayers.count { it.holds == null },
        )
    }

    fun sharedHoldCount(
        candidate: List<BoardHold>,
        activeLayers: List<BoardClimbLayer>,
        replacingSlot: Int?,
    ): Int {
        val occupied = activeLayers.filterNot { it.slot == replacingSlot }
            .flatMapTo(mutableSetOf()) { layer -> layer.holds.map { it.placementId } }
        return candidate.count { it.placementId in occupied }
    }
}
