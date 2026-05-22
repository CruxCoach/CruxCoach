package com.cruxcoach.android.ui.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates the bundled MoonBoard 2016 layout asset (FEAT-027).
 *
 * [MoonBoardVisualization] places its highlight overlay straight from
 * this coordinate map, so the shipped JSON must parse and cover every
 * one of the 198 grid positions with in-range normalized coordinates —
 * a malformed or short map would silently mis-place holds.
 */
class MoonBoardAssetTest {

    private fun loadLayout(): MoonBoardLayoutJson {
        // Unit tests run with the module dir as working dir; the second
        // candidate covers a repo-root invocation.
        val file = listOf(
            File("src/main/assets/board_images/moonboard_2016.json"),
            File("androidApp/src/main/assets/board_images/moonboard_2016.json"),
        ).firstOrNull { it.exists() }
            ?: error("moonboard_2016.json not found (cwd=${File(".").absolutePath})")
        return parseMoonBoardLayout(file.readText())
    }

    @Test
    fun `layout parses and covers all 198 grid positions`() {
        val layout = loadLayout()
        assertEquals("moonboard_2016", layout.variant)
        assertEquals("moonboard_2016.webp", layout.image)
        assertEquals(198, layout.holds.size)
        assertEquals((1..198).toList(), layout.holds.map { it.holdId }.sorted())
    }

    @Test
    fun `hold coordinates are normalized within the image`() {
        loadLayout().holds.forEach { h ->
            assertTrue("holdId=${h.holdId} x=${h.x} out of 0..1", h.x in 0f..1f)
            assertTrue("holdId=${h.holdId} y=${h.y} out of 0..1", h.y in 0f..1f)
        }
    }

    @Test
    fun `occupied count matches the sparse 2016 set`() {
        assertEquals(140, loadLayout().holds.count { it.occupied })
    }

    @Test
    fun `image aspect ratio is portrait`() {
        assertTrue(loadLayout().imageAspect in 0.5f..0.8f)
    }
}
