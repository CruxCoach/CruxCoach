package com.cruxcoach.board

import com.cruxcoach.domain.board.CruxBoardLayout
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CruxBoardLayout] parsing and the derived lookup/mirror
 * accessors. The embedded sample is a verbatim subset of the real
 * 288-position `layout.json`: a main-tier mirror pair (M0000 ↔ M1100),
 * an aux-tier mirror pair (A0100 ↔ A0900) and a self-symmetric aux
 * center-column position (A0500, mirror_id == id).
 */
class CruxBoardLayoutTest {

    // Verbatim entries from the v1 reference layout, incl. an unknown
    // meta key ("seed") that ignoreUnknownKeys must tolerate.
    private val sampleJson = """
        {
          "meta": {
            "name": "CruxCoach Board v1 Referenz-Layout",
            "grid_mm": 200,
            "main_cols": 12,
            "main_rows": 13,
            "symmetry_axis_x_mm": 1100.0,
            "led_offset_y_mm": -60,
            "total_positions": 288,
            "seed": 1234,
            "note": "Regel-basiertes v1-Referenzlayout"
          },
          "positions": [
            {"id": "M0000", "tier": "main", "col": 0, "row": 0, "x_mm": 0, "y_mm": 0,
             "hold_type": "PINCH", "role": "hand", "mirror_id": "M1100",
             "led_index": 0, "led_hole_x_mm": 0, "led_hole_y_mm": -60},
            {"id": "M1100", "tier": "main", "col": 11, "row": 0, "x_mm": 2200, "y_mm": 0,
             "hold_type": "PINCH", "role": "hand", "mirror_id": "M0000",
             "led_index": 275, "led_hole_x_mm": 2200, "led_hole_y_mm": -60},
            {"id": "A0100", "tier": "aux", "col": 1, "row": 0, "x_mm": 300, "y_mm": 100,
             "hold_type": "FOOT_CHIP", "role": "foot", "mirror_id": "A0900",
             "led_index": 49, "led_hole_x_mm": 300, "led_hole_y_mm": 40},
            {"id": "A0900", "tier": "aux", "col": 9, "row": 0, "x_mm": 1900, "y_mm": 100,
             "hold_type": "FOOT_CHIP", "role": "foot", "mirror_id": "A0100",
             "led_index": 249, "led_hole_x_mm": 1900, "led_hole_y_mm": 40},
            {"id": "A0500", "tier": "aux", "col": 5, "row": 0, "x_mm": 1100, "y_mm": 100,
             "hold_type": "FOOT_CHIP", "role": "foot", "mirror_id": "A0500",
             "led_index": 149, "led_hole_x_mm": 1100, "led_hole_y_mm": 40}
          ]
        }
    """.trimIndent()

    private val layout = CruxBoardLayout.parse(sampleJson)

    // ── Parsing / @SerialName mapping ────────────────────────────────────

    @Test
    fun parsesMetaWithSnakeCaseKeysAndIgnoresUnknownOnes() {
        val meta = layout.meta
        assertEquals("CruxCoach Board v1 Referenz-Layout", meta.name)
        assertEquals(200, meta.gridMm)
        assertEquals(12, meta.mainCols)
        assertEquals(13, meta.mainRows)
        assertEquals(1100.0, meta.symmetryAxisXMm)
        assertEquals(-60, meta.ledOffsetYMm)
        assertEquals(288, meta.totalPositions)
    }

    @Test
    fun parsesPositionFieldsWithSnakeCaseKeys() {
        assertEquals(5, layout.positions.size)
        val p = layout.positions.first()
        assertEquals("M0000", p.id)
        assertEquals("main", p.tier)
        assertEquals(0, p.col)
        assertEquals(0, p.row)
        assertEquals(0, p.xMm)
        assertEquals(0, p.yMm)
        assertEquals("PINCH", p.holdType)
        assertEquals("hand", p.role)
        assertEquals("M1100", p.mirrorId)
        assertEquals(0, p.ledIndex)
        assertEquals(0, p.ledHoleXMm)
        assertEquals(-60, p.ledHoleYMm)
    }

    @Test
    fun parseRejectsMalformedJson() {
        assertFailsWith<SerializationException> { CruxBoardLayout.parse("not json") }
        assertFailsWith<SerializationException> {
            // A position missing required fields must not parse silently.
            CruxBoardLayout.parse("""{"meta": {}, "positions": [{"id": "M0000"}]}""")
        }
    }

    // ── Lookup maps ──────────────────────────────────────────────────────

    @Test
    fun positionsByIdLooksUpEveryPosition() {
        assertEquals(5, layout.positionsById.size)
        assertEquals(2200, layout.positionsById.getValue("M1100").xMm)
        assertEquals("aux", layout.positionsById.getValue("A0500").tier)
        assertNull(layout.positionsById["M9999"])
    }

    @Test
    fun byLedIndexLooksUpEveryPosition() {
        assertEquals(5, layout.byLedIndex.size)
        assertEquals("M0000", layout.byLedIndex.getValue(0).id)
        assertEquals("M1100", layout.byLedIndex.getValue(275).id)
        assertEquals("A0500", layout.byLedIndex.getValue(149).id)
        assertNull(layout.byLedIndex[9999])
    }

    // ── Mirror map ───────────────────────────────────────────────────────

    @Test
    fun mirrorMapIsAnInvolution() {
        // Following the mirror map twice must land on the original id.
        for (id in layout.mirrorMap.keys) {
            val once = layout.mirrorOf(id)
            assertTrue(once != null, "mirror of $id missing")
            assertEquals(id, layout.mirrorOf(once), "mirrorOf(mirrorOf($id))")
        }
        assertEquals("M1100", layout.mirrorOf("M0000"))
        assertEquals("A0100", layout.mirrorOf("A0900"))
    }

    @Test
    fun auxCenterColumnPositionIsSelfSymmetric() {
        assertEquals("A0500", layout.mirrorOf("A0500"))
        assertEquals("A0500", layout.positionsById.getValue("A0500").mirrorId)
    }

    @Test
    fun mirrorOfUnknownIdIsNull() {
        assertNull(layout.mirrorOf("M9999"))
    }

    // ── Coordinate / role accessors ──────────────────────────────────────

    @Test
    fun coordinatesOfReturnsBoltPositionInMm() {
        assertEquals(0 to 0, layout.coordinatesOf("M0000"))
        assertEquals(2200 to 0, layout.coordinatesOf("M1100"))
        assertEquals(1100 to 100, layout.coordinatesOf("A0500"))
        assertNull(layout.coordinatesOf("M9999"))
    }

    @Test
    fun roleOfReturnsHandOrFoot() {
        assertEquals("hand", layout.roleOf("M0000"))
        assertEquals("foot", layout.roleOf("A0100"))
        assertNull(layout.roleOf("M9999"))
    }
}
