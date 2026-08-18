package com.cruxcoach.android.boardcell

import android.content.Context
import com.cruxcoach.android.ble.DiscoveredBoard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stable identity policy. Aurora serials are preferred; Android BLE addresses
 * are the best protocol-observable fallback. Name/model/RSSI are never keys.
 * If an address is randomized, callers must persist and supply a user binding.
 */
object PhysicalBoardIdentity {
    fun resolve(board: DiscoveredBoard, persistentFallback: String? = null): PhysicalBoardId {
        val brand = board.boardBrand.name.lowercase()
        return when {
            board.serial.isNotBlank() -> PhysicalBoardId("$brand:serial:${board.serial.lowercase()}")
            !persistentFallback.isNullOrBlank() -> PhysicalBoardId("crux:$persistentFallback")
            board.address.isNotBlank() -> PhysicalBoardId("$brand:ble:${board.address.uppercase()}")
            else -> error("Board has neither stable serial/address nor an explicit persistent binding")
        }
    }
}

/** App-private mapping populated by an explicit QR/manual board binding. */
class PhysicalBoardBindingStore(context: Context) {
    private val prefs = context.getSharedPreferences("physical_board_bindings_v1", Context.MODE_PRIVATE)

    fun bindingFor(observedAddress: String): String? =
        prefs.getString(observedAddress.uppercase(), null)?.takeIf(String::isNotBlank)

    fun bind(observedAddress: String, durableBindingId: String) {
        require(observedAddress.isNotBlank() && durableBindingId.isNotBlank())
        prefs.edit().putString(observedAddress.uppercase(), durableBindingId).apply()
    }
}

/** Process-local selection boundary shared by legacy BLE and BoardCell paths. */
object BoardCellScopeRegistry {
    private val lock = Any()
    private val known = mutableSetOf<PhysicalBoardId>()
    private val cells = mutableMapOf<PhysicalBoardId, BoardCellId>()
    private val _selected = MutableStateFlow<PhysicalBoardId?>(null)
    val selected = _selected.asStateFlow()

    fun select(board: DiscoveredBoard) = synchronized(lock) {
        PhysicalBoardIdentity.resolve(board).also { known += it; _selected.value = it }
    }
    fun select(boardId: PhysicalBoardId) = synchronized(lock) {
        known += boardId; _selected.value = boardId
    }
    fun replaceProvisionalSelection(boardId: PhysicalBoardId) = synchronized(lock) {
        _selected.value?.takeIf { it !in cells }?.let(known::remove)
        known += boardId; _selected.value = boardId
    }

    fun clearSelection() { _selected.value = null }
    fun observe(boardId: PhysicalBoardId) = synchronized(lock) { known += boardId }
    fun bindCell(boardId: PhysicalBoardId, cellId: BoardCellId) = synchronized(lock) {
        known += boardId; cells[boardId] = cellId
    }
    fun joinCell(boardId: PhysicalBoardId, cellId: BoardCellId) = synchronized(lock) {
        known += boardId; cells[boardId] = cellId; _selected.value = boardId
    }
    fun selectedCellId(): BoardCellId? = synchronized(lock) { _selected.value?.let(cells::get) }
    fun resetForTest() = synchronized(lock) { known.clear(); cells.clear(); _selected.value = null }

    /** Unscoped v1 advertisements are only safe when no board ambiguity exists. */
    fun acceptsLegacyUnscoped(): Boolean = synchronized(lock) { known.size <= 1 }
}
