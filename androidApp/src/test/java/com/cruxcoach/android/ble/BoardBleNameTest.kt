package com.cruxcoach.android.ble

import android.content.Context
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the pure BLE-name classifiers (FEAT-031). The scanner only
 * stores its Context, so a relaxed mock is enough to reach the string logic.
 */
class BoardBleNameTest {

    private val scanner = BoardBleScanner(mockk<Context>(relaxed = true))

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
}
