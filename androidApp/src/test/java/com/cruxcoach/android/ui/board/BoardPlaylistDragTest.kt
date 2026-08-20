package com.cruxcoach.android.ui.board

import com.cruxcoach.android.boardcell.BoardPlaylistAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BoardPlaylistDragTest {

    @Test fun `preview moves one occurrence without changing its identity`() {
        val order = listOf("first", "repeat-a", "repeat-b", "last")

        assertEquals(
            listOf("first", "repeat-b", "last", "repeat-a"),
            moveBoardPlaylistPreview(order, 1, 3),
        )
    }

    @Test fun `invalid preview target leaves canonical-looking order untouched`() {
        val order = listOf("a", "b")

        assertSame(order, moveBoardPlaylistPreview(order, 0, 9))
    }

    @Test fun `drag start resolves the current occurrence index after an earlier reorder`() {
        val reordered = listOf("b", "c", "a")

        assertEquals(0, boardPlaylistDragStartIndex(reordered, "b"))
        assertEquals(2, boardPlaylistDragStartIndex(reordered, "a"))
    }

    @Test fun `optimistic order accepts unrelated concurrent insertions`() {
        val preview = listOf("b", "c", "a")
        val canonical = listOf("new-head", "b", "c", "new-middle", "a")

        assertEquals(true, canonicalOrderMatchesPreview(canonical, preview))
    }

    @Test fun `drop target is occurrence anchored rather than an unstable index`() {
        assertEquals(
            BoardPlaylistAnchor.After("c"),
            boardPlaylistAnchorForOrder(listOf("b", "c", "a"), "a"),
        )
        assertEquals(
            BoardPlaylistAnchor.Head,
            boardPlaylistAnchorForOrder(listOf("a", "b", "c"), "a"),
        )
    }
}
