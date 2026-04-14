package com.cruxcoach.android.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cruxcoach.android.data.RestTimerAlarmScheduler

/**
 * Receives the AlarmManager callback when the rest timer expires.
 * Shows the "Pause vorbei!" notification + vibration, then cleans up SharedPreferences.
 * This fires even in Doze mode thanks to setExactAndAllowWhileIdle().
 */
class RestTimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val notificationService = AppNotificationService(context.applicationContext)
        notificationService.notifyRestTimerFinished()

        // Clean up persisted timer state
        val prefs = context.getSharedPreferences("board_session", Context.MODE_PRIVATE)
        prefs.edit().remove(RestTimerAlarmScheduler.KEY_REST_TIMER_END_MS).apply()
    }
}
