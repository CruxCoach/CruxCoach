package com.cruxcoach.app.ui

import com.cruxcoach.app.ble.DiscoveredBoard
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals

/** Android `onboardingBoardSuggestion`: the family from the controller's advertised name only. */
class BluetoothBoardSuggestionTest {
    private fun board(name: String, relay: Boolean = false) =
        DiscoveredBoard(displayName = name, serial = "s", apiLevel = 3, identifier = "id", rssi = -50,
                        boardBrand = BoardBrand.KILTER, isCruxRelay = relay)

    @Test
    fun `advertised names map to their family ignoring case spaces and dashes`() {
        assertEquals("kilter", BluetoothBoardSuggestion.of(board("Kilter Board#1234")).brandWire)
        assertEquals("moonboard", BluetoothBoardSuggestion.of(board("MoonBoard A")).brandWire)
        assertEquals("tension", BluetoothBoardSuggestion.of(board("Tension Board 2")).brandWire)
        assertEquals("soill", BluetoothBoardSuggestion.of(board("So-iLL Board")).brandWire)
        assertEquals("touchstone", BluetoothBoardSuggestion.of(board("Touchstone 12")).brandWire)
        assertEquals(BoardBrand.MOONBOARD.displayName, BluetoothBoardSuggestion.of(board("moonboard")).brandTitle)
    }

    @Test
    fun `a relay an unknown name or no board suggest nothing`() {
        assertEquals("", BluetoothBoardSuggestion.of(board("Kilter Board#1", relay = true)).brandWire)
        assertEquals("", BluetoothBoardSuggestion.of(board("LED-Controller")).brandWire, "the family is not guessed from the default brand")
        assertEquals("", BluetoothBoardSuggestion.of(null).brandWire)
        assertEquals("", BluetoothBoardSuggestion.of(null).brandTitle)
    }
}
