package com.cruxcoach.app.kilter

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.RawAscent
import com.cruxcoach.data.repository.RawBid
import com.cruxcoach.domain.board.ClimbUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Kilter calls an upload run makes ([KilterApi] in the app, an in-memory Kilter in tests). */
interface KilterUploadApi {
    suspend fun fetchLogs(): KilterLogsOutcome

    /** Posts [logs] exactly as given; Kilter writes a bulk request as a whole or not at all. */
    suspend fun uploadLogs(logs: List<KilterLog>): KilterBulkOutcome

    /** `DELETE /logs/{uuid}` answered as whether Kilter held the log. */
    suspend fun deleteLogIfHeld(logUuid: String): KilterDeleteOutcome

    /** A user's deletion; NONE once Kilter confirmed the log is gone (404 included). */
    suspend fun deleteLog(logUuid: String): KilterFailure
}

/** Whether Kilter held a log it was asked to delete ([KilterUploadApi.deleteLogIfHeld]). */
enum class KilterDeleteOutcome { HELD, NOT_HELD, FAILED }

/** The account facts an upload needs; [KilterTokens] in the app. */
interface KilterUploadSession {
    fun userUuid(): String
    fun hasCredentials(): Boolean
    fun wallContext(): KilterWallContext?
    fun storeWallContext(context: KilterWallContext)
}

/** The part of the personal logbook an upload reads and stamps. */
interface KilterUploadLogbook {
    fun unsyncedAscents(): List<RawAscent>
    fun unsyncedBids(): List<RawBid>
    fun markAscentSyncedIfUnchanged(uuid: String, rowVersion: Long): Boolean
    fun markBidSyncedIfUnchanged(uuid: String, rowVersion: Long): Boolean
    fun runInTransaction(block: () -> Unit)
    fun pendingLogDeletions(): List<String>
    fun clearLogDeletion(logUuid: String)
    fun logUuidsWithExternalIdPrefix(prefix: String): Set<String>
}

/** What the board catalogue knows about the logbook's climbs. */
interface KilterUploadCatalogue {
    fun communityOnlyClimbUuids(uuids: Collection<String>): Set<String>
    fun cruxcoachClimbUuids(uuids: Collection<String>): Set<String>
    fun existingClimbUuids(uuids: Collection<String>): Set<String>
    /** Stored uuid → climb name, any angle. */
    fun climbNames(uuids: Collection<String>): Map<String, String>
}

class RepositoryKilterUploadLogbook(private val repo: PersonalBoardRepository) : KilterUploadLogbook {
    override fun unsyncedAscents() = repo.getUnsyncedAscents()
    override fun unsyncedBids() = repo.getUnsyncedBids()
    override fun markAscentSyncedIfUnchanged(uuid: String, rowVersion: Long) = repo.markAscentSyncedIfUnchanged(uuid, rowVersion)
    override fun markBidSyncedIfUnchanged(uuid: String, rowVersion: Long) = repo.markBidSyncedIfUnchanged(uuid, rowVersion)
    override fun runInTransaction(block: () -> Unit) = repo.runInTransaction(block)
    override fun pendingLogDeletions() = repo.pendingLogDeletions()
    override fun clearLogDeletion(logUuid: String) = repo.clearLogDeletion(logUuid)
    override fun logUuidsWithExternalIdPrefix(prefix: String) = repo.getLogUuidsWithExternalIdPrefix(prefix)
}

class RepositoryKilterUploadCatalogue(private val repo: BoardRepository) : KilterUploadCatalogue {
    override fun communityOnlyClimbUuids(uuids: Collection<String>) = repo.communityOnlyClimbUuids(uuids)
    override fun cruxcoachClimbUuids(uuids: Collection<String>) = repo.cruxcoachClimbUuids(uuids)
    override fun existingClimbUuids(uuids: Collection<String>) = repo.existingClimbUuids(uuids)
    override fun climbNames(uuids: Collection<String>): Map<String, String> =
        repo.getClimbsByUuidsAnyAngle(uuids).associate { it.uuid to it.name }
}

/**
 * Uploads the local logbook entries Kilter does not have yet: the upload half
 * of Android's `KilterSyncEngine` (0.2.4), same constants and rules.
 *
 * One run reads Kilter's copy of the logbook once and settles everything it
 * can locally: entries Kilter already holds are marked synced without a
 * request, entries that differ from Kilter's copy of their uuid stay local as
 * conflicts, and climb ids are sent in the spelling Kilter stores
 * ([KilterClimbWireIds]).
 *
 * Kilter fails a bulk request as a whole when it refuses one row. A run
 * therefore sends one by one the rows of a climb Kilter refused with proof
 * before, then settles retries of earlier refusals, then everything else in
 * chunks, splitting a refused chunk until the row is alone. A lone row tries
 * every id its climb may have on Kilter ([KilterClimbWireIds.candidates]). It
 * is held back ([KilterUploadLedger]) only with proof: Kilter accepted another
 * request after the row's last refusal in the same run, or it refused the
 * same climb with proof before. Kilter answers its own failures with HTTP 500
 * too, and an outage, also one that begins mid-run, must not park a row
 * Kilter would take. Requests per run are bounded; what is left waits for the
 * next run.
 *
 * Differences from Android, both forced by the platform: the gym, wall and
 * layout come only from a log the account already has on Kilter (no custom
 * wall is created, so a missing one stops with [KilterUploadReason.WALL_CONTEXT]);
 * and follow-up runs are timers in the app process ([CoroutineKilterUploadRetryScheduler]).
 */
