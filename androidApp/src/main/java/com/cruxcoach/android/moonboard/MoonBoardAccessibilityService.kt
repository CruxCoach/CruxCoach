package com.cruxcoach.android.moonboard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cruxcoach.android.MainActivity
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.navigation.Routes
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the official Moon app through its own Logbook and reads the visible
 * semantic labels.
 *
 * Two properties matter more than speed here:
 *
 *  * A list is only "finished" once Moon itself confirms it. Every list page
 *    carries an authoritative count — `Logbook\n83 entries, 382 problems` for
 *    the date list, `Logbook\n4 problems` and `4 problems (1 completed, 17
 *    tries)` for one session — so a momentarily stable but partially rendered
 *    page can never be mistaken for the end of the data.
 *  * A single odd page never aborts the run. Anything the parser cannot read is
 *    counted, named and carried into the result summary, so the user sees which
 *    entries were skipped instead of a generic failure.
 */
@AndroidEntryPoint
class MoonBoardAccessibilityService : AccessibilityService() {
    @Inject lateinit var importer: MoonBoardCsvImporter

    private enum class Stage(val drives: Boolean = false) {
        IDLE, PREPARING, FINISHING, SAVING,
        OPEN_HUB(true), OPEN_LOGBOOK(true), DATES(true), DETAIL(true), LEAVE_DETAIL(true),
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stage = Stage.IDLE

    private val traversal = MoonBoardListTraversal(LIST_END_CONFIRMATIONS)
    private var collector: MoonBoardSessionCollector? = null
    private val tally = MoonBoardImportTally()
    /** Moon entries already in the logbook per training day — the delta filter. */
    private var existingByDate: Map<String, MoonBoardImportedDay> = emptyMap()
    private val unreadableLabels = LinkedHashMap<String, Int>()
    private val warnings = ArrayList<String>()
    private val completedSessions = LinkedHashSet<String>()
    private val knownSessions = LinkedHashSet<String>()
    private var expectedSessions = 0
    private var expectedProblems = 0

    private var steps = 0
    private var scanStartedAt = 0L
    private var lastProgressAt = 0L
    private var listEndPasses = 0
    private var nextPollDelay = WAIT_POLL_MS
    private var rewinding = false
    private var rewinds = 0
    private var datesAtTop = false
    private var detailAtTop = false
    private var nullRootPasses = 0
    private var sampleLabels: List<String> = emptyList()
    @Volatile private var cancelRequested = false
    private var emptyPasses = 0
    private var leavePasses = 0
    private var clickFailures = 0
    private var openFailures = 0
    private var foreignWindowPasses = 0
    /** Absolute uptime of the pending drive; [NO_DRIVE_SCHEDULED] if none. */
    private var driveScheduledAt = NO_DRIVE_SCHEDULED

    override fun onServiceConnected() {
        MoonBoardAccessibilityBridge.connected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (stage.drives && event?.packageName == MOON_PACKAGE) scheduleDrive()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        driveScheduledAt = NO_DRIVE_SCHEDULED
        scope.cancel()
        if (MoonBoardAccessibilityBridge.service === this) {
            MoonBoardAccessibilityBridge.connected(
                null,
                getString(R.string.moon_scan_error_interrupted, completedSessions.size),
            )
        }
        super.onDestroy()
    }

