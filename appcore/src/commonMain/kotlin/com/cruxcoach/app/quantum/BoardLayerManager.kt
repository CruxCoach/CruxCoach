package com.cruxcoach.app.quantum

import com.cruxcoach.app.ble.BoardLayerBoardIdentity
import com.cruxcoach.app.ble.QuantumControllerSyncStatus
import com.cruxcoach.app.ble.quantumPlayersMatch
import com.cruxcoach.app.crypto.Sha256
import com.cruxcoach.app.logbook.randomUuidString
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.QuantumActivePlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Port of Android's ble/BoardLayerManager.kt: the four Quantum layer identities
// of this installation, their local plans and the controller roster.

/** Controller-neutral description of one climb projection layer. */
internal data class BoardClimbLayer(
    val slot: Int,
    val climbUuid: String,
    val routeUuid: String,
    val climbName: String,
    val angle: Int,
    val userUuid: String,
    /** Opaque ARGB. */
    val color: Int,
    val holds: List<BoardHold>,
    val status: BoardLayerStatus,
    val ownedByThisInstallation: Boolean = true,
    /** Route the controller reports for this identity; kept apart from [routeUuid]
     *  so a staged plan never pretends the old projection has gone. */
    val confirmedRouteUuid: String? = null,
    val confirmedColor: Int? = null,
    val confirmedClimbUuid: String? = null,
    val confirmedClimbName: String? = null,
    /** Null: the controller route could not be resolved locally. */
    val confirmedHolds: List<BoardHold>? = null,
    /** False for a layer rebuilt only from its wire UUID: its holds are unknown. */
    val controllerDetailsKnown: Boolean = true,
    /** Compare-and-set token: a suspended completion may only change the plan that started it. */
    val planToken: String = randomUuidString(),
) {
    fun planKey(): BoardLayerPlanKey = BoardLayerPlanKey(slot, planToken)
}

internal data class BoardLayerPlanKey(val slot: Int, val planToken: String)

internal enum class BoardLayerStatus { PREVIEW, SENDING, CONFIRMED, FAILED }

internal data class ExternalBoardLayer(
    val routeUuid: String,
    val userUuid: String,
    val color: Int,
    val remainingSeconds: Int,
    val climbUuid: String? = null,
    val climbName: String? = null,
    /** Null means unknown, not an empty climb. */
    val holds: List<BoardHold>? = null,
)

internal data class BoardLayerRouteDetails(
    val climbUuid: String,
    val climbName: String,
    val holds: List<BoardHold>,
)

/** Route readback is player-scoped: route and controller user together. */
internal data class BoardLayerControllerRouteKey(
    val routeUuid: String,
    val userUuid: String,
)

internal data class BoardLayerState(
    val brand: BoardBrand? = null,
    /** Null before the first connection: nothing is staged for anything yet. */
    val board: BoardLayerBoardIdentity? = null,
    val layers: List<BoardClimbLayer> = emptyList(),
    val externalLayers: List<ExternalBoardLayer> = emptyList(),
    val quantumSyncStatus: QuantumControllerSyncStatus = QuantumControllerSyncStatus.UNSYNCED,
) {
    /** Physical controller occupancy. PREVIEW layers are local-only. */
    val occupiedCount: Int
        get() = layers.count { it.confirmedRouteUuid != null } + externalLayers.size
    val assignedCount: Int get() = layers.size

    /** Colours reserved by staged plans and by routes still lit; the target slot is excluded. */
    fun reservedLayerColors(replacingSlot: Int? = null): Set<Int> = buildSet {
        layers.filterNot { it.slot == replacingSlot }.forEach { layer ->
            add(layer.color)
            layer.confirmedColor?.let { add(it) }
        }
        externalLayers.forEach { add(it.color) }
    }

    /** The hydrated rack still describes exactly these physical players (countdowns ignored). */
    fun matchesQuantumPlayers(players: List<QuantumActivePlayer>): Boolean {
        val represented = buildList {
            layers.forEach { layer ->
                val route = layer.confirmedRouteUuid ?: return@forEach
                add(QuantumActivePlayer(route, layer.userUuid, 0, layer.confirmedColor ?: layer.color))
            }
            externalLayers.forEach { add(QuantumActivePlayer(it.routeUuid, it.userUuid, 0, it.color)) }
        }
        return quantumPlayersMatch(players, represented)
    }
}

