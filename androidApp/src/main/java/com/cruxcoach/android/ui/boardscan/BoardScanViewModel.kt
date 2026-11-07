package com.cruxcoach.android.ui.boardscan

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.boardscan.BoardScanner
import com.cruxcoach.android.boardscan.FrameOutcome
import com.cruxcoach.android.boardscan.MatchResult
import com.cruxcoach.android.boardscan.ScanBoard
import com.cruxcoach.android.boardscan.ScanCatalogue
import com.cruxcoach.android.boardscan.ScanCatalogueLoader
import com.cruxcoach.android.boardscan.ScanTestLog
import com.cruxcoach.android.boardscan.ScanTestRecord
import com.cruxcoach.android.boardscan.ScanTestSummary
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

enum class BoardScanPhase { PREPARING, UNSUPPORTED, NO_CATALOGUE, READY, SCANNING, ANSWER }

/** A problem the scan offers (or the user picked). [row] is its place in the scan catalogue, -1 when not in it. */
data class ScanCandidate(
    val uuid: String,
    val name: String? = null,
    val grade: String? = null,
    val mirrored: Boolean = false,
    val matched: Int = 0,
    val holds: Int = 0,
    val row: Int = -1,
)

data class BoardScanState(
    val phase: BoardScanPhase = BoardScanPhase.PREPARING,
    val board: ScanBoard? = null,
    val boardLabel: String = "",
    val problems: Int = 0,
    val testMode: Boolean = true,
    val climberInFront: Boolean = false,
    /** Frame of the running scan (1-based). */
    val frame: Int = 0,
    /** The running scan's best guesses, or the answer. */
    val candidates: List<ScanCandidate> = emptyList(),
    val sure: Boolean = false,
    val leds: Int = 0,
    val msToAnswer: Long = 0,
    val frames: Int = 0,
    /** Test mode: the answer was wrong (or unsure), the user picks the lit problem. */
    val choosing: Boolean = false,
    val searchResults: List<ScanCandidate> = emptyList(),
    val summaries: List<ScanTestSummary> = emptyList(),
    /** Test scans saved in this visit. */
    val saved: Int = 0,
    val cameraError: Boolean = false,
    /** The last scan stopped with an error (e.g. out of memory on an older phone). */
    val scanFailed: Boolean = false,
    /** Test mode, Kilter: the lit problem picked by name was lit mirrored. */
    val truthMirrored: Boolean = false,
    /** The board's problems can be lit mirrored (Kilter). */
    val mirrorable: Boolean = false,
    /** Debuggable builds: frames come from files instead of the camera ([FileFrames]). */
    val fileFed: Boolean = false,
    /** An exported log waiting for the share sheet. */
    val shareFile: File? = null,
)

/**
 * The board scan (#65): reads the active board's catalogue into the recogniser, runs a scan on camera frames when
 * asked, and in test mode records each answer against the problem the user says was lit.
 */
