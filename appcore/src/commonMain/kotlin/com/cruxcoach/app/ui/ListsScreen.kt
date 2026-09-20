package com.cruxcoach.app.ui

import com.cruxcoach.app.playlist.ListDetailPresenter
import com.cruxcoach.app.playlist.ListDetailState
import com.cruxcoach.app.playlist.ListMember
import com.cruxcoach.app.playlist.ListsPresenter
import com.cruxcoach.app.playlist.ListsState
import com.cruxcoach.app.playlist.PlanStep
import com.cruxcoach.app.logbook.BoardStatsComputer
import com.cruxcoach.app.settings.GradeScale
import com.cruxcoach.data.repository.Climb_lists
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder

class ClimbListRowUi(
    val id: Long,
    val name: String,
    val climbCount: Long,
    val isBuiltin: Boolean,
    /** The built-in Ignored list; Favorites is the other built-in. */
    val isIgnored: Boolean,
    val isGenerated: Boolean,
    val hasPlaybackPlan: Boolean,
    /** True while the add-to-list sheet's climb is a member. */
    val containsClimb: Boolean,
)

class ListsScreenState(
    val isLoading: Boolean,
    val lists: List<ClimbListRowUi>,
    val showCreateDialog: Boolean,
    val newListName: String,
    /** 0 = no confirmation pending. */
    val deleteConfirmListId: Long,
    val membershipClimbUuid: String,
    val isFavorite: Boolean,
    val isIgnored: Boolean,
    /** none | loadFailed | createFailed | deleteFailed | duplicateName | membershipFailed */
    val errorCode: String,
)

class ListMemberUi(
    val climbUuid: String,
    val name: String,
    val grade: String,
    val setter: String,
    val boardWire: String,
    val addedAt: String,
    /** False when the catalogue on this device cannot resolve the climb. */
    val isAvailable: Boolean,
)

class PlanStepUi(
    val stepId: Long,
    val isRest: Boolean,
    val restSeconds: Int,
    val climbUuid: String,
    val name: String,
    val grade: String,
    /** -1 when the step pins no angle. */
    val angle: Int,
    val isAvailable: Boolean,
)

class ListDetailScreenState(
    val listId: Long,
    val isLoading: Boolean,
    val name: String,
    val isBuiltin: Boolean,
    val isIgnoredList: Boolean,
    val isGenerated: Boolean,
    val members: List<ListMemberUi>,
    val planSteps: List<PlanStepUi>,
    val hasPlaybackPlan: Boolean,
    /** list | shuffle */
    val orderCode: String,
    /** manual | afterSend | afterLog */
    val advanceCode: String,
    val defaultRestSeconds: Int,
    /** Angle a plan step without a pinned angle plays at (the browser's angle). */
    val defaultAngle: Int,
    val unavailableCount: Int,
    val showRenameDialog: Boolean,
    val renameValue: String,
    val editRestStepIds: List<Long>,
    /** none | loadFailed | notFound | renameFailed | editFailed | planFailed */
    val errorCode: String,
)

internal object ListCodes {
    fun order(value: ListPlaybackOrder): String = when (value) {
        ListPlaybackOrder.LIST -> "list"
        ListPlaybackOrder.SHUFFLE -> "shuffle"
    }

    fun order(code: String): ListPlaybackOrder? = when (code) {
        "list" -> ListPlaybackOrder.LIST
        "shuffle" -> ListPlaybackOrder.SHUFFLE
        else -> null
    }

    fun advance(value: ListPlaybackAdvance): String = when (value) {
        ListPlaybackAdvance.MANUAL -> "manual"
        ListPlaybackAdvance.AFTER_SEND -> "afterSend"
        ListPlaybackAdvance.AFTER_LOG -> "afterLog"
    }

    fun advance(code: String): ListPlaybackAdvance? = when (code) {
        "manual" -> ListPlaybackAdvance.MANUAL
        "afterSend" -> ListPlaybackAdvance.AFTER_SEND
        "afterLog" -> ListPlaybackAdvance.AFTER_LOG
        else -> null
    }

    fun code(name: String): String = name.lowercase().split("_").mapIndexed { i, part ->
        if (i == 0) part else part.replaceFirstChar { c -> c.uppercaseChar() }
    }.joinToString("")
}

/** Climb lists: the two built-ins plus the user's own, and the add-to-list sheet. */
class ListsScreenModel(private val presenter: ListsPresenter) {

    val currentState: ListsScreenState get() = map(presenter.state.value)

