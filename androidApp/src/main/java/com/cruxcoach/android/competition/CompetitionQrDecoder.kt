package com.cruxcoach.android.competition

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QR decoding for the competition scanner.
 *
 * ZXing rather than ML Kit: ML Kit needs Google Play services, and CruxCoach has
 * to work on a phone that has none. ZXing's core is already a dependency here
 * for generating codes, so this adds no new supply chain.
 *
 * Deliberately separate from anything Android: the whole decode path is a
 * function from luminance bytes to a string, which means it can be tested on
 * the JVM against the same QR the app generates.
 */
@Singleton
class CompetitionQrDecoder @Inject constructor() {

    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    /**
     * Decode one frame.
     *
     * @param luminance Y plane, one byte per pixel, row-major
     * @return the decoded text, or null when this frame holds no QR — which is
     *   most of them, and is not an error.
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, rotate: Boolean = false): String? {
        if (width <= 0 || height <= 0 || luminance.size < width * height) return null
        val (data, w, h) = if (rotate) rotate90(luminance, width, height) else Triple(luminance, width, height)
        val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            // A checksum failure or a half-visible code is an ordinary frame,
            // not something to report: the next frame is 30ms away.
            null
        } finally {
            reader.reset()
        }
    }

    /**
     * Turn the frame a quarter turn.
     *
     * The camera hands back its sensor orientation, which on most phones held
     * upright is landscape. A QR is square, so this only matters for codes that
     * are partly out of frame — but that is exactly the case where somebody is
     * standing there wondering why it will not scan.
     */
    private fun rotate90(source: ByteArray, width: Int, height: Int): Triple<ByteArray, Int, Int> {
        val rotated = ByteArray(width * height)
        var index = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                rotated[index++] = source[y * width + x]
            }
        }
        return Triple(rotated, height, width)
    }
}
