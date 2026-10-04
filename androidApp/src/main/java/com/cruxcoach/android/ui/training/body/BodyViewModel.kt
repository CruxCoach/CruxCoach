package com.cruxcoach.android.ui.training.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.logic.RedsSignal
import com.cruxcoach.athlete.logic.StrengthMath
import com.cruxcoach.athlete.logic.TrendWeight
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.SetType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import javax.inject.Inject

/** Chart window; ALL shows the whole series. */
enum class BodyRange(val days: Int?) { M1(30), M3(90), M6(182), Y1(365), ALL(null) }

/** Latest value of one metric and the change since the reading before it. */
data class MetricSummary(val metricKey: String, val latest: BodyMeasurement, val delta: Double?)

/** Best strength-to-weight value of one exercise on one day. */
data class StrengthPoint(val day: LocalDate, val slug: String, val percentBodyweight: Double)

data class BodyState(
    val loading: Boolean = true,
    val profile: AthleteProfile = AthleteProfile(),
    val today: LocalDate? = null,
    val weight: List<TrendWeight.Point> = emptyList(),
    val range: BodyRange = BodyRange.M3,
    val weeklyRateKg: Double? = null,
    val summaries: List<MetricSummary> = emptyList(),
    val apeIndexCm: Double? = null,
    val recent: List<BodyMeasurement> = emptyList(),
    val strength: List<StrengthPoint> = emptyList(),
    /** Catalogue entries of the strength-to-weight exercises, for names. */
    val strengthDefs: Map<String, ExerciseDefinition> = emptyMap(),
    val redsSignals: List<RedsSignal> = emptyList(),
) {
    /** Points inside the selected window (all points for ALL). */
    val visibleWeight: List<TrendWeight.Point>
        get() {
            val days = range.days ?: return weight
            val end = today ?: weight.lastOrNull()?.day ?: return weight
            val start = end.minus(DatePeriod(days = days))
            return weight.filter { it.day >= start }
        }
}

@HiltViewModel
class BodyViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(BodyState())
    val state: StateFlow<BodyState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            // observeRecentMeasurements re-emits on every change of the
            // measurement table, which is the trigger to re-derive everything.
            combine(repo.observeProfile(), repo.observeRecentMeasurements(30)) { profile, recent -> profile to recent }
                .collect { (profile, recent) -> refresh(profile, recent) }
        }
    }

    private fun refresh(profile: AthleteProfile, recent: List<BodyMeasurement>) {
        val repo = service.repo
        val trend = service.weightTrend()
        val summaries = BodyMetric.entries.mapNotNull { metric ->
            val series = repo.series(metric.key)
            val latest = series.lastOrNull() ?: return@mapNotNull null
            val previous = series.getOrNull(series.size - 2)
            MetricSummary(metric.key, latest, previous?.let { latest.value - it.value })
        }
        val ape = Units.apeIndex(repo.latest(BodyMetric.ARM_SPAN.key)?.value, repo.latest(BodyMetric.HEIGHT.key)?.value)
        _state.update {
            it.copy(
                loading = false,
                profile = profile,
                today = service.today(),
                weight = trend,
                weeklyRateKg = TrendWeight.weeklyRate(trend),
                summaries = summaries,
                apeIndexCm = ape,
                recent = recent,
                strength = strengthPoints(),
                strengthDefs = STRENGTH_SLUGS.mapNotNull { slug -> service.catalog[slug]?.let { slug to it } }.toMap(),
                redsSignals = service.redsSignals(profile, service.activities(days = 14)),
            )
        }
    }

    /**
     * Best % body weight per day for the benchmark exercises. Hangs and
     * pick-ups use the effective load; weighted pull-ups their estimated
     * one-rep max. Each set carries the body weight of its day; older sets
     * without one fall back to today's trend weight.
     */
    private fun strengthPoints(): List<StrengthPoint> {
        val fallbackBw = service.currentBodyweight()
        val zone = java.time.ZoneId.systemDefault()
        return STRENGTH_SLUGS.flatMap { slug ->
            val def = service.catalog[slug] ?: return@flatMap emptyList()
            service.repo.history(slug, 500)
                .filter { it.isCompleted && it.setType != SetType.WARMUP }
                .mapNotNull { set ->
                    val bw = set.bodyweightKg ?: fallbackBw ?: return@mapNotNull null
                    val percent = if (def.kind == ExerciseKind.LOAD_REPS) {
                        val load = StrengthMath.effectiveLoad(def.load, set.loadKg, bw) ?: return@mapNotNull null
                        val reps = set.reps?.takeIf { it > 0 } ?: return@mapNotNull null
                        StrengthMath.epley(load, reps) / bw * 100.0
                    } else {
                        if ((set.durationS ?: 0.0) < 5.0) return@mapNotNull null
                        StrengthMath.percentBodyweight(def.load, set.loadKg, bw) ?: return@mapNotNull null
                    }
                    val local = java.time.Instant.ofEpochMilli(set.completedAt ?: return@mapNotNull null)
                        .atZone(zone).toLocalDate()
                    val day = LocalDate.parse(local.toString())
                    StrengthPoint(day, slug, percent)
                }
                .groupBy { it.day }
                .map { (_, points) -> points.maxBy { it.percentBodyweight } }
        }.sortedBy { it.day }
    }

    fun setRange(range: BodyRange) = _state.update { it.copy(range = range) }

    /**
     * Saves a reading in canonical units. [original] is the entry being
     * edited: if its day or metric changed, the old row is removed so a
     * correction never leaves a duplicate behind.
     */
    fun save(metricKey: String, day: LocalDate, canonicalValue: Double, unit: String, original: BodyMeasurement?) {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            repo.transaction {
                if (original != null && (original.day != day.toString() || original.metric != metricKey)) {
                    repo.deleteMeasurement(original.day, original.metric)
                }
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
        const val SLUG_MAX_HANG = "finger.max_hang"
        const val SLUG_ONE_ARM_PICKUP = "finger.one_arm_pickup"
        const val SLUG_WEIGHTED_PULL_UP = "pull.weighted_pull_up"
        val STRENGTH_SLUGS = listOf(SLUG_MAX_HANG, SLUG_ONE_ARM_PICKUP, SLUG_WEIGHTED_PULL_UP)
    }
}
