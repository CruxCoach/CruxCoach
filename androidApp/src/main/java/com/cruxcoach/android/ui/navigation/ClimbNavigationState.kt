package com.cruxcoach.android.ui.navigation

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current list of climb UUIDs for swipe navigation
 * in the detail screen. Populated by the source screen (browser, list detail,
 * logbook) before navigating to a climb detail.
 *
 * Injected as a singleton via Hilt so it's testable and DI-managed.
 */
enum class ClimbNavigationSource { BROWSER, QUEUE, LIST, LOGBOOK }

@Singleton
class ClimbNavigationState @Inject constructor() {
    var climbUuids: List<String> = emptyList()
    var angle: Int = 40
    var source: ClimbNavigationSource = ClimbNavigationSource.BROWSER
    /** Set by detail VM after ascent/bid insert or delete; read & reset by browser VM on refresh. */
    var statusDataChanged: Boolean = false
    /** UUIDs of climbs whose status changed (logged/deleted). Consumed together with the flag. */
    val changedClimbUuids: MutableSet<String> = mutableSetOf()
    /** Set by detail screen when user taps a setter name; consumed by browser on resume. */
    var pendingSetterFilter: String? = null
}
