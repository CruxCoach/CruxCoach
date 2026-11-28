package com.cruxcoach.android.ui.training.body

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.MetricGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import javax.inject.Inject

/** Which readings the history shows; [ALL] includes height, arm span and Waistline's own stats too. */
enum class HistoryFilter(val key: String, private val groups: Set<MetricGroup>?) {
    ALL("all", null),
    WEIGHT("weight", setOf(MetricGroup.WEIGHT)),
    COMPOSITION("composition", setOf(MetricGroup.COMPOSITION)),
    CIRCUMFERENCE("circumference", setOf(MetricGroup.CIRCUMFERENCE));

    fun includes(metricKey: String): Boolean {
        val wanted = groups ?: return true
        val group = BodyMetric.fromKey(metricKey)?.group ?: return false
        return group in wanted
    }

    companion object {
        fun of(key: String?): HistoryFilter = entries.firstOrNull { it.key == key } ?: ALL
    }
}

data class BodyHistoryState(
    val loading: Boolean = true,
    val profile: AthleteProfile = AthleteProfile(),
    val today: LocalDate? = null,
    /** Every reading, newest day first. */
    val entries: List<BodyMeasurement> = emptyList(),
    val filter: HistoryFilter = HistoryFilter.ALL,
) {
    val visible: List<BodyMeasurement> get() = entries.filter { filter.includes(it.metric) }
}

/**
 * Every body reading ever entered or imported (owner 2026-11-28: "nirgendwo alle historischen Gewichtslogs, nur
 * letzte Einträge"), to look through, correct and delete.
 */
@HiltViewModel
class BodyHistoryViewModel @Inject constructor(
    private val service: AthleteService,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(BodyHistoryState(filter = HistoryFilter.of(savedState.get<String>(ARG_GROUP))))
    val state: StateFlow<BodyHistoryState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            // The recent entries re-emit on every change of the table: the trigger to read all of them again.
            combine(repo.observeProfile(), repo.observeRecentMeasurements(1)) { profile, _ -> profile }
                .collect { profile ->
                    val all = repo.allMeasurements().sortedWith(compareByDescending<BodyMeasurement> { it.day }.thenBy { it.metric })
                    _state.update { it.copy(loading = false, profile = profile, today = service.today(), entries = all) }
                }
        }
    }

    fun setFilter(filter: HistoryFilter) {
        savedState[ARG_GROUP] = filter.key
        _state.update { it.copy(filter = filter) }
    }

    /** As on the body page: a changed day or metric removes the old row, so a correction leaves no duplicate. */
    fun save(metricKey: String, day: LocalDate, canonicalValue: Double, unit: String, original: BodyMeasurement) {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            repo.transaction {
                if (original.day != day.toString() || original.metric != metricKey) repo.deleteMeasurement(original.day, original.metric)
                repo.saveMeasurement(
                    BodyMeasurement(
                        day = day.toString(),
                        metric = metricKey,
                        value = canonicalValue,
                        unit = unit,
                        measuredAt = System.currentTimeMillis(),
                        source = BodyMeasurement.SOURCE_MANUAL,
                    ),
                )
            }
        }
    }

    fun delete(entry: BodyMeasurement) {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            service.repo.deleteMeasurement(entry.day, entry.metric)
        }
    }

    fun restore(entry: BodyMeasurement) {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            service.repo.saveMeasurement(entry)
        }
    }

    companion object {
        /** Route argument: the [HistoryFilter.key] to start with. */
        const val ARG_GROUP = "group"
    }
}
