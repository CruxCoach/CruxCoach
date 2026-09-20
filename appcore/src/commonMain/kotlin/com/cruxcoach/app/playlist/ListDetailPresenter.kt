package com.cruxcoach.app.playlist

import com.cruxcoach.app.logbook.StateWatch
import com.cruxcoach.app.logbook.watchState
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import com.cruxcoach.data.repository.NewListPlaybackStep
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.inferAutoPlaybackRestSeconds
import com.cruxcoach.data.repository.playbackStepsWithAutoRests
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

/** A list member. [climb] is null when the catalogue on this device lacks it. */
data class ListMember(val climbUuid: String, val addedAt: String, val climb: ClimbWithStats?)

/** One row of the explicit plan: a climb at a pinned angle, or a rest. */
data class PlanStep(
    val stepId: Long,
    val isRest: Boolean,
    val restSeconds: Long?,
    val climbUuid: String?,
    val angle: Long?,
    val climb: ClimbWithStats?,
)

enum class ListDetailError { NONE, LOAD_FAILED, NOT_FOUND, RENAME_FAILED, EDIT_FAILED, PLAN_FAILED }

data class ListDetailState(
    val listId: Long = 0,
    val isLoading: Boolean = true,
    val name: String = "",
    val isBuiltin: Boolean = false,
    val isIgnoredList: Boolean = false,
    val isGenerated: Boolean = false,
    val members: List<ListMember> = emptyList(),
    /** Empty unless the list has an explicit plan. */
    val planSteps: List<PlanStep> = emptyList(),
    val hasPlaybackPlan: Boolean = false,
    val playbackOrder: ListPlaybackOrder = ListPlaybackOrder.LIST,
    val playbackAdvance: ListPlaybackAdvance = ListPlaybackAdvance.MANUAL,
    val playbackRestSeconds: Long = 0L,
    /** Members whose climb the local catalogue cannot resolve. */
    val unavailableCount: Int = 0,
    val showRenameDialog: Boolean = false,
    val renameValue: String = "",
    /** Rest rows being edited together. */
    val editRestStepIds: List<Long> = emptyList(),
    val error: ListDetailError = ListDetailError.NONE,
)

/**
 * Port of Android `BoardListDetailViewModel` (membership, rename, playback
 * settings) and the plan-editing half of `PlaylistDetailViewModel`. A list is
 * the user's explicit selection, so it is always shown in full, never scoped to
 * the active board.
 */
