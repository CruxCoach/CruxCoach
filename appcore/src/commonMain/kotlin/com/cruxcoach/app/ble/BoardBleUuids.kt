package com.cruxcoach.app.ble

/**
 * GATT UUIDs, copied verbatim from Android's BoardBleUuids. Kept as upper-case
 * strings because commonMain has no UUID type; compare with [sameUuid], since
 * CoreBluetooth abbreviates Bluetooth-base UUIDs ("FFE0").
 */
object BoardBleUuids {
    const val ADVERTISING_SERVICE = "4488B571-7806-4DF6-BCFF-A2897E4953FF"
    const val DATA_TRANSFER_SERVICE = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val DATA_TRANSFER_CHAR = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    const val QUANTUM_SERVICE = "0000FFE0-0000-1000-8000-00805F9B34FB"
    const val QUANTUM_SERVICE_OLD = "0000FFF0-0000-1000-8000-00805F9B34FB"
    const val QUANTUM_NOTIFY_CHAR = "0000FFF1-0000-1000-8000-00805F9B34FB"
    const val QUANTUM_WRITE_CHAR = "0000FFF2-0000-1000-8000-00805F9B34FB"
    const val QUANTUM_STATE_CHAR = "0000FFF4-0000-1000-8000-00805F9B34FB"

    /** 41-byte eWalls 2.0.14 controller record: type at byte 34, then
     * big-endian columns/rows at bytes 35..38. */
    const val QUANTUM_METADATA_CHAR = "0000FFF5-0000-1000-8000-00805F9B34FB"
    const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805F9B34FB"

    /** Red Bear Lab UART service of pre-2017 MoonBoard LED kits. NOT a supported
     * transport: only recognised so the user gets an honest failure code. */
    const val REDBEAR_UART_SERVICE = "713D0000-503E-4C75-BA94-3148F18D941E"
    const val CRUXCOACH_CLIMB_SHARING = "C1140B00-CC01-4000-8000-DEADC0AC0001"

    private const val BASE_SUFFIX = "-0000-1000-8000-00805F9B34FB"

    /** Canonical 128-bit upper-case form of a 16/32/128-bit UUID string. */
    fun canonical(uuid: String): String {
        val u = uuid.trim().uppercase()
        return when (u.length) {
            4 -> "0000$u$BASE_SUFFIX"
            8 -> "$u$BASE_SUFFIX"
            else -> u
        }
    }

    fun sameUuid(a: String, b: String): Boolean = canonical(a) == canonical(b)
}