    fun startScan() {
        if (stage != Stage.IDLE) return
        collector = null
        tally.reset()
        existingByDate = emptyMap()
        unreadableLabels.clear()
        warnings.clear()
        completedSessions.clear()
        knownSessions.clear()
        expectedSessions = 0
        expectedProblems = 0
        steps = 0
        foreignWindowPasses = 0
        nullRootPasses = 0
        sampleLabels = emptyList()
        clickFailures = 0
        openFailures = 0
        rewinding = false
        rewinds = 0
        datesAtTop = false
        cancelRequested = false
        scanStartedAt = SystemClock.elapsedRealtime()
        stage = Stage.PREPARING
        progress()
        MoonBoardAccessibilityBridge.update {
            MoonBoardScanState(
                serviceConnected = true,
                running = true,
                status = getString(R.string.moon_scan_status_preparing),
            )
        }
        scope.launch {
            // Which training days are already complete decides what has to be
            // opened at all, so it is read before Moon is even launched.
            existingByDate = runCatching { importer.beginScreenImport() }
                .onFailure { Log.w(TAG, "delta preload failed", it) }
                .getOrDefault(emptyMap())
            val launch = packageManager.getLaunchIntentForPackage(MOON_PACKAGE)
            if (launch == null) return@launch fail(getString(R.string.moon_scan_error_missing_app))
            enterStage(Stage.OPEN_HUB)
            status(getString(R.string.moon_scan_status_opening))
            // CLEAR_TASK finishes whatever Moon was left showing and starts it
            // at its own root, so a scan always begins from the same screen
            // instead of halfway down a logbook someone was browsing. It is the
            // ordinary "reopen from the launcher" path — no Moon data is
            // touched and the app keeps its session.
            startActivity(
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            scheduleDrive(MOON_RESTART_MS)
        }
    }

    /** Stops at the next safe point; days already read stay in the logbook. */
    fun cancelScan() {
        if (!stage.drives && stage != Stage.SAVING && stage != Stage.PREPARING) return
        Log.i(TAG, "cancel requested in $stage")
        cancelRequested = true
        scheduleDrive(0)
    }

    /**
     * Schedules the earliest requested look at Moon.
     *
     * Accessibility content events arrive in bursts while a list scrolls. The
     * old debounce removed a 90 ms scroll poll and replaced it with a fresh
     * 450 ms delay for every event, so more responsive Moon builds actually
     * made the scraper slower (and could starve it during an animation). An
     * event may now wake the driver sooner, but can never postpone work that is
     * already due. ListTraversal still prevents two scrolls on one node tree.
     */
    private fun scheduleDrive(delay: Long = EVENT_POLL_MS) {
        val dueAt = SystemClock.uptimeMillis() + delay.coerceAtLeast(0)
        if (driveScheduledAt != NO_DRIVE_SCHEDULED && driveScheduledAt <= dueAt) return
        handler.removeCallbacks(driveRunnable)
        driveScheduledAt = dueAt
        handler.postAtTime(driveRunnable, dueAt)
    }

    private val driveRunnable = Runnable {
        driveScheduledAt = NO_DRIVE_SCHEDULED
        drive()
    }

    private fun drive() {
        if (cancelRequested) return finishScan(cancelled = true)
        val now = SystemClock.elapsedRealtime()
        if (++steps > MAX_STEPS || now - scanStartedAt > SCAN_TIMEOUT_MS) {
            return fail(getString(R.string.moon_scan_error_timeout, completedSessions.size))
        }
        // A dark screen stops Moon from rendering, so the scan cannot make
        // progress and must not be blamed for it. Pause instead of failing;
        // it picks up by itself when the screen comes back.
        if (!isScreenOn()) {
            progress()
            return scheduleDrive(SCREEN_OFF_POLL_MS)
        }
        if (now - lastProgressAt > NO_PROGRESS_MS) {
            return fail(getString(R.string.moon_scan_error_stalled, completedSessions.size))
        }
        // rootInActiveWindow is briefly null while Moon swaps windows, and
        // relaunching on that restarts Moon at its hub and throws the list
        // position away. Only a null that persists means another app really
        // owns the screen.
        val root = rootInActiveWindow
        if (root == null) {
            if (++nullRootPasses < NULL_ROOT_LIMIT) return scheduleDrive(400)
            nullRootPasses = 0
            return recoverMoonForeground()
        }
        nullRootPasses = 0
        if (root.packageName?.toString() != MOON_PACKAGE) return recoverMoonForeground()
        foreignWindowPasses = 0
        val nodes = root.flatten()
        rememberLabels(nodes)
        readHeaderCounts(nodes)
        when (stage) {
            Stage.OPEN_HUB -> openHub(nodes)
            Stage.OPEN_LOGBOOK -> openLogbook(nodes)
            Stage.DATES -> scanDates(nodes)
            Stage.DETAIL -> scanDetail(nodes)
            Stage.LEAVE_DETAIL -> leaveDetail(nodes)
            Stage.IDLE, Stage.PREPARING, Stage.SAVING, Stage.FINISHING -> Unit
        }
    }

    /**
     * Keeps a few of the labels last seen on screen.
     *
     * The parser is written against the wording of one Moon build. If a future
     * one renames "Logbook" or its date rows, the scan finds nothing and there
     * is otherwise no way to tell that from "the logbook is empty". Reporting
     * what Moon actually showed turns an unreproducible bug report into an
     * answerable one.
     */
    private fun rememberLabels(nodes: List<AccessibilityNodeInfo>) {
        if (knownSessions.isNotEmpty()) return
        val seen = nodes.asSequence()
            .map { it.label() }
            .filter { it.isNotBlank() }
            .map { it.replace('\n', '/').take(60) }
            .distinct()
            .take(MAX_SAMPLE_LABELS)
            .toList()
        if (seen.isNotEmpty()) sampleLabels = seen
    }

    private fun isScreenOn(): Boolean =
        runCatching { getSystemService(PowerManager::class.java)?.isInteractive != false }
            .getOrDefault(true)

    /** `Logbook\n83 entries, 382 problems` is the run's completeness contract. */
    private fun readHeaderCounts(nodes: List<AccessibilityNodeInfo>) {
        nodes.forEach { node ->
            val header = MoonBoardScreenParser.parseHeader(node.label()) ?: return@forEach
            header.sessions?.let { if (it > expectedSessions) expectedSessions = it }
            // The per-session header also says "N problems"; only the date list
            // states an entry count, so the total is taken from that page alone.
            if (header.sessions != null) header.problems?.let { if (it > expectedProblems) expectedProblems = it }
        }
    }

    private fun openHub(nodes: List<AccessibilityNodeInfo>) {
        if (nodes.any { MoonBoardScreenParser.parseSession(it.label()) != null }) {
            enterStage(Stage.DATES)
            return scheduleDrive(400)
        }
        if (nodes.any { MoonBoardScreenParser.isProblemLabel(it.label()) }) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return scheduleDrive(900)
        }
        val logbook = nodes.firstOrNull { MoonBoardScreenParser.isLogbookTitle(it.label()) }
        if (logbook != null && click(logbook)) {
            enterStage(Stage.DATES)
            status("Reading logbook…")
        } else {
            val hub = nodes.firstOrNull { it.label().lineOne().equals("Hub", ignoreCase = true) }
            if (hub != null && click(hub)) {
                enterStage(Stage.OPEN_LOGBOOK)
                status(getString(R.string.moon_scan_status_logbook))
            }
        }
        scheduleDrive(900)
    }