/**
 * Process-wide rack. Identities are random per installation and unrelated to
 * accounts or keys; they are kept because the controller retains projections
 * after a disconnect and a reconnect must find and remove only its own slots.
 */
internal class BoardLayerManager(keyValues: KeyValueStore) {
    private val installationId: String = keyValues.getString(KEY_INSTALLATION_ID)
        ?: randomUuidString().also { keyValues.putString(KEY_INSTALLATION_ID, it) }
    private val identities = List(com.cruxcoach.app.ble.MAX_LAYER_IDENTITIES) { slot ->
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

    /** Trust boundary for every scoped Quantum write: a valid UUID is not proof of ownership. */
    fun ownsIdentity(userUuid: String): Boolean = identities.any { it.equals(userUuid, ignoreCase = true) }

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
        _state.update { current -> assignPreview(current, layer, current.layers.firstOrNull { it.slot == layer.slot }) }
    }

    /** Compare-and-set assignment: null [expectedCurrent] means the slot was empty when the work began. */
    fun assignPreviewIfCurrent(layer: BoardClimbLayer, expectedCurrent: BoardLayerPlanKey?): Boolean {
        var matched = false
        _state.update { current ->
            matched = false
            val previous = current.layers.firstOrNull { it.slot == layer.slot }
            val currentMatches = if (expectedCurrent == null) previous == null else previous?.planKey() == expectedCurrent
            if (!currentMatches) current else {
                matched = true
                assignPreview(current, layer, previous)
            }
        }
        return matched
    }

