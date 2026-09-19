package com.cruxcoach.app.logbook

import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.ClimbHistoryEntry
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.QuickLogBidInput
import com.cruxcoach.data.repository.QuickLogSendInput
import com.cruxcoach.util.DateTimeUtil
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ascent logging dialog form state (Android `AscentFormState`). */
data class AscentFormState(
    val showDialog: Boolean = false,
    val isSend: Boolean = true,
    val bidCount: Int = 1,
    val quality: Int = 0,
    val comment: String = "",
    val isBenchmark: Boolean = false,
    val editingUuid: String? = null,
    val deleteConfirmUuid: String? = null,
)

/** One-shot confirmation for a quick log; drives the undo affordance. */
data class QuickLogFeedback(
    val eventId: String,
    val entryUuid: String,
    val isSend: Boolean,
)

/** The climb variant being logged: angle and mirror come from the detail context. */
data class LogTarget(
    val climbUuid: String,
    val climbName: String,
    val frames: String,
    val framesCount: Long,
    val difficultyAverage: Double?,
    val boardBrand: String,
    val layoutId: Long?,
    val angle: Int,
    val isMirrored: Boolean,
)

data class LogAttemptState(
    val target: LogTarget? = null,
    val ascent: AscentFormState = AscentFormState(),
    val userAscents: List<AscentWithClimb> = emptyList(),
    val isQuickLogging: Boolean = false,
    val quickLogFeedback: QuickLogFeedback? = null,
    val quickLogFailed: Boolean = false,
)

/** Board-session counters touched by logging (Android `BoardSessionManager`). */
interface SessionRecorder {
    fun recordAscent()
    fun recordBid()
    fun undoRecordedAscent()
    fun undoRecordedBid()
}

object NoSessionRecorder : SessionRecorder {
    override fun recordAscent() = Unit
    override fun recordBid() = Unit
    override fun undoRecordedAscent() = Unit
    override fun undoRecordedBid() = Unit
}

@OptIn(ExperimentalUuidApi::class)
internal fun randomUuidString(): String = Uuid.random().toString()

/**
 * Port of Android `AscentLogger`: ascent/bid CRUD plus the quick-log state
 * machine. Consecutive quick bids on one (climb, angle, mirror) accumulate in a
 * single bid row; a quick send promotes that row with attemptsToTop = open
 * bids + 1. Every SEND is also appended to the projection history.
 */
