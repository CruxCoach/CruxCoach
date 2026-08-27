package com.cruxcoach.android.moonboard

/** What to do with a list after one look at it. */
enum class MoonBoardListStep {
    /** Advance one page: this view is fresh and fully accounted for. */
    SCROLL,

    /** Look again without touching the list. */
    WAIT,

    /** Proven to be fully traversed. */
    EXHAUSTED,
}

/**
 * Decides when a Moon list has really been traversed, and when it is safe to
 * advance it.
 *
 * Two rules carry the whole thing:
 *
 *  * **Never scroll twice on the same observation.** `performAction` is
 *    asynchronous, so the node tree can still describe the page *before* the
 *    last scroll. Acting on it again would move two pages while only one was
 *    inspected — exactly how a date row gets jumped over and a training day
 *    silently never imported. Because a repeated view is answered with WAIT
 *    instead of another scroll, the poll interval can be short without ever
 *    risking that.
 *  * **A list is only finished when the widget says so.** Android advertises
 *    ACTION_SCROLL_FORWARD only while there is room left, which is a far better
 *    end-of-list signal than "the rows stopped changing" — that cannot tell the
 *    bottom of a list from one that has not re-rendered yet. The repeated-view
 *    counter stays as a backstop for a list that advertises the action but
 *    ignores it.
 */
class MoonBoardListTraversal(private val confirmations: Int = 4) {
    private var last: String? = null
    private var actedOn: String? = null
    private var stablePasses = 0
    private var stalledPasses = 0

    /** True when the most recent [next] saw content it had not seen before. */
    var changed = false
        private set

    fun reset() {
        last = null
        actedOn = null
        stablePasses = 0
        stalledPasses = 0
        changed = false
    }

    /**
     * @param fingerprint what is visible right now.
     * @param canScroll whether the list still offers a forward scroll.
     */
    fun next(fingerprint: String, canScroll: Boolean): MoonBoardListStep {
        changed = fingerprint != last
        if (changed) {
            last = fingerprint
            stablePasses = 0
        } else {
            stablePasses++
        }
        if (!canScroll) {
            stalledPasses++
            return if (stalledPasses >= confirmations || stablePasses >= confirmations) {
                MoonBoardListStep.EXHAUSTED
            } else {
                MoonBoardListStep.WAIT
            }
        }
        stalledPasses = 0
        if (stablePasses >= confirmations) return MoonBoardListStep.EXHAUSTED
        if (fingerprint == actedOn) return MoonBoardListStep.WAIT
        actedOn = fingerprint
        return MoonBoardListStep.SCROLL
    }
}

/**
 * Prevents a second BACK from being queued while Moon is still processing the
 * first one. Recognition of the destination page is deliberately not gated:
 * a normal transition can therefore continue on its first accessibility event
 * with no added delay, while only a destructive retry has to wait.
 */
class MoonBoardBackRetryGate(private val minimumIntervalMs: Long) {
    private var retryNotBefore = 0L

    fun reset() {
        retryNotBefore = 0L
    }

    fun backRequested(nowMs: Long) {
        retryNotBefore = nowMs + minimumIntervalMs
    }

    /** Zero means a retry is safe now; otherwise the remaining wait in ms. */
    fun remainingDelay(nowMs: Long): Long = (retryNotBefore - nowMs).coerceAtLeast(0L)
}

/**
 * What the CruxCoach logbook already holds for one Moon training day.
 *
 * All three numbers have a counterpart in Moon's own date row, which is what
 * makes a content check possible at all.
 */
data class MoonBoardImportedDay(val entries: Int, val sends: Int, val tries: Int)

/**
 * Whether a training day can be skipped without opening it.
 *
 * Deliberately an exact match on all three of Moon's own counts rather than
 * "the logbook already has at least as many rows". A day that was edited in
 * Moon after the import — an extra problem, a project that became a send, a
 * corrected attempt count — changes at least one of them, and any disagreement
 * (in either direction) re-reads the day. A day is only ever skipped when
 * CruxCoach and Moon describe exactly the same training day.
 */
