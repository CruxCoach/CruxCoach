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
    fun parseAuroraName_falls_back_to_api_2_for_an_unreadable_api_level() {
        assertNull(scanner.parseBoardName("Kilter Board@x"))
        assertEquals(Triple("Kilter Board", "", 2), scanner.parseAuroraName("Kilter Board@x", false))
        assertNull(scanner.parseAuroraName("Headset@x", false))
    }
}
