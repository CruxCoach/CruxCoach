package com.cruxcoach.android.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.boardcell.BoardCellAvailability
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.SessionVisibility
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.util.safeLaunch
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import com.cruxcoach.data.repository.NewListPlaybackStep
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.inferAutoPlaybackRestSeconds
import com.cruxcoach.data.repository.playbackStepsWithAutoRests
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** One rendered playlist row — a resolved climb, an unresolvable climb
 *  (catalogue not downloaded), or a rest block. */
data class PlaylistUiEntry(
    val entryId: Long,
    val isRest: Boolean,
    val restSeconds: Long? = null,
    val climbUuid: String? = null,
    val angle: Long? = null,
    /** Null for rest rows AND for climbs missing from the local catalogue. */
    val climb: ClimbWithStats? = null,
)

data class PlaylistDetailState(
    val listId: Long = 0,
    val name: String = "",
    val isBuiltin: Boolean = false,
    val isGenerated: Boolean = false,
    val playbackAdvance: ListPlaybackAdvance = ListPlaybackAdvance.MANUAL,
    val playbackOrder: ListPlaybackOrder = ListPlaybackOrder.LIST,
    val playbackRestSeconds: Long = 0L,
    val entries: List<PlaylistUiEntry> = emptyList(),
    val gradeScale: GradeScale = GradeScale.FRENCH,
    val unavailableCount: Int = 0,
    /** Reorder mode toggles per-row up/down handles. */
    val editMode: Boolean = false,
    val showRenameDialog: Boolean = false,
    val renameValue: String = "",
    /** Rest rows edited together (one row, or uniform rests between attempts). */
    val editRestEntryIds: List<Long> = emptyList(),
    val playbackBoardError: Boolean = false,
)

enum class BoardPlaylistTarget {
    NONE,
    RECOVERING,
    ACTIVE,
}

