package com.cruxcoach.android.updater

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cruxcoach.android.MainActivity
import com.cruxcoach.android.R
import com.cruxcoach.android.notification.AppNotificationService

/**
 * Single-notification surface for the updater (§5.2, §6.10, §6.14).
 *
 * One channel, one id — every call to `show*()` replaces in-place so the
 * user only ever sees one active update notification. Content states:
 * `PENDING_DOWNLOAD → DOWNLOADING → READY_TO_INSTALL`, plus the terminal
 * error / cert-mismatch surfaces.
 *
 * Tap routes to the Settings screen. The notification is intentionally
 * NOT the entry point to a separate "release notes" route — Settings
 * holds the same primary action inline, so one visual anchor stays
 * consistent across the two surfaces.
 */
class UpdateNotifier(private val context: Context) {

    private val manager: NotificationManagerCompat = NotificationManagerCompat.from(context)

    fun showPendingDownload(info: UpdateInfo) {
        // No inline Download action: tapping the notification opens
        // Settings and auto-triggers the download-confirm dialog — the
        // user still gets one explicit confirmation before any bytes fly.
        val builder = base(info)
            .setContentTitle(context.getString(R.string.updater_notif_pending_title, info.versionName))
            .setContentText(context.getString(R.string.updater_notif_pending_body, humanizeSize(info.apkSizeBytes)))
            .setOngoing(false)
            .setContentIntent(settingsPendingIntent(askDownload = true))
        notify(builder)
    }

    fun showDownloading(info: UpdateInfo, progressPercent: Int) {
        val builder = base(info)
            .setContentTitle(context.getString(R.string.updater_notif_downloading_title, info.versionName))
            .setContentText(context.getString(R.string.updater_notif_downloading_body, progressPercent))
            .setProgress(100, progressPercent, progressPercent <= 0)
            .setOngoing(true)
        notify(builder)
    }