fun canSkipSession(session: MoonBoardScreenSession, imported: MoonBoardImportedDay?): Boolean {
    val problems = session.problems ?: return false
    if (problems <= 0 || imported == null) return false
    if (imported.entries != problems) return false
    session.completed?.let { if (imported.sends != it) return false }
    session.tries?.let { if (imported.tries != it) return false }
    // A day whose row states no counts at all carries no evidence to check.
    return session.completed != null || session.tries != null
}

/**
 * Something a session promised but the scan could not deliver. Kept structured
 * rather than pre-formatted so the wording lives in the localized resources and
 * the logic stays testable without a Context.
 */
sealed interface MoonBoardDeviation {
    val date: String

    /** Fewer problem cards were read than the session lists. */
    data class MissingProblems(override val date: String, val read: Int, val expected: Int) : MoonBoardDeviation

    /** More semantic cards were exposed than Moon says the day contains. */
    data class ExcessProblems(override val date: String, val read: Int, val expected: Int) : MoonBoardDeviation

    /** A card whose outcome wording the parser does not know. */
    data class UnknownWording(override val date: String, val count: Int) : MoonBoardDeviation

    /** Sends read do not match the session's "N completed". */
    data class SendMismatch(override val date: String, val read: Int, val expected: Int) : MoonBoardDeviation

    /** Attempts read do not match the session's "N tries". */
    data class TryMismatch(override val date: String, val read: Int, val expected: Int) : MoonBoardDeviation

    /** Moon refused to open the training day at all. */
    data class SessionNotOpened(override val date: String) : MoonBoardDeviation

    /**
     * Rows Moon no longer lists for this day that were kept rather than removed,
     * because somebody added a comment or a rating to them in CruxCoach.
     * Deleting those is the user's call, not the import's.
     */
    data class KeptEntries(override val date: String, val count: Int) : MoonBoardDeviation
}

/** The problem cards of one Moon session, plus everything that did not add up. */
data class MoonBoardSessionResult(
    val entries: List<MoonBoardScreenEntry>,
    val unreadable: Map<String, Int>,
    val deviations: List<MoonBoardDeviation>,
    val expected: Int?,
    val hasFullMoonContract: Boolean,
) {
    /** Every structurally visible card, including ones with unknown wording. */
    val observed: Int get() = entries.size + unreadable.values.sum()

    /**
     * True only when this reading accounts for the training day in full: every
     * problem Moon promised was seen, every card was understood, and the send
     * and attempt totals match Moon's own. Anything less and the importer must
     * not conclude that a stored row is gone from Moon — it might simply not
     * have been read.
     */
    val complete: Boolean get() =
        expected != null &&
            hasFullMoonContract &&
            observed == expected &&
            deviations.isEmpty()
}

/**
 * Accumulates the problem cards seen while one Moon session was open.
 *
 * Cards are counted, not de-duplicated: the same card is seen again on every
 * scroll pass, but a session that genuinely lists a problem twice still has to
 * import it twice. On [finish] the collected labels are checked against the
 * three counts Moon itself publishes for the session, so an incomplete or
 * misread session is named instead of quietly shrinking the import.
 */
class MoonBoardSessionCollector(val session: MoonBoardScreenSession) {
    private val labels = LinkedHashMap<String, Int>()

    val seen: Int get() = labels.values.sum()
    val isEmpty: Boolean get() = labels.isEmpty()

    /** @return true when this pass revealed a card that was not seen before. */
    fun observe(visible: List<String>): Boolean {
        var changed = false
        visible.filter(MoonBoardScreenParser::isProblemLabel)
            .groupingBy { it }
            .eachCount()
            .forEach { (label, count) ->
                if (count > (labels[label] ?: 0)) {
                    labels[label] = count
                    changed = true
                }
            }
        return changed
    }

