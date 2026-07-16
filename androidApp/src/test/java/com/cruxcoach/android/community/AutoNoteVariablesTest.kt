package com.cruxcoach.android.community

import com.cruxcoach.domain.community.AutoNoteTemplate
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoNoteVariablesTest {
    private val variables = autoNoteVariables(
        climbName = "Test Boulder",
        boardName = "Kilter",
        naddr = "naddr1test",
        authorNpub = "npub1author",
        climbUrl = "https://fork.example/c/naddr1test",
    )

    @Test
    fun `generic placeholders render the current default vocabulary`() {
        assertEquals(
            "Test Boulder · Kilter · npub1author · https://fork.example/c/naddr1test",
            AutoNoteTemplate.render(
                "{name} · {board} · {author_npub} · {climb_url}",
                variables,
            ),
        )
    }

    @Test
    fun `legacy branded placeholder names remain compatible`() {
        assertEquals(
            "npub1author · https://fork.example/c/naddr1test",
            AutoNoteTemplate.render(
                "{npub_cruxcoach} · {cruxcoach_url}",
                variables,
            ),
        )
    }
}
