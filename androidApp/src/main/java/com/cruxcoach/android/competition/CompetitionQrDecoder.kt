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
     * Repack a camera plane into tightly-packed luminance.
     *
     * This is the part that a synthetic test will not tell you about. CameraX
     * hands back a `PlaneProxy` whose rows may be padded — `rowStride` can
     * exceed the width, and `pixelStride` can exceed one when the Y plane is
     * interleaved — and a decoder that assumes `width * height` reads the
     * padding as image data. The synthetic buffers in a JVM test are always
     * tightly packed, so the bug only appears on a real phone, as "it just
     * does not scan".
     *
     * The crop rectangle matters for the same reason: the analyser may be
     * handed a buffer larger than the region the camera actually considers
     * valid, and decoding the whole thing means decoding a border of noise.
     *
     * @param plane the raw plane bytes, exactly as read from the buffer
     * @param rowStride bytes from the start of one row to the start of the next
     * @param pixelStride bytes from one pixel to the next within a row
     * @return a `width * height` buffer, or null when the plane is too small to
     *   hold what it claims — refused rather than read out of bounds.
     */
    fun packLuminance(
        plane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        left: Int = 0,
        top: Int = 0,
    ): ByteArray? {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return null
        if (left < 0 || top < 0) return null

        // The last byte this would read, checked before reading any of it.
        val lastRow = top + height - 1
        val lastPixel = left + width - 1
        val lastIndex = lastRow.toLong() * rowStride + lastPixel.toLong() * pixelStride
        if (lastIndex >= plane.size) return null

        // The common case is already what we want, and copying it row by row
        // would be a measurable cost on every frame.
        if (rowStride == width && pixelStride == 1 && left == 0 && top == 0 &&
            plane.size == width * height
        ) {
            return plane
        }

        val packed = ByteArray(width * height)
        var out = 0
        for (y in 0 until height) {
            var index = (top + y) * rowStride + left * pixelStride
            if (pixelStride == 1) {
                // Contiguous within the row: one copy per row rather than per pixel.
                System.arraycopy(plane, index, packed, out, width)
                out += width
            } else {
                for (x in 0 until width) {
                    packed[out++] = plane[index]
                    index += pixelStride
                }
            }
        }
        return packed
    }

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
