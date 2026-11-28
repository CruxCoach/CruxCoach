package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.ShinyWallVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Device 0.2.5: Kilter at 45° → Shiny Wall, whose catalogue was not downloaded yet. The download finished and
 * the list showed Shiny Wall climbs, but the angle sheet offered no angles until a restart. From Kilter at 70°
 * the list stayed empty (Shiny Wall has 0–60°) with no way out. The switch read the angles before the catalogue
 * existed and nothing read them again.
 */
class BoardBrowserCatalogueAnglesTest {

    private val shinyLayout = ShinyWallVariant.ST1.layoutId.toInt()
    private val shinyAngles = ShinyWallVariant.ANGLES

    private fun onKilter(angle: Int) = BoardBrowserState(
        isLoading = false,
        filter = BrowserFilterState(
            angle = angle,
            layoutId = BoardConstants.KILTER_ORIGINAL_LAYOUT,
            boardBrand = BoardBrand.KILTER.wireValue,
            minAscensionists = 25,
            searchQuery = "crimp",
            minGradeIndex = 3,
            maxGradeIndex = 9,
        ),
    )

    /** The switch as the browser shows it: no catalogue yet, so no chips. */
    private fun switchedToShinyWall(fromAngle: Int) =
        onKilter(fromAngle).showPendingBoard(BoardBrand.SHINYWALL.wireValue, shinyLayout, fromAngle)

    @Test
    fun `a catalogue board switched to before its download waits for its angles`() {
        val shown = switchedToShinyWall(45)
        assertEquals(emptyList(), shown.filter.angleChips)

        assertFalse(
            BoardAnglePicker.awaitsCatalogueChips(BoardBrand.SHINYWALL, shown.filter.angleChips, brandHasCatalogue = false),
            "no catalogue, nothing to read",
        )
        assertTrue(
            BoardAnglePicker.awaitsCatalogueChips(BoardBrand.SHINYWALL, shown.filter.angleChips, brandHasCatalogue = true),
            "the catalogue landed: its angles must be read",
        )
    }

    @Test
    fun `known chips and slider boards need no further read`() {
        assertFalse(BoardAnglePicker.awaitsCatalogueChips(BoardBrand.SHINYWALL, shinyAngles, brandHasCatalogue = true))
        assertFalse(BoardAnglePicker.awaitsCatalogueChips(BoardBrand.KILTER, emptyList(), brandHasCatalogue = true))
        assertFalse(BoardAnglePicker.awaitsCatalogueChips(BoardBrand.MOONBOARD, emptyList(), brandHasCatalogue = true))
        assertTrue(BoardAnglePicker.awaitsCatalogueChips(BoardBrand.TENSION, emptyList(), brandHasCatalogue = true))
        assertTrue(BoardAnglePicker.awaitsCatalogueChips(BoardBrand.QUANTUM, emptyList(), brandHasCatalogue = true))
    }

    @Test
    fun `the downloaded angles appear and a valid angle stays`() {
        val after = switchedToShinyWall(45).withCatalogueAngles(BoardBrand.SHINYWALL.wireValue, shinyLayout, shinyAngles)

        assertEquals(shinyAngles, after.filter.angleChips)
        assertEquals(45, after.filter.angle)
    }

    @Test
    fun `an angle the board lacks snaps to its nearest angle`() {
        val after = switchedToShinyWall(70).withCatalogueAngles(BoardBrand.SHINYWALL.wireValue, shinyLayout, shinyAngles)

        assertEquals(60, after.filter.angle, "70° has no Shiny Wall climbs; 60° is the steepest it has")
        assertEquals(shinyAngles, after.filter.angleChips)
    }

    @Test
    fun `reading the angles leaves every other filter alone`() {
        val before = switchedToShinyWall(70).copy(hsmExcludedMask = 4L)
        val after = before.withCatalogueAngles(BoardBrand.SHINYWALL.wireValue, shinyLayout, shinyAngles)

        assertEquals(before.filter.copy(angle = 60, angleChips = shinyAngles), after.filter)
        assertEquals(25, after.filter.minAscensionists)
        assertEquals("crimp", after.filter.searchQuery)
        assertEquals(4L, after.hsmExcludedMask, "no board switch: the hold-set mask stays")
        assertEquals(before.copy(filter = after.filter), after)
    }

    @Test
    fun `angles read for a board that is no longer on screen are dropped`() {
        val onKilterAgain = onKilter(70)
        val after = onKilterAgain.withCatalogueAngles(BoardBrand.SHINYWALL.wireValue, shinyLayout, shinyAngles)

        assertSame(onKilterAgain, after)
    }
}
