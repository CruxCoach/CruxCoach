package com.cruxcoach.android.fips

import android.Manifest
import android.os.Build
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FipsPermissionPolicyTest {
    @Test
    fun `api 28 uses the established GATT fallback and requests no FIPS permissions`() {
        assertTrue(FipsPermissionPolicy.requiredPermissions(Build.VERSION_CODES.P).isEmpty())
    }

    @Test
    fun `api 29 and 30 request fine location for BLE discovery`() {
        val expected = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(expected, FipsPermissionPolicy.requiredPermissions(Build.VERSION_CODES.Q))
        assertEquals(expected, FipsPermissionPolicy.requiredPermissions(Build.VERSION_CODES.R))
    }

    @Test
    fun `api 31 plus requests symmetric scan connect and advertise permissions`() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ),
            FipsPermissionPolicy.requiredPermissions(Build.VERSION_CODES.S),
        )
    }
}
