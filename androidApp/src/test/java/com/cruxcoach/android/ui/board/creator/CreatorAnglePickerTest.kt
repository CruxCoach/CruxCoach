package com.cruxcoach.android.ui.board.creator

import com.cruxcoach.android.fakes.FakeBoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FEAT-033: the climb-creator's angle dropdown is board-specific.
 *
 * Verifies [CreatorAnglePicker] — the pure options-source + snap logic the
 * ClimbEditorViewModel uses in loadBoardData (the VM does the brand-scoped
 * DISTINCT climb_stats.angle IO; this object decides what the dropdown offers
 * and keeps the seeded angle valid):
 * - MoonBoard → the variant's fixed-config angles
 * - an Aurora-family board → its real supported-angle set (incl. negatives)
 * - Kilter → its real supported-angle set too (data-driven, no slider here)
 * - an empty query (brand-new board) → the generic 20–70° fallback
 * - a seeded angle outside the set snaps to the nearest offered option
 */
class CreatorAnglePickerTest {

    private val generic = listOf(20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70)

    // ── options source per brand ─────────────────────────────

    @Test
    fun `MoonBoard options come from the variant fixed configs`() {
        // Masters 2017 (layout 4) is set at 25 / 40.
        val options = CreatorAnglePicker.optionsFor(
            brand = BoardBrand.MOONBOARD,
            layoutId = MoonBoardVariant.MASTERS_2017.layoutId,
            supportedAngles = emptyList(),
        )
        assertEquals(listOf(25, 40), options)
    }

    @Test
    fun `Aurora board options come from the supported-angle set`() {
        // Touchstone-like board: getSupportedAnglesForLayout returns [35, 40].
        val repo = FakeBoardRepository()
        repo.supportedAnglesByBrand["touchstone"] = listOf(35, 40)
        val supported = repo.getSupportedAnglesForLayout(layoutId = 1, boardBrand = "touchstone")

        val options = CreatorAnglePicker.optionsFor(
            brand = BoardBrand.TOUCHSTONE,
            layoutId = 1L,
            supportedAngles = supported,
        )
        assertEquals(listOf(35, 40), options)
    }

    @Test
    fun `Aurora board keeps negative angles`() {
        // Grasshopper-like board supports a negative low angle (-5°).
        val options = CreatorAnglePicker.optionsFor(
            brand = BoardBrand.GRASSHOPPER,
            layoutId = 1L,
            supportedAngles = listOf(-5, 0, 5, 10, 60),
        )
        assertEquals(listOf(-5, 0, 5, 10, 60), options)
    }

    @Test
    fun `Kilter uses its real data-driven set not a hardcoded list`() {
        val options = CreatorAnglePicker.optionsFor(
            brand = BoardBrand.KILTER,
            layoutId = 1L,
            supportedAngles = listOf(0, 5, 10, 15, 20),
        )
        assertEquals(listOf(0, 5, 10, 15, 20), options)
    }

    @Test
    fun `empty query falls back to the generic list so the dropdown is never empty`() {
        // Brand-new board with no climbs yet → DISTINCT query returns [].
        val options = CreatorAnglePicker.optionsFor(
            brand = BoardBrand.TENSION,
            layoutId = 1L,
            supportedAngles = emptyList(),
        )
        assertEquals(generic, options)
    }

    // ── seeded-angle snap ────────────────────────────────────

    @Test
    fun `seeded angle outside the set snaps to the nearest option`() {
        // A 30° pref carried onto a 35°/40°-only Touchstone snaps up to 35.
        assertEquals(35, CreatorAnglePicker.snapAngle(angle = 30, options = listOf(35, 40)))
    }

    @Test
    fun `seeded angle already in the set is left unchanged`() {
        assertEquals(40, CreatorAnglePicker.snapAngle(angle = 40, options = listOf(35, 40)))
    }

    @Test
    fun `seeded angle snaps to the nearest including negative options`() {
        assertEquals(-5, CreatorAnglePicker.snapAngle(angle = -3, options = listOf(-5, 0, 5)))
    }
}
