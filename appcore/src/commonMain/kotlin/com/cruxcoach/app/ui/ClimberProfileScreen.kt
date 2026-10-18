package com.cruxcoach.app.ui

import com.cruxcoach.app.climber.ClimberProfilePresenter
import com.cruxcoach.app.climber.ClimberProfileRunState
import com.cruxcoach.app.climber.PositionPoint
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.logic.Bottleneck
import com.cruxcoach.athlete.logic.BottleneckArea
import com.cruxcoach.athlete.logic.BottleneckReason
import com.cruxcoach.athlete.logic.ClimberProfileData
import com.cruxcoach.athlete.logic.ClimbingLoad
import com.cruxcoach.athlete.logic.GradeStrengthNorms
import com.cruxcoach.athlete.logic.LoadStatus
import com.cruxcoach.athlete.logic.LoadStructure
import com.cruxcoach.athlete.logic.LoadTrend
import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.logic.NormPosition
import com.cruxcoach.athlete.logic.StrengthSource
import com.cruxcoach.athlete.logic.StructureLoad
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** One headline tile: working grade, flash grade, finger strength, pull 1RM. */
class ClimberTileUi(val label: TrText, val value: TrText, val supporting: TrText)

/** One strength value at the grade climbed in its week; oldest first, [opacity] fades the older ones. */
class ClimberPositionPointUi(
    val difficulty: Double,
    val pctBw: Double,
    val isoDate: String,
    /** The athlete's own estimate (hollow dot). */
    val estimated: Boolean,
    val newest: Boolean,
    val opacity: Double,
)

/** The orientation band at one grade (% body weight). */
class ClimberBandUi(val difficulty: Double, val low: Double, val center: Double, val high: Double)

/** An axis tick: a numeric position with its label (a Font grade, or "120 %"). */
class ClimberTickUi(val value: Double, val label: String)

/** "Strength by grade" for fingers or pulling (Android `PositionSection` + `GradePositionChart`). */
class ClimberPositionUi(
    /** finger | pull */
    val metricCode: String,
    val tab: TrText,
    val axis: TrText,
    /** noValues (open the performance values) | noGrade (log climbing) | chart */
    val stateCode: String,
    val emptyText: TrText,
    val points: List<ClimberPositionPointUi>,
    val band: List<ClimberBandUi>,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
    val gradeTicks: List<ClimberTickUi>,
    val valueTicks: List<ClimberTickUi>,
    /** The callout at the newest point: "you: 125 % · 6C". */
    val youLabel: TrText,
    val chartDescription: TrText,
    /** Where the newest value sits against the band; empty without a reading. */
    val reading: TrText,
    val showEstimatedNote: Boolean,
)

/** One row of an aligned timeline (Android `TimelineRow`): its own value range, the shared date axis. */
class ClimberTimelineRowUi(
    /** grade | finger | left | right */
    val code: String,
    val label: TrText,
    /** The newest value, formatted ("6C", "125 %"). */
    val latest: String,
    val points: List<ProgressPointUi>,
    val minY: Double,
    val maxY: Double,
    val minLabel: String,
    val maxLabel: String,
)

/** One mark of the flash–working–hardest gauge. */
class ClimberGapMarkUi(
    /** flash | working | max */
    val code: String,
    val title: TrText,
    val difficulty: Double,
    val grade: String,
)

/** One day of a structure's load chart, with the 7-day and 28-day means. */
class ClimberLoadDayUi(val isoDate: String, val value: Double, val acute: Double, val chronic: Double)

/** Load of one structure (Android `LoadSection` + `LoadTrendChart`). */
class ClimberLoadUi(
    /** finger | skin | shoulder */
    val code: String,
    /** "Fingers · normal" */
    val chip: TrText,
    val days: List<ClimberLoadDayUi>,
    val maxY: Double,
    val chartDescription: TrText,
    val explanation: TrText,
)

