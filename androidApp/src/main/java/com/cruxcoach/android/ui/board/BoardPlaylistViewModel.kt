package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.BoardCellSnapshot
import com.cruxcoach.android.boardcell.BoardPlaylistAnchor
import com.cruxcoach.android.boardcell.BoardPlaylistEdit
import com.cruxcoach.android.boardcell.BoardPlaylistEditKind
import com.cruxcoach.android.boardcell.BoardPlaylistEntry
import com.cruxcoach.android.boardcell.BoardPlaylistEntryId
import com.cruxcoach.android.boardcell.BoardPlaylistOp
import com.cruxcoach.android.boardcell.BoardPlaylistOps
import com.cruxcoach.android.boardcell.BoardPlaylistPendingProjection
import com.cruxcoach.android.boardcell.BoardPlaylistState
import com.cruxcoach.android.boardcell.BoardPlaylistUndo
import com.cruxcoach.android.data.BoardPlaylistLogMark
import com.cruxcoach.android.data.boardPlaylistLogKey
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.personalBoardPlaylistLogMarks
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One occurrence, as the Board-Playlist list draws it. */
data class BoardPlaylistRow(
    val entryId: String,
    val climbUuid: String,
    val angle: Int,
    val name: String,
    val gradeLabel: String?,
    val restAfterSeconds: Int,
    /** The occurrence the whole group is pointing at. */
    val isCurrent: Boolean,
    /** Behind the current entry — done with, as far as the list is concerned. */
    val isPast: Boolean,
    val mark: BoardPlaylistLogMark,
    /** Which repeat of this climb this is, and how many there are in total. */
    val duplicateIndex: Int,
    val duplicateCount: Int,
)

/** The canonical clear offer, counted down against this device's own clock. */
data class BoardPlaylistRestoreOffer(
    val entryCount: Int,
    val secondsRemaining: Int,
)

data class BoardPlaylistUiState(
    /** This device is in an active BoardCell, so there is a list at all. */
    val available: Boolean = false,
    val boardName: String? = null,
    val memberCount: Int = 0,
    /** False during a partition: what is on screen may already be stale. */
    val synchronized: Boolean = true,
    val rows: List<BoardPlaylistRow> = emptyList(),
    val currentIndex: Int = -1,
    /** The selected entry is the one the board last confirmed. */
    val selectionOnBoard: Boolean = false,
    /** Nobody knows what is on the wall — not the same as "not yours". */
    val boardClimbUnknown: Boolean = false,
    /** What the board is showing instead, when that is known and different. */
    val confirmedClimbName: String? = null,
    val pendingProjection: BoardPlaylistPendingProjection? = null,
    val pendingCommands: Int = 0,
    val restore: BoardPlaylistRestoreOffer? = null,
    /** The last edit *this device* made, while it can still be taken back. */
    val undo: BoardPlaylistEdit? = null,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
    val hasPrevious: Boolean get() = currentIndex > 0
    val hasNext: Boolean get() = currentIndex >= 0 && currentIndex < rows.size - 1
}

/**
 * The board's one shared list, as a screen of its own.
 *
 * Reads canonical BoardCell state directly rather than the mirrored session
 * state, for one reason that matters: a cleared list is still a list — it has
 * a restore offer on it that every member must be able to see and act on —
 * whereas the mirrored session deliberately ends the moment the playlist is
 * empty. Everything written here travels as an ordinary occurrence-addressed
 * command through [SessionGattBridge], so this screen has no privileged path
 * and holds no second copy of the truth.
 */
