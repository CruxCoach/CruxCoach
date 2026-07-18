package com.cruxcoach.android.ble

import android.Manifest
import android.os.Build
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decision table for [BlePermissionHelper.isLocationRequired] — the pure gate
 * behind the "enable location services" prompt on the BLE connection sheet.
 * The invariant under test: the prompt may only claim location is required
 * when the OS would actually withhold scan results (discovery scan, API 23-30,
 * location off); a direct GATT connect and anything on API 31+
 * (BLUETOOTH_SCAN neverForLocation) must never be gated.
 */
class BlePermissionHelperTest {

    private val legacyScanApis = listOf(
        Build.VERSION_CODES.M,
        Build.VERSION_CODES.O,
        Build.VERSION_CODES.P,
        Build.VERSION_CODES.Q,
        Build.VERSION_CODES.R,
    )
    private val apisFromS = listOf(
        Build.VERSION_CODES.S,
        Build.VERSION_CODES.S_V2,
        Build.VERSION_CODES.TIRAMISU,
        Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        Build.VERSION_CODES.VANILLA_ICE_CREAM,
    )

    @Test
    fun connection_permissions_follow_the_platform_minimum() {
        assertContentEquals(
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            BlePermissionHelper.getRequiredPermissions(Build.VERSION_CODES.O_MR1)
        )
        assertContentEquals(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            BlePermissionHelper.getRequiredPermissions(Build.VERSION_CODES.Q)
        )
        assertContentEquals(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            BlePermissionHelper.getRequiredPermissions(Build.VERSION_CODES.S)
        )
    }

    @Test
    fun advertising_is_not_part_of_board_discovery_permissions() {
        for (api in apisFromS) {
            assertFalse(
                BlePermissionHelper.getRequiredPermissions(api)
                    .contains(Manifest.permission.BLUETOOTH_ADVERTISE)
            )
        }
    }

    @Test
    fun api31_plus_never_requires_location_even_for_a_scan_with_location_off() {
        for (api in apisFromS) {
            assertFalse(
                BlePermissionHelper.isLocationRequired(
                    apiLevel = api, flowNeedsScan = true, locationEnabled = false
                ),
                "API $api must not location-gate a scan (neverForLocation)"
            )
        }
    }

    @Test
    fun scan_flow_below_api31_requires_location_exactly_while_it_is_off() {
        for (api in legacyScanApis) {
            assertTrue(
                BlePermissionHelper.isLocationRequired(
                    apiLevel = api, flowNeedsScan = true, locationEnabled = false
                ),
                "API $api must location-gate a scan while location is off"
            )
            assertFalse(
                BlePermissionHelper.isLocationRequired(
                    apiLevel = api, flowNeedsScan = true, locationEnabled = true
                ),
                "API $api must not gate a scan once location is on"
            )
        }
    }

    @Test
    fun scan_before_runtime_location_permissions_is_not_location_gated() {
        assertFalse(
            BlePermissionHelper.isLocationRequired(
                apiLevel = Build.VERSION_CODES.LOLLIPOP,
                flowNeedsScan = true,
                locationEnabled = false,
            )
        )
    }

    @Test
    fun direct_gatt_connect_flow_never_requires_location_on_any_api() {
        for (api in listOf(Build.VERSION_CODES.LOLLIPOP) + legacyScanApis + apisFromS) {
            assertFalse(
                BlePermissionHelper.isLocationRequired(
                    apiLevel = api, flowNeedsScan = false, locationEnabled = false
                ),
                "API $api must not location-gate a direct connect"
            )
        }
    }
}
