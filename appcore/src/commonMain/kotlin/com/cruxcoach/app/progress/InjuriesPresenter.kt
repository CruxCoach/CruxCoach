// Port of androidApp/.../ui/training/athlete/InjuriesScreen.kt's InjuriesViewModel (0e1bad0c6) for
// the iOS app. Kept close to the Android file so later Android changes carry over by diff.
// Platform seams: an injected scope and IO dispatcher, the common clock.
package com.cruxcoach.app.progress

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.PauseReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal data class InjuriesRunState(
    val loading: Boolean = true,
    val active: List<Injury> = emptyList(),
    val past: List<Injury> = emptyList(),
    val pauses: List<PausePeriod> = emptyList(),
    /** Injuries and the comeback diary go into the automatic encrypted backup too (Android, owner 2026-10-20). */
    val healthInBackup: Boolean = false,
)

internal class InjuriesPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(InjuriesRunState())
    val state: StateFlow<InjuriesRunState> = _state.asStateFlow()

    init {
        scope.launch(io) {
            service.ensureReady()
            combine(service.repo.observeActiveInjuries(), service.repo.observePauses(), service.repo.observeProfile()) { active, pauses, profile ->
                InjuriesRunState(false, active, service.repo.allInjuries().filter { !it.isActive }, pauses, profile.healthInCloudBackup)
            }.collect { _state.value = it }
        }
    }

    fun add(region: InjuryRegion, side: InjurySide?, detail: String, severity: Int, pauseClimbing: Boolean, pauseStreak: Boolean) = io {
        val repo = service.repo
        val today = service.today().toString()
        repo.saveInjury(Injury(repo.newId(), region, side, detail.trim().ifBlank { null }, severity, pauseClimbing, today,
            updatedAt = nowMs()))
        if (pauseStreak && repo.openPause() == null) repo.savePause(PausePeriod(repo.newId(), PauseReason.INJURY, today))
    }

    fun update(injury: Injury) = io { service.repo.saveInjury(injury.copy(updatedAt = nowMs())) }

    /** Healing an injury also ends an injury pause once nothing else is open. */
    fun resolve(injury: Injury) = io {
        val repo = service.repo
        val today = service.today().toString()
        repo.saveInjury(injury.copy(resolvedOn = today, updatedAt = nowMs()))
        if (repo.activeInjuries().isEmpty()) {
            repo.openPause()?.takeIf { it.reason == PauseReason.INJURY }?.let { repo.savePause(it.copy(endDay = today)) }
        }
    }

    fun delete(injury: Injury) = io { service.repo.deleteInjury(injury.id) }

    fun setHealthInBackup(on: Boolean) = io { service.repo.updateProfile { it.copy(healthInCloudBackup = on) } }

    fun startHoliday() = io {
        val repo = service.repo
        if (repo.openPause() == null) repo.savePause(PausePeriod(repo.newId(), PauseReason.HOLIDAY, service.today().toString()))
    }

    fun endPause(p: PausePeriod) = io { service.repo.savePause(p.copy(endDay = service.today().toString())) }
    fun deletePause(p: PausePeriod) = io { service.repo.deletePause(p.id) }

    fun close() = scope.cancel()

    private fun io(block: suspend () -> Unit) {
        scope.launch(io) { service.ensureReady(); block() }
    }
}
