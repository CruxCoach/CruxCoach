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
)

enum class BoardLayerStatus { PREVIEW, SENDING, CONFIRMED, FAILED }

data class ExternalBoardLayer(
    val routeUuid: String,
    val userUuid: String,
    @ColorInt val color: Int,
    val remainingSeconds: Int,
)

data class BoardLayerState(
    val brand: BoardBrand? = null,
    val layers: List<BoardClimbLayer> = emptyList(),
    val externalLayers: List<ExternalBoardLayer> = emptyList(),
) {
    val occupiedCount: Int get() = layers.size + externalLayers.size
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
        if (_state.value.occupiedCount >= max) return null
        if (preferred != null && preferred in 0 until max) return preferred
        return (0 until max).firstOrNull { it !in used }
    }

    fun defaultColor(slot: Int): Int = LAYER_COLORS[slot.mod(LAYER_COLORS.size)]

    fun availableColors(): List<Int> {
        val used = _state.value.layers.mapTo(mutableSetOf()) { it.color } +
            _state.value.externalLayers.map { it.color }
        return LAYER_COLORS.filterNot { it in used }
    }

    fun beginProjection(layer: BoardClimbLayer) {
        _state.update { current ->
            current.copy(
                brand = BoardBrand.QUANTUM,
                layers = (current.layers.filterNot { it.slot == layer.slot } +
                    layer.copy(status = BoardLayerStatus.SENDING)).sortedBy { it.slot },
            )
        }
    }

    fun confirmProjection(slot: Int) = updateOwned(slot) { it.copy(status = BoardLayerStatus.CONFIRMED) }

    fun failProjection(slot: Int) = updateOwned(slot) { it.copy(status = BoardLayerStatus.FAILED) }

    fun removeOwned(slot: Int) {
        _state.update { it.copy(layers = it.layers.filterNot { layer -> layer.slot == slot }) }
    }

    fun clearLocalState() {
        _state.value = BoardLayerState(brand = _state.value.brand)
    }

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
                    return@mapNotNull layer.takeIf { it.status == BoardLayerStatus.SENDING }
                }
                if (!player.routeId.equals(layer.routeUuid, ignoreCase = true)) {
                    return@mapNotNull layer.takeIf { it.status == BoardLayerStatus.SENDING }
                }
                layer.copy(color = player.color.asOpaqueArgb(), status = BoardLayerStatus.CONFIRMED)
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
        val LAYER_COLORS = listOf(
            0xFF00BCD4.toInt(), // cyan
            0xFFFF8C00.toInt(), // orange
            0xFFB56CFF.toInt(), // violet
            0xFF4CD964.toInt(), // green
            0xFFFF4F81.toInt(), // pink
            0xFFFFD60A.toInt(), // yellow
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
