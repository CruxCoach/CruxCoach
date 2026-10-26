package com.cruxcoach.android.ui.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the screen draws, the camera path reads back: a grey frame with the code in it. */
class QrCodesTest {
    private fun frame(text: String, size: Int, pad: Int): Triple<ByteArray, Int, Int> {
        val matrix = QrCodes.encode(text, size)
        val width = size + 2 * pad
        // A grey camera frame: light background, dark modules, rows wider than the picture.
        val stride = width + 16
        val luma = ByteArray(stride * width) { 200.toByte() }
        for (y in 0 until size) for (x in 0 until size) {
            luma[(y + pad) * stride + x + pad] = if (matrix[x, y]) 30 else 220.toByte()
        }
        return Triple(luma, stride, width)
    }

    @Test fun a_contact_code_survives_drawing_and_reading() {
        val code = "cruxcoach:c1:" + "Y2NjMQ" + "B".repeat(85)
        val (luma, stride, width) = frame(code, 400, 60)
        assertEquals(code, QrCodes.decode(luma, stride, width, width))
    }

    @Test fun a_frame_without_a_code_reads_as_nothing() {
        val luma = ByteArray(320 * 240) { (it % 251).toByte() }
        assertNull(QrCodes.decode(luma, 320, 320, 240))
    }
}
