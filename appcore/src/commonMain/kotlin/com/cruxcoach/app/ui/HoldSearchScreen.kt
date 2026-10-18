package com.cruxcoach.app.ui

import com.cruxcoach.domain.board.BoardZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HoldSearchScreenState(
    /** The sheet's pending selection, not yet the browser's filter. */
    val selectedHolds: List<Int>,
    val zoneActive: Boolean,
    /** The pending area normalized over the board image (origin top-left); 0 while none. */
    val zoneLeft: Float,
    val zoneTop: Float,
    val zoneRight: Float,
    val zoneBottom: Float,
    /** Climbs the pending holds/area match on their own (Android `matchCount`); -1 when the count failed. */
    val matchCount: Int,
    val searching: Boolean,
    val hasSelection: Boolean,
    /** Android's "Filter" button: something matches and the count is current. */
    val canApply: Boolean,
)

/**
 * Android `HoldSearchSheet` + the browser view model's hold search: taps and the
 * area only change a pending selection and recount how many climbs it matches
 * (the browser's other filters apply, its search text does not); "Filter" makes
 * it the browser's filter and closes the sheet, "Reset" drops it everywhere.
 */
class HoldSearchScreenModel internal constructor(
    private val browser: BrowserScreenModel,
    main: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val holds: MutableSet<Int> = LinkedHashSet(browser.appliedHolds())
    private var zone: BoardZone? = browser.appliedZone()
    private var matchCount = 0
    private var searching = false
    private var countJob: Job? = null
    private val state = MutableStateFlow(snapshot())

    val currentState: HoldSearchScreenState get() = state.value

    init {
        // Reopened over an active filter: show what it matches, as Android's kept count does.
        if (holds.isNotEmpty() || zone != null) recount()
    }

    fun watch(onState: (HoldSearchScreenState) -> Unit): Subscription {
        val job = scope.launch { state.collect { onState(it) } }
        return Subscription { job.cancel() }
    }

    /** Android `toggleHoldSelection`: tapping a selected hold again removes it. */
    fun toggleHold(placementId: Int) {
        if (!holds.add(placementId)) holds.remove(placementId)
        recount()
    }

    /** Android `setZone`: the dragged box becomes the pending area. */
    fun setZone(ax: Float, ay: Float, bx: Float, by: Float) {
        zone = browser.zoneFromCanvas(ax, ay, bx, by) ?: return
        recount()
    }

    /**
     * Android `clearZone`: while the browser is already filtered the remaining selection is
     * applied at once (and the sheet closes); otherwise only the count changes.
     * Returns true when Swift should close the sheet.
     */
    fun clearZone(): Boolean {
        if (zone == null) return false
        zone = null
        if (browser.appliedHolds().isNotEmpty() || browser.appliedZone() != null) {
            publish()
            return apply()
        }
        recount()
        return false
    }

    /** Android `clearHoldSelection`: drops the selection, the area and the browser's hold filter. */
    fun reset() {
        countJob?.cancel()
        holds.clear()
        zone = null
        matchCount = 0
        searching = false
        browser.applyHoldSearch(emptySet(), null)
        publish()
    }

    /** Android `applyHoldFilter` ("Filter"); returns true when Swift should close the sheet. */
    fun apply(): Boolean {
        if (holds.isEmpty() && zone == null) {
            reset()
            return false
        }
        browser.applyHoldSearch(holds.toSet(), zone)
        return true
    }

    fun close() = scope.cancel()

    private fun recount() {
        countJob?.cancel()
        if (holds.isEmpty() && zone == null) {
            matchCount = 0
            searching = false
            publish()
            return
        }
        val selection = holds.toSet()
        val area = zone
        searching = true
        publish()
        countJob = scope.launch {
            val count = try {
                browser.holdMatchCount(selection, area)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                -1
            }
            matchCount = count
            searching = false
            publish()
        }
    }

    private fun publish() {
        state.value = snapshot()
    }

    private fun snapshot(): HoldSearchScreenState {
        val box = browser.zoneBox(browser.browserState, zone)
        val hasSelection = holds.isNotEmpty() || zone != null
        return HoldSearchScreenState(
            selectedHolds = holds.toList(),
            zoneActive = zone != null,
            zoneLeft = box[0],
            zoneTop = box[1],
            zoneRight = box[2],
            zoneBottom = box[3],
            matchCount = matchCount,
            searching = searching,
            hasSelection = hasSelection,
            canApply = hasSelection && !searching && matchCount > 0,
        )
    }
}