@HiltViewModel
class BoardScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferences,
    private val repository: BoardRepository,
    private val loader: ScanCatalogueLoader,
) : ViewModel() {
    val grabber = ScanFrameGrabber()
    private val _state = MutableStateFlow(BoardScanState())
    val state: StateFlow<BoardScanState> = _state.asStateFlow()

    private var catalogue: ScanCatalogue? = null
    private var fileFrames: FileFrames? = null
    private var scanner: BoardScanner? = null
    private var scanJob: Job? = null
    private var searchJob: Job? = null
    private var gradeScale = GradeScale.FRENCH
    private val log = ScanTestLog(File(context.filesDir, "board_scan/test_scans.csv"))
    private val logLock = Mutex()

    /** The running scan: its start, its best frame so far and the latest partial ranking. */
    private var started = 0L
    @Volatile private var best: FrameOutcome? = null
    @Volatile private var partial: MatchResult? = null
    @Volatile private var partialLeds = 0
    private var frames = 0

    init {
        viewModelScope.launch { prepare() }
    }

    private suspend fun prepare() {
        val snap = preferences.getBoardFilterSnapshot()
        val board = ScanBoard(snap.boardBrand, snap.layoutId, preferences.boardProductSizeId.first(), snap.angle)
        gradeScale = snap.gradeScale
        val label = withContext(Dispatchers.IO) { boardLabel(board) }
        fileFrames = withContext(Dispatchers.IO) { FileFrames.find(context) }
        _state.update {
            it.copy(board = board, boardLabel = label, fileFed = fileFrames != null,
                phase = if (board.isSupported) BoardScanPhase.PREPARING else BoardScanPhase.UNSUPPORTED)
        }
        refreshSummary()
        if (!board.isSupported) return
        val cat = loader.load(board)
        if (cat == null || cat.size == 0) {
            _state.update { it.copy(phase = BoardScanPhase.NO_CATALOGUE) }
            return
        }
        catalogue = cat
        scanner = BoardScanner(cat, board.angle, threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS))
        _state.update { it.copy(phase = BoardScanPhase.READY, problems = cat.size, mirrorable = cat.searchMirrored) }
    }

    private fun boardLabel(board: ScanBoard): String {
        val name = board.moonBoardVariant?.displayName
            ?: listOfNotNull("Kilter", repository.getProductSize(board.sizeId, board.brand)?.name).joinToString(" ")
        return "$name · ${board.angle}°"
    }

    fun setTestMode(on: Boolean) = _state.update { it.copy(testMode = on) }

    fun setClimberInFront(on: Boolean) = _state.update { it.copy(climberInFront = on) }

    fun onCameraError() = _state.update { it.copy(cameraError = true) }

    /** Scans until a sure answer, [MAX_FRAMES] frames or [BUDGET_MS], whichever comes first. */
    fun scan() {
        val sc = scanner ?: return
        if (scanJob?.isActive == true) return
        started = SystemClock.elapsedRealtime()
        best = null
        partial = null
        partialLeds = 0
        frames = 0
        searchJob?.cancel()
        _state.update {
            it.copy(phase = BoardScanPhase.SCANNING, frame = 1, candidates = emptyList(), sure = false, choosing = false,
                searchResults = emptyList(), truthMirrored = false, cameraError = false, scanFailed = false)
        }
        scanJob = viewModelScope.launch {
            try {
                scanFrames(sc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // a failed frame (e.g. out of memory on an older phone) ends this scan, not the app
                Log.e(TAG, "scan failed", e)
                _state.update { it.copy(phase = BoardScanPhase.READY, scanFailed = true, candidates = emptyList()) }
            }
        }
    }

    private suspend fun scanFrames(sc: BoardScanner) {
        while (true) {
            val files = fileFrames
            val frame = if (files != null) {
                withContext(Dispatchers.IO) { runCatching { files.next() }.onFailure { Log.w(TAG, "file frame", it) }.getOrNull() }
            } else {
                withTimeoutOrNull(FRAME_TIMEOUT_MS) { grabber.next() }
            }
            if (frame == null) {
                // no picture from the camera: report it, and show what earlier frames gave
                _state.update { it.copy(cameraError = true, phase = if (frames == 0) BoardScanPhase.READY else it.phase) }
                if (frames == 0) return
                break
            }
            frames++
            _state.update { it.copy(frame = frames) }
            val outcome = runInterruptible(Dispatchers.Default) {
                sc.scan(frame) { leds, r ->
                    partialLeds = leds
                    partial = r
                    // from the search thread: show it on the main thread, unless the scan has ended meanwhile
                    viewModelScope.launch { if (_state.value.phase == BoardScanPhase.SCANNING) showGuesses(r) }
                }
            }
            partial = null
            val b = best
            if (b == null || outcome.result.sure || topScore(outcome.result) > topScore(b.result)) best = outcome
            if (outcome.result.sure) break
            if (frames >= MAX_FRAMES || SystemClock.elapsedRealtime() - started >= BUDGET_MS) break
            showGuesses(outcome.result)
        }
        finish()
    }

    /** Stops the running scan and shows its best guesses so far. */
    fun stop() {
        val job = scanJob ?: return
        if (!job.isActive) return
        viewModelScope.launch {
            job.cancel()
            job.join()
            finish()
        }
    }

    private fun topScore(r: MatchResult) = r.ranked.firstOrNull()?.score ?: Double.NEGATIVE_INFINITY

    private fun finish() {
        val ms = SystemClock.elapsedRealtime() - started
        val p = partial
        val b = best
        // a stopped scan: the partial ranking of the frame it stopped in may be better than the finished frames
        val result = if (p != null && (b == null || topScore(p) > topScore(b.result))) p else b?.result
        val leds = if (p != null && result === p) partialLeds else b?.leds ?: 0
        val sure = result?.sure == true
        _state.update {
            it.copy(phase = BoardScanPhase.ANSWER, candidates = candidates(result), sure = sure, leds = leds,
                msToAnswer = ms, frames = frames, choosing = false)
        }
        resolveNames()
    }

    private fun showGuesses(r: MatchResult) {
        _state.update { it.copy(candidates = candidates(r)) }
        resolveNames()
    }

    private fun candidates(r: MatchResult?): List<ScanCandidate> {
        val cat = catalogue ?: return emptyList()
        val ix = cat.index
        return r?.ranked?.take(3)?.map { c ->
            val known = _state.value.candidates.firstOrNull { it.row == c.climb && it.name != null }
            ScanCandidate(cat.uuids[c.climb], known?.name, known?.grade, c.mirrored, c.matched,
                ix.offsets[c.climb + 1] - ix.offsets[c.climb], c.climb)
        } ?: emptyList()
    }

    private val names = HashMap<String, Pair<String, String?>>()

    private fun resolveNames() {
        val missing = _state.value.candidates.map { it.uuid }.filter { it !in names }
        viewModelScope.launch {
            if (missing.isNotEmpty()) {
                val found = withContext(Dispatchers.IO) { lookUp(missing) }
                for (c in found) names[c.uuid] = c.name to c.difficultyAverage?.let { GradeDisplayHelper.formatDifficulty(it, gradeScale) }
            }
            _state.update { s ->
                s.copy(candidates = s.candidates.map { c -> names[c.uuid]?.let { c.copy(name = it.first, grade = it.second) } ?: c })
            }
        }
    }

    private fun lookUp(uuids: List<String>): List<ClimbWithStats> {
        val angle = _state.value.board?.angle ?: return emptyList()
        val atAngle = repository.getClimbsByUuids(uuids, angle)
        val rest = uuids.filter { u -> atAngle.none { it.uuid.equals(u, ignoreCase = true) } }
        val any = if (rest.isEmpty()) emptyList() else repository.getClimbsByUuidsAnyAngle(rest)
        return (atAngle + any).map { c -> c.copy(uuid = uuids.firstOrNull { it.equals(c.uuid, ignoreCase = true) } ?: c.uuid) }
    }

    fun scanAgain() {
        searchJob?.cancel()
        _state.update {
            it.copy(phase = BoardScanPhase.READY, candidates = emptyList(), choosing = false, searchResults = emptyList(),
                truthMirrored = false)
        }
    }

    // ── Test mode ───────────────────────────────────────────

    fun startChoosing() = _state.update { it.copy(choosing = true) }

    fun setTruthMirrored(on: Boolean) = _state.update { it.copy(truthMirrored = on) }

    /** The offered answer at [index] was the lit problem. */
    fun confirm(index: Int) {
        val c = _state.value.candidates.getOrNull(index) ?: return
        record(c)
    }

    fun search(query: String) {
        searchJob?.cancel()
        val board = _state.value.board ?: return
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val found = withContext(Dispatchers.IO) {
                // the scan catalogue's own problems (on Kilter at every angle), so every problem it could answer is found
                val hits = loader.search(board, query.trim(), SEARCH_LIMIT)
                val grades = lookUp(hits.map { it.first }).associate { it.uuid to it.difficultyAverage }
                hits.map { (uuid, name) -> ScanCandidate(uuid, name, grades[uuid]?.let { GradeDisplayHelper.formatDifficulty(it, gradeScale) }) }
            }
            _state.update { it.copy(searchResults = found) }
        }
    }

    /** The lit problem, picked from the search; on Kilter it may have been lit mirrored. */
    fun pick(c: ScanCandidate) {
        val row = catalogue?.rowOf(c.uuid) ?: -1
        record(c.copy(row = row, mirrored = _state.value.truthMirrored))
    }

    /** The lit problem is not in the catalogue on the phone. */
    fun notInCatalogue() = record(null)

    /** Leaves this scan out of the test results. */
    fun skip() = scanAgain()

    private fun record(truth: ScanCandidate?) {
        val s = _state.value
        val board = s.board ?: return
        val cat = catalogue ?: return
        val answer = s.candidates.firstOrNull()
        // the same hold set under another name is the same lit problem: the camera cannot tell them apart
        val rank = if (truth == null || truth.row < 0) 0 else s.candidates.indexOfFirst {
            it.mirrored == truth.mirrored && cat.index.sig[it.row] == cat.index.sig[truth.row]
        } + 1
        val rec = ScanTestRecord(
            timeMs = System.currentTimeMillis(), board = board, climberInFront = s.climberInFront,
            answerUuid = answer?.uuid, answerName = answer?.name, answerMirrored = answer?.mirrored == true, sure = s.sure,
            truthUuid = truth?.uuid, truthName = truth?.name, truthMirrored = truth?.mirrored == true, truthRank = rank,
            msToAnswer = s.msToAnswer, frames = s.frames, leds = s.leds,
            device = "${Build.MANUFACTURER} ${Build.MODEL}", appVersion = BuildConfig.VERSION_NAME,
        )
        viewModelScope.launch {
            val ok = logLock.withLock {
                withContext(Dispatchers.IO) { runCatching { log.append(rec) }.onFailure { Log.w(TAG, "log write failed", it) }.isSuccess }
            }
            if (ok) _state.update { it.copy(saved = it.saved + 1) }
            refreshSummary()
        }
        scanAgain()
    }

    private suspend fun refreshSummary() {
        val records = logLock.withLock { withContext(Dispatchers.IO) { runCatching { log.read() }.getOrDefault(emptyList()) } }
        val board = _state.value.board
        // the board in use first
        val summaries = ScanTestLog.summarise(records)
            .sortedByDescending { board != null && it.board.copy(angle = 0) == board.copy(angle = 0) }
        _state.update { it.copy(summaries = summaries) }
    }

    fun export() {
        viewModelScope.launch {
            val file = logLock.withLock {
                withContext(Dispatchers.IO) {
                    runCatching { log.exportTo(File(context.cacheDir, "board_scan"), System.currentTimeMillis()) }
                        .onFailure { Log.w(TAG, "export failed", it) }.getOrNull()
                }
            }
            _state.update { it.copy(shareFile = file) }
        }
    }

    fun shareHandled() = _state.update { it.copy(shareFile = null) }

    fun clearResults() {
        viewModelScope.launch {
            logLock.withLock { withContext(Dispatchers.IO) { log.clear() } }
            _state.update { it.copy(saved = 0) }
            refreshSummary()
        }
    }

    override fun onCleared() {
        val sc = scanner ?: return
        // the search threads go only once the scan has stopped using them
        val job = scanJob
        if (job != null && !job.isCompleted) job.invokeOnCompletion { sc.close() } else sc.close()
    }

    private companion object {
        const val TAG = "BoardScan"
        const val MAX_THREADS = 8
        const val MAX_FRAMES = 3
        const val BUDGET_MS = 10_000L
        const val FRAME_TIMEOUT_MS = 5_000L
        const val SEARCH_DEBOUNCE_MS = 250L
        const val SEARCH_LIMIT = 30
    }
}
