package com.cruxcoach.android.notification

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.cruxcoach.android.R
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.UserRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class TrainingReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val planRepository: PlanRepository,
    private val userRepository: UserRepository,
    private val notificationService: AppNotificationService
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        val profile = userRepository.getActiveProfile() ?: return Result.success()

        planRepository.getActivePlan(profile.id) ?: return Result.success()
        val session = planRepository.getSessionForToday(profile.id)

        if (session != null && session.sessionType.name != "REST") {
            val typeName = when (session.sessionType.name) {
                "STRENGTH" -> applicationContext.getString(R.string.training_type_strength)
                "POWER" -> applicationContext.getString(R.string.training_type_power)
                "VOLUME" -> applicationContext.getString(R.string.training_type_volume)
                "TECHNIQUE" -> applicationContext.getString(R.string.training_type_technique)
                "DELOAD" -> applicationContext.getString(R.string.training_type_deload)
                else -> session.sessionType.name
            }
            notificationService.notifyTrainingReminder(
                title = applicationContext.getString(R.string.training_reminder_title),
                message = applicationContext.getString(R.string.training_reminder_message, typeName, session.exercises.size)
            )
        }

        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Training reminder failed; retrying", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "TrainingReminder"
        const val WORK_NAME = "training_reminder_daily"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrainingReminderWorker>(
                1, TimeUnit.DAYS
            )
                .setInitialDelay(calculateInitialDelay(), TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        private fun calculateInitialDelay(): Long {
            val now = java.util.Calendar.getInstance()
            val target = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 8)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}
