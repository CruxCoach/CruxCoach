// Port of androidApp/.../ui/training/workouts/WeeklyVolumeCard.kt's WeeklyVolumeViewModel (0e1bad0c6):
// the week's targets per area on the Progress tab, with the athlete's own target per area.
// Platform seams: an injected scope and IO dispatcher.
package com.cruxcoach.app.progress

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.athlete.logic.AreaProgress
import com.cruxcoach.athlete.logic.VolumeArea
import com.cruxcoach.athlete.logic.WeeklyVolume
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Injury
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal data class WeeklyVolumeRunState(
    val loading: Boolean = true,
    val progress: List<AreaProgress> = emptyList(),
    /** An open pause (illness, injury, holiday): the week's targets rest. */
    val paused: Boolean = false,
    /** The athlete's own targets per area (sets; finger: sessions). */
    val own: Map<String, Int> = emptyMap(),
)

internal class WeeklyVolumePresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(WeeklyVolumeRunState())
    val state: StateFlow<WeeklyVolumeRunState> = _state.asStateFlow()

    init {
        scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            val weekStart = WeeklyVolume.weekStartOf(service.today())
            combine(
                repo.observeProfile(),
                repo.observeRecentWorkouts(30),
                repo.observeActiveInjuries(),
                repo.observeClimbingDaysSince(weekStart.toString()),
            ) { profile, _, injuries, _ -> profile to injuries }
                .collect { (profile, injuries) -> runCatching { compute(profile, injuries) } }
        }
    }

    /** An own weekly target for [area] (sets, finger: sessions), or back to the recommendation with null. */
    fun setOwn(area: VolumeArea, value: Int?) {
        scope.launch(io) {
            service.ensureReady()
            service.repo.updateProfile { p ->
                p.copy(ownWeeklyTargets = if (value == null) p.ownWeeklyTargets - area.name else p.ownWeeklyTargets + (area.name to value))
            }
            runCatching { compute(service.repo.profile(), service.repo.activeInjuries()) }
        }
    }

    /** Logged sets do not touch the observed tables; the tab recomputes when it is shown again. */
    fun refresh() {
        scope.launch(io) {
            service.ensureReady()
            runCatching { compute(service.repo.profile(), service.repo.activeInjuries()) }
        }
    }

    private fun compute(profile: AthleteProfile, injuries: List<Injury>) {
        // Display only: the block without fatigue signals (Today may pull a deload forward).
        _state.value = WeeklyVolumeRunState(
            loading = false,
            progress = service.weeklyProgress(profile, injuries),
            paused = service.repo.openPause() != null,
            own = profile.ownWeeklyTargets,
        )
    }

    fun close() = scope.cancel()
}