/** One area of the bottleneck profile; without [hasScore] the reason stands in for the bar. */
class ClimberBottleneckUi(
    /** finger | pull | power | endurance | core */
    val code: String,
    val label: TrText,
    val hasScore: Boolean,
    /** −1 … +1 against grade-typical (negative = weaker). */
    val score: Double,
    val reason: TrText,
)

class ClimberProfileScreenState(
    val loading: Boolean,
    /** The data could not be read (iOS only; Android has no such state). */
    val failed: Boolean,
    /** No grade and no strength value at all: the empty card with its two actions. */
    val isEmpty: Boolean,
    /** Working, flash, finger, pull. */
    val tiles: List<ClimberTileUi>,
    /** Finger, then pull. */
    val positions: List<ClimberPositionUi>,
    val timelineEmpty: Boolean,
    val timeline: List<ClimberTimelineRowUi>,
    /** The shared date axis (ISO days); equal when every value falls on one day. */
    val timelineStart: String,
    val timelineEnd: String,
    val timelineDescription: TrText,
    val hasCorrelation: Boolean,
    /** "r = 0.62 over 8 weeks"; empty without a correlation. */
    val correlationValue: TrText,
    /** The reading of r, or how many weeks are still missing. */
    val correlationText: TrText,
    val hasGap: Boolean,
    val gapMarks: List<ClimberGapMarkUi>,
    val gapLo: Double,
    val gapHi: Double,
    val gapTicks: List<ClimberTickUi>,
    /** Whether the flash–working stretch is drawn (working above flash). */
    val gapBand: Boolean,
    val gapFlash: Double,
    val gapWorking: Double,
    val gapDescription: TrText,
    /** The plural reading of the gap; empty without a flash level. */
    val gapText: TrText,
    val attempts: TrText,
    val topAngle: String,
    val brand: String,
    val hasPickup: Boolean,
    val pickup: List<ClimberTimelineRowUi>,
    val pickupStart: String,
    val pickupEnd: String,
    /** Balanced or "the left hand is 12 % stronger"; empty without both hands. */
    val pickupBalance: TrText,
    val hasLoad: Boolean,
    /** Finger, skin, shoulder. */
    val loads: List<ClimberLoadUi>,
    val bottlenecks: List<ClimberBottleneckUi>,
)

/**
 * The climber profile (Android `ClimberProfileScreen`): headline tiles; strength by grade (fingers
 * and pulling) against the orientation band with the own path over time; working grade and finger
 * strength on one timeline with their correlation; flash, working grade and hardest send; one-arm
 * pick-up per hand; load per structure; strengths and bottlenecks. Orientation, never a target.
 */
