package com.cruxcoach.app.ui

import com.cruxcoach.app.progress.BodyChartRange
import com.cruxcoach.app.progress.BodyPresenter
import com.cruxcoach.app.progress.BodyRunState
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.logic.WaistlineImport
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.MetricGroup
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** One day of the weight chart, in the athlete's units. */
class BodyWeightPointUi(
    val isoDate: String,
    /** Days since 1970-01-01, for the chart's x axis. */
    val epochDay: Long,
    val raw: Double,
    val trend: Double,
)

/** A metric's latest value (summary) or one entry (recent list). */
class BodyEntryUi(
    val metricKey: String,
    val label: TrText,
    val isoDate: String,
    /** The value in the athlete's units, for editing. */
    val displayValue: Double,
    val value: TrText,
    /** "−0.4 cm vs. previous" or the date only. */
    val supporting: TrText,
    /** "hide numbers" covers this value. */
    val hidden: Boolean,
    /** "from an older version" / "Waistline"; empty for own entries. */
    val badge: TrText,
)

/** A metric the entry dialog and the measurement round offer. */
class BodyMetricOptionUi(
    val key: String,
    val label: TrText,
    /** kg | lb | cm | in | % */
    val unitLabel: String,
    /** The latest value for the round's "last …" line; empty without or when hidden. */
    val lastText: TrText,
    val inRound: Boolean,
)

class BodyStrengthPointUi(val isoDate: String, val epochDay: Long, val slug: String, val percent: Double)

class BodyStrengthSeriesUi(val slug: String, val name: String, val best: TrText)

class BodyScreenState(
    val loading: Boolean,
    /** metric | imperial */
    val unitsCode: String,
    val hideNumbers: Boolean,
    val todayIso: String,
    /** m1 | m3 | m6 | y1 | all */
    val rangeCode: String,
    // ── Weight ──
    val hasWeight: Boolean,
    val trend: TrText,
    /** "slightly falling" … */
    val direction: TrText,
    val rate: TrText,
    val readings: Int,
    val points: List<BodyWeightPointUi>,
    val chartDescription: TrText,
    // ── Measurements ──
    val hasApeIndex: Boolean,
    val apeIndex: TrText,
    /** The metric the ape-index row asks for next: height | arm_span. */
    val apeEnterMetric: String,
    val summaries: List<BodyEntryUi>,
    val recent: List<BodyEntryUi>,
    val metrics: List<BodyMetricOptionUi>,
    // ── Strength to weight ──
    val strength: List<BodyStrengthPointUi>,
    val strengthSeries: List<BodyStrengthSeriesUi>,
    // ── One-shot ──
    /** Values just saved by a measurement round; 0 = no message. */
    val roundSaved: Int,
)

/**
 * The body page (Android `BodyScreen`): weight trend, measurements, ape index, strength to
 * weight, back-dated entries and the measurement round. Values arrive and leave in the
 * athlete's units; the facade converts and refuses implausible ones as Android's dialog does.
 */