    private fun openLogbook(nodes: List<AccessibilityNodeInfo>) {
        val logbook = nodes.firstOrNull { MoonBoardScreenParser.isLogbookTitle(it.label()) }
        if (logbook != null && click(logbook)) {
            enterStage(Stage.DATES)
            status("Reading logbook…")
        } else if (nodes.any { MoonBoardScreenParser.parseSession(it.label()) != null }) {
            enterStage(Stage.DATES)
        } else if (++emptyPasses >= EMPTY_SCREEN_LIMIT) {
            enterStage(Stage.OPEN_HUB)
        }
        scheduleDrive(900)
    }

    private fun scanDates(nodes: List<AccessibilityNodeInfo>) {
        val rows = nodes.mapNotNull { node ->
            MoonBoardScreenParser.parseSession(node.label())?.let { it to node }
        }.distinctBy { it.first.label }
        if (rows.isEmpty()) return recoverDateList(nodes)
        emptyPasses = 0
        rows.forEach { if (knownSessions.add(it.first.label)) progress() }

        // Moon may already have been open on a scrolled logbook. The date list
        // is only ever traversed downwards, so every session above the initial
        // viewport would never be opened — always start from the very top.
        if (!datesAtTop) {
            if (scrollBackward(nodes)) {
                progress()
                return scheduleDrive(SCROLL_POLL_MS)
            }
            datesAtTop = true
            traversal.reset()
        }

        // Delta load: retire every visible day the logbook already holds in
        // exactly the shape Moon describes. Done for the whole page at once so a
        // fully imported logbook costs scrolling only, not 83 open/close cycles.
        rows.forEach { (session, _) ->
            if (session.label in completedSessions) return@forEach
            if (!canSkipSession(session, existingByDate[session.climbedAt])) return@forEach
            completedSessions += session.label
            tally.skipSession(session.problems ?: 0)
            progress()
        }

        val next = rows.firstOrNull { it.first.label !in completedSessions }
        if (next != null) {
            if (click(next.second)) {
                collector = MoonBoardSessionCollector(next.first)
                detailAtTop = false
                clickFailures = 0
                enterStage(Stage.DETAIL)
                status(getString(R.string.moon_scan_status_session, next.first.label.lineOne()))
                return scheduleDrive(OPEN_SETTLE_MS)
            }
            // Never scroll past a row that refused to open — that is exactly how
            // a scan silently loses sessions. Retry, then record it by name.
            if (++clickFailures >= CLICK_RETRY_LIMIT) {
                clickFailures = 0
                completedSessions += next.first.label
                warn(MoonBoardDeviation.SessionNotOpened(next.first.label.lineOne()))
                progress()
            }
            return scheduleDrive(700)
        }

        if (rewinding) {
            if (scrollBackward(nodes)) {
                progress()
                return scheduleDrive(SCROLL_POLL_MS)
            }
            rewinding = false
            traversal.reset()
            listEndPasses = 0
            return scheduleDrive(700)
        }

        if (!advanceList(nodes, rows.joinToString("|") { it.first.label })) {
            listEndPasses = 0
            return scheduleDrive(nextPollDelay)
        }
        // Moon's own entry count is the completeness contract. A list that looks
        // finished while sessions are still missing gets extra confirmations …
        val missing = expectedSessions > 0 && completedSessions.size < expectedSessions
        if (missing && ++listEndPasses < LIST_END_GRACE) return scheduleDrive(900)
        // … and then one full pass from the top, because "the list will not
        // scroll further" only proves where the viewport is, not that every row
        // below the first screen was actually opened.
        if (missing && rewinds < MAX_REWINDS) {
            rewinds++
            rewinding = true
            listEndPasses = 0
            Log.i(TAG, "rewinding date list at ${completedSessions.size}/$expectedSessions sessions")
            return scheduleDrive(700)
        }
        finishScan()
    }

