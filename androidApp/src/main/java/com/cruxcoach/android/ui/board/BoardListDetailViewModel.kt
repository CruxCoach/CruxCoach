package com.cruxcoach.android.ui.board

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.Climb_list_entries
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.inferAutoPlaybackRestSeconds
import com.cruxcoach.data.repository.playbackStepsWithAutoRests
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.cruxcoach.android.util.safeLaunch

/** One board-filter chip for a list (FEAT-023).
 *  [layoutKey] pins a specific sub-board — Kilter Original (1) / Homewall (8),
 *  or a MoonBoard variant layout — OR is [BoardListDetailViewModel.ANY_LAYOUT]
 *  for a whole-brand roll-up ("all MoonBoard") and for Aurora brands (not split
 *  by layout). [count] = entries it covers. The human label is resolved in the
 *  UI layer (needs string resources + MoonBoardVariant). */
data class BoardFilterOption(val brandWire: String, val layoutKey: Long, val count: Int) {
    /** Identity match (ignores [count], which changes as the list edits). */
    fun matchesKey(other: BoardFilterOption) =
        brandWire == other.brandWire && layoutKey == other.layoutKey
}

data class BoardListDetailState(
    val isLoading: Boolean = true,
    val listId: Long = 0,
    val listName: String = "",
    val isBuiltin: Boolean = false,
    val isIgnored: Boolean = false,
    val hasPlaybackPlan: Boolean = false,
    /** Entries currently shown — the full set narrowed by [selectedFilters]. */
    val entries: List<Climb_list_entries> = emptyList(),
    /** Total entries the user SAVED (board-agnostic; unaffected by the filter).
     *  May exceed the shown set — see [unavailableCount]. */
    val totalCount: Long = 0,
    /** Saved entries that couldn't be resolved to a local climb (their board
     *  catalogue isn't downloaded). Surfaced as a hint so they're never
     *  silently hidden. */
    val unavailableCount: Int = 0,
    /** Distinct boards present, as filter chips (incl. brand roll-ups). Empty
     *  when the list spans a single board (no point offering a filter). */
    val boardFilters: List<BoardFilterOption> = emptyList(),
    /** Active board filters — MULTI-SELECT, union semantics. Empty = "Alle". */
    val selectedFilters: Set<BoardFilterOption> = emptySet(),
    val angle: Int = 40,
    val gradeScale: GradeScale = GradeScale.V_SCALE,
    val zones: IntensityZones? = null,
    val showPlaybackOptions: Boolean = false,
    val usePlaybackPlan: Boolean = false,
    val playbackOrder: ListPlaybackOrder = ListPlaybackOrder.LIST,
    val playbackAdvance: ListPlaybackAdvance = ListPlaybackAdvance.MANUAL,
    val playbackRestSeconds: Long = 0L,
    val isStartingPlayback: Boolean = false,
    val playbackStartError: PlaybackStartError? = null,
    val showRenameDialog: Boolean = false,
    val renameValue: String = "",
)

enum class PlaybackStartError { EMPTY, MULTIPLE_BOARDS }