class BodyScreenModel internal constructor(
    private val presenter: BodyPresenter,
    private val language: String,
) {
    val currentState: BodyScreenState get() = map(presenter.state.value)

    fun watch(onState: (BodyScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun close() = presenter.close()

    fun setRange(code: String) {
        BodyChartRange.entries.firstOrNull { it.name.lowercase() == code }?.let { presenter.setRange(it) }
    }

    fun setHideNumbers(hide: Boolean) = presenter.setHideNumbers(hide)

    /**
     * Saves [displayValue] (athlete's units) for [metricKey] on [isoDay]. With [originalDay] and
     * [originalMetric] (both non-empty) it corrects that entry. False = refused: unknown metric,
     * a day in the future or not a date, or a value outside Android's plausible range.
     */
    fun save(metricKey: String, isoDay: String, displayValue: Double, originalDay: String, originalMetric: String): Boolean {
        val s = presenter.state.value
        val metric = BodyMetric.fromKey(metricKey) ?: return false
        val day = pastDay(isoDay, s) ?: return false
        val canonical = toCanonical(displayValue, metric.unit, s.profile.units)
        if (!canonical.isFinite() || !BodyPresenter.plausible(metric.key, canonical)) return false
        val original = if (originalDay.isNotEmpty() && originalMetric.isNotEmpty()) findEntry(originalDay, originalMetric, s) else null
        presenter.save(metric.key, day, canonical, metric.unit, original)
        return true
    }

    /**
     * The measurement round: [keys] and [displayValues] pair up (athlete's units). Returns the
     * number saved, or -1 when any value is implausible or the day is not today or earlier
     * (Android disables "Save" then); 0 when nothing was filled in.
     */
    fun saveRound(isoDay: String, keys: List<String>, displayValues: List<Double>): Int {
        val s = presenter.state.value
        val day = pastDay(isoDay, s) ?: return -1
        if (keys.size != displayValues.size) return -1
        val values = LinkedHashMap<BodyMetric, Double>()
        keys.forEachIndexed { i, key ->
            val metric = BodyPresenter.ROUND_METRICS.firstOrNull { it.key == key } ?: return -1
            val canonical = toCanonical(displayValues[i], metric.unit, s.profile.units)
            if (!canonical.isFinite() || !BodyPresenter.plausible(metric.key, canonical)) return -1
            values[metric] = canonical
        }
        if (values.isEmpty()) return 0
        presenter.saveRound(day, values)
        return values.size
    }

    /** Is [displayValue] acceptable for [metricKey]? (the field's error line) */
    fun isPlausible(metricKey: String, displayValue: Double): Boolean {
        val metric = BodyMetric.fromKey(metricKey) ?: return false
        val canonical = toCanonical(displayValue, metric.unit, presenter.state.value.profile.units)
        return canonical.isFinite() && BodyPresenter.plausible(metric.key, canonical)
    }

    fun consumeRoundSaved() = presenter.consumeRoundSaved()

    private var lastDeleted: BodyMeasurement? = null

    /** Deletes the entry; [undoDelete] puts it back exactly (Android's snackbar). */
    fun delete(isoDay: String, metricKey: String) {
        val entry = findEntry(isoDay, metricKey, presenter.state.value) ?: return
        lastDeleted = entry
        presenter.delete(entry)
    }

    fun undoDelete() {
        lastDeleted?.let { presenter.restore(it) }
        lastDeleted = null
    }

    private fun findEntry(day: String, metric: String, s: BodyRunState): BodyMeasurement? =
        s.recent.firstOrNull { it.day == day && it.metric == metric }
            ?: s.summaries.firstOrNull { it.latest.day == day && it.latest.metric == metric }?.latest
            ?: s.weight.firstOrNull { metric == BodyMetric.WEIGHT.key && it.day.toString() == day }?.let { p ->
                BodyMeasurement(day, metric, p.raw, BodyMetric.WEIGHT.unit, 0)
            }

    private fun pastDay(iso: String, s: BodyRunState): LocalDate? {
        val day = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return null
        val today = s.today ?: return day
        return day.takeIf { it <= today }
    }

    private var lastGood: BodyScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: BodyRunState): BodyScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(BodyRunState())
    }

    private fun mapUnsafe(s: BodyRunState): BodyScreenState {
        val units = s.profile.units
        val hide = s.profile.hideBodyNumbers
        val rate = s.weeklyRateKg
        val direction = Tx.t(when {
            rate == null -> "trb_direction_unknown"
            abs(rate) < 0.1 -> "trb_direction_flat"
            rate > 0 -> "trb_direction_up"
            else -> "trb_direction_down"
        })
        val trend = s.weight.lastOrNull()?.trend
        val visible = s.visibleWeight
        val description = if (visible.isEmpty()) Tx.EMPTY else if (hide) {
            Tx.t("trb_chart_description_hidden", Tx.date(visible.first().day.toString()), Tx.date(visible.last().day.toString()), Tx.x(direction))
        } else {
            Tx.t("trb_chart_description", Tx.date(visible.first().day.toString()), Tx.date(visible.last().day.toString()),
                Tx.x(Tx.mass(visible.first().trend, units)), Tx.x(Tx.mass(visible.last().trend, units)))
        }
        val nonWeight = s.summaries.filter { BodyMetric.fromKey(it.metricKey)?.group != MetricGroup.WEIGHT }
        // Android lists the summaries group by group (composition, build, circumferences).
        val grouped = MetricGroup.entries.filter { it != MetricGroup.WEIGHT }.flatMap { group ->
            nonWeight.filter { BodyMetric.fromKey(it.metricKey)?.group == group }
        }
        val hasHeight = s.summaries.any { it.metricKey == BodyMetric.HEIGHT.key }
        val latestByKey = s.summaries.associate { it.metricKey to it.latest }
        val bySlug = s.strength.groupBy { it.slug }
        return BodyScreenState(
            loading = s.loading,
            unitsCode = unitsCode(units),
            hideNumbers = hide,
            todayIso = s.today?.toString() ?: "",
            rangeCode = s.range.name.lowercase(),
            hasWeight = trend != null,
            trend = trend?.let { Tx.mass(it, units) } ?: Tx.EMPTY,
            direction = direction,
            rate = rate?.let { Tx.mass(it, units, signed = true) } ?: Tx.plain("–"),
            readings = s.weight.size,
            points = visible.map { p ->
                BodyWeightPointUi(p.day.toString(), p.day.toEpochDays().toLong(),
                    Units.massToDisplay(p.raw, units), Units.massToDisplay(p.trend, units))
            },
            chartDescription = description,
            hasApeIndex = s.apeIndexCm != null,
            apeIndex = s.apeIndexCm?.let { lengthText(it, units, signed = true) } ?: Tx.EMPTY,
            apeEnterMetric = if (hasHeight) BodyMetric.ARM_SPAN.key else BodyMetric.HEIGHT.key,
            summaries = grouped.map { summary ->
                val latest = summary.latest
                val hidden = isHidden(summary.metricKey, hide)
                val delta = summary.delta?.takeIf { !hidden && abs(it) > 1e-9 }
                entry(latest, units, hidden,
                    if (delta != null) Tx.join(listOf(dateText(latest.day),
                        Tx.t("trb_delta", Tx.x(valueText(delta, latest.unit, units, signed = true)))))
                    else dateText(latest.day))
            },
            recent = s.recent.map { e -> entry(e, units, isHidden(e.metric, hide), dateText(e.day)) },
            metrics = BodyMetric.entries.map { m ->
                val last = latestByKey[m.key]?.takeIf { !isHidden(m.key, hide) }
                BodyMetricOptionUi(
                    key = m.key,
                    label = Tx.t(metricLabelKey(m)),
                    unitLabel = displayUnit(m.unit, units),
                    lastText = last?.let { Tx.t("trb_round_last", Tx.x(valueText(it.value, it.unit, units))) } ?: Tx.EMPTY,
                    inRound = m in BodyPresenter.ROUND_METRICS,
                )
            },
            strength = s.strength.map { BodyStrengthPointUi(it.day.toString(), it.day.toEpochDays().toLong(), it.slug, it.percentBodyweight) },
            strengthSeries = BodyPresenter.STRENGTH_SLUGS.mapNotNull { slug ->
                val points = bySlug[slug] ?: return@mapNotNull null
                BodyStrengthSeriesUi(slug, s.strengthDefs[slug]?.name(language) ?: humanize(slug.substringAfter('.')),
                    Tx.t("trb_strength_best", Tx.i(points.maxOf { it.percentBodyweight }.roundToInt())))
            },
            roundSaved = s.roundSaved,
        )
    }

    private fun entry(m: BodyMeasurement, units: UnitSystem, hidden: Boolean, supporting: TrText) = BodyEntryUi(
        metricKey = m.metric,
        label = BodyMetric.fromKey(m.metric)?.let { Tx.t(metricLabelKey(it)) } ?: Tx.plain(humanize(m.metric)),
        isoDate = m.day,
        displayValue = toDisplay(m.value, m.unit, units),
        value = if (hidden) Tx.t("trb_hidden_value") else valueText(m.value, m.unit, units),
        supporting = supporting,
        hidden = hidden,
        badge = when (m.source) {
            BodyMeasurement.SOURCE_LEGACY -> Tx.t("trb_legacy_badge")
            WaistlineImport.SOURCE -> Tx.t("trb_waistline_badge")
            else -> Tx.EMPTY
        },
    )

    private fun dateText(iso: String): TrText = TrText("", listOf(Tx.date(iso)), -1, "")

    internal companion object {
        fun unitsCode(units: UnitSystem) = if (units == UnitSystem.IMPERIAL) "imperial" else "metric"

        /** With "hide numbers" on, everything body-image related stays hidden; build data (height, span) does not. */
        fun isHidden(metricKey: String, hideNumbers: Boolean): Boolean {
            if (!hideNumbers) return false
            return BodyMetric.fromKey(metricKey)?.group != MetricGroup.ANTHROPOMETRY
        }

        fun humanize(key: String) =
            key.removePrefix(WaistlineImport.CUSTOM_PREFIX).replace('_', ' ').replaceFirstChar { it.uppercase() }

        fun metricLabelKey(m: BodyMetric) = when (m) {
            BodyMetric.WEIGHT -> "tr_metric_weight"
            BodyMetric.BODY_FAT -> "tr_metric_body_fat"
            BodyMetric.HEIGHT -> "tr_metric_height"
            BodyMetric.ARM_SPAN -> "tr_metric_arm_span"
            BodyMetric.FOREARM -> "tr_metric_forearm"
            BodyMetric.UPPER_ARM -> "tr_metric_upper_arm"
            BodyMetric.CHEST -> "tr_metric_chest"
            BodyMetric.WAIST -> "tr_metric_waist"
            BodyMetric.HIPS -> "tr_metric_hips"
            BodyMetric.THIGH -> "tr_metric_thigh"
            BodyMetric.CALF -> "tr_metric_calf"
            BodyMetric.NECK -> "tr_metric_neck"
        }

        fun lengthText(cm: Double, units: UnitSystem, signed: Boolean = false): TrText {
            val v = Units.lengthToDisplay(cm, units)
            return Tx.t(if (units == UnitSystem.IMPERIAL) "tr_format_in" else "tr_format_cm", Tx.n(v, 1, signed))
        }

        /** Android `formatValue` (BodyScreen.kt). */
        fun valueText(value: Double, unit: String, units: UnitSystem, signed: Boolean = false): TrText = when (unit) {
            "kg" -> Tx.mass(value, units, signed)
            "cm" -> lengthText(value, units, signed)
            "%" -> TrText("", listOf(Tx.n(value, 1, signed), Tx.s(" %")), -1, "")
            else -> TrText("", listOf(Tx.n(value, 1, signed), Tx.s(" $unit")), -1, "")
        }

        fun toCanonical(value: Double, unit: String, units: UnitSystem): Double = when (unit) {
            "kg" -> Units.massFromDisplay(value, units)
            "cm" -> Units.lengthFromDisplay(value, units)
            else -> value
        }

        fun toDisplay(value: Double, unit: String, units: UnitSystem): Double = when (unit) {
            "kg" -> Units.massToDisplay(value, units)
            "cm" -> Units.lengthToDisplay(value, units)
            else -> value
        }

        fun displayUnit(unit: String, units: UnitSystem): String = when (unit) {
            "kg" -> Units.massUnit(units)
            "cm" -> Units.lengthUnit(units)
            else -> unit
        }
    }
}
