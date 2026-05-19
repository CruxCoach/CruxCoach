package com.cruxcoach.android.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cruxcoach.android.data.RestTimerAlarmScheduler

/**
 * Receives the AlarmManager callback when the rest timer expires.
 * Fires the (ringer-aware) tone + vibration + notification, caps the
 * tone at [REST_TONE_MS] so it's noticeable but never permanent, then
 * cleans up. Fires even in Doze thanks to setExactAndAllowWhileIdle().
 */
class RestTimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val notificationService = AppNotificationService(context.applicationContext)
        // Ringer-mode-aware alert (sound only when ringer is NORMAL,
        // vibration in VIBRATE, nothing in SILENT) + visual notification.
        // Works even when notifications are denied; the
        // exact-and-allow-while-idle alarm wakes us in Doze.
        notificationService.alertRestTimerFinished()

        // Clean up persisted timer state
        context.getSharedPreferences("board_session", Context.MODE_PRIVATE)
            .edit().remove(RestTimerAlarmScheduler.KEY_REST_TIMER_END_MS).apply()

        // The alarm tone loops/runs long; keep the receiver alive via
        // goAsync() just long enough to hard-stop it after the cap so
        // it's prominent + a little longer, yet self-limiting and never
        // "stuck on". goAsync() guarantees the process stays up for
        // this (well under its ~10 s budget).
        val pending = goAsync()
        Thread {
            try {
                Thread.sleep(REST_TONE_MS)
            } catch (_: InterruptedException) {
                // fall through to stop + finish
            } finally {
                notificationService.stopRestAlarmSound()
                pending.finish()
            }
        }.start()
    }

    private companion object {
        /** Tone length cap — noticeable + a bit longer, not permanent. */
        const val REST_TONE_MS = 3000L
    }
}