    private fun scanDetail(nodes: List<AccessibilityNodeInfo>) {
        val open = collector ?: run {
            enterStage(Stage.DATES)
            return scheduleDrive(500)
        }
        val session = open.session
        // Moon navigated back on its own (or the click never opened anything).
        if (nodes.any { MoonBoardScreenParser.parseSession(it.label()) != null }) {
            if (open.isEmpty) {
                // Retrying is right for a slow transition, but a row that never
                // opens would otherwise be retried forever.
                if (++openFailures >= OPEN_RETRY_LIMIT) {
                    openFailures = 0
                    completedSessions += session.label
                    warn(MoonBoardDeviation.SessionNotOpened(session.label.lineOne()))
                }
                collector = null
                enterStage(Stage.DATES)
                return scheduleDrive(500)
            }
            return completeSession(open, sessionExpectation(nodes, session), nodes)
        }
        openFailures = 0

        // Same reasoning as the date list: read a session from its first card,
        // never from wherever a restored view happens to sit.
        if (!detailAtTop) {
            if (scrollBackward(nodes)) {
                progress()
                return scheduleDrive(SCROLL_POLL_MS)
            }
            detailAtTop = true
            traversal.reset()
        }

        val visible = nodes.map { it.label() }
        if (open.observe(visible)) progress()
        val seen = open.seen
        publishProgress(seen)

        if (seen == 0) {
            // A single slow or partially rendered session must not end the run.
            if (++emptyPasses >= EMPTY_SCREEN_LIMIT) {
                return completeSession(open, sessionExpectation(nodes, session), nodes)
            }
            return scheduleDrive(OPEN_SETTLE_MS)
        }
        emptyPasses = 0

        val expected = sessionExpectation(nodes, session)
        if (expected != null && seen >= expected) return completeSession(open, expected, nodes)
        // Full semantic cards, not only their names: Moon permits repeated
        // names and may reorder/update card metadata while the first line stays
        // unchanged. Treating those views as identical can falsely signal a
        // stalled/end list.
        val fingerprint = visible.filter(MoonBoardScreenParser::isProblemLabel)
            .joinToString("|")
        if (!advanceList(nodes, fingerprint)) return scheduleDrive(nextPollDelay)
        completeSession(open, expected, nodes)
    }

