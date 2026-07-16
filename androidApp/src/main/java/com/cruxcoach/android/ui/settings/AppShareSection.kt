package com.cruxcoach.android.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
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
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.ErrorCard
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.util.ApkShareHelper
import com.cruxcoach.android.util.LocalApkServer
import com.cruxcoach.android.util.WifiDirectHotspot
import java.io.File

/**
 * Copies [text] to the system clipboard under [label] and shows a short
 * confirmation toast. Android 13+ shows its own system-level clipboard
 * confirmation too; the toast on top is harmless duplication but keeps
 * the UX consistent across Android versions.
 */
private fun copyToClipboard(context: Context, label: String, text: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

/** Trailing-icon copy button — Material outlined ContentCopy, Orange-tinted. */
@Composable
private fun CopyIconButton(onClick: () -> Unit, contentDescription: String) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = contentDescription,
            tint = OrangeAccent,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Full-width "URL + copy icon" row. Long URLs used to push the trailing
 * [CopyIconButton] out of the viewport in a naked [Row] (no weight on
 * the Text, no fillMaxWidth on the Row). Wrapping both into this helper
 * keeps the icon visible, makes the whole row clickable so the user
 * doesn't have to target the 36 dp icon, and shares a single path to
 * the clipboard + confirmation toast.
 */
@Composable
private fun CopyableUrlRow(
    url: String,
    clipLabel: String,
    toastMessage: String,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    val context = LocalContext.current
    val onCopy = {
        copyToClipboard(
            context = context,
            label = clipLabel,
            text = url,
            toastMessage = toastMessage,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            url,
            modifier = Modifier.weight(1f),
            style = textStyle,
            color = textColor,
            fontWeight = fontWeight,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        CopyIconButton(
            onClick = onCopy,
            contentDescription = stringResource(R.string.action_copy),
        )
    }
}

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
    var showReleaseQr by remember { mutableStateOf(false) }
    var showZapstoreQr by remember { mutableStateOf(false) }

    val releaseApkUrl = remember {
        val tag = "v${BuildConfig.VERSION_NAME}"
        // Derive web host from the fork-configurable updater API base
        // ("https://codeberg.org/api/v1" → "https://codeberg.org").
        val apiBase = BuildConfig.UPDATER_API_BASE
        val webHost = apiBase.substringBefore("/api/", apiBase)
        val owner = BuildConfig.UPDATER_REPO_OWNER
        val repo = BuildConfig.UPDATER_REPO_NAME
        "$webHost/$owner/$repo/releases/download/$tag/$repo-$tag.apk"
    }
    val releaseQrBitmap = remember {
        runCatching { ApkShareHelper.generateQrBitmap(releaseApkUrl) }.getOrNull()
    }

    // Zapstore public app page. Fork-configurable via local.properties
    // (BuildConfig.ZAPSTORE_APP_URL), same mechanism as the UPDATER_*
    // constants — a fork pointing at its own repo almost certainly wants
    // its own Zapstore listing as well.
    val zapstoreAppUrl = remember { BuildConfig.ZAPSTORE_APP_URL }
    val zapstoreQrBitmap = remember {
        runCatching { ApkShareHelper.generateQrBitmap(zapstoreAppUrl) }.getOrNull()
    }

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
                            boardDbFile = if (boardDb.exists()) boardDb else null,
                            // Serve a checkpointed snapshot, not the live WAL
                            // file — see LocalApkServer.boardDbSnapshot.
                            snapshotDir = context.cacheDir,
                            licenseText = runCatching {
                                context.assets.open("licenses/CruxCoach-GPL-3.0-only.txt")
                                    .use { it.readBytes() }
                            }.getOrNull(),
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

    // 1. Online: release page download QR
    OutlinedButton(
        onClick = { showReleaseQr = !showReleaseQr },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent)
    ) {
        Text(stringResource(
            if (showReleaseQr) R.string.settings_share_online_hide
            else R.string.settings_share_online
        ))
    }
    Text(
        stringResource(R.string.settings_share_online_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (showReleaseQr && releaseQrBitmap != null) {
        ReleaseDownloadCard(
            qrBitmap = releaseQrBitmap,
            downloadUrl = releaseApkUrl
        )
    }

    // 2. Offline: WiFi Direct hotspot
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

    // 3. Zapstore: share the Zapstore app-listing URL as QR + link. The
    //    recipient scans / taps and lands on zapstore.dev/apps/... — if
    //    Zapstore is installed with the matching intent filter it opens
    //    there directly; otherwise the browser shows the install guide.
    OutlinedButton(
        onClick = { showZapstoreQr = !showZapstoreQr },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent),
    ) {
        Text(
            stringResource(
                if (showZapstoreQr) R.string.settings_share_zapstore_hide
                else R.string.settings_share_zapstore,
            ),
        )
    }
    Text(
        stringResource(R.string.settings_share_zapstore_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (showZapstoreQr && zapstoreQrBitmap != null) {
        ZapstoreShareCard(
            qrBitmap = zapstoreQrBitmap,
            zapstoreUrl = zapstoreAppUrl,
        )
    }

    // 4. Via apps (system share sheet)
    OutlinedButton(
        onClick = { ApkShareHelper.shareViaIntent(context) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent)
    ) { Text(stringResource(R.string.settings_share_via_apps)) }
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

            val context = LocalContext.current
            Text(
                stringResource(R.string.settings_share_network, hotspotSsid),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_share_password, hotspotPassword),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                CopyIconButton(
                    onClick = {
                        copyToClipboard(
                            context = context,
                            label = "${BuildConfig.APP_DISPLAY_NAME} WiFi password",
                            text = hotspotPassword,
                            toastMessage = context.getString(R.string.settings_share_copied_password),
                        )
                    },
                    contentDescription = stringResource(R.string.action_copy),
                )
            }

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

            CopyableUrlRow(
                url = downloadUrl,
                clipLabel = "${BuildConfig.APP_DISPLAY_NAME} download URL",
                toastMessage = stringResource(R.string.settings_share_copied_url),
                textStyle = MaterialTheme.typography.titleMedium,
                textColor = OrangeAccent,
                fontWeight = FontWeight.Bold,
            )

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

@Composable
private fun ZapstoreShareCard(
    qrBitmap: Bitmap,
    zapstoreUrl: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = OrangeAccent.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.settings_share_zapstore_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_zapstore_share_qr),
                modifier = Modifier.size(220.dp),
            )
            CopyableUrlRow(
                url = zapstoreUrl,
                clipLabel = "${BuildConfig.APP_DISPLAY_NAME} Zapstore URL",
                toastMessage = stringResource(R.string.settings_share_copied_url),
            )
        }
    }
}

@Composable
private fun ReleaseDownloadCard(
    qrBitmap: Bitmap,
    downloadUrl: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = OrangeAccent.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.settings_share_online_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_release_download_qr),
                modifier = Modifier.size(220.dp),
            )
            CopyableUrlRow(
                url = downloadUrl,
                clipLabel = "${BuildConfig.APP_DISPLAY_NAME} release URL",
                toastMessage = stringResource(R.string.settings_share_copied_url),
            )
        }
    }
}
