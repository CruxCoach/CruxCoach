// Port of androidApp/.../ui/training/stats/ExerciseStatsScreen.kt's ExerciseStatsViewModel
// (0e1bad0c6) for the iOS app. Kept close to the Android file so later Android changes carry over
// by diff. Platform seams: an injected scope and IO dispatcher.
package com.cruxcoach.app.progress

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.BenchmarkMath
import com.cruxcoach.athlete.logic.ExerciseProgress
import com.cruxcoach.athlete.logic.PersonalRecords
import com.cruxcoach.athlete.logic.ProgressStats
import com.cruxcoach.athlete.logic.RecordKind
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Side
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

internal data class ExerciseStatsRunState(
    val loading: Boolean = true,
    val def: ExerciseDefinition? = null,
    val profile: AthleteProfile = AthleteProfile(),
    val today: LocalDate? = null,
    val range: ProgressRange = ProgressRange.SIX_MONTHS,
    val progress: ExerciseProgress? = null,
    /** Current performance value per side, as display value. */
    val levels: Map<Side?, Double> = emptyMap(),
    val bests: List<Pair<PersonalRecords.Key, Pair<RecordKind, Double>>> = emptyList(),
    val setsByWorkout: Map<String, List<ExerciseSet>> = emptyMap(),
)

/** Everything about one exercise over time: capacity, volume, sessions, bests. */
internal class ExerciseStatsPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(ExerciseStatsRunState())
    val state: StateFlow<ExerciseStatsRunState> = _state.asStateFlow()
    private var loaded: String? = null

    fun load(slug: String) {
        if (loaded == slug) return
        loaded = slug
        scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            val def = service.catalog.fallbackFor(slug)
            val bodyweight = service.currentBodyweight()
            val history = repo.history(slug, AthleteService.HISTORY_LIMIT)
            val progress = ProgressStats.exerciseProgress(def, history, TimeZone.currentSystemDefault(), bodyweight)
            val kind = BenchmarkMath.capacityKind(def)
            val levels = repo.benchmarks(slug).groupBy { it.side }.mapNotNull { (side, list) ->
                val newest = list.maxBy { it.measuredAt }
                BenchmarkMath.capacity(def, newest, bodyweight)?.let { cap ->
                    side to ProgressStats.displayValue(def, kind, cap.value, newest.bodyweightKg ?: bodyweight)
                }
            }.toMap()
            _state.update {
                it.copy(
                    loading = false,
                    def = def,
                    profile = repo.profile(),
                    today = service.today(),
                    progress = progress,
                    levels = levels,
                    bests = PersonalRecords.bests(def, history).entries.map { e -> e.key to e.value }
                        .sortedWith(compareBy({ it.first.side?.ordinal ?: -1 }, { it.first.edgeMm ?: 0 })),
                    setsByWorkout = history.filter { s -> s.isCompleted }.groupBy { s -> s.workoutId },
                )
            }
        }
    }

    fun setRange(range: ProgressRange) = _state.update { it.copy(range = range) }

    fun close() = scope.cancel()
}
