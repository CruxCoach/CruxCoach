package com.cruxcoach.android.ui.paintbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.ui.settings.boardSelectionLabel
import com.cruxcoach.data.repository.*
import com.cruxcoach.domain.board.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

data class PaintBoxState(
    val imageImport: PaintImageSession? = null,
    val imageLoading: Boolean = false,
    val document: PaintDocument? = null,
    val boardSize: BoardSize? = null,
    val placements: Map<Int, BoardPlacement> = emptyMap(),
    val images: List<BoardImage> = emptyList(),
    val ledMap: Map<Int, Int> = emptyMap(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val missingBoard: Boolean = false,
    /** A board family PaintBox cannot light yet, e.g. Shiny Wall. */
    val unsupportedBoard: Boolean = false,
    val dirty: Boolean = false,
    val brush: Int = 0x1c,
    val eraser: Boolean = false,
    val live: Boolean = false,
    val sending: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val library: PaintLibrary = PaintLibrary(emptyList(), 0),
    val message: Int? = null,
    /** The app's board changed (e.g. in the connection sheet) while this picture has unsaved changes. */
    val boardChange: PaintBoard? = null,
    /** Send or Live was refused: the picture is for another board than the app's (kept after [boardChange]). */
    val otherBoard: Boolean = false,
) {
    val boardReady: Boolean get() = !missingBoard && !unsupportedBoard
}

@HiltViewModel
class PaintBoxViewModel @Inject constructor(
    private val repository: PaintRepository,
    private val boards: BoardRepository,
    private val preferences: UserPreferences,
    private val ble: BoardBleConnection,
    private val projector: PaintProjector,
) : ViewModel() {
    private val mutable = MutableStateFlow(PaintBoxState())
    val state = mutable.asStateFlow()
    private val history = PaintHistory()
    private var saved: PaintDocument? = null
    private var generation = 0L
    private var pending = false
    private var sender: Job? = null
    private var loadJob: Job? = null
    private var imageJob: Job? = null
    private var previewAddress: String? = null
    val connection = ble.connectionState

    /** The board of the open picture, or of the one being opened. */
    private var target: PaintBoard? = null
    /** The app's board as last saved. */
    private var appBoard: PaintBoard? = null
    /** The user already agreed to leave the picture as it is (the board picker asks first). */
    private var leaveAgreed = false

    init {
        viewModelScope.launch {
            // The board picker and the connection sheet save the board in the background,
            // so follow the saved board instead of reading it once.
            savedBoard().collect { board ->
                appBoard = board
                val open = target
                when {
                    open == null -> openBoard(board)
                    open.sameBoardAs(board) -> mutable.update { it.copy(boardChange = null) }
                    // While a board or picture opens, nothing unsaved is on screen: the newer choice wins.
                    leaveAgreed || !state.value.dirty || loadJob?.isActive == true -> openBoard(board)
                    else -> { pause(); mutable.update { it.copy(boardChange = board) } }
                }
            }
        }
        viewModelScope.launch {
            connection.collect { if (it != ConnectionState.CONNECTED && it != ConnectionState.SENDING) pause() }
        }
        viewModelScope.launch {
            // A session or board sharing takes the board: Live stops instead of fighting it.
            projector.busy.collect { reason -> if (reason != null && state.value.live) { pause(); message(reason) } }
        }
    }
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun savedBoard(): Flow<PaintBoard> =
        combine(preferences.boardBrand, preferences.boardLayoutId, preferences.boardProductSizeId, ::Triple)
            // One saved selection reaches the three flows one after the other.
            .debounce(SELECTION_SETTLE_MS)
            .flatMapLatest { (brand, layout, size) ->
                val variant = MoonBoardVariant.fromLayoutId(layout.toLong())
                if (brand == BoardBrand.MOONBOARD.wireValue && variant != null) {
                    preferences.moonBoardHoldSets(variant).map { PaintBoard(brand, layout, size, it) }
                } else flowOf(PaintBoard(brand, layout, size))
            }
            .distinctUntilChanged()
    /** The picker confirmed a board. It opens once the picker has saved it; picking the open board keeps the picture. */
    fun boardPicked() {
        pause()
        leaveAgreed = true
        // After keepBoard the app's board differs from the picture's. Picking the app's
        // board saves nothing new, so open it now; any other pick follows as usual.
        appBoard?.takeIf { app -> target?.sameBoardAs(app) == false }?.let(::openBoard)
    }
    /** Opens the app's new board after the user saved or discarded the picture ([PaintBoxState.boardChange]). */
    fun openBoardChange() {
        val board = state.value.boardChange ?: return
        mutable.update { it.copy(boardChange = null) }
        openBoard(board)
    }
    /** Keeps painting the open picture on its own board. Send and Live wait until it is the app's board again. */
    fun keepBoard() { mutable.update { it.copy(boardChange = null) } }
    /** Opens the app's board instead of a picture made for another one ([PaintBoxState.otherBoard]). */
    fun openAppBoard() {
        mutable.update { it.copy(otherBoard = false) }
        appBoard?.let(::openBoard)
    }
    fun dismissOtherBoard() { mutable.update { it.copy(otherBoard = false) } }
    /** The app's board is the one chosen for the connected box: another board's LED map and power budget would scramble it. */
    private fun onAppBoard(document: PaintDocument): Boolean = appBoard?.let { document.board.sameBoardAs(it) } == true
    /** The connection sheet can save another board: an earlier agreement to leave the picture ends here. */
    fun connectionSheetOpened() { leaveAgreed = false }
    /** Whether opening [document] changes the app's board, which the user confirms first. */
    fun switchesBoard(document: PaintDocument): Boolean = appBoard?.let { !document.board.sameBoardAs(it) } == true
    private fun openBoard(board: PaintBoard) {
        pause()
        leaveAgreed = false
        target = board
        mutable.update { it.copy(boardChange = null) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try { open(PaintDocument(board = board), false) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(loading = false, message = R.string.paintbox_load_error) } }
        }
    }
    private suspend fun open(document: PaintDocument, persisted: Boolean) {
        val supported = BoardBrand.fromWire(document.board.brand).supportsPaintBox
        if (supported) document.validate()
        mutable.update { it.copy(loading = true) }
        val board = document.board
        val geometry = withContext(Dispatchers.IO) {
            if (board.brand == "moonboard" || !supported) Triple(null, emptyList(), emptyList())
            else Triple(boards.getProductSize(board.sizeId, board.brand),
                boards.getPlacementsForLayout(board.sizeId, board.layoutId, board.brand),
                boards.getBoardImages(board.sizeId, board.layoutId, board.brand))
        }
        val leds = withContext(Dispatchers.IO) {
            if (board.brand == "moonboard" || !supported) emptyMap() else boards.getPlacementLedMap(board.sizeId, board.brand)
        }
        // Only holds with an LED on this size can be painted, so a send never misses one.
        val placements = geometry.second.filter { it.placementId.toInt() in leds }
        val brand = BoardBrand.fromWire(board.brand)
        val detail = when (brand) {
            BoardBrand.MOONBOARD -> MoonBoardVariant.fromLayoutId(board.layoutId.toLong())?.displayName
            BoardBrand.QUANTUM -> QuantumBoardModel.fromLayoutId(board.layoutId.toLong())?.displayName
            BoardBrand.KILTER -> geometry.first?.name
            else -> listOfNotNull(BoardConstants.auroraVariant(brand, board.layoutId)?.displayName,
                geometry.first?.name).distinct().joinToString(" · ")
        }
        val label = if (detail == null && board.label.isNotBlank()) board.label
            else boardSelectionLabel(brand, board.layoutId, detail)
        val paintable = placements.mapTo(mutableSetOf()) { it.placementId.toInt() }
        // Without board data (catalogue missing) the picture stays whole for the retry.
        val colors = if (board.brand == "moonboard" || !supported || paintable.isEmpty()) document.colors
            else document.colors.filterKeys { it in paintable }
        val labelled = document.copy(board = board.copy(label = label), colors = colors)
        history.clear()
        saved = labelled.takeIf { persisted }
        // Keep the chosen tool and color when the new board can show that color.
        val previous = mutable.value
        val brush = previous.brush.takeIf { it in PaintPalette.forBoard(brand) } ?: PaintBoxState().brush
        mutable.value = PaintBoxState(document = labelled, boardSize = geometry.first, brush = brush, eraser = previous.eraser,
            placements = placements.associateBy { it.placementId.toInt() }, images = geometry.third,
            ledMap = leds, loading = false,
            missingBoard = supported && board.brand != "moonboard" && (geometry.first == null || placements.isEmpty()),
            unsupportedBoard = !supported,
            // A board chosen elsewhere while this one opened still waits for its answer.
            boardChange = previous.boardChange?.takeUnless { it.sameBoardAs(board) },
            library = mutable.value.library)
    }
    /** Opens a saved picture and makes its board the app's board (the screen asks first, see [switchesBoard]). */
    fun load(document: PaintDocument) {
        pause()
        leaveAgreed = false
        target = document.board
        mutable.update { it.copy(boardChange = null) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                document.validate()
                // Only a switch writes the selection: selecting a MoonBoard again resets its angle.
                if (switchesBoard(document)) {
                    selectBoard(document.board)
                    // The saved board arrives a moment later; Send must not refuse the picture meanwhile.
                    appBoard = document.board
                }
                open(document, true)
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(loading = false, message = R.string.paintbox_load_error) } }
        }
    }
    private suspend fun selectBoard(board: PaintBoard) {
        if (board.brand != "moonboard") return preferences.setBoardSelection(board.brand, board.layoutId, board.sizeId)
        val variant = requireNotNull(MoonBoardVariant.fromLayoutId(board.layoutId.toLong()))
        val sets = board.mountedMoonHoldSets()
        // The same MoonBoard with other hold sets keeps its angle.
        val app = appBoard
        if (app?.brand == board.brand && app.layoutId == board.layoutId) preferences.setMoonBoardHoldSets(variant, sets)
        else preferences.setMoonBoardSelection(board.layoutId, sets)
    }
    fun brush(color: Int) { mutable.update { it.copy(brush = color, eraser = false) } }
    fun importImage(context: android.content.Context, uri: android.net.Uri) {
        imageJob?.cancel()
        mutable.update { it.copy(imageLoading = true) }
        imageJob = viewModelScope.launch {
            try {
                val bitmap = loadPaintImage(context.applicationContext, uri)
                startImage(bitmap)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(imageLoading = false, message = R.string.paintbox_image_error) } }
        }
    }
    internal fun startImage(bitmap: android.graphics.Bitmap) {
        endStroke()
        generation++
        pending = false
        mutable.update { it.copy(imageImport = PaintImageSession(bitmap), imageLoading = false) }
    }
    fun imageTransform(transform: PaintImageTransform) {
        mutable.update { s -> s.copy(imageImport = s.imageImport?.copy(transform = transform, ready = false)) }
    }
    fun imageBackgroundTool() {
        mutable.update { s -> s.copy(imageImport = s.imageImport?.let {
            if (it.background != null || it.pickingBackground) it.copy(background = null, pickingBackground = false, ready = false)
            else it.copy(pickingBackground = true)
        }) }
    }
    fun imageBackground(color: Int) {
        if ((color ushr 24) < 128) return
        mutable.update { s -> s.copy(imageImport = s.imageImport?.copy(background = color and 0xffffff,
            pickingBackground = false, ready = false)) }
    }
    fun imagePreview(source: PaintImageSession, colors: Map<Int, Int>) {
        if (state.value.imageImport !== source) return
        mutable.update { it.copy(imageImport = source.copy(colors = colors, ready = true)) }
        if (state.value.live && (!source.ready || source.colors != colors)) queueSend()
    }
    fun acceptImage() {
        val image = state.value.imageImport ?: return
        if (!image.ready || state.value.imageLoading) return
        generation++
        pending = false
        beginStroke()
        mutable.update { it.copy(imageImport = null) }
        change(image.colors)
        endStroke()
        if (state.value.live) queueSend()
        previewAddress = null
    }
    fun cancelImage() {
        imageJob?.cancel()
        generation++
        pending = false
        mutable.update { it.copy(imageImport = null, imageLoading = false) }
        val restore = previewAddress != null && previewAddress == ble.connectedBoard?.address
        previewAddress = null
        if (restore && connection.value in setOf(ConnectionState.CONNECTED, ConnectionState.SENDING)) queueSend()
    }
    fun eraser() { mutable.update { it.copy(eraser = true) } }
    fun beginStroke() { state.value.document?.let { history.begin(it.colors) } }
    fun endStroke() { state.value.document?.let { history.end(it.colors) }; updateHistory() }
    fun paint(ids: Set<Int>) {
        val s = state.value
        val document = s.document ?: return
        if (s.loading || !s.boardReady) return
        val valid = ids.filter { if (document.board.brand == "moonboard") it in document.board.moonHoldIds() else it in s.placements }
        val colors = document.colors.toMutableMap()
        valid.forEach { if (s.eraser) colors.remove(it) else colors[it] = s.brush }
        change(colors)
    }
    private fun change(colors: Map<Int, Int>) {
        val old = state.value.document ?: return
        if (old.colors == colors) return
        leaveAgreed = false
        val next = old.copy(colors = colors)
        mutable.update { it.copy(document = next, dirty = next != saved && (saved != null || colors.isNotEmpty())) }
        if (state.value.live) queueSend()
    }
    private fun updateHistory() { mutable.update { it.copy(canUndo = history.canUndo, canRedo = history.canRedo) } }
    fun undo() { state.value.document?.let { change(history.undo(it.colors)) }; updateHistory() }
    fun redo() { state.value.document?.let { change(history.redo(it.colors)) }; updateHistory() }
    fun clear() { beginStroke(); change(emptyMap()); endStroke() }
    fun pause() {
        generation++
        pending = false
        mutable.update { it.copy(live = false) }
        ble.releaseKeepAlive("paintbox")
    }
    fun enter() {
        ble.pauseAutomaticProjection("paintbox")
        // Back from the catalogue settings: try the board again by itself.
        if (state.value.missingBoard) retryBoard()
    }
    /** Opens the current picture's board again, e.g. once its catalogue is downloaded. */
    private fun retryBoard() {
        val document = state.value.document ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try { open(document, persisted = saved?.id == document.id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(loading = false, message = R.string.paintbox_load_error) } }
        }
    }
    fun leave() { pause(); ble.resumeAutomaticProjection("paintbox") }
    fun toggleLive() {
        if (state.value.live) pause() else {
            if (connection.value != ConnectionState.CONNECTED) { message(R.string.paintbox_connect_error); return }
            projector.boardBusy()?.let { message(it); return }
            val document = state.value.document
            if (document != null && !onAppBoard(document)) { mutable.update { it.copy(otherBoard = true) }; return }
            mutable.update { it.copy(live = true) }
            ble.acquireKeepAlive("paintbox")
            queueSend()
        }
    }
    fun sendOnce() { queueSend() }
    private fun queueSend() {
        pending = true
        if (sender?.isActive == true) return
        sender = viewModelScope.launch {
            while (pending) {
                // Coalesce pointer samples without cancelling an in-flight GATT command.
                delay(100)
                if (!pending) break
                pending = false
                val ticket = generation
                val snapshot = state.value
                val original = snapshot.document ?: break
                val image = snapshot.imageImport
                if (image != null && !image.ready) continue
                val document = if (image == null) original else original.copy(colors = image.colors)
                val address = ble.connectedBoard?.address
                mutable.update { it.copy(sending = true) }
                try {
                    if (!snapshot.boardReady) throw PaintProjectionException(R.string.paintbox_missing_board)
                    if (!onAppBoard(document)) throw PaintProjectionException(R.string.paintbox_other_board)
                    if (image != null) previewAddress = address
                    val sent = projector.send(document, snapshot.ledMap) {
                        generation == ticket && ble.connectedBoard?.address == address
                    }
                    if (ticket == generation) {
                        if (!sent) { pause(); message(R.string.paintbox_send_error) }
                        else if (!state.value.live) message(if (projector.lastSendDimmed) R.string.paintbox_sent_dimmed else R.string.paintbox_sent)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (ticket == generation) {
                        pause()
                        when (val reason = (e as? PaintProjectionException)?.messageRes) {
                            R.string.paintbox_other_board -> mutable.update { it.copy(otherBoard = true) }
                            else -> message(reason ?: R.string.paintbox_send_error)
                        }
                    }
                } finally { mutable.update { it.copy(sending = false) } }
            }
        }
    }
    fun refreshLibrary() = viewModelScope.launch {
        try { val library = repository.list(); mutable.update { it.copy(library = library) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message(R.string.paintbox_load_error) }
    }
    fun save(name: String, copy: Boolean, done: () -> Unit = {}) {
        val original = state.value.document ?: return
        if (state.value.saving || name.isBlank()) return
        val document = original.copy(id = if (copy) UUID.randomUUID().toString() else original.id,
            name = name.trim(), updatedAt = System.currentTimeMillis())
        mutable.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                repository.save(document)
                saved = document
                mutable.update { s ->
                    val current = s.document
                    if (current?.id == original.id) s.copy(document = document.copy(colors = current.colors),
                        dirty = current.colors != document.colors, saving = false)
                    else s.copy(saving = false)
                }
                refreshLibrary()
                done()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(saving = false, message = R.string.paintbox_save_error) } }
        }
    }
    fun delete(document: PaintDocument) = viewModelScope.launch {
        try {
            repository.delete(document.id)
            if (saved?.id == document.id) { saved = null; mutable.update { it.copy(dirty = true) } }
            refreshLibrary()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message(R.string.paintbox_save_error) }
    }
    fun message(value: Int?) { mutable.update { it.copy(message = value) } }
    suspend fun preview(document: PaintDocument): PaintBoxState? = withContext(Dispatchers.IO) {
        try {
            val b = document.board
            PaintBoxState(boardSize = boards.getProductSize(b.sizeId, b.brand),
                placements = boards.getPlacementsForLayout(b.sizeId, b.layoutId, b.brand).associateBy { it.placementId.toInt() },
                images = boards.getBoardImages(b.sizeId, b.layoutId, b.brand))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
    override fun onCleared() { leave(); super.onCleared() }

    private companion object {
        const val SELECTION_SETTLE_MS = 50L
    }
}
