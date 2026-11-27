package com.cruxcoach.android.athlete.force

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ble.BlePermissionHelper
import com.cruxcoach.android.ui.training.TrainingScaffold
import com.cruxcoach.android.ui.training.formatMass
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

enum class ForceHand { LEFT, RIGHT, BOTH }

enum class PullPhase { IDLE, COUNTDOWN, PULLING, DONE }

data class PullResult(val hand: ForceHand, val edgeMm: Int, val peakKg: Double, val bestMeanKg: Double?)

data class ForceGaugeState(
    val status: ProgressorStatus = ProgressorStatus.IDLE,
    val deviceName: String? = null,
    val batteryMv: Int? = null,
    val lowPower: Boolean = false,
    val enabled: Boolean = false,
    val units: UnitSystem = UnitSystem.METRIC,
    val bodyweightKg: Double? = null,
    val liveKg: Double? = null,
    /** Last seconds of samples for the curve, time in µs since the window start. */
    val recent: List<ForceSample> = emptyList(),
    val hand: ForceHand = ForceHand.BOTH,
    val edgeMm: Int = 20,
    val phase: PullPhase = PullPhase.IDLE,
    val countdown: Int = 0,
    val secondsLeft: Int = 0,
    val result: PullResult? = null,
    val saved: Boolean = false,
)

/**
 * Experimental force measurement with a Tindeq Progressor (FEAT-071): live
 * curve, and a max pull of [PULL_SECONDS] s for the left hand, the right hand
 * or both on a chosen edge. The best 5-s mean becomes a test value of the
 * one- or two-arm pick-up, which feeds the prescriptions like any other
 * performance value. The device is set up like a pick-up: anchored below,
 * the athlete pulls up on the edge.
 */
