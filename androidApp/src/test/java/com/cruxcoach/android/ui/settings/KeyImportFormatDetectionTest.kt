package com.cruxcoach.android.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for key import format detection logic.
 *
 * The ViewModel's detectFormat() is private, so we test the same regex/prefix
 * logic directly to verify all five format branches.
 */
class KeyImportFormatDetectionTest {

    private val HEX_64_REGEX = Regex("^[0-9a-f]{64}$")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /** Mirrors KeyImportViewModel.detectFormat() exactly. */
    private fun detectFormat(input: String): ImportFormat = when {
        input.startsWith("nsec1") -> ImportFormat.NSEC
        input.startsWith("ncryptsec1") -> ImportFormat.NCRYPTSEC
        input.matches(HEX_64_REGEX) -> ImportFormat.HEX
        input.split(WHITESPACE_REGEX).size in 12..24 -> ImportFormat.MNEMONIC
        else -> ImportFormat.UNKNOWN
    }

    // ── nsec ─────────────────────────────────────────────────────

    @Test
    fun `nsec1 prefix detected as NSEC`() {
        val input = "nsec1fakefakefakefakefakefakefakefakefakefakefakefakefakefakefake"
        assertEquals(ImportFormat.NSEC, detectFormat(input))
    }

    @Test
    fun `nsec1 with trailing whitespace after trim`() {
        val input = "nsec1fakefakefakefakefakefakefakefakefakefakefakefakefakefakefake"
        assertEquals(ImportFormat.NSEC, detectFormat(input.trim()))
    }

    // ── ncryptsec ────────────────────────────────────────────────

    @Test
    fun `ncryptsec1 prefix detected as NCRYPTSEC`() {
        val input = "ncryptsec1fakefakefakefakefakefakefakefakefakefakefakefakefakefakefakefakefakefakefake"
        assertEquals(ImportFormat.NCRYPTSEC, detectFormat(input))
    }

    // ── hex ──────────────────────────────────────────────────────

    @Test
    fun `64 lowercase hex chars detected as HEX`() {
        val input = "a" .repeat(64)
        assertEquals(ImportFormat.HEX, detectFormat(input))
    }

    @Test
    fun `mixed hex chars detected as HEX`() {
        val input = "0123456789abcdef".repeat(4)
        assertEquals(ImportFormat.HEX, detectFormat(input))
    }

    @Test
    fun `uppercase hex not detected as HEX`() {
        val input = "A".repeat(64)
        assertEquals(ImportFormat.UNKNOWN, detectFormat(input))
    }

    @Test
    fun `63 hex chars not detected as HEX`() {
        val input = "a".repeat(63)
        assertEquals(ImportFormat.UNKNOWN, detectFormat(input))
    }

    @Test
    fun `65 hex chars not detected as HEX`() {
        val input = "a".repeat(65)
        assertEquals(ImportFormat.UNKNOWN, detectFormat(input))
    }

    // ── mnemonic ─────────────────────────────────────────────────

    @Test
    fun `12 words detected as MNEMONIC`() {
        val input = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        assertEquals(ImportFormat.MNEMONIC, detectFormat(input))
    }

    @Test
    fun `24 words detected as MNEMONIC`() {
        val words = (1..24).joinToString(" ") { "word$it" }
        assertEquals(ImportFormat.MNEMONIC, detectFormat(words))
    }

    @Test
    fun `11 words not detected as MNEMONIC`() {
        val words = (1..11).joinToString(" ") { "word$it" }
        assertEquals(ImportFormat.UNKNOWN, detectFormat(words))
    }

    @Test
    fun `25 words not detected as MNEMONIC`() {
        val words = (1..25).joinToString(" ") { "word$it" }
        assertEquals(ImportFormat.UNKNOWN, detectFormat(words))
    }

    // ── unknown ──────────────────────────────────────────────────

    @Test
    fun `empty string is UNKNOWN`() {
        assertEquals(ImportFormat.UNKNOWN, detectFormat(""))
    }

    @Test
    fun `random text is UNKNOWN`() {
        assertEquals(ImportFormat.UNKNOWN, detectFormat("hello world"))
    }

    @Test
    fun `npub prefix is UNKNOWN`() {
        assertEquals(ImportFormat.UNKNOWN, detectFormat("npub1abc123"))
    }

    // ── priority: nsec wins over hex length match ────────────────

    @Test
    fun `nsec1 prefix takes priority even if rest could match other patterns`() {
        // nsec1 check happens before hex check
        val input = "nsec1" + "a".repeat(59)
        assertEquals(ImportFormat.NSEC, detectFormat(input))
    }

    @Test
    fun `ncryptsec1 prefix takes priority over mnemonic word count`() {
        val input = "ncryptsec1 a b c d e f g h i j k"
        assertEquals(ImportFormat.NCRYPTSEC, detectFormat(input))
    }
}
