package com.cruxcoach.android.ui.devcontact

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which parts of a message open in the browser: http and https links only, without the punctuation after them. */
class MessageLinksTest {

    private fun urls(text: String) = messageLinks(text).map { it.url }

    private fun linked(text: String) = messageLinks(text).map { text.substring(it.range) }

    @Test
    fun `http and https links are found where they stand`() {
        val text = "Build: https://cruxcoach.org/download and http://example.org/a?b=1&c=2#d, see you"
        assertEquals(listOf("https://cruxcoach.org/download", "http://example.org/a?b=1&c=2#d"), linked(text))
        assertEquals(linked(text), urls(text))
    }

    @Test
    fun `other kinds of links are not opened`() {
        val text = "intent://scan#Intent;scheme=x;end javascript:alert(1) file:///sdcard/a ftp://x.org " +
            "mailto:a@b.org nostr:npub1abc www.cruxcoach.org cruxcoach.org content://media/1 market://details?id=x"
        assertEquals(emptyList<String>(), urls(text))
    }

    @Test
    fun `a scheme inside another word is not a link`() {
        assertEquals(emptyList<String>(), urls("xhttps://evil.example and fooHTTP://a.b"))
    }

    @Test
    fun `punctuation and closing quotes after a link stay text`() {
        assertEquals(listOf("https://a.org/x"), linked("Look at https://a.org/x."))
        assertEquals(listOf("https://a.org/x"), linked("(https://a.org/x)"))
        assertEquals(listOf("https://a.org/x"), linked("„https://a.org/x“"))
        assertEquals(listOf("https://a.org/x"), linked("\"https://a.org/x\","))
        assertEquals(listOf("https://a.org/x"), linked("https://a.org/x?!"))
        assertEquals(listOf("https://a.org/x"), linked("<https://a.org/x>"))
        assertEquals(listOf("https://a.org/x"), linked("`https://a.org/x`"))
        assertEquals(listOf("https://a.org/x"), linked("https://a.org/x…"))
        assertEquals(listOf("https://a.org/x"), linked("https://a.org/x\u00A0und mehr"))
        assertEquals(listOf("https://a.org/x"), linked("https://a.org/x\u202F!"))
    }

    @Test
    fun `brackets the link opened itself stay in it`() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Crux_(climbing)"),
            linked("See https://en.wikipedia.org/wiki/Crux_(climbing)."),
        )
        assertEquals(listOf("https://a.org/[1]"), linked("[https://a.org/[1]]"))
    }

    @Test
    fun `a scheme alone is no link, and the scheme is opened in lower case`() {
        assertEquals(emptyList<String>(), urls("https:// and http://."))
        assertEquals(listOf("https://CruxCoach.org/A"), urls("HTTPS://CruxCoach.org/A"))
        assertEquals(listOf("HTTPS://CruxCoach.org/A"), linked("HTTPS://CruxCoach.org/A"))
    }

    @Test
    fun `links over several lines are found one by one`() {
        assertEquals(listOf("https://a.org", "https://b.org/c"), linked("1. https://a.org\n2. https://b.org/c\n"))
    }
}
