package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardBrowserHeaderTest {
    @Test
    fun `header reserves room for the board picker and overflow menu`() {
        assertEquals(5, directHeaderActionCount(360))
        assertEquals(3, directHeaderActionCount(320))
        assertEquals(2, directHeaderActionCount(300))
        assertEquals(1, directHeaderActionCount(260))
        assertEquals(0, directHeaderActionCount(220))
    }

    @Test
    fun `compact priority selects visible actions without changing legacy visual order`() {
        val bluetooth = BoardHeaderAction.BLUETOOTH
        val filter = BoardHeaderAction.FILTER
        val logbook = BoardHeaderAction.LOGBOOK
        val lists = BoardHeaderAction.LISTS
        val settings = BoardHeaderAction.SETTINGS
        val legacyOrder = listOf(bluetooth, filter, logbook, lists, settings)
        val cases = listOf(
            Triple(360, legacyOrder, emptyList()),
            // Settings stays directly reachable even though it is the rightmost icon.
            Triple(320, listOf(bluetooth, filter, settings), listOf(logbook, lists)),
            Triple(300, listOf(bluetooth, filter), listOf(logbook, lists, settings)),
            Triple(260, listOf(bluetooth), listOf(filter, logbook, lists, settings)),
            Triple(220, emptyList(), legacyOrder),
            Triple(400, legacyOrder, emptyList()),
        )
        for ((width, direct, overflow) in cases) {
            val actual = boardHeaderActionLayout(width)
            assertEquals("Direct actions at ${width}dp", direct, actual.direct)
            assertEquals("Overflow actions at ${width}dp", overflow, actual.overflow)
        }
    }

    @Test
    fun `kilter context exposes model and physical subtype`() {
        val context = boardBrowserHeaderContext(
            boardBrand = BoardBrand.KILTER.wireValue,
            layoutId = BoardConstants.KILTER_ORIGINAL_LAYOUT,
            boardSize = BoardSize(
                id = 10,
                productId = 1,
                name = "12x12",
                edgeLeft = 0,
                edgeRight = 0,
                edgeBottom = 0,
                edgeTop = 0,
                imageFilename = null,
            ),
        )

        assertEquals("Kilter Original", context.title)
        assertEquals("12x12, with Kickboard", context.subtitle)
    }

    @Test
    fun `homewall subtype does not repeat the model name`() {
        val context = boardBrowserHeaderContext(
            boardBrand = BoardBrand.KILTER.wireValue,
            layoutId = BoardConstants.KILTER_HOMEWALL_LAYOUT,
            boardSize = BoardSize(
                id = 17,
                productId = 7,
                name = "Homewall 10x7 — Full Ride",
                edgeLeft = 0,
                edgeRight = 0,
                edgeBottom = 0,
                edgeTop = 0,
                imageFilename = null,
            ),
        )

        assertEquals("Kilter Homewall", context.title)
        assertEquals("10x7 — Full Ride", context.subtitle)
    }
}
