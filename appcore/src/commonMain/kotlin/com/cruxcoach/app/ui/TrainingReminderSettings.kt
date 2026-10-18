package com.cruxcoach.app.ui

import com.cruxcoach.app.training.Tx
import com.cruxcoach.app.trainingreminder.TrainingReminderScheduler
import com.cruxcoach.app.trainingreminder.TrainingReminderSettingsPresenter
import com.cruxcoach.app.trainingreminder.TrainingReminderSettingsRunState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TrainingReminderSettingsState(
    val loaded: Boolean,
    val enabled: Boolean,
    /** Minutes after midnight. */
    val minutes: Int,
    /** "Time: 17:00" */
    val timeText: TrText,
    val onBoardDays: Boolean,
    /** Without a week plan there is nothing to remind of (Android shows a hint and "Edit week plan"). */
    val hasWeekPlan: Boolean,
    val denied: Boolean,
)

/**
 * The training reminder in the training settings (Android `TrainingReminderSection`): on planned
 * training days at the chosen time, optionally on board days too. Every change is scheduled at once.
 */
class TrainingReminderSettingsModel internal constructor(private val presenter: TrainingReminderSettingsPresenter) {
    val currentState: TrainingReminderSettingsState get() = map(presenter.state.value)

    fun watch(onState: (TrainingReminderSettingsState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun setEnabled(on: Boolean) = presenter.setEnabled(on)

    fun setMinutes(minutes: Int) = presenter.setMinutes(minutes)

    fun setBoardDays(on: Boolean) = presenter.setBoardDays(on)

    fun close() = presenter.close()

    private fun map(s: TrainingReminderSettingsRunState): TrainingReminderSettingsState {
        val p = s.profile
        val m = p.trainingReminderMinutes.coerceIn(0, 24 * 60 - 1)
        return TrainingReminderSettingsState(
            loaded = s.loaded,
            enabled = p.trainingReminderEnabled,
            minutes = m,
            timeText = Tx.t("trr_time", Tx.s(clock(m))),
            onBoardDays = p.trainingReminderOnClimbingDays,
            hasWeekPlan = p.weekPlan.isNotEmpty(),
            denied = s.denied,
        )
    }

    internal companion object {
        /** Android formats "%02d:%02d" in the root locale. */
        fun clock(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return (if (h < 10) "0$h" else "$h") + ":" + (if (m < 10) "0$m" else "$m")
        }
    }
}

/**
 * Schedules the training-day reminders ahead again (Android `TrainingReminders.ensureScheduled`),
 * off the main thread; failures are logged, never thrown into Swift. Built by
 * `AppCore.rescheduleTrainingReminders`.
 */
class TrainingRemindersModel internal constructor(
    private val service: com.cruxcoach.app.athlete.AthleteService,
    private val scheduler: TrainingReminderScheduler,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val io: kotlinx.coroutines.CoroutineDispatcher,
) {
    fun reschedule() {
        scope.launch(io) {
            runCatching { service.ensureReady(); scheduler.reschedule(service.repo.profile()) }
                .onFailure { println("TrainingRemindersModel: reminders not scheduled: ${it.message}") }
            scope.cancel()
        }
    }

    /**
     * Android decides when the reminder fires, from the plan of that moment; iOS schedules ahead,
     * so the reminders follow every change that decides them (the reminder settings, the week plan
     * from any screen or a restored backup, a pause, a training started) until [Subscription.cancel].
     */
    fun follow(): Subscription {
        scope.launch(io) {
            try {
                service.ensureReady()
                val repo = service.repo
                combine(
                    repo.observeProfile().map { p ->
                        listOf(p.trainingReminderEnabled, p.trainingReminderMinutes, p.trainingReminderOnClimbingDays, p.weekPlan)
                    }.distinctUntilChanged(),
                    repo.observePauses().map { list -> list.map { it.id to it.endDay } }.distinctUntilChanged(),
                    repo.observeOpenWorkout().map { it?.id }.distinctUntilChanged(),
                ) { _, _, _ -> Unit }.collectLatest {
                    runCatching { scheduler.reschedule(repo.profile()) }
                        .onFailure { println("TrainingRemindersModel: reminders not scheduled: ${it.message}") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("TrainingRemindersModel: reminders not followed: ${e.message}")
            }
        }
        return Subscription { scope.cancel() }
    }
}
