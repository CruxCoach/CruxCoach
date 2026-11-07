package com.cruxcoach.android.ui.boardscan

import android.view.Surface
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ScreenAxesTest {

    @Test
    fun aPhoneAtItsNaturalOrientationKeepsItsAxes() {
        assertContentEquals(floatArrayOf(0.1f, 9.7f, 1.2f), toScreenAxes(floatArrayOf(0.1f, 9.7f, 1.2f), Surface.ROTATION_0))
    }

    @Test
    fun upOnTheScreenStaysUpWhateverTheDevicesNaturalOrientation() {
        // turned a quarter counter-clockwise from its natural orientation, the device's x axis points up
        assertContentEquals(floatArrayOf(-0.0f, 9.8f, 0.5f), toScreenAxes(floatArrayOf(9.8f, 0.0f, 0.5f), Surface.ROTATION_90))
        // a quarter clockwise: its x axis points down
        assertContentEquals(floatArrayOf(0.0f, 9.8f, 0.5f), toScreenAxes(floatArrayOf(-9.8f, 0.0f, 0.5f), Surface.ROTATION_270))
        // upside down: its y axis points down
        assertContentEquals(floatArrayOf(-0.0f, 9.8f, 0.5f), toScreenAxes(floatArrayOf(0.0f, -9.8f, 0.5f), Surface.ROTATION_180))
    }
}
