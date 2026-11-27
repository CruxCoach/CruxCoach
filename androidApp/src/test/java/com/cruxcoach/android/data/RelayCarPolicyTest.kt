package com.cruxcoach.android.data

import android.bluetooth.BluetoothClass.Device
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCarPolicyTest {

    @Test
    fun `car kits end sharing`() {
        assertTrue(RelayCarPolicy.isCar(Device.AUDIO_VIDEO_CAR_AUDIO))
        assertTrue(RelayCarPolicy.isCar(Device.AUDIO_VIDEO_HANDSFREE))
    }

    @Test
    fun `headphones, headsets and speakers do not`() {
        for (deviceClass in listOf(
            Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            Device.AUDIO_VIDEO_HEADPHONES,
            Device.AUDIO_VIDEO_LOUDSPEAKER,
            Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            Device.AUDIO_VIDEO_HIFI_AUDIO,
            Device.AUDIO_VIDEO_UNCATEGORIZED,
            Device.WEARABLE_WRIST_WATCH,
            Device.PHONE_SMART,
            Device.COMPUTER_LAPTOP,
        )) {
            assertFalse("class $deviceClass", RelayCarPolicy.isCar(deviceClass))
        }
    }

    @Test
    fun `an unknown class changes nothing`() {
        assertFalse(RelayCarPolicy.isCar(null))
        assertFalse(RelayCarPolicy.isCar(0))
        assertFalse(RelayCarPolicy.isCar(0x1F00))
    }
}
