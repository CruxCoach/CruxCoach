package com.cruxcoach.android.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When the pin follows a key rotation, and when it must refuse to.
 *
 * Without the advance, a device that has installed the rotated release
 * would keep pinning the superseded certificate and would therefore keep
 * accepting APKs signed with it — Android refuses to install those, so it
 * is not a compromise, but this gate would have stopped contributing and
 * the user would see a repeating, unexplained install failure instead of a
 * clean rejection.
 *
 * The dangerous direction is the other one, so the refusals below matter
 * more than the accept: a pin that advanced onto an unrelated certificate
 * would stop being a check at all.
 */
class UpdaterPinAdvanceTest {

    private val original = "a".repeat(64)
    private val rotated = "b".repeat(64)
    private val second = "c".repeat(64)
    private val stranger = "d".repeat(64)

    @Test
    fun `pin follows the rotation onto the new signer`() {
        assertEquals(
            rotated,
            UpdaterPinStore.nextPinAfterRotation(original, listOf(original, rotated)),
        )
    }

    @Test
    fun `pin follows a second rotation from wherever it currently sits`() {
        assertEquals(
            second,
            UpdaterPinStore.nextPinAfterRotation(rotated, listOf(original, rotated, second)),
        )
    }

    @Test
    fun `a never-rotated app leaves the pin alone`() {
        assertNull(UpdaterPinStore.nextPinAfterRotation(original, listOf(original)))
    }

    @Test
    fun `an already-current pin is not rewritten`() {
        // Avoids a pointless Keystore-backed write on every single start-up.
        assertNull(UpdaterPinStore.nextPinAfterRotation(rotated, listOf(original, rotated)))
    }

    @Test
    fun `a pin absent from the chain never advances`() {
        // The load-bearing refusal: advancing here would let any installed
        // signature redefine what the pin means.
        assertNull(UpdaterPinStore.nextPinAfterRotation(stranger, listOf(original, rotated)))
    }

    @Test
    fun `no pin and no chain are both refusals, never a silent adopt`() {
        assertNull(UpdaterPinStore.nextPinAfterRotation(null, listOf(original, rotated)))
        assertNull(UpdaterPinStore.nextPinAfterRotation("", listOf(original, rotated)))
        assertNull(UpdaterPinStore.nextPinAfterRotation(original, emptyList()))
    }

    @Test
    fun `casing differences across extraction paths do not force a rewrite`() {
        assertNull(
            UpdaterPinStore.nextPinAfterRotation(
                rotated.uppercase(),
                listOf(original, rotated),
            ),
        )
        assertEquals(
            rotated,
            UpdaterPinStore.nextPinAfterRotation(
                original.uppercase(),
                listOf(original, rotated),
            ),
        )
    }

    @Test
    fun `after the advance an old-key APK no longer passes the cert gate`() {
        // The two halves have to agree: this is the end-to-end property the
        // advance exists for, expressed against the real matcher.
        val advanced = UpdaterPinStore.nextPinAfterRotation(original, listOf(original, rotated))!!
        assertEquals(
            false,
            IntegrityVerifier.pinMatchesSigningHistory(advanced, listOf(original)),
        )
        assertEquals(
            true,
            IntegrityVerifier.pinMatchesSigningHistory(advanced, listOf(original, rotated)),
        )
    }
}
