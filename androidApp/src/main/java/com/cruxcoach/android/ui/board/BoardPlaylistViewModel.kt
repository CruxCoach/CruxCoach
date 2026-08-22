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
import com.cruxcoach.android.boardcell.BoardProjectionConfidence
import com.cruxcoach.android.boardcell.BoardProjectionConfidencePolicy
import com.cruxcoach.android.boardcell.BoardPlaylistUndo
import com.cruxcoach.android.data.BoardPlaylistLogMark
import com.cruxcoach.android.data.boardPlaylistLogKey
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.personalBoardPlaylistLogMarks
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    /** The occurrence the board is confirmed to be showing. */
    val isOnBoard: Boolean = false,
    /** Behind the current entry — done with, as far as the list is concerned. */
    val isPast: Boolean,
    val mark: BoardPlaylistLogMark,
    /** Which repeat of this climb this is, and how many there are in total. */
    val duplicateIndex: Int,
    val duplicateCount: Int,
    /**
     * What is claimed about *this* occurrence, or null when nothing is.
     *
     * Per-row rather than per-screen because after a failed send two
     * occurrences have different answers at once: the confirmed current is on
     * the wall, and the one behind it never got there.
     */
    val projection: BoardProjectionConfidence? = null,
) {
    /** Asked for, and it did not reach the wall. Retryable under this same id. */
    val hasFailed: Boolean get() = projection == BoardProjectionConfidence.FAILED
}

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
    /** Canonical occurrence confirmed current-on-board, for transport and dimming. */
    val currentIndex: Int = -1,
    /** The current occurrence matches the controller's known projection. */
    val selectionOnBoard: Boolean = false,
    /**
     * How well anybody here knows what the wall is showing.
     *
     * "Sent" and "the controller says so" are different claims and only one of
     * them is available on a write-only board, so the screen says which it is
     * rather than flattening both into "on the board".
     */
    val projectionConfidence: BoardProjectionConfidence = BoardProjectionConfidence.UNKNOWN,
    /** Nobody knows what is on the wall — not the same as "not yours". */
    val boardClimbUnknown: Boolean = false,
    /** What the board is showing instead, when that is known and different. */
    val confirmedClimbName: String? = null,
    /**
     * What the board is showing, whether or not it is the selected occurrence.
     *
     * The failure line needs it precisely when the two are the same: "X was not
     * sent" is only half the truth without "the board still shows Y".
     */
    val boardClimbName: String? = null,
    val pendingProjection: BoardPlaylistPendingProjection? = null,
    /** The name of the occurrence that was not sent, for the recovery line. */
    val failedClimbName: String? = null,
    val pendingCommands: Int = 0,
    val restore: BoardPlaylistRestoreOffer? = null,
    /** The last edit *this device* made, while it can still be taken back. */
    val undo: BoardPlaylistEdit? = null,
) {
    /** The occurrence a `Retry` acts on — its own id, never a matching climb. */
    val failedEntryId: String? get() = pendingProjection?.entryId

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
    private val randomClimbPicker: RandomBoardClimbPicker,
    private val climbNavState: com.cruxcoach.android.ui.navigation.ClimbNavigationState,
) : ViewModel() {

    private val _state = MutableStateFlow(BoardPlaylistUiState())
    val state: StateFlow<BoardPlaylistUiState> = _state.asStateFlow()

    val commandFeedback = gattBridge.commandFeedback

    /** Whether the arrow menu has already been pointed out on this install. */
    val addOptionsHintSeen: StateFlow<Boolean> = userPreferences.addOptionsHintSeen
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)

    fun markAddOptionsHintSeen() {
        viewModelScope.launch { userPreferences.markAddOptionsHintSeen() }
    }
    /** Why a dice roll came back empty — the two reasons need different answers. */
    private val _randomAddUnavailable = MutableSharedFlow<RandomClimbRoll>(extraBufferCapacity = 1)
    val randomAddUnavailable: SharedFlow<RandomClimbRoll> = _randomAddUnavailable

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
        // Confidence has two live inputs the snapshot does not carry: a write
        // this device has out, and what the controller says it holds.
        viewModelScope.launch {
            boardCellManager.projectionInFlight.collect { render(boardCellManager.snapshot()) }
        }
        viewModelScope.launch {
            bleConnection.quantumControllerState.collect { render(boardCellManager.snapshot()) }
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
        val currentIndex = playlist.currentIndex
        val confirmedCurrent = playlist.currentEntryId
        val status = projectionStatus(snapshot)
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
                // Two different facts, so two different marks. The board can be
                // showing an occurrence nobody is looking at, and the group can
                // be looking at one that has never been sent.
                isOnBoard = entry.entryId == confirmedCurrent && status.shows(entry),
                isPast = currentIndex >= 0 && index < currentIndex,
                mark = personalLogMarks[boardPlaylistLogKey(entry.climbUuid, entry.angle)]
                    ?: BoardPlaylistLogMark.UNATTEMPTED,
                duplicateIndex = occurrence,
                duplicateCount = totals[key] ?: 1,
                projection = status.confidenceFor(entry),
            )
        }
        val selectedEntry = playlist.currentEntry()
        val selectionOnBoard = status.shows(selectedEntry)
        val failedEntry = playlist.pendingProjection?.let { playlist.entry(it.entryId) }
        // What the wall shows *instead*, and only where that is worth
        // saying: an in-flight or failed send is already its own answer.
        val confirmed = status.projection?.takeIf {
            !selectionOnBoard && status.confidence != BoardProjectionConfidence.PENDING
        }
        confirmed?.let { resolveName(it.climbUuid, it.angle) }
        // Resolved even when it is the current occurrence: after a failed send
        // that is exactly the climb the group needs named.
        status.projection?.takeIf { status.confidence != BoardProjectionConfidence.PENDING }
            ?.let { resolveName(it.climbUuid, it.angle) }
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
            projectionConfidence = status.confidence,
            boardClimbUnknown = status.confidence == BoardProjectionConfidence.UNKNOWN,
            confirmedClimbName = confirmed?.let {
                climbInfos[climbInfoKey(it.climbUuid, it.angle)]?.name
            },
            boardClimbName = status.projection
                ?.takeIf { status.confidence != BoardProjectionConfidence.PENDING }
                ?.let { climbInfos[climbInfoKey(it.climbUuid, it.angle)]?.name },
            pendingProjection = playlist.pendingProjection,
            failedClimbName = failedEntry?.let {
                climbInfos[climbInfoKey(it.climbUuid, it.angle)]?.name
            },
            pendingCommands = _state.value.pendingCommands,
            restore = restore,
            undo = lastEdit?.takeIf { it.canUndo },
        )
    }

    /**
     * What the wall is showing, and what that claim is worth here.
     *
     * The readback half is deliberately narrow: a Quantum controller naming
     * the canonical climb is a confirmation, and every other board gets the
     * strongest honest answer its protocol allows, which is that the transport
     * completed.
     */
    private fun projectionStatus(snapshot: BoardCellSnapshot) =
        BoardProjectionConfidencePolicy.evaluate(
            snapshot = snapshot,
            inFlight = boardCellManager.projectionInFlight.value,
            readbackNamesProjection = bleConnection.quantumControllerState.value.let { controller ->
                BoardProjectionConfidencePolicy.readbackNames(
                    projection = snapshot.projection,
                    authoritative = controller.authoritative,
                    heldRouteIds = controller.players.map { it.routeId },
                )
            },
            brandConfirmsByReadback =
                bleConnection.connectedBoardBrand.value?.confirmsProjectionByControllerReadback == true,
        )

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

    /** Transport arrows step from the occurrence actually on the board. */
    fun next() {
        val playlist = boardCellManager.playlist() ?: return
        playlist.entries.getOrNull(playlist.currentIndex + 1)?.let { lightEntry(it.entryId) }
    }

    fun previous() {
        val playlist = boardCellManager.playlist() ?: return
        playlist.entries.getOrNull(playlist.currentIndex - 1)?.let { lightEntry(it.entryId) }
    }

    /**
     * Points the group at one occurrence. Says nothing about the wall.
     *
     * It emitted `SetCurrent` — the operation that means "the board is
     * confirmed to be showing this" — which on the controller moved the
     * confirmed current with no projection behind it, and from a member was a
     * command the controller-only policy refuses outright. The documentation
     * above it has said "selection, and nothing else" since the two were split.
     */
    fun select(entryId: String) =
        submit(BoardPlaylistEditKind.SELECT, "select") {
            if (it.entry(entryId) == null) emptyList()
            else listOf(BoardPlaylistOp.SetSelection(entryId))
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

    /** Add one filtered random occurrence; selection and wall stay untouched. */
    fun appendRandom() {
        viewModelScope.launch {
            when (val roll = withContext(Dispatchers.IO) { randomClimbPicker.roll() }) {
                is RandomClimbRoll.Picked -> append(roll.climbUuid, roll.angle)
                else -> _randomAddUnavailable.emit(roll)
            }
        }
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
            // "As next" means next from where the group is looking, which is
            // the cursor. Reading the confirmed current here put a warm-up at
            // the head of the list on any cell that had not sent anything yet.
            val anchor = state.selectedEntryId
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

    /** The centre lamp resends canonical current (or starts at the first row). */
    fun projectSelectedEntry() {
        val playlist = boardCellManager.playlist() ?: return
        (playlist.currentEntry() ?: playlist.entries.firstOrNull())?.let { lightEntry(it.entryId) }
    }

    /**
     * Put this climb on the wall now.
     *
     * [fromEntryId] is the occurrence the user was looking at, when they came
     * from the list. Without it the climb is not on the list yet and becomes a
     * new occurrence right after the current one — never an existing repeat of
     * the same climb, which belongs where its owner put it.
     */
    fun lightNow(climbUuid: String, angle: Int, fromEntryId: String? = null) =
        gattBridge.lightNow(climbUuid, angle, fromEntryId)

    /**
     * Remember which occurrence a climb page is being opened for.
     *
     * Without it the page would only know the climb, and "on the board now"
     * would mint a second occurrence for a climb that is already on the list —
     * the identity would be lost by mirroring an entry down to a climb record.
     * Paired with the climb uuid so a swipe to the next climb cannot inherit
     * this occurrence.
     */
    fun rememberOpenedEntry(entryId: String, climbUuid: String) {
        climbNavState.boardPlaylistEntryId = entryId
        climbNavState.boardPlaylistEntryClimbUuid = climbUuid
        climbNavState.climbUuids = _state.value.rows.map { it.climbUuid }.distinct()
        climbNavState.source = com.cruxcoach.android.ui.navigation.ClimbNavigationSource.QUEUE
    }

    /**
     * Put one existing occurrence on the wall.
     *
     * This is the only thing that moves the group's current from the list.
     * Opening an entry no longer does — that is local to whoever tapped it —
     * so the entry somebody is reading about is not the entry everybody is
     * climbing unless they said so.
     */
    fun lightEntry(entryId: String) {
        val entry = boardCellManager.playlist()?.entry(entryId) ?: return
        gattBridge.lightNow(entry.climbUuid, entry.angle, entryId)
    }

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
