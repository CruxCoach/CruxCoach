package com.cruxcoach.app.notify

import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.app.settings.SettingsStore

/**
 * Turns the playlist player's rest block into a local notification.
 *
 * The in-app countdown stops being delivered the moment iOS suspends the app,
 * so a rest that ends while the phone is locked would otherwise pass silently.
 * The notification is scheduled against the rest's *end instant*
 * (`PlaybackPhase.Resting.endsAtEpochSeconds`), not against a tick count, so a
 * suspended app still fires at the right time — the same reasoning that made
 * Android use an exact alarm rather than a foreground countdown.
 *
 * Idempotent: feeding the same end instant repeatedly (every state emission of
 * the player) re-uses the pending request instead of re-scheduling it.
 */
class RestTimerNotifier(
    private val scheduler: NotificationScheduler,
    private val settings: SettingsStore,
    private val clock: WallClock,
) {
    private var scheduledEndsAt: Long = 0L

    /** True once the host said it may post; false while undecided or denied. */
    var permitted: Boolean = false
        private set

    /** Ask before the first rest can end in the background, not on cold start. */
    fun requestPermission(onResult: (Boolean) -> Unit) = scheduler.requestPermission {
        permitted = it
        onResult(it)
    }

    /**
     * Mirrors the player's rest phase. [endsAtEpochSeconds] is 0 while climbing,
     * which cancels a pending notification — skipping a rest must not leave an
     * alert queued for a countdown that no longer exists.
     */
    fun restChanged(endsAtEpochSeconds: Long) {
        if (endsAtEpochSeconds <= 0L) {
            cancel()
            return
        }
        val lead = endsAtEpochSeconds - clock.epochSeconds()
        // Check expiry BEFORE the unchanged-end short circuit: when the app comes
        // back after the rest was already over, the end has not changed but the
        // pending notification must still go, or it fires late.
        // A trigger in the past (or this same second) is rejected by
        // UNTimeIntervalNotificationTrigger; the in-app state already shows the end.
        if (lead < MIN_LEAD_SECONDS) {
            cancel()
            return
        }
        if (endsAtEpochSeconds == scheduledEndsAt) return
        scheduledEndsAt = endsAtEpochSeconds
        scheduler.schedule(REST_ID, lead.toInt(), TITLE_KEY, BODY_KEY)
    }

    fun cancel() {
        if (scheduledEndsAt == 0L) return
        scheduledEndsAt = 0L
        scheduler.cancel(REST_ID)
    }

    /**
     * Android's `rest_timer_auto_start`: how long the rest that follows a
     * logged attempt runs, or 0 when the user did not opt into it. The caller
     * starts the rest; this only answers the preference.
     */
    fun autoStartSeconds(): Int {
        val snapshot = settings.snapshot
        return if (snapshot.restTimerAutoStart) snapshot.restTimerDurationSeconds else 0
    }

    companion object {
        /** One rest runs at a time, so one stable id keeps re-schedules from stacking. */
        const val REST_ID = "cruxcoach.rest_timer"
        const val TITLE_KEY = "notification_rest_timer_title"
        const val BODY_KEY = "notification_rest_timer_message"
        const val MIN_LEAD_SECONDS = 1L
    }
}