class ClimberProfileScreenModel internal constructor(
    private val presenter: ClimberProfilePresenter,
) {
    val currentState: ClimberProfileScreenState get() = map(presenter.state.value)

    fun watch(onState: (ClimberProfileScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    /** Android reloads when the screen is entered again (after the values or a climbing day). */
    fun reload() = presenter.reload()

    fun close() = presenter.close()

    private var lastGood: ClimberProfileScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: ClimberProfileRunState): ClimberProfileScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(ClimberProfileRunState(loading = false, failed = true))
    }

    private fun mapUnsafe(s: ClimberProfileRunState): ClimberProfileScreenState {
        val p = s.profile ?: return empty(s.loading, s.failed)
        val timelineRows = timelineRows(p)
        val (tStart, tEnd) = axis(timelineRows)
        val pickupRows = pickupRows(p)
        val (pStart, pEnd) = axis(pickupRows)
        val c = p.correlation
        val r = c.r
        val sum = p.summary
        val gapValues = listOfNotNull(sum.flashDifficulty, sum.workingDifficulty, sum.maxDifficulty)
        val gapLo = if (gapValues.isEmpty()) 0.0 else floor(gapValues.min() - 1.0)
        val gapHi = if (gapValues.isEmpty()) 0.0 else ceil(gapValues.max() + 1.0).let { if (it - gapLo < 4) gapLo + 4 else it }
        val loads = s.load?.let { loadUis(it) }.orEmpty()
        return ClimberProfileScreenState(
            loading = false,
            failed = false,
            isEmpty = p.isEmpty,
            tiles = tiles(p),
            positions = listOf(
                position(p, s.fingerPoints, GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM),
                position(p, s.pullPoints, GradeStrengthNorms.Metric.PULL_UP_1RM),
            ),
            timelineEmpty = p.gradeTimeline.isEmpty() && p.finger.isEmpty(),
            timeline = timelineRows,
            timelineStart = tStart,
            timelineEnd = tEnd,
            timelineDescription = Tx.t("trl_timeline_cd", Tx.i(p.gradeTimeline.size), Tx.i(p.finger.size)),
            hasCorrelation = r != null,
            correlationValue = if (r == null) Tx.EMPTY else Tx.t("trl_corr_value", Tx.n(r, 2), Tx.i(c.pairedWeeks)),
            correlationText = if (r == null) Tx.t("trl_corr_waiting", Tx.i(c.missingWeeks), Tx.i(c.pairedWeeks))
                else Tx.t(correlationKey(r)),
            hasGap = sum.workingDifficulty != null,
            gapMarks = listOfNotNull(
                sum.flashDifficulty?.let { ClimberGapMarkUi("flash", Tx.t("trl_gap_flash"), it, fontLabel(it)) },
                sum.workingDifficulty?.let { ClimberGapMarkUi("working", Tx.t("trl_gap_working"), it, fontLabel(it)) },
                sum.maxDifficulty?.let { ClimberGapMarkUi("max", Tx.t("trl_gap_max"), it, fontLabel(it)) },
            ),
            gapLo = gapLo,
            gapHi = gapHi,
            gapTicks = gapTicks(gapLo, gapHi, gapValues.isNotEmpty()),
            gapBand = sum.flashDifficulty != null && sum.workingDifficulty != null && sum.workingDifficulty!! > sum.flashDifficulty!!,
            gapFlash = sum.flashDifficulty ?: 0.0,
            gapWorking = sum.workingDifficulty ?: 0.0,
            gapDescription = Tx.t("trl_gap_cd", Tx.s(sum.flashDifficulty?.let(::fontLabel) ?: DASH),
                Tx.s(sum.workingDifficulty?.let(::fontLabel) ?: DASH)),
            gapText = p.flashGap?.let { gap ->
                val steps = gap.roundToInt()
                Tx.p(when {
                    gap <= 1.0 -> "trl_gap_small"
                    gap >= 5.0 -> "trl_gap_large"
                    else -> "trl_gap_normal"
                }, steps, Tx.i(steps))
            } ?: Tx.EMPTY,
            attempts = p.attemptsPerSend?.let { TrText("", listOf(Tx.n(it, 1)), -1, "") } ?: Tx.plain(DASH),
            topAngle = p.angleDistribution.maxByOrNull { it.value }?.key?.let { "$it°" } ?: DASH,
            brand = sum.primaryBrand?.replaceFirstChar { it.uppercase() } ?: "",
            hasPickup = p.pickupLeft.isNotEmpty() || p.pickupRight.isNotEmpty(),
            pickup = pickupRows,
            pickupStart = pStart,
            pickupEnd = pEnd,
            pickupBalance = pickupBalance(p),
            hasLoad = loads.isNotEmpty(),
            loads = loads,
            bottlenecks = p.bottlenecks.map { b ->
                ClimberBottleneckUi(areaCode(b.area), Tx.t(areaKey(b.area)), b.score != null, b.score ?: 0.0, Tx.t(reasonKey(b)))
            },
        )
    }

    private fun empty(loading: Boolean, failed: Boolean) = ClimberProfileScreenState(
        loading, failed, false, emptyList(), emptyList(), true, emptyList(), "", "", Tx.EMPTY, false, Tx.EMPTY, Tx.EMPTY,
        false, emptyList(), 0.0, 0.0, emptyList(), false, 0.0, 0.0, Tx.EMPTY, Tx.EMPTY, Tx.plain(DASH), DASH, "",
        false, emptyList(), "", "", Tx.EMPTY, false, emptyList(), emptyList(),
    )

    private fun tiles(p: ClimberProfileData): List<ClimberTileUi> {
        val sum = p.summary
        return listOf(
            ClimberTileUi(Tx.t("trl_tile_working"), Tx.plain(sum.workingDifficulty?.let(::fontLabel) ?: DASH), when {
                sum.gradesFromCoach -> Tx.t("trl_tile_from_coach")
                sum.sampleSize > 0 -> Tx.t("trl_tile_working_sub", Tx.i(sum.sampleSize))
                else -> Tx.EMPTY
            }),
            ClimberTileUi(Tx.t("trl_tile_flash"), Tx.plain(sum.flashDifficulty?.let(::fontLabel) ?: DASH),
                sum.maxDifficulty?.let { Tx.t("trl_tile_max_sub", Tx.s(fontLabel(it))) } ?: Tx.EMPTY),
            ClimberTileUi(Tx.t("trl_tile_finger"), Tx.plain(p.finger.lastOrNull()?.let { pct(it.pctBw) } ?: DASH), Tx.t("trl_tile_finger_sub")),
            ClimberTileUi(Tx.t("trl_tile_pull"), Tx.plain(p.pull.lastOrNull()?.let { pct(it.pctBw) } ?: DASH), Tx.t("trl_tile_pull_sub")),
        )
    }

    private fun position(p: ClimberProfileData, points: List<PositionPoint>, metric: GradeStrengthNorms.Metric): ClimberPositionUi {
        val finger = metric == GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM
        val values = if (finger) p.finger else p.pull
        val pos = if (finger) p.fingerPosition else p.pullPosition
        val code = if (finger) "finger" else "pull"
        val tab = Tx.t(if (finger) "trl_tab_finger" else "trl_tab_pull")
        val axis = Tx.t(if (finger) "trl_position_finger_axis" else "trl_position_pull_axis")
        if (points.isEmpty()) {
            val (state, text) = if (values.isEmpty()) "noValues" to (if (finger) "trl_position_no_finger" else "trl_position_no_pull")
                else "noGrade" to "trl_position_no_grade"
            return ClimberPositionUi(code, tab, axis, state, Tx.t(text), emptyList(), emptyList(), 0.0, 0.0, 0.0, 0.0,
                emptyList(), emptyList(), Tx.EMPTY, Tx.EMPTY, Tx.EMPTY, false)
        }
        val chart = PositionChart.of(metric, points)
        val sorted = points.sortedBy { it.day.toEpochDays() }
        val newest = points.maxBy { it.day.toEpochDays() }
        return ClimberPositionUi(
            metricCode = code,
            tab = tab,
            axis = axis,
            stateCode = "chart",
            emptyText = Tx.EMPTY,
            points = sorted.mapIndexed { i, pt ->
                val last = i == sorted.lastIndex
                ClimberPositionPointUi(pt.difficulty, pt.pctBw, pt.day.toString(), pt.estimated, last,
                    if (last) 1.0 else 0.35 + 0.5 * (i + 1) / sorted.size)
            },
            band = chart.band,
            xMin = chart.xMin,
            xMax = chart.xMax,
            yMin = chart.yMin,
            yMax = chart.yMax,
            gradeTicks = chart.gradeTicks,
            valueTicks = listOf(chart.yMin, (chart.yMin + chart.yMax) / 2, chart.yMax).map { ClimberTickUi(it, "${it.roundToInt()} %") },
            youLabel = Tx.t("trl_position_you", Tx.s(pct(newest.pctBw)), Tx.s(fontLabel(newest.difficulty))),
            chartDescription = Tx.t("trl_position_cd", Tx.s(pct(newest.pctBw)), Tx.s(fontLabel(newest.difficulty)), Tx.i(points.size)),
            reading = pos?.let(::positionReading) ?: Tx.EMPTY,
            showEstimatedNote = points.any { it.estimated },
        )
    }

    private fun timelineRows(p: ClimberProfileData): List<ClimberTimelineRowUi> = listOf(
        row("grade", Tx.t("trl_timeline_grade"), p.gradeTimeline.map { it.weekStart to it.difficulty }) { fontLabel(it) },
        row("finger", Tx.t("trl_timeline_finger"), p.finger.filter { it.source != StrengthSource.ESTIMATE }.map { it.day to it.pctBw }) { pct(it) },
    )

    // A hand without values (e.g. the injured one) gets no empty row.
    private fun pickupRows(p: ClimberProfileData): List<ClimberTimelineRowUi> = listOfNotNull(
        row("left", Tx.t("tr_side_left"), p.pickupLeft.map { it.day to it.pctBw }) { pct(it) }.takeIf { p.pickupLeft.isNotEmpty() },
        row("right", Tx.t("tr_side_right"), p.pickupRight.map { it.day to it.pctBw }) { pct(it) }.takeIf { p.pickupRight.isNotEmpty() },
    )

    private fun pickupBalance(p: ClimberProfileData): TrText {
        val l = p.pickupLeft.lastOrNull()?.pctBw
        val r = p.pickupRight.lastOrNull()?.pctBw
        if (l == null || r == null || l <= 0 || r <= 0) return Tx.EMPTY
        val diff = (abs(l - r) / maxOf(l, r) * 100).roundToInt()
        return if (diff <= 5) Tx.t("trl_pickup_balanced")
            else Tx.t("trl_pickup_imbalance", Tx.k(if (l > r) "tr_side_left" else "tr_side_right"), Tx.i(diff))
    }

    private fun loadUis(load: LoadStatus): List<ClimberLoadUi> = LoadStructure.entries.mapNotNull { st ->
        val series = load.series[st].orEmpty()
        val leadIn = ClimbingLoad.LEAD_DAYS
        // Android's LoadTrendChart draws nothing without days after the lead-in.
        if (series.size <= leadIn) return@mapNotNull null
        val all = series.map { it.second }
        fun rolling(n: Int) = all.indices.map { i -> all.subList(max(0, i - n + 1), i + 1).average() }.drop(leadIn)
        val acute = rolling(7)
        val chronic = rolling(28)
        val shown = series.drop(leadIn)
        val values = shown.map { it.second }
        val sl = load.structures[st]
        val trend = sl?.trend ?: LoadTrend.LOW
        ClimberLoadUi(
            code = structureCode(st),
            chip = Tx.join(listOf(Tx.t(structureKey(st)), Tx.t(trendKey(trend)))),
            days = shown.mapIndexed { i, (day, v) -> ClimberLoadDayUi(day.toString(), v, acute[i], chronic[i]) },
            maxY = (values + acute + chronic).maxOrNull()?.takeIf { it > 0 } ?: 1.0,
            chartDescription = Tx.t("trl_load_cd", Tx.k(structureKey(st)), Tx.k(trendKey(trend))),
            explanation = sl?.let(::trendExplanation) ?: Tx.EMPTY,
        )
    }

    internal companion object {
        const val DASH = "–"

        /** Android `fontLabel` (ProfileCharts.kt). */
        fun fontLabel(difficulty: Double): String = LogbookSummaries.fontOf(difficulty)?.uppercase() ?: DASH

        fun pct(v: Double): String = "${v.roundToInt()} %"

        /** Android `AlignedTimeline` row: its value range padded by 15 % (at least 0.5). */
        fun row(code: String, label: TrText, points: List<Pair<LocalDate, Double>>, format: (Double) -> String): ClimberTimelineRowUi {
            val sorted = points.sortedBy { it.first.toEpochDays() }
            val values = sorted.map { it.second }
            if (values.isEmpty()) return ClimberTimelineRowUi(code, label, "", emptyList(), 0.0, 1.0, "", "")
            val pad = ((values.max() - values.min()) * 0.15).coerceAtLeast(0.5)
            val minY = values.min() - pad
            val maxY = values.max() + pad
            return ClimberTimelineRowUi(code, label, format(sorted.last().second),
                sorted.map { (d, v) -> ProgressPointUi(d.toString(), d.toEpochDays(), v) },
                minY, maxY, format(minY), format(maxY))
        }

        /** The shared date axis of aligned rows: the first and the last day (Swift widens one day to two). */
        fun axis(rows: List<ClimberTimelineRowUi>): Pair<String, String> {
            val all = rows.flatMap { it.points }
            if (all.isEmpty()) return "" to ""
            return all.minBy { it.epochDay }.isoDate to all.maxBy { it.epochDay }.isoDate
        }

        fun gapTicks(lo: Double, hi: Double, any: Boolean): List<ClimberTickUi> {
            if (!any) return emptyList()
            val out = mutableListOf<ClimberTickUi>()
            var d = lo
            while (d <= hi + 1e-6) {
                out += ClimberTickUi(d, fontLabel(d))
                d += if (hi - lo > 8) 2.0 else 1.0
            }
            return out
        }

        fun correlationKey(r: Double): String = when {
            r >= 0.7 -> "trl_corr_strong"
            r >= 0.4 -> "trl_corr_clear"
            r >= 0.2 -> "trl_corr_weak"
            r > -0.2 -> "trl_corr_none"
            else -> "trl_corr_negative"
        }

        fun positionReading(pos: NormPosition): TrText {
            val typical = Tx.s(fontLabel(pos.typicalDifficulty))
            val value = Tx.s(pct(pos.latestPctBw))
            val subject = Tx.k(if (pos.metric == GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM) "trl_subject_finger" else "trl_subject_pull")
            val working = pos.workingDifficulty ?: return Tx.t("trl_reading_no_grade", subject, value, typical)
            val climb = Tx.s(fontLabel(working))
            return when (pos.position) {
                GradeStrengthNorms.Position.BELOW -> Tx.t("trl_reading_below", subject, value, typical, climb)
                GradeStrengthNorms.Position.ABOVE -> Tx.t("trl_reading_above", subject, value, typical, climb)
                else -> Tx.t("trl_reading_within", subject, value, climb)
            }
        }

        fun structureCode(s: LoadStructure) = when (s) {
            LoadStructure.FINGER -> "finger"
            LoadStructure.SKIN -> "skin"
            LoadStructure.SHOULDER -> "shoulder"
        }

        fun structureKey(s: LoadStructure) = when (s) {
            LoadStructure.FINGER -> "trl_structure_finger"
            LoadStructure.SKIN -> "trl_structure_skin"
            LoadStructure.SHOULDER -> "trl_structure_shoulder"
        }

        fun trendKey(t: LoadTrend) = when (t) {
            LoadTrend.LOW -> "trl_trend_low"
            LoadTrend.NORMAL -> "trl_trend_normal"
            LoadTrend.RISING -> "trl_trend_rising"
            LoadTrend.SPIKE -> "trl_trend_spike"
        }

        fun trendExplanation(l: StructureLoad): TrText {
            val ratio = l.ratio
            return when (l.trend) {
                LoadTrend.SPIKE -> Tx.t("trl_trend_spike_text", Tx.i(((ratio ?: 1.5) * 100 - 100).roundToInt()))
                LoadTrend.RISING -> if (ratio != null) Tx.t("trl_trend_rising_text", Tx.i((ratio * 100 - 100).roundToInt()))
                    else Tx.t("trl_trend_new_text")
                LoadTrend.NORMAL -> Tx.t("trl_trend_normal_text")
                LoadTrend.LOW -> Tx.t("trl_trend_low_text")
            }
        }

        fun areaCode(a: BottleneckArea) = when (a) {
            BottleneckArea.FINGER -> "finger"
            BottleneckArea.PULL -> "pull"
            BottleneckArea.POWER -> "power"
            BottleneckArea.ENDURANCE -> "endurance"
            BottleneckArea.CORE -> "core"
        }

        fun areaKey(a: BottleneckArea) = when (a) {
            BottleneckArea.FINGER -> "trl_area_finger"
            BottleneckArea.PULL -> "trl_area_pull"
            BottleneckArea.POWER -> "trl_area_power"
            BottleneckArea.ENDURANCE -> "trl_area_endurance"
            BottleneckArea.CORE -> "trl_area_core"
        }

        fun reasonKey(b: Bottleneck): String = when (b.reason) {
            BottleneckReason.BELOW_TYPICAL -> "trl_reason_below"
            BottleneckReason.WITHIN_TYPICAL -> if (b.area == BottleneckArea.POWER) "trl_reason_gap_normal" else "trl_reason_within"
            BottleneckReason.ABOVE_TYPICAL -> "trl_reason_above"
            BottleneckReason.LARGE_FLASH_GAP -> "trl_reason_large_gap"
            BottleneckReason.SMALL_FLASH_GAP -> "trl_reason_small_gap"
            BottleneckReason.NO_NORM -> "trl_reason_no_norm"
            BottleneckReason.NO_DATA -> when (b.area) {
                BottleneckArea.FINGER -> "trl_reason_no_finger"
                BottleneckArea.PULL -> "trl_reason_no_pull"
                BottleneckArea.POWER -> "trl_reason_no_grade"
                BottleneckArea.ENDURANCE -> "trl_reason_no_endurance"
                BottleneckArea.CORE -> "trl_reason_no_core"
            }
        }
    }
}

