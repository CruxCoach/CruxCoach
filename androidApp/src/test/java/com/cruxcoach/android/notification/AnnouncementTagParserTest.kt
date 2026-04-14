package com.cruxcoach.android.notification

import org.junit.Assert.*
import org.junit.Test

class AnnouncementTagParserTest {

    // ── findNotifyTag ────────────────────────────────────────────

    @Test
    fun `findNotifyTag returns matching tag value`() {
        val tags = arrayOf(
            arrayOf("p", "somePubkey"),
            arrayOf("t", "cruxcoach-notify-release")
        )
        assertEquals("cruxcoach-notify-release", AnnouncementTagParser.findNotifyTag(tags))
    }

    @Test
    fun `findNotifyTag returns first matching tag when multiple present`() {
        val tags = arrayOf(
            arrayOf("t", "cruxcoach-notify-release"),
            arrayOf("t", "cruxcoach-notify-tip")
        )
        assertEquals("cruxcoach-notify-release", AnnouncementTagParser.findNotifyTag(tags))
    }

    @Test
    fun `findNotifyTag returns null when no t tags`() {
        val tags = arrayOf(
            arrayOf("p", "somePubkey"),
            arrayOf("e", "someEventId")
        )
        assertNull(AnnouncementTagParser.findNotifyTag(tags))
    }

    @Test
    fun `findNotifyTag returns null when t tag has wrong prefix`() {
        val tags = arrayOf(
            arrayOf("t", "some-other-hashtag"),
            arrayOf("t", "unrelated")
        )
        assertNull(AnnouncementTagParser.findNotifyTag(tags))
    }

    @Test
    fun `findNotifyTag returns null for empty tags`() {
        assertNull(AnnouncementTagParser.findNotifyTag(emptyArray()))
    }

    @Test
    fun `findNotifyTag ignores t tag with only one element`() {
        val tags = arrayOf(arrayOf("t"))
        assertNull(AnnouncementTagParser.findNotifyTag(tags))
    }

    // ── extractCategory ──────────────────────────────────────────

    @Test
    fun `extractCategory maps release tag`() {
        assertEquals("release", AnnouncementTagParser.extractCategory("cruxcoach-notify-release"))
    }

    @Test
    fun `extractCategory maps issue tag`() {
        assertEquals("issue", AnnouncementTagParser.extractCategory("cruxcoach-notify-issue"))
    }

    @Test
    fun `extractCategory maps tip tag`() {
        assertEquals("tip", AnnouncementTagParser.extractCategory("cruxcoach-notify-tip"))
    }

    @Test
    fun `extractCategory defaults to general for unknown tag`() {
        assertEquals("general", AnnouncementTagParser.extractCategory("cruxcoach-notify-unknown"))
    }

    @Test
    fun `extractCategory defaults to general for empty string`() {
        assertEquals("general", AnnouncementTagParser.extractCategory(""))
    }

    // ── extractPriority ──────────────────────────────────────────

    @Test
    fun `extractPriority maps release to high`() {
        assertEquals("high", AnnouncementTagParser.extractPriority("cruxcoach-notify-release"))
    }

    @Test
    fun `extractPriority maps issue to default`() {
        assertEquals("default", AnnouncementTagParser.extractPriority("cruxcoach-notify-issue"))
    }

    @Test
    fun `extractPriority maps tip to low`() {
        assertEquals("low", AnnouncementTagParser.extractPriority("cruxcoach-notify-tip"))
    }

    @Test
    fun `extractPriority defaults to default for unknown tag`() {
        assertEquals("default", AnnouncementTagParser.extractPriority("cruxcoach-notify-unknown"))
    }

    // ── extractLanguage ──────────────────────────────────────────

    @Test
    fun `extractLanguage returns language from NIP-32 i18n tag`() {
        val tags = arrayOf(
            arrayOf("t", "cruxcoach-notify-release"),
            arrayOf("l", "de", "i18n")
        )
        assertEquals("de", AnnouncementTagParser.extractLanguage(tags))
    }

    @Test
    fun `extractLanguage returns null when no i18n tag`() {
        val tags = arrayOf(
            arrayOf("t", "cruxcoach-notify-release"),
            arrayOf("l", "de", "other-namespace")
        )
        assertNull(AnnouncementTagParser.extractLanguage(tags))
    }

    @Test
    fun `extractLanguage returns null for empty tags`() {
        assertNull(AnnouncementTagParser.extractLanguage(emptyArray()))
    }

    @Test
    fun `extractLanguage ignores l tag with fewer than 3 elements`() {
        val tags = arrayOf(arrayOf("l", "de"))
        assertNull(AnnouncementTagParser.extractLanguage(tags))
    }

    // ── extractTranslationOfId ───────────────────────────────────

    @Test
    fun `extractTranslationOfId returns event id from translation e-tag`() {
        val tags = arrayOf(
            arrayOf("e", "abc123", "wss://relay.example.com", "translation")
        )
        assertEquals("abc123", AnnouncementTagParser.extractTranslationOfId(tags))
    }

    @Test
    fun `extractTranslationOfId returns null when e-tag has different marker`() {
        val tags = arrayOf(
            arrayOf("e", "abc123", "wss://relay.example.com", "reply")
        )
        assertNull(AnnouncementTagParser.extractTranslationOfId(tags))
    }

    @Test
    fun `extractTranslationOfId returns null for empty tags`() {
        assertNull(AnnouncementTagParser.extractTranslationOfId(emptyArray()))
    }

    @Test
    fun `extractTranslationOfId ignores e-tag with fewer than 4 elements`() {
        val tags = arrayOf(arrayOf("e", "abc123", "wss://relay.example.com"))
        assertNull(AnnouncementTagParser.extractTranslationOfId(tags))
    }
}