    private fun assignPreview(current: BoardLayerState, layer: BoardClimbLayer, previous: BoardClimbLayer?): BoardLayerState =
        current.copy(
            brand = BoardBrand.QUANTUM,
            layers = (
                current.layers.filterNot { it.slot == layer.slot } +
                    layer.copy(
                        status = BoardLayerStatus.PREVIEW,
                        confirmedRouteUuid = previous?.confirmedRouteUuid,
                        confirmedColor = previous?.confirmedColor,
                        confirmedClimbUuid = previous?.confirmedClimbUuid,
                        confirmedClimbName = previous?.confirmedClimbName,
                        confirmedHolds = previous?.confirmedHolds,
                        controllerDetailsKnown = previous?.controllerDetailsKnown ?: true,
                    )
                ).sortedBy { it.slot },
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
            current.copy(layers = current.layers.filterNot { layer -> (layer.planKey() == expected).also { if (it) matched = true } })
        }
        return matched
    }

    /** Remove an unsent assignment; a lit identity goes only through a scoped removal first. */
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
        val isReplacement = !confirmedRoute.equals(layer.routeUuid, ignoreCase = true) || layer.confirmedColor != layer.color
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

    /** Replacing an identity already on the controller never consumes another place. */
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
     * Same board: keep everything. No board (a reconnect in progress): keep it.
     * A different board or model drops the plans and the foreign layers — they
     * describe holds on a controller this phone is no longer talking to.
     */
    fun bindBoard(identity: BoardLayerBoardIdentity?) {
        if (identity == null) return
        _state.update { current ->
            if (current.board == identity) current else BoardLayerState(brand = current.brand, board = identity)
        }
    }

    fun setQuantumSyncStatus(status: QuantumControllerSyncStatus) {
        _state.update { it.copy(quantumSyncStatus = status) }
    }

    fun isBoundTo(identity: BoardLayerBoardIdentity?): Boolean = identity != null && _state.value.board == identity

    /** Merge an authoritative snapshot without claiming foreign slots. */
    fun reconcile(players: List<QuantumActivePlayer>) {
        val byUser = players.associateBy { it.userId.lowercase() }
        val ownedIds = identities.mapTo(mutableSetOf()) { it.lowercase() }
        _state.update { current ->
            val owned = current.layers.mapNotNull { layer ->
                val player = byUser[layer.userUuid.lowercase()]
                // TURN_OFF_USER yields an intermediate snapshot before the activation:
                // keep the transaction placeholder until the sender confirms or fails it.
                if (player == null) {
                    return@mapNotNull if (layer.status == BoardLayerStatus.CONFIRMED) null else layer.copy(
                        confirmedRouteUuid = null,
                        confirmedColor = null,
                        confirmedClimbUuid = null,
                        confirmedClimbName = null,
                        confirmedHolds = null,
                        controllerDetailsKnown = true,
                    )
                }
                val reportedColor = opaque(player.color)
                val routeMatchesPlan = player.routeId.equals(layer.routeUuid, ignoreCase = true)
                val tupleMatchesPlan = routeMatchesPlan && reportedColor == layer.color
                layer.copy(
                    // Readback never replaces the planned colour or climb.
                    status = if (tupleMatchesPlan) BoardLayerStatus.CONFIRMED else layer.status,
                    confirmedRouteUuid = player.routeId,
                    confirmedColor = reportedColor,
                    confirmedClimbUuid = layer.climbUuid.takeIf { routeMatchesPlan },
                    confirmedClimbName = layer.climbName.takeIf { routeMatchesPlan } ?: player.routeId.take(8),
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
                        color = opaque(player.color),
                        holds = emptyList(),
                        status = BoardLayerStatus.CONFIRMED,
                        confirmedRouteUuid = player.routeId,
                        confirmedColor = opaque(player.color),
                        confirmedClimbName = player.routeId.take(8),
                        confirmedHolds = null,
                        controllerDetailsKnown = false,
                    )
                }
            val previousExternal = current.externalLayers.associateBy {
                BoardLayerControllerRouteKey(it.routeUuid, it.userUuid).normalized()
            }
            val external = players.filterNot { it.userId.lowercase() in ownedIds }.map { player ->
                val previous = previousExternal[BoardLayerControllerRouteKey(player.routeId, player.userId).normalized()]
                ExternalBoardLayer(
                    routeUuid = player.routeId,
                    userUuid = player.userId,
                    color = opaque(player.color),
                    remainingSeconds = player.remainingSeconds,
                    // A countdown refresh of the same player keeps catalogue knowledge.
                    climbUuid = previous?.climbUuid,
                    climbName = previous?.climbName,
                    holds = previous?.holds,
                )
            }
            current.copy(brand = BoardBrand.QUANTUM, layers = owned.sortedBy { it.slot }, externalLayers = external)
        }
    }

    /**
     * Adds local catalogue knowledge to a fresh roster. Matching needs route AND
     * controller user and applies only to players still present. Empty geometry
     * stays unknown: a known empty climb would let conflict checks fail open.
     */
    fun hydrateControllerRoutes(detailsByPlayer: Map<BoardLayerControllerRouteKey, BoardLayerRouteDetails>) {
        val normalized = detailsByPlayer.filterValues { it.holds.isNotEmpty() }.mapKeys { (key, _) -> key.normalized() }
        _state.update { current ->
            current.copy(
                layers = current.layers.map { layer ->
                    val route = layer.confirmedRouteUuid ?: return@map layer
                    val details = normalized[BoardLayerControllerRouteKey(route, layer.userUuid).normalized()] ?: return@map layer
                    val physicalIsPlan = route.equals(layer.routeUuid, ignoreCase = true)
                    layer.copy(
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
                    val details = normalized[BoardLayerControllerRouteKey(layer.routeUuid, layer.userUuid).normalized()]
                        ?: return@map layer
                    layer.copy(climbUuid = details.climbUuid, climbName = details.climbName, holds = details.holds)
                },
            )
        }
    }

    fun layerForClimb(climbUuid: String): BoardClimbLayer? = _state.value.layers.firstOrNull { it.climbUuid == climbUuid }

    private fun updateOwned(expected: BoardLayerPlanKey, transform: (BoardClimbLayer) -> BoardClimbLayer): Boolean {
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
        const val MAX_LAYER_IDENTITIES = com.cruxcoach.app.ble.MAX_LAYER_IDENTITIES

        /**
         * The four unique BLE colours of the vendor app's six swatches after its
         * colour normalization; the two extra swatches collapse onto magenta/cyan.
         */
        val LAYER_COLORS: List<Int> = listOf(
            0xFF00FF00.toInt(), // green
            0xFF00FFFF.toInt(), // blue/cyan
            0xFFFF00FF.toInt(), // red/magenta
            0xFFFFFF00.toInt(), // ochre/yellow
        )
        private const val KEY_INSTALLATION_ID = "board_layer_identity/installation_uuid_v1"

        /** SHA-256 name-based UUID with version 4 / RFC 4122 variant bits, as on Android. */
        internal fun deriveFipsSafeUuid(name: String): String {
            val bytes = Sha256.digest(name.encodeToByteArray()).copyOf(16)
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20)}"
        }

        /** Quantum broadcasts 24-bit RGB; the palette is opaque ARGB. */
        internal fun opaque(rgb: Int): Int = 0xff000000.toInt() or (rgb and 0x00ffffff)

        private fun BoardLayerControllerRouteKey.normalized() =
            BoardLayerControllerRouteKey(routeUuid = routeUuid.lowercase(), userUuid = userUuid.lowercase())
    }
}

