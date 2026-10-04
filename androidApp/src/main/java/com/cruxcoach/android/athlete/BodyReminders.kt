package com.cruxcoach.android.athlete

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cruxcoach.android.MainActivity
import com.cruxcoach.android.R
import com.cruxcoach.android.notification.AppNotificationService
import com.cruxcoach.android.ui.training.TrainingRoutes
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.MetricGroup
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Optional reminders to log body data regularly: a weigh-in on chosen
 * weekdays and a monthly measuring round. Off by default, calm wording,
 * nothing is sent when today's value is already logged.
 *
 * Each reminder is one unique one-time work request for its next occurrence;
 * the worker re-enqueues the following one. The schedule is mirrored in
 * plain preferences (no personal values, only days and times) so app start
 * can repair a broken chain without opening the encrypted athlete database.
 */
object BodyReminders {

    internal const val KIND_KEY = "kind"
    internal const val KIND_WEIGH = "weigh"
    internal const val KIND_MEASURE = "measure"
    private const val WORK_WEIGH = "body_weigh_reminder"
    private const val WORK_MEASURE = "body_measure_reminder"
    private const val PREFS = "body_reminders"
    internal const val ID_WEIGH = 1101
    internal const val ID_MEASURE = 1102

    /** Call after the reminder settings changed (and from the worker for the next occurrence). */
    fun reschedule(context: Context, profile: AthleteProfile) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("weigh_enabled", profile.weighReminderEnabled)
            .putStringSet("weigh_days", profile.weighReminderDays.map { it.toString() }.toSet())
            .putInt("minutes", profile.weighReminderMinutes)
            .putBoolean("measure_enabled", profile.measureReminderEnabled)
            .putInt("measure_day", profile.measureReminderDayOfMonth)
            .apply()
        schedule(app, ExistingWorkPolicy.REPLACE, Config.from(profile))
    }

    /** App start: re-create a missing chain from the mirrored settings; never touches the database. */
    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("weigh_enabled")) return
        val config = Config(
            weighEnabled = prefs.getBoolean("weigh_enabled", false),
            weighDays = prefs.getStringSet("weigh_days", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet(),
            minutes = prefs.getInt("minutes", 7 * 60 + 30),
            measureEnabled = prefs.getBoolean("measure_enabled", false),
            measureDay = prefs.getInt("measure_day", 1),
        )
        schedule(app, ExistingWorkPolicy.KEEP, config)
    }

    internal data class Config(
        val weighEnabled: Boolean,
        val weighDays: Set<Int>,
        val minutes: Int,
        val measureEnabled: Boolean,
        val measureDay: Int,
    ) {
        companion object {
            fun from(p: AthleteProfile) = Config(p.weighReminderEnabled, p.weighReminderDays, p.weighReminderMinutes,
                p.measureReminderEnabled, p.measureReminderDayOfMonth)
        }
    }

    private fun schedule(context: Context, policy: ExistingWorkPolicy, config: Config) {
        val wm = WorkManager.getInstance(context)
        val now = ZonedDateTime.now()
        val weighAt = if (config.weighEnabled) nextWeigh(now, config.weighDays, config.minutes) else null
        if (weighAt == null) wm.cancelUniqueWork(WORK_WEIGH)
        else wm.enqueueUniqueWork(WORK_WEIGH, policy, request(KIND_WEIGH, now, weighAt))
        val measureAt = if (config.measureEnabled) nextMeasure(now, config.measureDay, config.minutes) else null
        if (measureAt == null) wm.cancelUniqueWork(WORK_MEASURE)
        else wm.enqueueUniqueWork(WORK_MEASURE, policy, request(KIND_MEASURE, now, measureAt))
    }

    private fun request(kind: String, now: ZonedDateTime, at: ZonedDateTime) =
        OneTimeWorkRequestBuilder<BodyReminderWorker>()
            .setInitialDelay((at.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KIND_KEY to kind))
            .build()

    /** Next chosen weekday at the chosen time, strictly in the future; null without weekdays. */
    internal fun nextWeigh(now: ZonedDateTime, days: Set<Int>, minutes: Int): ZonedDateTime? {
        val valid = days.filter { it in 1..7 }.toSet()
        if (valid.isEmpty()) return null
        val m = minutes.coerceIn(0, 24 * 60 - 1)
        for (offset in 0..7) {
            val candidate = now.toLocalDate().plusDays(offset.toLong())
                .atTime(m / 60, m % 60).atZone(now.zone)
            if (candidate.dayOfWeek.value in valid && candidate.isAfter(now.plusSeconds(30))) return candidate
        }
        return null
    }

    /** Chosen day of this or next month (1–28, so every month has it) at the chosen time. */
    internal fun nextMeasure(now: ZonedDateTime, dayOfMonth: Int, minutes: Int): ZonedDateTime {
        val day = dayOfMonth.coerceIn(1, 28)
        val m = minutes.coerceIn(0, 24 * 60 - 1)
        val thisMonth = now.toLocalDate().withDayOfMonth(day).atTime(m / 60, m % 60).atZone(now.zone)
        return if (thisMonth.isAfter(now.plusSeconds(30))) thisMonth
        else now.toLocalDate().plusMonths(1).withDayOfMonth(day).atTime(m / 60, m % 60).atZone(now.zone)
    }

    internal fun circumferenceKeys(): Set<String> =
        BodyMetric.entries.filter { it.group == MetricGroup.CIRCUMFERENCE }.map { it.key }.toSet()
}

@HiltWorker
class BodyReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: dagger.Lazy<AthleteRepository>,
    private val notifications: AppNotificationService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(BodyReminders.KIND_KEY) ?: return Result.success()
        val repo = repository.get()
        val profile = repo.profile()
        val today = LocalDate.now().toString()
        when (kind) {
            BodyReminders.KIND_WEIGH -> if (profile.weighReminderEnabled &&
                repo.latest(BodyMetric.WEIGHT.key)?.day != today
            ) {
                show(BodyReminders.ID_WEIGH, R.string.trr_notify_weigh_title, R.string.trr_notify_weigh_text)
            }
            BodyReminders.KIND_MEASURE -> if (profile.measureReminderEnabled &&
                BodyReminders.circumferenceKeys().none { repo.latest(it)?.day == today }
            ) {
                show(BodyReminders.ID_MEASURE, R.string.trr_notify_measure_title, R.string.trr_notify_measure_text)
            }
        }
        // Next occurrence (or cancellation when the reminder was switched off meanwhile).
        BodyReminders.reschedule(applicationContext, profile)
        return Result.success()
    }

    private fun show(id: Int, title: Int, text: Int) {
        if (!notifications.hasPermission()) return
        val ctx = applicationContext
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", TrainingRoutes.BODY)
        }
        val pending = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(ctx, AppNotificationService.Channel.TRAINING_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(ctx.getString(title))
            .setContentText(ctx.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(text)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: nothing to do.
        }
    }
}