    /** How many problems Moon promises for the open session. */
    private fun sessionExpectation(
        nodes: List<AccessibilityNodeInfo>,
        session: MoonBoardScreenSession,
    ): Int? {
        val header = nodes.firstNotNullOfOrNull { node ->
            MoonBoardScreenParser.parseHeader(node.label())?.takeIf { it.sessions == null }
        }
        return header?.problems ?: session.problems
    }

    /**
     * Stores one finished training day right away.
     *
     * Writing per day instead of once at the very end is what makes an
     * interrupted run useful: every day already read stays in the logbook, and
     * the next run's delta filter skips it. Moon is sent back to the date list
     * first, so the write overlaps its navigation instead of following it.
     */
    private fun completeSession(
        open: MoonBoardSessionCollector,
        expected: Int?,
        nodes: List<AccessibilityNodeInfo>,
    ) {
        val session = open.session
        val result = open.finish(expected)
        completedSessions += session.label
        result.unreadable.forEach { (label, count) -> unreadableLabels.merge(label, count, Int::plus) }
        result.deviations.forEach(::warn)
        collector = null
        progress()
        navigateBack(nodes)
        stage = Stage.SAVING
        scope.launch {
            val stored = if (!result.complete) {
                // Only Moon's exact three-way contract (problem count, sends,
                // tries) authorises a write. A partial or structurally
                // ambiguous reading must leave the existing day untouched.
                MoonBoardCsvImportResult(
                    foundEntries = result.observed,
                    notImported = result.observed,
                )
            } else runCatching {
                importer.importScreenSession(result.entries, result.complete) {
                    // The one-off catalogue scan takes tens of seconds on a
                    // full MoonBoard catalogue. Without this the screen keeps
                    // showing the last training day and looks frozen.
                    MoonBoardAccessibilityBridge.update {
                        it.copy(status = getString(R.string.moon_scan_status_catalogue))
                    }
                }
            }
                .getOrElse {
                    Log.w(TAG, "storing ${session.label.lineOne()} failed", it)
                    warnings += getString(
                        R.string.moon_scan_deviation_store_failed,
                        session.label.lineOne(),
                        result.entries.size,
                    )
                    MoonBoardCsvImportResult(foundEntries = result.entries.size, notImported = result.entries.size)
                }
            tally.add(stored)
            if (stored.keptOrphans > 0) {
                warn(MoonBoardDeviation.KeptEntries(session.label.lineOne(), stored.keptOrphans))
            }
            progress()
            publishProgress()
            enterStage(Stage.LEAVE_DETAIL)
            scheduleDrive(BACK_SETTLE_MS)
        }
    }

    private fun leaveDetail(nodes: List<AccessibilityNodeInfo>) {
        if (nodes.any { MoonBoardScreenParser.parseSession(it.label()) != null }) {
            enterStage(Stage.DATES)
            return scheduleDrive(300)
        }
        leavePasses++
        if (leavePasses > LEAVE_DETAIL_LIMIT) {
            enterStage(Stage.OPEN_HUB)
            return scheduleDrive(700)
        }
        // Re-send BACK sparingly: a second press that arrives while the first
        // transition is still running leaves the Logbook entirely.
        if (leavePasses % BACK_RETRY_EVERY == 0) performGlobalAction(GLOBAL_ACTION_BACK)
        scheduleDrive(BACK_SETTLE_MS)
    }

