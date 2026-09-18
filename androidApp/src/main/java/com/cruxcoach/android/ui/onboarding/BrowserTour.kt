package com.cruxcoach.android.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R

internal enum class TourStep { INACTIVE, CONNECT, ANGLE, FILTER, OPEN, PROJECT, LOG, DONE }

/** Separate from setup completion: upgrades remain untouched and dismissal survives restart. */
internal class BrowserTour(context: Context) {
    private val prefs = context.getSharedPreferences("browser_tour_v1", Context.MODE_PRIVATE)
    fun step(): TourStep = runCatching { TourStep.valueOf(prefs.getString("step", "INACTIVE")!!) }.getOrDefault(TourStep.INACTIVE)
    fun move(step: TourStep) { prefs.edit().putString("step", step.name).apply() }
    fun deferBle() { prefs.edit().putBoolean("defer_ble", true).apply() }
    fun start(replay: Boolean = false) {
        if (replay) prefs.edit().putBoolean("defer_ble", false).apply()
        move(if (prefs.getBoolean("defer_ble", false)) TourStep.ANGLE else TourStep.CONNECT)
    }
    fun listen(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun unlisten(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)
}

@Composable
internal fun rememberBrowserTour(): Pair<BrowserTour, TourStep> {
    val context = LocalContext.current
    val tour = remember(context) { BrowserTour(context.applicationContext) }
    var step by remember(tour) { mutableStateOf(tour.step()) }
    DisposableEffect(tour) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> step = tour.step() }
        tour.listen(listener)
        step = tour.step()
        onDispose { tour.unlisten(listener) }
    }
    return tour to step
}

/** A contextual card adjacent to the real controls; never a modal that blocks those controls. */
@Composable
internal fun TourHint(title: Int, message: Int, action: Int, onAction: () -> Unit,
                      onEnd: () -> Unit, secondary: Int? = null, onSecondary: () -> Unit = {}) {
    BackHandler(onBack = onEnd)
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.45f).coerceAtLeast(160.dp)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("browser_tour_hint")) {
        Box(Modifier.heightIn(max = maxHeight)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), modifier = Modifier.padding(end = 40.dp), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onAction) { Text(stringResource(action)) }
                    if (secondary != null) TextButton(onClick = onSecondary) { Text(stringResource(secondary)) }
                }
            }
            // Dismissal stays visible even when large text makes the content scroll.
            IconButton(onClick = onEnd, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.tour_end))
            }
        }
    }
}

/** Advertising names are hints only; unknown Aurora names must not inherit Kilter's transport fallback. */
internal fun onboardingBoardSuggestion(board: com.cruxcoach.android.ble.DiscoveredBoard?): com.cruxcoach.domain.board.BoardBrand? {
    if (board == null || board.isCruxRelay) return null
    val name = board.displayName.lowercase().replace(" ", "").replace("-", "")
    return when {
        name.startsWith("kilter") -> com.cruxcoach.domain.board.BoardBrand.KILTER
        name.startsWith("moonboard") -> com.cruxcoach.domain.board.BoardBrand.MOONBOARD
        name.startsWith("tension") -> com.cruxcoach.domain.board.BoardBrand.TENSION
        name.startsWith("grasshopper") -> com.cruxcoach.domain.board.BoardBrand.GRASSHOPPER
        name.startsWith("decoy") -> com.cruxcoach.domain.board.BoardBrand.DECOY
        name.startsWith("soill") -> com.cruxcoach.domain.board.BoardBrand.SOILL
        name.startsWith("touchstone") -> com.cruxcoach.domain.board.BoardBrand.TOUCHSTONE
        name.startsWith("quantum") -> com.cruxcoach.domain.board.BoardBrand.QUANTUM
        else -> null
    }
}
