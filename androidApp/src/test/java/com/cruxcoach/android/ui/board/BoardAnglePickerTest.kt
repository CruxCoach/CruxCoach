package com.cruxcoach.android.ui.board

import com.cruxcoach.android.fakes.FakeBoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FEAT-033: the browse-filter angle picker is board-specific.
 *
 * Verifies [BoardAnglePicker] — the pure chip-source + clamp logic the
 * BoardBrowserViewModel uses at both its angle sites (init + board switch):
 * - MoonBoard → the variant's fixed-config angles (chips)
 * - an Aurora-family board → its real supported-angle set (chips, incl. sparse)
 * - Kilter → empty (the 0-70° slider)
 * - a stale active angle snaps to the nearest available chip
 */
class BoardAnglePickerTest {

    // ── chip source per brand ────────────────────────────────

    @Test
    fun `MoonBoard chips come from the variant fixed configs`() {
        // Masters 2017 (layout 4) is set at 25 / 40.
        val chips = BoardAnglePicker.chipsFor(
            brand = BoardBrand.MOONBOARD,
            layoutId = MoonBoardVariant.MASTERS_2017.layoutId.toInt(),
            supportedAngles = emptyList(),
        )
        assertEquals(listOf(25, 40), chips)
    }

    @Test
    fun `MoonBoard 2016 and 2024 are adjustable so chips offer 25 and 40`() {
        // Both are adjustable boards — the official catalogue sets problems at
        // 25° and 40°, so the picker offers both (widened from 40°-only).
        for (variant in listOf(MoonBoardVariant.MOONBOARD_2016, MoonBoardVariant.MOONBOARD_2024)) {
            val chips = BoardAnglePicker.chipsFor(
                brand = BoardBrand.MOONBOARD,
                layoutId = variant.layoutId.toInt(),
                supportedAngles = emptyList(),
            )
            assertEquals(listOf(25, 40), chips, "expected 25/40 chips for $variant")
        }
    }

    @Test
    fun `Mini boards and MoonBoard 2010 stay fixed at 40`() {
        val fixed = listOf(
            MoonBoardVariant.MINI_2020,
            MoonBoardVariant.MINI_2025,
            MoonBoardVariant.MOONBOARD_2010,
        )
        fixed.forEach { variant ->
            val chips = BoardAnglePicker.chipsFor(
                brand = BoardBrand.MOONBOARD,
                layoutId = variant.layoutId.toInt(),
                supportedAngles = emptyList(),
            )
            assertEquals(listOf(40), chips, "expected fixed 40-degree config for $variant")
        }
    }

    @Test
    fun `Aurora board chips come from the supported-angle set including sparse`() {
        // Touchstone-like board: getSupportedAnglesForLayout returns [35, 40];
        // all angles are kept (no low-count suppression).
        val repo = FakeBoardRepository()
        repo.supportedAnglesByBrand["touchstone"] = listOf(35, 40)
        val supported = repo.getSupportedAnglesForLayout(layoutId = 1, boardBrand = "touchstone")

        val chips = BoardAnglePicker.chipsFor(
            brand = BoardBrand.TOUCHSTONE,
            layoutId = 1,
            supportedAngles = supported,
        )
        assertEquals(listOf(35, 40), chips)
    }

    @Test
    fun `Aurora board keeps negative angles`() {
        val chips = BoardAnglePicker.chipsFor(
            brand = BoardBrand.GRASSHOPPER,
            layoutId = 1,
            supportedAngles = listOf(-5, 0, 5, 10),
        )
        assertEquals(listOf(-5, 0, 5, 10), chips)
    }

    @Test
    fun `Quantum uses only the selected model catalogue angles`() {
        val supported = listOf(15, 25, 30, 35, 40, 45, 50, 55, 60)
        assertEquals(
            supported,
            BoardAnglePicker.chipsFor(BoardBrand.QUANTUM, 9101, supported),
        )
        val index = BoardAnglePicker.sliderIndex(supported, 40)
        assertEquals(4, index)
        assertEquals(40, BoardAnglePicker.angleAtSliderIndex(supported, index))
    }

    @Test
    fun `Kilter has no chips so the slider is used`() {
        val chips = BoardAnglePicker.chipsFor(
            brand = BoardBrand.KILTER,
            layoutId = 1,
            supportedAngles = listOf(0, 5, 10), // ignored for Kilter
        )
        assertEquals(emptyList(), chips)
    }

    @Test
    fun `Kilter supported detail angles match the established slider stops`() {
        assertEquals((0..70 step 5).toList(), BoardAnglePicker.kilterSupportedAngles)
        assertEquals(
            (0..70 step 5).toSet(),
            BoardAnglePicker.fixedDetailAngles(BoardBrand.KILTER),
        )
        assertEquals(null, BoardAnglePicker.fixedDetailAngles(BoardBrand.TOUCHSTONE))
    }

    // ── angle clamp on board switch ──────────────────────────

    @Test
    fun `stale angle snaps to the nearest chip`() {
        // Active angle 25 on a board whose set is [35, 40] → snaps to 35.
        assertEquals(35, BoardAnglePicker.clampAngle(angle = 25, chips = listOf(35, 40)))
    }

    @Test
    fun `valid angle is left unchanged`() {
        assertEquals(40, BoardAnglePicker.clampAngle(angle = 40, chips = listOf(35, 40)))
    }

    @Test
    fun `negative angle clamps to nearest including negative chips`() {
        assertEquals(-5, BoardAnglePicker.clampAngle(angle = -3, chips = listOf(-5, 0, 5)))
    }

    @Test
    fun `empty chips leave the slider angle untouched`() {
        assertEquals(37, BoardAnglePicker.clampAngle(angle = 37, chips = emptyList()))
    }
}
