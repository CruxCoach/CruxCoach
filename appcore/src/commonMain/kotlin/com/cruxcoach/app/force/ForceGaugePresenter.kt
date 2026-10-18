// Port of ForceGaugeViewModel and ForceGaugeSettingsViewModel (androidApp/.../athlete/force/,
// 0e1bad0c6): live curve, a max pull with countdown, peak / best 5 s / % body weight, saved as a
// pick-up test value. Same constants and rules. Platform seams: an injected scope and IO
// dispatcher, the common clock, the curve ticker runs only while connected.
package com.cruxcoach.app.force

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class ForceHand { LEFT, RIGHT, BOTH }

internal enum class PullPhase { IDLE, COUNTDOWN, PULLING, DONE }

internal data class PullResult(val hand: ForceHand, val edgeMm: Int, val peakKg: Double, val bestMeanKg: Double?)

internal data class ForceGaugeRunState(
    val loading: Boolean = true,
    val status: ProgressorStatus = ProgressorStatus.IDLE,
    val deviceName: String? = null,
    val batteryMv: Int? = null,
    val lowPower: Boolean = false,
    val enabled: Boolean = false,
    val units: UnitSystem = UnitSystem.METRIC,
    val bodyweightKg: Double? = null,
    val liveKg: Double? = null,
    /** Last seconds of samples for the curve, unwrapped device time in µs. */
    val recent: List<ForceSample> = emptyList(),
    val hand: ForceHand = ForceHand.BOTH,
    val edgeMm: Int = 20,
    val phase: PullPhase = PullPhase.IDLE,
    val countdown: Int = 0,
    val secondsLeft: Int = 0,
    val result: PullResult? = null,
    val saved: Boolean = false,
)

