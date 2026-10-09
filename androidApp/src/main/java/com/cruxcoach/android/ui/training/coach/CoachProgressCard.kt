package com.cruxcoach.android.ui.training.coach

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.logic.CoachLevel
import com.cruxcoach.athlete.logic.CoachLogic
import com.cruxcoach.athlete.logic.CoachStep
import com.cruxcoach.athlete.logic.Completeness
import com.cruxcoach.athlete.logic.LogbookSummary
import com.cruxcoach.athlete.model.SetupState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CoachProgressState(
    val visible: Boolean = false,
    val setupState: SetupState = SetupState.NOT_STARTED,
    val completeness: Completeness = Completeness(CoachLevel.BASIS, 0, emptyList()),
)

@HiltViewModel
class CoachProgressViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(CoachProgressState())
    val state: StateFlow<CoachProgressState> = _state.asStateFlow()
    /** Fires once per install: the first visit to Training opens the setup by itself. */
    private val _autoOpen = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val autoOpen: Flow<Unit> = _autoOpen.receiveAsFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val first = service.repo.profile().coach
            if (first.setupState == SetupState.NOT_STARTED && first.setupUpdatedAt == 0L) {
                // Mark it before opening, so backing out never brings the setup back on its own.
                service.repo.updateProfile { p -> p.copy(coach = p.coach.copy(setupUpdatedAt = System.currentTimeMillis())) }
                _autoOpen.trySend(Unit)
            }
            val logbook = runCatching { service.logbookSummary() }.getOrDefault(LogbookSummary())
            combine(service.repo.observeProfile(), service.repo.observeAllBenchmarks()) { profile, benchmarks ->
                val setup = profile.coach.setupState
                CoachProgressState(
                    visible = setup != SetupState.DONE && setup != SetupState.DISMISSED,
                    setupState = setup,
                    completeness = CoachLogic.completeness(profile, benchmarks, logbook),
                )
            }.collect { _state.value = it }
        }
    }

    fun dismiss() = viewModelScope.launch(Dispatchers.IO) {
        service.ensureReady()
        service.repo.updateProfile { p ->
            p.copy(coach = p.coach.copy(setupState = SetupState.DISMISSED, setupUpdatedAt = System.currentTimeMillis()))
        }
    }
}

@Composable
internal fun levelLabel(l: CoachLevel): String = stringResource(when (l) {
    CoachLevel.BASIS -> R.string.trc_level_basis
    CoachLevel.PERSONAL -> R.string.trc_level_personal
    CoachLevel.PRECISE -> R.string.trc_level_precise
})

@Composable
internal fun stepText(step: CoachStep): String = stringResource(when (step) {
    CoachStep.GOAL -> R.string.trc_next_goal
    CoachStep.WEEK -> R.string.trc_next_week
    CoachStep.EQUIPMENT -> R.string.trc_next_equipment
    CoachStep.START_VALUE_PULL -> R.string.trc_next_value_pull
    CoachStep.START_VALUE_FINGER -> R.string.trc_next_value_finger
    CoachStep.EXPERIENCE -> R.string.trc_next_experience
    CoachStep.PREFERENCES -> R.string.trc_next_preferences
    CoachStep.GRADE -> R.string.trc_next_grade
})
