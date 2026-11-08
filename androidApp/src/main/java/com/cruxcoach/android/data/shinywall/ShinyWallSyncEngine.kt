package com.cruxcoach.android.data.shinywall

import android.util.Log
import com.cruxcoach.android.data.ShinyWallSnapshot
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.ClimbUuid
import com.cruxcoach.domain.board.ShinyWallCatalogue
import com.cruxcoach.domain.board.ShinyWallVariant
import com.cruxcoach.util.DateTimeUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ShinyWallImportPreview(
    val totalEntries: Int,
    val newAscents: Int,
    val newProjects: Int,
    val duplicates: Int,
    /** Entries that are no climb log (route creations) or cannot be placed (custom walls, unknown variant). */
    val skipped: Int,
    /**
     * Entries the import does not bring in again: deleted in CruxCoach after an earlier import,
     * or older than the last import's window (one that reached Shiny Wall late).
     */
    val deleted: Int = 0,
)

data class ShinyWallImportResult(
    val newAscents: Int,
    val newProjects: Int,
    val duplicates: Int,
    val skipped: Int,
    /** Lists written (favourites, own lists, own routes). */
    val lists: Int,
    /** Entries not written again, see [ShinyWallImportPreview.deleted]. */
    val deleted: Int = 0,
)

/** Names of the lists the import creates; localized by the caller. */
data class ShinyWallListNames(val favorites: String, val ownRoutes: String)

/**
 * Imports the signed-in user's own Shiny Wall logbook into CruxCoach.
 * Read-only towards Shiny Wall: nothing is uploaded, changed or deleted there.
 *
 * - Activity history → logbook. Sends become ascents, projects become
 *   attempts. An attempt entry holds Shiny's running total on the route and
 *   angle since the last send, so each day's attempts become one project
 *   with that day's share ([rows]). Shiny's attempt count of a send includes
 *   the attempts before it. Those of the send's day are part of it: no entry
 *   of their own, and an attempt an earlier import logged gives way to the
 *   send (or to the day's later entry) unless the user edited it. Those of
 *   earlier days stay entries of their own, and the send keeps the rest of
 *   the count.
 *   Each history entry gets a stable uuid and the external id
 *   `shinywall:history:<entry id>`, so a re-import never duplicates it and
 *   a local edit is never overwritten. Entries the user deleted locally stay
 *   deleted: the import keeps its own record of the entries it has placed
 *   ([ShinyWallTokenStorage.placedLogs]), and one the logbook lacks is never
 *   written again (Kilter's deletion queue only holds Kilter's entries).
 *   Entries older than the last import (minus [WATERMARK_OVERLAP_MS]) are
 *   not looked at again, so the record only keeps that window. An entry
 *   dated in the future does not move it: it would hide every later one.
 * - Favourites, own lists and own published routes → local lists
 *   (`shinywall:favorites`, `shinywall:list:<name>`, `shinywall:own-routes`),
 *   refreshed on every import. A list the user deleted stays deleted, like
 *   an entry ([ShinyWallTokenStorage.importedLists]). Best effort: a failure
 *   there never fails the logbook import.
 * - Clearing the logbook in Settings resets these records
 *   ([ShinyWallTokenStorage.resetImportRecords]): the next import brings
 *   everything in again.
 * - An external id that would not fit into a backup carries a hash of the
 *   entry id or list name instead ([MAX_EXTERNAL_ID_LENGTH]).
 *
 * Only standard walls are supported. A route is the catalogue climb
 * [ShinyWallSnapshot.climbUuid] of its variant; entries of custom walls
 * (no standard variant) are skipped and counted.
 */
