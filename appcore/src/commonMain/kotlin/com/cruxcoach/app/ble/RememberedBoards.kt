package com.cruxcoach.app.ble

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One remembered controller per brand, as on Android, but keyed by the
 * CBPeripheral identifier: iOS has no MAC address. The identifier is only
 * meaningful on this device, so this record must never be backed up to or
 * restored on another one. RSSI and runtime capacity are not stored.
 */
@Serializable
data class RememberedBoard(
    val displayName: String,
    val serial: String,
    val apiLevel: Int,
    val identifier: String,
    val brand: String,
) {
    fun toDiscovered(): DiscoveredBoard? {
        val boardBrand = BoardBrand.fromWireOrNull(brand) ?: return null
        return DiscoveredBoard(displayName, serial, apiLevel, identifier, rssi = 0, boardBrand = boardBrand)
    }
}

class RememberedBoardStore(private val keyValues: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(brand: BoardBrand): RememberedBoard? {
        val raw = keyValues.getString(KEY_PREFIX + brand.wireValue) ?: return null
        return try {
            json.decodeFromString(RememberedBoard.serializer(), raw)
                .takeIf { it.brand == brand.wireValue && it.identifier.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun all(): List<RememberedBoard> = BoardBrand.entries.mapNotNull(::get)

    /** A CruxRelay is another climber's phone, not a wall: never remembered. */
    fun remember(board: DiscoveredBoard) {
        if (board.isCruxRelay || board.identifier.isBlank()) return
        val record = RememberedBoard(
            board.displayName, board.serial, board.apiLevel, board.identifier, board.boardBrand.wireValue,
        )
        keyValues.putString(KEY_PREFIX + board.boardBrand.wireValue, json.encodeToString(RememberedBoard.serializer(), record))
    }

    fun forget(brand: BoardBrand) = keyValues.putString(KEY_PREFIX + brand.wireValue, null)

    private companion object {
        const val KEY_PREFIX = "ble.remembered_board."
    }
}
