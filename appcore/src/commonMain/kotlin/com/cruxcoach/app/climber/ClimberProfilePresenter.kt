// Port of androidApp/.../ui/training/stats/ClimberProfileScreen.kt's ClimberProfileViewModel
// (0e1bad0c6) for the iOS app. Kept close to the Android file so later Android changes carry over
// by diff. Platform seams: an injected scope and IO dispatcher. The package is `climber` because
// `app.profile` is the Nostr profile.
package com.cruxcoach.app.climber

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.athlete.logic.ClimberProfileData
import com.cruxcoach.athlete.logic.LoadStatus
import com.cruxcoach.athlete.logic.PerformanceProfiles
import com.cruxcoach.athlete.logic.StrengthPoint
import com.cruxcoach.athlete.logic.StrengthSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** One strength value at the working grade of its week (Android `PositionPoint`, ProfileCharts.kt). */
internal data class PositionPoint(val difficulty: Double, val pctBw: Double, val day: LocalDate, val estimated: Boolean)

internal data class ClimberProfileRunState(
    val loading: Boolean = true,
    /** Reading the data failed; Android has no such state (it would crash), iOS says so instead. */
    val failed: Boolean = false,
    val profile: ClimberProfileData? = null,
    val load: LoadStatus? = null,
    val fingerPoints: List<PositionPoint> = emptyList(),
    val pullPoints: List<PositionPoint> = emptyList(),
)

/**
 * "Kletterprofil": how the working grade moves, how strong fingers and pulling are relative to
 * body weight, where that sits among climbers of the same grade, how grade and finger strength
 * move together, and where the load stands. Orientation, never a target.
 */
internal class ClimberProfilePresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(ClimberProfileRunState())
    val state: StateFlow<ClimberProfileRunState> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        scope.launch(io) {
            try {
                service.ensureReady()
                val profile = service.climberProfile()
                val load = service.loadStatus()
                _state.value = ClimberProfileRunState(
                    loading = false,
                    profile = profile,
                    load = load,
                    fingerPoints = positionPoints(profile, profile.finger),
                    pullPoints = positionPoints(profile, profile.pull),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("ClimberProfilePresenter: profile not read: ${e.message}")
                // A reload that fails keeps what was already on screen.
                _state.update { if (it.profile != null) it else it.copy(loading = false, failed = true) }
            }
        }
    }

    fun close() = scope.cancel()

    internal companion object {
        /** Each measurement at the working grade of its week (the newest earlier week, else today's anchor). */
        fun positionPoints(p: ClimberProfileData, values: List<StrengthPoint>): List<PositionPoint> = values.mapNotNull { v ->
            val week = PerformanceProfiles.weekStart(v.day)
            val grade = p.gradeTimeline.lastOrNull { it.weekStart <= week }?.difficulty ?: p.summary.workingDifficulty
            grade?.let { PositionPoint(it, v.pctBw, v.day, v.source == StrengthSource.ESTIMATE) }
        }
    }
}
