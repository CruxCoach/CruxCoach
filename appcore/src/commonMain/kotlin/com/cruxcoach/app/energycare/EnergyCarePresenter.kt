// The data behind Android's `EnergyCareCard` (ui/training/EnergyCareCard.kt, 0e1bad0c6): Today,
// Body and the weekly review each read `AthleteService.energyReport` in their own view model; on
// iOS one small presenter reads it so the same card can be dropped into any screen.
package com.cruxcoach.app.energycare

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.athlete.logic.EnergyReport
import com.cruxcoach.athlete.model.AthleteProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class EnergyCareRunState(
    val loading: Boolean = true,
    val report: EnergyReport = EnergyReport(),
    val profile: AthleteProfile = AthleteProfile(),
)

internal class EnergyCarePresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(EnergyCareRunState())
    val state: StateFlow<EnergyCareRunState> = _state.asStateFlow()
    private val refresh = MutableStateFlow(0)

    init {
        scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            // The goal, today's food and new weights change the reasons while the card is on screen;
            // older days are picked up by reload() when the screen appears again.
            combine(repo.observeProfile(), repo.observeFoodLog(service.today().toString()),
                repo.observeRecentMeasurements(RECENT_MEASUREMENTS), refresh) { profile, _, _, _ -> profile }
                .collectLatest { profile -> compute(profile) }
        }
    }

    private fun compute(profile: AthleteProfile) {
        try {
            // Android's Body reads 14 days; the report itself looks at the last seven.
            val report = service.energyReport(profile, service.activities(ACTIVITY_DAYS))
            _state.value = EnergyCareRunState(loading = false, report = report, profile = profile)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("EnergyCarePresenter: energy report not read: ${e.message}")
            _state.update { it.copy(loading = false) }
        }
    }

    fun reload() = refresh.update { it + 1 }

    fun close() = scope.cancel()

    private companion object {
        const val ACTIVITY_DAYS = 14
        const val RECENT_MEASUREMENTS = 3
    }
}