internal class AscentLogger(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<LogAttemptState>,
    private val personalBoardRepo: PersonalBoardRepository,
    private val sessionRecorder: SessionRecorder,
    /** Deferred follow-up (upload/sync): fires once the undo window closed. */
    private val onAscentSaved: (isSend: Boolean) -> Unit,
    /** Immediate follow-up for a quick log: the rest timer restarts on every tap. */
    private val onQuickLogSaved: (isSend: Boolean) -> Unit = {},
    /** Status badges elsewhere must refresh for this climb. */
    private val onClimbStatusChanged: (climbUuid: String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowIso: () -> String = { DateTimeUtil.nowIso() },
    private val newUuid: () -> String = ::randomUuidString,
    private val undoWindowMillis: Long = UNDO_WINDOW_MILLIS,
) {

    private data class ActiveQuickLog(
        val entryUuid: String,
        val climbUuid: String,
        val angle: Long,
        val isMirror: Boolean,
        val attemptCount: Long,
        val climbedAt: String,
        val climbName: String,
        val difficultyAverage: Double?,
        val boardBrand: String,
        val layoutId: Long?,
    )

    private class PendingQuickLog(
        val climbUuid: String,
        val angle: Long,
        val isSend: Boolean,
        val before: ActiveQuickLog?,
        val after: ActiveQuickLog?,
        val sendEntryUuid: String?,
        val previousHistory: ClimbHistoryEntry?,
    )

    private var pendingQuickLog: PendingQuickLog? = null
    private var activeQuickLog: ActiveQuickLog? = null
    private var pendingFinalizationJob: Job? = null

    fun showDialog() {
        finishQuickSequence()
        state.update { it.copy(ascent = AscentFormState(showDialog = true)) }
    }

    fun dismissDialog() {
        state.update { it.copy(ascent = it.ascent.copy(showDialog = false, editingUuid = null)) }
    }

    fun updateIsSend(isSend: Boolean) = state.update { it.copy(ascent = it.ascent.copy(isSend = isSend)) }

    fun updateBidCount(count: Int) =
        state.update { it.copy(ascent = it.ascent.copy(bidCount = count.coerceAtLeast(1))) }

    fun updateQuality(quality: Int) =
        state.update { it.copy(ascent = it.ascent.copy(quality = quality.coerceIn(0, 5))) }

    fun updateComment(comment: String) = state.update { it.copy(ascent = it.ascent.copy(comment = comment)) }

    fun updateIsBenchmark(value: Boolean) =
        state.update { it.copy(ascent = it.ascent.copy(isBenchmark = value)) }

    fun save() = save(isQuickLog = false)

    fun quickLog(isSend: Boolean) {
        finalizePendingQuickLog()
        state.update {
            it.copy(ascent = AscentFormState(isSend = isSend), isQuickLogging = true, quickLogFeedback = null)
        }
        save(isQuickLog = true)
    }

    fun consumeQuickLogFeedback() {
        finalizePendingQuickLog()
        state.update { it.copy(quickLogFeedback = null) }
    }

    /** A different climb starts a different logging sequence. */
    fun finishQuickSequence() {
        finalizePendingQuickLog()
        activeQuickLog = null
    }

    fun undoQuickLog() {
        val pending = pendingQuickLog ?: return
        pendingFinalizationJob?.cancel()
        pendingFinalizationJob = null
        pendingQuickLog = null
        scope.launch {
            try {
                withContext(ioDispatcher) {
                    if (pending.isSend) {
                        val before = pending.before
                        if (before != null) {
                            personalBoardRepo.restoreQuickBidFromSend(before.toInput())
                        } else {
                            pending.sendEntryUuid?.let(personalBoardRepo::deleteAscent)
                        }
                        val currentHistory = personalBoardRepo.observeClimbHistory().first()
                            .firstOrNull { it.climbUuid == pending.climbUuid && it.angle.toLong() == pending.angle }
                        currentHistory?.let { personalBoardRepo.deleteClimbHistory(listOf(it.id)) }
                        pending.previousHistory?.let { previous ->
                            personalBoardRepo.recordClimbHistory(
                                climbUuid = previous.climbUuid,
                                climbName = previous.climbName,
                                angle = previous.angle.toLong(),
                                difficultyAverage = previous.difficultyAverage,
                                boardBrand = previous.boardBrand,
                                layoutId = previous.layoutId,
                                climbedAt = previous.climbedAt,
                                recordedAt = previous.recordedAt,
                            )
                        }
                    } else {
                        val before = pending.before
                        if (before == null) {
                            pending.after?.let { personalBoardRepo.deleteBid(it.entryUuid) }
                        } else {
                            personalBoardRepo.updateBid(
                                uuid = before.entryUuid,
                                bidCount = before.attemptCount,
                                comment = null,
                            )
                        }
                    }
                    activeQuickLog = pending.before
                    val updated = personalBoardRepo.getUserHistoryForClimb(pending.climbUuid)
                    state.update {
                        it.copy(userAscents = updated, isQuickLogging = false, quickLogFeedback = null)
                    }
                }
                if (pending.isSend) sessionRecorder.undoRecordedAscent() else sessionRecorder.undoRecordedBid()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.update { it.copy(isQuickLogging = false, quickLogFeedback = null, quickLogFailed = true) }
            }
        }
    }

    private fun finalizePendingQuickLog() {
        val pending = pendingQuickLog ?: return
        pendingFinalizationJob?.cancel()
        pendingFinalizationJob = null
        pendingQuickLog = null
        onAscentSaved(pending.isSend)
    }

    private fun ActiveQuickLog.toInput() = QuickLogBidInput(
        uuid = entryUuid,
        climbUuid = climbUuid,
        angle = angle,
        isMirror = isMirror,
        bidCount = attemptCount,
        climbedAt = climbedAt,
        climbName = climbName,
        difficultyAverage = difficultyAverage,
        boardBrand = boardBrand,
        layoutId = layoutId,
    )

    private fun schedulePendingFinalization(pending: PendingQuickLog) {
        pendingFinalizationJob?.cancel()
        pendingFinalizationJob = scope.launch {
            delay(undoWindowMillis)
            if (pendingQuickLog === pending) finalizePendingQuickLog()
        }
    }

    private fun save(isQuickLog: Boolean) {
        val s = state.value
        // Close on the way out so a missing target never reads as a hang.
        val climb = s.target ?: run {
            dismissDialog()
            state.update { it.copy(isQuickLogging = false) }
            return
        }
        val form = s.ascent
        val editUuid = form.editingUuid
        val entryUuid = editUuid ?: newUuid()
        val climbUuid = climb.climbUuid
        val angle = climb.angle.toLong()

        scope.launch {
            try {
                withContext(ioDispatcher) {
                    var effectiveEntryUuid = entryUuid
                    var quickBefore: ActiveQuickLog? = null
                    var quickAfter: ActiveQuickLog? = null
                    var sendEntryUuid: String? = null
                    val previousHistory = if (isQuickLog && form.isSend) {
                        try {
                            personalBoardRepo.observeClimbHistory().first()
                                .firstOrNull { it.climbUuid == climbUuid && it.angle.toLong() == angle }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    if (editUuid != null) {
                        // Route by entry type: a bid lives in the bids table, so
                        // updateAscent would match zero rows. Bids carry no quality.
                        if (form.isSend) {
                            personalBoardRepo.updateAscent(
                                uuid = editUuid,
                                bidCount = form.bidCount.toLong(),
                                quality = if (form.quality > 0) form.quality.toLong() else null,
                                comment = form.comment.ifBlank { null },
                            )
                        } else {
                            personalBoardRepo.updateBid(
                                uuid = editUuid,
                                bidCount = form.bidCount.toLong(),
                                comment = form.comment.ifBlank { null },
                            )
                        }
                    } else {
                        val now = nowIso()
                        val compatibleOpenLog = activeQuickLog?.takeIf {
                            isQuickLog && it.climbUuid == climbUuid && it.angle == angle &&
                                it.isMirror == climb.isMirrored
                        }
                        quickBefore = compatibleOpenLog
                        if (form.isSend) {
                            // A quick send closes the open sequence: previous failed
                            // burns become the send's attempts-to-top.
                            val attemptsToTop = if (isQuickLog) {
                                (compatibleOpenLog?.attemptCount ?: 0L) + 1L
                            } else {
                                form.bidCount.toLong()
                            }
                            if (compatibleOpenLog != null) {
                                effectiveEntryUuid = compatibleOpenLog.entryUuid
                                personalBoardRepo.promoteQuickBidToSend(
                                    QuickLogSendInput(
                                        uuid = compatibleOpenLog.entryUuid,
                                        climbUuid = compatibleOpenLog.climbUuid,
                                        angle = compatibleOpenLog.angle,
                                        isMirror = compatibleOpenLog.isMirror,
                                        bidCount = attemptsToTop,
                                        difficulty = climb.difficultyAverage?.toLong(),
                                        climbedAt = now,
                                        climbName = compatibleOpenLog.climbName,
                                        difficultyAverage = compatibleOpenLog.difficultyAverage,
                                        climbFrames = climb.frames,
                                        framesCount = climb.framesCount,
                                        boardBrand = compatibleOpenLog.boardBrand,
                                        layoutId = compatibleOpenLog.layoutId,
                                    )
                                )
                            } else {
                                personalBoardRepo.insertAscent(
                                    uuid = effectiveEntryUuid,
                                    climbUuid = climbUuid,
                                    angle = angle,
                                    isMirror = climb.isMirrored,
                                    attemptId = 0,
                                    bidCount = attemptsToTop,
                                    quality = if (form.quality > 0) form.quality.toLong() else null,
                                    difficulty = climb.difficultyAverage?.toLong(),
                                    isBenchmark = form.isBenchmark,
                                    comment = form.comment.ifBlank { null },
                                    climbedAt = now,
                                    synced = false,
                                    climbName = climb.climbName,
                                    difficultyAverage = climb.difficultyAverage,
                                    climbFrames = climb.frames,
                                    framesCount = climb.framesCount,
                                    boardBrand = climb.boardBrand,
                                    layoutId = climb.layoutId,
                                )
                            }
                            sendEntryUuid = effectiveEntryUuid
                            // Only sends enter the projection history, never bids.
                            try {
                                personalBoardRepo.recordClimbHistory(
                                    climbUuid = climbUuid,
                                    climbName = climb.climbName,
                                    angle = angle,
                                    difficultyAverage = climb.difficultyAverage,
                                    boardBrand = climb.boardBrand,
                                    layoutId = climb.layoutId,
                                    climbedAt = now,
                                    recordedAt = now,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // The primary log is durable; a history failure must
                                // not turn a saved send into a duplicating retry.
                            }
                            if (isQuickLog) activeQuickLog = null
                        } else {
                            if (isQuickLog && compatibleOpenLog != null) {
                                effectiveEntryUuid = compatibleOpenLog.entryUuid
                                val after = compatibleOpenLog.copy(attemptCount = compatibleOpenLog.attemptCount + 1L)
                                quickAfter = after
                                personalBoardRepo.updateBid(
                                    uuid = effectiveEntryUuid,
                                    bidCount = after.attemptCount,
                                    comment = null,
                                )
                            } else {
                                quickAfter = ActiveQuickLog(
                                    entryUuid = effectiveEntryUuid,
                                    climbUuid = climbUuid,
                                    angle = angle,
                                    isMirror = climb.isMirrored,
                                    attemptCount = form.bidCount.toLong(),
                                    climbedAt = now,
                                    climbName = climb.climbName,
                                    difficultyAverage = climb.difficultyAverage,
                                    boardBrand = climb.boardBrand,
                                    layoutId = climb.layoutId,
                                )
                                personalBoardRepo.insertBid(
                                    uuid = effectiveEntryUuid,
                                    climbUuid = climbUuid,
                                    angle = angle,
                                    isMirror = climb.isMirrored,
                                    bidCount = form.bidCount.toLong(),
                                    comment = form.comment.ifBlank { null },
                                    climbedAt = now,
                                    synced = false,
                                    climbName = climb.climbName,
                                    difficultyAverage = climb.difficultyAverage,
                                    boardBrand = climb.boardBrand,
                                    layoutId = climb.layoutId,
                                )
                            }
                            if (isQuickLog) activeQuickLog = quickAfter
                        }
                    }
                    val updatedAscents = try {
                        personalBoardRepo.getUserHistoryForClimb(climbUuid)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        state.value.userAscents
                    }
                    if (isQuickLog && editUuid == null) {
                        val pending = PendingQuickLog(
                            climbUuid = climbUuid,
                            angle = angle,
                            isSend = form.isSend,
                            before = quickBefore,
                            after = quickAfter,
                            sendEntryUuid = sendEntryUuid,
                            previousHistory = previousHistory,
                        )
                        pendingQuickLog = pending
                        schedulePendingFinalization(pending)
                    }
                    // isQuickLogging=false is the completion boundary: fire the
                    // immediate follow-up before it becomes observable.
                    if (editUuid == null && isQuickLog) onQuickLogSaved(form.isSend)
                    state.update { current ->
                        if (current.target != climb) return@update current.copy(isQuickLogging = false)
                        current.copy(
                            ascent = AscentFormState(),
                            userAscents = updatedAscents,
                            isQuickLogging = false,
                            quickLogFeedback = if (isQuickLog && editUuid == null) {
                                QuickLogFeedback(
                                    eventId = newUuid(),
                                    entryUuid = effectiveEntryUuid,
                                    isSend = form.isSend,
                                )
                            } else {
                                current.quickLogFeedback
                            },
                        )
                    }
                }
                if (editUuid == null) {
                    onClimbStatusChanged(climbUuid)
                    if (form.isSend) sessionRecorder.recordAscent() else sessionRecorder.recordBid()
                    if (!isQuickLog) onAscentSaved(form.isSend)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                pendingFinalizationJob?.cancel()
                pendingFinalizationJob = null
                pendingQuickLog = null
                state.update { current ->
                    current.copy(
                        isQuickLogging = false,
                        quickLogFeedback = null,
                        quickLogFailed = isQuickLog && current.target == climb,
                    )
                }
            }
        }
    }

    fun edit(ascent: AscentWithClimb) {
        finishQuickSequence()
        state.update {
            it.copy(
                ascent = AscentFormState(
                    showDialog = true,
                    editingUuid = ascent.uuid,
                    isSend = ascent.isSend,
                    bidCount = ascent.bidCount.toInt().coerceAtLeast(1),
                    quality = (ascent.quality?.toInt() ?: 0).coerceIn(0, 5),
                    comment = ascent.comment ?: "",
                )
            )
        }
    }

    fun requestDelete(uuid: String) {
        finishQuickSequence()
        state.update { it.copy(ascent = it.ascent.copy(deleteConfirmUuid = uuid)) }
    }

    fun dismissDeleteConfirm() = state.update { it.copy(ascent = it.ascent.copy(deleteConfirmUuid = null)) }

    fun confirmDelete() {
        val uuid = state.value.ascent.deleteConfirmUuid ?: return
        val entry = state.value.userAscents.find { it.uuid == uuid }
        val climbUuid = state.value.target?.climbUuid ?: entry?.climbUuid ?: return
        scope.launch {
            try {
                withContext(ioDispatcher) {
                    // Bids and sends live in different tables.
                    if (entry?.isSend == false) personalBoardRepo.deleteBid(uuid)
                    else personalBoardRepo.deleteAscent(uuid)
                    val updatedAscents = personalBoardRepo.getUserHistoryForClimb(climbUuid)
                    state.update {
                        it.copy(ascent = it.ascent.copy(deleteConfirmUuid = null), userAscents = updatedAscents)
                    }
                }
                onClimbStatusChanged(climbUuid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Android lets this crash the coroutine; on iOS nothing may escape.
                state.update { it.copy(ascent = it.ascent.copy(deleteConfirmUuid = null)) }
            }
        }
    }

    companion object {
        const val UNDO_WINDOW_MILLIS = 7_000L
    }
}
