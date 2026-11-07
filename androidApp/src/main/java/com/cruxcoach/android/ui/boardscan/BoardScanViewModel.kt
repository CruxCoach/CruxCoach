package com.cruxcoach.android.ui.boardscan

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.boardscan.BoardScanner
import com.cruxcoach.android.boardscan.Candidate
import com.cruxcoach.android.boardscan.FrameOutcome
import com.cruxcoach.android.boardscan.MatchResult
import com.cruxcoach.android.boardscan.ScanBoard
import com.cruxcoach.android.boardscan.ScanCatalogue
import com.cruxcoach.android.boardscan.ScanCatalogueLoader
import com.cruxcoach.android.boardscan.ScanDiagnostics
import com.cruxcoach.android.boardscan.ScanTestLog
import com.cruxcoach.android.boardscan.ScanTestRecord
import com.cruxcoach.android.boardscan.ScanTestSummary
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.BoardStateManager
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
import kotlinx.coroutines.NonCancellable
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
    /** The boards of [summaries] as the board screen names them. */
    val summaryLabels: Map<ScanBoard, String> = emptyMap(),
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
    /**
     * Test mode: the problem CruxCoach last lit on this board (from this phone or a nearby one), so the lit problem
     * is one tap away. The scan itself never sees it.
     */
    val onBoard: ScanCandidate? = null,
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
    private val boardState: BoardStateManager,
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
    @Volatile private var partial: FrameOutcome? = null
    private var frames = 0
    /** The frame the shown answer comes from: its LEDs, for test mode's look at the lit problem. */
    private var answered: FrameOutcome? = null

    private var litByApp: BoardStateManager.LastBoardClimb? = null

    init {
        viewModelScope.launch { prepare() }
        viewModelScope.launch {
            boardState.lastClimb.collect {
                litByApp = it
                updateOnBoard()
            }
        }
    }

    /** The problem CruxCoach lit, if it is one of this board's scan catalogue. */
    private suspend fun updateOnBoard() {
        val lit = litByApp
        val cat = catalogue
        val found = if (lit == null || cat == null) null else withContext(Dispatchers.Default) {
            val row = cat.rowOf(lit.uuid)
            if (row < 0) null else {
                val grade = runCatching { withContext(Dispatchers.IO) { lookUp(listOf(cat.uuids[row])) } }.getOrNull()
                    ?.firstOrNull()?.difficultyAverage?.let { GradeDisplayHelper.formatDifficulty(it, gradeScale) }
                ScanCandidate(cat.uuids[row], lit.name, grade, row = row)
            }
        }
        if (litByApp === lit) _state.update { it.copy(onBoard = found) }
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
        updateOnBoard()
    }

    /** The board as the board screen names it, e.g. "Kilter Original · 14x12 Super Tall, with Kickboard · 40°". */
    private fun boardLabel(board: ScanBoard): String {
        val name = board.moonBoardVariant?.displayName ?: run {
            val size = repository.getProductSize(board.sizeId, board.brand)
            listOfNotNull("Kilter Original", size?.let { BoardConstants.sizeLabel(it.id, it.name, it.boardBrand) }).joinToString(" · ")
        }
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
        answered = null
        frames = 0
        searchJob?.cancel()
        fileFrames?.startScan()
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
            val frameStart = SystemClock.elapsedRealtime()
            val outcome = runInterruptible(Dispatchers.Default) {
                sc.scan(frame) { o ->
                    partial = o
                    // from the search thread: show it on the main thread, unless the scan has ended meanwhile
                    viewModelScope.launch { if (_state.value.phase == BoardScanPhase.SCANNING) showGuesses(o.result) }
                }
            }
            partial = null
            val r = outcome.result
            Log.i(TAG, "frame $frames: ${outcome.leds} LEDs, brightness ${outcome.brightness}, detect ${outcome.detectMs} ms, " +
                "match ${outcome.matchMs} ms (coarse ${r.coarseMs}, verify ${r.verifyMs}, tiers ${r.tiers}), sure ${r.sure}")
            val b = best
            if (b == null || r.sure || topScore(r) > topScore(b.result)) best = outcome
            if (r.sure) break
            // another frame only when it can finish within the budget
            val now = SystemClock.elapsedRealtime()
            if (frames >= MAX_FRAMES || now - started + (now - frameStart) > BUDGET_MS) break
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
        val shown = if (p != null && (b == null || topScore(p.result) > topScore(b.result))) p else b
        answered = shown
        val result = shown?.result
        val sure = result?.sure == true
        _state.update {
            it.copy(phase = BoardScanPhase.ANSWER, candidates = candidates(result), sure = sure, leds = shown?.leds ?: 0,
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
        // one letter matches most of a catalogue: seconds of searching for nothing useful on an older phone
        if (query.trim().length < MIN_SEARCH_CHARS) {
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

    /**
     * The lit problem, picked from the search; on Kilter it may have been lit mirrored. The problem CruxCoach lit is
     * never mirrored: the app does not light problems mirrored.
     */
    fun pick(c: ScanCandidate, mirrored: Boolean = _state.value.truthMirrored) {
        val row = catalogue?.rowOf(c.uuid) ?: -1
        record(c.copy(row = row, mirrored = mirrored))
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
        val outcome = answered
        val sc = scanner
        val rec0 = ScanTestRecord(
            timeMs = System.currentTimeMillis(), board = board, climberInFront = s.climberInFront,
            answerUuid = answer?.uuid, answerName = answer?.name, answerMirrored = answer?.mirrored == true, sure = s.sure,
            truthUuid = truth?.uuid, truthName = truth?.name, truthMirrored = truth?.mirrored == true, truthRank = rank,
            msToAnswer = s.msToAnswer, frames = s.frames, leds = s.leds,
            device = "${Build.MANUFACTURER} ${Build.MODEL}", appVersion = BuildConfig.VERSION_NAME,
        )
        viewModelScope.launch {
            // the scan is saved even when the screen closes meanwhile; a failed look at the frame costs the numbers only
            val ok = withContext(NonCancellable) {
                val diag = if (outcome == null || sc == null) ScanDiagnostics() else withContext(Dispatchers.Default) {
                    runCatching { diagnose(sc, cat, outcome, truth?.takeIf { it.row >= 0 }) }
                        .onFailure { Log.w(TAG, "diagnostics failed", it) }.getOrDefault(ScanDiagnostics())
                }
                val rec = rec0.copy(diagnostics = diag)
                logLock.withLock {
                    withContext(Dispatchers.IO) { runCatching { log.append(rec) }.onFailure { Log.w(TAG, "log write failed", it) }.isSuccess }
                }
            }
            if (ok) _state.update { it.copy(saved = it.saved + 1) }
            refreshSummary()
        }
        scanAgain()
    }

    /**
     * Test mode: the numbers behind a scan. The answer's fit and the lit problem's, from the ranking or, when it was
     * not ranked, searched on its own in the same frame; the board's distance, the camera's pitch and the frame's
     * brightness. Only numbers derived from the frame's LEDs; the frame itself is long gone.
     */
    private fun diagnose(sc: BoardScanner, cat: ScanCatalogue, o: FrameOutcome, truth: ScanCandidate?): ScanDiagnostics {
        val ix = cat.index
        fun holds(c: Int) = ix.offsets[c + 1] - ix.offsets[c]
        val ranked = o.result.ranked
        val top = ranked.firstOrNull()
        val at = truth?.let { t -> ranked.indexOfFirst { it.mirrored == t.mirrored && ix.sig[it.climb] == ix.sig[t.row] } } ?: -1
        val truthFit: Candidate? = when {
            truth == null -> null
            at >= 0 -> ranked[at]
            else -> sc.explain(o, truth.row, truth.mirrored)
        }
        // the lit problem's pose places the board best; the answer's when the lit problem is unknown
        val pose = truthFit ?: top
        val peaks = o.blobs.map { it.peak }.sorted()
        val chroma = o.blobs.map { it.chroma }.sorted()
        return ScanDiagnostics(
            palette = o.result.palette,
            answerHolds = top?.let { holds(it.climb) },
            answerMatched = top?.matched,
            answerMissing = top?.missing,
            answerExtra = top?.extra,
            answerResid = top?.resid,
            answerMargin = if (ranked.size >= 2) ranked[0].score - ranked[1].score else null,
            truthHolds = truth?.let { holds(it.row) },
            truthRankAll = truth?.let { at + 1 },
            truthMatched = truthFit?.matched,
            truthMissing = truthFit?.missing,
            truthExtra = truthFit?.extra,
            distanceM = pose?.let { sc.distanceM(o, it) },
            pitchDeg = o.up?.let { BoardScanner.cameraPitchDeg(it) },
            brightness = o.brightness,
            ledPeakMedian = peaks.getOrNull(peaks.size / 2),
            ledChromaMedian = chroma.getOrNull(chroma.size / 2)?.toInt(),
            detectMs = o.detectMs,
            matchMs = o.matchMs,
            tiers = o.result.tiers,
            frameW = o.width,
            frameH = o.height,
        )
    }

    private suspend fun refreshSummary() {
        val records = logLock.withLock { withContext(Dispatchers.IO) { runCatching { log.read() }.getOrDefault(emptyList()) } }
        val board = _state.value.board
        // the board in use first
        val summaries = ScanTestLog.summarise(records)
            .sortedByDescending { board != null && it.board.copy(angle = 0) == board.copy(angle = 0) }
        val labels = withContext(Dispatchers.IO) {
            summaries.associate { s -> s.board to runCatching { boardLabel(s.board) }.getOrDefault("") }.filterValues { it.isNotEmpty() }
        }
        _state.update { it.copy(summaries = summaries, summaryLabels = labels) }
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
        /** A search reads the whole catalogue (seconds on an older phone): wait until typing pauses. */
        const val SEARCH_DEBOUNCE_MS = 600L
        const val SEARCH_LIMIT = 30
        const val MIN_SEARCH_CHARS = 2
    }
}