    fun watch(onState: (ListsScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun refresh() = presenter.refresh()
    fun close() = presenter.close()
    fun consumeError() = presenter.consumeError()

    fun showCreateDialog() = presenter.showCreateDialog()
    fun dismissCreateDialog() = presenter.dismissCreateDialog()
    fun setNewListName(name: String) = presenter.updateNewListName(name)
    fun createList() = presenter.createList()
    fun requestDeleteList(listId: Long) = presenter.requestDeleteList(listId)
    fun dismissDeleteConfirm() = presenter.dismissDeleteConfirm()
    fun confirmDeleteList() = presenter.confirmDeleteList()

    /** Opens the add-to-list sheet for one climb. */
    fun openClimb(climbUuid: String, angle: Int) = presenter.openClimb(climbUuid, angle)
    fun closeClimb() = presenter.closeClimb()
    fun toggleList(listId: Long) = presenter.toggleList(listId)
    fun createListAndAdd() = presenter.createListAndAdd()
    fun toggleFavorite() = presenter.toggleFavorite()
    fun toggleIgnored() = presenter.toggleIgnored()

    private fun map(state: ListsState) = ListsScreenState(
        isLoading = state.isLoading,
        lists = state.lists.map { row(it, it.id in state.membershipListIds) },
        showCreateDialog = state.showCreateDialog,
        newListName = state.newListName,
        deleteConfirmListId = state.deleteConfirmListId ?: 0L,
        membershipClimbUuid = state.membershipClimbUuid,
        isFavorite = state.isFavorite,
        isIgnored = state.isIgnored,
        errorCode = ListCodes.code(state.error.name),
    )

    private fun row(list: Climb_lists, containsClimb: Boolean) = ClimbListRowUi(
        id = list.id,
        name = list.name,
        climbCount = list.climbCount,
        isBuiltin = list.isBuiltin,
        isIgnored = list.isIgnored,
        isGenerated = list.generatorParams != null,
        hasPlaybackPlan = list.hasPlaybackPlan,
        containsClimb = containsClimb,
    )
}

/** One list: its members, its optional playback plan and its playback settings. */
class ListDetailScreenModel(
    private val presenter: ListDetailPresenter,
    /** "FRENCH" or "V_SCALE", as `SettingsModel.gradeScale` spells it. */
    gradeScale: String,
    /** Same angle the browser and the presenter resolve catalogue rows at. */
    private val defaultAngle: Int = 40,
) {
    private val scale = if (gradeScale == "V_SCALE") GradeScale.V_SCALE else GradeScale.FRENCH

    val currentState: ListDetailScreenState get() = map(presenter.state.value)

    fun watch(onState: (ListDetailScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun refresh() = presenter.refresh()
    fun close() = presenter.close()
    fun consumeError() = presenter.consumeError()

    fun showRenameDialog() = presenter.showRenameDialog()
    fun dismissRenameDialog() = presenter.dismissRenameDialog()
    fun setRenameValue(value: String) = presenter.updateRenameValue(value)
    fun confirmRename() = presenter.confirmRename()

    fun removeFromList(climbUuid: String) = presenter.removeFromList(climbUuid)

    fun setOrder(code: String) {
        ListCodes.order(code)?.let { presenter.setPlaybackOrder(it) }
    }

    fun setAdvance(code: String) {
        ListCodes.advance(code)?.let { presenter.setPlaybackAdvance(it) }
    }

    fun setDefaultRestSeconds(seconds: Int) = presenter.setPlaybackRestSeconds(seconds.toLong())

    fun resetPlanFromList() = presenter.resetPlanFromList()
    fun clearPlan() = presenter.clearPlan()

    /** [afterStepId] 0 appends. */
    fun addRest(seconds: Int, afterStepId: Long) =
        presenter.addRest(seconds.toLong(), afterStepId.takeIf { it != 0L })

    fun duplicateRest(stepId: Long) = presenter.duplicateRest(stepId)
    fun duplicateClimb(stepId: Long) = presenter.duplicateClimb(stepId)
    fun removeStep(stepId: Long) = presenter.removeStep(stepId)
    fun moveStep(fromIndex: Int, toIndex: Int) = presenter.moveStep(fromIndex, toIndex)
    fun commitOrder(orderedStepIds: List<Long>) = presenter.commitOrder(orderedStepIds)
    fun showEditRests(stepIds: List<Long>) = presenter.showEditRests(stepIds)
    fun dismissEditRests() = presenter.dismissEditRests()
    fun setSelectedRestSeconds(seconds: Int) = presenter.updateSelectedRestSeconds(seconds.toLong())
    fun removeSelectedRests() = presenter.removeSelectedRests()

    private fun map(state: ListDetailState) = ListDetailScreenState(
        listId = state.listId,
        isLoading = state.isLoading,
        name = state.name,
        isBuiltin = state.isBuiltin,
        isIgnoredList = state.isIgnoredList,
        isGenerated = state.isGenerated,
        members = state.members.map { member(it) },
        planSteps = state.planSteps.map { step(it) },
        hasPlaybackPlan = state.hasPlaybackPlan,
        orderCode = ListCodes.order(state.playbackOrder),
        advanceCode = ListCodes.advance(state.playbackAdvance),
        defaultRestSeconds = state.playbackRestSeconds.toInt(),
        defaultAngle = defaultAngle,
        unavailableCount = state.unavailableCount,
        showRenameDialog = state.showRenameDialog,
        renameValue = state.renameValue,
        editRestStepIds = state.editRestStepIds,
        errorCode = ListCodes.code(state.error.name),
    )

    private fun gradeLabel(difficulty: Double?): String =
        difficulty?.let { BoardStatsComputer.formatDifficulty(it, scale) } ?: ""

    private fun member(member: ListMember) = ListMemberUi(
        climbUuid = member.climbUuid,
        name = member.climb?.name ?: "",
        grade = gradeLabel(member.climb?.difficultyAverage),
        setter = member.climb?.setterUsername ?: "",
        boardWire = member.climb?.boardBrand ?: "",
        addedAt = member.addedAt,
        isAvailable = member.climb != null,
    )

    private fun step(step: PlanStep) = PlanStepUi(
        stepId = step.stepId,
        isRest = step.isRest,
        restSeconds = (step.restSeconds ?: 0L).toInt(),
        climbUuid = step.climbUuid ?: "",
        name = step.climb?.name ?: "",
        grade = gradeLabel(step.climb?.difficultyAverage),
        angle = step.angle?.toInt() ?: -1,
        isAvailable = step.isRest || step.climb != null,
    )
}