internal class ForceGaugePresenter(
    private val service: AthleteService,
    private val client: ProgressorClient,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(ForceGaugeRunState())
    val state: StateFlow<ForceGaugeRunState> = _state.asStateFlow()

    // Touched only on [scope] (single-threaded): the client delivers samples there too.
    private val window = ArrayDeque<ForceSample>()
    private val recording = mutableListOf<ForceSample>()
    private var isRecording = false
    private var testJob: Job? = null
    private var tickerJob: Job? = null
    private var streaming = false

    init {
        scope.launch(io) {
            service.ensureReady()
            val profile = service.repo.profile()
            _state.update {
                it.copy(loading = false, enabled = profile.coach.forceGaugeEnabled, units = profile.units, bodyweightKg = service.currentBodyweight())
            }
        }
        scope.launch { client.status.collect { s -> onStatus(s) } }
        scope.launch { client.deviceName.collect { n -> _state.update { it.copy(deviceName = n) } } }
        scope.launch { client.batteryMv.collect { mv -> _state.update { it.copy(batteryMv = mv) } } }
        scope.launch { client.lowPower.collect { low -> _state.update { it.copy(lowPower = low) } } }
        client.onSample = { s ->
            // The device clock (uint32 µs) wraps after ~71 min; start the curve afresh then.
            if (window.isNotEmpty() && s.micros < window.last().micros - 1_000_000_000L) window.clear()
            window.addLast(s)
            while (window.size > 1 && s.micros - window.first().micros > WINDOW_MICROS) window.removeFirst()
            if (isRecording) recording += s
        }
    }

    private fun onStatus(s: ProgressorStatus) {
        _state.update { it.copy(status = s) }
        if (s == ProgressorStatus.CONNECTED && !streaming) {
            streaming = true
            scope.launch { client.startMeasuring() }
            // The curve is redrawn ~15× a second, not per sample (~80 Hz).
            tickerJob?.cancel()
            tickerJob = scope.launch {
                while (isActive) {
                    delay(66)
                    val snapshot = ForceAnalysis.unwrap(window.toList())
                    _state.update { it.copy(liveKg = snapshot.lastOrNull()?.kg, recent = snapshot) }
                }
            }
        }
        if (s != ProgressorStatus.CONNECTED) {
            streaming = false
            tickerJob?.cancel()
            tickerJob = null
        }
    }

    fun connect() = client.connectNearest()

    fun disconnect() {
        cancelTest()
        client.disconnect()
    }

    fun enable() {
        scope.launch(io) {
            service.ensureReady()
            service.repo.updateProfile { it.copy(coach = it.coach.copy(forceGaugeEnabled = true)) }
            _state.update { it.copy(enabled = true) }
        }
    }

    fun tare() {
        scope.launch { client.tare() }
    }

    fun setHand(h: ForceHand) = _state.update { it.copy(hand = h, result = null, saved = false) }

    fun setEdge(mm: Int) = _state.update { it.copy(edgeMm = mm.coerceIn(6, 45), result = null, saved = false) }

    fun startPull() {
        if (_state.value.status != ProgressorStatus.CONNECTED || testJob?.isActive == true) return
        testJob = scope.launch {
            _state.update { it.copy(result = null, saved = false) }
            for (n in COUNTDOWN_SECONDS downTo 1) {
                _state.update { it.copy(phase = PullPhase.COUNTDOWN, countdown = n) }
                delay(1_000)
            }
            recording.clear()
            isRecording = true
            for (left in PULL_SECONDS downTo 1) {
                _state.update { it.copy(phase = PullPhase.PULLING, secondsLeft = left) }
                delay(1_000)
            }
            isRecording = false
            val samples = recording.toList()
            val peak = ForceAnalysis.peak(samples)
            val result = peak?.let { PullResult(_state.value.hand, _state.value.edgeMm, it, ForceAnalysis.bestWindowMean(samples)) }
            _state.update { it.copy(phase = PullPhase.DONE, result = result) }
        }
    }

    fun cancelTest() {
        testJob?.cancel()
        isRecording = false
        _state.update { it.copy(phase = PullPhase.IDLE) }
    }

    /** Saves the best 5-s mean as a test value of the matching pick-up. */
    fun save() {
        val r = _state.value.result ?: return
        val load = r.bestMeanKg ?: return
        if (_state.value.saved) return
        scope.launch(io) {
            service.ensureReady()
            val slug = if (r.hand == ForceHand.BOTH) SLUG_TWO_ARM else SLUG_ONE_ARM
            if (service.catalog[slug] == null) return@launch
            service.saveBenchmark(
                Benchmark(
                    id = service.repo.newId(),
                    exerciseSlug = slug,
                    side = when (r.hand) { ForceHand.LEFT -> Side.LEFT; ForceHand.RIGHT -> Side.RIGHT; ForceHand.BOTH -> null },
                    edgeMm = r.edgeMm.toDouble(),
                    loadKg = (load * 10).roundToInt() / 10.0,
                    durationS = ForceAnalysis.WINDOW_MICROS / 1_000_000.0,
                    bodyweightKg = service.currentBodyweight(),
                    source = BenchmarkSource.TEST,
                    measuredAt = nowMs(),
                    note = "Tindeq Progressor · $PULL_SECONDS s pull",
                ),
            )
            _state.update { it.copy(saved = true) }
        }
    }

    /** The client stops the stream and disconnects on its own scope; this screen's work ends now. */
    fun close() {
        client.onSample = null
        client.shutdown()
        scope.cancel()
    }

    companion object {
        const val PULL_SECONDS = 7
        const val COUNTDOWN_SECONDS = 3
        const val WINDOW_MICROS = 10_000_000L
        const val SLUG_ONE_ARM = "finger.one_arm_pickup"
        const val SLUG_TWO_ARM = "finger.two_arm_pickup"
    }
}

/** Android `ForceGaugeSettingsViewModel`: the switch of the settings card. */
internal class ForceGaugeSettingsPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        scope.launch(io) {
            service.ensureReady()
            service.repo.observeProfile().map { it.coach.forceGaugeEnabled }.collect { on -> _enabled.value = on }
        }
    }

    fun setEnabled(on: Boolean) {
        scope.launch(io) {
            service.ensureReady()
            service.repo.updateProfile { it.copy(coach = it.coach.copy(forceGaugeEnabled = on)) }
        }
    }

    fun close() = scope.cancel()
}