@HiltViewModel
class BoardPlaylistViewModel @Inject constructor(
    private val boardCellManager: BoardCellManager,
    private val gattBridge: SessionGattBridge,
    private val queueManager: SessionQueueManager,
    private val boardRepository: BoardRepository,
    private val personalBoardRepository: PersonalBoardRepository,
    private val userPreferences: UserPreferences,
    private val bleConnection: BoardBleConnection,
    private val fipsMeshRuntime: FipsMeshRuntime,
    val climbNavState: ClimbNavigationState,
) : ViewModel() {

    private val _state = MutableStateFlow(BoardPlaylistUiState())
    val state: StateFlow<BoardPlaylistUiState> = _state.asStateFlow()

    val commandFeedback = gattBridge.commandFeedback

    private val climbInfos = HashMap<String, QueueRowInfo>()
    private var personalLogMarks = emptyMap<String, BoardPlaylistLogMark>()
    private var retainedBoardName: String? = null
    private var retainedBoardCellId: String? = null
    private var gradeScale: GradeScale? = null
    private var renderToken = 0L
    private var personalLogRefreshToken = 0L

    /**
     * The last edit this device made, kept only until the next one.
     *
     * Local on purpose: an undo that named somebody else's change would be a
     * way to revert an edit you never saw, on a list several people are
     * writing to at once. The clear is the one exception and has a canonical
     * offer of its own.
     */
    private var lastEdit: BoardPlaylistEdit? = null
    private var undoExpiry: kotlinx.coroutines.Job? = null
    private var latestEditToken = 0L

    init {
        viewModelScope.launch {
            boardCellManager.snapshots.collect { snapshot -> render(snapshot) }
        }
        // The restore offer is a canonical deadline counted down locally, so it
        // needs a tick of its own — but only while there is one. Nothing else
        // on this screen changes without a snapshot, and a permanent 1 Hz
        // wake-up on a phone sitting in somebody's pocket at the wall is a
        // cost with nothing on the other side of it.
        viewModelScope.launch {
            _state.map { it.restore != null }.distinctUntilChanged().collectLatest { counting ->
                while (counting) {
                    delay(1_000)
                    render(boardCellManager.snapshot())
                }
            }
        }
        viewModelScope.launch {
            gattBridge.pendingPlaylistCommandCount.collect { pending ->
                _state.update { it.copy(pendingCommands = pending) }
            }
        }
        viewModelScope.launch {
            bleConnection.connectedBoardDescriptor.collect { board ->
                board?.displayName?.takeIf { it.isNotBlank() }?.let { retainedBoardName = it }
                render(boardCellManager.snapshot())
            }
        }
        viewModelScope.launch {
            fipsMeshRuntime.nearbyMeshes.collect { nearby ->
                val snapshot = boardCellManager.snapshot()
                nearby.firstOrNull {
                    it.joinableBoardCellId == snapshot?.cellId?.value || it.matchesActiveRealm
                }?.boardName?.takeIf { it.isNotBlank() }?.let { retainedBoardName = it }
                render(snapshot)
            }
        }
        refreshPersonalLogs()
    }

    // ── Reading ────────────────────────────────────────────────────────────

    private suspend fun render(snapshot: BoardCellSnapshot?) {
        val token = ++renderToken
        val member = snapshot != null && boardCellManager.localNodeId() in snapshot.members
        if (snapshot == null || !member) {
            // An offer to undo an edit on a list this device is no longer in
            // is an offer it could not honour.
            lastEdit = null
            retainedBoardCellId = null
            retainedBoardName = null
            _state.value = BoardPlaylistUiState(
                available = false,
                pendingCommands = _state.value.pendingCommands,
            )
            return
        }
        if (retainedBoardCellId != snapshot.cellId.value) {
            retainedBoardCellId = snapshot.cellId.value
            retainedBoardName = null
        }
        val playlist = snapshot.playlist
        resolveMissingNames(playlist)
        if (token != renderToken) return
        val current = playlist.currentEntryId
        val currentIndex = playlist.currentIndex
        val occurrences = HashMap<String, Int>()
        val totals = playlist.entries.groupingBy { duplicateKey(it) }.eachCount()
        val rows = playlist.entries.mapIndexed { index, entry ->
            val key = duplicateKey(entry)
            val occurrence = (occurrences[key] ?: 0) + 1
            occurrences[key] = occurrence
            val info = climbInfos[climbInfoKey(entry.climbUuid, entry.angle)]
            BoardPlaylistRow(
                entryId = entry.entryId,
                climbUuid = entry.climbUuid,
                angle = entry.angle,
                name = info?.name ?: entry.climbUuid.take(8),
                gradeLabel = info?.gradeLabel,
                restAfterSeconds = entry.restAfterSeconds,
                isCurrent = entry.entryId == current,
                isPast = currentIndex >= 0 && index < currentIndex,
                mark = personalLogMarks[boardPlaylistLogKey(entry.climbUuid, entry.angle)]
                    ?: BoardPlaylistLogMark.UNATTEMPTED,
                duplicateIndex = occurrence,
                duplicateCount = totals[key] ?: 1,
            )
        }
        val currentEntry = playlist.currentEntry()
        val selectionOnBoard = snapshot.projectionKnown && currentEntry != null &&
            snapshot.projection?.let {
                it.climbUuid == currentEntry.climbUuid && it.angle == currentEntry.angle
            } == true
        val confirmed = snapshot.projection?.takeIf { snapshot.projectionKnown && !selectionOnBoard }
        confirmed?.let { resolveName(it.climbUuid, it.angle) }
        if (token != renderToken) return
        val now = System.currentTimeMillis()
        val restore = playlist.lastClear
            ?.takeIf { !it.hasExpired(now) }
            ?.let {
                BoardPlaylistRestoreOffer(it.entries.size, it.remainingSeconds(now))
            }
        _state.value = BoardPlaylistUiState(
            available = true,
            boardName = currentBoardName(snapshot),
            memberCount = snapshot.members.size,
            synchronized = boardCellManager.isPlaylistSynchronized(),
            rows = rows,
            currentIndex = currentIndex,
            selectionOnBoard = selectionOnBoard,
            boardClimbUnknown = !snapshot.projectionKnown,
            confirmedClimbName = confirmed?.let {
                climbInfos[climbInfoKey(it.climbUuid, it.angle)]?.name
            },
            pendingProjection = playlist.pendingProjection,
            pendingCommands = _state.value.pendingCommands,
            restore = restore,
            undo = lastEdit?.takeIf { it.canUndo },
        )
    }

    /**
     * Occurrences of the same climb at the same angle are what a duplicate
     * count is about — the same problem queued again. A different angle is a
     * different problem to climb, so it counts separately.
     */
    private fun duplicateKey(entry: BoardPlaylistEntry): String =
        "${entry.climbUuid.lowercase()}@${entry.angle}"

    private fun climbInfoKey(climbUuid: String, angle: Int): String =
        "${climbUuid.lowercase()}@$angle"

    private suspend fun resolveMissingNames(playlist: BoardPlaylistState) {
        val missing = playlist.entries
            .filterNot { climbInfos.containsKey(climbInfoKey(it.climbUuid, it.angle)) }
            .distinctBy { climbInfoKey(it.climbUuid, it.angle) }
        missing.forEach { resolveName(it.climbUuid, it.angle) }
    }

    private suspend fun resolveName(climbUuid: String, angle: Int) {
        val key = climbInfoKey(climbUuid, angle)
        if (climbInfos.containsKey(key)) return
        val scale = gradeScale ?: userPreferences.gradeScale.first().also { gradeScale = it }
        val resolved = withContext(Dispatchers.IO) {
            val climb = boardRepository.getClimbByUuid(climbUuid, angle)
                ?: boardRepository.getClimbByUuid(climbUuid.lowercase(), angle)
                ?: boardRepository.getClimbByUuid(climbUuid.uppercase(), angle)
            QueueRowInfo(
                name = climb?.name ?: climbUuid.take(8),
                gradeLabel = climb?.difficultyAverage?.let {
                    GradeDisplayHelper.formatDifficulty(it, scale)
                },
            )
        }
        climbInfos[key] = resolved
    }

    /** Refresh after returning from the climb logger; these colours are local-only log facts. */
    fun refreshPersonalLogs() {
        val token = ++personalLogRefreshToken
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                personalBoardPlaylistLogMarks(personalBoardRepository.getUserLogbookAllLight())
            }
            if (token != personalLogRefreshToken) return@launch
            personalLogMarks = loaded
            render(boardCellManager.snapshot())
        }
    }

    private fun currentBoardName(snapshot: BoardCellSnapshot): String {
        bleConnection.connectedBoardDescriptor.value?.displayName
            ?.takeIf { it.isNotBlank() }
            ?.let { retainedBoardName = it }
        fipsMeshRuntime.nearbyMeshes.value.firstOrNull {
            it.joinableBoardCellId == snapshot.cellId.value || it.matchesActiveRealm
        }?.boardName?.takeIf { it.isNotBlank() }?.let { retainedBoardName = it }
        return retainedBoardName ?: when (snapshot.physicalBoardId.value.substringBefore(':').lowercase()) {
            "kilter" -> "Kilter Board"
            "tension" -> "Tension Board"
            "moon" -> "MoonBoard"
            "decoy" -> "Decoy Board"
            else -> "Board"
        }
    }

    // ── Editing — every member, every operation ────────────────────────────

    /**
     * Steps the group's selection, and nothing else.
     *
     * Deliberately does not touch the wall: somebody looking ahead through the
     * list must not take the board from whoever is climbing on it. The lamp is
     * the one control that changes what is projected.
     */
    fun next() = submit(BoardPlaylistEditKind.SELECT, "next") { BoardPlaylistOps.next(it) }

    fun previous() =
        submit(BoardPlaylistEditKind.SELECT, "previous") { BoardPlaylistOps.previous(it) }

    fun select(entryId: String) =
        submit(BoardPlaylistEditKind.SELECT, "select") {
            if (it.entry(entryId) == null) emptyList()
            else listOf(BoardPlaylistOp.SetCurrent(entryId))
        }

    fun remove(entryId: String) =
        submit(BoardPlaylistEditKind.REMOVE, "remove") {
            if (it.entry(entryId) == null) emptyList()
            else listOf(BoardPlaylistOp.Remove(entryId))
        }

    fun move(entryId: String, anchor: BoardPlaylistAnchor) =
        submit(BoardPlaylistEditKind.MOVE, "move") { state ->
            if (state.entry(entryId) == null ||
                anchor is BoardPlaylistAnchor.After && state.entry(anchor.entryId) == null
            ) emptyList() else listOf(BoardPlaylistOp.Move(entryId, anchor))
        }

    /**
     * Append one climb to the end of the board's list.
     *
     * Repeatable on purpose: pressing it twice queues the problem twice,
     * because that is what a 4x4 or a limit block is. Every press mints a
     * fresh occurrence id, so two people appending the same climb at the same
     * moment get two entries rather than one of them silently losing.
     */
    fun append(climbUuid: String, angle: Int) =
        submit(BoardPlaylistEditKind.ADD, "add") {
            BoardPlaylistOps.add(climbUuid, angle)
        }

    /**
     * Put one climb straight after whatever the group is on.
     *
     * The other half of "add": at the wall the useful distinction is almost
     * always "later" versus "right after this one", and burying the second in
     * a reorder after the fact is how a warm-up ends up at the bottom of a
     * forty-climb list.
     */
    fun appendAsNext(climbUuid: String, angle: Int) =
        submit(BoardPlaylistEditKind.ADD, "add_next") { state ->
            val anchor = state.currentEntryId
                ?.let { BoardPlaylistAnchor.After(it) }
                ?: BoardPlaylistAnchor.Head
            BoardPlaylistOps.add(climbUuid, angle, anchor = anchor)
        }

    /** Append one more go at the same problem, right after this occurrence. */
    fun repeatAfter(entryId: String) =
        submit(BoardPlaylistEditKind.ADD, "repeat") { state ->
            state.entry(entryId)?.let { entry ->
                BoardPlaylistOps.add(
                    climbUuid = entry.climbUuid,
                    angle = entry.angle,
                    restAfterSeconds = entry.restAfterSeconds,
                    entryId = BoardPlaylistEntryId.random(),
                    anchor = BoardPlaylistAnchor.After(entryId),
                )
            }.orEmpty()
        }

    /**
     * Empty the list for everybody.
     *
     * Open to every member, and confirmed exactly once in the UI. What makes
     * that safe is the canonical restore offer the clear leaves behind, not a
     * second dialog.
     */
    fun clear() {
        latestEditToken++
        lastEdit = null
        gattBridge.clearSharedPlaylist()
        queueManager.resumeFollowingSharedPlaylist()
    }

    /** Take the clear back — for everybody, from any member's device. */
    fun restore() {
        latestEditToken++
        lastEdit = null
        gattBridge.restoreClearedPlaylist()
        queueManager.resumeFollowingSharedPlaylist()
    }

    /** Take back the last edit *this* device made. */
    fun undo() {
        val edit = lastEdit ?: return
        latestEditToken++
        lastEdit = null
        undoExpiry?.cancel()
        gattBridge.editSharedPlaylist("undo_${edit.kind.name.lowercase()}", edit.inverse)
        viewModelScope.launch { render(boardCellManager.snapshot()) }
    }

    fun dismissUndo() {
        lastEdit = null
        undoExpiry?.cancel()
        viewModelScope.launch { render(boardCellManager.snapshot()) }
    }

    /** Re-adopt the canonical queue before navigating to its focused player. */
    fun resumePlayer() = queueManager.resumeFollowingSharedPlaylist()

    /**
     * The lamp: put the selected entry on the wall.
     *
     * The only control anywhere in the Board-Playlist that touches the
     * physical board, and open to every member — whoever is standing at the
     * wall is the one who notices it is dark. Pressing it when the selection
     * is already confirmed is a deliberate resend.
     */
    fun projectSelectedEntry() = gattBridge.projectSelectedEntry()

    private fun submit(
        kind: BoardPlaylistEditKind,
        label: String,
        compose: (BoardPlaylistState) -> List<BoardPlaylistOp>,
    ) {
        val before = boardCellManager.playlist() ?: return
        val ops = compose(before)
        if (ops.isEmpty()) return
        // Composed against the same state the operations were, so a remove
        // comes back where it was rather than at the end of a list that has
        // since moved on.
        val inverse = BoardPlaylistUndo.inverseOf(before, ops)
        val edit = BoardPlaylistEdit(kind, ops, inverse)
        val token = latestEditToken + 1
        // Publish the token before the bridge launch: a local controller can
        // return COMMITTED without suspending, including under an unconfined
        // test dispatcher, so its callback may run before this call returns.
        val previousToken = latestEditToken
        latestEditToken = token
        if (!gattBridge.editSharedPlaylist(label, ops) { ack ->
                if (ack?.changedPlaylist == true && token == latestEditToken) {
                    viewModelScope.launch { offerUndo(edit) }
                }
            }) {
            if (latestEditToken == token) latestEditToken = previousToken
            return
        }
        // A second edit is now in flight. The previous edit is no longer the
        // latest action and must not remain undoable while this one commits.
        // Preserve [edit] only when a synchronous local-controller callback
        // has already installed its confirmed offer.
        if (lastEdit !== edit) {
            lastEdit = null
            undoExpiry?.cancel()
        }
        queueManager.resumeFollowingSharedPlaylist()
        viewModelScope.launch { render(boardCellManager.snapshot()) }
    }

    private suspend fun offerUndo(edit: BoardPlaylistEdit) {
        lastEdit = edit
        // An offer that never lapses is one somebody accepts much later,
        // against a list that has moved on and in front of people who have
        // forgotten the edit it names. Start this only after COMMITTED: an
        // inverse of a rejected command would itself be a new, wrong edit.
        undoExpiry?.cancel()
        undoExpiry = viewModelScope.launch {
            delay(UNDO_OFFER_MS)
            if (lastEdit != null) {
                lastEdit = null
                render(boardCellManager.snapshot())
            }
        }
        render(boardCellManager.snapshot())
    }

    private companion object {
        /** Long enough to notice the edit, short enough to still mean it. */
        const val UNDO_OFFER_MS = 12_000L
    }
}
