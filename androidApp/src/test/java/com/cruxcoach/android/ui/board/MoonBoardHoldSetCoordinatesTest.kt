package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ties the hold-set cell map (FEAT-049, shared module) to the bundled board
 * geometry it is drawn on (androidApp assets) — AC 13/14.
 *
 * The picker preview rings a set's holds by looking each cell id up in the
 * variant's `board_images/<variant>.json`. A cell with no coordinate would be
 * dropped silently, so the preview would understate the set without any
 * visible failure. This test is the reason that cannot happen unnoticed.
 *
 * It deliberately does NOT consult `occupied`: that flag is parsed but read by
 * nothing, and it is wrong on two boards — 2024 marks 0 of 198 positions
 * occupied and Masters 2019 only 68, while both genuinely carry all 198. A
 * preview filtered on it would render 2024 empty. Cells come from the map,
 * coordinates from the JSON, and nothing else from either.
 */
class MoonBoardHoldSetCoordinatesTest {

    // Unit tests run with the module dir as working dir; the second candidate
    // covers a repo-root invocation.
    private fun loadVariant(variant: MoonBoardVariant): MoonBoardLayoutJson {
        val name = variant.assetBaseName()
        val file = listOf(
            File("src/main/assets/board_images/$name.json"),
            File("androidApp/src/main/assets/board_images/$name.json"),
        ).firstOrNull { it.exists() }
            ?: error("$name.json not found (cwd=${File(".").absolutePath})")
        return parseMoonBoardLayout(file.readText())
    }

    @Test
    fun everyCellInTheHoldSetMap_hasBoardCoordinates() {
        MoonBoardVariant.entries.forEach { variant ->
            val coords = loadVariant(variant).holds.associateBy { it.holdId }
            MoonBoardHoldSets.cellSets(variant).keys.forEach { holdId ->
                val hold = coords[holdId]
                assertTrue(
                    "$variant: cell $holdId has no coordinate in the board image JSON",
                    hold != null,
                )
                assertTrue("$variant: cell $holdId x=${hold!!.x}", hold.x in 0f..1f)
                assertTrue("$variant: cell $holdId y=${hold.y}", hold.y in 0f..1f)
            }
        }
    }

    @Test
    fun everySetPreviewWouldDrawItsFullHoldCount() {
        // AC 13: the rings drawn for a set are exactly its cell-map entries.
        // Counting resolvable coordinates per set is the drawable count, so a
        // regression in either half shows up as a number that no longer
        // matches the §3.2 figures.
        val expected = mapOf(
            MoonBoardVariant.MOONBOARD_2010 to listOf(40),
            MoonBoardVariant.MOONBOARD_2016 to listOf(50, 50, 40),
            MoonBoardVariant.MOONBOARD_2024 to listOf(39, 41, 40, 31, 23, 24),
            MoonBoardVariant.MASTERS_2017 to listOf(40, 40, 52, 34, 32),
            MoonBoardVariant.MASTERS_2019 to listOf(40, 40, 38, 32, 24, 24),
            MoonBoardVariant.MINI_2020 to listOf(40, 32, 24, 24),
            MoonBoardVariant.MINI_2025 to listOf(40, 40, 24, 24),
        )
        MoonBoardVariant.entries.forEach { variant ->
            val coords = loadVariant(variant).holds.map { it.holdId }.toSet()
            val drawable = MoonBoardHoldSets.setIdsFor(variant).map { setId ->
                MoonBoardHoldSets.holdIdsFor(variant, setId).count { it in coords }
            }
            assertEquals("$variant: drawable holds per set", expected[variant], drawable)
        }
    }

    @Test
    fun occupiedFlag_isUnreliableAndMustNotFilterThePreview() {
        // Pins the observation behind edge case 8 so a future "let's only draw
        // occupied holds" cleanup fails loudly instead of blanking a board.
        val h2024 = loadVariant(MoonBoardVariant.MOONBOARD_2024).holds
        assertEquals(198, h2024.size)
        assertEquals(0, h2024.count { it.occupied })
        assertEquals(198, MoonBoardHoldSets.cellSets(MoonBoardVariant.MOONBOARD_2024).size)

        val h2019 = loadVariant(MoonBoardVariant.MASTERS_2019).holds
        assertEquals(198, h2019.size)
        assertEquals(68, h2019.count { it.occupied })
        assertEquals(198, MoonBoardHoldSets.cellSets(MoonBoardVariant.MASTERS_2019).size)
    }
}
