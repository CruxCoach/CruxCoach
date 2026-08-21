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
    var angle: Int = 40
    var source: ClimbNavigationSource = ClimbNavigationSource.BROWSER

    /**
     * The shared-playlist occurrence this detail screen was opened from.
     *
     * "On the board now" needs to know whether the climb in front of the user
     * is already a specific entry on the group's list. If it is, that entry is
     * what becomes current — its own stable id, so a repeat of the same climb
     * further down is not what moves. If it is not, the climb is not on the
     * list at all and becomes a new occurrence after the current one.
     *
     * Null for every route that does not come from an occurrence, which is
     * every route today: the browser, search, the logbook and deep links all
     * open a climb, not an entry. Paired with the climb uuid it was set for so
     * a swipe to the next climb cannot inherit the previous one's entry.
     */
    var boardPlaylistEntryId: String? = null
    var boardPlaylistEntryClimbUuid: String? = null
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
