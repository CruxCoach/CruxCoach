package com.cruxcoach.android.data

import com.cruxcoach.domain.relay.RelayBoardName

/**
 * Checks a Bluetooth name the user confirms for their phone, when CruxCoach
 * does not know the phone's earlier name (sharing settings).
 */
object BluetoothNameInput {
    /** [android.bluetooth.BluetoothAdapter.setName]: at most 248 bytes in UTF-8. */
    const val MAX_BYTES = 248

    enum class Problem { BLANK, SHARING_NAME, TOO_LONG }

    fun problem(name: String): Problem? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> Problem.BLANK
            RelayBoardName.isOwnAdapterName(trimmed) -> Problem.SHARING_NAME
            trimmed.encodeToByteArray().size > MAX_BYTES -> Problem.TOO_LONG
            else -> null
        }
    }

    /** The device name from the phone settings, offered for editing, unless it could not be used. */
    fun suggestion(deviceName: String?): String =
        deviceName?.trim()?.takeIf { problem(it) == null }.orEmpty()
}
