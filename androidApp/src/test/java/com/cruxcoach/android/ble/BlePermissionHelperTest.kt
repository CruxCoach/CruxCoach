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
    fun direct_connection_permissions_do_not_include_legacy_location() {
        assertContentEquals(
            emptyArray<String>(),
            BlePermissionHelper.getConnectionPermissions(Build.VERSION_CODES.Q)
        )
        assertContentEquals(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            BlePermissionHelper.getConnectionPermissions(Build.VERSION_CODES.S)
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
    fun session_hosting_requests_advertise_and_connect_but_never_scan_or_location() {
        assertContentEquals(
            emptyArray<String>(),
            BlePermissionHelper.getSessionHostingPermissions(Build.VERSION_CODES.R),
        )
        assertContentEquals(
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            BlePermissionHelper.getSessionHostingPermissions(Build.VERSION_CODES.S),
        )
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

/**
 * Guard for [BlePermissionHelper.canRequestBluetoothEnable].
 *
 * Regression cover for a crash on the BLE sheet: with Bluetooth off and no
 * permissions yet, the sheet fired ACTION_REQUEST_ENABLE straight away. From
 * API 31 that intent is itself BLUETOOTH_CONNECT-protected, so the platform
 * answered with a SecurityException and the app died on the spot — reproduced
 * on Android 15 (API 35).
 */
class BluetoothEnableGateTest {

    @Test
    fun `api 31 and above needs the connect permission first`() {
        assertFalse(
            BlePermissionHelper.canRequestBluetoothEnable(
                hasConnectionPermission = false,
                apiLevel = Build.VERSION_CODES.S,
            )
        )
        assertTrue(
            BlePermissionHelper.canRequestBluetoothEnable(
                hasConnectionPermission = true,
                apiLevel = Build.VERSION_CODES.S,
            )
        )
    }

    @Test
    fun `below api 31 the intent needs no runtime permission`() {
        assertTrue(
            BlePermissionHelper.canRequestBluetoothEnable(
                hasConnectionPermission = false,
                apiLevel = Build.VERSION_CODES.R,
            )
        )
    }

    @Test
    fun `current android release still requires the permission`() {
        assertFalse(
            BlePermissionHelper.canRequestBluetoothEnable(
                hasConnectionPermission = false,
                apiLevel = 35,
            ),
            "API 35 is where this crashed in the field",
        )
    }
}

/**
 * Decision table for the reconnect permission set.
 *
 * Background: reconnecting to a known controller connects by address and
 * needs no scan, and a controller's capacity no longer depends on scanning
 * either — unprobed means exclusive, which is what real boards are. The
 * advertising probe can only upgrade that, so it is run when scan rights
 * happen to be there and never asked for.
 */
class ReconnectPermissionsTest {

    @Test
    fun `reconnect asks for the connect permission only`() {
        assertContentEquals(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            BlePermissionHelper.getReconnectPermissions(apiLevel = Build.VERSION_CODES.S),
        )
    }

    @Test
    fun `the probe runs whenever scanning would work`() {
        assertTrue(
            BlePermissionHelper.wantsCapacityProbe(
                capacityKnown = false,
                hasScanPermission = true,
                apiLevel = Build.VERSION_CODES.S,
            )
        )
        // Legacy Android too — a granted location permission plus location
        // services means scan results flow, so the capacity can be established
        // there just as well.
        assertTrue(
            BlePermissionHelper.wantsCapacityProbe(
                capacityKnown = false,
                hasScanPermission = true,
                locationEnabled = true,
                apiLevel = Build.VERSION_CODES.R,
            )
        )
        assertFalse(
            BlePermissionHelper.wantsCapacityProbe(
                capacityKnown = false,
                hasScanPermission = false,
                apiLevel = Build.VERSION_CODES.S,
            ),
            "a probe must never be the reason to request a permission",
        )
        assertFalse(
            BlePermissionHelper.wantsCapacityProbe(
                capacityKnown = false,
                hasScanPermission = true,
                locationEnabled = false,
                apiLevel = Build.VERSION_CODES.R,
            ),
            "API 30 withholds scan results while location services are off",
        )
    }

    @Test
    fun `an established capacity is not probed again`() {
        assertFalse(
            BlePermissionHelper.wantsCapacityProbe(
                capacityKnown = true,
                hasScanPermission = true,
                apiLevel = Build.VERSION_CODES.S,
            )
        )
    }

    /** The whole point of the split: connecting must never drag in location. */
    @Test
    fun `legacy reconnect never asks for location`() {
        for (api in listOf(Build.VERSION_CODES.M, Build.VERSION_CODES.Q, Build.VERSION_CODES.R)) {
            val perms = BlePermissionHelper.getReconnectPermissions(apiLevel = api)
            assertFalse(
                perms.contains(Manifest.permission.ACCESS_FINE_LOCATION),
                "API $api reconnect must not require location",
            )
            assertFalse(
                perms.contains(Manifest.permission.ACCESS_COARSE_LOCATION),
                "API $api reconnect must not require location",
            )
        }
    }
}
