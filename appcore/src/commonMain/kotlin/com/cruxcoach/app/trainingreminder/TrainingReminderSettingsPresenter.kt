// Port of the training-reminder part of androidApp/.../ui/training/athlete/AthleteSettingsScreen.kt
// (`TrainingReminderSection`, 0e1bad0c6): stored in the profile, rescheduled at once, the
// notification permission asked when switching it on.
package com.cruxcoach.app.trainingreminder

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.notify.NotificationScheduler
import com.cruxcoach.athlete.model.AthleteProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal data class TrainingReminderSettingsRunState(
    val loaded: Boolean = false,
    val profile: AthleteProfile = AthleteProfile(),
    /** Notifications were refused when the reminder was switched on (the setting is kept, as on Android). */
    val denied: Boolean = false,
)

internal class TrainingReminderSettingsPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val reminders: TrainingReminderScheduler,
    private val notifications: NotificationScheduler,
) {
    private val _state = MutableStateFlow(TrainingReminderSettingsRunState())
    val state: StateFlow<TrainingReminderSettingsRunState> = _state.asStateFlow()
    private val denied = MutableStateFlow(false)

    init {
        scope.launch(io) {
            service.ensureReady()
            combine(service.repo.observeProfile(), denied) { p, d -> TrainingReminderSettingsRunState(true, p, d) }
                .collect { _state.value = it }
        }
    }

    fun setEnabled(on: Boolean) = update(enabling = on) { it.copy(trainingReminderEnabled = on) }

    fun setMinutes(minutes: Int) = update(enabling = false) { it.copy(trainingReminderMinutes = minutes.coerceIn(0, 24 * 60 - 1)) }

    fun setBoardDays(on: Boolean) = update(enabling = false) { it.copy(trainingReminderOnClimbingDays = on) }

    /**
     * Stored, then scheduled again at once. Switching on asks for the notification permission
     * first; a refusal keeps the setting and the section says why nothing will appear.
     */
    private fun update(enabling: Boolean, transform: (AthleteProfile) -> AthleteProfile) {
        val apply: () -> Unit = {
            scope.launch(io) {
                try {
                    service.ensureReady()
                    service.repo.updateProfile(transform)
                    reminders.reschedule(service.repo.profile())
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("TrainingReminderSettingsPresenter: reminder not saved: ${e.message}")
                }
            }
        }
        if (enabling) notifications.requestPermission { granted -> denied.value = !granted; apply() } else apply()
    }

    fun close() = scope.cancel()
}