/** Pure plan edit used by targeted pause insertion and its regression tests. */
internal fun playbackStepsWithRestInsertedAfter(
    entries: List<PlaylistUiEntry>,
    afterEntryId: Long,
    seconds: Long,
): List<NewListPlaybackStep>? {
    val afterIndex = entries.indexOfFirst { it.entryId == afterEntryId }
    if (afterIndex < 0) return null
    return entries.map { entry ->
        NewListPlaybackStep(entry.climbUuid, entry.angle, entry.restSeconds)
    }.toMutableList().apply {
        add(
            afterIndex + 1,
            NewListPlaybackStep(
                climbUuid = null,
                restSeconds = seconds.coerceIn(10L, 3600L),
            ),
        )
    }
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val personalBoardRepo: PersonalBoardRepository,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
    val climbNavState: com.cruxcoach.android.ui.navigation.ClimbNavigationState,
    private val playback: com.cruxcoach.android.data.PlaylistPlaybackCoordinator,
    private val boardCellManager: BoardCellManager,
) : ViewModel() {

    private val listId: Long = savedStateHandle.get<String>("listId")?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(PlaylistDetailState(listId = listId))
    val state = _state.asStateFlow()

    /** Which single live target this saved list can be added to right now. */
    val boardPlaylistTarget = boardCellManager.snapshots.map { snapshot ->
        when {
            snapshot == null || boardCellManager.localNodeId() !in snapshot.members ->
                BoardPlaylistTarget.NONE
            snapshot.availability == BoardCellAvailability.ACTIVE ->
                BoardPlaylistTarget.ACTIVE
            else -> BoardPlaylistTarget.RECOVERING
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        boardCellManager.snapshot()?.let { snapshot ->
            when {
                boardCellManager.localNodeId() !in snapshot.members -> BoardPlaylistTarget.NONE
                snapshot.availability == BoardCellAvailability.ACTIVE -> BoardPlaylistTarget.ACTIVE
                else -> BoardPlaylistTarget.RECOVERING
            }
        } ?: BoardPlaylistTarget.NONE,
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.safeLaunch(TAG) {
            val snapshot = withContext(Dispatchers.IO) { userPreferences.getBoardFilterSnapshot() }
            val (list, uiEntries) = withContext(Dispatchers.IO) {
                val list = personalBoardRepo.getClimbListById(listId)
                val rows = personalBoardRepo.getPlaybackSteps(listId)
                // Two-phase: resolve climb details from the board DB in one
                // batch; angle-agnostic since playlist rows pin their angle.
                val uuids = rows.mapNotNull { it.climbUuid }.distinct()
                val climbs = resolveClimbs(boardRepository, uuids)
                list to rows.map { row ->
                    PlaylistUiEntry(
                        entryId = row.id,
                        isRest = row.isRest,
                        restSeconds = row.restSeconds,
                        climbUuid = row.climbUuid,
                        angle = row.angle,
                        climb = row.climbUuid?.let { climbs[normUuidKey(it)] },
                    )
                }
            }
            _state.update {
                it.copy(
                    name = list?.name ?: "",
                    isBuiltin = list?.isBuiltin == true,
                    isGenerated = list?.generatorParams != null,
                    playbackAdvance = list?.playbackAdvance ?: ListPlaybackAdvance.MANUAL,
                    playbackOrder = list?.playbackOrder ?: ListPlaybackOrder.LIST,
                    playbackRestSeconds = list?.playbackRestSeconds ?: 0L,
                    entries = uiEntries,
                    gradeScale = snapshot.gradeScale,
                    unavailableCount = uiEntries.count { e -> !e.isRest && e.climb == null },
                )
            }
        }
    }

    fun toggleEditMode() = _state.update { it.copy(editMode = !it.editMode) }

    fun moveEntry(fromIndex: Int, toIndex: Int) {
        if (!moveEntryInState(fromIndex, toIndex)) return
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) { personalBoardRepo.movePlaybackStep(listId, fromIndex, toIndex) }
        }
    }

    /** Reorder only the rendered plan while a drag gesture is in progress. */
    fun previewMoveEntry(fromIndex: Int, toIndex: Int) {
        moveEntryInState(fromIndex, toIndex)
    }

    /** Persist the complete preview order after a drag, retaining row IDs. */
    fun commitPreviewedOrder() {
        val orderedIds = _state.value.entries.map { it.entryId }
        if (orderedIds.isEmpty()) return
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                personalBoardRepo.reorderPlaybackSteps(listId, orderedIds)
            }
            // A stale snapshot is rejected by the repository. Either way,
            // reconcile with the authoritative stored plan after the gesture.
            refresh()
        }
    }

    private fun moveEntryInState(fromIndex: Int, toIndex: Int): Boolean {
        val entries = _state.value.entries
        if (fromIndex !in entries.indices || toIndex !in entries.indices || fromIndex == toIndex) {
            return false
        }
        val reordered = entries.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        _state.update { it.copy(entries = reordered) }
        return true
    }

    fun removeEntry(entryId: Long) {
        _state.update { s -> s.copy(entries = s.entries.filter { it.entryId != entryId }) }
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) { personalBoardRepo.removePlaybackStep(entryId) }
        }
    }

    fun duplicateClimb(entryId: Long) {
        val current = _state.value.entries
        val index = current.indexOfFirst { it.entryId == entryId && !it.isRest }
        if (index < 0) return
        val replacement = current.map { it.toNewStep() }.toMutableList().apply {
            add(index + 1, current[index].toNewStep())
        }
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                personalBoardRepo.replacePlaybackSteps(listId, replacement)
            }
            refresh()
        }
    }

    /** Rebuild the explicit plan from unique normal list membership. */
    fun resetFromList() {
        viewModelScope.safeLaunch(TAG) {
            val snapshot = withContext(Dispatchers.IO) { userPreferences.getBoardFilterSnapshot() }
            val steps = withContext(Dispatchers.IO) {
                val current = personalBoardRepo.getPlaybackSteps(listId)
                val climbUuids = personalBoardRepo
                    .getClimbListEntryUuids(listId, Int.MAX_VALUE, 0)
                    .map { it.first }
                playbackStepsWithAutoRests(
                    climbUuids = climbUuids,
                    angle = snapshot.angle.toLong(),
                    restSeconds = inferAutoPlaybackRestSeconds(
                        previousRestSeconds = current.map { it.restSeconds },
                        configuredFallbackSeconds = _state.value.playbackRestSeconds,
                    ),
                )
            }
            withContext(Dispatchers.IO) { personalBoardRepo.replacePlaybackSteps(listId, steps) }
            refresh()
        }
    }

    fun clearPlan() {
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) { personalBoardRepo.replacePlaybackSteps(listId, emptyList()) }
            refresh()
        }
    }

    fun addRest(seconds: Long, afterEntryId: Long? = null) {
        val entries = _state.value.entries
        // A leading rest is ignored by playback because no climb can own it.
        if (entries.none { !it.isRest }) return
        val duration = seconds.coerceIn(10L, 3600L)
        val replacement = afterEntryId?.let { entryId ->
            playbackStepsWithRestInsertedAfter(entries, entryId, duration)
        }
        if (afterEntryId != null && replacement == null) return
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                if (replacement == null) {
                    personalBoardRepo.addPlaybackRest(listId, duration)
                } else {
                    personalBoardRepo.replacePlaybackSteps(listId, replacement)
                }
            }
            refresh()
        }
    }

    fun duplicateRest(entryId: Long) {
        val entries = _state.value.entries
        val rest = entries.firstOrNull { it.entryId == entryId && it.isRest } ?: return
        val replacement = playbackStepsWithRestInsertedAfter(
            entries,
            entryId,
            rest.restSeconds ?: 60L,
        ) ?: return
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                personalBoardRepo.replacePlaybackSteps(listId, replacement)
            }
            refresh()
        }
    }

    fun showEditRest(entryId: Long) = showEditRests(listOf(entryId))

    fun showEditRests(entryIds: List<Long>) {
        val restIds = entryIds.distinct().filter { entryId ->
            _state.value.entries.any { it.entryId == entryId && it.isRest }
        }
        if (restIds.isNotEmpty()) {
            _state.update { it.copy(editRestEntryIds = restIds) }
        }
    }

    fun dismissEditRest() = _state.update { it.copy(editRestEntryIds = emptyList()) }

    fun updateSelectedRestSeconds(seconds: Long) {
        val entryIds = _state.value.editRestEntryIds
        if (entryIds.isEmpty()) return
        val idSet = entryIds.toSet()
        val duration = seconds.coerceIn(10L, 3600L)
        _state.update { state ->
            state.copy(
                entries = state.entries.map { entry ->
                    if (entry.entryId in idSet && entry.isRest) {
                        entry.copy(restSeconds = duration)
                    } else {
                        entry
                    }
                },
                editRestEntryIds = emptyList(),
            )
        }
        viewModelScope.safeLaunch(TAG) {
            try {
                withContext(Dispatchers.IO) {
                    personalBoardRepo.updatePlaybackRestSeconds(entryIds, duration)
                }
            } finally {
                // Also restore the optimistic UI if the database write fails.
                refresh()
            }
        }
    }

    fun removeSelectedRests() {
        val entryIds = _state.value.editRestEntryIds
        if (entryIds.isEmpty()) return
        val idSet = entryIds.toSet()
        _state.update { state ->
            state.copy(
                entries = state.entries.filterNot { it.entryId in idSet && it.isRest },
                editRestEntryIds = emptyList(),
            )
        }
        viewModelScope.safeLaunch(TAG) {
            try {
                withContext(Dispatchers.IO) {
                    personalBoardRepo.removePlaybackSteps(entryIds)
                }
            } finally {
                // Also restore the optimistic UI if the database write fails.
                refresh()
            }
        }
    }

    fun showRenameDialog() {
        if (_state.value.isBuiltin) return
        _state.update { it.copy(showRenameDialog = true, renameValue = it.name) }
    }
    fun dismissRenameDialog() = _state.update { it.copy(showRenameDialog = false) }
    fun updateRenameValue(value: String) = _state.update { it.copy(renameValue = value) }

    fun confirmRename() {
        val name = _state.value.renameValue.trim()
        if (name.isBlank() || _state.value.isBuiltin) return
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) { personalBoardRepo.renameClimbList(listId, name) }
            _state.update { it.copy(showRenameDialog = false, name = name) }
        }
    }

    /** (uuid, angle) pairs of the resolvable climbs, playlist order — the
     *  payload both the play action and the detail-pager navigation use. */
    fun playableEntries(): List<Pair<String, Int>> =
        _state.value.entries.mapNotNull { e ->
            val uuid = e.climbUuid ?: return@mapNotNull null
            if (e.climb == null) return@mapNotNull null
            uuid to (e.angle?.toInt() ?: 40)
        }

    /**
     * Play: playlist → session queue → board.
     *
     * Mirrors the browser's session-start path (BoardSessionManager timer +
     * queue as HOST + optional GATT sharing chosen for this run), but
     * bulk-loads the playlist instead of starting empty. Rest rows collapse
     * onto their preceding climb as [QueueItem.restAfterSeconds] (summing
     * consecutive rests) — they never enter the shared BLE queue; advancing
     * past such a climb arms the local rest timer via [onRestRequested].
     *
     * Unresolvable climbs (catalogue not downloaded) are skipped — the
     * screen already surfaces the count.
     */
    fun play(
        hostName: String,
        visibility: SessionVisibility,
        onStarted: () -> Unit,
    ) {
        val boardCount = _state.value.entries
            .mapNotNull { it.climb }
            .map { it.boardBrand to it.layoutId }
            .distinct()
            .size
        if (boardCount > 1) {
            _state.update { it.copy(playbackBoardError = true) }
            return
        }
        val items = buildList<com.cruxcoach.android.ble.QueueItem> {
            var pendingRest = 0L
            _state.value.entries.forEach { e ->
                if (e.isRest) {
                    pendingRest += e.restSeconds ?: 0L
                    return@forEach
                }
                val uuid = e.climbUuid ?: return@forEach
                if (e.climb == null) return@forEach
                // A rest BEFORE a climb paces the gap after the previous
                // climb — attach accumulated rest to the last added item.
                if (pendingRest > 0 && isNotEmpty()) {
                    val last = removeAt(size - 1)
                    add(last.copy(restAfterSeconds = last.restAfterSeconds + pendingRest.toInt()))
                }
                pendingRest = 0L
                add(
                    com.cruxcoach.android.ble.QueueItem(
                        climbUuid = uuid,
                        angle = e.angle?.toInt() ?: 40,
                    )
                )
            }
            if (pendingRest > 0 && isNotEmpty()) {
                val last = removeAt(size - 1)
                add(last.copy(restAfterSeconds = last.restAfterSeconds + pendingRest.toInt()))
            }
        }
        if (items.isEmpty()) return
        playback.play(
            hostName,
            items,
            _state.value.playbackAdvance,
            visibility,
        )
        _state.update { it.copy(playbackBoardError = false) }
        onStarted()
    }

    private fun PlaylistUiEntry.toNewStep() = NewListPlaybackStep(
        climbUuid = climbUuid,
        angle = angle,
        restSeconds = restSeconds,
    )

    companion object {
        private const val TAG = "PlaylistDetailVM"

        /** Spelling-agnostic uuid key (mirrors KilterSyncEngine.normUuidKey):
         *  the climbs DB mixes forms — curated rows are nodash-UPPERCASE,
         *  community rows nodash-lowercase — while share-link imports and
         *  backup restores may carry dashed and/or lowercased spellings. */
        fun normUuidKey(uuid: String): String = uuid.replace("-", "").lowercase()

        /** Batch-resolve playlist entry uuids against the board DB, tolerant
         *  of spelling differences: query every plausible stored spelling and
         *  key the result by [normUuidKey]. */
        fun resolveClimbs(
            boardRepository: BoardRepository,
            uuids: Collection<String>,
        ): Map<String, ClimbWithStats> {
            if (uuids.isEmpty()) return emptyMap()
            val lookupUuids = uuids.asSequence()
                .flatMap {
                    val bare = it.replace("-", "")
                    sequenceOf(it, bare.lowercase(), bare.uppercase())
                }
                .distinct()
                .toList()
            return boardRepository.getClimbsByUuidsAnyAngle(lookupUuids)
                .associateBy { normUuidKey(it.uuid) }
        }
    }
}
