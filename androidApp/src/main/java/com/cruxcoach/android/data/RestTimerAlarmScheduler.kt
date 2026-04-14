package com.cruxcoach.android.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.cruxcoach.android.notification.RestTimerAlarmReceiver

/**
 * Schedules an exact alarm for the rest timer so the notification fires even in Doze mode.
 * Also persists the timer end timestamp in SharedPreferences for recovery after process death.
 */
class RestTimerAlarmScheduler(private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun schedule(endMs: Long) {
        prefs.edit().putLong(KEY_REST_TIMER_END_MS, endMs).apply()

        // API 31+: SCHEDULE_EXACT_ALARM permission required, may not be granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarm — permission not granted, falling back to inexact")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                endMs,
                createPendingIntent()
            )
            return
        }

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            endMs,
            createPendingIntent()
        )
    }

    fun cancel() {
        alarmManager.cancel(createPendingIntent())
        prefs.edit().remove(KEY_REST_TIMER_END_MS).apply()
    }

    /** Returns the persisted end timestamp, or 0L if none. */
    fun getPersistedEndMs(): Long = prefs.getLong(KEY_REST_TIMER_END_MS, 0L)

    /** Returns true if an alarm PendingIntent is currently scheduled. */
    fun isAlarmScheduled(): Boolean {
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REST_TIMER,
            Intent(context, RestTimerAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) != null
    }

    /** Clean up persisted state (e.g., when timer expires or is stale). */
    fun cleanup() {
        prefs.edit().remove(KEY_REST_TIMER_END_MS).apply()
    }

    private fun createPendingIntent(): PendingIntent {
        val intent = Intent(context, RestTimerAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REST_TIMER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "RestTimerAlarmScheduler"
        private const val PREFS_NAME = "board_session"
        const val KEY_REST_TIMER_END_MS = "rest_timer_end_ms"
        private const val REQUEST_CODE_REST_TIMER = 42001
    }
}
