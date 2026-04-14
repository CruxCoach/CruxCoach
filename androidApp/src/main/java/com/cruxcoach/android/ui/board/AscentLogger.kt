package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.data.repository.AuroraAscentWithClimb
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.util.DateTimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
        state.update { it.copy(ascent = it.ascent.copy(quality = quality.coerceIn(0, 3))) }
    }

    fun updateComment(comment: String) {
        state.update { it.copy(ascent = it.ascent.copy(comment = comment)) }
    }

    fun save() {
        val s = state.value
        val climb = s.climb ?: return
        val form = s.ascent
        val editUuid = form.editingUuid
        val climbUuid = currentClimbUuid()

        scope.launch {
            withContext(Dispatchers.IO) {
                if (editUuid != null) {
                    personalBoardRepo.updateAscent(
                        uuid = editUuid,
                        bidCount = form.bidCount.toLong(),
                        quality = if (form.quality > 0) form.quality.toLong() else null,
                        comment = form.comment.ifBlank { null }
                    )
                } else {
                    val uuid = UUID.randomUUID().toString()
                    val now = DateTimeUtil.nowIso()
                    if (form.isSend) {
                        personalBoardRepo.insertAscent(
                            uuid = uuid,
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
                            framesCount = climb.framesCount
                        )
                    } else {
                        personalBoardRepo.insertBid(
                            uuid = uuid,
                            climbUuid = climb.uuid,
                            angle = s.angle.toLong(),
                            isMirror = s.isMirrored,
                            bidCount = form.bidCount.toLong(),
                            comment = form.comment.ifBlank { null },
                            climbedAt = now,
                            synced = false,
                            climbName = climb.name,
                            difficultyAverage = climb.difficultyAverage
                        )
                    }
                }
                val updatedAscents = personalBoardRepo.getUserHistoryForClimb(climbUuid)
                state.update { it.copy(
                    ascent = AscentFormState(),
                    userAscents = updatedAscents
                ) }
            }
            if (editUuid == null) {
                climbNavState.statusDataChanged = true
                climbNavState.changedClimbUuids.add(climbUuid)
                if (form.isSend) sessionManager.recordAscent()
                else sessionManager.recordBid()
                onAscentSaved(form.isSend)
                zoneManager.recompute()
            }
        }
    }

    fun edit(ascent: AuroraAscentWithClimb) {
        state.update { it.copy(ascent = AscentFormState(
            showDialog = true,
            editingUuid = ascent.uuid,
            isSend = ascent.isSend,
            bidCount = ascent.bidCount.toInt().coerceAtLeast(1),
            quality = (ascent.quality?.toInt() ?: 0).coerceIn(0, 3),
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
