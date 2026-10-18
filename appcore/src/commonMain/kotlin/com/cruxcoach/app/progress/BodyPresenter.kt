// Port of androidApp/.../ui/training/body/BodyViewModel.kt (0e1bad0c6) for the iOS app.
// Kept close to the Android file so later Android changes carry over by diff. Platform seams:
// an injected scope and IO dispatcher instead of viewModelScope/Dispatchers.IO, the common
// clock, kotlinx-datetime instead of java.time. The Waistline file import lives in
// `app/waistline/WaistlineImportPresenter` (same logic, its own facade); everything else — trend,
// summaries, ape index, strength to weight, entries with back-dating, the measurement round,
// delete and undo — is here.
package com.cruxcoach.app.progress

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.logic.ProgressStats
import com.cruxcoach.athlete.logic.StrengthMath
import com.cruxcoach.athlete.logic.TrendWeight
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.SetType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus

/** Chart window; ALL shows the whole series. */
internal enum class BodyChartRange(val days: Int?) { M1(30), M3(90), M6(182), Y1(365), ALL(null) }

/** Latest value of one metric and the change since the reading before it. */
internal data class BodyMetricSummary(val metricKey: String, val latest: BodyMeasurement, val delta: Double?)

/** Best strength-to-weight value of one exercise on one day. */
internal data class BodyStrengthPoint(val day: LocalDate, val slug: String, val percentBodyweight: Double)

internal data class BodyRunState(
    val loading: Boolean = true,
    val profile: AthleteProfile = AthleteProfile(),
    val today: LocalDate? = null,
    val weight: List<TrendWeight.Point> = emptyList(),
    val range: BodyChartRange = BodyChartRange.M3,
    val weeklyRateKg: Double? = null,
    val summaries: List<BodyMetricSummary> = emptyList(),
    val apeIndexCm: Double? = null,
    val recent: List<BodyMeasurement> = emptyList(),
    val strength: List<BodyStrengthPoint> = emptyList(),
    /** Catalogue entries of the strength-to-weight exercises, for names. */
    val strengthDefs: Map<String, ExerciseDefinition> = emptyMap(),
    /** Count of the last measurement round saved, for the one-shot message; 0 = none. */
    val roundSaved: Int = 0,
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

internal class BodyPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(BodyRunState())
    val state: StateFlow<BodyRunState> = _state.asStateFlow()

    init {
        scope.launch(io) {
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
            BodyMetricSummary(metric.key, latest, previous?.let { latest.value - it.value })
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
            )
        }
    }

    /**
     * Best % body weight per day for the benchmark exercises. Hangs and
     * pick-ups use the effective load; weighted pull-ups their estimated
     * one-rep max. Each set carries the body weight of its day; older sets
     * without one fall back to today's trend weight.
     */
    private fun strengthPoints(): List<BodyStrengthPoint> {
        val fallbackBw = service.currentBodyweight()
        val zone = TimeZone.currentSystemDefault()
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
                    val day = ProgressStats.dayOf(set.completedAt ?: return@mapNotNull null, zone)
                    BodyStrengthPoint(day, slug, percent)
                }
                .groupBy { it.day }
                .map { (_, points) -> points.maxBy { it.percentBodyweight } }
        }.sortedBy { it.day }
    }

    fun setRange(range: BodyChartRange) = _state.update { it.copy(range = range) }

    fun consumeRoundSaved() = _state.update { it.copy(roundSaved = 0) }

    // ── Measurement round ────────────────────────────────────────────

    /** Saves several readings of one day at once (canonical values, upsert per day and metric). */
    fun saveRound(day: LocalDate, values: Map<BodyMetric, Double>) {
        if (values.isEmpty()) return
        scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            val now = nowMs()
            repo.transaction {
                values.forEach { (metric, value) ->
                    repo.saveMeasurement(BodyMeasurement(day.toString(), metric.key, value, metric.unit, now, BodyMeasurement.SOURCE_MANUAL))
                }
            }
            _state.update { it.copy(roundSaved = values.size) }
        }
    }

    /**
     * Saves a reading in canonical units. [original] is the entry being
     * edited: if its day or metric changed, the old row is removed so a
     * correction never leaves a duplicate behind.
     */
    fun save(metricKey: String, day: LocalDate, canonicalValue: Double, unit: String, original: BodyMeasurement?) {
        scope.launch(io) {
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
                        measuredAt = nowMs(),
                        source = BodyMeasurement.SOURCE_MANUAL,
                    ),
                )
            }
        }
    }

    fun delete(entry: BodyMeasurement) {
        scope.launch(io) {
            service.ensureReady()
            service.repo.deleteMeasurement(entry.day, entry.metric)
        }
    }

    fun restore(entry: BodyMeasurement) {
        scope.launch(io) {
            service.ensureReady()
            service.repo.saveMeasurement(entry)
        }
    }

    /** "Hide numbers" (Android's training settings): body-image values stay hidden everywhere. */
    fun setHideNumbers(hide: Boolean) {
        scope.launch(io) {
            service.ensureReady()
            service.repo.updateProfile { it.copy(hideBodyNumbers = hide) }
        }
    }

    fun close() = scope.cancel()

    companion object {
        const val SLUG_MAX_HANG = "finger.max_hang"
        const val SLUG_ONE_ARM_PICKUP = "finger.one_arm_pickup"
        const val SLUG_WEIGHTED_PULL_UP = "pull.weighted_pull_up"
        val STRENGTH_SLUGS = listOf(SLUG_MAX_HANG, SLUG_ONE_ARM_PICKUP, SLUG_WEIGHTED_PULL_UP)

        /** The measurement round's fields (Android `ROUND_METRICS`). */
        val ROUND_METRICS = listOf(
            BodyMetric.WEIGHT, BodyMetric.BODY_FAT, BodyMetric.WAIST, BodyMetric.HIPS, BodyMetric.CHEST,
            BodyMetric.UPPER_ARM, BodyMetric.FOREARM, BodyMetric.THIGH, BodyMetric.CALF, BodyMetric.NECK,
        )

        /** Android `plausible` (BodyScreen.kt): the entry dialog refuses anything outside. */
        fun plausible(metricKey: String, canonical: Double): Boolean = when (BodyMetric.fromKey(metricKey)) {
            BodyMetric.WEIGHT -> canonical in 20.0..300.0
            BodyMetric.BODY_FAT -> canonical in 2.0..70.0
            BodyMetric.HEIGHT -> canonical in 100.0..250.0
            BodyMetric.ARM_SPAN -> canonical in 100.0..270.0
            null -> canonical > 0 && canonical < 10_000
            else -> canonical in 5.0..200.0
        }
    }
}
