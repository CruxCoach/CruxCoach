package com.cruxcoach.android.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        set(value) {
            field = value
            // A source that does not know per-climb angles must not inherit the last one's.
            climbAngles = emptyMap()
        }
    var angle: Int = 40
    /** The angle each climb of [climbUuids] opens at when they differ (logbook entries were logged at
     *  their own angles). A climb missing here opens at [angle]. Cleared whenever [climbUuids] is set. */
    var climbAngles: Map<String, Int> = emptyMap()
        private set

    /**
     * Fills the pager from entries that each carry their own angle, in list order: every climb once, at
     * the angle of its first entry, and the opened climb at the opened entry's angle. Paging used to open
     * every neighbour at the opened entry's angle, so a quick log stored that angle too.
     */
    fun setClimbsWithAngles(entries: List<Pair<String, Int>>, openedUuid: String, openedAngle: Int) {
        val angles = LinkedHashMap<String, Int>()
        entries.forEach { (uuid, entryAngle) -> if (uuid !in angles) angles[uuid] = entryAngle }
        val uuids = angles.keys.toList()
        angles[openedUuid] = openedAngle
        climbUuids = uuids
        climbAngles = angles
        angle = openedAngle
    }
    var source: ClimbNavigationSource = ClimbNavigationSource.BROWSER
    /** Set by detail VM after ascent/bid insert or delete; read & reset by browser VM on refresh. */
    var statusDataChanged: Boolean = false
    /** UUIDs of climbs whose status changed (logged/deleted). Consumed together with the flag. */
    val changedClimbUuids: MutableSet<String> = mutableSetOf()
    /** Set by detail screen when user taps a setter name; consumed by browser on resume. */
    var pendingSetterFilter: String? = null
    /** Set by [ClimbEditorViewModel] after any save / update / publish / delete
     *  on a local draft; read & reset by [BoardBrowserViewModel.refreshBoardData].
     *  Without this, edit-without-count-change (e.g. rename) leaves the browser
     *  showing the stale row title until app restart, since refreshBoardData
     *  only re-runs searchClimbs when the count changed. */
    var creatorDataChanged: Boolean = false
        set(value) {
            field = value
            // Bump the revision on every "something changed" so the browser
            // can react reactively. The lifecycle-ON_RESUME trigger proved
            // unreliable: the browser's nav entry stays RESUMED while the
            // detail/editor is on top, so it never re-fired on return.
            if (value) _creatorRevision.value += 1
        }

    private val _creatorRevision = MutableStateFlow(0)
    /** Increments whenever [creatorDataChanged] is set true (any creator-side
     *  save / publish / edit / delete / un-claim). The browser VM — retained
     *  across navigation — collects this and re-queries, so edits reflect in
     *  the (community) list without a manual re-open, independent of the
     *  screen lifecycle. */
    val creatorRevision: StateFlow<Int> = _creatorRevision.asStateFlow()
}