@Singleton
class ShinyWallSyncEngine @Inject constructor(
    private val client: ShinyWallAccountClient,
    private val tokenStore: ShinyWallTokenStorage,
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val secureDb: SecureDatabase,
) {
    internal companion object {
        const val TAG = "ShinyWallSync"
        const val EXTERNAL_ID_PREFIX = "shinywall:history:"
        const val LIST_FAVORITES = "shinywall:favorites"
        const val LIST_OWN_ROUTES = "shinywall:own-routes"
        const val LIST_PREFIX = "shinywall:list:"
        const val BRAND = "shinywall"
        const val CLIMB_LOOKUP_CHUNK = 400
        /** History entries this close before the last import's newest entry are re-checked (deduped by uuid). */
        const val WATERMARK_OVERLAP_MS = 7L * 24 * 60 * 60 * 1000

        // What a backup takes back (CruxCoachBackup): a longer value makes the whole backup unrestorable.
        const val MAX_EXTERNAL_ID_LENGTH = 100
        const val MAX_LIST_NAME_LENGTH = 200
        const val MAX_ANGLE = 70
        const val MAX_ATTEMPTS = 100_000L

        /** Variant preference when a list route has no variant of its own. */
        val VARIANT_ORDER = listOf(
            ShinyWallVariant.ST1, ShinyWallVariant.ST5, ShinyWallVariant.ST2, ShinyWallVariant.ST4, ShinyWallVariant.ST0,
        )

        fun logUuid(docId: String): String =
            UUID.nameUUIDFromBytes("shinywall-log:$docId".encodeToByteArray()).toString()

        fun historyExternalId(docId: String): String = boundedExternalId(EXTERNAL_ID_PREFIX, docId)
        fun listExternalId(name: String): String = boundedExternalId(LIST_PREFIX, name)

        /** [prefix] + [key], or [prefix] + `#` + the SHA-256 of [key] when that is too long for a backup. */
        private fun boundedExternalId(prefix: String, key: String): String {
            val plain = prefix + key
            if (plain.length <= MAX_EXTERNAL_ID_LENGTH) return plain
            val hash = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())
            return prefix + "#" + hash.joinToString("") { "%02x".format(it) }
        }

        /** Attempts of a send from "more than nine" on. */
        const val MAX_SEND_ATTEMPTS = 10L

        /** (attempt_id, bid_count) of a send, or null when the entry is a project. */
        fun sendShape(sent: Int): Pair<Long, Long>? = when {
            sent < 0 -> null
            sent == 0 || sent == 1 -> 0L to 1L
            sent in 2..9 -> 1L to sent.toLong()
            sent == ShinyWallParsers.SENT_UNKNOWN_ATTEMPTS -> 1L to 1L
            else -> 1L to MAX_SEND_ATTEMPTS
        }

        fun epochMs(timestamp: String?): Long? =
            timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        /** Local wall-clock ISO date-time (seconds) of an RFC 3339 instant, the logbook's `climbed_at` shape. */
        fun localClimbedAt(timestamp: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
            timestamp?.let {
                runCatching {
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                        Instant.parse(it).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS)
                    )
                }.getOrNull()
            }
    }

    private val importMutex = Mutex()

    /** Time zone of the logbook dates; tests travel with it. */
    internal var zone: () -> ZoneId = ZoneId::systemDefault

    /** Wall clock (ms); caps the watermark. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** One history entry the import would write. */
    internal data class PlannedLog(
        val entry: ShinyWallHistoryEntry,
        val variant: ShinyWallVariant,
        val climbUuid: String,
        val uuid: String,
        val angle: Int,
        val climbedAt: String,
        /** (attempt_id, bid_count) for a send, null for a project. */
        val send: Pair<Long, Long>?,
        /** Attempts of a project. */
        val attempts: Long = 1,
    )

    internal data class Plan(
        val logs: List<PlannedLog>,
        /** Entries the logbook holds already. */
        val duplicates: Int,
        val skipped: Int,
        val total: Int,
        val newestMs: Long?,
        val catalogue: Map<String, ClimbWithStats>,
        /** Attempts an earlier import logged, unedited, that are now part of a send: removed on import. */
        val mergedAttempts: Set<String> = emptySet(),
        /** Entries an earlier import placed that the logbook lacks now: deleted there, never written again. */
        val deleted: Int = 0,
        /** What [ShinyWallTokenStorage.placedLogs] holds after this import. */
        val placed: Map<String, Long> = emptyMap(),
    )

    /** A history entry the logbook can take. */
    private data class Placed(
        val entry: ShinyWallHistoryEntry, val variant: ShinyWallVariant, val sent: Int, val angle: Int,
        val climbedAt: String, val ms: Long?,
    ) {
        val climbUuid: String get() = ShinyWallSnapshot.climbUuid(entry.routeId, variant)
        val uuid: String get() = logUuid(entry.docId)
        /** Local date of [climbedAt]. */
        val day: String get() = climbedAt.substringBefore('T')
        /** Attempts of a project. */
        val attempts: Long get() = if (sent < 0) (-sent.toLong()).coerceAtMost(MAX_ATTEMPTS) else 1L
    }

    /** An attempt entry a [LogRow] stands for, with the day's attempts up to and including it. */
    private class RowPart(val entry: Placed, val daySoFar: Long)

    /**
     * A logbook row: a send, or the last attempt entry of a day. [parts] are the entries of the
     * same day it stands for besides [entry], in time order. A send carries Shiny's count less
     * [earlierDays]; a project its day's [attempts]. [later] are the entries after a project's day
     * up to the send.
     */
    private class LogRow(
        val entry: Placed,
        val parts: List<RowPart>,
        val attempts: Long = 1,
        val earlierDays: Long = 0,
        val later: List<Placed> = emptyList(),
    )

    suspend fun previewImport(): Result<ShinyWallImportPreview> = withContext(Dispatchers.IO) {
        val session = storedSession().getOrElse { return@withContext Result.failure(it) }
            ?: return@withContext notSignedIn<ShinyWallImportPreview>()
        val history = client.fetchHistory(session.uid).getOrElse { return@withContext Result.failure(it) }
        logbookStep { plan(session.uid, history) }.map { plan ->
            ShinyWallImportPreview(
                totalEntries = plan.total,
                newAscents = plan.logs.count { it.send != null },
                newProjects = plan.logs.count { it.send == null },
                duplicates = plan.duplicates,
                skipped = plan.skipped,
                deleted = plan.deleted,
            )
        }
    }

    suspend fun importAll(listNames: ShinyWallListNames): Result<ShinyWallImportResult> = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val session = storedSession().getOrElse { return@withLock Result.failure(it) }
                ?: return@withLock notSignedIn<ShinyWallImportResult>()
            val history = client.fetchHistory(session.uid).getOrElse { return@withLock Result.failure<ShinyWallImportResult>(it) }
            val plan = logbookStep { plan(session.uid, history) }.getOrElse { return@withLock Result.failure(it) }
            logbookStep { write(plan) }.getOrElse { return@withLock Result.failure(it) }
            // Right after the write, before the lists suspend: cancelled there, the logbook
            // still knows what it holds.
            keystoreStep { tokenStore.recordImport(session.uid, clock(), plan.newestMs, plan.placed) }
                .onFailure { Log.w(TAG, "Import record not saved, logbook import kept") }
            val lists = try {
                importLists(session.uid, history, listNames)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "List import failed, logbook import kept", e)
                0
            }
            Log.i(
                TAG,
                "Imported ${plan.logs.size} entries (${plan.duplicates} known, ${plan.deleted} deleted here, " +
                    "${plan.skipped} skipped, ${plan.mergedAttempts.size} attempts merged into sends), $lists list(s)",
            )
            Result.success(
                ShinyWallImportResult(
                    newAscents = plan.logs.count { it.send != null },
                    newProjects = plan.logs.count { it.send == null },
                    duplicates = plan.duplicates,
                    skipped = plan.skipped,
                    lists = lists,
                    deleted = plan.deleted,
                )
            )
        }
    }

    /** The stored session; a Keystore error fails the import instead of crashing the app. */
    private fun storedSession(): Result<ShinyWallSession?> = keystoreStep { tokenStore.session() }

    private inline fun <T> keystoreStep(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        Log.w(TAG, "Sign-in storage failed (${e.javaClass.simpleName})")
        Result.failure(e)
    }

    /**
     * Runs [deleteLogbook] while no import runs, then forgets what imports have placed, so the next
     * import brings everything in again. Neither step can be cancelled half-way: a running import
     * would write its record back over the reset, and a cancelled reset would leave every entry
     * "won't be imported again" with the logbook already gone. A failed reset is the result; a
     * failed delete throws, as it did without the lock.
     */
    suspend fun clearLogbook(deleteLogbook: () -> Unit): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        importMutex.withLock {
            deleteLogbook()
            keystoreStep { tokenStore.resetImportRecords() }
        }
    }

    private fun <T> notSignedIn(): Result<T> =
        Result.failure(ShinyWallApiException(ShinyWallApiException.Reason.NotAuthenticated, "not signed in"))

    /** Runs a step on the logbook database; a database error fails the import instead of crashing the app. */
    private inline fun <T> logbookStep(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Logbook step failed (${e.javaClass.simpleName})")
        Result.failure(e)
    }

    internal fun plan(uid: String, history: List<ShinyWallHistoryEntry>): Plan {
        val zone = zone()
        val existing = personalBoardRepo.getExistingLogUuids()
        val deleted = personalBoardRepo.pendingLogDeletions().toHashSet()
        val placedBefore = tokenStore.placedLogs(uid)
        val now = clock()
        // Capped here too: a watermark an older version saved in the future heals.
        val lastWatermark = tokenStore.historyWatermarkMs(uid)?.coerceAtMost(now)
        val watermark = lastWatermark?.minus(WATERMARK_OVERLAP_MS)
        // Only entries the logbook holds or gets move the watermark, so skipped entries
        // newer than those stay in view (older ones need a new watermark key).
        var newestMs: Long? = null

        // Variant candidates per entry; the catalogue decides when the entry names none.
        val logs = history.filterNot { ShinyWallParsers.isCreation(it) }
        val candidates = logs.associateWith { variantCandidates(it) }
        val catalogue = lookupCatalogue(candidates.values.flatten().map { (variant, routeId) -> ShinyWallSnapshot.climbUuid(routeId, variant) })

        var skipped = history.size - logs.size
        val placed = mutableListOf<Placed>()
        for (entry in logs) {
            val options = candidates[entry].orEmpty()
            val variant = when {
                options.size == 1 -> options.single().first
                else -> options.firstOrNull { (v, r) -> ShinyWallSnapshot.climbUuid(r, v) in catalogue }?.first
            }
            val sent = entry.sent
            val angle = entry.angle?.takeIf { it in 0..MAX_ANGLE }
            val climbedAt = localClimbedAt(entry.timestamp, zone)
            if (variant == null || sent == null || angle == null || climbedAt == null) {
                skipped++
                continue
            }
            val ms = epochMs(entry.timestamp)
            // An entry with a date in the future would put every later one before the watermark.
            if (ms != null && ms <= now) newestMs = maxOf(newestMs ?: ms, ms)
            placed += Placed(entry, variant, sent, angle, climbedAt, ms)
        }

        // The whole history, not only what is past the watermark: a send can come after
        // attempts an earlier import has logged already.
        // An entry an earlier import placed is in the logbook, or the user deleted it there.
        fun isNew(p: Placed) = p.uuid !in existing && p.uuid !in deleted && p.uuid !in placedBefore &&
            (watermark == null || p.ms == null || p.ms >= watermark)

        // One row per send and one per day of attempts (see [rows]). An entry a row stands for
        // has none of its own, and one an earlier import logged gives way to a new row unless it
        // was edited since; then it stays, and the new row carries the rest. One the user deleted
        // stays deleted: the new row carries only what came after it. Once a row is in the
        // logbook, what stands beside it stays.
        fun known(e: Placed) = e.uuid in existing || e.uuid in placedBefore
        val partOfRow = HashSet<Placed>()
        val merged = mutableSetOf<String>()
        var duplicates = 0
        var deletedHere = 0
        val planned = mutableListOf<PlannedLog>()
        for (row in rows(placed)) {
            row.parts.forEach { partOfRow += it.entry }
            val p = row.entry
            if (p.uuid in existing) {
                duplicates++
                continue
            }
            val fresh = isNew(p)
            // A day that reached Shiny Wall after a later one was imported: that day's total holds
            // its attempts already (the counter carries over), so it brings none of its own.
            if (fresh && p.sent < 0 && row.later.any(::known)) {
                deletedHere++
                continue
            }
            // What the day's earlier rows hold: up to the latest kept one, or up to the latest one an
            // import wrote if the user deleted it. Only rows after that give way to the new one.
            val logged = if (fresh) row.parts.filter { it.entry.uuid in existing } else emptyList()
            val (asWritten, edited) = logged.partition { asImported(it) }
            val latestKnown = if (fresh) row.parts.lastOrNull { known(it.entry) } else null
            val holder = latestKnown?.takeIf { it.entry.uuid !in existing } ?: edited.lastOrNull()
            val held = holder?.daySoFar ?: 0L
            val attempts = row.attempts - held
            // No new attempts that day, or the day's other rows hold them all already.
            if (p.sent < 0 && attempts < 1) continue
            if (!fresh) {
                deletedHere++
                continue
            }
            val heldUpTo = holder?.let { row.parts.indexOf(it) } ?: -1
            asWritten.filter { row.parts.indexOf(it) > heldUpTo }.forEach { merged += it.entry.uuid }
            val send = if (p.sent < 0) null else {
                val own = row.earlierDays + held
                val shape = sendShape(p.sent)
                when {
                    shape == null || own == 0L -> shape
                    p.sent == ShinyWallParsers.SENT_UNKNOWN_ATTEMPTS -> shape
                    // After attempts on other days the send is no flash, and carries what is left
                    // of Shiny's count (before the cap).
                    else -> 1L to (p.sent - own).coerceIn(1L, MAX_SEND_ATTEMPTS)
                }
            }
            planned += PlannedLog(
                entry = p.entry,
                variant = p.variant,
                climbUuid = p.climbUuid,
                uuid = p.uuid,
                angle = p.angle,
                climbedAt = p.climbedAt,
                send = send,
                attempts = attempts,
            )
        }
        duplicates += placed.count { it in partOfRow && it.uuid in existing && it.uuid !in merged }

        // One entry per uuid even if Shiny lists an id twice.
        val unique = planned.distinctBy { it.uuid }
        duplicates += planned.size - unique.size

        // Every placed entry is dealt with now: in the logbook, deleted there, or part of its send.
        // Entries before the next import's window are never looked at again, so they need no record.
        // The window may move back (newer entries gone from Shiny Wall): keep the wider one.
        val keepFrom = listOfNotNull(newestMs, lastWatermark).minOrNull()?.minus(WATERMARK_OVERLAP_MS) ?: Long.MIN_VALUE
        val placedNow = (placedBefore + placed.mapNotNull { p -> p.ms?.let { p.uuid to it } })
            .filterValues { it >= keepFrom }
        return Plan(unique, duplicates, skipped, history.size, newestMs, catalogue, merged, deletedHere, placedNow)
    }

    /**
     * True while [part]'s row is still as an import wrote it: the same entry, no comment, and the
     * day's attempts up to it as its count. A row written with less (the rest after a kept row) or
     * changed by hand counts as edited. Not the date: the import writes it in the time zone of the
     * day, so after travelling the same entry has another one, and the app has no way to change it.
     */
    private fun asImported(part: RowPart): Boolean {
        val row = secureDb.bidsQueries.selectBidContent(part.entry.uuid).executeAsOneOrNull() ?: return false
        return row.external_id == historyExternalId(part.entry.entry.docId) &&
            row.bid_count == part.daySoFar &&
            row.comment.isNullOrBlank()
    }

    /**
     * The logbook rows of the history, per route and angle. Shiny keeps one attempt counter per
     * route and angle until the next send, and every time the user logs attempts it adds a history
     * entry with the counter's new total. So the attempts of a day are the day's last total minus
     * the one before the day (a total that fell is taken as a count of its own). Each day with new
     * attempts becomes one project row, its last entry, standing for the day's other entries. A send holds
     * Shiny's whole count: the attempts of its own day are part of it, those of earlier days stay
     * projects of their own, and the send keeps the rest.
     */
    private fun rows(placed: List<Placed>): List<LogRow> {
        val rows = mutableListOf<LogRow>()
        for (group in placed.groupBy { it.climbUuid to it.angle }.values) {
            val waiting = mutableListOf<Placed>()
            // At the same moment the attempt comes first, so it belongs to the send.
            for (p in group.sortedWith(compareBy({ it.ms ?: Long.MIN_VALUE }, { it.sent >= 0 }))) {
                if (p.sent < 0) {
                    waiting += p
                } else {
                    rows += runRows(waiting, p)
                    waiting.clear()
                }
            }
            rows += runRows(waiting, null)
        }
        return rows
    }

    /** Rows of the attempts since the last send, and of [send] if one ends them. */
    private fun runRows(attempts: List<Placed>, send: Placed?): List<LogRow> {
        val rows = mutableListOf<LogRow>()
        var before = 0L
        var earlierDays = 0L
        val sendParts = mutableListOf<RowPart>()
        val days = attempts.groupBy { it.day }.values.toList()
        days.forEachIndexed { i, day ->
            val last = day.last().attempts
            // A total that fell starts a count of its own; one that stayed added nothing.
            val base = if (last >= before) before else 0L
            val parts = day.map { RowPart(it, daySoFar = it.attempts - base) }
            val dayAttempts = last - base
            if (send != null && day.first().day == send.day) {
                sendParts += parts
            } else {
                val later = days.drop(i + 1).flatten() + listOfNotNull(send)
                rows += LogRow(day.last(), parts.dropLast(1), attempts = dayAttempts, later = later)
                earlierDays += dayAttempts
            }
            before = last
        }
        if (send != null) rows += LogRow(send, sendParts, earlierDays = earlierDays)
        return rows
    }

    /** (variant, routeId) options: the entry's own standard variant, or every supported one for an entry without a wall. */
    private fun variantCandidates(entry: ShinyWallHistoryEntry): List<Pair<ShinyWallVariant, String>> {
        val own = ShinyWallVariant.fromCode(entry.standard)
        return when {
            own != null -> if (own.supported) listOf(own to entry.routeId) else emptyList()
            // A wall but no standard variant: a custom wall, not supported.
            entry.standard == null && entry.wallId != null -> emptyList()
            entry.standard == null -> VARIANT_ORDER.map { it to entry.routeId }
            else -> emptyList()
        }
    }

    /** Catalogue climbs for [climbUuids], keyed by the uuid as given (any stored spelling matches). */
    private fun lookupCatalogue(climbUuids: Collection<String>): Map<String, ClimbWithStats> {
        val wanted = climbUuids.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val byKey = mutableMapOf<String, ClimbWithStats>()
        val lookup = wanted.asSequence().flatMap { ClimbUuid.spellings(it).asSequence() }.distinct().toList()
        for (chunk in lookup.chunked(CLIMB_LOOKUP_CHUNK)) {
            for (climb in boardRepository.getClimbsByUuidsAnyAngle(chunk)) {
                byKey[ClimbUuid.normKey(climb.uuid)] = climb
            }
        }
        return wanted.mapNotNull { uuid -> byKey[ClimbUuid.normKey(uuid)]?.let { uuid to it } }.toMap()
    }

    private fun write(plan: Plan) {
        if (plan.logs.isEmpty() && plan.mergedAttempts.isEmpty()) return
        val difficultyByAngle = mutableMapOf<Pair<String, Int>, Double>()
        for (angle in plan.logs.map { it.angle }.distinct()) {
            val uuids = plan.logs.filter { it.angle == angle }.map { it.climbUuid }.distinct()
            for (chunk in uuids.chunked(CLIMB_LOOKUP_CHUNK)) {
                boardRepository.getClimbDifficultiesForAngle(chunk, angle).forEach { (uuid, diff) ->
                    difficultyByAngle[ClimbUuid.normKey(uuid) to angle] = diff
                }
            }
        }
        personalBoardRepo.runInTransaction {
            plan.mergedAttempts.forEach { personalBoardRepo.deleteBid(it) }
            for (log in plan.logs) {
                val climb = plan.catalogue[log.climbUuid]
                val difficulty = ShinyWallCatalogue.difficultyForGrade(log.entry.grade)?.toLong()
                val diffAvg = difficultyByAngle[ClimbUuid.normKey(log.climbUuid) to log.angle]
                val send = log.send
                if (send != null) {
                    personalBoardRepo.insertAscent(
                        uuid = log.uuid,
                        climbUuid = log.climbUuid,
                        angle = log.angle.toLong(),
                        isMirror = false,
                        attemptId = send.first,
                        bidCount = send.second,
                        quality = null,
                        difficulty = difficulty,
                        isBenchmark = false,
                        comment = null,
                        climbedAt = log.climbedAt,
                        synced = true,
                        climbName = climb?.name.orEmpty(),
                        difficultyAverage = diffAvg,
                        climbFrames = climb?.frames.orEmpty(),
                        framesCount = climb?.framesCount ?: 1L,
                        boardBrand = BRAND,
                        layoutId = log.variant.layoutId,
                        externalId = historyExternalId(log.entry.docId),
                    )
                } else {
                    personalBoardRepo.insertBid(
                        uuid = log.uuid,
                        climbUuid = log.climbUuid,
                        angle = log.angle.toLong(),
                        isMirror = false,
                        bidCount = log.attempts,
                        comment = null,
                        climbedAt = log.climbedAt,
                        synced = true,
                        climbName = climb?.name.orEmpty(),
                        difficultyAverage = diffAvg,
                        boardBrand = BRAND,
                        layoutId = log.variant.layoutId,
                        externalId = historyExternalId(log.entry.docId),
                    )
                }
            }
        }
    }

    /**
     * Favourites, own lists and own routes → local lists. Returns how many lists were written.
     * A list an earlier import created that is gone now was deleted by the user: not created again.
     */
    private suspend fun importLists(
        uid: String,
        history: List<ShinyWallHistoryEntry>,
        names: ShinyWallListNames,
    ): Int {
        val profile = client.fetchProfile(uid).getOrElse {
            Log.w(TAG, "Profile unavailable, lists skipped (${it.javaClass.simpleName})")
            null
        }
        val ownRoutes = client.fetchOwnRoutes(uid).getOrElse {
            Log.w(TAG, "Own routes unavailable (${it.javaClass.simpleName})")
            emptyList()
        }
        profile?.username?.let { tokenStore.updateDisplayName(uid, it) }

        val preferred = history.mapNotNull { ShinyWallVariant.fromCode(it.standard)?.takeIf { v -> v.supported } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val order = listOfNotNull(preferred) + VARIANT_ORDER.filter { it != preferred }

        val lists = linkedMapOf<String, Pair<String, List<Pair<String, ShinyWallVariant?>>>>()
        profile?.favoriteRoutes?.takeIf { it.isNotEmpty() }?.let { refs ->
            lists[LIST_FAVORITES] = names.favorites to refs.map { it.routeId to null }
        }
        profile?.customLists?.forEach { (name, refs) ->
            lists[listExternalId(name)] = name.take(MAX_LIST_NAME_LENGTH) to refs.map { it.routeId to null }
        }
        if (ownRoutes.isNotEmpty()) {
            lists[LIST_OWN_ROUTES] = names.ownRoutes to ownRoutes.map {
                it.routeId to ShinyWallVariant.fromCode(it.originVariant)?.takeIf { v -> v.supported }
            }
        }
        if (lists.isEmpty()) return 0

        val allCandidates = lists.values.flatMap { (_, members) ->
            members.flatMap { (routeId, own) -> (listOfNotNull(own) + order).map { ShinyWallSnapshot.climbUuid(routeId, it) } }
        }
        val catalogue = lookupCatalogue(allCandidates)
        val q = secureDb.climbListsQueries
        val now = DateTimeUtil.nowIso()
        val created = tokenStore.importedLists(uid).toMutableSet()
        var written = 0
        secureDb.transaction {
            for ((externalId, value) in lists) {
                val (name, members) = value
                val climbUuids = members.mapNotNull { (routeId, own) ->
                    (listOfNotNull(own) + order).map { ShinyWallSnapshot.climbUuid(routeId, it) }.firstOrNull { it in catalogue }
                }.distinct()
                val existingId = q.findClimbListByExternalId(externalId).executeAsOneOrNull()
                if (existingId == null && (climbUuids.isEmpty() || externalId in created)) continue
                val listId = if (existingId != null) {
                    q.updateAuroraClimbListMeta(name = name, description = null, color = null, id = existingId)
                    q.deleteClimbListEntries(existingId)
                    existingId
                } else {
                    q.insertAuroraClimbList(name = name, created_at = now, description = null, color = null, external_id = externalId)
                    q.getLastInsertedListId().executeAsOne()
                }
                for (climbUuid in climbUuids) {
                    q.insertClimbListEntry(list_id = listId, climb_uuid = climbUuid, added_at = now)
                }
                q.prunePlaybackStepsOutsideMembership(listId)
                created += externalId
                written++
            }
        }
        // The lists are written: a failed record does not undo them.
        keystoreStep { tokenStore.recordImportedLists(uid, created) }
        return written
    }
}
