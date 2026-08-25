package com.cruxcoach.android.ble

import java.util.UUID

object BoardBleUuids {
    val ADVERTISING_SERVICE: UUID = UUID.fromString("4488B571-7806-4DF6-BCFF-A2897E4953FF")
    val DATA_TRANSFER_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val DATA_TRANSFER_CHAR: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    val QUANTUM_SERVICE: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
    val QUANTUM_SERVICE_OLD: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    val QUANTUM_NOTIFY_CHAR: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    val QUANTUM_WRITE_CHAR: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
    val QUANTUM_STATE_CHAR: UUID = UUID.fromString("0000FFF4-0000-1000-8000-00805F9B34FB")
    /** 41-byte eWalls 2.0.14 controller record: type at byte 34, then
     * big-endian columns/rows at bytes 35..38. */
    val QUANTUM_METADATA_CHAR: UUID = UUID.fromString("0000FFF5-0000-1000-8000-00805F9B34FB")
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** Red Bear Lab UART service spoken by pre-2017 MoonBoard LED kits.
     *  NOT a supported transport — only used to RECOGNIZE such a board at
     *  service discovery so the user gets an honest "this MoonBoard
     *  generation is not supported yet" message instead of a silent
     *  disconnect loop. */
    val REDBEAR_UART_SERVICE: UUID = UUID.fromString("713D0000-503E-4C75-BA94-3148F18D941E")
    val CRUXCOACH_CLIMB_SHARING: UUID = UUID.fromString("C1140B00-CC01-4000-8000-DEADC0AC0001")
}