    private fun recoverDateList(nodes: List<AccessibilityNodeInfo>) {
        if (nodes.any { MoonBoardScreenParser.isProblemLabel(it.label()) }) {
            enterStage(Stage.LEAVE_DETAIL)
            performGlobalAction(GLOBAL_ACTION_BACK)
            return scheduleDrive(900)
        }
        val logbook = nodes.firstOrNull { MoonBoardScreenParser.isLogbookTitle(it.label()) }
        if (logbook != null && click(logbook)) return scheduleDrive(900)
        if (++emptyPasses >= EMPTY_SCREEN_LIMIT) enterStage(Stage.OPEN_HUB)
        scheduleDrive(900)
    }

    /**
     * One step through the current list. Returns true once it is proven to be
     * fully traversed; otherwise it has either advanced a page or decided to
     * look again, and [nextPollDelay] says how soon.
     */
    private fun advanceList(nodes: List<AccessibilityNodeInfo>, fingerprint: String): Boolean {
        val scrollable = nodes.firstOrNull { it.isScrollable }
        val canScroll = scrollable != null &&
            scrollable.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
        val step = traversal.next(fingerprint, canScroll)
        if (traversal.changed) progress()
        nextPollDelay = when (step) {
            MoonBoardListStep.SCROLL -> {
                scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SCROLL_POLL_MS
            }
            MoonBoardListStep.WAIT -> WAIT_POLL_MS
            MoonBoardListStep.EXHAUSTED -> WAIT_POLL_MS
        }
        return step == MoonBoardListStep.EXHAUSTED
    }

    /**
     * Scrolling happens through the accessibility action, never a synthesised
     * touch gesture. A fling gesture skips list rows outright, and a skipped
     * date row is a training day that never gets imported. Worse, a synthesised
     * drag is a real touch stream in someone else's app: a two-stroke gesture
     * is two simultaneous fingers, which crashed the official Moon app with
     * "pointerIndex out of range" inside its own ViewGroup. Driving another app
     * stays strictly within what that app already advertises.
     */
    private fun scrollBackward(nodes: List<AccessibilityNodeInfo>): Boolean {
        val scrollable = nodes.firstOrNull { it.isScrollable } ?: return false
        if (scrollable.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) {
            return false
        }
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun finishScan(error: String? = null, cancelled: Boolean = false) {
        stage = Stage.FINISHING
        handler.removeCallbacks(driveRunnable)
        driveScheduledAt = NO_DRIVE_SCHEDULED
        if (cancelled) {
            warnings += getString(
                R.string.moon_scan_deviation_cancelled,
                completedSessions.size,
                expectedSessions,
            )
        }
        if (expectedSessions > 0 && completedSessions.size != expectedSessions) {
            warnings += getString(
                R.string.moon_scan_deviation_sessions,
                completedSessions.size,
                expectedSessions,
            )
        }
        if (expectedProblems > 0 && tally.found != expectedProblems) {
            warnings += getString(R.string.moon_scan_deviation_problems, tally.found, expectedProblems)
        }
        unreadableLabels.forEach { (label, count) ->
            warnings += getString(R.string.moon_scan_deviation_label, count, label)
        }
        val result = tally.toResult(
            sessionsScanned = completedSessions.size,
            sessionsExpected = expectedSessions,
            problemsExpected = expectedProblems,
            warnings = warnings.toList(),
            error = error,
        )
        importer.endScreenImport()
        Log.i(
            TAG,
            "scan finished: ${result.foundEntries} entries " +
                "(${result.imported} imported, ${result.duplicates} duplicate, ${result.notImported} failed), " +
                "${result.sessionsScanned}/${result.sessionsExpected} sessions " +
                "(${result.sessionsSkipped} already stored), ${result.warnings.size} deviation(s)",
        )
        stage = Stage.IDLE
        cancelRequested = false
        MoonBoardAccessibilityBridge.update {
            it.copy(
                running = false,
                cancelling = false,
                status = "",
                captured = tally.found,
                sessionsDone = completedSessions.size,
                sessionsTotal = expectedSessions,
                result = result,
            )
        }
        returnToCruxCoach()
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        // Nothing was ever recognised: name what Moon actually put on screen so
        // the mismatch is diagnosable instead of just "it did not work".
        if (knownSessions.isEmpty() && sampleLabels.isNotEmpty()) {
            warnings += getString(R.string.moon_scan_deviation_unrecognised)
            sampleLabels.forEach { warnings += "· $it" }
        }
        // Everything read so far is already in the logbook, so a run that got
        // anywhere reports its real counters plus the reason it stopped —
        // never a bare failure that hides what was stored.
        if (tally.found == 0) {
            stage = Stage.IDLE
            handler.removeCallbacks(driveRunnable)
            driveScheduledAt = NO_DRIVE_SCHEDULED
            importer.endScreenImport()
            MoonBoardAccessibilityBridge.update {
                it.copy(
                    running = false,
                    cancelling = false,
                    status = "",
                    result = MoonBoardCsvImportResult(error = message),
                )
            }
            returnToCruxCoach()
            return
        }
        warnings += message
        finishScan()
    }

    /**
     * Brings the user back to the screen they started the transfer from, not
     * merely to the app. After minutes in Moon the CruxCoach task may sit on
     * any screen — or have been recreated — so the destination is stated
     * explicitly rather than assumed to still be on top.
     */
    private fun returnToCruxCoach() {
        // An explicit component rather than the launcher intent: bringing an
        // existing task forward via ACTION_MAIN does not deliver extras, so the
        // route would silently be dropped. CLEAR_TOP + SINGLE_TOP reuses the
        // running activity and hands it the destination through onNewIntent.
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .putExtra("navigate_to", Routes.MOONBOARD_CSV_IMPORT)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "could not return to CruxCoach", it) }
    }

