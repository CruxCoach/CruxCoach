package com.cruxcoach.android.ui.settings

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.ErrorCard
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.util.ApkShareHelper
import com.cruxcoach.android.util.LocalApkServer
import com.cruxcoach.android.util.WifiDirectHotspot
import java.io.File

@Composable
internal fun AppShareSection(
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var hotspot by remember { mutableStateOf<WifiDirectHotspot?>(null) }
    var server by remember { mutableStateOf<LocalApkServer?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var urlQrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var hotspotSsid by remember { mutableStateOf("") }
    var hotspotPassword by remember { mutableStateOf("") }
    var downloadUrl by remember { mutableStateOf("") }
    var isStarting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val startSharing: () -> Unit = {
        isStarting = true
        errorMessage = null
        try {
            val wifiHotspot = WifiDirectHotspot(context)
            wifiHotspot.start(
                onStarted = { info ->
                    try {
                        val apk = File(context.applicationInfo.sourceDir)
                        // Serve the public board DB alongside the APK (community climb data only).
                        // NEVER serve cruxcoach_secure.db or any user data.
                        val boardDb = context.getDatabasePath("cruxcoach.db")
                        val apkServer = LocalApkServer(
                            apkFile = apk,
                            boardDbFile = if (boardDb.exists()) boardDb else null
                        )
                        apkServer.onAutoShutdown = {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                server = null
                                hotspot?.stop(); hotspot = null
                                qrBitmap = null; urlQrBitmap = null
                            }
                        }
                        val port = apkServer.start(hostIp = info.ip)
                        val url = "http://${info.ip}:$port"
                        val qr = ApkShareHelper.generateWifiQrBitmap(
                            info.ssid, info.password
                        )
                        val urlQr = ApkShareHelper.generateQrBitmap(url)
                        hotspot = wifiHotspot; server = apkServer; qrBitmap = qr; urlQrBitmap = urlQr
                        hotspotSsid = info.ssid; hotspotPassword = info.password; downloadUrl = url
                        isStarting = false
                    } catch (e: Exception) {
                        Log.e("AppShare", "Server start failed", e)
                        wifiHotspot.stop()
                        errorMessage = context.getString(R.string.settings_share_server_error, e.message ?: ""); isStarting = false
                    }
                },
                onError = { err ->
                    wifiHotspot.stop()
                    errorMessage = err; isStarting = false
                }
            )
        } catch (e: Exception) {
            Log.e("AppShare", "WiFi Direct failed", e)
            errorMessage = context.getString(R.string.settings_share_error, e.message ?: ""); isStarting = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startSharing()
        } else {
            val denied = results.filter { !it.value }.keys.joinToString(", ") {
                it.substringAfterLast(".")
            }
            errorMessage = context.getString(R.string.settings_share_permissions_needed, denied)
            isStarting = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            server?.stop()
            hotspot?.stop()
        }
    }

    Text(
        stringResource(R.string.settings_share_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Text(
        stringResource(R.string.settings_share_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Share via system share sheet
    OutlinedButton(
        onClick = { ApkShareHelper.shareViaIntent(context) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent)
    ) { Text(stringResource(R.string.settings_share_via_apps)) }

    // Error
    errorMessage?.let { err ->
        ErrorCard(
            error = err,
            onDismiss = { errorMessage = null },
            onReportBug = {
                onNavigateToBugReport(
                    context.getString(R.string.error_bug_report_share_title),
                    err
                )
                errorMessage = null
            }
        )
    }

    // Active: QR code + info
    if (qrBitmap != null) {
        AppShareActiveCard(
            qrBitmap = qrBitmap!!,
            urlQrBitmap = urlQrBitmap,
            hotspotSsid = hotspotSsid,
            hotspotPassword = hotspotPassword,
            downloadUrl = downloadUrl,
            onStop = {
                server?.stop(); server = null
                hotspot?.stop(); hotspot = null
                qrBitmap = null; urlQrBitmap = null
            }
        )
    } else if (errorMessage == null) {
        OutlinedButton(
            onClick = {
                val perms = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    } else {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }
                permissionLauncher.launch(perms.toTypedArray())
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent),
            enabled = !isStarting
        ) {
            if (isStarting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = OrangeAccent)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(stringResource(R.string.settings_share_local))
        }
        Text(stringResource(R.string.settings_share_local_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppShareActiveCard(
    qrBitmap: Bitmap,
    urlQrBitmap: Bitmap?,
    hotspotSsid: String,
    hotspotPassword: String,
    downloadUrl: String,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.settings_share_mobile_data_warning),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = OrangeAccent)

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(stringResource(R.string.settings_share_step1),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, color = SuccessGreen)

            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_wifi_qr_code),
                modifier = Modifier.size(220.dp)
            )

            Text(stringResource(R.string.settings_share_network, hotspotSsid),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text(stringResource(R.string.settings_share_password, hotspotPassword),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(stringResource(R.string.settings_share_step2),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, color = SuccessGreen)

            if (urlQrBitmap != null) {
                Image(
                    bitmap = urlQrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_download_qr_code),
                    modifier = Modifier.size(180.dp)
                )
            }

            Text(downloadUrl,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = OrangeAccent)

            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.settings_share_stop)) }
        }
    }
}
