package com.cruxcoach.android.ble

/**
 * Stable identity for one physical board.
 *
 * Lifted out of the BoardCell layer, which this release does not carry: which
 * board is on the link is not a mesh question. A Quantum layer is a diode plan
 * for one controller, so it has to be able to name the controller it was
 * staged for.
 */
@JvmInline
value class PhysicalBoardId(val value: String) {
    init { require(value.isNotBlank()) }
}

/**
 * Aurora serials are preferred; Android BLE addresses are the best
 * protocol-observable fallback. Name, model and RSSI are never keys — they are
 * not stable, and two boards in one gym can share all three.
 *
 * Where an address is randomized, callers must persist and supply their own
 * binding rather than letting the address stand in for identity.
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