    private fun warn(deviation: MoonBoardDeviation) {
        val message = when (deviation) {
            is MoonBoardDeviation.MissingProblems -> getString(
                R.string.moon_scan_deviation_missing, deviation.date, deviation.read, deviation.expected,
            )
            is MoonBoardDeviation.ExcessProblems -> getString(
                R.string.moon_scan_deviation_excess, deviation.date, deviation.read, deviation.expected,
            )
            is MoonBoardDeviation.UnknownWording -> getString(
                R.string.moon_scan_deviation_wording, deviation.date, deviation.count,
            )
            is MoonBoardDeviation.SendMismatch -> getString(
                R.string.moon_scan_deviation_sends, deviation.date, deviation.read, deviation.expected,
            )
            is MoonBoardDeviation.TryMismatch -> getString(
                R.string.moon_scan_deviation_tries, deviation.date, deviation.read, deviation.expected,
            )
            is MoonBoardDeviation.SessionNotOpened -> getString(
                R.string.moon_scan_deviation_not_opened, deviation.date,
            )
            is MoonBoardDeviation.KeptEntries -> getString(
                R.string.moon_scan_deviation_kept, deviation.date, deviation.count,
            )
        }
        if (warnings.size < MAX_WARNINGS && warnings.none { it == message }) warnings += message
        Log.i(TAG, "deviation: $message")
    }

    private fun progress() {
        lastProgressAt = SystemClock.elapsedRealtime()
    }

    private fun publishProgress(inFlight: Int = 0) {
        MoonBoardAccessibilityBridge.update {
            it.copy(
                captured = tally.found + inFlight,
                sessionsDone = completedSessions.size,
                sessionsTotal = expectedSessions,
            )
        }
    }

    private fun status(message: String) {
        Log.i(TAG, "$stage: $message (${tally.found} entries, ${completedSessions.size}/$expectedSessions sessions, ${tally.sessionsSkipped} skipped)")
        MoonBoardAccessibilityBridge.update {
            it.copy(
                status = message,
                captured = tally.found,
                sessionsDone = completedSessions.size,
                sessionsTotal = expectedSessions,
            )
        }
    }