/** Android `toBrowserQuantumLayers`: what is physically lit, and whether every lit route is known. */
internal fun BoardLayerState.toBrowserQuantumLayers(): com.cruxcoach.app.browse.BrowserQuantumLayerState {
    val lit = HashSet<Int>()
    var complete = true
    layers.filter { it.confirmedRouteUuid != null }.forEach { layer ->
        val holds = layer.confirmedHolds
        if (!layer.controllerDetailsKnown || holds == null) complete = false else holds.mapTo(lit) { it.placementId }
    }
    externalLayers.forEach { layer ->
        val holds = layer.holds
        if (holds == null) complete = false else holds.mapTo(lit) { it.placementId }
    }
    return com.cruxcoach.app.browse.BrowserQuantumLayerState(litPlacements = lit, layerCount = occupiedCount, complete = complete)
}

internal data class BoardLayerCapabilities(
    val maxLayers: Int,
    val independentRemoval: Boolean,
    val selectableColors: Boolean,
)

internal object BoardLayerConflictPolicy {
    data class Assessment(val sharedHoldCount: Int, val unknownLayerCount: Int) {
        val canProveConflictFree: Boolean get() = sharedHoldCount == 0 && unknownLayerCount == 0
    }

    fun assess(
        candidate: List<BoardHold>,
        activeLayers: List<BoardClimbLayer>,
        externalLayers: List<ExternalBoardLayer>,
        replacingSlot: Int?,
    ): Assessment {
        val own = activeLayers.filterNot { it.slot == replacingSlot }.filter { it.confirmedRouteUuid != null }
        val occupied = own.mapNotNull(BoardClimbLayer::confirmedHolds).flatten().mapTo(mutableSetOf(), BoardHold::placementId)
        externalLayers.mapNotNull(ExternalBoardLayer::holds).flatten().mapTo(occupied, BoardHold::placementId)
        val unknown = own.count { !it.controllerDetailsKnown || it.confirmedHolds == null } +
            externalLayers.count { it.holds == null }
        return Assessment(sharedHoldCount = candidate.count { it.placementId in occupied }, unknownLayerCount = unknown)
    }

    fun assessPlacements(
        candidate: Set<Int>,
        activeLayers: List<BoardClimbLayer>,
        externalLayers: List<ExternalBoardLayer>,
        replacingSlot: Int?,
    ): Assessment {
        val own = activeLayers.filterNot { it.slot == replacingSlot }.filter { it.confirmedRouteUuid != null }
        val occupied = own.mapNotNull(BoardClimbLayer::confirmedHolds).flatten().mapTo(mutableSetOf(), BoardHold::placementId)
        externalLayers.mapNotNull(ExternalBoardLayer::holds).flatten().mapTo(occupied, BoardHold::placementId)
        return Assessment(
            sharedHoldCount = candidate.count { it in occupied },
            unknownLayerCount = own.count { !it.controllerDetailsKnown || it.confirmedHolds == null } +
                externalLayers.count { it.holds == null },
        )
    }

    fun sharedHoldCount(candidate: List<BoardHold>, activeLayers: List<BoardClimbLayer>, replacingSlot: Int?): Int {
        val occupied = activeLayers.filterNot { it.slot == replacingSlot }
            .flatMapTo(mutableSetOf()) { layer -> layer.holds.map { it.placementId } }
        return candidate.count { it.placementId in occupied }
    }
}