    fun showReadyToInstall(info: UpdateInfo) {
        val builder = base(info)
            .setContentTitle(context.getString(R.string.updater_notif_ready_title, info.versionName))
            .setContentText(context.getString(R.string.updater_notif_ready_body))
            .setOngoing(false)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    context.getString(R.string.updater_notif_action_install),
                    actionPendingIntent(Action.INSTALL),
                ).build()
            )
        notify(builder)
    }

    /**
     * §5.5 — surface a pending install-consent dialog as a tappable
     * notification. The tap fires the [PackageInstaller] consent IntentSender
     * with a fresh background-activity-start grant (see
     * [UpdaterRepository.onConsentRequired]). It stays ongoing because
     * dismissing the only consent handle would strand the committed session.
     */
    fun showConsentRequired(info: UpdateInfo, consentIntent: Intent) {
        consentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val consentPi = PendingIntent.getActivity(
            context,
            REQ_CONSENT,
            consentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = base(info)
            .setContentTitle(context.getString(R.string.updater_notif_ready_title, info.versionName))
            .setContentText(context.getString(R.string.updater_notif_consent_body))
            .setOngoing(true)
            .setContentIntent(consentPi)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    context.getString(R.string.updater_notif_action_install),
                    consentPi,
                ).build()
            )
        notify(builder)
    }

    fun showCertMismatch(info: UpdateInfo) {
        val builder = base(info)
            .setContentTitle(context.getString(R.string.updater_notif_cert_title))
            .setContentText(context.getString(R.string.updater_notif_cert_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.updater_notif_cert_body)))
            .setOngoing(false)
            .setContentIntent(openReleasePendingIntent(info.releasePageUrl))
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    context.getString(R.string.updater_notif_action_open_release),
                    openReleasePendingIntent(info.releasePageUrl),
                ).build()
            )
        notify(builder)
    }

    fun showDownloadError(info: UpdateInfo, reason: DownloadError) {
        val body = when (reason) {
            DownloadError.NO_SPACE -> context.getString(R.string.updater_notif_error_no_space)
            DownloadError.CORRUPT -> context.getString(R.string.updater_notif_error_corrupt)
            DownloadError.GENERIC -> context.getString(R.string.updater_notif_error_generic)
        }
        val builder = base(info)
            .setContentTitle(context.getString(R.string.updater_notif_error_title, info.versionName))
            .setContentText(body)
            .setOngoing(false)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    context.getString(R.string.updater_notif_action_retry),
                    actionPendingIntent(Action.DOWNLOAD),
                ).build()
            )
        notify(builder)
    }

    fun showInstallError(info: UpdateInfo, status: Int, message: String?) {
        val body = when (status) {
            PackageInstaller.STATUS_FAILURE_INVALID ->
                context.getString(R.string.updater_notif_install_invalid)
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                context.getString(R.string.updater_notif_install_storage)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                context.getString(R.string.updater_notif_install_conflict)
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                context.getString(R.string.updater_notif_install_blocked)
            else -> context.getString(R.string.updater_notif_install_generic)
        }
        val builder = base(info)
            .setContentTitle(context.getString(R.string.updater_notif_install_error_title, info.versionName))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(false)
        notify(builder)
    }

    /**
     * One-time notice that this device will not be offered further releases.
     *
     * Deliberately not `setOngoing` and dismissible: it is information, not a
     * task. It carries no update action because there is nothing installable
     * to offer — tapping it opens Settings, where the same message is
     * repeated permanently for anyone who swipes this away.
     */
    fun showEndOfSupport(requiredSdkInt: Int) {
        val body = context.getString(
            R.string.updater_notif_end_of_support_body,
            androidVersionName(requiredSdkInt),
        )
        val builder = NotificationCompat.Builder(context, AppNotificationService.Channel.UPDATER)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentTitle(context.getString(R.string.updater_notif_end_of_support_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(settingsPendingIntent())
        notify(builder)
    }

    fun cancel() {
        manager.cancel(AppNotificationService.Id.UPDATE)
    }

    /**
     * Marketing version for an API level, so the message can say "Android 9"
     * instead of "API 28". Only the levels this app can actually run on need
     * an entry; anything else degrades to the raw number rather than lying.
     */
    private fun androidVersionName(sdkInt: Int): String = when (sdkInt) {
        26 -> "8.0"
        27 -> "8.1"
        28 -> "9"
        29 -> "10"
        30 -> "11"
        31, 32 -> "12"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        else -> "API $sdkInt"
    }

    private fun base(@Suppress("UNUSED_PARAMETER") info: UpdateInfo): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, AppNotificationService.Channel.UPDATER)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(false)
            .setContentIntent(settingsPendingIntent())
            .setDeleteIntent(actionPendingIntent(Action.DISMISS))
    }

    private fun settingsPendingIntent(askDownload: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "settings")
            if (askDownload) putExtra("updater_show_download_dialog", true)
        }
        return PendingIntent.getActivity(
            context,
            if (askDownload) REQ_SETTINGS_ASK else REQ_SETTINGS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openReleasePendingIntent(url: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            REQ_OPEN_RELEASE_PAGE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionPendingIntent(action: Action): PendingIntent {
        val intent = Intent(context, UpdaterActionReceiver::class.java).apply {
            setPackage(context.packageName)
            this.action = action.intentAction
        }
        return PendingIntent.getBroadcast(
            context,
            action.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @SuppressLint("MissingPermission")
    private fun notify(builder: NotificationCompat.Builder) {
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sys = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val ch = sys?.getNotificationChannel(AppNotificationService.Channel.UPDATER)
            if (ch?.importance == NotificationManager.IMPORTANCE_NONE) return
        }
        manager.notify(AppNotificationService.Id.UPDATE, builder.build())
    }

    private fun humanizeSize(bytes: Long): String {
        if (bytes <= 0) return "—"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return "%.1f MB".format(mb)
    }

    enum class DownloadError { NO_SPACE, CORRUPT, GENERIC }

    enum class Action(val intentAction: String, val requestCode: Int) {
        DOWNLOAD("com.cruxcoach.android.updater.ACTION_DOWNLOAD", 101),
        INSTALL("com.cruxcoach.android.updater.ACTION_INSTALL", 102),
        DISMISS("com.cruxcoach.android.updater.ACTION_DISMISS", 103),
    }

    companion object {
        private const val REQ_SETTINGS = 200
        private const val REQ_OPEN_RELEASE_PAGE = 201
        private const val REQ_SETTINGS_ASK = 202
        private const val REQ_CONSENT = 203
    }
}
