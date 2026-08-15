package com.cruxcoach.android.fips

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.cruxcoach.android.ble.BlePermissionHelper

/** Runtime permissions needed by the symmetric FIPS BLE/L2CAP radio. */
internal object FipsPermissionPolicy {
    fun requiredPermissions(apiLevel: Int = Build.VERSION.SDK_INT): List<String> {
        if (apiLevel < Build.VERSION_CODES.Q) return emptyList()
        return (BlePermissionHelper.getRequiredPermissions(apiLevel).asSequence() +
            BlePermissionHelper.getAdvertisingPermissions(apiLevel).asSequence())
            .distinct()
            .toList()
    }

    fun missingPermissions(
        context: Context,
        apiLevel: Int = Build.VERSION.SDK_INT,
    ): List<String> = requiredPermissions(apiLevel).filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}
