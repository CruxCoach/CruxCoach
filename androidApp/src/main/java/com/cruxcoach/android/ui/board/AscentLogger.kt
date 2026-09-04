package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.QuickLogBidInput
import com.cruxcoach.data.repository.QuickLogSendInput
import com.cruxcoach.util.DateTimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Handles ascent/bid CRUD operations for the climb detail screen.
 *
 * Plain Kotlin class (not a ViewModel). Receives a [CoroutineScope] from the
 * parent ViewModel for launching async work.
 */
internal class AscentLogger(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ClimbDetailState>,
    private val personalBoardRepo: PersonalBoardRepository,
    private val sessionManager: BoardSessionManager,
    private val zoneManager: IntensityZoneManager,
    private val climbNavState: ClimbNavigationState,
    private val currentClimbUuid: () -> String,
    private val onAscentSaved: (isSend: Boolean) -> Unit,
    /** Immediate UI-side follow-up for a successful quick log. Sync remains
     * deferred until the undo window closes, but the rest timer must restart
     * after every tap rather than after the snackbar disappears. */
    private val onQuickLogSaved: (isSend: Boolean) -> Unit = {},
) {

    /** One still-open quick-log sequence on the currently displayed variant. */
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

    private data class PendingQuickLog(
        val climbUuid: String,
        val angle: Long,
        val isSend: Boolean,
        val before: ActiveQuickLog?,
        val after: ActiveQuickLog?,
        val sendEntryUuid: String?,
        val previousHistory: com.cruxcoach.data.repository.ClimbHistoryEntry?,
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

    fun updateIsSend(isSend: Boolean) {
        state.update { it.copy(ascent = it.ascent.copy(isSend = isSend)) }
    }

    fun updateBidCount(count: Int) {
        state.update { it.copy(ascent = it.ascent.copy(bidCount = count.coerceAtLeast(1))) }
    }

    fun updateQuality(quality: Int) {
        state.update { it.copy(ascent = it.ascent.copy(quality = quality.coerceIn(0, 5))) }
    }

    fun updateComment(comment: String) {
        state.update { it.copy(ascent = it.ascent.copy(comment = comment)) }
    }

    fun save() = save(isQuickLog = false)

    /** Log the common case without opening the detail form. */
    fun quickLog(isSend: Boolean) {
        finalizePendingQuickLog()
        state.update {
            it.copy(
                ascent = AscentFormState(isSend = isSend),
                isQuickLogging = true,
                quickLogFeedback = null,
            )
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
                withContext(Dispatchers.IO) {
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
                    val after = pending.after
                    if (before == null) {
                        after?.let { personalBoardRepo.deleteBid(it.entryUuid) }
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
                    it.copy(
                        userAscents = updated,
                        isQuickLogging = false,
                        quickLogFeedback = null,
                    )
                }
            }
                if (pending.isSend) sessionManager.undoRecordedAscent()
                else sessionManager.undoRecordedBid()
                zoneManager.recompute()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                state.update {
                    it.copy(
                        isQuickLogging = false,
                        quickLogFeedback = null,
                        quickLogFailed = true,
                    )
                }
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
            delay(7_000)
            if (pendingQuickLog === pending) finalizePendingQuickLog()
        }
    }

    private fun quickSendInput(
        active: ActiveQuickLog,
        attemptsToTop: Long,
        now: String,
        climbFrames: String,
        framesCount: Long,
        difficulty: Long?,
    ) = QuickLogSendInput(
        uuid = active.entryUuid,
        climbUuid = active.climbUuid,
        angle = active.angle,
        isMirror = active.isMirror,
        bidCount = attemptsToTop,
        difficulty = difficulty,
        climbedAt = now,
        climbName = active.climbName,
        difficultyAverage = active.difficultyAverage,
        climbFrames = climbFrames,
        framesCount = framesCount,
        boardBrand = active.boardBrand,
        layoutId = active.layoutId,
    )

    private fun save(isQuickLog: Boolean) {
        val s = state.value
        // Close on the way out. The bare `return` left the dialog standing and
        // every further tap did the same nothing, which reads as a hang rather
        // than as "not possible here". The button that opens this is gated on
        // the same condition; this is the second line of defence.
        val climb = s.climb ?: run {
            dismissDialog()
            return
        }
        val form = s.ascent
        val editUuid = form.editingUuid
        val entryUuid = editUuid ?: UUID.randomUUID().toString()
        val climbUuid = currentClimbUuid()

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                var effectiveEntryUuid = entryUuid
                var quickBefore: ActiveQuickLog? = null
                var quickAfter: ActiveQuickLog? = null
                var sendEntryUuid: String? = null
                val previousHistory = if (isQuickLog && form.isSend) {
                    try {
                        personalBoardRepo.observeClimbHistory().first()
                            .firstOrNull { it.climbUuid == climb.uuid && it.angle.toLong() == s.angle.toLong() }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                if (editUuid != null) {
                    // Route by entry type: a bid lives in the bids table, so
                    // updateAscent (ascents table) would match zero rows and
                    // silently drop the edit. Bids carry no quality.
                    if (form.isSend) {
                        personalBoardRepo.updateAscent(
                            uuid = editUuid,
                            bidCount = form.bidCount.toLong(),
                            quality = if (form.quality > 0) form.quality.toLong() else null,
                            comment = form.comment.ifBlank { null }
                        )
                    } else {
                        personalBoardRepo.updateBid(
                            uuid = editUuid,
                            bidCount = form.bidCount.toLong(),
                            comment = form.comment.ifBlank { null }
                        )
                    }
                } else {
                    val now = DateTimeUtil.nowIso()
                    val compatibleOpenLog = activeQuickLog?.takeIf {
                        isQuickLog &&
                            it.climbUuid == climb.uuid &&
                            it.angle == s.angle.toLong() &&
                            it.isMirror == s.isMirrored
                    }
                    quickBefore = compatibleOpenLog
                    if (form.isSend) {
                        // A quick send closes the open sequence: previous
                        // failed burns become the send's attempts-to-top and
                        // the separate bid row disappears from the logbook.
                        val attemptsToTop = if (isQuickLog) {
                            (compatibleOpenLog?.attemptCount ?: 0L) + 1L
                        } else {
                            form.bidCount.toLong()
                        }
                        if (compatibleOpenLog != null) {
                            effectiveEntryUuid = compatibleOpenLog.entryUuid
                            personalBoardRepo.promoteQuickBidToSend(
                                quickSendInput(
                                    active = compatibleOpenLog,
                                    attemptsToTop = attemptsToTop,
                                    now = now,
                                    climbFrames = climb.frames,
                                    framesCount = climb.framesCount,
                                    difficulty = climb.difficultyAverage?.toLong(),
                                )
                            )
                        } else {
                            personalBoardRepo.insertAscent(
                                uuid = effectiveEntryUuid,
                                climbUuid = climb.uuid,
                                angle = s.angle.toLong(),
                                isMirror = s.isMirrored,
                                attemptId = 0,
                                bidCount = attemptsToTop,
                                quality = if (form.quality > 0) form.quality.toLong() else null,
                                difficulty = climb.difficultyAverage?.toLong(),
                                isBenchmark = form.isBenchmark,
                                comment = form.comment.ifBlank { null },
                                climbedAt = now,
                                synced = false,
                                climbName = climb.name,
                                difficultyAverage = climb.difficultyAverage,
                                climbFrames = climb.frames,
                                framesCount = climb.framesCount,
                                boardBrand = climb.boardBrand,
                                layoutId = climb.layoutId,
                            )
                        }
                        sendEntryUuid = effectiveEntryUuid
                        // Append the SEND to the local "Verlauf" history log.
                        // Only sends are recorded here — never attempts/bids.
                        try {
                            personalBoardRepo.recordClimbHistory(
                                climbUuid = climb.uuid,
                                climbName = climb.name,
                                angle = s.angle.toLong(),
                                difficultyAverage = climb.difficultyAverage,
                                boardBrand = climb.boardBrand,
                                layoutId = climb.layoutId,
                                climbedAt = now,
                                recordedAt = now,
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // The primary log is already durable. History is
                            // a convenience index and must not turn a saved
                            // send into a retry that would duplicate it.
                        }
                        if (isQuickLog) activeQuickLog = null
                    } else {
                        if (isQuickLog && compatibleOpenLog != null) {
                            effectiveEntryUuid = compatibleOpenLog.entryUuid
                            quickAfter = compatibleOpenLog.copy(
                                attemptCount = compatibleOpenLog.attemptCount + 1L,
                            )
                            personalBoardRepo.updateBid(
                                uuid = effectiveEntryUuid,
                                bidCount = quickAfter.attemptCount,
                                comment = null,
                            )
                        } else {
                            quickAfter = ActiveQuickLog(
                                entryUuid = effectiveEntryUuid,
                                climbUuid = climb.uuid,
                                angle = s.angle.toLong(),
                                isMirror = s.isMirrored,
                                attemptCount = form.bidCount.toLong(),
                                climbedAt = now,
                                climbName = climb.name,
                                difficultyAverage = climb.difficultyAverage,
                                boardBrand = climb.boardBrand,
                                layoutId = climb.layoutId,
                            )
                            personalBoardRepo.insertBid(
                                uuid = effectiveEntryUuid,
                                climbUuid = climb.uuid,
                                angle = s.angle.toLong(),
                                isMirror = s.isMirrored,
                                bidCount = form.bidCount.toLong(),
                                comment = form.comment.ifBlank { null },
                                climbedAt = now,
                                synced = false,
                                climbName = climb.name,
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    state.value.userAscents
                }
                if (isQuickLog && editUuid == null) {
                    val pending = PendingQuickLog(
                        climbUuid = climbUuid,
                        angle = s.angle.toLong(),
                        isSend = form.isSend,
                        before = quickBefore,
                        after = quickAfter,
                        sendEntryUuid = sendEntryUuid,
                        previousHistory = previousHistory,
                    )
                    pendingQuickLog = pending
                    schedulePendingFinalization(pending)
                }
                // Callers use isQuickLogging=false as the completion boundary.
                // Fire the immediate quick-log callback first so a completed
                // save can never be observed before its rest-timer follow-up.
                if (editUuid == null && isQuickLog) onQuickLogSaved(form.isSend)
                state.update { current ->
                    val isSameVariant = current.climb?.uuid == climb.uuid &&
                        current.angle.toLong() == s.angle.toLong() &&
                        current.isMirrored == s.isMirrored
                    if (!isSameVariant) return@update current.copy(isQuickLogging = false)
                    current.copy(
                        ascent = AscentFormState(),
                        userAscents = updatedAscents,
                        isQuickLogging = false,
                        quickLogFeedback = if (isQuickLog && editUuid == null) {
                            QuickLogFeedback(
                                eventId = UUID.randomUUID().toString(),
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
                    climbNavState.statusDataChanged = true
                    climbNavState.changedClimbUuids.add(climbUuid)
                    if (form.isSend) sessionManager.recordAscent()
                    else sessionManager.recordBid()
                    if (!isQuickLog) onAscentSaved(form.isSend)
                    zoneManager.recompute()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                pendingFinalizationJob?.cancel()
                pendingFinalizationJob = null
                pendingQuickLog = null
                state.update { current ->
                    val isSameVariant = current.climb?.uuid == climb.uuid &&
                        current.angle.toLong() == s.angle.toLong() &&
                        current.isMirrored == s.isMirrored
                    current.copy(
                        isQuickLogging = false,
                        quickLogFeedback = null,
                        quickLogFailed = isQuickLog && isSameVariant,
                    )
                }
            }
        }
    }

    fun edit(ascent: AscentWithClimb) {
        finishQuickSequence()
        state.update { it.copy(ascent = AscentFormState(
            showDialog = true,
            editingUuid = ascent.uuid,
            isSend = ascent.isSend,
            bidCount = ascent.bidCount.toInt().coerceAtLeast(1),
            quality = (ascent.quality?.toInt() ?: 0).coerceIn(0, 5),
            comment = ascent.comment ?: ""
        )) }
    }

    fun requestDelete(uuid: String) {
        finishQuickSequence()
        state.update { it.copy(ascent = it.ascent.copy(deleteConfirmUuid = uuid)) }
    }

    fun dismissDeleteConfirm() {
        state.update { it.copy(ascent = it.ascent.copy(deleteConfirmUuid = null)) }
    }

    fun confirmDelete() {
        val uuid = state.value.ascent.deleteConfirmUuid ?: return
        val entry = state.value.userAscents.find { it.uuid == uuid }
        val climbUuid = currentClimbUuid()
        scope.launch {
            withContext(Dispatchers.IO) {
                if (entry?.isSend == false) personalBoardRepo.deleteBid(uuid)
                else personalBoardRepo.deleteAscent(uuid)
                val updatedAscents = personalBoardRepo.getUserHistoryForClimb(climbUuid)
                state.update { it.copy(ascent = it.ascent.copy(deleteConfirmUuid = null), userAscents = updatedAscents) }
            }
            climbNavState.statusDataChanged = true
            climbNavState.changedClimbUuids.add(climbUuid)
        }
    }

}
