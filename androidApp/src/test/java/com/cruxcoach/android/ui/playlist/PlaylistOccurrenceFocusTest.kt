package com.cruxcoach.android.ui.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOccurrenceFocusTest {
    private val entries = listOf("first", "repeat-a", "repeat-b", "last")

    @Test fun `route occurrence wins over canonical current`() {
        assertEquals(
            "repeat-b",
            PlaylistOccurrenceFocus.resolve(entries, "repeat-b", null, "first"),
        )
    }

    @Test fun `existing local focus survives canonical current movement`() {
        assertEquals(
            "repeat-b",
            PlaylistOccurrenceFocus.resolve(entries, "first", "repeat-b", "last"),
        )
    }

    @Test fun `previous and next step occurrence identity locally`() {
        assertEquals("repeat-a", PlaylistOccurrenceFocus.step(entries, "repeat-b", -1))
        assertEquals("last", PlaylistOccurrenceFocus.step(entries, "repeat-b", 1))
    }
}