class ListDetailPresenter(
    private val personalBoardRepo: PersonalBoardRepository,
    private val listId: Long,
    private val climbLookup: ListClimbLookup = EmptyClimbLookup,
    /** Angle the catalogue rows are resolved at, and pinned by new plan steps. */
    private val defaultAngle: () -> Int = { 40 },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(ListDetailState(listId = listId))
    val state: StateFlow<ListDetailState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun watch(onState: (ListDetailState) -> Unit): StateWatch = scope.watchState(state, onState)

    fun refresh() {
        scope.launch {
            try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, error = ListDetailError.LOAD_FAILED) }
            }
        }
    }

    private suspend fun load() {
        val angle = defaultAngle()

        val loaded = withContext(ioDispatcher) {
            val list = personalBoardRepo.getClimbListById(listId)
            val entries = personalBoardRepo.getClimbListEntryUuids(listId, Int.MAX_VALUE, 0)
            val steps = personalBoardRepo.getPlaybackSteps(listId)
            val uuids = entries.map { it.first } + steps.mapNotNull { it.climbUuid }
            val climbs = climbLookup.resolve(uuids, angle)
            Loaded(list, entries, steps, climbs)
        }
        val list = loaded.list
        if (list == null) {
            _state.update { it.copy(isLoading = false, error = ListDetailError.NOT_FOUND) }
            return
        }
        val members = loaded.entries.map { (uuid, addedAt) ->
            ListMember(uuid, addedAt, loaded.climbs[normUuidKey(uuid)])
        }
        _state.update {
            it.copy(
                isLoading = false,
                name = list.name,
                isBuiltin = list.isBuiltin,
                isIgnoredList = list.isIgnored,
                isGenerated = list.generatorParams != null,
                members = members,
                planSteps = loaded.steps.map { row ->
                    PlanStep(
                        stepId = row.id,
                        isRest = row.isRest,
                        restSeconds = row.restSeconds,
                        climbUuid = row.climbUuid,
                        angle = row.angle,
                        climb = row.climbUuid?.let { uuid -> loaded.climbs[normUuidKey(uuid)] },
                    )
                },
                hasPlaybackPlan = loaded.steps.isNotEmpty(),
                // While a settings write is queued or running, the row is older
                // than what the user just chose: keep the state's values, or the
                // toggle visibly flips back.
                playbackOrder = if (pendingSettingsWrites == 0) list.playbackOrder else it.playbackOrder,
                playbackAdvance = if (pendingSettingsWrites == 0) list.playbackAdvance else it.playbackAdvance,
                playbackRestSeconds = if (pendingSettingsWrites == 0) list.playbackRestSeconds else it.playbackRestSeconds,
                unavailableCount = members.count { m -> m.climb == null },
                error = ListDetailError.NONE,
            )
        }
    }

    private class Loaded(
        val list: com.cruxcoach.data.repository.Climb_lists?,
        val entries: List<Pair<String, String>>,
        val steps: List<com.cruxcoach.data.repository.ListPlaybackStepRow>,
        val climbs: Map<String, ClimbWithStats>,
    )

    // --- Name ---

    fun showRenameDialog() = _state.update { it.copy(showRenameDialog = true, renameValue = it.name) }

    fun dismissRenameDialog() = _state.update { it.copy(showRenameDialog = false) }

    fun updateRenameValue(value: String) = _state.update { it.copy(renameValue = value) }

    fun confirmRename() {
        val name = _state.value.renameValue.trim()
        // The built-in lists carry fixed identities; their names are not the user's.
        if (name.isEmpty() || _state.value.isBuiltin) {
            _state.update { it.copy(showRenameDialog = false) }
            return
        }
        mutate(ListDetailError.RENAME_FAILED) {
            personalBoardRepo.renameClimbList(listId, name)
            _state.update { it.copy(showRenameDialog = false) }
        }
    }

    // --- Membership ---

    fun removeFromList(climbUuid: String) = mutate(ListDetailError.EDIT_FAILED) {
        // Also drops the climb's plan steps, so the plan cannot outlive membership.
        climbLookup.equivalentUuids(climbUuid).forEach { personalBoardRepo.removeClimbFromList(listId, it) }
    }

    // --- Playback settings ---

    fun setPlaybackOrder(order: ListPlaybackOrder) = savePlayback(order = order)

    fun setPlaybackAdvance(advance: ListPlaybackAdvance) = savePlayback(advance = advance)

    fun setPlaybackRestSeconds(seconds: Long) = savePlayback(restSeconds = seconds.coerceIn(0L, MAX_REST_SECONDS))

    /** Settings writes queued or running; a [load] must not overwrite them. */
    private var pendingSettingsWrites = 0

    private fun savePlayback(
        order: ListPlaybackOrder? = null,
        advance: ListPlaybackAdvance? = null,
        restSeconds: Long? = null,
    ) {
        // Apply to state first: the three settings are independent controls, and a
        // second change must not be computed from a snapshot the first one already
        // superseded (it would silently write the old value back).
        pendingSettingsWrites++
        var next = Triple(ListPlaybackOrder.LIST, ListPlaybackAdvance.MANUAL, 0L)
        _state.update { s ->
            next = Triple(
                order ?: s.playbackOrder,
                advance ?: s.playbackAdvance,
                restSeconds ?: s.playbackRestSeconds,
            )
            s.copy(playbackOrder = next.first, playbackAdvance = next.second, playbackRestSeconds = next.third)
        }
        // Persist the triple this call computed. Reading the state again at write
        // time looks safer but is not: the initial load, or any other refresh,
        // can land in between and the write then stores the row's old values
        // back over the user's change.
        val (order0, advance0, rest0) = next
        mutate(ListDetailError.EDIT_FAILED, reload = false) {
            try {
                personalBoardRepo.updatePlaybackSettings(listId, order0, advance0, rest0)
            } finally {
                pendingSettingsWrites--
            }
        }
    }

    // --- Explicit plan ---

    /** Rebuilds the plan from unique membership, with an inferred rest between climbs. */
    fun resetPlanFromList() {
        val angle = defaultAngle().toLong()
        mutate(ListDetailError.PLAN_FAILED) {
            val current = personalBoardRepo.getPlaybackSteps(listId)
            val climbUuids = personalBoardRepo.getClimbListEntryUuids(listId, Int.MAX_VALUE, 0).map { it.first }
            personalBoardRepo.replacePlaybackSteps(
                listId,
                playbackStepsWithAutoRests(
                    climbUuids = climbUuids,
                    angle = angle,
                    restSeconds = inferAutoPlaybackRestSeconds(
                        previousRestSeconds = current.map { it.restSeconds },
                        configuredFallbackSeconds = _state.value.playbackRestSeconds,
                    ),
                ),
            )
        }
    }

    fun clearPlan() = mutate(ListDetailError.PLAN_FAILED) {
        personalBoardRepo.replacePlaybackSteps(listId, emptyList())
    }

    /** Appends a rest, or inserts one after [afterStepId]. */
    fun addRest(seconds: Long, afterStepId: Long? = null) {
        val steps = _state.value.planSteps
        // A leading rest is ignored by playback because no climb owns it.
        if (steps.none { !it.isRest }) return
        val duration = seconds.coerceIn(MIN_REST_SECONDS, MAX_REST_SECONDS)
        val replacement = afterStepId?.let { planWithRestInsertedAfter(steps, it, duration) }
        if (afterStepId != null && replacement == null) return
        mutate(ListDetailError.PLAN_FAILED) {
            if (replacement == null) personalBoardRepo.addPlaybackRest(listId, duration)
            else personalBoardRepo.replacePlaybackSteps(listId, replacement)
        }
    }

    fun duplicateRest(stepId: Long) {
        val steps = _state.value.planSteps
        val rest = steps.firstOrNull { it.stepId == stepId && it.isRest } ?: return
        val replacement = planWithRestInsertedAfter(steps, stepId, rest.restSeconds ?: 60L) ?: return
        mutate(ListDetailError.PLAN_FAILED) { personalBoardRepo.replacePlaybackSteps(listId, replacement) }
    }

    fun duplicateClimb(stepId: Long) {
        val steps = _state.value.planSteps
        val index = steps.indexOfFirst { it.stepId == stepId && !it.isRest }
        if (index < 0) return
        val replacement = steps.map { it.toNewStep() }.toMutableList()
        replacement.add(index + 1, steps[index].toNewStep())
        mutate(ListDetailError.PLAN_FAILED) { personalBoardRepo.replacePlaybackSteps(listId, replacement) }
    }

    fun removeStep(stepId: Long) {
        _state.update { s -> s.copy(planSteps = s.planSteps.filter { it.stepId != stepId }) }
        mutate(ListDetailError.PLAN_FAILED) { personalBoardRepo.removePlaybackStep(stepId) }
    }

    fun moveStep(fromIndex: Int, toIndex: Int) {
        val steps = _state.value.planSteps
        if (fromIndex !in steps.indices || toIndex !in steps.indices || fromIndex == toIndex) return
        val reordered = steps.toMutableList()
        reordered.add(toIndex, reordered.removeAt(fromIndex))
        _state.update { it.copy(planSteps = reordered) }
        mutate(ListDetailError.PLAN_FAILED) { personalBoardRepo.movePlaybackStep(listId, fromIndex, toIndex) }
    }

    /** Persists an exact drag order while retaining row ids. */
    fun commitOrder(orderedStepIds: List<Long>) = mutate(ListDetailError.PLAN_FAILED) {
        personalBoardRepo.reorderPlaybackSteps(listId, orderedStepIds)
    }

    fun showEditRests(stepIds: List<Long>) {
        val restIds = stepIds.distinct().filter { id -> _state.value.planSteps.any { it.stepId == id && it.isRest } }
        if (restIds.isNotEmpty()) _state.update { it.copy(editRestStepIds = restIds) }
    }

    fun dismissEditRests() = _state.update { it.copy(editRestStepIds = emptyList()) }

    fun updateSelectedRestSeconds(seconds: Long) {
        val ids = _state.value.editRestStepIds
        if (ids.isEmpty()) return
        val duration = seconds.coerceIn(MIN_REST_SECONDS, MAX_REST_SECONDS)
        mutate(ListDetailError.PLAN_FAILED) {
            personalBoardRepo.updatePlaybackRestSeconds(ids, duration)
            _state.update { it.copy(editRestStepIds = emptyList()) }
        }
    }

    fun removeSelectedRests() {
        val ids = _state.value.editRestStepIds
        if (ids.isEmpty()) return
        mutate(ListDetailError.PLAN_FAILED) {
            personalBoardRepo.removePlaybackSteps(ids)
            _state.update { it.copy(editRestStepIds = emptyList()) }
        }
    }

    fun consumeError() = _state.update { it.copy(error = ListDetailError.NONE) }

    fun close() = scope.cancel()

    private fun PlanStep.toNewStep() = NewListPlaybackStep(climbUuid, angle, restSeconds)

    /**
     * [reload] re-reads the row after the write, which plan edits need because
     * the database assigns step ids. Settings edits must NOT reload: a reload
     * that lands after a newer edit has already updated the state overwrites it
     * with the older row, and the user watches their second toggle flip back.
     */
    private fun mutate(onFailure: ListDetailError, reload: Boolean = true, block: suspend () -> Unit) {
        scope.launch {
            try {
                withContext(ioDispatcher) { block() }
                if (reload) load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = onFailure) }
            }
        }
    }

    companion object {
        const val MIN_REST_SECONDS = 10L
        const val MAX_REST_SECONDS = 3600L
    }
}

/** Pure plan edit: the rendered plan with one rest inserted after [afterStepId]. */
internal fun planWithRestInsertedAfter(
    steps: List<PlanStep>,
    afterStepId: Long,
    seconds: Long,
): List<NewListPlaybackStep>? {
    val afterIndex = steps.indexOfFirst { it.stepId == afterStepId }
    if (afterIndex < 0) return null
    val out = steps.map { NewListPlaybackStep(it.climbUuid, it.angle, it.restSeconds) }.toMutableList()
    out.add(
        afterIndex + 1,
        NewListPlaybackStep(
            climbUuid = null,
            restSeconds = seconds.coerceIn(ListDetailPresenter.MIN_REST_SECONDS, ListDetailPresenter.MAX_REST_SECONDS),
        ),
    )
    return out
}
