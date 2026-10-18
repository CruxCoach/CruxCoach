package com.cruxcoach.app.ui

import com.cruxcoach.app.progress.ProgressPresenter
import com.cruxcoach.app.progress.ProgressRange
import com.cruxcoach.app.progress.ProgressRunState
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.CapacityKind
import com.cruxcoach.athlete.logic.LoadStructure
import com.cruxcoach.athlete.logic.LoadTrend
import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.isoDayNumber

/** One week of the days and minutes charts. */
class ProgressWeekUi(
    val weekStartIso: String,
    val epochDay: Long,
    val trainingDays: Int,
    val climbingDays: Int,
    /** Training days without climbing (Android's second bar segment). */
    val offBoardDays: Int,
    val climbingMinutes: Int,
    val workoutMinutes: Int,
)

/** One day of the 16-week heatmap: [level] 0 rest … 3 hard (Android `ProgressStats.calendar`). */
class ProgressCalendarDayUi(val isoDate: String, val epochDay: Long, val isoWeekday: Int, val level: Int)

/** Loading days per week on one structure. */
class ProgressDomainUi(
    /** finger | shoulder | elbow | skin */
    val code: String,
    val label: TrText,
    val days: List<Int>,
    val description: TrText,
)

/** Current load of one structure: "finger · rising". */
class ProgressLoadChipUi(
    val code: String,
    val label: TrText,
    /** low | normal | rising | spike */
    val trendCode: String,
)

class ProgressPointUi(val isoDate: String, val epochDay: Long, val value: Double)

class ProgressSeriesUi(val slug: String, val name: String, val points: List<ProgressPointUi>)

/** One trained exercise: latest capacity, the change in 4 weeks and a sparkline. */
class ProgressExerciseUi(
    val slug: String,
    val name: String,
    val supporting: TrText,
    val spark: List<Double>,
)

class ProgressScreenState(
    val loading: Boolean,
    val unitsCode: String,
    val hideNumbers: Boolean,
    /** w4 | w12 | m6 | y1 */
    val rangeCode: String,
    // ── Hub tiles ──
    val bodyValue: TrText,
    /** No weight yet: the tile asks for one instead of opening the body page. */
    val bodyIsAction: Boolean,
    val weeklyValue: String,
    val benchmarksValue: TrText,
    val benchmarksIsAction: Boolean,
    val historyValue: Int,
    val historySupporting: TrText,
    // ── Climber profile teaser and load ──
    val profileLine: TrText,
    val hasLoad: Boolean,
    val loadChips: List<ProgressLoadChipUi>,
    // ── Training ──
    val hasAnyData: Boolean,
    val trained: Boolean,
    val weeks: List<ProgressWeekUi>,
    val daysChartDescription: TrText,
    val minutesChartDescription: TrText,
    val calendar: List<ProgressCalendarDayUi>,
    val calendarDescription: TrText,
    val domains: List<ProgressDomainUi>,
    // ── Body ──
    /** Trend weight in the range, athlete's units. */
    val weight: List<ProgressPointUi>,
    val lastWeightDisplay: Double,
    val strength: List<ProgressSeriesUi>,
    // ── Exercises ──
    val exercises: List<ProgressExerciseUi>,
)

/**
 * The Progress tab (Android `StatsHubScreen`): the hub tiles, training days and minutes per
 * week, the calendar, load per structure, body weight and strength to weight, and the trained
 * exercises. All numbers come from the ported presenter; Swift draws them with Swift Charts.
 */
