package com.cruxcoach.app.render

import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardGeometryTest {
    @Test
    fun `default edge box is the Kilter 12x12 and y is flipped`() {
        val g = AuroraBoardGeometry.of(null)
        assertEquals(144f / 156f, g.aspectRatio)
        assertEquals(BoardPoint(0f, 1560f), g.point(0, 0, 1440f, 1560f))
        assertEquals(BoardPoint(1440f, 0f), g.point(144, 156, 1440f, 1560f))
        assertEquals(BoardPoint(720f, 780f), g.point(72, 78, 1440f, 1560f))
        assertEquals(10f, g.markerScale(1440f))
    }

    @Test
    fun `edge box of a product size offsets and scales placements`() {
        val size = BoardSize(17, 7, "7x10", edgeLeft = -44, edgeRight = 44, edgeBottom = 24, edgeTop = 144, imageFilename = null)
        val g = AuroraBoardGeometry.of(size)
        // px = (x - edgeLeft) * w / (edgeRight - edgeLeft); py = h - (y - edgeBottom) * h / (edgeTop - edgeBottom)
        assertEquals(BoardPoint(440f, 600f), g.point(0, 84, 880f, 1200f))
        assertEquals(BoardPoint(0.5f, 0.5f), g.normalized(0, 84))
        assertTrue(!g.isVisible(g.point(-60, 84, 880f, 1200f), 880f, 1200f))
        val placements = listOf(BoardPlacement(1, 1, 1, 0, 84), BoardPlacement(2, 2, 1, 8, 84))
        assertEquals(1, g.nearestPlacement(445f, 600f, placements, 880f, 1200f))
        assertEquals(2, g.nearestPlacement(515f, 605f, placements, 880f, 1200f))
        // tap radius = 6 x markerScale(10) = 60 px
        assertNull(g.nearestPlacement(440f, 700f, placements, 880f, 1200f))
        val zone = g.zoneFromCanvas(440f, 600f, 0f, 1200f, 880f, 1200f)
        assertEquals(listOf(-44L, 0L, 24L, 84L), listOf(zone.minX, zone.maxX, zone.minY, zone.maxY))
    }

    @Test
    fun `heatmap threshold and gradient`() {
        assertEquals(0.15f, BoardHeatmap.MIN_INTENSITY)
        assertEquals(0xFF1B5E20, BoardHeatmap.colorArgb(0f))
        assertEquals(0xFFF44336, BoardHeatmap.colorArgb(1f))
        assertEquals(0xFFFFEB3B, BoardHeatmap.colorArgb(0.5f))
    }

    @Test
    fun `palettes and rgb332 expansion`() {
        val d = LedHoldColors()
        assertEquals(listOf(0xE3, 0x03, 0x1C, 0xE0), listOf(d.start, d.hand, d.finish, d.foot))
        assertEquals(LedHoldColors(0x1C, 0x1F, 0xE3, 0xF4), LedHoldColors.standardFor(BoardBrand.KILTER))
        assertEquals(LedHoldColors(0x1C, 0x03, 0xE0, 0xE3), LedHoldColors.standardFor(BoardBrand.TENSION))
        assertEquals(LedHoldColors(0x1C, 0xE3, 0xFF, 0x1F), LedHoldColors.standardFor(BoardBrand.SOILL))
        assertEquals(LedHoldColors(0x1C, 0x03, 0xE0, 0x03), LedHoldColors.standardFor(BoardBrand.MOONBOARD))
        assertEquals(0xFFFF00FF, d.argbForRole(HoldRole.START))
        assertEquals(0xFFFF0000, d.argbForRole(HoldRole.FOOT))
        assertEquals(0xFFFFB600, Rgb332Palette.toArgb(0xF4))
        // 0x25 is not a named entry: r3=1 g3=1 b2=1 -> 36, 36, 85
        assertEquals(0xFF242455, Rgb332Palette.toArgb(0x25))
        assertEquals("color_custom", Rgb332Palette.nameKey(0x25))
        assertEquals(42, Rgb332Palette.PALETTE.size)
        assertEquals(42, Rgb332Palette.PALETTE.map { it.byte }.toSet().size)
    }

    @Test
    fun `moonboard ids roles grid and measured map`() {
        assertEquals(1, MoonBoardRender.holdId(1, 0))
        assertEquals(198, MoonBoardRender.holdId(18, 10))
        assertEquals(0xFF2FB84A, MoonBoardRender.roleArgb(42))
        assertEquals(0xFF2F6BE0, MoonBoardRender.roleArgb(43))
        assertEquals(0xFFE23B36, MoonBoardRender.roleArgb(44))
        assertNull(MoonBoardRender.roleArgb(12))

        val grid = MoonBoardGridGeometry(18)
        assertEquals(0.65f, grid.aspectRatio)
        val a1 = grid.point(1, 650f, 1000f)!!
        assertEquals(650f * 0.09f, a1.x, 0.001f)
        assertEquals(1000f * 0.955f, a1.y, 0.001f)
        val k18 = grid.point(198, 650f, 1000f)!!
        assertEquals(650f * 0.94f, k18.x, 0.001f)
        assertEquals(1000f * 0.055f, k18.y, 0.001f)
        assertNull(grid.point(199, 650f, 1000f))
        for (id in listOf(1, 17, 100, 198)) {
            val p = grid.point(id, 650f, 1000f)!!
            assertEquals(id, grid.holdIdAt(p.x + 3f, p.y - 3f, 650f, 1000f))
        }
        assertNull(MoonBoardGridGeometry(12).point(133, 650f, 1000f))

        val layout = parseMoonBoardLayout(
            """{"variant":"x","image":"x.webp","imageAspect":0.7,"grid":{"rows":18},
               "holds":[{"holdId":1,"x":0.1,"y":0.9,"occupied":true},{"holdId":2,"x":0.2,"y":0.9,"occupied":false}]}""",
        )
        val mapped = MoonBoardMappedGeometry(layout)
        assertEquals(BoardPoint(100f, 1260f), mapped.point(1, 1000f, 1400f))
        assertEquals(28f, mapped.holdRadius(1000f))
        assertEquals(2, mapped.holdIdAt(210f, 1265f, 1000f, 1400f))
        assertNull(mapped.holdIdAt(500f, 500f, 1000f, 1400f))
        assertNull(parseMoonBoardLayoutOrNull("{not json"))
        assertEquals("board_images/mini_moonboard_2020.json", MoonBoardVariant.MINI_2020.layoutJsonAssetPath())
    }
}
