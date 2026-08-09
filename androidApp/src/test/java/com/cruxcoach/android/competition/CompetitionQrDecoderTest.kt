package com.cruxcoach.android.competition

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Scanning a competition code, without a camera.
 *
 * The camera hands the decoder a plain luminance buffer, so the interesting
 * half of the scanner is testable on the JVM: encode a QR, render it to
 * luminance the way a sensor would, and decode it back. What that pins is the
 * part that goes wrong silently — a code that scans but is not ours, or one
 * that is ours and gets rejected.
 */
class CompetitionQrDecoderTest {

    private val decoder = CompetitionQrDecoder()

    /** A QR rendered as one byte per pixel, black 0 and white 255, like a sensor. */
    private fun luminance(text: String, size: Int = 240, quiet: Int = 4): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to quiet,
            ),
        )
        val bytes = ByteArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                bytes[y * matrix.width + x] = if (matrix.get(x, y)) 0 else 255.toByte()
            }
        }
        return Triple(bytes, matrix.width, matrix.height)
    }

    private val naddr = CompetitionShareLink.naddr(
        organizerPubkey = "2014dc3b1e6ca37888d3b4620fd4f23f1d8e5440dfbe51121cf787ad63b15004",
        compId = "bb00cc11dd22ee33",
    )

    @Test
    fun `the code this app generates is the code this app reads`() {
        val link = CompetitionShareLink.httpsLink(naddr, "cruxcoach.org")
        val (bytes, width, height) = luminance(link)
        assertEquals(link, decoder.decode(bytes, width, height))
    }

    @Test
    fun `a frame with no code at all is not an error`() {
        // Most frames look like this, and treating them as failures would put a
        // message on screen thirty times a second.
        val blank = ByteArray(240 * 240) { 255.toByte() }
        assertNull(decoder.decode(blank, 240, 240))
    }

    @Test
    fun `a frame smaller than it claims is refused rather than read out of bounds`() {
        assertNull(decoder.decode(ByteArray(10), 240, 240))
        assertNull(decoder.decode(ByteArray(100), 0, 0))
    }

    @Test
    fun `a rotated frame still decodes`() {
        val link = CompetitionShareLink.httpsLink(naddr, "cruxcoach.org")
        val (bytes, width, height) = luminance(link)
        assertEquals(link, decoder.decode(bytes, width, height, rotate = true))
    }

    @Test
    fun `a scanned competition link opens that competition`() {
        val link = CompetitionShareLink.httpsLink(naddr, "cruxcoach.org")
        val (bytes, width, height) = luminance(link)
        val scan = CompetitionShareLink.classify(decoder.decode(bytes, width, height))
        val competition = assertIs<CompetitionShareLink.Scan.Competition>(scan)
        assertEquals("bb00cc11dd22ee33", competition.ref.compId)
        assertEquals(naddr, competition.ref.naddr)
    }

    @Test
    fun `every other code someone might point the camera at is named`() {
        // A person standing at a wall can act on "that is a climb code". They
        // cannot act on "that did not work".
        val cases = listOf(
            "https://cruxcoach.org/c/a1c93f57-6e28-4b04-9d75-2f8a1e63c0b9"
                to CompetitionShareLink.Scan.Climb,
            "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqshp52w2"
                to CompetitionShareLink.Scan.OtherNostr,
            "https://cruxcoach.org/comp/naddr1notrealatall"
                to CompetitionShareLink.Scan.Damaged,
            "WIFI:S=GymGuest;T=WPA;P=chalkdust;;"
                to CompetitionShareLink.Scan.Unknown,
            "https://example.org/menu"
                to CompetitionShareLink.Scan.Unknown,
            "" to CompetitionShareLink.Scan.Unknown,
        )
        for ((text, expected) in cases) {
            assertEquals(expected, CompetitionShareLink.classify(text), text)
        }
    }

    @Test
    fun `a naddr for something that is not a competition is refused`() {
        // A QR from any Nostr client can carry an naddr. Only ours addresses a
        // competition, and the kind is what says so.
        val someoneElse = "naddr1qqxnzd3exqmrzv3exgmr2wfeqgsrf9j0zjaxcwaqvks99yq3lz9qmhq3g4jscl2wglaqvux5lstllnkhqrqsqqqa28r0jt8x"
        assertIs<CompetitionShareLink.Scan.Damaged>(
            CompetitionShareLink.classify(someoneElse),
            "an naddr that is not a competition must not open one",
        )
        assertNull(CompetitionShareLink.parse(someoneElse))
    }
}
