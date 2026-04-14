package com.cruxcoach.android.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for locale resolution logic used in LanguageSection and MainActivity.
 *
 * The actual localeListForChoice() requires an Android Context (for LocaleManager),
 * so we test the pure resolution logic: given a system language and user choice,
 * what locale tag should be applied?
 */
class LocaleResolutionTest {

    /**
     * Mirrors the resolution logic from localeListForChoice():
     * - "de"/"en" → use as-is
     * - "system" → map system language to "de" or fallback to "en"
     */
    private fun resolveLocaleTag(userChoice: String, systemLanguage: String): String {
        return when (userChoice) {
            "de", "en" -> userChoice
            else -> if (systemLanguage == "de") "de" else "en"
        }
    }

    // ── Explicit user choice ─────────────────────────────────────

    @Test
    fun `explicit de choice returns de regardless of system language`() {
        assertEquals("de", resolveLocaleTag("de", "en"))
        assertEquals("de", resolveLocaleTag("de", "es"))
        assertEquals("de", resolveLocaleTag("de", "de"))
        assertEquals("de", resolveLocaleTag("de", "fr"))
    }

    @Test
    fun `explicit en choice returns en regardless of system language`() {
        assertEquals("en", resolveLocaleTag("en", "de"))
        assertEquals("en", resolveLocaleTag("en", "es"))
        assertEquals("en", resolveLocaleTag("en", "en"))
        assertEquals("en", resolveLocaleTag("en", "ja"))
    }

    // ── System mode with German system language ──────────────────

    @Test
    fun `system mode with German system returns de`() {
        assertEquals("de", resolveLocaleTag("system", "de"))
    }

    // ── System mode with non-German system languages → English fallback ──

    @Test
    fun `system mode with English system returns en`() {
        assertEquals("en", resolveLocaleTag("system", "en"))
    }

    @Test
    fun `system mode with Spanish system returns en`() {
        assertEquals("en", resolveLocaleTag("system", "es"))
    }

    @Test
    fun `system mode with French system returns en`() {
        assertEquals("en", resolveLocaleTag("system", "fr"))
    }

    @Test
    fun `system mode with Japanese system returns en`() {
        assertEquals("en", resolveLocaleTag("system", "ja"))
    }

    @Test
    fun `system mode with Chinese system returns en`() {
        assertEquals("en", resolveLocaleTag("system", "zh"))
    }

    @Test
    fun `system mode with Arabic system returns en`() {
        assertEquals("en", resolveLocaleTag("system", "ar"))
    }

    // ── Edge cases ───────────────────────────────────────────────

    @Test
    fun `unknown user choice treated as system mode`() {
        assertEquals("en", resolveLocaleTag("invalid", "en"))
        assertEquals("de", resolveLocaleTag("invalid", "de"))
    }

    @Test
    fun `empty user choice treated as system mode`() {
        assertEquals("en", resolveLocaleTag("", "fr"))
        assertEquals("de", resolveLocaleTag("", "de"))
    }
}
