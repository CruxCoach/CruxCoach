package com.cruxcoach.app.ui

import com.cruxcoach.app.ble.BoardCleaningState
import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.BoardConnectionUiState
import com.cruxcoach.app.ble.ConnectionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

class BoardCleaningScreenState(
    /** The connected board can be cleaned (a direct Aurora-family or MoonBoard link). */
    val available: Boolean,
    /** Holds collected today, or left in the running round while [active]. */
    val holdCount: Int,
    val active: Boolean,
    val busy: Boolean,
    /** Holds an older controller lights per section; 0 when there is no limit. */
    val holdLimit: Int,
    /** More holds than one section: "board_cleaning_sections". */
    val sectioned: Boolean,
    /** Holds lit in the current section. */
    val displayedHoldCount: Int,
    val boardName: String,
    /** A board is connected (also while it is sending). */
    val connected: Boolean,
    /** The link is idle and can take a cleaning frame. */
    val ready: Boolean,
    /** The last start/finish did not reach the board: "board_cleaning_error". */
    val error: Boolean,
)

/**
 * "Clean holds" (Android's `BoardCleaningSheet` + `BoardCleaningViewModel`):
 * lights the holds of every problem sent to the connected board today, in
 * sections on older controllers. While it is open the day is re-read every
 * 30 s, so the collection follows the local date boundary.
 *
 * Board sharing (relay) does not exist on iOS, so the relay notice and lock
 * of the Android sheet never apply here.
 */
class BoardCleaningScreenModel(
    private val presenter: BoardConnectionPresenter,
    main: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val error = MutableStateFlow(false)
    private var running = false
    private var ticker: Job? = null

    private val state: StateFlow<BoardCleaningScreenState> =
        combine(presenter.cleaningState, presenter.state, error, ::map)
            .stateIn(scope, SharingStarted.Eagerly, map(presenter.cleaningState.value, presenter.state.value, false))

    val currentState: BoardCleaningScreenState get() = state.value

    fun watch(onState: (BoardCleaningScreenState) -> Unit): Subscription {
        val collector = scope.launch { state.collect { onState(it) } }
        return Subscription { collector.cancel() }
    }

    /** Re-reads the day now and every 30 s until [stopDayRefresh] or [close]. */
    fun startDayRefresh() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                presenter.refreshCleaningDay()
                delay(DAY_REFRESH_MS)
            }
        }
    }

    fun stopDayRefresh() {
        ticker?.cancel()
        ticker = null
    }

    fun start() = command { done -> presenter.startCleaning(done) }

    /** [markCleaned]: "Cleaned · reset collection" (or the next section); false: "Stop · keep collection". */
    fun finish(markCleaned: Boolean) = command { done -> presenter.finishCleaning(markCleaned, done) }

    fun close() = scope.cancel()

    private fun command(send: ((Boolean) -> Unit) -> Unit) {
        if (running) return
        running = true
        error.value = false
        send { success ->
            running = false
            if (!success) error.value = true
        }
    }

    private fun map(cleaning: BoardCleaningState, link: BoardConnectionUiState, failed: Boolean) = BoardCleaningScreenState(
        available = cleaning.available,
        holdCount = cleaning.holdCount,
        active = cleaning.active,
        busy = cleaning.busy,
        holdLimit = if (cleaning.holdLimit == Int.MAX_VALUE) 0 else cleaning.holdLimit,
        sectioned = cleaning.holdCount > cleaning.holdLimit,
        displayedHoldCount = cleaning.displayedHoldCount,
        boardName = link.connectedBoard?.displayName ?: "",
        connected = link.connection == ConnectionState.CONNECTED || link.connection == ConnectionState.SENDING,
        ready = link.connection == ConnectionState.CONNECTED,
        error = failed,
    )

    private companion object {
        const val DAY_REFRESH_MS = 30_000L
    }
}
