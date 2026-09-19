package com.cruxcoach.app.logbook

import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.PersonalBoardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Callbacks a host wires to the rest timer / playlist player / sync. */
interface LogAttemptListener {
    /** Deferred: after the quick-log undo window closed, or right after a dialog save. */
    fun onAscentSaved(isSend: Boolean)
    /** Immediate, once per successful quick log. */
    fun onQuickLogSaved(isSend: Boolean)
    fun onClimbStatusChanged(climbUuid: String)
}

/** Swift-facing log dialog + quick log for the climb detail screen. */
class LogAttemptPresenter(
    private val personalBoardRepo: PersonalBoardRepository,
    private val sessionRecorder: SessionRecorder = NoSessionRecorder,
    private val listener: LogAttemptListener? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(LogAttemptState())
    val state: StateFlow<LogAttemptState> = _state.asStateFlow()

    private val logger = AscentLogger(
        scope = scope,
        state = _state,
        personalBoardRepo = personalBoardRepo,
        sessionRecorder = sessionRecorder,
        onAscentSaved = { isSend -> listener?.onAscentSaved(isSend) },
        onQuickLogSaved = { isSend -> listener?.onQuickLogSaved(isSend) },
        onClimbStatusChanged = { uuid -> listener?.onClimbStatusChanged(uuid) },
        ioDispatcher = ioDispatcher,
    )

    fun watch(onState: (LogAttemptState) -> Unit): StateWatch = scope.watchState(state, onState)

    /** Switching climb, angle or mirror ends the open quick-log sequence. */
    fun setTarget(target: LogTarget?) {
        if (_state.value.target == target) return
        logger.finishQuickSequence()
        _state.update { LogAttemptState(target = target) }
        if (target == null) return
        scope.launch {
            try {
                val entries = withContext(ioDispatcher) { personalBoardRepo.getUserHistoryForClimb(target.climbUuid) }
                _state.update { if (it.target == target) it.copy(userAscents = entries) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Entries stay empty; logging itself is unaffected.
            }
        }
    }

    fun showDialog() = logger.showDialog()
    fun dismissDialog() = logger.dismissDialog()
    fun updateIsSend(isSend: Boolean) = logger.updateIsSend(isSend)
    fun updateBidCount(count: Int) = logger.updateBidCount(count)
    fun updateQuality(quality: Int) = logger.updateQuality(quality)
    fun updateComment(comment: String) = logger.updateComment(comment)
    fun updateIsBenchmark(value: Boolean) = logger.updateIsBenchmark(value)
    fun save() = logger.save()
    fun quickLog(isSend: Boolean) = logger.quickLog(isSend)
    fun undoQuickLog() = logger.undoQuickLog()
    fun consumeQuickLogFeedback() = logger.consumeQuickLogFeedback()
    fun consumeQuickLogFailure() = _state.update { it.copy(quickLogFailed = false) }
    fun edit(entry: AscentWithClimb) = logger.edit(entry)
    fun requestDelete(uuid: String) = logger.requestDelete(uuid)
    fun dismissDeleteConfirm() = logger.dismissDeleteConfirm()
    fun confirmDelete() = logger.confirmDelete()

    /** Flushes a pending quick log's deferred follow-up, then stops all work. */
    fun close() {
        logger.finishQuickSequence()
        scope.cancel()
    }
}