@HiltViewModel
class ForceGaugeViewModel @Inject constructor(
    app: Application,
    private val service: AthleteService,
) : AndroidViewModel(app) {

    private val client = ProgressorClient(app)
    private val _state = MutableStateFlow(ForceGaugeState())
    val state: StateFlow<ForceGaugeState> = _state.asStateFlow()

    private val window = ArrayDeque<ForceSample>()
    private val recording = mutableListOf<ForceSample>()
    @Volatile private var isRecording = false
    private var testJob: Job? = null
    private var streaming = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val profile = service.repo.profile()
            _state.update { it.copy(enabled = profile.coach.forceGaugeEnabled, units = profile.units, bodyweightKg = service.currentBodyweight()) }
        }
        viewModelScope.launch { client.status.collect { s -> onStatus(s) } }
        viewModelScope.launch { client.deviceName.collect { n -> _state.update { it.copy(deviceName = n) } } }
        viewModelScope.launch { client.batteryMv.collect { mv -> _state.update { it.copy(batteryMv = mv) } } }
        viewModelScope.launch { client.lowPower.collect { low -> _state.update { it.copy(lowPower = low) } } }
        viewModelScope.launch {
            client.samples.collect { s ->
                // The device clock (uint32 µs) wraps after ~71 min; start the curve afresh then.
                if (window.isNotEmpty() && s.micros < window.last().micros - 1_000_000_000L) window.clear()
                window.addLast(s)
                while (window.size > 1 && s.micros - window.first().micros > WINDOW_MICROS) window.removeFirst()
                if (isRecording) recording += s
            }
        }
        // The curve is redrawn ~15× a second, not per sample (~80 Hz).
        viewModelScope.launch {
            while (isActive) {
                delay(66)
                val snapshot = ForceAnalysis.unwrap(window.toList())
                _state.update { it.copy(liveKg = snapshot.lastOrNull()?.kg, recent = snapshot) }
            }
        }
    }

    private fun onStatus(s: ProgressorStatus) {
        _state.update { it.copy(status = s) }
        if (s == ProgressorStatus.CONNECTED && !streaming) {
            streaming = true
            viewModelScope.launch { client.startMeasuring() }
        }
        if (s != ProgressorStatus.CONNECTED) streaming = false
    }

    fun hasPermissions(): Boolean = client.hasPermissions()

    fun connect() = client.connectNearest()

    fun disconnect() {
        cancelTest()
        client.disconnect()
    }

    fun enable() = viewModelScope.launch(Dispatchers.IO) {
        service.repo.updateProfile { it.copy(coach = it.coach.copy(forceGaugeEnabled = true)) }
        _state.update { it.copy(enabled = true) }
    }

    fun tare() = viewModelScope.launch { client.tare() }

    fun setHand(h: ForceHand) = _state.update { it.copy(hand = h, result = null, saved = false) }

    fun setEdge(mm: Int) = _state.update { it.copy(edgeMm = mm.coerceIn(6, 45), result = null, saved = false) }

    fun startPull() {
        if (_state.value.status != ProgressorStatus.CONNECTED || testJob?.isActive == true) return
        testJob = viewModelScope.launch {
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
        viewModelScope.launch(Dispatchers.IO) {
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
                    measuredAt = System.currentTimeMillis(),
                    note = "Tindeq Progressor · ${PULL_SECONDS} s pull",
                ),
            )
            _state.update { it.copy(saved = true) }
        }
    }

    override fun onCleared() {
        // The client stops the stream and disconnects on its own scope; the screen is gone already.
        client.shutdown()
        super.onCleared()
    }

    companion object {
        const val PULL_SECONDS = 7
        const val COUNTDOWN_SECONDS = 3
        const val WINDOW_MICROS = 10_000_000L
        const val SLUG_ONE_ARM = "finger.one_arm_pickup"
        const val SLUG_TWO_ARM = "finger.two_arm_pickup"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ForceGaugeScreen(onBack: () -> Unit, viewModel: ForceGaugeViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) viewModel.connect()
    }
    fun connect() {
        if (viewModel.hasPermissions()) viewModel.connect()
        else permissionLauncher.launch(BlePermissionHelper.getRequiredPermissions())
    }

    TrainingScaffold(title = stringResource(R.string.trd_force_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag("force_gauge"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text(stringResource(R.string.trd_force_experimental), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp))
            }
            if (!s.enabled) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.trd_force_enable_hint), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { viewModel.enable() }, modifier = Modifier.testTag("force_enable")) {
                            Text(stringResource(R.string.trd_force_enable))
                        }
                    }
                }
                return@Column
            }

            ConnectionCard(s, onConnect = ::connect, onDisconnect = viewModel::disconnect, onTare = { viewModel.tare() })

            if (s.status == ProgressorStatus.CONNECTED) {
                LiveCard(s)
                Text(stringResource(R.string.trd_force_setup_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.trd_force_hand), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForceHand.entries.forEach { h ->
                        FilterChip(selected = s.hand == h, onClick = { viewModel.setHand(h) }, enabled = s.phase != PullPhase.PULLING,
                            label = { Text(handLabel(h)) }, modifier = Modifier.testTag("force_hand_${h.name.lowercase()}"))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.trd_force_edge, s.edgeMm), modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { viewModel.setEdge(s.edgeMm - 1) }, modifier = Modifier.testTag("force_edge_minus")) { Text("−") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { viewModel.setEdge(s.edgeMm + 1) }, modifier = Modifier.testTag("force_edge_plus")) { Text("+") }
                }
                PullCard(s, onStart = viewModel::startPull, onCancel = viewModel::cancelTest, onSave = viewModel::save)
            }
        }
    }
}

