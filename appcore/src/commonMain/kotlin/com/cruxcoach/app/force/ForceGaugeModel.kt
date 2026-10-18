package com.cruxcoach.app.force

import com.cruxcoach.app.training.Tx
import com.cruxcoach.app.ui.Subscription
import com.cruxcoach.app.ui.TrText
import com.cruxcoach.athlete.logic.Units
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One point of the live curve: seconds before now (−10…0) and force in the athlete's units. */
class ForcePointUi(val seconds: Double, val value: Double)

/**
 * The force gauge screen (Android `ForceGaugeScreen`). Lives in its own package because the
 * training facades belong to another part of the iOS port; the shape follows the
 * `com.cruxcoach.app.ui` rules (codes, TrText recipes, no enums).
 */
class ForceGaugeState(
    val loading: Boolean,
    val enabled: Boolean,
    /** idle | scanning | connecting | connected | notFound | noPermission | bluetoothOff | failed */
    val statusCode: String,
    val statusText: TrText,
    val lowPower: Boolean,
    /** Current force, or "–". */
    val liveText: TrText,
    /** "peak 32.1 kg" over the curve's window; empty without samples. */
    val peakText: TrText,
    val curve: List<ForcePointUi>,
    /** Top of the curve's y axis, athlete's units (Android: 10 kg or 115 % of the peak). */
    val curveMax: Double,
    /** The peak line's height, athlete's units; negative without samples. */
    val curvePeak: Double,
    val curveDescription: TrText,
    /** left | right | both */
    val handCode: String,
    val edgeMm: Int,
    val edgeText: TrText,
    /** idle | countdown | pulling | done */
    val phaseCode: String,
    val countdownText: TrText,
    val pullingText: TrText,
    val pullTitle: TrText,
    /** done: a result arrived (else "no data"). */
    val hasResult: Boolean,
    val resultPeak: TrText,
    /** "Best 5 s: …" or "shorter than 5 s". */
    val resultMean: TrText,
    /** "% of body weight"; empty without a body weight or a mean. */
    val resultPercent: TrText,
    val canSave: Boolean,
    val saved: Boolean,
)

class ForceGaugeModel internal constructor(private val presenter: ForceGaugePresenter) {
    val currentState: ForceGaugeState get() = map(presenter.state.value)

