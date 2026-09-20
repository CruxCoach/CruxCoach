package com.cruxcoach.app.ui

import com.cruxcoach.app.platform.KeyValueStore

/**
 * The guided first browse, port of Android's `BrowserTour` state machine:
 * connect a board, set the angle, filter, open a climb, save it as a project,
 * log an attempt, and find it again in the logbook.
 *
 * Deliberately separate from onboarding completion, as on Android: an upgrade
 * is never dragged back into a tour, and a dismissal survives a restart.
 *
 * **Difference from Android:** the steps are shown as a banner over the
 * browser, not as a spotlight cut-out around the control being described.
 */
class BrowserTourModel(private val store: KeyValueStore) {

    /** inactive | connect | angle | filter | open | project | log | logbook | entry | done */
    var step: String = STEP_INACTIVE
        private set

    /** Climb logged during the tour, so the logbook step can point at it. */
    var loggedEntryUuid: String = ""
        private set

    init {
        refresh()
    }

    fun refresh() {
        step = store.getString(KEY_STEP)?.takeIf { it in STEPS } ?: STEP_INACTIVE
        loggedEntryUuid = store.getString(KEY_ENTRY) ?: ""
    }

    val isActive: Boolean get() = step != STEP_INACTIVE && step != STEP_DONE

    /**
     * Starts the tour. Without Bluetooth the connect step is skipped rather
     * than shown and failed; [replay] from settings offers it again.
     */
    fun start(replay: Boolean) {
        store.putString(KEY_ENTRY, null)
        loggedEntryUuid = ""
        if (replay) store.putString(KEY_DEFER_BLE, "false")
        move(if (store.getString(KEY_DEFER_BLE) == "true") STEP_ANGLE else STEP_CONNECT)
    }

    /** The user has no board to connect: never offer that step again. */
    fun deferBluetooth() {
        store.putString(KEY_DEFER_BLE, "true")
        if (step == STEP_CONNECT) move(STEP_ANGLE)
    }

    /** Advances only from [from], so a repeated action cannot skip a step. */
    fun advance(from: String) {
        if (step != from) return
        val index = ORDER.indexOf(from)
        if (index < 0 || index + 1 >= ORDER.size) return
        move(ORDER[index + 1])
    }

    fun logged(entryUuid: String) {
        store.putString(KEY_ENTRY, entryUuid)
        loggedEntryUuid = entryUuid
        move(STEP_LOGBOOK)
    }

    fun skip() = move(STEP_DONE)

    private fun move(next: String) {
        store.putString(KEY_STEP, next)
        step = next
    }

    companion object {
        const val STEP_INACTIVE = "inactive"
        const val STEP_CONNECT = "connect"
        const val STEP_ANGLE = "angle"
        const val STEP_FILTER = "filter"
        const val STEP_OPEN = "open"
        const val STEP_PROJECT = "project"
        const val STEP_LOG = "log"
        const val STEP_LOGBOOK = "logbook"
        const val STEP_ENTRY = "entry"
        const val STEP_DONE = "done"

        /** Android's `browser_tour_v1` keys, so a shared backup agrees. */
        const val KEY_STEP = "browser_tour_step"
        const val KEY_ENTRY = "browser_tour_entry_uuid"
        const val KEY_DEFER_BLE = "browser_tour_defer_ble"

        private val ORDER = listOf(
            STEP_CONNECT, STEP_ANGLE, STEP_FILTER, STEP_OPEN,
            STEP_PROJECT, STEP_LOG, STEP_LOGBOOK, STEP_ENTRY, STEP_DONE,
        )
        private val STEPS = (ORDER + STEP_INACTIVE).toSet()
    }
}
