package com.cruxcoach.android.ui.training.workout

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

private enum class TimerPhase { PREPARE, WORK, REST, SET_REST, DONE }

private data class Segment(val phase: TimerPhase, val durationMs: Long, val rep: Int, val set: Int, val startMs: Long) {
    val endMs: Long get() = startMs + durationMs
}

private const val PREPARE_SECONDS = 5

private fun buildSegments(workS: Int, restBetweenS: Int, reps: Int, sets: Int, restBetweenSetsS: Int): List<Segment> {
    val list = mutableListOf<Segment>()
    var t = 0L
    fun add(phase: TimerPhase, seconds: Int, rep: Int, set: Int) {
        if (seconds <= 0) return
        list += Segment(phase, seconds * 1000L, rep, set, t)
        t += seconds * 1000L
    }
    add(TimerPhase.PREPARE, PREPARE_SECONDS, 0, 0)
    for (s in 0 until sets) {
        for (r in 0 until reps) {
            add(TimerPhase.WORK, workS, r, s)
            if (r < reps - 1) add(TimerPhase.REST, restBetweenS, r, s)
        }
        if (s < sets - 1) add(TimerPhase.SET_REST, restBetweenSetsS, reps - 1, s)
    }
    return list
}

/**
 * Full-screen hang / interval timer: prepare → work → rest … → set rest → done.
 *
 * Timing is wall-clock based (start anchor + accumulated time), so a slow
 * frame or a short stall never stretches a hang. Beeps mark the last three
 * seconds of every phase and a longer tone the phase change; vibration and
 * spoken cues are optional. The screen stays on while the timer is open.
 * Also serves simple holds: reps = 1, sets = 1.
 */
