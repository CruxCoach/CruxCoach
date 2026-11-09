package com.cruxcoach.android.ui.paintbox

import androidx.compose.ui.geometry.Offset
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PaintBoxTest {
    @Test fun `a complete stroke is undone and redone together`() {
        val history = PaintHistory()
        val empty = emptyMap<Int, Int>()
        val stroke = mapOf(1 to 28, 2 to 28, 3 to 28)
        history.begin(empty)
        history.end(stroke)
        assertEquals(empty, history.undo(stroke))
        assertEquals(stroke, history.redo(empty))
        history.begin(stroke)
        history.end(stroke)
        assertEquals(empty, history.undo(stroke))
    }
    @Test fun `a new edit invalidates redo and history is bounded`() {
        val h = PaintHistory()
        var colors = emptyMap<Int, Int>()
        repeat(60) { i -> h.begin(colors); colors = colors + (i + 1 to 28); h.end(colors) }
        repeat(50) { colors = h.undo(colors) }
        assertEquals(10, colors.size)
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)
        h.begin(colors); colors = colors + (99 to 3); h.end(colors)
        assertFalse(h.canRedo)
    }
    @Test fun `fast drag samples intermediate holds`() {
        val samples = sampledSegment(Offset.Zero, Offset(100f, 0f))
        assertTrue(samples.any { it.x == 50f })
        assertEquals(Offset.Zero, samples.first())
        assertEquals(Offset(100f, 0f), samples.last())
    }
    @Test fun `picture round trip preserves board and individual colors`() {
        val picture = PaintDocument(name = "Sun", board = PaintBoard("kilter", 1, 10), colors = mapOf(1 to 28, 2 to 224))
        picture.validate()
        assertEquals(picture, Json.decodeFromString<PaintDocument>(Json.encodeToString(picture)))
    }
    @Test fun `moon colors become native roles and quantum colors are exact`() {
        assertEquals("p1r42p2r43p3r44", PaintPalette.moonFrames(mapOf(3 to 224, 1 to 28, 2 to 3)))
        assertEquals(listOf(0x00ff00, 0x00ffff, 0xff00ff, 0xffff00), PaintPalette.quantum.map(PaintPalette::rgb))
        assertEquals(3, PaintPalette.forBoard(BoardBrand.MOONBOARD).size)
    }
    @Test fun `unknown format and unsupported colors are rejected`() {
        val base = PaintDocument(board = PaintBoard("moonboard", 2, 0))
        base.validate()
        assertTrue(runCatching { base.copy(version = 2).validate() }.isFailure)
        assertTrue(runCatching { base.copy(colors = mapOf(1 to 255)).validate() }.isFailure)
        assertTrue(runCatching { base.copy(id = "../escape").validate() }.isFailure)
    }
    @Test fun `MoonBoard mounted sets constrain paint and survive save data`() {
        val board = PaintBoard("moonboard", 2, 0, listOf(2L))
        val mounted = board.moonHoldIds()
        val full = board.copy(holdSets = emptyList()).moonHoldIds()
        assertTrue(mounted.isNotEmpty())
        assertTrue(full.size > mounted.size)
        PaintDocument(board = board, colors = mapOf(mounted.first() to 28)).validate()
        assertTrue(runCatching {
            PaintDocument(board = board, colors = mapOf((full - mounted).first() to 28)).validate()
        }.isFailure)
    }
}
