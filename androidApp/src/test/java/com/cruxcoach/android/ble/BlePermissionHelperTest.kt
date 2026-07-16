package com.cruxcoach.android.ble

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class BlePermissionHelperTest {

    private val application: Application
        get() = RuntimeEnvironment.getApplication()

    @Test
    @Config(sdk = [31], application = Application::class)
    fun `android 12 requires scan connect and advertise`() {
        assertArrayEquals(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ),
            BlePermissionHelper.getRequiredPermissions(),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE),
            BlePermissionHelper.getAdvertisingPermissions(),
        )

        assertFalse(BlePermissionHelper.hasPermissions(application))
        shadowOf(application).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        assertTrue(BlePermissionHelper.hasPermissions(application))
        assertTrue(BlePermissionHelper.hasAdvertisingPermission(application))
        assertTrue(BlePermissionHelper.isLocationEnabledForBle(application))
    }

    @Test
    @Config(sdk = [30], application = Application::class)
    fun `android 11 requires fine location and enabled location service`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            BlePermissionHelper.getRequiredPermissions(),
        )
        assertTrue(BlePermissionHelper.getAdvertisingPermissions().isEmpty())
        assertTrue(BlePermissionHelper.hasAdvertisingPermission(application))

        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(BlePermissionHelper.hasPermissions(application))

        val manager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowManager = shadowOf(manager)
        shadowManager.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowManager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        assertFalse(BlePermissionHelper.isLocationEnabledForBle(application))

        shadowManager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        assertTrue(BlePermissionHelper.isLocationEnabledForBle(application))
    }
}