@Composable
fun HangTimerDialog(
    workS: Int,
    restBetweenS: Int,
    reps: Int,
    sets: Int,
    restBetweenSetsS: Int,
    sound: Boolean,
    vibration: Boolean,
    voice: Boolean,
    sideLabels: List<String>?,
    onDismiss: () -> Unit,
    onSetFinished: (setIndex: Int) -> Unit,
) {
    val repCount = reps.coerceAtLeast(1)
    val setCount = sets.coerceAtLeast(1)
    val segments = remember(workS, restBetweenS, repCount, setCount, restBetweenSetsS) {
        buildSegments(workS.coerceAtLeast(1), restBetweenS.coerceAtLeast(0), repCount, setCount, restBetweenSetsS.coerceAtLeast(0))
    }
    val totalMs = segments.last().endMs
    val context = LocalContext.current

    val goText = stringResource(R.string.trw_voice_go)
    val restText = stringResource(R.string.trw_voice_rest)
    val doneText = stringResource(R.string.trw_voice_done)

    // ── Feedback channels ──────────────────────────────────────────
    val tone = remember { if (sound) runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull() else null }
    val vibrator: Vibrator? = remember {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
        }.getOrNull()
    }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(voice) {
        var engine: TextToSpeech? = null
        if (voice) {
            engine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    runCatching { engine?.language = Locale.getDefault() }
                    tts = engine
                }
            }
        }
        onDispose {
            tts = null
            runCatching { engine?.stop(); engine?.shutdown() }
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { tone?.release() } } }
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    fun beep(long: Boolean) {
        if (!sound) return
        runCatching {
            if (long) tone?.startTone(ToneGenerator.TONE_PROP_ACK, 400) else tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        }
    }
    fun buzz(ms: Long) {
        if (!vibration) return
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }
    fun say(text: String) {
        if (!voice) return
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hang_timer") }
    }

    // ── Clock ──────────────────────────────────────────────────────
    var running by remember { mutableStateOf(true) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var baseMs by remember { mutableLongStateOf(0L) }
    var anchorMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var segmentIndex by remember { mutableIntStateOf(0) }
    var lastBeepSecond by remember { mutableIntStateOf(-1) }
    val finishedSets = remember { mutableSetOf<Int>() }
    val done = elapsedMs >= totalMs

    fun crossed(from: Int, toExclusive: Int) {
        for (k in from until toExclusive.coerceAtMost(segments.size)) {
            val seg = segments[k]
            if (seg.phase == TimerPhase.WORK && seg.rep == repCount - 1 && finishedSets.add(seg.set)) onSetFinished(seg.set)
        }
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        anchorMs = System.currentTimeMillis()
        while (isActive) {
            val e = baseMs + (System.currentTimeMillis() - anchorMs)
            elapsedMs = e.coerceAtMost(totalMs)
            if (e >= totalMs) {
                crossed(segmentIndex, segments.size)
                segmentIndex = segments.size
                beep(long = true); buzz(500); say(doneText)
                running = false
                break
            }
            val idx = segments.indexOfLast { it.startMs <= e }.coerceAtLeast(0)
            if (idx != segmentIndex) {
                crossed(segmentIndex, idx)
                segmentIndex = idx
                lastBeepSecond = -1
                beep(long = true); buzz(300)
                when (segments[idx].phase) {
                    TimerPhase.WORK -> say(goText)
                    TimerPhase.REST, TimerPhase.SET_REST -> say(restText)
                    else -> Unit
                }
            }
            val remaining = ((segments[idx].endMs - e + 999) / 1000).toInt()
            if (remaining in 1..3 && remaining != lastBeepSecond) {
                lastBeepSecond = remaining
                beep(long = false)
            }
            delay(100)
        }
    }

    fun togglePause() {
        if (done) return
        if (running) { baseMs = elapsedMs; running = false } else running = true
    }

    fun skip() {
        if (done) return
        val next = segments.getOrNull(segmentIndex + 1)?.startMs ?: totalMs
        baseMs = next
        anchorMs = System.currentTimeMillis()
        if (!running) elapsedMs = next
    }

    // ── UI ─────────────────────────────────────────────────────────
    val current = segments.getOrNull(segmentIndex)
    val phase = if (done) TimerPhase.DONE else current?.phase ?: TimerPhase.PREPARE
    val (bg, fg) = when (phase) {
        TimerPhase.WORK -> CruxCoachDesign.colors.brandAccent to CruxCoachDesign.colors.onBrandAccent
        TimerPhase.REST -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        TimerPhase.SET_REST -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        TimerPhase.PREPARE -> CruxCoachDesign.colors.cautionContainer to CruxCoachDesign.colors.onCautionContainer
        TimerPhase.DONE -> CruxCoachDesign.colors.positiveContainer to CruxCoachDesign.colors.onPositiveContainer
    }
    val remainingSeconds = if (done || current == null) 0 else ((current.endMs - elapsedMs + 999) / 1000).toInt()
    val phaseLabel = stringResource(when (phase) {
        TimerPhase.PREPARE -> R.string.trw_phase_prepare
        TimerPhase.WORK -> R.string.trw_phase_work
        TimerPhase.REST -> R.string.trw_phase_rest
        TimerPhase.SET_REST -> R.string.trw_phase_set_rest
        TimerPhase.DONE -> R.string.trw_phase_done
    })

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize().background(bg).testTag("hang_timer")) {
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("hang_timer_close")) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.trw_timer_close), tint = fg)
            }
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val sideLabel = sideLabels?.takeIf { it.isNotEmpty() }?.let { labels -> labels[(current?.set ?: 0) % labels.size] }
                if (sideLabel != null) {
                    Text(sideLabel, color = fg, style = MaterialTheme.typography.titleLarge)
                }
                Text(phaseLabel, color = fg, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(
                    if (done) "✓" else remainingSeconds.toString(),
                    color = fg, fontSize = 120.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("hang_timer_countdown"),
                )
                if (current != null && !done && phase != TimerPhase.PREPARE) {
                    if (repCount > 1) {
                        Text(stringResource(R.string.trw_timer_rep, current.rep + 1, repCount), color = fg,
                            style = MaterialTheme.typography.titleMedium)
                    }
                    if (setCount > 1) {
                        Text(stringResource(R.string.trw_timer_set, current.set + 1, setCount), color = fg,
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(
                    progress = { (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(0.8f),
                    color = fg,
                    trackColor = fg.copy(alpha = 0.25f),
                )
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (done) {
                        Button(onClick = onDismiss, modifier = Modifier.testTag("hang_timer_done")) {
                            Text(stringResource(R.string.tr_action_done))
                        }
                    } else {
                        FilledTonalButton(onClick = ::togglePause, modifier = Modifier.testTag("hang_timer_pause")) {
                            Icon(if (running) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (running) R.string.trw_timer_pause else R.string.trw_timer_resume))
                        }
                        OutlinedButton(
                            onClick = ::skip,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = fg),
                            modifier = Modifier.testTag("hang_timer_skip"),
                        ) {
                            Icon(Icons.Default.SkipNext, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.trw_timer_skip))
                        }
                    }
                }
            }
        }
    }
}

