package com.cruxcoach.android.fips

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.cruxcoach.android.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FipsMeshService : android.app.Service() {
    @Inject lateinit var runtime: FipsMeshRuntime

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.fips_mesh_channel), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(getString(R.string.fips_mesh_title))
                .setContentText(getString(R.string.fips_mesh_text))
                .setOngoing(true).setOnlyAlertOnce(true).build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        // Runtime startup happens on its IO scope before the service is requested.
        // Never run the native ready handshake from the application's main thread.
        if (!runtime.running.value) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "fips_board_cell_mesh"
        private const val NOTIFICATION_ID = 5901
        fun start(context: Context) = context.startForegroundService(Intent(context, FipsMeshService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, FipsMeshService::class.java)) }
    }
}
