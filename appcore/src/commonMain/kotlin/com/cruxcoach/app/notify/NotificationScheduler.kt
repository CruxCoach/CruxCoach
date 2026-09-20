package com.cruxcoach.app.notify

/**
 * Local notifications, implemented by the host.
 *
 * Android fires the rest timer from an exact `AlarmManager` alarm
 * (`notification/RestTimerAlarmReceiver`); the iOS equivalent is a
 * `UNUserNotificationCenter` request with a time-interval trigger, which is the
 * only mechanism that still fires once the app is suspended or the phone is
 * locked. Both are one-shot and keyed by a stable id so a re-scheduled timer
 * replaces the previous one instead of stacking.
 *
 * [titleKey] and [bodyKey] are Android string-resource names; the host resolves
 * them in its own locale. No user-visible text crosses this boundary.
 */
interface NotificationScheduler {
    /** Replaces any pending notification with the same [id]. [afterSeconds] must be >= 1. */
    fun schedule(id: String, afterSeconds: Int, titleKey: String, bodyKey: String)

    fun cancel(id: String)

    /**
     * Asks for the notification permission. [onResult] gets true only when the
     * host may actually post; a denied prompt is a value, never an exception.
     */
    fun requestPermission(onResult: (Boolean) -> Unit)
}

/** Used where no host scheduler exists (tests, hosts without local notifications). */
class NoNotifications : NotificationScheduler {
    override fun schedule(id: String, afterSeconds: Int, titleKey: String, bodyKey: String) = Unit
    override fun cancel(id: String) = Unit
    override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(false)
}
