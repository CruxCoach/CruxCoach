package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.MoonBoardVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * Validates the bundled MoonBoard layout assets (FEAT-027).
 *
 * [MoonBoardVisualization] places its highlight overlay straight from
 * this coordinate map, so the shipped JSON must parse and cover every
 * one of the 198 grid positions with in-range normalized coordinates —
 * a malformed or short map would silently mis-place holds.
 */
class MoonBoardAssetTest {

    // Unit tests run with the module dir as working dir; the second candidate
    // covers a repo-root invocation.
    private fun loadVariant(name: String): MoonBoardLayoutJson {
        val file = listOf(
            File("src/main/assets/board_images/$name.json"),
            File("androidApp/src/main/assets/board_images/$name.json"),
        ).firstOrNull { it.exists() }
            ?: error("$name.json not found (cwd=${File(".").absolutePath})")
        return parseMoonBoardLayout(file.readText())
    }

    private fun loadLayout(): MoonBoardLayoutJson = loadVariant("moonboard_2016")

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

    @Test
    fun `all bundled variant maps parse with contiguous in-range holds`() {
        listOf(
            "moonboard_2016",
            "moonboard_2017",
            "moonboard_2019",
            "moonboard_2024",
            "mini_moonboard_2020",
            "mini_moonboard_2025",
            "moonboard_2010",
        ).forEach { name ->
            val layout = loadVariant(name)
            assertEquals("$name: variant tag", name, layout.variant)
            assertTrue("$name: has holds", layout.holds.isNotEmpty())
            assertEquals(
                "$name: holdIds must be 1..N contiguous (no gaps/dupes)",
                (1..layout.holds.size).toList(),
                layout.holds.map { it.holdId }.sorted(),
            )
            layout.holds.forEach { h ->
                assertTrue("$name holdId=${h.holdId} x=${h.x} out of 0..1", h.x in 0f..1f)
                assertTrue("$name holdId=${h.holdId} y=${h.y} out of 0..1", h.y in 0f..1f)
            }
            assertTrue("$name: valid board aspect", layout.imageAspect in 0.4f..1.0f)
        }
    }

    @Test
    fun `new fixed configurations reference complete image layer bundles`() {
        val expected = mapOf(
            "moonboard_2010" to 1,
            "mini_moonboard_2025" to 4,
        )
        expected.forEach { (name, overlayCount) ->
            val layout = loadVariant(name)
            assertEquals(overlayCount, layout.overlays.size)
            val base = assetFile(layout.image)
            assertTrue("$name base image missing", base.isFile)
            val dimensions = pngDimensions(base)
            layout.overlays.forEach { filename ->
                val overlay = assetFile(filename)
                assertTrue("$name overlay missing: $filename", overlay.isFile)
                assertEquals("$name overlay dimensions: $filename", dimensions, pngDimensions(overlay))
            }
            assertEquals(dimensions.first.toFloat() / dimensions.second, layout.imageAspect, 0.0001f)
        }
        assertEquals(198, loadVariant("moonboard_2010").holds.size)
        assertEquals(132, loadVariant("mini_moonboard_2025").holds.size)
        assertEquals(40, loadVariant("moonboard_2010").holds.count { it.occupied })
        assertEquals(128, loadVariant("mini_moonboard_2025").holds.count { it.occupied })
    }

    @Test
    fun `preview resolves the new base images inside MoonBoard brand`() {
        assertEquals(
            listOf("board_images/moonboard_2010_base.png"),
            boardPreviewCandidatePaths(
                com.cruxcoach.domain.board.BoardBrand.MOONBOARD,
                0L,
                MoonBoardVariant.MOONBOARD_2010.layoutId,
            ),
        )
        assertEquals(
            listOf("board_images/mini_moonboard_2025_base.png"),
            boardPreviewCandidatePaths(
                com.cruxcoach.domain.board.BoardBrand.MOONBOARD,
                0L,
                MoonBoardVariant.MINI_2025.layoutId,
            ),
        )
    }

    private fun assetFile(filename: String): File = listOf(
        File("src/main/assets/board_images/$filename"),
        File("androidApp/src/main/assets/board_images/$filename"),
    ).firstOrNull { it.exists() } ?: File(filename)

    private fun pngDimensions(file: File): Pair<Int, Int> {
        val bytes = file.readBytes()
        assertTrue("${file.name}: truncated PNG", bytes.size >= 24)
        return ByteBuffer.wrap(bytes, 16, 8).let { it.int to it.int }
    }
}
