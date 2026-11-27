package com.cruxcoach.android.ble

import android.content.Context
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the pure BLE-name classifiers (FEAT-031). The scanner only
 * stores its Context, so a relaxed mock is enough to reach the string logic.
 */
class BoardBleNameTest {

    private val scanner = BoardBleScanner(mockk<Context>(relaxed = true))

    @Test
    fun quantumNamesRequireCurrentPrefixAndTwelveHexMac() {
        assertEquals("020000000001", scanner.quantumSerialOrNull("QB_020000000001"))
        assertEquals("020000000001", scanner.quantumSerialOrNull("QBB_020000000001"))
        assertEquals("AABBCCDDEEFF", scanner.quantumSerialOrNull("eWalls_room_AABBCCDDEEFF"))
        assertEquals(null, scanner.quantumSerialOrNull("Quantum Board"))
        assertEquals(null, scanner.quantumSerialOrNull("QB_not-a-mac"))
        assertEquals(null, scanner.quantumSerialOrNull("qb_020000000001"))
    }

    @Test
    fun shinyWallStandardControllersAreOfferedAsShinyWall() {
        val board = scanner.shinyWallBoardOrNull("ShinyWall_STP_A1B2C3", isRelay = false, address = "AA:BB", rssi = -60)
        assertEquals(BoardBrand.SHINYWALL, board?.boardBrand)
        assertEquals("A1B2C3", board?.serial)
        assertEquals("ShinyWall_STP_A1B2C3", board?.displayName)
        assertEquals(0, board?.apiLevel)
        assertEquals(
            BoardBrand.SHINYWALL,
            scanner.shinyWallBoardOrNull("Shiny Wall ST0 (ShinyMini)", false, "AA:BB", -60)?.boardBrand,
        )
    }

    @Test
    fun shinyWallCustomRelayedAndLookalikeNamesAreNotOffered() {
        assertNull(scanner.shinyWallBoardOrNull("ShinyWall_CTM_1234", false, "AA:BB", -60))
        assertNull(scanner.shinyWallBoardOrNull("ShinyWall_STP_1234", isRelay = true, address = "AA:BB", rssi = -60))
        assertNull(scanner.shinyWallBoardOrNull("ESP32", false, "AA:BB", -60))
        assertNull(scanner.shinyWallBoardOrNull("Kilter Board#123@3", false, "AA:BB", -60))
    }

    @Test
    fun isMoonBoardName_matches_both_casings_and_rejects_aurora_names() {
        assertTrue(scanner.isMoonBoardName("MoonBoard Masters 2019"))
        assertTrue(scanner.isMoonBoardName("Moonboard"))
        assertFalse(scanner.isMoonBoardName("Kilter Board"))
        assertFalse(scanner.isMoonBoardName("Tension Board 2"))
        assertFalse(scanner.isMoonBoardName("my moonboard")) // prefix only, case-sensitive
    }

    @Test
    fun auroraBrandFromName_maps_each_family_prefix_normalising_spaces_and_hyphens() {
        assertEquals(BoardBrand.TENSION, scanner.auroraBrandFromName("Tension Board 2"))
        assertEquals(BoardBrand.GRASSHOPPER, scanner.auroraBrandFromName("Grasshopper Board"))
        assertEquals(BoardBrand.DECOY, scanner.auroraBrandFromName("Decoy Board"))
        assertEquals(BoardBrand.SOILL, scanner.auroraBrandFromName("So iLL Board"))
        assertEquals(BoardBrand.SOILL, scanner.auroraBrandFromName("So-iLL Board"))
        assertEquals(BoardBrand.TOUCHSTONE, scanner.auroraBrandFromName("Touchstone Board"))
    }

    @Test
    fun auroraBrandFromName_defaults_to_kilter_for_kilter_and_unknown_names() {
        assertEquals(BoardBrand.KILTER, scanner.auroraBrandFromName("Kilter Board"))
        assertEquals(BoardBrand.KILTER, scanner.auroraBrandFromName("Some Unknown Board"))
    }

