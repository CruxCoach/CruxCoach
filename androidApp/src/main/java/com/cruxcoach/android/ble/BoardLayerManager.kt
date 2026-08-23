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
)

enum class BoardLayerStatus { PREVIEW, SENDING, CONFIRMED, FAILED }

data class ExternalBoardLayer(
    val routeUuid: String,
    val userUuid: String,
    @ColorInt val color: Int,
    val remainingSeconds: Int,
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
) {
    /** Physical controller occupancy. PREVIEW layers are local-only. */
    val occupiedCount: Int
        get() = layers.count { it.confirmedRouteUuid != null } + externalLayers.size
    val assignedCount: Int get() = layers.size
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

    fun nextAvailableSlot(brand: BoardBrand, preferred: Int? = null): Int? {
        val max = brand.maxSimultaneousClimbs
        val used = _state.value.layers.filter { it.ownedByThisInstallation }.mapTo(mutableSetOf()) { it.slot }
        if (preferred != null && preferred in used) return preferred
        if (preferred != null && preferred in 0 until max) return preferred
        return (0 until max).firstOrNull { it !in used }
    }

    fun defaultColor(slot: Int): Int = LAYER_COLORS[slot.mod(LAYER_COLORS.size)]

    fun availableColors(): List<Int> {
        val used = _state.value.layers.mapTo(mutableSetOf()) { it.color } +
            _state.value.externalLayers.map { it.color }
        return LAYER_COLORS.filterNot { it in used }
    }

    /** Assign or replace a local layer without touching BLE/controller state. */
    fun assignPreview(layer: BoardClimbLayer) {
        _state.update { current ->
            val previous = current.layers.firstOrNull { it.slot == layer.slot }
            current.copy(
                brand = BoardBrand.QUANTUM,
                layers = (current.layers.filterNot { it.slot == layer.slot } +
                    layer.copy(
                        status = BoardLayerStatus.PREVIEW,
                        confirmedRouteUuid = previous?.confirmedRouteUuid,
                        confirmedColor = previous?.confirmedColor,
                    )).sortedBy { it.slot },
            )
        }
    }

    fun beginProjection(layer: BoardClimbLayer) {
        assignPreview(layer)
        beginProjection(layer.slot)
    }

    fun beginProjection(slot: Int) = updateOwned(slot) {
        it.copy(status = BoardLayerStatus.SENDING)
    }

    fun confirmProjection(slot: Int) = updateOwned(slot) {
        it.copy(
            status = BoardLayerStatus.CONFIRMED,
            confirmedRouteUuid = it.routeUuid,
            confirmedColor = it.color,
        )
    }

    fun failProjection(slot: Int) = updateOwned(slot) { it.copy(status = BoardLayerStatus.FAILED) }

    fun removeOwned(slot: Int) {
        _state.update { it.copy(layers = it.layers.filterNot { layer -> layer.slot == slot }) }
    }

    /** Remove an unsent assignment. A physically active identity is removed
     * only through BoardBleConnection.removeQuantumLayer first. */
    fun removePreview(slot: Int): Boolean {
        val layer = _state.value.layers.firstOrNull { it.slot == slot } ?: return false
        if (layer.confirmedRouteUuid != null) return false
        removeOwned(slot)
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
                        layer.copy(confirmedRouteUuid = null, confirmedColor = null)
                    }
                }
                if (!player.routeId.equals(layer.routeUuid, ignoreCase = true)) {
                    return@mapNotNull layer.copy(
                        confirmedRouteUuid = player.routeId,
                        confirmedColor = player.color.asOpaqueArgb(),
                    )
                }
                layer.copy(
                    color = player.color.asOpaqueArgb(),
                    status = BoardLayerStatus.CONFIRMED,
                    confirmedRouteUuid = player.routeId,
                    confirmedColor = player.color.asOpaqueArgb(),
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
                    )
                }
            val external = players.filterNot { it.userId.lowercase() in ownedIds }.map {
                ExternalBoardLayer(it.routeId, it.userId, it.color.asOpaqueArgb(), it.remainingSeconds)
            }
            current.copy(
                brand = BoardBrand.QUANTUM,
                layers = owned.sortedBy { it.slot },
                externalLayers = external,
            )
        }
    }

    fun layerForClimb(climbUuid: String): BoardClimbLayer? =
        _state.value.layers.firstOrNull { it.climbUuid == climbUuid }

    private fun updateOwned(slot: Int, transform: (BoardClimbLayer) -> BoardClimbLayer) {
        _state.update { current ->
            current.copy(layers = current.layers.map { if (it.slot == slot) transform(it) else it })
        }
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
}

data class BoardLayerCapabilities(
    val maxLayers: Int,
    val independentRemoval: Boolean,
    val selectableColors: Boolean,
)

object BoardLayerConflictPolicy {
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
