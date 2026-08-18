package com.cruxcoach.android.updater

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The certificate gate's decision rule, in isolation from PackageManager.
 *
 * This is the rule that decides whether a v3 key rotation is installable at
 * all: the pinned certificate has to appear somewhere in the history the APK
 * proved, not just as its current signer. Getting it wrong in either
 * direction is severe — too strict and every install refuses the rotated
 * release, too loose and the pin stops meaning anything — so it is pinned
 * down here rather than left to the Android plumbing that surrounds it.
 */
class IntegrityVerifierLineageTest {

    private val original = "a".repeat(64)
    private val rotated = "b".repeat(64)
    private val stranger = "c".repeat(64)

    @Test
    fun `un-rotated release matches its single certificate`() {
        assertTrue(
            IntegrityVerifier.pinMatchesSigningHistory(original, listOf(original)),
        )
    }

    @Test
    fun `rotated release is accepted because the pin is an ancestor`() {
        // The v3 lineage is ordered oldest-first, so the pinned original sits
        // at the front and the new signer at the back. This is the case that
        // makes rotation possible at all.
        assertTrue(
            IntegrityVerifier.pinMatchesSigningHistory(original, listOf(original, rotated)),
        )
    }

    @Test
    fun `pin is accepted wherever it sits in a longer lineage`() {
        // Second rotation: the anchor is now two links back.
        assertTrue(
            IntegrityVerifier.pinMatchesSigningHistory(
                original,
                listOf(original, rotated, "d".repeat(64)),
            ),
        )
    }

    @Test
    fun `an install already on the rotated key still matches`() {
        // A device that first installed after the rotation pins the new key;
        // subsequent releases carry the same lineage.
        assertTrue(
            IntegrityVerifier.pinMatchesSigningHistory(rotated, listOf(original, rotated)),
        )
    }

    @Test
    fun `a lineage that does not contain the pin is rejected`() {
        // Someone else's rotation chain. This is the attack the pin exists for.
        assertFalse(
            IntegrityVerifier.pinMatchesSigningHistory(original, listOf(stranger, rotated)),
        )
    }

    @Test
    fun `an unsigned or unreadable APK is rejected, never treated as a match`() {
        assertFalse(IntegrityVerifier.pinMatchesSigningHistory(original, emptyList()))
    }

    @Test
    fun `a blank pin never matches anything`() {
        assertFalse(IntegrityVerifier.pinMatchesSigningHistory("", listOf(original)))
        assertFalse(IntegrityVerifier.pinMatchesSigningHistory("", emptyList()))
    }

    @Test
    fun `comparison is case-insensitive across extraction paths`() {
        // The three extraction paths do not agree on hex casing.
        assertTrue(
            IntegrityVerifier.pinMatchesSigningHistory(
                original.uppercase(),
                listOf(rotated, original),
            ),
        )
    }

    @Test
    fun `a truncated or padded hash never matches`() {
        assertFalse(
            IntegrityVerifier.pinMatchesSigningHistory(original, listOf(original.dropLast(1))),
        )
        assertFalse(
            IntegrityVerifier.pinMatchesSigningHistory(original, listOf(original + "0")),
        )
    }
}
