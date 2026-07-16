package com.cruxcoach.android.ui.board.creator

import com.cruxcoach.android.community.autoNoteTags
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoNotePublishPolicyTest {

    @Test
    fun `edited text wins over the resource default`() {
        val spec = resolveAutoNoteSpec(
            enabled = true,
            editedText = "My reviewed note without a maintainer mention",
            defaultTemplate = "Default {npub_cruxcoach}",
        )

        assertEquals("My reviewed note without a maintainer mention", spec?.template)
    }

    @Test
    fun `explicitly clearing the editor suppresses the note`() {
        assertNull(
            resolveAutoNoteSpec(
                enabled = true,
                editedText = "",
                defaultTemplate = "Default {npub_cruxcoach}",
            )
        )
    }

    @Test
    fun `unseeded globally enabled editor uses the visible default`() {
        val spec = resolveAutoNoteSpec(
            enabled = true,
            editedText = null,
            defaultTemplate = "Default template",
        )

        assertEquals("Default template", spec?.template)
    }

    @Test
    fun `automatic tags contain discovery hashtags but no person mention`() {
        val tags = autoNoteTags(BoardBrand.KILTER).map(Array<String>::toList)

        assertEquals(
            listOf(listOf("t", "kilterboard"), listOf("t", "climbing")),
            tags,
        )
    }
}
