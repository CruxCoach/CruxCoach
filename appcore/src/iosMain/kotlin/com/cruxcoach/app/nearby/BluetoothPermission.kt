package com.cruxcoach.app.nearby

import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways

/**
 * The user already allowed Bluetooth. Reading the class property shows no prompt (creating a
 * manager would), so the board screens can ask before they scan for nearby phones.
 */
internal fun bluetoothAlreadyAllowed(): Boolean = CBManager.authorization == CBManagerAuthorizationAllowedAlways
