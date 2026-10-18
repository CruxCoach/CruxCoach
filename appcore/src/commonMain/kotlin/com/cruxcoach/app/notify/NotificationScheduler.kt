package com.cruxcoach.app.notify

import com.cruxcoach.app.ui.TrText

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

    /**
     * [schedule] for a text with arguments (Android's "Today: <routine>") and with the place a tap
     * opens: [route] is one of [NotificationRoutes]' codes (Android's `navigate_to` extra), empty
     * for just the app. [title] and [body] are recipes the host renders in its own locale, so still
     * no user-visible text crosses this boundary. The default keeps hosts and test doubles that only
     * know plain keys working; it drops the arguments and the route.
     */
    fun scheduleText(id: String, afterSeconds: Int, title: TrText, body: TrText, route: String) {
        schedule(id, afterSeconds, title.key, body.key)
    }

    fun cancel(id: String)

    /**
     * Asks for the notification permission. [onResult] gets true only when the
     * host may actually post; a denied prompt is a value, never an exception.
     */
    fun requestPermission(onResult: (Boolean) -> Unit)
}

/**
 * Where a notification tap leads, as Android's `navigate_to` extra (`MainActivity.safeNavigateToRoute`
 * accepts exactly these for the athlete reminders). The host opens only a code it knows; anything else,
 * and the rest timer's empty route, just brings the app forward.
 */
object NotificationRoutes {
    /** Week-plan training reminder (Android `TrainingRoutes.TODAY`): the training area's Today. */
    const val TODAY = "today"
    /** Weigh-in and measuring reminders (Android `TrainingRoutes.BODY`): the body page above Today. */
    const val BODY = "body"

    fun isKnown(route: String): Boolean = route == TODAY || route == BODY
}

/** Used where no host scheduler exists (tests, hosts without local notifications). */
class NoNotifications : NotificationScheduler {
    override fun schedule(id: String, afterSeconds: Int, titleKey: String, bodyKey: String) = Unit
    override fun cancel(id: String) = Unit
    override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(false)
}