@Composable
private fun ConnectionCard(s: ForceGaugeState, onConnect: () -> Unit, onDisconnect: () -> Unit, onTare: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("force_connection")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(statusText(s), style = MaterialTheme.typography.titleSmall)
            if (s.lowPower) Text(stringResource(R.string.trd_force_low_power), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s.status) {
                    ProgressorStatus.CONNECTED -> {
                        OutlinedButton(onClick = onTare, modifier = Modifier.testTag("force_tare")) { Text(stringResource(R.string.trd_force_tare)) }
                        TextButton(onClick = onDisconnect) { Text(stringResource(R.string.trd_force_disconnect)) }
                    }
                    ProgressorStatus.SCANNING, ProgressorStatus.CONNECTING -> CircularProgressIndicator(Modifier.size(24.dp))
                    else -> Button(onClick = onConnect, modifier = Modifier.testTag("force_connect")) {
                        Text(stringResource(R.string.trd_force_connect))
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(s: ForceGaugeState): String = when (s.status) {
    ProgressorStatus.IDLE -> stringResource(R.string.trd_force_status_idle)
    ProgressorStatus.SCANNING -> stringResource(R.string.trd_force_status_scanning)
    ProgressorStatus.CONNECTING -> stringResource(R.string.trd_force_status_connecting, s.deviceName ?: "")
    ProgressorStatus.CONNECTED -> {
        val battery = s.batteryMv?.let { stringResource(R.string.trd_force_battery, formatNumber(it / 1000.0, 2)) }
        listOfNotNull(stringResource(R.string.trd_force_status_connected, s.deviceName ?: ""), battery).joinToString(" · ")
    }
    ProgressorStatus.NOT_FOUND -> stringResource(R.string.trd_force_status_not_found)
    ProgressorStatus.NO_PERMISSION -> stringResource(R.string.trd_force_status_permission)
    ProgressorStatus.BLUETOOTH_OFF -> stringResource(R.string.trd_force_status_bluetooth_off)
    ProgressorStatus.FAILED -> stringResource(R.string.trd_force_status_failed)
}

@Composable
private fun LiveCard(s: ForceGaugeState) {
    val peak = s.recent.maxOfOrNull { it.kg }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(s.liveKg?.let { formatMass(it, s.units) } ?: "–", style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("force_live"))
                Spacer(Modifier.width(12.dp))
                peak?.let {
                    Text(stringResource(R.string.trd_force_peak_recent, formatMass(it, s.units)), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ForceCurve(s.recent, Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp),
                stringResource(R.string.trd_force_curve_cd, peak?.let { formatMass(it, s.units) } ?: "–"))
        }
    }
}

@Composable
private fun ForceCurve(samples: List<ForceSample>, modifier: Modifier, description: String) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val peakColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.semantics { contentDescription = description }) {
        val w = size.width
        val h = size.height
        val maxKg = maxOf(10.0, (samples.maxOfOrNull { it.kg } ?: 0.0) * 1.15)
        for (i in 0..4) {
            val y = h - h * i / 4f
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (samples.size < 2) return@Canvas
        val t0 = samples.last().micros - ForceGaugeViewModel.WINDOW_MICROS
        fun x(t: Long) = ((t - t0).toFloat() / ForceGaugeViewModel.WINDOW_MICROS) * w
        fun y(kg: Double) = h - (kg.coerceAtLeast(0.0) / maxKg).toFloat() * h
        val path = Path()
        samples.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(s.micros), y(s.kg)) else path.lineTo(x(s.micros), y(s.kg)) }
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))
        samples.maxByOrNull { it.kg }?.let { p ->
            drawLine(peakColor, Offset(0f, y(p.kg)), Offset(w, y(p.kg)), strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
    }
}

@Composable
private fun PullCard(s: ForceGaugeState, onStart: () -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("force_pull")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.trd_force_pull_title, ForceGaugeViewModel.PULL_SECONDS), style = MaterialTheme.typography.titleSmall)
            when (s.phase) {
                PullPhase.IDLE -> Button(onClick = onStart, modifier = Modifier.fillMaxWidth().testTag("force_start")) {
                    Text(stringResource(R.string.trd_force_start))
                }
                PullPhase.COUNTDOWN -> {
                    Text(stringResource(R.string.trd_force_get_ready, s.countdown), style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.trd_force_cancel)) }
                }
                PullPhase.PULLING -> {
                    Text(stringResource(R.string.trd_force_pull_now, s.secondsLeft), style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("force_pulling"))
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.trd_force_cancel)) }
                }
                PullPhase.DONE -> {
                    val r = s.result
                    if (r == null) {
                        Text(stringResource(R.string.trd_force_no_data), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(stringResource(R.string.trd_force_result_peak, formatMass(r.peakKg, s.units)), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            r.bestMeanKg?.let { stringResource(R.string.trd_force_result_mean, formatMass(it, s.units)) }
                                ?: stringResource(R.string.trd_force_result_too_short),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag("force_result"),
                        )
                        val bw = s.bodyweightKg
                        if (bw != null && bw > 0 && r.bestMeanKg != null) {
                            Text(stringResource(R.string.trd_force_result_pct, (r.bestMeanKg / bw * 100).roundToInt()),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (s.saved) {
                            Text(stringResource(R.string.trd_force_saved), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("force_saved"))
                        } else {
                            Button(onClick = onSave, enabled = r.bestMeanKg != null, modifier = Modifier.testTag("force_save")) {
                                Text(stringResource(R.string.trd_force_save))
                            }
                        }
                    }
                    OutlinedButton(onClick = onStart, modifier = Modifier.testTag("force_again")) { Text(stringResource(R.string.trd_force_again)) }
                }
            }
        }
    }
}

@Composable
private fun handLabel(h: ForceHand): String = stringResource(when (h) {
    ForceHand.LEFT -> R.string.trd_force_hand_left
    ForceHand.RIGHT -> R.string.trd_force_hand_right
    ForceHand.BOTH -> R.string.trd_force_hand_both
})
