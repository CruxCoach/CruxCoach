package com.cruxcoach.android.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.util.safeLaunch
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val isGenerated: Boolean = false,
    val entries: List<PlaylistUiEntry> = emptyList(),
    val gradeScale: GradeScale = GradeScale.FRENCH,
    val unavailableCount: Int = 0,
    /** Reorder mode toggles per-row up/down handles. */
    val editMode: Boolean = false,
    val showRenameDialog: Boolean = false,
    val renameValue: String = "",
    /** Entry id whose rest duration is being edited. */
    val editRestEntryId: Long? = null,
)

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val personalBoardRepo: PersonalBoardRepository,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
    val climbNavState: com.cruxcoach.android.ui.navigation.ClimbNavigationState,
    private val playback: com.cruxcoach.android.data.PlaylistPlaybackCoordinator,
) : ViewModel() {

    private val listId: Long = savedStateHandle.get<String>("listId")?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(PlaylistDetailState(listId = listId))
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.safeLaunch(TAG) {
            val snapshot = withContext(Dispatchers.IO) { userPreferences.getBoardFilterSnapshot() }
            val (list, uiEntries) = withContext(Dispatchers.IO) {
                val list = personalBoardRepo.getClimbListById(listId)
                val rows = personalBoardRepo.getPlaylistEntries(listId)
                // Two-phase: resolve climb details from the board DB in one
                // batch; angle-agnostic since playlist rows pin their angle.
                val uuids = rows.mapNotNull { it.climbUuid }.distinct()
                val climbs = if (uuids.isEmpty()) emptyMap()
                else boardRepository.getClimbsByUuidsAnyAngle(uuids).associateBy { it.uuid }
                list to rows.map { row ->
                    PlaylistUiEntry(
                        entryId = row.id,
                        isRest = row.isRest,
                        restSeconds = row.restSeconds,
                        climbUuid = row.climbUuid,
                        angle = row.angle,
                        climb = row.climbUuid?.let { climbs[it] },
                    )
                }
            }
            _state.update {
                it.copy(
                    name = list?.name ?: "",
                    isGenerated = list?.generatorParams != null,
                    entries = uiEntries,
                    gradeScale = snapshot.gradeScale,
                    unavailableCount = uiEntries.count { e -> !e.isRest && e.climb == null },
                )
            }
        }
    }

    fun toggleEditMode() = _state.update { it.copy(editMode = !it.editMode) }

    fun moveEntry(fromIndex: Int, toIndex: Int) {
        val entries = _state.value.entries
        if (fromIndex !in entries.indices || toIndex !in entries.indices) return
        // Optimistic in-memory move so the row animates immediately.
        val reordered = entries.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        _state.update { it.copy(entries = reordered) }
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) { personalBoardRepo.movePlaylistEntry(listId, fromIndex, toIndex) }
        }
    }

    fun removeEntry(entryId: Long) {
        _state.update { s -> s.copy(entries = s.entries.filter { it.entryId != entryId }) }
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) { personalBoardRepo.removePlaylistEntry(entryId) }
        }
    }

    fun addRest(seconds: Long) {
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) { personalBoardRepo.addPlaylistRest(listId, seconds) }
            refresh()
        }
    }

    fun showEditRest(entryId: Long) = _state.update { it.copy(editRestEntryId = entryId) }
    fun dismissEditRest() = _state.update { it.copy(editRestEntryId = null) }

    fun updateRestSeconds(entryId: Long, seconds: Long) {
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                personalBoardRepo.updatePlaylistRestSeconds(entryId, seconds.coerceIn(10L, 3600L))
            }
            _state.update { it.copy(editRestEntryId = null) }
            refresh()
        }
    }

    fun showRenameDialog() = _state.update { it.copy(showRenameDialog = true, renameValue = it.name) }
    fun dismissRenameDialog() = _state.update { it.copy(showRenameDialog = false) }
    fun updateRenameValue(value: String) = _state.update { it.copy(renameValue = value) }

    fun confirmRename() {
        val name = _state.value.renameValue.trim()
        if (name.isBlank()) return
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
     * queue as HOST + GATT sharing when the privacy toggle allows), but
     * bulk-loads the playlist instead of starting empty. Rest rows collapse
     * onto their preceding climb as [QueueItem.restAfterSeconds] (summing
     * consecutive rests) — they never enter the shared BLE queue; advancing
     * past such a climb arms the local rest timer via [onRestRequested].
     *
     * Unresolvable climbs (catalogue not downloaded) are skipped — the
     * screen already surfaces the count.
     */
    fun play(hostName: String) {
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
        }
        if (items.isEmpty()) return
        playback.play(hostName, items)
    }

    private companion object {
        const val TAG = "PlaylistDetailVM"
    }
}
