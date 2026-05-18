package com.cruxcoach.domain.community

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoNoteTemplateTest {

    @Test
    fun substitutes_known_placeholders() {
        val out = AutoNoteTemplate.render(
            template = "Neuer Boulder „{name}\" — {cruxcoach_url}",
            vars = mapOf("name" to "Pumpdragon", "cruxcoach_url" to "https://cruxcoach.org/c/naddr1xyz"),
        )
        assertEquals("Neuer Boulder „Pumpdragon\" — https://cruxcoach.org/c/naddr1xyz", out)
    }

    @Test
    fun leaves_unknown_placeholders_in_place() {
        // Surface typos — `{naame}` stays visible so a fork-author editing
        // the template sees the error instead of an empty silent gap.
        val out = AutoNoteTemplate.render(
            template = "{naame} via {npub_cruxcoach}",
            vars = mapOf("npub_cruxcoach" to "npub1abc"),
        )
        assertEquals("{naame} via npub1abc", out)
    }

    @Test
    fun renders_full_default_shape() {
        val out = AutoNoteTemplate.render(
            template = """Ich habe einen neuen Boulder auf nostr:{npub_cruxcoach} veröffentlicht: „{name}"

#kilterboard #climbing

{cruxcoach_url}
nostr:{naddr}""",
            vars = mapOf(
                "name" to "Test",
                "naddr" to "naddr1deadbeef",
                "npub_cruxcoach" to "npub1cruxcoach",
                "cruxcoach_url" to "https://cruxcoach.org/c/naddr1deadbeef",
            ),
        )
        val expected = """Ich habe einen neuen Boulder auf nostr:npub1cruxcoach veröffentlicht: „Test"

#kilterboard #climbing

https://cruxcoach.org/c/naddr1deadbeef
nostr:naddr1deadbeef"""
        assertEquals(expected, out)
    }

    @Test
    fun empty_value_substitutes_empty() {
        val out = AutoNoteTemplate.render(
            template = "x={name}=y",
            vars = mapOf("name" to ""),
        )
        assertEquals("x==y", out)
    }
}