class ProgressScreenModel internal constructor(
    private val presenter: ProgressPresenter,
    private val language: String,
) {
    val currentState: ProgressScreenState get() = map(presenter.state.value)

    fun watch(onState: (ProgressScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun close() = presenter.close()

    fun setRange(code: String) {
        RANGES.entries.firstOrNull { it.value == code }?.let { presenter.setRange(it.key) }
    }

    fun reload() = presenter.reload()

    /** [display] in the athlete's units; implausible values are refused (false), as on Today. */
    fun logWeight(display: Double): Boolean {
        val kg = Units.massFromDisplay(display, presenter.state.value.profile.units)
        if (!kg.isFinite() || kg < PLAUSIBLE_MIN_KG || kg > PLAUSIBLE_MAX_KG) return false
        presenter.logWeight(kg)
        return true
    }

    /** The label of a range chip ("4 weeks" …). */
    fun rangeLabel(code: String): TrText = Tx.t(rangeKey(RANGES.entries.firstOrNull { it.value == code }?.key ?: ProgressRange.TWELVE_WEEKS))

    val rangeCodes: List<String> get() = ProgressRange.entries.map { RANGES.getValue(it) }

    private var lastGood: ProgressScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: ProgressRunState): ProgressScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(ProgressRunState())
    }

    private fun mapUnsafe(s: ProgressRunState): ProgressScreenState {
        val units = s.profile.units
        val hide = s.profile.hideBodyNumbers
        val w = s.latestWeightKg
        val finger = s.strength["finger.max_hang"]?.lastOrNull()?.second
        val parts = listOfNotNull(
            s.logbook.workingDifficulty?.let { Tx.t("trl_card_grade", Tx.s(fontLabel(it))) },
            finger?.let { Tx.t("trl_card_finger", Tx.s("${it.roundToInt()} %")) },
        )
        val domains = listOf(LoadDomain.FINGER, LoadDomain.SHOULDER, LoadDomain.ELBOW, LoadDomain.SKIN)
        val totalDays = s.weeks.sumOf { it.trainingDays }
        return ProgressScreenState(
            loading = s.loading,
            unitsCode = BodyScreenModel.unitsCode(units),
            hideNumbers = hide,
            rangeCode = RANGES.getValue(s.range),
            bodyValue = when {
                w == null -> Tx.t("tru_tile_log")
                hide -> Tx.t("trt_numbers_hidden_short")
                else -> Tx.mass(w, units)
            },
            bodyIsAction = w == null,
            weeklyValue = "${s.daysThisWeek} / ${s.profile.weeklyGoal}",
            benchmarksValue = if (s.benchmarkCount > 0) Tx.p("tru_hub_values", s.benchmarkCount, Tx.i(s.benchmarkCount))
                else Tx.t("tru_tile_log"),
            benchmarksIsAction = s.benchmarkCount == 0,
            historyValue = s.trainingDaysInRange,
            historySupporting = Tx.join(listOf(
                Tx.p("tru_hub_history_days", s.trainingDaysInRange, Tx.x(Tx.t(rangeKey(s.range)))),
                Tx.p("tru_hub_records", s.recordsInRange, Tx.i(s.recordsInRange)),
            )),
            profileLine = if (parts.isEmpty()) Tx.t("trl_card_empty") else Tx.join(parts),
            hasLoad = s.load != null,
            loadChips = s.load?.let { load ->
                LoadStructure.entries.map { st ->
                    val trend = load.structures[st]?.trend ?: LoadTrend.LOW
                    ProgressLoadChipUi(st.name.lowercase(), Tx.join(listOf(Tx.t(Tx.structureKey(st)), Tx.t(Tx.trendKey(trend)))),
                        trend.name.lowercase())
                }
            } ?: emptyList(),
            hasAnyData = s.hasAnyData,
            trained = s.weeks.any { it.trainingDays > 0 },
            weeks = s.weeks.map { wk ->
                ProgressWeekUi(wk.weekStart.toString(), wk.weekStart.toEpochDays().toLong(), wk.trainingDays, wk.climbingDays,
                    (wk.trainingDays - wk.climbingDays).coerceAtLeast(0), wk.climbingMinutes, wk.workoutMinutes)
            },
            daysChartDescription = Tx.p("trs_days_chart_cd", totalDays, Tx.i(s.weeks.size), Tx.i(totalDays)),
            minutesChartDescription = Tx.t("trs_minutes_chart_cd", Tx.i(s.weeks.sumOf { it.climbingMinutes + it.workoutMinutes })),
            calendar = s.calendar.entries.sortedBy { it.key }.map { (day, level) ->
                ProgressCalendarDayUi(day.toString(), day.toEpochDays().toLong(), day.dayOfWeek.isoDayNumber, level)
            },
            calendarDescription = Tx.t("trs_calendar_cd", Tx.i(s.calendar.count { it.value > 0 })),
            domains = domains.map { d ->
                ProgressDomainUi(
                    code = d.name.lowercase(),
                    label = Tx.t(Tx.domainKey(d)),
                    days = s.domainWeeks.map { it.days[d] ?: 0 },
                    description = Tx.t("trs_load_cd", Tx.k(Tx.domainKey(d)), Tx.i(s.domainWeeks.lastOrNull()?.days?.get(d) ?: 0)),
                )
            },
            weight = s.weight.map { p -> ProgressPointUi(p.day.toString(), p.day.toEpochDays().toLong(), Units.massToDisplay(p.trend, units)) },
            lastWeightDisplay = w?.let { Units.massToDisplay(it, units) } ?: 0.0,
            strength = s.strength.entries.map { (slug, points) ->
                ProgressSeriesUi(slug, s.strengthDefs[slug]?.name(language) ?: slug,
                    points.map { (day, v) -> ProgressPointUi(day.toString(), day.toEpochDays().toLong(), v) })
            },
            exercises = s.exercises.map { row ->
                val supporting = listOfNotNull(
                    row.last?.let { Tx.join(listOf(Tx.t(capacityLabelKey(row.kind)), capacityText(row.def, row.kind, it, units)), ": ") },
                    row.change?.let { Tx.t("trs_change_28", Tx.x(changeText(row.def, row.kind, it, units))) },
                )
                ProgressExerciseUi(
                    slug = row.def.slug,
                    name = row.def.name(language),
                    supporting = if (supporting.isEmpty()) Tx.t("trs_no_value") else Tx.join(supporting),
                    spark = row.spark,
                )
            },
        )
    }

    internal companion object {
        /** TodayScreenModel's bounds for a weigh-in. */
        const val PLAUSIBLE_MIN_KG = 20.0
        const val PLAUSIBLE_MAX_KG = 300.0

        val RANGES = mapOf(
            ProgressRange.FOUR_WEEKS to "w4", ProgressRange.TWELVE_WEEKS to "w12",
            ProgressRange.SIX_MONTHS to "m6", ProgressRange.YEAR to "y1",
        )

        fun rangeKey(r: ProgressRange) = when (r) {
            ProgressRange.FOUR_WEEKS -> "trs_range_4w"
            ProgressRange.TWELVE_WEEKS -> "trs_range_12w"
            ProgressRange.SIX_MONTHS -> "trs_range_6m"
            ProgressRange.YEAR -> "trs_range_1y"
        }

        /** Android `fontLabel` (ProfileCharts.kt). */
        fun fontLabel(difficulty: Double): String = LogbookSummaries.fontOf(difficulty)?.uppercase() ?: "–"

        /** What the capacity number of an exercise means (Android `capacityLabel`). */
        fun capacityLabelKey(kind: CapacityKind?) = when (kind) {
            CapacityKind.E1RM_TOTAL -> "trs_kind_e1rm"
            CapacityKind.TEN_SECOND_MAX -> "trs_kind_ten_second"
            CapacityKind.MAX_REPS -> "trs_kind_reps"
            CapacityKind.MAX_SECONDS -> "trs_kind_seconds"
            null -> "trs_kind_volume"
        }

        /** A display value (see ProgressStats.displayValue) in the athlete's units (Android `capacityText`). */
        fun capacityText(def: ExerciseDefinition, kind: CapacityKind?, value: Double, units: UnitSystem): TrText = when (kind) {
            CapacityKind.E1RM_TOTAL, CapacityKind.TEN_SECOND_MAX ->
                if (def.load == LoadMode.BODYWEIGHT_PLUS) Tx.mass(value, units, signed = true) else Tx.mass(value, units)
            CapacityKind.MAX_REPS -> value.roundToInt().let { Tx.p("trs_reps", it, Tx.i(it)) }
            CapacityKind.MAX_SECONDS -> Tx.t("tr_format_seconds", Tx.i(value.roundToInt()))
            null -> Tx.plain(value.roundToInt().toString())
        }

        /** "↑ +2.5 kg" / "↓ −1" — change against the session four weeks earlier (Android `changeText`). */
        fun changeText(def: ExerciseDefinition, kind: CapacityKind?, change: Double, units: UnitSystem): TrText {
            val arrow = when {
                abs(change) < 1e-6 -> "→"
                change > 0 -> "↑"
                else -> "↓"
            }
            val amount = when (kind) {
                CapacityKind.E1RM_TOTAL, CapacityKind.TEN_SECOND_MAX -> Tx.mass(change, units, signed = true)
                CapacityKind.MAX_REPS -> Tx.plain((if (change > 0) "+" else "") + change.roundToInt())
                CapacityKind.MAX_SECONDS -> TrText("", listOf(Tx.s(if (change > 0) "+" else ""),
                    Tx.x(Tx.t("tr_format_seconds", Tx.i(change.roundToInt())))), -1, "")
                null -> Tx.plain(change.roundToInt().toString())
            }
            return Tx.join(listOf(Tx.plain(arrow), amount), " ")
        }
    }
}