/** Android `GradePositionChart`'s window: around the athlete's points, inside the band's range. */
internal class PositionChart private constructor(
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
    val band: List<ClimberBandUi>,
    val gradeTicks: List<ClimberTickUi>,
) {
    companion object {
        fun of(metric: GradeStrengthNorms.Metric, points: List<PositionPoint>): PositionChart {
            // Grade window: around the athlete's points, at least two Font grades wide, inside the band's range.
            val dMin = points.minOfOrNull { it.difficulty } ?: 18.0
            val dMax = points.maxOfOrNull { it.difficulty } ?: 22.0
            val xMin = floor(min(dMin - 2.0, dMax - 6.0)).coerceAtLeast(13.0)
            // Swift traps on an empty range: grades beyond the band's end still get a window.
            val xMax = ceil(max(dMax + 2.0, xMin + 6.0)).coerceAtMost(32.0).let { if (it > xMin) it else xMin + 6.0 }
            val bandAt = { d: Double -> GradeStrengthNorms.band(metric, d) }
            val bandValues = listOfNotNull(bandAt(xMin)?.low, bandAt(xMax)?.high)
            val yValues = points.map { it.pctBw } + bandValues
            val yMin = floor(((yValues.minOrNull() ?: 90.0) - 8.0) / 10.0) * 10.0
            val yMax = ceil(((yValues.maxOrNull() ?: 160.0) + 8.0) / 10.0) * 10.0
            val steps = 40
            val band = (0..steps).map { xMin + (xMax - xMin) * it / steps }
                .mapNotNull { d -> bandAt(d)?.let { ClimberBandUi(d, it.low, it.center, it.high) } }
            val stepEvery = if (xMax - xMin > 10) 2 else 1
            val ticks = mutableListOf<ClimberTickUi>()
            var d = xMin
            var tick = 0
            while (d <= xMax + 1e-6) {
                if (tick % stepEvery == 0) ticks += ClimberTickUi(d, ClimberProfileScreenModel.fontLabel(d))
                d += 1.0
                tick++
            }
            return PositionChart(xMin, xMax, yMin, yMax, if (band.size >= 2) band else emptyList(), ticks)
        }
    }
}
