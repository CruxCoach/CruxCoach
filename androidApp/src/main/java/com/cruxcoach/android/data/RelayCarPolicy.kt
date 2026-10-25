package com.cruxcoach.android.data

import android.bluetooth.BluetoothClass

/**
 * Sharing a board ends when the phone connects to a car: whoever sits in the
 * car is not at the board, and the car would show the sharing name. Only car
 * kits count (car audio, hands-free); headphones, headsets and speakers
 * deliberately do not, and an unknown class changes nothing.
 */
internal object RelayCarPolicy {
    /** @param deviceClass [BluetoothClass.getDeviceClass], null when unknown. */
    fun isCar(deviceClass: Int?): Boolean =
        deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
}
