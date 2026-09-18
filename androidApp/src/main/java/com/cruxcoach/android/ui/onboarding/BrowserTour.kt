package com.cruxcoach.android.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal enum class TourStep { INACTIVE, CONNECT, ANGLE, FILTER, OPEN, PROJECT, LOG, LOGBOOK, ENTRY, DONE }

/** Separate from setup completion: upgrades remain untouched and dismissal survives restart. */
internal class BrowserTour(context: Context) {
    private val prefs = context.getSharedPreferences("browser_tour_v1", Context.MODE_PRIVATE)
    fun step(): TourStep = runCatching { TourStep.valueOf(prefs.getString("step", "INACTIVE")!!) }.getOrDefault(TourStep.INACTIVE)
    fun move(step: TourStep) { prefs.edit().putString("step", step.name).apply() }
    fun loggedEntry(): String? = prefs.getString("entry_uuid", null)
    fun logged(entryUuid: String) {
        prefs.edit().putString("entry_uuid", entryUuid).putString("step", TourStep.LOGBOOK.name).apply()
    }
    fun deferBle() { prefs.edit().putBoolean("defer_ble", true).apply() }
    fun start(replay: Boolean = false) {
        prefs.edit().remove("entry_uuid").apply()
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
