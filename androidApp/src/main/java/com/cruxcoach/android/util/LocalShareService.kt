package com.cruxcoach.android.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.MainActivity
import com.cruxcoach.android.R
import com.cruxcoach.android.fips.FipsMeshRuntime
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the complete sender-side offline-share session.
 *
 * Keeping the hotspot and HTTP server in a foreground service means leaving
 * the Compose screen no longer tears down a receiver's transfer. The service
 * is deliberately non-sticky: creating a new hotspot after process death
 * would change its credentials and make the QR code the user scanned stale.
 */
@AndroidEntryPoint
class LocalShareService : Service() {

    @Inject lateinit var fipsMeshRuntime: FipsMeshRuntime

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Active(
            val ssid: String,
            val password: String,
            val baseUrl: String,
        ) : State
        data class Failed(val message: String) : State
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hotspot: WifiDirectHotspot? = null
    private var server: LocalApkServer? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSharing()
            else -> startSharingIfNeeded()
        }
        return START_NOT_STICKY
    }

    private fun startSharingIfNeeded() {
        if (hotspot != null || server != null || state.value is State.Active) return
        stopping = false
        _state.value = State.Starting
        startInForeground(getString(R.string.local_share_notification_starting))

        val wifiHotspot = WifiDirectHotspot(this)
        hotspot = wifiHotspot
        wifiHotspot.start(
            onStarted = { info ->
                if (stopping) {
                    wifiHotspot.stop()
                    return@start
                }
                try {
                    val apk = File(applicationInfo.sourceDir)
                    val boardDb = getDatabasePath("cruxcoach.db")
                    val baseUrl = "http://${info.ip}:${LocalApkServer.LOCAL_SHARE_PORT}"
                    val invitation = LocalShareProtocol.Invitation(
                        baseUrl = baseUrl,
                        ssid = info.ssid,
                        password = info.password,
                    )
                    val localServer = LocalApkServer(
                        apkFile = apk,
                        boardDbFile = boardDb.takeIf { it.exists() },
                        snapshotDir = cacheDir,
                        apkVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        apkVersionName = BuildConfig.VERSION_NAME,
                        openAppUri = LocalShareProtocol.invitationUri(invitation),
                    )
                    localServer.onAutoShutdown = {
                        mainHandler.post {
                            Log.d(TAG, "Stopping share at the fixed session deadline")
                            stopSharing()
                        }
                    }
                    localServer.onBulkTransferStarted = {
                        // The user explicitly requested this foreground share:
                        // once a receiver asks for a large file, dedicate the
                        // radios and CPU to APK first and board DB second.
                        fipsMeshRuntime.suspendForBulkTransfer()
                    }
                    localServer.onReceiverComplete = {
                        mainHandler.post {
                            // A completion belongs to one receiver, not to the
                            // whole share session. Other nearby devices may
                            // still be downloading (or may join afterwards),
                            // so keep the hotspot and server alive until the
                            // fixed deadline or an explicit sender stop.
                            Log.d(TAG, "Receiver completed; keeping 1:n share active")
                            updateNotification(
                                getString(R.string.local_share_notification_complete),
                            )
                        }
                    }
                    val port = localServer.start(hostIp = info.ip)
                    server = localServer
                    check(port == LocalApkServer.LOCAL_SHARE_PORT) {
                        "Local share started on unexpected port $port"
                    }
                    val active = State.Active(
                        ssid = info.ssid,
                        password = info.password,
                        baseUrl = baseUrl,
                    )
                    _state.value = active
                    updateNotification(getString(R.string.local_share_notification_active))
                } catch (error: Exception) {
                    Log.e(TAG, "Local share server failed to start", error)
                    fail(getString(R.string.settings_share_server_error, error.message.orEmpty()))
                }
            },
            onError = { message ->
                fail(message)
            },
        )
    }

    private fun fail(message: String) {
        releaseShareResources()
        _state.value = State.Failed(message)
        stopping = true
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSharing() {
        if (stopping) return
        stopping = true
        releaseShareResources()
        _state.value = State.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseShareResources()
        if (_state.value !is State.Failed) _state.value = State.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun releaseShareResources() {
        server?.stop()
        server = null
        hotspot?.stop()
        hotspot = null
        if (::fipsMeshRuntime.isInitialized) {
            fipsMeshRuntime.resumeAfterBulkTransfer()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "app_share")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocalShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.local_share_notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.settings_share_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.local_share_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.local_share_notification_channel_desc)
            },
        )
    }

    companion object {
        private const val TAG = "LocalShareService"
        private const val CHANNEL_ID = "offline_share"
        private const val NOTIFICATION_ID = 4949
        private const val ACTION_START = "com.cruxcoach.android.share.ACTION_START"
        private const val ACTION_STOP = "com.cruxcoach.android.share.ACTION_STOP"

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state.asStateFlow()

        fun start(context: Context) {
            _state.value = State.Starting
            try {
                context.startForegroundService(
                    Intent(context, LocalShareService::class.java).setAction(ACTION_START),
                )
            } catch (error: Exception) {
                _state.value = State.Failed(error.message ?: error.javaClass.simpleName)
                throw error
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocalShareService::class.java).setAction(ACTION_STOP),
            )
        }

        fun clearFailure() {
            if (_state.value is State.Failed) _state.value = State.Idle
        }
    }
}