    private fun enterStage(value: Stage) {
        Log.i(TAG, "$stage -> $value")
        stage = value
        traversal.reset()
        listEndPasses = 0
        emptyPasses = 0
        leavePasses = 0
        progress()
    }

    private fun recoverMoonForeground() {
        foreignWindowPasses++
        if (foreignWindowPasses < FOREIGN_WINDOW_LIMIT) return scheduleDrive(700)
        foreignWindowPasses = 0
        Log.i(TAG, "Restoring Moon while scan is active")
        val launch = packageManager.getLaunchIntentForPackage(MOON_PACKAGE)
            ?: return fail(getString(R.string.moon_scan_error_missing_app))
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        scheduleDrive(1200)
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    /**
     * Prefer Moon's visible back control over Android's global back dispatch.
     * Flutter applications may attach route-result/state restoration work to
     * that control. Current Moon usually maps both paths to Navigator.pop, but
     * using the app's own path gives future builds the opportunity to preserve
     * the Logbook ScrollController. Global back remains a reliable fallback.
     */
    private fun navigateBack(nodes: List<AccessibilityNodeInfo>): Boolean {
        val back = nodes.firstOrNull { node ->
            node.label().trim().lowercase() in BACK_LABELS
        }
        return (back != null && click(back)) || performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun AccessibilityNodeInfo.flatten(): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += this
        while (queue.isNotEmpty() && result.size < MAX_NODES) {
            val node = queue.removeFirst()
            result += node
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return result
    }

    /**
     * Flutter currently puts whole cards in contentDescription. A future
     * native/Compose screen may expose the same semantic value through `text`
     * instead, so keep that standard Accessibility fallback available.
     */
    private fun AccessibilityNodeInfo.label(): String =
        contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?.trim()
            ?: text?.toString().orEmpty().trim()

    private fun String.lineOne(): String = lineSequence().firstOrNull()?.trim().orEmpty()

    private companion object {
        const val MOON_PACKAGE = "com.trainingboard.moon"
        const val LIST_END_CONFIRMATIONS = 4
        const val LIST_END_GRACE = 4
        const val MAX_REWINDS = 2
        // Every delay below is backed by a retry path: a page that is not
        // ready yet costs one more pass, never a lost session. ACTION_SCROLL_
        // FORWARD advances exactly one viewport from wherever the list stands,
        // so reading a mid-animation tree can under-scroll but never skip rows.
        // Polling can be this quick because a repeated view is answered with
        // another look rather than another scroll (see MoonBoardListTraversal),
        // so a tree that has not caught up yet costs one cheap pass instead of
        // risking a skipped row. Successive scrolls landing under
        // ScrollView.ANIMATED_SCROLL_GAP (250 ms) also make the widget jump a
        // full viewport instantly rather than animate, which is both faster and
        // more deterministic.
        const val SCROLL_POLL_MS = 90L
        const val WAIT_POLL_MS = 120L
        const val EVENT_POLL_MS = 80L
        const val OPEN_SETTLE_MS = 500L
        const val BACK_SETTLE_MS = 450L
        const val CLICK_RETRY_LIMIT = 4
        const val OPEN_RETRY_LIMIT = 4
        const val MAX_STEPS = 20_000
        const val MAX_NODES = 4_000
        const val MAX_WARNINGS = 120
        const val FOREIGN_WINDOW_LIMIT = 2
        const val NULL_ROOT_LIMIT = 12
        const val MAX_SAMPLE_LABELS = 6
        const val SCREEN_OFF_POLL_MS = 2_000L
        const val MOON_RESTART_MS = 1_800L
        const val EMPTY_SCREEN_LIMIT = 8
        const val LEAVE_DETAIL_LIMIT = 12
        const val BACK_RETRY_EVERY = 3
        const val NO_PROGRESS_MS = 150_000L
        const val SCAN_TIMEOUT_MS = 75L * 60_000L
        const val TAG = "MoonBoardImport"
        const val NO_DRIVE_SCHEDULED = Long.MAX_VALUE
        val BACK_LABELS = setOf("back", "zurück")
    }
}