    @Test
    fun parseAuroraName_accepts_the_bare_name_of_kilter_built_boxes_as_api_2() {
        // Kilter-built boxes (since mid-2025) advertise no #serial and no @apiLevel.
        assertEquals(Triple("Kilter Board", "", 2), scanner.parseAuroraName("Kilter Board", false))
        assertEquals(Triple("Tension Board", "", 2), scanner.parseAuroraName("Tension Board", false))
        assertEquals(Triple("So iLL Board", "", 2), scanner.parseAuroraName("So iLL Board ", false))
        assertEquals(BoardBrand.KILTER, scanner.auroraBrandFromName("Kilter Board"))
    }

    @Test
    fun parseAuroraName_keeps_the_suffixed_formats_unchanged() {
        assertEquals(Triple("Kilter Board", "751737", 3), scanner.parseAuroraName("Kilter Board#751737@3", false))
        assertEquals(Triple("Kilter Board", "751737", 2), scanner.parseAuroraName("Kilter Board#751737", false))
        assertEquals(Triple("Kilter Board", "", 3), scanner.parseAuroraName("Kilter Board@3", false))
    }

    @Test
    fun parseAuroraName_needs_a_family_name_or_the_aurora_service_for_a_bare_name() {
        // The scan has no UUID filter: headphones and watches must not become boards.
        assertNull(scanner.parseAuroraName("Galaxy Buds2", false))
        assertNull(scanner.parseAuroraName("Kilt", false))
        assertEquals(Triple("LED Box", "", 2), scanner.parseAuroraName("LED Box", true))
        assertNull(scanner.parseAuroraName("   ", true))
    }

    @Test
    fun parseAuroraName_needs_a_family_name_or_the_service_for_a_suffixed_name() {
        assertNull(scanner.parseAuroraName("Printer #751737", false))
        assertNull(scanner.parseAuroraName("Foo#bar", false))
        assertNull(scanner.parseAuroraName("Speaker@3", false))
        assertEquals(Triple("Printer ", "751737", 2), scanner.parseAuroraName("Printer #751737", true))
        // Relays and real boxes keep working.
        assertEquals(Triple("Kilter Board", "CR-1", 3), scanner.parseAuroraName("Kilter Board#CR-1@3", false))
        assertEquals(Triple("So iLL Board", "9", 3), scanner.parseAuroraName("So iLL Board#9@3", false))
        assertEquals(Triple("KilterBoard", "ABC123", 3), scanner.parseAuroraName("KilterBoard#ABC123@3", false))
        // The official apps accept the brand anywhere in a suffixed name.
        assertEquals(Triple("Boulderhalle Kilter", "12", 3), scanner.parseAuroraName("Boulderhalle Kilter#12@3", false))
    }

    @Test
    fun parseAuroraName_needs_family_board_for_a_bare_name_without_the_service() {
        assertNull(scanner.parseAuroraName("Tensioner", false))
        assertNull(scanner.parseAuroraName("Decoy", false))
        assertNull(scanner.parseAuroraName("Kilter-anything", false))
        assertEquals(Triple("Decoy", "", 2), scanner.parseAuroraName("Decoy", true))
        assertEquals(Triple("Kilter Board 2", "", 2), scanner.parseAuroraName("Kilter Board 2", false))
        assertEquals(Triple("Grasshopper Board", "", 2), scanner.parseAuroraName("Grasshopper Board", false))
    }

    @Test
    fun parseAuroraName_falls_back_to_api_2_for_an_unreadable_api_level() {
        assertNull(scanner.parseBoardName("Kilter Board@x"))
        assertEquals(Triple("Kilter Board", "", 2), scanner.parseAuroraName("Kilter Board@x", false))
        assertNull(scanner.parseAuroraName("Headset@x", false))
    }
}
