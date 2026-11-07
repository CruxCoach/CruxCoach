package com.cruxcoach.android.ui.boardscan

import kotlin.test.Test
import kotlin.test.assertContentEquals

class RotateRgbaTest {

    // a 3 x 2 RGBA frame with one padding byte per row; pixel value = its red channel
    private val w = 3
    private val h = 2
    private val stride = w * 4 + 1
    private val bytes = ByteArray(stride * h).also { b ->
        for (y in 0 until h) for (x in 0 until w) {
            val o = y * stride + x * 4
            b[o] = (10 * y + x).toByte(); b[o + 3] = 0xff.toByte()
        }
    }

    private fun reds(a: IntArray) = a.map { (it shr 16) and 0xff }

    @Test
    fun uprightFramesKeepTheirOrder() {
        assertContentEquals(listOf(0, 1, 2, 10, 11, 12), reds(rotateRgba(bytes, w, h, stride, 4, 0)))
    }

    @Test
    fun aSensorFrameTurnsClockwiseForAPortraitPhone() {
        // 90 degrees clockwise: the left column, read bottom-up, becomes the top row
        assertContentEquals(listOf(10, 0, 11, 1, 12, 2), reds(rotateRgba(bytes, w, h, stride, 4, 90)))
    }

    @Test
    fun otherTurns() {
        assertContentEquals(listOf(12, 11, 10, 2, 1, 0), reds(rotateRgba(bytes, w, h, stride, 4, 180)))
        assertContentEquals(listOf(2, 12, 1, 11, 0, 10), reds(rotateRgba(bytes, w, h, stride, 4, 270)))
    }
}