class KilterUploadEngine(
    private val api: KilterUploadApi,
    private val session: KilterUploadSession,
    private val logbook: KilterUploadLogbook,
    private val catalogue: KilterUploadCatalogue,
    private val ledger: KilterUploadLedger,
    private val diagnostics: KilterUploadDiagnostics,
    private val idTables: KilterClimbIdTables,
    private val appVersionName: String,
    private val appVersionCode: Int,
    /** Wall clock in epoch milliseconds. */
    private val clock: () -> Long,
    /** False stops a run before its next request (Android's push switch). */
    private val pushEnabled: suspend () -> Boolean = { true },
) {
    /** Set once by the composition root: the scheduler starts runs of this engine. */
    var retryScheduler: KilterUploadRetryScheduler = KilterUploadRetryScheduler.NONE

    private val uploadMutex = Mutex()

    private val _sessionExpired = MutableStateFlow(false)

    /** True when Kilter refused the session during an upload; a new sign-in clears it. */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    fun clearSessionExpired() { _sessionExpired.value = false }

    /** The last run's status, restored from the diagnostics history. */
    val uploadStatus: StateFlow<KilterUploadStatus?> get() = diagnostics.latest

    /** True while a run holds the upload. */
    val isRunning: Boolean get() = uploadMutex.isLocked

    /**
     * Upload the local logbook entries Kilter does not have yet. Serialized
     * across manual and automatic triggers; never throws but for cancellation.
     */
    suspend fun upload(
        trigger: KilterUploadTrigger = KilterUploadTrigger.MANUAL,
        /** Number of this automatic follow-up run ([KilterUploadTrigger.RETRY]), 0 for any other. */
        retryAttempt: Int = 0,
    ): KilterUploadStatus = uploadMutex.withLock { UploadRun(trigger, retryAttempt).run() }

    suspend fun importedUploadEnabled(): Boolean = ledger.importedUploadEnabled()

    suspend fun setImportedUploadEnabled(enabled: Boolean) = ledger.setImportedUploadEnabled(enabled)

    /** Forget held-back rows, the imported opt-in and the history, e.g. when the Kilter account is disconnected. */
    suspend fun clearUploadLedger() = uploadMutex.withLock {
        retryScheduler.cancel()
        ledger.clear()
        diagnostics.clear()
    }

    suspend fun diagnosticSnapshot(): String = diagnostics.snapshot()

    /**
     * The entries the upload could not hand to Kilter, with the reason, for
     * the list the user can open and report. Entries synced since are left
     * out; one edited since is listed until the next run retries it. Reads
     * what the last run saved, without waiting for a run in progress.
     */
    suspend fun notUploadedEntries(): List<KilterNotUploadedEntry> {
        val now = clock()
        // Entries of an earlier build stay listed until a run of this one has decided them anew.
        val rejections = ledger.rejections()
            .filter { now - it.atMs <= KilterUploadLedger.MAX_AGE_MS }
            .sortedBy { it.appVersionCode == appVersionCode }
            .associateBy { it.logUuid }.values
        val outcome = ledger.lastOutcome()
        val rows = (logbook.unsyncedAscents().map { it.toRow() } + logbook.unsyncedBids().map { it.toRow() })
            .associateBy { it.uuid }
        val names = climbNames(rows.values.map { it.climbUuid })
        fun entry(row: CandidateRow, reason: KilterNotUploadedReason, wireId: String? = null, http: Int? = null) =
            KilterNotUploadedEntry(
                logUuid = row.uuid, climbUuid = row.climbUuid, climbName = names[ClimbUuid.normKey(row.climbUuid)],
                angle = row.angle, climbedAt = row.climbedAt, isAscent = row.isAscent,
                reason = reason, wireId = wireId, httpStatus = http,
            )
        return buildList {
            for (r in rejections) {
                val row = rows[r.logUuid] ?: continue
                val reason = if (r.confirmed || r.unproven >= UNPROVEN_STRIKES) KilterNotUploadedReason.NOT_ON_KILTER
                else KilterNotUploadedReason.RETRY_LATER
                add(entry(row, reason, r.wireId, r.httpStatus))
            }
            outcome.conflicts.mapNotNull(rows::get).forEach { add(entry(it, KilterNotUploadedReason.CONFLICT)) }
            outcome.invalid.mapNotNull(rows::get).forEach { add(entry(it, KilterNotUploadedReason.INVALID_DATE)) }
        }.distinctBy { it.logUuid }.sortedByDescending { it.climbedAt }
    }

    private fun climbNames(climbUuids: List<String>): Map<String, String> = try {
        climbUuids.asSequence().flatMap { ClimbUuid.spellings(it).asSequence() }.distinct().toList()
            .chunked(CLIMB_LOOKUP_CHUNK)
            .flatMap { catalogue.climbNames(it).entries }
            .associate { ClimbUuid.normKey(it.key) to it.value }
    } catch (e: Exception) {
        emptyMap()
    }

    /**
     * Pushes the user's deletions. Best effort: a failure leaves the
     * tombstone, which the next run tries again. 404 counts as done.
     */
    private suspend fun pushPendingLogDeletions(): Int {
        var done = 0
        for (logUuid in logbook.pendingLogDeletions()) {
            if (api.deleteLog(logUuid) == KilterFailure.NONE) {
                logbook.clearLogDeletion(logUuid)
                done++
            }
        }
        return done
    }

    private inner class UploadRun(
        private val trigger: KilterUploadTrigger,
        private val retryAttempt: Int,
    ) {
        private val start = clock()
        private var uploaded = 0
        private val attempted = HashSet<String>()
        private val accepted = HashSet<String>()
        private var pending = 0
        private var requests = 0
        private var heldImported = 0
        private var alreadyOnKilter = 0
        private var rejectedByKilter = 0
        private var rejectedConflict = 0
        private var rejectedInvalid = 0
        private var unconfirmed = 0
        private var replaced = 0
        /** Requests still without an answer; each is saved before it goes out ([KilterDoubtfulRequest]). */
        private val doubts = ArrayList<KilterDoubtfulRequest>()
        /** When the first row held back for a pause may go again; a run is woken for it. */
        private var restingUntil: Long? = null

        /** [more]: the run ended by itself with rows a follow-up run can take now (budget, rows waiting for Kilter). */
        private suspend fun finish(
            reason: KilterUploadReason = KilterUploadReason.NONE,
            http: Int? = null,
            more: Boolean = false,
        ): KilterUploadStatus {
            val retry = reason in RETRYABLE || reason == KilterUploadReason.NONE && more
            val next = (retryAttempt + 1).takeIf { retry && it <= KilterUploadRetryScheduler.DELAYS_MIN.size } ?: 0
            val status = KilterUploadStatus(
                uploaded = uploaded, pending = pending, attempted = attempted.size,
                reason = reason, httpStatus = http,
                timestampMs = clock(), durationMs = clock() - start,
                appVersion = "$appVersionName ($appVersionCode)", trigger = trigger,
                rejectedByKilter = rejectedByKilter, rejectedConflict = rejectedConflict,
                rejectedInvalid = rejectedInvalid, heldImported = heldImported,
                alreadyOnKilter = alreadyOnKilter, requests = requests, unconfirmed = unconfirmed,
                replaced = replaced, nextRetry = next,
            )
            diagnostics.record(status)
            if (reason == KilterUploadReason.AUTHENTICATION) _sessionExpired.value = true
            when {
                // A run of the chain moves it on; any other run leaves a chain that is under way as it is.
                next > 0 -> retryScheduler.schedule(next, replace = trigger == KilterUploadTrigger.RETRY)
                !retry -> {
                    retryScheduler.cancel()
                    restingUntil?.let { retryScheduler.wakeAfter((it - clock()).coerceAtLeast(0)) }
                }
            }
            return status
        }

        private fun overTime() = clock() - start >= RUN_TIME_BUDGET_MS

        private fun overBudget() = requests >= MAX_REQUESTS_PER_RUN || overTime()

        suspend fun run(): KilterUploadStatus = try {
            upload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed catalogue lookup or an unexpected answer: nothing is held
            // back, and the next trigger repeats the run.
            finish(KilterUploadReason.INTERNAL)
        }

        /** Asks Kilter to delete a copy it may hold of [logUuid]; true when it held one. */
        private suspend fun deleteCopy(logUuid: String): KilterDeleteOutcome =
            api.deleteLogIfHeld(logUuid).also { if (it == KilterDeleteOutcome.HELD) replaced++ }

        private suspend fun upload(): KilterUploadStatus {
            // Logs of CruxCoach community climbs that Kilter never accepted
            // stay local: Kilter does not know their uuid. They are not
            // pending, just not Kilter's.
            val allAscents = logbook.unsyncedAscents()
            val allBids = logbook.unsyncedBids()
            val localClimbs = (allAscents.map { it.climbUuid } + allBids.map { it.climbUuid }).distinct()
            val facts = catalogueFacts(localClimbs)
            val rows = allAscents.filter { ClimbUuid.normKey(it.climbUuid) !in facts.communityOnly }.map { it.toRow() } +
                allBids.filter { ClimbUuid.normKey(it.climbUuid) !in facts.communityOnly }.map { it.toRow() }

            val now = clock()
            // Decisions hold for this build only (an update may know the id Kilter
            // takes); entries of earlier builds keep a row queued and prove its climb.
            // A clock set back must not age entries out: only the past counts.
            val recent = ledger.rejections().filter { now - it.atMs <= KilterUploadLedger.MAX_AGE_MS }
            val previous = recent.filter { it.appVersionCode == appVersionCode }.associateBy { it.logUuid }
            val earlier = recent.filter { it.appVersionCode != appVersionCode }.associateBy { it.logUuid }
            val importedEnabled = ledger.importedUploadEnabled()
            val doubtful = ledger.doubtfulRequests().filter { now - it.atMs <= KilterUploadLedger.MAX_AGE_MS }
            val doubtfulRows = doubtful.flatMapTo(HashSet()) { r -> r.rows.map { it.logUuid } }
            // An imported entry tried under the opt-in stays queued until Kilter settled it, across updates too.
            val candidates = rows.filter {
                !it.imported || importedEnabled || it.uuid in previous || it.uuid in earlier || it.uuid in doubtfulRows
            }
            heldImported = rows.size - candidates.size
            // Until Kilter's logbook is read, rows held back count as not
            // uploaded, not pending (a run that stops early reports them so).
            rejectedByKilter = candidates.count { previous[it.uuid]?.confirmed == true }
            pending = candidates.size - rejectedByKilter
            if (!pushEnabled()) return finish(KilterUploadReason.DISABLED)
            if (candidates.isEmpty()) {
                // Nothing to upload, but the user's deletions still go out: iOS
                // has no separate sync that would carry them.
                if (session.hasCredentials()) pushPendingLogDeletions()
                return finish()
            }
            val userUuid = session.userUuid().takeIf { it.isNotBlank() }
                ?: return finish(KilterUploadReason.AUTHENTICATION)
            if (!session.hasCredentials()) return finish(KilterUploadReason.AUTHENTICATION)
            var prefetched: List<KilterLog>? = null
            val wall = session.wallContext() ?: run {
                // Only a log the account already has names its board; a guess
                // would file the entries against the wrong wall.
                val read = api.fetchLogs()
                if (read.failure != KilterFailure.NONE) {
                    val stop = stopFor(read.httpStatus, read.failure)
                    return finish(stop.reason, stop.http)
                }
                prefetched = read.logs
                KilterWallContext.fromLogs(read.logs)?.also(session::storeWallContext)
                    ?: return finish(KilterUploadReason.WALL_CONTEXT)
            }

            // Deletions first, also the copies of edited entries: Kilter refuses
            // a uuid it holds, so a row whose copy is still there waits.
            val pushed = pushPendingLogDeletions()
            val deleting = logbook.pendingLogDeletions().toHashSet()
            // Kilter's copy of the logbook, read once per run: retries after a
            // lost response, entries restored under a new uuid and edits of
            // already-uploaded rows are all settled against it without a request.
            // A log the user deleted here is on its way out of Kilter and must
            // not stand in for another entry.
            val fetched = prefetched?.takeIf { pushed == 0 } ?: run {
                val read = api.fetchLogs()
                if (read.failure != KilterFailure.NONE) {
                    val stop = stopFor(read.httpStatus, read.failure)
                    return finish(stop.reason, stop.http)
                }
                read.logs
            }
            val remote = fetched.filter { it.logUuid !in deleting }
            val remoteByUuid = remote.associateBy { it.logUuid }
            // Kilter's copy of one of this device's entries belongs to that entry: an
            // imported entry uploaded in an earlier run, or a row settled by its uuid
            // here, must not stand in as the twin of another entry.
            val ownCopies = logbook.logUuidsWithExternalIdPrefix(AURORA_IMPORT_PREFIX) + candidates.map { it.uuid }
            val twins = KilterTwinIndex(remote.filter { it.logUuid !in ownCopies })
            val learned = LinkedHashMap(ledger.learnedWireIds())
            val wireIds = KilterClimbWireIds(
                lowercaseIndex = idTables.lowercaseIndex(),
                cruxcoachKeys = facts.cruxcoach,
                accountSpellings = KilterClimbWireIds.accountSpellings(remote),
                legacyKeys = facts.legacy,
                aliases = idTables.aliases(),
                learned = learned,
            )

            val settled = ArrayList<KilterUploadItem>()
            var unmatched = ArrayList<KilterUploadItem>()
            val conflicts = ArrayList<String>()
            val invalid = ArrayList<String>()
            /** Rows waiting for Kilter: a copy still to delete, or a request it may still be writing. */
            val waitingForKilter = HashSet<String>()
            pending = candidates.size
            rejectedByKilter = 0
            for (row in candidates.sortedByDescending { it.sortMillis }) {
                if (row.uuid in deleting) {
                    waitingForKilter += row.uuid
                    continue
                }
                val createdAt = KilterLogUploadPlan.kilterTimestamp(row.climbedAt)
                if (createdAt == null) {
                    rejectedInvalid++
                    pending--
                    invalid += row.uuid
                    continue
                }
                val ids = wireIds.candidates(row.climbUuid)
                val item = KilterUploadItem(
                    uuid = row.uuid, rowVersion = row.rowVersion, isAscent = row.isAscent, imported = row.imported,
                    log = KilterLog(
                        logUuid = row.uuid, userUuid = userUuid,
                        climbUuid = ids.first(),
                        gymUuid = row.gymUuid ?: wall.gymUuid, wallUuid = row.wallUuid ?: wall.wallUuid,
                        productLayoutUuid = row.productLayoutUuid ?: wall.productLayoutUuid,
                        angle = row.angle, flashed = row.isAscent && row.bidCount <= 1L, topped = row.isAscent,
                        attempts = row.bidCount.toInt().coerceAtLeast(1), createdAt = createdAt, comment = row.comment,
                    ),
                    climbKey = ClimbUuid.normKey(row.climbUuid),
                    candidates = ids,
                )
                val known = remoteByUuid[row.uuid]
                when {
                    known == null -> unmatched += item
                    item.candidates.any { KilterLogUploadPlan.sameContent(item.log.copy(climbUuid = it), known) } -> settled += item
                    // Kilter holds the attempt this send grew from (a quick-log
                    // sequence of a build before the copy was replaced on change):
                    // its copy is replaced.
                    !known.topped && item.isAscent && known.angle == item.log.angle &&
                        item.candidates.any { ClimbUuid.normKey(it) == ClimbUuid.normKey(known.climbUuid) } &&
                        deleteCopy(row.uuid) != KilterDeleteOutcome.FAILED -> unmatched += item
                    // Otherwise bulk cannot update a log, and replacing what
                    // Kilter holds could undo an edit made there: the edit stays local.
                    else -> {
                        rejectedConflict++
                        pending--
                        conflicts += row.uuid
                    }
                }
            }

            // Requests whose answer was lost, decided against Kilter's logbook
            // before twins are matched: rows of a written request are on Kilter
            // and must not stand in for other entries.
            val listed = fetched.mapTo(HashSet()) { it.logUuid }
            val unsynced = candidates.mapTo(HashSet()) { it.uuid }
            /** Kilter wrote [r] as a whole: its rows are there as sent, but [except]; one edited since has its copy replaced. */
            suspend fun settleWritten(r: KilterDoubtfulRequest, except: String? = null) {
                val sent = r.rows.associateBy { it.logUuid }
                val keep = ArrayList<KilterUploadItem>()
                var kept = false
                for (item in unmatched) {
                    val row = sent[item.uuid]
                    when {
                        row == null || item.uuid == except -> keep += item
                        row.rowVersion == item.rowVersion -> settled += item
                        deleteCopy(item.uuid) != KilterDeleteOutcome.FAILED -> keep += item
                        else -> {
                            waitingForKilter += item.uuid
                            kept = true
                        }
                    }
                }
                unmatched = keep
                if (kept) doubts += r
            }
            val open = ArrayList<KilterDoubtfulRequest>()
            for (r in doubtful) {
                val local = r.rows.filter { it.logUuid in unsynced }
                when {
                    local.isEmpty() -> Unit
                    // Kilter wrote the request as a whole: also the attempts it does not list are there.
                    r.rows.any { it.logUuid in listed } -> settleWritten(r)
                    // Kilter may still be writing it: its rows wait.
                    now - r.atMs < DOUBT_SETTLE_MS -> {
                        doubts += r
                        local.mapTo(waitingForKilter) { it.logUuid }
                    }
                    // Kilter lists every ascent it holds; one missing means the request was not written.
                    local.any { it.ascent } -> Unit
                    else -> open += r
                }
            }
            // Attempts only, which Kilter's logbook may leave out: whether Kilter
            // holds one of the rows tells for all of them (bulk is atomic). Asking
            // deletes that row on Kilter; it goes up again with the rest.
            for ((index, r) in open.withIndex()) {
                val probe = r.rows.firstNotNullOfOrNull { row -> unmatched.firstOrNull { it.uuid == row.logUuid } }
                val answer = if (probe == null || index >= MAX_DOUBT_CHECKS_PER_RUN) KilterDeleteOutcome.FAILED
                else deleteCopy(probe.uuid)
                when (answer) {
                    KilterDeleteOutcome.HELD -> settleWritten(r, except = probe!!.uuid)
                    KilterDeleteOutcome.NOT_HELD -> Unit
                    // Undecided: the rows wait for the next run.
                    KilterDeleteOutcome.FAILED -> if (probe != null) {
                        doubts += r
                        val ids = r.rows.mapTo(HashSet()) { it.logUuid }
                        unmatched = unmatched.filterTo(ArrayList()) { item ->
                            (item.uuid !in ids).also { if (!it) waitingForKilter += item.uuid }
                        }
                    }
                }
            }

            unmatched = unmatched.filterTo(ArrayList()) { it.uuid !in waitingForKilter }

            // Exact twins for every entry first (under any id the climb has on
            // Kilter), then the nearest log up to a day away for imported ones,
            // so no exact twin is taken by a looser match.
            unmatched = unmatched.filterTo(ArrayList()) { item ->
                val twin = item.candidates.any { twins.claimExact(item.log.copy(climbUuid = it)) }
                if (twin) settled += item
                !twin
            }
            val near = twins.claimNearest(
                unmatched.filter { it.imported }.flatMap { item -> item.candidates.map { item.uuid to item.log.copy(climbUuid = it) } },
            )
            unmatched = unmatched.filterTo(ArrayList()) { item -> (item.uuid !in near).also { if (!it) settled += item } }
            if (settled.isNotEmpty()) {
                val marked = markSynced(settled)
                alreadyOnKilter += marked
                pending -= marked
            }

            // Entries of rows this run does not reach (request budget) keep
            // their state; settled and accepted rows drop theirs below.
            val held = LinkedHashMap(earlier).apply { putAll(previous) }
            settled.forEach { held.remove(it.uuid) }
            // A climb Kilter refused under every id, with proof, is not on Kilter:
            // its other rows go one by one and their lone refusal counts as proven.
            // Rows of a climb refused without proof wait like the refused row.
            val provenClimbs = previous.values.filter { it.confirmed }.mapNotNullTo(HashSet()) { it.climbKey }
            // Proven under an earlier build: retried one by one with every id, the proof still holds.
            val provenEarlier = earlier.values.filter { it.confirmed }.mapNotNullTo(HashSet()) { it.climbKey } - provenClimbs
            val suspectClimbs = previous.values.filterNot { it.confirmed }.associate { (it.climbKey ?: it.logUuid) to it.retryAtMs }
            val waiting = ArrayList<KilterUploadItem>()
            val retryAfterUpdate = ArrayList<KilterUploadItem>()
            val notOnKilter = ArrayList<KilterUploadItem>()
            val chunked = ArrayList<KilterUploadItem>()
            for (item in unmatched) {
                val decision = previous[item.uuid]?.takeIf { it.fingerprint == item.fingerprint }
                when {
                    decision?.confirmed == true -> {
                        rejectedByKilter++
                        pending--
                    }
                    decision != null || item.climbKey in suspectClimbs -> waiting += item
                    item.climbKey in provenClimbs -> notOnKilter += item.copy(candidates = listOf(item.log.climbUuid))
                    item.climbKey in provenEarlier -> retryAfterUpdate += item
                    else -> chunked += item
                }
            }
            // Only rows that may be accepted can prove a refusal; without them
            // unproven refusals are retried after a pause, not on every trigger.
            val fresh = chunked.isNotEmpty()
            fun retryAt(item: KilterUploadItem) = previous[item.uuid]?.retryAtMs ?: suspectClimbs[item.climbKey] ?: 0L
            val (due, resting) = waiting.partition { item -> fresh || now >= retryAt(item) }
            // Listed as "tried again" are the rows Kilter refused themselves.
            unconfirmed += resting.count { previous[it.uuid] != null }
            restingUntil = resting.minOfOrNull { retryAt(it) }

            val outcome = send(retryAfterUpdate + notOnKilter, retried = due, chunked = chunked)
            accepted.forEach { held.remove(it) }
            learned.putAll(outcome.learned)
            var unprovenHttp: Int? = null
            val provenNow = outcome.refused.filter { outcome.lastAcceptedRequest > it.request }.mapTo(HashSet()) { it.item.climbKey }
            for (r in outcome.refused) {
                val before = previous[r.item.uuid]?.takeIf { it.fingerprint == r.item.fingerprint && !it.confirmed }
                // Only a refusal on schedule counts as a strike; an early retry
                // (others were sent anyway) keeps the schedule as it was.
                val onSchedule = before == null || now >= before.retryAtMs
                val strikes = (before?.unproven ?: 0) + if (onSchedule) 1 else 0
                val proven = outcome.lastAcceptedRequest > r.request ||
                    r.item.climbKey in provenClimbs || r.item.climbKey in provenEarlier || r.item.climbKey in provenNow
                val retryAtMs = when {
                    proven -> 0
                    onSchedule -> now + UNPROVEN_RETRY_MS[(strikes - 1).coerceIn(0, UNPROVEN_RETRY_MS.lastIndex)]
                    else -> before.retryAtMs
                }
                held[r.item.uuid] = KilterUploadRejection(
                    logUuid = r.item.uuid, fingerprint = r.item.fingerprint, appVersionCode = appVersionCode,
                    httpStatus = r.http, atMs = now, confirmed = proven, retryAtMs = retryAtMs,
                    unproven = strikes, climbKey = r.item.climbKey, wireId = r.item.log.climbUuid,
                )
                // Unproven rows stay pending: they are retried, and a run is woken for them.
                if (proven) {
                    rejectedByKilter++
                    pending--
                } else {
                    unconfirmed++
                    unprovenHttp = r.http
                    restingUntil = minOf(restingUntil ?: retryAtMs, retryAtMs)
                }
            }
            ledger.saveRejections(held.values.sortedBy { it.atMs })
            ledger.saveLearnedWireIds(learned)
            ledger.saveLastOutcome(KilterUploadLastOutcome(conflicts = conflicts, invalid = invalid))
            ledger.saveDoubtfulRequests(doubts)
            // The imported opt-in covers the entries present when it was given:
            // it ends with the run that leaves none of them open.
            val complete = outcome.stop == null && !outcome.exhausted && outcome.deferred == 0 &&
                doubts.isEmpty() && waitingForKilter.isEmpty() && resting.isEmpty() &&
                outcome.refused.all { previous[it.item.uuid] == null }
            if (importedEnabled && complete) ledger.setImportedUploadEnabled(false)

            val stop = outcome.stop
            return when {
                stop != null -> finish(stop.reason, stop.http)
                // New rows refused and nothing accepted after any of them:
                // Kilter may be down. Shown as a failure, nothing held back.
                unprovenHttp != null && !outcome.anyAccepted && fresh -> finish(KilterUploadReason.HTTP, unprovenHttp)
                else -> finish(
                    http = unprovenHttp,
                    more = outcome.exhausted || outcome.deferred > 0 || waitingForKilter.isNotEmpty(),
                )
            }
        }

        /** Stamps synced=1 per row where its version is unchanged; returns how many were stamped. */
        private fun markSynced(items: List<KilterUploadItem>): Int {
            var marked = 0
            logbook.runInTransaction {
                for (item in items) {
                    val applied = if (item.isAscent) logbook.markAscentSyncedIfUnchanged(item.uuid, item.rowVersion)
                    else logbook.markBidSyncedIfUnchanged(item.uuid, item.rowVersion)
                    if (applied) marked++
                }
            }
            return marked
        }

        /**
         * One request; marks its rows synced when Kilter took it. The request
         * is saved as doubtful before it goes out and dropped once Kilter
         * answered (after the rows are marked), so that a lost answer — also
         * when the app is stopped mid-request — leaves a trace the next run
         * settles.
         */
        private suspend fun post(batch: List<KilterUploadItem>): Posted {
            requests++
            batch.forEach { attempted += it.uuid }
            val doubt = KilterDoubtfulRequest(clock(), batch.map { KilterDoubtfulRow(it.uuid, it.rowVersion, it.isAscent) })
            doubts += doubt
            ledger.saveDoubtfulRequests(doubts)
            val result = api.uploadLogs(batch.map { it.log })
            val http = result.httpStatus.takeIf { it != 0 }
            if (result.accepted) {
                batch.forEach { accepted += it.uuid }
                val marked = markSynced(batch)
                uploaded += batch.size
                pending -= marked
            }
            val answered = when {
                result.accepted -> true
                http != null -> http !in GATEWAY_HTTP
                else -> !result.mayHaveReachedKilter
            }
            if (answered) {
                doubts.remove(doubt)
                ledger.saveDoubtfulRequests(doubts)
            }
            if (result.accepted) return Posted.Accepted
            // Unauthenticated, offline, overloaded or a lost response (the
            // rows may have been written; the next run settles them against
            // Kilter's logbook): stop, keep everything pending.
            if (http == null || http == 401 || http == 403 || http in TRANSIENT_HTTP) {
                return Posted.Stopped(stopFor(result.httpStatus, result.failure))
            }
            return Posted.Refused(http)
        }

        /**
         * [item] was refused under every id it may have. A uuid Kilter holds is
         * refused whatever else is right (a copy from before an edit, or of a
         * request whose answer was lost): Kilter deletes it, and the row goes
         * once more. Null: Kilter holds no copy, the refusal is about the climb.
         */
        private suspend fun replaceHeldCopy(item: KilterUploadItem): KilterUploadItem? {
            if (item.replaced || deleteCopy(item.uuid) != KilterDeleteOutcome.HELD) return null
            return item.copy(log = item.log.copy(climbUuid = item.candidates.first()), tried = 0, replaced = true)
        }

        /**
         * Sends [singles] (rows of a climb Kilter refused with proof) one by
         * one, then [retried] and [chunked] in chunks, the retries first so that
         * the chunks after them can prove a renewed refusal. Every full chunk
         * goes out before a refused one is isolated, so a single bad row costs
         * the queue nothing but its own requests. Rows of a climb Kilter
         * refused under every id in this run leave the chunks not yet sent and
         * wait for the next run.
         */
        private suspend fun send(
            singles: List<KilterUploadItem>,
            retried: List<KilterUploadItem>,
            chunked: List<KilterUploadItem>,
        ): SendOutcome {
            val out = SendOutcome()
            val refusedClimbs = HashSet<String>()
            val promoted = ArrayList<KilterUploadItem>()
            val queue = ArrayDeque(singles)
            var current: KilterUploadItem? = null
            while (true) {
                // An opt-out while waiting/in flight takes effect before the next request.
                if (!pushEnabled()) return out.apply { stop = Stop(KilterUploadReason.DISABLED) }
                val item = current ?: queue.removeFirstOrNull() ?: break
                current = null
                if (requests >= MAX_SINGLE_REQUESTS_PER_RUN || overTime()) {
                    out.deferred += 1 + queue.size
                    break
                }
                // Kilter refused this climb under every id already: one request settles the row.
                val row = if (item.climbKey in refusedClimbs && item.tried == 0 && !item.replaced) {
                    item.copy(candidates = listOf(item.log.climbUuid))
                } else item
                when (val posted = post(listOf(row))) {
                    Posted.Accepted -> {
                        out.accept(requests)
                        out.learned[row.climbKey] = row.log.climbUuid
                        // The climb's other queued rows now know their id and can go in a chunk.
                        val same = queue.filter { it.climbKey == row.climbKey }
                        queue.removeAll(same.toSet())
                        promoted += same.map { it.startingWith(row.log.climbUuid) }
                    }
                    is Posted.Stopped -> return out.apply { stop = posted.stop }
                    is Posted.Refused -> {
                        current = row.nextCandidate() ?: replaceHeldCopy(row)
                        if (current == null) {
                            out.refused += Refusal(row, posted.http, requests)
                            refusedClimbs += row.climbKey
                        }
                    }
                }
            }

            // Retries are settled first, splitting included, so that the chunks
            // after them can prove a renewed refusal.
            val retries = ArrayDeque(retried.chunked(UPLOAD_CHUNK))
            val chunks = ArrayDeque((promoted + chunked).chunked(UPLOAD_CHUNK))
            val isolating = ArrayDeque<List<KilterUploadItem>>()
            while (retries.isNotEmpty() || chunks.isNotEmpty() || isolating.isNotEmpty()) {
                if (!pushEnabled()) return out.apply { stop = Stop(KilterUploadReason.DISABLED) }
                if (overBudget()) return out.apply { exhausted = true }
                val split = if (retries.isNotEmpty()) retries else isolating
                var batch = retries.removeFirstOrNull() ?: chunks.removeFirstOrNull() ?: isolating.removeFirst()
                if (batch.size > 1 && refusedClimbs.isNotEmpty()) {
                    val (refusedClimb, rest) = batch.partition { it.climbKey in refusedClimbs }
                    out.deferred += refusedClimb.size
                    if (rest.isEmpty()) continue
                    batch = rest
                }
                when (val posted = post(batch)) {
                    Posted.Accepted -> {
                        out.accept(requests)
                        batch.singleOrNull()?.takeIf { it.tried > 0 }?.let { out.learned[it.climbKey] = it.log.climbUuid }
                    }
                    is Posted.Stopped -> return out.apply { stop = posted.stop }
                    is Posted.Refused -> {
                        if (batch.size > 1) {
                            val half = batch.size / 2
                            split.addFirst(batch.subList(half, batch.size))
                            split.addFirst(batch.subList(0, half))
                            continue
                        }
                        val item = batch.single()
                        val next = item.nextCandidate() ?: replaceHeldCopy(item)
                        if (next != null) {
                            split.addFirst(listOf(next))
                        } else {
                            out.refused += Refusal(item, posted.http, requests)
                            refusedClimbs += item.climbKey
                        }
                    }
                }
            }
            return out
        }
    }

    private fun KilterUploadItem.startingWith(id: String) =
        copy(log = log.copy(climbUuid = id), candidates = listOf(id) + candidates.filter { it != id }, tried = 0)

    private sealed interface Posted {
        data object Accepted : Posted
        class Refused(val http: Int) : Posted
        class Stopped(val stop: Stop) : Posted
    }

    private class Stop(val reason: KilterUploadReason, val http: Int? = null)

    private fun stopFor(httpStatus: Int, failure: KilterFailure): Stop = when {
        httpStatus == 401 || httpStatus == 403 -> Stop(KilterUploadReason.AUTHENTICATION, httpStatus)
        httpStatus != 0 -> Stop(KilterUploadReason.HTTP, httpStatus)
        failure == KilterFailure.NOT_SIGNED_IN -> Stop(KilterUploadReason.AUTHENTICATION)
        failure == KilterFailure.TIMEOUT -> Stop(KilterUploadReason.TIMEOUT)
        failure == KilterFailure.OFFLINE || failure == KilterFailure.SERVER_ERROR -> Stop(KilterUploadReason.NETWORK)
        else -> Stop(KilterUploadReason.INTERNAL)
    }

    /** A row Kilter refused under every id it could name; [request] orders it against acceptances. */
    private class Refusal(val item: KilterUploadItem, val http: Int, val request: Int)

    private class SendOutcome {
        var anyAccepted = false
        /** Ordinal of the last request Kilter accepted, -1 if none: proof for refusals before it. */
        var lastAcceptedRequest = -1
        val refused = ArrayList<Refusal>()
        /** Ids Kilter took for rows that needed another than their first. */
        val learned = LinkedHashMap<String, String>()
        /** Rows left for the next run although the budget held. */
        var deferred = 0
        var stop: Stop? = null
        /** The request budget ran out with rows still unsent. */
        var exhausted = false

        fun accept(request: Int) {
            anyAccepted = true
            lastAcceptedRequest = request
        }
    }

    /** One unsynced ascent or bid, flattened for the upload plan. */
    private class CandidateRow(
        val uuid: String,
        val climbUuid: String,
        val angle: Int,
        val bidCount: Long,
        val comment: String?,
        val climbedAt: String,
        val gymUuid: String?,
        val wallUuid: String?,
        val productLayoutUuid: String?,
        val rowVersion: Long,
        val isAscent: Boolean,
        val imported: Boolean,
    ) {
        val sortMillis: Long = KilterLogUploadPlan.kilterTimestamp(climbedAt)
            ?.let(KilterLogUploadPlan::epochMillis) ?: Long.MIN_VALUE
    }

    private fun RawAscent.toRow() = CandidateRow(
        uuid = uuid, climbUuid = climbUuid, angle = angle.toInt(), bidCount = bidCount, comment = comment,
        climbedAt = climbedAt, gymUuid = gymUuid, wallUuid = wallUuid, productLayoutUuid = productLayoutUuid,
        rowVersion = rowVersion, isAscent = true, imported = externalId.isAuroraImport(),
    )

    private fun RawBid.toRow() = CandidateRow(
        uuid = uuid, climbUuid = climbUuid, angle = angle.toInt(), bidCount = bidCount, comment = comment,
        climbedAt = climbedAt, gymUuid = gymUuid, wallUuid = wallUuid, productLayoutUuid = productLayoutUuid,
        rowVersion = rowVersion, isAscent = false, imported = externalId.isAuroraImport(),
    )

    private fun String?.isAuroraImport() = this?.startsWith(AURORA_IMPORT_PREFIX) == true

    /** What the board catalogue knows about the logbook's climbs, by normKey. */
    private class CatalogueFacts(
        /** CruxCoach climbs Kilter never accepted. */
        val communityOnly: Set<String>,
        /** Every CruxCoach-authored climb. */
        val cruxcoach: Set<String>,
        /** Dashed-spelled logbook climbs whose catalogue row is compact (legacy). */
        val legacy: Set<String>,
    )

    /**
     * Looks up every stored spelling in chunks. A failed lookup fails the run,
     * which the next trigger repeats: without these facts a dashed spelling of
     * a legacy climb would be sent dashed, which Kilter accepts under a
     * separate statistics identity, so no retry would correct it.
     */
    private fun catalogueFacts(climbUuids: List<String>): CatalogueFacts {
        if (climbUuids.isEmpty()) return CatalogueFacts(emptySet(), emptySet(), emptySet())
        val lookup = climbUuids.asSequence()
            .flatMap { ClimbUuid.spellings(it).asSequence() }
            .distinct()
            .toList()
        val chunks = lookup.chunked(CLIMB_LOOKUP_CHUNK)
        val communityOnly = chunks.flatMap { catalogue.communityOnlyClimbUuids(it) }.mapTo(HashSet()) { ClimbUuid.normKey(it) }
        val cruxcoach = chunks.flatMap { catalogue.cruxcoachClimbUuids(it) }.mapTo(HashSet()) { ClimbUuid.normKey(it) }
        val dashedCompact = climbUuids.asSequence()
            .filter { it.length == 36 && it[8] == '-' }
            .map { ClimbUuid.normKey(it) }
            .distinct()
            .toList()
        val legacy = dashedCompact.chunked(CLIMB_LOOKUP_CHUNK)
            .flatMap { catalogue.existingClimbUuids(it) }
            .mapTo(HashSet()) { ClimbUuid.normKey(it) }
        return CatalogueFacts(communityOnly, cruxcoach, legacy)
    }

    companion object {
        /** Max uuids per board-catalogue IN() lookup (SQLite bound-variable limit). */
        const val CLIMB_LOOKUP_CHUNK = 400

        /**
         * Max logs per Kilter bulk-upload request. Kilter took over 30 s for
         * 200 rows under load (field report, 2026-10-05); smaller requests keep
         * its answer within the timeout.
         */
        const val UPLOAD_CHUNK = 100

        /**
         * Bulk requests per upload run. A clean queue needs one per
         * [UPLOAD_CHUNK] rows; isolating a refused row costs about two per
         * halving. Whatever is left over waits for the next run.
         */
        const val MAX_REQUESTS_PER_RUN = 40

        /** Statuses that say nothing about the rows: stop and retry later. */
        val TRANSIENT_HTTP = setOf(408, 425, 429, 502, 503, 504)

        /** Gateway answers: Kilter itself may still have written the request. */
        val GATEWAY_HTTP = setOf(502, 504)

        /** No new request starts after this long; what is left goes to a follow-up run. */
        const val RUN_TIME_BUDGET_MS = 5L * 60 * 1000

        /**
         * How long Kilter may still be writing a request whose answer was lost.
         * Before that its rows wait; after it, an ascent of the request missing
         * from Kilter's logbook shows the request was not written.
         */
        const val DOUBT_SETTLE_MS = 5L * 60 * 1000

        /** Doubtful requests of attempts only a run asks Kilter about (one deletion each). */
        const val MAX_DOUBT_CHECKS_PER_RUN = 10

        /** Stops after which an automatic follow-up run is scheduled. */
        val RETRYABLE = setOf(
            KilterUploadReason.NETWORK, KilterUploadReason.TIMEOUT, KilterUploadReason.HTTP,
            KilterUploadReason.INTERNAL, KilterUploadReason.WALL_CONTEXT,
        )

        /**
         * Requests a run may spend on rows sent one by one before the bulk
         * chunks: retries of unproven refusals, and rows of climbs Kilter
         * refused before or keeps under another id.
         */
        const val MAX_SINGLE_REQUESTS_PER_RUN = 16

        /** Retry spacing for a refusal without proof when a run has nothing else to send: 1 h, 4 h, 12 h, then daily. */
        val UNPROVEN_RETRY_MS = longArrayOf(1, 4, 12, 24).map { it * 60 * 60 * 1000 }

        /**
         * Lone refusals on schedule after which the list tells the user Kilter
         * does not know the climb. They never hold a row back.
         */
        const val UNPROVEN_STRIKES = 3

        /** external_id prefix of entries imported from an Aurora export. */
        const val AURORA_IMPORT_PREFIX = "aurora-json:"
    }
}