    fun finish(expected: Int?): MoonBoardSessionResult {
        val entries = ArrayList<MoonBoardScreenEntry>()
        val unreadable = LinkedHashMap<String, Int>()
        labels.forEach { (label, count) ->
            val entry = MoonBoardScreenParser.parseProblem(label, session.climbedAt)
            if (entry == null) {
                unreadable.merge(label.summarise(), count, Int::plus)
            } else {
                repeat(count) { entries += entry }
            }
        }
        val date = session.label.lineOne()
        val deviations = ArrayList<MoonBoardDeviation>()
        if (expected != null && seen < expected) {
            deviations += MoonBoardDeviation.MissingProblems(date, seen, expected)
        }
        if (expected != null && seen > expected) {
            deviations += MoonBoardDeviation.ExcessProblems(date, seen, expected)
        }
        val missing = unreadable.values.sum()
        if (missing > 0) deviations += MoonBoardDeviation.UnknownWording(date, missing)
        session.completed?.let { promised ->
            val sends = entries.count { it.isSend }
            if (sends != promised) deviations += MoonBoardDeviation.SendMismatch(date, sends, promised)
        }
        session.tries?.let { promised ->
            val tries = entries.sumOf { it.attempts }
            if (tries != promised) deviations += MoonBoardDeviation.TryMismatch(date, tries, promised)
        }
        return MoonBoardSessionResult(
            entries = entries,
            unreadable = unreadable,
            deviations = deviations,
            expected = expected,
            hasFullMoonContract = expected != null && session.completed != null && session.tries != null,
        )
    }

    private companion object {
        fun String.lineOne(): String = lineSequence().firstOrNull()?.trim().orEmpty()

        /** Problem name plus outcome — enough to find the card, no account data. */
        fun String.summarise(): String {
            val lines = lines().map(String::trim).filter(String::isNotEmpty)
            return "${lines.firstOrNull().orEmpty()} — ${lines.lastOrNull().orEmpty()}"
        }
    }
}


/**
 * Running totals of an incremental on-device import.
 *
 * Each training day is written to the logbook as soon as it has been read, so
 * these counters are the only place where the run as a whole is summed up — and
 * an interrupted scan can still report exactly what it managed to store.
 */
class MoonBoardImportTally {
    var found = 0
        private set
    var importedAscents = 0
        private set
    var importedProjects = 0
        private set
    var duplicates = 0
        private set
    var notImported = 0
        private set
    var snapshotOnly = 0
        private set
    var stagedEntries = 0
        private set
    var unresolvedEntries = 0
        private set
    var replacedEntries = 0
        private set
    var keptOrphans = 0
        private set
    var sessionsSkipped = 0
        private set
    private val unresolved = LinkedHashSet<String>()

    fun reset() {
        found = 0
        importedAscents = 0
        importedProjects = 0
        duplicates = 0
        notImported = 0
        snapshotOnly = 0
        stagedEntries = 0
        unresolvedEntries = 0
        replacedEntries = 0
        keptOrphans = 0
        sessionsSkipped = 0
        unresolved.clear()
    }

    fun add(result: MoonBoardCsvImportResult) {
        found += result.foundEntries
        importedAscents += result.importedAscents
        importedProjects += result.importedProjects
        duplicates += result.duplicates
        notImported += result.notImported
        snapshotOnly += result.snapshotOnly
        stagedEntries += result.stagedEntries
        unresolvedEntries += result.unresolvedEntries
        replacedEntries += result.replacedEntries
        keptOrphans += result.keptOrphans
        unresolved += result.unresolvedLabels
    }

    /**
     * A training day that is already complete in the logbook and was therefore
     * never opened. Its problems are counted as found and as duplicates, which
     * is what they are — Moon lists them, CruxCoach already has them.
     */
    fun skipSession(problems: Int) {
        sessionsSkipped++
        found += problems
        duplicates += problems
    }

    fun toResult(
        sessionsScanned: Int,
        sessionsExpected: Int,
        problemsExpected: Int,
        warnings: List<String>,
        error: String? = null,
    ) = MoonBoardCsvImportResult(
        importedAscents = importedAscents,
        importedProjects = importedProjects,
        duplicates = duplicates,
        foundEntries = found,
        notImported = notImported,
        snapshotOnly = snapshotOnly,
        stagedEntries = stagedEntries,
        unresolvedEntries = unresolvedEntries,
        replacedEntries = replacedEntries,
        keptOrphans = keptOrphans,
        sessionsScanned = sessionsScanned,
        sessionsExpected = sessionsExpected,
        sessionsSkipped = sessionsSkipped,
        expectedEntries = problemsExpected,
        warnings = warnings,
        unresolvedLabels = unresolved.take(100),
        error = error,
    )
}