@HiltViewModel
class BoardListDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val userPreferences: UserPreferences,
    private val zoneManager: IntensityZoneManager,
    private val playback: com.cruxcoach.android.data.PlaylistPlaybackCoordinator,
    val climbNavState: com.cruxcoach.android.ui.navigation.ClimbNavigationState
) : ViewModel() {

    private val listId: Long = savedStateHandle.get<String>("listId")?.toLongOrNull() ?: 0

    private val _state = MutableStateFlow(BoardListDetailState(listId = listId))
    val state: StateFlow<BoardListDetailState> = _state.asStateFlow()

    /** All resolved entries, board-agnostic and unfiltered. The displayed
     *  [BoardListDetailState.entries] is this narrowed by the active filters;
     *  kept here so toggling a filter never re-queries the DB. */
    private var allEntries: List<Climb_list_entries> = emptyList()

    init {
        viewModelScope.safeLaunch(TAG) {
            userPreferences.boardAngle.collect { angle ->
                _state.update { it.copy(angle = angle) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            userPreferences.gradeScale.collect { scale ->
                _state.update { it.copy(gradeScale = scale) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            zoneManager.zones.collect { zones ->
                _state.update { it.copy(zones = zones) }
            }
        }
        loadList()
    }

    /** Re-query — used on ON_RESUME so an edit/delete done on the detail screen
     *  reflects on return. */
    fun refresh() = loadList()

    private fun loadList() {
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                val list = personalBoardRepo.getClimbListById(listId)
                val angle = _state.value.angle
                val resolved = resolveAllEntries(angle)
                allEntries = resolved
                val savedCount = personalBoardRepo.countClimbListEntries(listId)
                val filters = buildBoardFilters(resolved)
                _state.update { s ->
                    // Re-map active filters onto the refreshed options (updates
                    // counts; drops ones whose board no longer exists).
                    val sel = s.selectedFilters
                        .mapNotNull { prev -> filters.firstOrNull { it.matchesKey(prev) } }
                        .toSet()
                    s.copy(
                        isLoading = false,
                        listName = list?.name ?: "",
                        isBuiltin = list?.isBuiltin == true,
                        isIgnored = list?.isIgnored == true,
                        hasPlaybackPlan = list?.hasPlaybackPlan == true,
                        playbackOrder = list?.playbackOrder ?: ListPlaybackOrder.LIST,
                        playbackAdvance = list?.playbackAdvance ?: ListPlaybackAdvance.MANUAL,
                        playbackRestSeconds = list?.playbackRestSeconds ?: 0L,
                        // True saved count; resolved may be smaller when an
                        // entry's board catalogue isn't downloaded — that gap is
                        // surfaced via unavailableCount, never silently dropped.
                        totalCount = savedCount,
                        unavailableCount = (savedCount - resolved.size).coerceAtLeast(0).toInt(),
                        boardFilters = filters,
                        selectedFilters = sel,
                        entries = applyFilter(resolved, sel),
                    )
                }
            }
        }
    }

    /** FEAT-023: a list is the user's explicit selection, so it's shown in FULL
     *  (board-agnostic) — never scoped to the active board. Resolves EVERY entry
     *  the device has board data for, at the active angle where available else a
     *  representative angle. Chunked to stay under SQLite's bound-parameter
     *  limit; lists are small, so this is a one-shot load (the full set is
     *  needed for the filter options anyway). */
    private fun resolveAllEntries(angle: Int): List<Climb_list_entries> {
        val uuidPairs = personalBoardRepo.getClimbListEntryUuids(listId, Int.MAX_VALUE, 0)
        if (uuidPairs.isEmpty()) return emptyList()
        val byUuid = HashMap<String, ClimbWithStats>()
        uuidPairs.map { it.first }.chunked(IN_CHUNK).forEach { chunk ->
            boardRepository.getClimbsByUuids(chunk, angle).forEach { byUuid[it.uuid] = it }
            val missing = chunk.filter { it !in byUuid }
            if (missing.isNotEmpty()) {
                boardRepository.getClimbsByUuidsAnyAngle(missing).forEach { byUuid[it.uuid] = it }
            }
        }
        return uuidPairs.mapNotNull { (uuid, addedAt) ->
            byUuid[uuid]?.let { climb -> Climb_list_entries(addedAt = addedAt, climb = climb) }
        }
    }

    /** Build the filter chips for the boards present. For each brand: one chip
     *  per distinct sub-board (Kilter Original/Homewall; each MoonBoard variant;
     *  Aurora collapses to a single per-brand chip), PLUS a brand roll-up chip
     *  ("all MoonBoard") when that brand has more than one sub-board present.
     *  Returns empty when the whole list is a single board (no filter needed). */
    private fun buildBoardFilters(entries: List<Climb_list_entries>): List<BoardFilterOption> {
        val leafCounts = LinkedHashMap<Pair<String, Long>, Int>()
        val brandCounts = LinkedHashMap<String, Int>()
        val brandSubs = LinkedHashMap<String, LinkedHashSet<Long>>()
        entries.forEach { e ->
            val brandWire = e.climb.boardBrand
            val sub = filterLayoutKey(BoardBrand.fromWire(brandWire), e.climb.layoutId)
            leafCounts[brandWire to sub] = (leafCounts[brandWire to sub] ?: 0) + 1
            brandCounts[brandWire] = (brandCounts[brandWire] ?: 0) + 1
            brandSubs.getOrPut(brandWire) { LinkedHashSet() }.add(sub)
        }
        if (leafCounts.size <= 1) return emptyList()
        val out = ArrayList<BoardFilterOption>()
        brandSubs.forEach { (brandWire, subs) ->
            if (subs.size > 1) {
                out.add(BoardFilterOption(brandWire, ANY_LAYOUT, brandCounts[brandWire] ?: 0))
            }
            subs.forEach { sub ->
                out.add(BoardFilterOption(brandWire, sub, leafCounts[brandWire to sub] ?: 0))
            }
        }
        return out
    }

    /** Union semantics: an entry shows if it matches ANY selected chip. A chip
     *  matches when its brand matches and either it's a brand roll-up
     *  ([ANY_LAYOUT]) or its layout key equals the entry's sub-board. */
    private fun applyFilter(
        entries: List<Climb_list_entries>, sel: Set<BoardFilterOption>
    ): List<Climb_list_entries> =
        if (sel.isEmpty()) entries
        else entries.filter { e ->
            val sub = filterLayoutKey(BoardBrand.fromWire(e.climb.boardBrand), e.climb.layoutId)
            sel.any { opt ->
                opt.brandWire == e.climb.boardBrand &&
                    (opt.layoutKey == ANY_LAYOUT || opt.layoutKey == sub)
            }
        }

    /** Filter granularity: layout matters only where it names a distinct,
     *  user-recognisable board — Kilter Original (1) vs Homewall (8), and each
     *  MoonBoard variant. Aurora brands (Tension TB1/TB2 etc.) have no simple
     *  per-layout name, so they collapse to one chip per brand. */
    private fun filterLayoutKey(brand: BoardBrand, layoutId: Long): Long = when (brand) {
        BoardBrand.KILTER, BoardBrand.MOONBOARD -> layoutId
        else -> ANY_LAYOUT
    }

    /** Toggle a board filter chip (multi-select union). */
    fun toggleBoardFilter(opt: BoardFilterOption) {
        _state.update { s ->
            val existing = s.selectedFilters.firstOrNull { it.matchesKey(opt) }
            val newSel = if (existing != null) s.selectedFilters - existing else s.selectedFilters + opt
            s.copy(selectedFilters = newSel, entries = applyFilter(allEntries, newSel))
        }
    }

    /** Clear all board filters ("Alle"). */
    fun clearBoardFilters() {
        _state.update { it.copy(selectedFilters = emptySet(), entries = applyFilter(allEntries, emptySet())) }
    }

    fun showPlaybackOptions() {
        val state = _state.value
        if (state.isIgnored || state.totalCount == 0L) return
        _state.update {
            it.copy(
                showPlaybackOptions = true,
                usePlaybackPlan = it.hasPlaybackPlan,
                playbackStartError = null,
            )
        }
    }

    fun dismissPlaybackOptions() {
        if (_state.value.isStartingPlayback) return
        _state.update { it.copy(showPlaybackOptions = false, playbackStartError = null) }
    }

    fun setUsePlaybackPlan(usePlan: Boolean) {
        _state.update {
            it.copy(usePlaybackPlan = usePlan && it.hasPlaybackPlan, playbackStartError = null)
        }
    }

    fun setPlaybackOrder(order: ListPlaybackOrder) {
        _state.update { it.copy(playbackOrder = order, playbackStartError = null) }
    }

    fun setPlaybackAdvance(advance: ListPlaybackAdvance) {
        _state.update { it.copy(playbackAdvance = advance, playbackStartError = null) }
    }

    fun setPlaybackRestSeconds(seconds: Long) {
        _state.update { it.copy(playbackRestSeconds = seconds.coerceIn(0L, 3600L)) }
    }

    fun showRenameDialog() {
        if (_state.value.isBuiltin) return
        _state.update { it.copy(showRenameDialog = true, renameValue = it.listName) }
    }

    fun dismissRenameDialog() = _state.update { it.copy(showRenameDialog = false) }

    fun updateRenameValue(value: String) = _state.update { it.copy(renameValue = value) }

    fun confirmRename() {
        val name = _state.value.renameValue.trim()
        if (name.isBlank() || _state.value.isBuiltin) return
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) { personalBoardRepo.renameClimbList(listId, name) }
            _state.update { it.copy(showRenameDialog = false, listName = name) }
        }
    }

    /** Creates a first explicit plan from the current list order. Existing
     *  plans are never overwritten by opening the editor. */
    fun preparePlaybackPlan(onReady: () -> Unit) {
        if (_state.value.hasPlaybackPlan) {
            onReady()
            return
        }
        viewModelScope.safeLaunch(TAG) {
            val snapshot = _state.value
            val seed = withContext(Dispatchers.IO) {
                val climbUuids = personalBoardRepo
                    .getClimbListEntryUuids(listId, Int.MAX_VALUE, 0)
                    .map { it.first }
                playbackStepsWithAutoRests(
                    climbUuids = climbUuids,
                    angle = snapshot.angle.toLong(),
                    restSeconds = inferAutoPlaybackRestSeconds(
                        previousRestSeconds = emptyList(),
                        configuredFallbackSeconds = snapshot.playbackRestSeconds,
                    ),
                )
            }
            withContext(Dispatchers.IO) {
                personalBoardRepo.replacePlaybackSteps(listId, seed)
            }
            _state.update { it.copy(hasPlaybackPlan = seed.isNotEmpty()) }
            onReady()
        }
    }

    /**
     * Compile the visible list directly into a private local playlist.
     * 0.2.2 has one start meaning: saved order, manual transport, no Nearby
     * publication. The explicit training-plan editor remains available as an
     * editor, but list playback never asks for a second "source" decision.
     */
    fun startPlayback(
        hostName: String,
        onStarted: () -> Unit,
    ) {
        val snapshot = _state.value
        if (snapshot.isStartingPlayback) return
        _state.update { it.copy(isStartingPlayback = true, playbackStartError = null) }
        viewModelScope.safeLaunch(TAG) {
            try {
                val prepared = prepareListPlayback(snapshot)
                val error = when {
                    prepared.items.isEmpty() -> PlaybackStartError.EMPTY
                    prepared.boardKeys.size > 1 -> PlaybackStartError.MULTIPLE_BOARDS
                    else -> null
                }
                if (error != null) {
                    _state.update { it.copy(playbackStartError = error) }
                    return@safeLaunch
                }
                withContext(Dispatchers.IO) {
                    personalBoardRepo.updatePlaybackSettings(
                        listId = listId,
                        order = ListPlaybackOrder.LIST,
                        advance = ListPlaybackAdvance.MANUAL,
                        restSeconds = snapshot.playbackRestSeconds,
                    )
                }
                playback.play(
                    hostName,
                    prepared.items,
                )
                _state.update { it.copy(showPlaybackOptions = false) }
                onStarted()
            } finally {
                _state.update { it.copy(isStartingPlayback = false) }
            }
        }
    }

    private data class PreparedPlayback(
        val items: List<com.cruxcoach.android.ble.QueueItem>,
        val boardKeys: Set<Pair<String, Long>>,
    )

    private fun prepareListPlayback(state: BoardListDetailState): PreparedPlayback {
        val entries = state.entries
        val items = entries.mapIndexed { index, entry ->
            com.cruxcoach.android.ble.QueueItem(
                climbUuid = entry.climb.uuid,
                angle = state.angle,
                restAfterSeconds = if (index < entries.lastIndex) {
                    state.playbackRestSeconds.toInt()
                } else 0,
            )
        }
        return PreparedPlayback(
            items = items,
            boardKeys = entries.map { it.climb.boardBrand to it.climb.layoutId }.toSet(),
        )
    }

    private fun preparePlanPlayback(defaultAngle: Int): PreparedPlayback {
        val steps = personalBoardRepo.getPlaybackSteps(listId)
        val uuids = steps.mapNotNull { it.climbUuid }.distinct()
        val lookupUuids = uuids.asSequence()
            .flatMap { uuid ->
                val bare = uuid.replace("-", "")
                sequenceOf(uuid, bare.lowercase(), bare.uppercase())
            }
            .distinct()
            .toList()
        val byUuid = boardRepository.getClimbsByUuidsAnyAngle(lookupUuids)
            .associateBy { normUuid(it.uuid) }
        val items = mutableListOf<com.cruxcoach.android.ble.QueueItem>()
        val boardKeys = linkedSetOf<Pair<String, Long>>()
        var pendingRest = 0
        steps.forEach { step ->
            if (step.isRest) {
                pendingRest += (step.restSeconds ?: 0L).toInt()
                return@forEach
            }
            val uuid = step.climbUuid ?: return@forEach
            val climb = byUuid[normUuid(uuid)] ?: return@forEach
            if (pendingRest > 0 && items.isNotEmpty()) {
                val last = items.removeAt(items.lastIndex)
                items.add(last.copy(restAfterSeconds = last.restAfterSeconds + pendingRest))
            }
            pendingRest = 0
            items.add(
                com.cruxcoach.android.ble.QueueItem(
                    climbUuid = uuid,
                    angle = step.angle?.toInt() ?: defaultAngle,
                )
            )
            boardKeys.add(climb.boardBrand to climb.layoutId)
        }
        if (pendingRest > 0 && items.isNotEmpty()) {
            val last = items.removeAt(items.lastIndex)
            items.add(last.copy(restAfterSeconds = last.restAfterSeconds + pendingRest))
        }
        return PreparedPlayback(items, boardKeys)
    }

    fun removeFromList(climbUuid: String) {
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                personalBoardRepo.removeClimbFromList(listId, climbUuid)
            }
            allEntries = allEntries.filterNot { it.climb.uuid == climbUuid }
            val filters = buildBoardFilters(allEntries)
            _state.update { s ->
                val sel = s.selectedFilters
                    .mapNotNull { prev -> filters.firstOrNull { it.matchesKey(prev) } }
                    .toSet()
                val newTotal = (s.totalCount - 1).coerceAtLeast(0)
                s.copy(
                    entries = applyFilter(allEntries, sel),
                    totalCount = newTotal,
                    unavailableCount = (newTotal - allEntries.size).coerceAtLeast(0).toInt(),
                    boardFilters = filters,
                    selectedFilters = sel,
                )
            }
        }
    }

    companion object {
        private const val TAG = "BoardListDetailVM"
        // SQLite's bound-parameter cap is 999 on older Android SQLite; chunk the
        // uuid IN-resolution well under it.
        private const val IN_CHUNK = 500
        // Sentinel layout key for brand roll-up chips + Aurora brands; never a
        // real layout_id. Negative so the UI can detect "whole brand".
        const val ANY_LAYOUT = -1L

        private fun normUuid(uuid: String): String = uuid.replace("-", "").lowercase()
    }
}
