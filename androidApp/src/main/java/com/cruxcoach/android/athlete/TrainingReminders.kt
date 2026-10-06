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
import com.cruxcoach.android.MainActivity
import com.cruxcoach.android.R
import com.cruxcoach.android.notification.AppNotificationService
import com.cruxcoach.android.ui.training.TrainingRoutes
import com.cruxcoach.android.ui.training.builtinRoutineNameRes
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Optional reminder for planned training days (week plan): "Heute: Zug &
 * Antagonist" at the chosen time. Off by default; quiet on rest days, on days
 * already trained or climbed, while a pause or illness is open, and on board
 * days unless the athlete wants those too.
 *
 * Like [BodyReminders], one unique one-time request for the next occurrence
 * that the worker re-enqueues. It fires every day at the chosen time and
 * decides then from the current week plan, so editing the plan needs no
 * rescheduling; only switching it on/off or changing the time does. The
 * schedule (on/off and time, nothing personal) is mirrored in plain
 * preferences so app start can repair the chain without the database.
 */
object TrainingReminders {

    private const val WORK = "week_plan_training_reminder"
    private const val PREFS = "training_reminders"
    internal const val ID = 1103

    /** Call after the reminder settings changed (and from the worker for the next occurrence). */
    fun reschedule(context: Context, profile: AthleteProfile) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", profile.trainingReminderEnabled)
            .putInt("minutes", profile.trainingReminderMinutes)
            .apply()
        schedule(app, ExistingWorkPolicy.REPLACE, profile.trainingReminderEnabled, profile.trainingReminderMinutes)
    }

    /** App start: re-create a missing chain from the mirrored settings; never touches the database. */
    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("enabled")) return
        schedule(app, ExistingWorkPolicy.KEEP, prefs.getBoolean("enabled", false), prefs.getInt("minutes", 17 * 60))
    }

    private fun schedule(context: Context, policy: ExistingWorkPolicy, enabled: Boolean, minutes: Int) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(WORK); return }
        val now = ZonedDateTime.now()
        val at = nextOccurrence(now, minutes)
        val request = OneTimeWorkRequestBuilder<WeekPlanReminderWorker>()
            .setInitialDelay((at.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(WORK, policy, request)
    }

    /** Today at the chosen time if still ahead, otherwise tomorrow. */
    internal fun nextOccurrence(now: ZonedDateTime, minutes: Int): ZonedDateTime {
        val m = minutes.coerceIn(0, 24 * 60 - 1)
        val today = now.toLocalDate().atTime(m / 60, m % 60).atZone(now.zone)
        return if (today.isAfter(now.plusSeconds(30))) today else now.toLocalDate().plusDays(1).atTime(m / 60, m % 60).atZone(now.zone)
    }

    /**
     * What the plan holds for [isoWeekday] worth a reminder: a routine id
     * ("builtin:<key>" or an own routine), [PLAN_BOARD], or null for a free or
     * rest day (and for board days when those are not wanted).
     */
    internal fun plannedEntry(profile: AthleteProfile, isoWeekday: Int): String? {
        val entry = profile.weekPlan[isoWeekday] ?: return null
        return when (entry) {
            PLAN_REST -> null
            PLAN_BOARD -> entry.takeIf { profile.trainingReminderOnClimbingDays }
            else -> entry
        }
    }
}

@HiltWorker
class WeekPlanReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val service: dagger.Lazy<AthleteService>,
    private val notifications: AppNotificationService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val athlete = service.get()
        val repo = athlete.repo
        val profile = repo.profile()
        if (profile.trainingReminderEnabled) {
            val today = LocalDate.now()
            val entry = TrainingReminders.plannedEntry(profile, today.dayOfWeek.value)
            val quiet = entry == null ||
                repo.openPause() != null ||
                repo.openWorkout() != null ||
                runCatching { athlete.activities(1)[today.toString()]?.trained == true }.getOrDefault(false)
            if (!quiet && entry != null) {
                val name = when {
                    entry == PLAN_BOARD -> applicationContext.getString(R.string.trw_notify_board)
                    entry.startsWith("builtin:") -> BuiltinRoutines.byKey(entry.removePrefix("builtin:"))
                        ?.let { r -> builtinRoutineNameRes(r.builtinKey)?.let { applicationContext.getString(it) } ?: r.name }
                    else -> repo.routines().firstOrNull { it.id == entry }?.name
                }
                // A routine deleted since it was planned: say nothing rather than something wrong.
                if (name != null) show(name)
            }
        }
        // Next day (or cancellation when switched off meanwhile).
        TrainingReminders.reschedule(applicationContext, profile)
        return Result.success()
    }

    private fun show(name: String) {
        if (!notifications.hasPermission()) return
        val ctx = applicationContext
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", TrainingRoutes.TODAY)
        }
        val pending = PendingIntent.getActivity(ctx, TrainingReminders.ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = ctx.getString(R.string.trw_notify_title, name)
        val text = ctx.getString(R.string.trw_notify_text)
        val notification = NotificationCompat.Builder(ctx, AppNotificationService.Channel.TRAINING_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(TrainingReminders.ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: nothing to do.
        }
    }
}