    fun watch(onState: (ForceGaugeState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun connect() = presenter.connect()
    fun disconnect() = presenter.disconnect()
    fun enable() = presenter.enable()
    fun tare() = presenter.tare()

    /** left | right | both; anything else is ignored. */
    fun setHand(code: String) {
        ForceHand.entries.firstOrNull { it.name.lowercase() == code }?.let { presenter.setHand(it) }
    }

    fun setEdge(mm: Int) = presenter.setEdge(mm)
    fun startPull() = presenter.startPull()
    fun cancelPull() = presenter.cancelTest()
    fun save() = presenter.save()
    fun close() = presenter.close()

    val pullSeconds: Int get() = ForceGaugePresenter.PULL_SECONDS

    private var lastGood: ForceGaugeState? = null

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: ForceGaugeRunState): ForceGaugeState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        println("ForceGaugeModel: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(ForceGaugeRunState())
    }

    private fun mapUnsafe(s: ForceGaugeRunState): ForceGaugeState {
        val units = s.units
        val peakKg = s.recent.maxOfOrNull { it.kg }
        val r = s.result
        val bw = s.bodyweightKg
        val mean = r?.bestMeanKg
        val maxKg = maxOf(10.0, (peakKg ?: 0.0) * 1.15)
        return ForceGaugeState(
            loading = s.loading,
            enabled = s.enabled,
            statusCode = statusCode(s.status),
            statusText = statusText(s),
            lowPower = s.lowPower,
            liveText = s.liveKg?.let { Tx.mass(it, units) } ?: Tx.plain("–"),
            peakText = peakKg?.let { Tx.t("trd_force_peak_recent", Tx.x(Tx.mass(it, units))) } ?: Tx.EMPTY,
            curve = curve(s.recent).map { ForcePointUi(it.first, Units.massToDisplay(it.second, units)) },
            curveMax = Units.massToDisplay(maxKg, units),
            curvePeak = peakKg?.let { Units.massToDisplay(it, units) } ?: -1.0,
            curveDescription = Tx.t("trd_force_curve_cd", Tx.x(peakKg?.let { Tx.mass(it, units) } ?: Tx.plain("–"))),
            handCode = s.hand.name.lowercase(),
            edgeMm = s.edgeMm,
            edgeText = Tx.t("trd_force_edge", Tx.i(s.edgeMm)),
            phaseCode = s.phase.name.lowercase(),
            countdownText = Tx.t("trd_force_get_ready", Tx.i(s.countdown)),
            pullingText = Tx.t("trd_force_pull_now", Tx.i(s.secondsLeft)),
            pullTitle = Tx.t("trd_force_pull_title", Tx.i(ForceGaugePresenter.PULL_SECONDS)),
            hasResult = r != null,
            resultPeak = r?.let { Tx.t("trd_force_result_peak", Tx.x(Tx.mass(it.peakKg, units))) } ?: Tx.EMPTY,
            resultMean = when {
                r == null -> Tx.EMPTY
                mean != null -> Tx.t("trd_force_result_mean", Tx.x(Tx.mass(mean, units)))
                else -> Tx.t("trd_force_result_too_short")
            },
            resultPercent = if (bw != null && bw > 0 && mean != null) Tx.t("trd_force_result_pct", Tx.i((mean / bw * 100).roundToInt())) else Tx.EMPTY,
            canSave = mean != null && !s.saved,
            saved = s.saved,
        )
    }

    private fun statusText(s: ForceGaugeRunState): TrText = when (s.status) {
        ProgressorStatus.IDLE -> Tx.t("trd_force_status_idle")
        ProgressorStatus.SCANNING -> Tx.t("trd_force_status_scanning")
        ProgressorStatus.CONNECTING -> Tx.t("trd_force_status_connecting", Tx.s(s.deviceName ?: ""))
        ProgressorStatus.CONNECTED -> {
            val battery = s.batteryMv?.let { Tx.t("trd_force_battery", Tx.n(it / 1000.0, 2)) }
            Tx.join(listOfNotNull(Tx.t("trd_force_status_connected", Tx.s(s.deviceName ?: "")), battery))
        }
        ProgressorStatus.NOT_FOUND -> Tx.t("trd_force_status_not_found")
        ProgressorStatus.NO_PERMISSION -> Tx.t("trd_force_status_permission")
        ProgressorStatus.BLUETOOTH_OFF -> Tx.t("trd_force_status_bluetooth_off")
        ProgressorStatus.FAILED -> Tx.t("trd_force_status_failed")
    }

    internal companion object {
        /** At most this many points reach the chart; ~80 Hz over 10 s would be 800 per redraw. */
        const val MAX_CURVE_POINTS = 200

        fun statusCode(s: ProgressorStatus): String = when (s) {
            ProgressorStatus.IDLE -> "idle"
            ProgressorStatus.SCANNING -> "scanning"
            ProgressorStatus.CONNECTING -> "connecting"
            ProgressorStatus.CONNECTED -> "connected"
            ProgressorStatus.NOT_FOUND -> "notFound"
            ProgressorStatus.NO_PERMISSION -> "noPermission"
            ProgressorStatus.BLUETOOTH_OFF -> "bluetoothOff"
            ProgressorStatus.FAILED -> "failed"
        }

        /** (seconds before the newest sample, kg); thinned evenly, the peak sample always kept. */
        fun curve(samples: List<ForceSample>): List<Pair<Double, Double>> {
            if (samples.size < 2) return emptyList()
            val end = samples.last().micros
            val step = (samples.size + MAX_CURVE_POINTS - 1) / MAX_CURVE_POINTS
            val peakIndex = samples.indices.maxBy { samples[it].kg }
            return samples.indices
                .filter { it % step == 0 || it == peakIndex || it == samples.lastIndex }
                .map { i -> (samples[i].micros - end) / 1_000_000.0 to samples[i].kg }
        }
    }
}

class ForceGaugeSettingsState(val enabled: Boolean)

/** The settings card's switch (Android `ForceGaugeSettingsCard`). */
class ForceGaugeSettingsModel internal constructor(private val presenter: ForceGaugeSettingsPresenter) {
    val currentState: ForceGaugeSettingsState get() = ForceGaugeSettingsState(presenter.enabled.value)

    fun watch(onState: (ForceGaugeSettingsState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.enabled.collect { onState(ForceGaugeSettingsState(it)) } }
        return Subscription { scope.cancel() }
    }

    fun setEnabled(on: Boolean) = presenter.setEnabled(on)
    fun close() = presenter.close()
}
