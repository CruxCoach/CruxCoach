package com.cruxcoach.app.ui

import com.cruxcoach.app.ble.DiscoveredBoard
import com.cruxcoach.domain.board.BoardBrand

/** The board family the connected controller suggests; an empty [brandWire] = none recognised. */
class BluetoothBoardSuggestionUi(val brandWire: String, val brandTitle: String)

/**
 * Android `onboardingBoardSuggestion`: the board picker's "Find via Bluetooth" only narrows the
 * family, from the controller's advertised name. A CruxRelay is another phone, not a board, and
 * suggests nothing; model and size are always the user's choice.
 */
internal object BluetoothBoardSuggestion {
    fun brand(board: DiscoveredBoard?): BoardBrand? {
        if (board == null || board.isCruxRelay) return null
        val name = board.displayName.lowercase().replace(" ", "").replace("-", "")
        return when {
            name.startsWith("kilter") -> BoardBrand.KILTER
            name.startsWith("moonboard") -> BoardBrand.MOONBOARD
            name.startsWith("tension") -> BoardBrand.TENSION
            name.startsWith("grasshopper") -> BoardBrand.GRASSHOPPER
            name.startsWith("decoy") -> BoardBrand.DECOY
            name.startsWith("soill") -> BoardBrand.SOILL
            name.startsWith("touchstone") -> BoardBrand.TOUCHSTONE
            name.startsWith("quantum") -> BoardBrand.QUANTUM
            else -> null
        }
    }

    fun of(board: DiscoveredBoard?): BluetoothBoardSuggestionUi =
        brand(board)?.let { BluetoothBoardSuggestionUi(it.wireValue, it.displayName) }
            ?: BluetoothBoardSuggestionUi("", "")
}
