package com.cruxcoach.android.notification

import com.cruxcoach.android.nostr.NostrConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementTagParserTest {

    private val namespace = NostrConfig.ANNOUNCE_NAMESPACE

    // ── isAnnouncement (NIP-32 L tag) ─────────────────────────────

    @Test
    fun `isAnnouncement returns true when namespace L tag present`() {
        val tags = arrayOf(
            arrayOf("p", "somePubkey"),
            arrayOf("L", namespace)
        )
        assertTrue(AnnouncementTagParser.isAnnouncement(tags))
    }

    @Test
    fun `isAnnouncement returns false when L tag has different namespace`() {
        val tags = arrayOf(arrayOf("L", "com.other.namespace"))
        assertFalse(AnnouncementTagParser.isAnnouncement(tags))
    }

    @Test
    fun `isAnnouncement returns false for empty tags`() {
        assertFalse(AnnouncementTagParser.isAnnouncement(emptyArray()))
    }

    @Test
    fun `isAnnouncement ignores L tag with only one element`() {
        val tags = arrayOf(arrayOf("L"))
        assertFalse(AnnouncementTagParser.isAnnouncement(tags))
    }

    // ── extractCategory (NIP-32 l tag) ────────────────────────────

    @Test
    fun `extractCategory reads release from namespaced l tag`() {
        val tags = arrayOf(
            arrayOf("L", namespace),
            arrayOf("l", "release", namespace)
        )
        assertEquals("release", AnnouncementTagParser.extractCategory(tags))
    }

    @Test
    fun `extractCategory reads issue from namespaced l tag`() {
        val tags = arrayOf(arrayOf("l", "issue", namespace))
        assertEquals("issue", AnnouncementTagParser.extractCategory(tags))
    }

    @Test
    fun `extractCategory reads tip from namespaced l tag`() {
        val tags = arrayOf(arrayOf("l", "tip", namespace))
        assertEquals("tip", AnnouncementTagParser.extractCategory(tags))
    }

    @Test
    fun `extractCategory defaults to general for unknown category`() {
        val tags = arrayOf(arrayOf("l", "bogus", namespace))
        assertEquals("general", AnnouncementTagParser.extractCategory(tags))
    }

    @Test
    fun `extractCategory defaults to general when l tag has different namespace`() {
        val tags = arrayOf(arrayOf("l", "release", "com.other.namespace"))
        assertEquals("general", AnnouncementTagParser.extractCategory(tags))
    }

    @Test
    fun `extractCategory defaults to general for empty tags`() {
        assertEquals("general", AnnouncementTagParser.extractCategory(emptyArray()))
    }

    // ── extractPriority ───────────────────────────────────────────

    @Test
    fun `extractPriority maps release to high`() {
        assertEquals("high", AnnouncementTagParser.extractPriority("release"))
    }

    @Test
    fun `extractPriority maps issue to default`() {
        assertEquals("default", AnnouncementTagParser.extractPriority("issue"))
    }

    @Test
    fun `extractPriority maps tip to low`() {
        assertEquals("low", AnnouncementTagParser.extractPriority("tip"))
    }

    @Test
    fun `extractPriority defaults to default for unknown category`() {
        assertEquals("default", AnnouncementTagParser.extractPriority("general"))
    }

    // ── extractLocalizedContent ──────────────────────────────────

    @Test
    fun `extractLocalizedContent returns English section for en`() {
        val content = "\uD83C\uDDEC\uD83C\uDDE7 Hello world\n\n\uD83C\uDDE9\uD83C\uDDEA Hallo Welt"
        assertEquals("Hello world", AnnouncementTagParser.extractLocalizedContent(content, "en"))
    }

    @Test
    fun `extractLocalizedContent returns German section for de`() {
        val content = "\uD83C\uDDEC\uD83C\uDDE7 Hello world\n\n\uD83C\uDDE9\uD83C\uDDEA Hallo Welt"
        assertEquals("Hallo Welt", AnnouncementTagParser.extractLocalizedContent(content, "de"))
    }

    @Test
    fun `extractLocalizedContent falls back to other language when target missing`() {
        val content = "\uD83C\uDDE9\uD83C\uDDEA Nur Deutsch"
        assertEquals("Nur Deutsch", AnnouncementTagParser.extractLocalizedContent(content, "en"))
    }

    @Test
    fun `extractLocalizedContent returns whole content when no flags`() {
        val content = "Plain text without flags"
        assertEquals("Plain text without flags", AnnouncementTagParser.extractLocalizedContent(content, "en"))
    }

    @Test
    fun `extractLocalizedContent preserves an empty section between adjacent flags`() {
        val content = "\uD83C\uDDEC\uD83C\uDDE7\uD83C\uDDE9\uD83C\uDDEA Nur Deutsch"
        assertEquals("", AnnouncementTagParser.extractLocalizedContent(content, "en"))
        assertEquals("Nur Deutsch", AnnouncementTagParser.extractLocalizedContent(content, "de"))
    }
}
