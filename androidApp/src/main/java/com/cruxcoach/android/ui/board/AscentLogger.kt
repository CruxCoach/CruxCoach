package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.util.DateTimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val onAscentSaved: (isSend: Boolean) -> Unit
) {

    private data class PendingQuickLog(
        val entryUuid: String,
        val climbUuid: String,
        val angle: Long,
        val isSend: Boolean,
        val previousHistory: com.cruxcoach.data.repository.ClimbHistoryEntry?,
    )

    private var pendingQuickLog: PendingQuickLog? = null

    fun showDialog() {
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

    fun undoQuickLog() {
        val pending = pendingQuickLog ?: return
        pendingQuickLog = null
        scope.launch {
            withContext(Dispatchers.IO) {
                if (pending.isSend) {
                    personalBoardRepo.deleteAscent(pending.entryUuid)
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
                    personalBoardRepo.deleteBid(pending.entryUuid)
                }
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
        }
    }

    private fun finalizePendingQuickLog() {
        val pending = pendingQuickLog ?: return
        pendingQuickLog = null
        onAscentSaved(pending.isSend)
    }

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
            withContext(Dispatchers.IO) {
                val previousHistory = if (isQuickLog && form.isSend) {
                    personalBoardRepo.observeClimbHistory().first()
                        .firstOrNull { it.climbUuid == climb.uuid && it.angle.toLong() == s.angle.toLong() }
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
                    if (form.isSend) {
                        personalBoardRepo.insertAscent(
                            uuid = entryUuid,
                            climbUuid = climb.uuid,
                            angle = s.angle.toLong(),
                            isMirror = s.isMirrored,
                            attemptId = 0,
                            bidCount = form.bidCount.toLong(),
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
                        // Append the SEND to the local "Verlauf" history log.
                        // Only sends are recorded here — never attempts/bids.
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
                    } else {
                        personalBoardRepo.insertBid(
                            uuid = entryUuid,
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
                }
                val updatedAscents = personalBoardRepo.getUserHistoryForClimb(climbUuid)
                if (isQuickLog && editUuid == null) {
                    pendingQuickLog = PendingQuickLog(
                        entryUuid = entryUuid,
                        climbUuid = climbUuid,
                        angle = s.angle.toLong(),
                        isSend = form.isSend,
                        previousHistory = previousHistory,
                    )
                }
                state.update { current ->
                    current.copy(
                        ascent = AscentFormState(),
                        userAscents = updatedAscents,
                        isQuickLogging = false,
                        quickLogFeedback = if (isQuickLog && editUuid == null) {
                            QuickLogFeedback(entryUuid = entryUuid, isSend = form.isSend)
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
        }
    }

    fun edit(ascent: AscentWithClimb) {
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
