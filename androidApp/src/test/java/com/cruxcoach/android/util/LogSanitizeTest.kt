package com.cruxcoach.android.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogSanitizeTest {
    @Test
    fun `neutralises line control and bidi characters`() {
        val rendered = "a\r\nb\tc\u001bd\u007fe\u2028f\u2029g\u202eh\u2066i".forLog()

        assertEquals("a··b·c·d·e·f·g·h·i", rendered)
        assertFalse(rendered.any { it == '\r' || it == '\n' || it == '\t' })
        assertFalse(rendered.any { it == '\u2028' || it == '\u2029' || it == '\u202e' || it == '\u2066' })
    }

    @Test
    fun `bounds output and marks truncation`() {
        assertEquals("abcd…", "abcdef".forLog(5))
        assertEquals("…", "abcdef".forLog(1))
        assertEquals("", "abcdef".forLog(0))
        assertEquals("abcde", "abcde".forLog(5))
    }
}
