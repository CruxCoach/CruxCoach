package com.cruxcoach.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Checks whether the device has an active internet connection.
 * Returns false if no network is available or the INTERNET permission is not granted.
 */
fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Whether THIS APP may use the network at all.
 *
 * [isNetworkAvailable] answers a different question — it reports the device's
 * connectivity, and keeps saying "yes" while our own sockets are refused. On
 * GrapheneOS the INTERNET permission is revocable per app, and with it revoked
 * the sync passes every connectivity check, starts, and fails on every
 * download with nothing to point at. On stock Android INTERNET is granted at
 * install time and this is always true, so the check costs nothing there.
 */
fun isNetworkPermissionGranted(context: Context): Boolean =
    context.checkSelfPermission(android.Manifest.permission.INTERNET) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * Checks whether the device is connected via WiFi (not metered / mobile data).
 */
fun isWifiConnected(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
