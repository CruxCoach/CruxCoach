package com.cruxcoach.android.moonboard

import android.os.SystemClock
import android.util.Log
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MoonBoardCsvImporter @Inject constructor(
    private val secureDb: SecureDatabase,
    private val boardDb: BoardDatabase,
) {
    /**
     * Catalogue index for the on-device scan currently running, one entry per
     * board angle. Built lazily and kept for the whole scan because the
     * underlying query is a full catalogue scan (see Board.sq
     * listMoonScreenIndex) — roughly 20 s and six figures of rows.
     *
     * Per angle rather than for every angle a MoonBoard can be built at: a
     * logbook is usually entirely 40°, and loading the 25° half of the
     * catalogue for it would double both the time and the retained memory for
     * nothing. A 25° entry later in the same scan simply builds that index too.
     */
    private val screenIndex = HashMap<Long, Map<String, Any>>()

    /**
     * How many training days were resolved with a targeted name lookup so far.
     * A delta run usually re-reads one or two days; paying a full catalogue
     * scan (~28 s on a 226k-problem MoonBoard catalogue) to resolve a handful
     * of entries is the difference between "instant" and "looks frozen". Once a
     * run turns out to be large, the full index is cheaper overall and takes
     * over.
     */
    private var targetedLookups = 0

    private data class ScreenIndexRow(val uuid: String, val setter: String?)

    /**
     * Opens an on-device scan and reports how many Moon entries each training
     * day already has in the logbook, so the scan can skip days that are
     * complete instead of re-reading them.
     */
    suspend fun beginScreenImport(): Map<String, MoonBoardImportedDay> = withContext(Dispatchers.IO) {
        screenIndex.clear()
        targetedLookups = 0
        finalizePendingIfCatalogueReady()
        val days = HashMap<String, MoonBoardImportedDay>()
        fun merge(date: String, entries: Int, sends: Int, tries: Int) {
            val previous = days[date]
            days[date] = MoonBoardImportedDay(
                entries = (previous?.entries ?: 0) + entries,
                sends = (previous?.sends ?: 0) + sends,
                tries = (previous?.tries ?: 0) + tries,
            )
        }
        secureDb.ascentsQueries.countImportedAscentsByDate(SCREEN_PREFIX).executeAsList()
            .forEach { merge(it.climbed_at, it.entries.toInt(), it.entries.toInt(), it.tries.toInt()) }
        secureDb.bidsQueries.countImportedBidsByDate(SCREEN_PREFIX).executeAsList()
            .forEach { merge(it.climbed_at, it.entries.toInt(), 0, it.tries.toInt()) }
        days
    }

    /** Releases the cached catalogue index once a scan is over. */
    fun endScreenImport() {
        screenIndex.clear()
    }

    /**
     * Imports one Moon training day as soon as it has been read.
     *
     * Writing per day rather than once at the end keeps every finished day
     * durable: an interrupted scan leaves the days it managed to read in the
     * logbook, and the next run skips them.
     */
    suspend fun importScreenSession(
        entries: List<MoonBoardScreenEntry>,
        complete: Boolean = false,
        onCatalogueScan: () -> Unit = {},
    ): MoonBoardCsvImportResult =
        withContext(Dispatchers.IO) {
            if (entries.isEmpty()) return@withContext MoonBoardCsvImportResult()
            var ascents = 0
            var projects = 0
            var duplicates = 0
            var notImported = 0
            var snapshotOnly = 0
            var staged = 0
            var trulyUnresolved = 0
            var replaced = 0
            var kept = 0
            val unresolved = linkedSetOf<String>()
            val occurrence = HashMap<String, Int>()
            val observedExternalIds = LinkedHashSet<String>()

            // Resolve against the catalogue *before* opening the write
            // transaction. Catalogue lookups inside it held the secure database
            // for the entire import and froze every other screen with it.
            val catalogue = resolveScreenEntries(entries, onCatalogueScan)
            val catalogueComplete = catalogueIsComplete()

            val prepared = entries.mapNotNull { entry ->
                val resolved = catalogue[screenKey(entry)]
                val signature = listOf(
                    entry.name, entry.setter, entry.angle, entry.climbedAt,
                    entry.tries, entry.attempts,
                ).joinToString(":")
                val ordinal = occurrence.merge(signature, 1, Int::plus) ?: 1
                val kind = if (entry.isSend) "ascent" else "bid"
                val externalId = "moon-screen:$kind:${sha256("$signature:$ordinal").take(32)}"
                observedExternalIds += externalId
                if (resolved == null) {
                    stageScreen(entry, externalId, catalogueComplete)
                    if (catalogueComplete) {
                        snapshotOnly++
                        trulyUnresolved++
                        unresolved += "${entry.name} — ${entry.setter} @ ${entry.angle}°"
                        return@mapNotNull PreparedEntry(entry, screenSnapshot(entry), externalId)
                    }
                    staged++
                    return@mapNotNull null
                }
                PreparedEntry(
                    entry = entry,
                    climb = resolved,
                    externalId = externalId,
                )
            }

            secureDb.transaction {
                val retired = retireSupersededRows(prepared, complete, observedExternalIds)
                replaced = retired.first
                kept = retired.second
                prepared.forEach { row ->
                    val entry = row.entry
                    try {
                        val inserted = insertResolved(
                            climb = row.climb,
                            climbedAt = entry.climbedAt,
                            attempts = entry.attempts,
                            rating = null,
                            isSend = entry.isSend,
                            externalId = row.externalId,
                        )
                        if (inserted) {
                            if (entry.isSend) ascents++ else projects++
                        } else duplicates++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w(TAG, "MoonBoard screen entry failed", t)
                        notImported++
                        unresolved += "${entry.name} — ${entry.setter} @ ${entry.angle}°"
                    }
                }
            }
            MoonBoardCsvImportResult(
                importedAscents = ascents,
                importedProjects = projects,
                duplicates = duplicates,
                foundEntries = entries.size,
                notImported = notImported,
                snapshotOnly = snapshotOnly,
                replacedEntries = replaced,
                keptOrphans = kept,
                stagedEntries = staged,
                unresolvedEntries = trulyUnresolved,
                unresolvedLabels = unresolved.take(100),
            )
        }

    suspend fun import(csv: String): MoonBoardCsvImportResult = withContext(Dispatchers.IO) {
        finalizePendingIfCatalogueReady()
        val export = MoonBoardCsvParser.parse(csv).getOrElse {
            return@withContext MoonBoardCsvImportResult(error = it.message ?: "Invalid MoonBoard CSV")
        }
        val resolved = resolve(export.entries.map { it.problemId }.distinct())
        var ascents = 0
        var projects = 0
        var duplicates = 0
        var notImported = 0
        var staged = 0
        var trulyUnresolved = 0
        val unresolved = linkedSetOf<Long>()
        val occurrence = HashMap<String, Int>()
        val catalogueComplete = catalogueIsComplete()

        secureDb.transaction {
            export.entries.forEach { entry ->
                try {
                    val signature = listOf(
                        entry.problemId, entry.climbedAt, entry.tries,
                        entry.attempts, entry.rating ?: "",
                    ).joinToString(":")
                    val ordinal = occurrence.merge(signature, 1, Int::plus) ?: 1
                    val kind = if (entry.isSend) "ascent" else "bid"
                    val externalId = "moon-csv:$kind:${sha256("$signature:$ordinal").take(32)}"
                    val climb = resolved[entry.problemId] ?: run {
                        stageCsv(entry, externalId, catalogueComplete)
                        if (catalogueComplete) {
                            notImported++
                            trulyUnresolved++
                            unresolved += entry.problemId
                        } else {
                            staged++
                        }
                        return@forEach
                    }
                    val inserted = insertResolved(
                        climb, entry.climbedAt, entry.attempts, entry.rating, entry.isSend, externalId,
                    )
                    if (inserted) {
                        if (entry.isSend) ascents++ else projects++
                    } else duplicates++
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "MoonBoard CSV row ${entry.sourceRow} failed", t)
                    notImported++
                    unresolved += entry.problemId
                }
            }
        }
        MoonBoardCsvImportResult(
            importedAscents = ascents,
            importedProjects = projects,
            duplicates = duplicates,
            foundEntries = export.entries.size,
            notImported = notImported,
            stagedEntries = staged,
            unresolvedEntries = trulyUnresolved,
            unresolvedProblemIds = unresolved.take(100),
        )
    }

    private data class PreparedEntry(
        val entry: MoonBoardScreenEntry,
        val climb: Resolved,
        val externalId: String,
    )

    /**
     * Removes what an earlier import of the same training day wrote for the
     * same climbs and this reading no longer produces.
     *
     * Without this, editing an entry in Moon — a project that became a send, a
     * corrected attempt count — leaves the original row behind and the logbook
     * shows the climb twice, once in each state. The outcome is part of the
     * external id, so the new reading can never overwrite the old row on its
     * own, and a send even moves from `bids` to `ascents`.
     *
     * Deliberately narrow: only rows this importer wrote (`moon-screen:`
     * prefix), and only on this training day.
     *
     * A climb Moon no longer lists at all is a weaker signal than a climb whose
     * outcome changed, so it is retired only when both of the following hold:
     * the day was read to its end (`complete`), because a scan cut short half
     * way looks exactly like a day whose second half was deleted; and the row
     * carries no comment or quality rating, because those can only have been
     * added in CruxCoach and are not this importer's to throw away. Rows kept
     * for that reason are counted and reported rather than silently left.
     */
    private fun retireSupersededRows(
        prepared: List<PreparedEntry>,
        complete: Boolean,
        observedExternalIds: Set<String> = prepared.mapTo(HashSet()) { it.externalId },
    ): Pair<Int, Int> {
        if (prepared.isEmpty() && observedExternalIds.isEmpty()) return 0 to 0
        val wantedIds = observedExternalIds
        val wantedClimbs = prepared.mapTo(HashSet()) { it.climb.uuid }
        var removed = 0
        var kept = 0

        /**
         * A row this importer wrote for this day that the current reading does
         * not produce again. Retiring it is right when the day is accounted for
         * in full; keeping it is right when the row carries something a person
         * added in CruxCoach, or when the reading itself was incomplete.
         */
        fun shouldRetire(sameClimb: Boolean, untouched: Boolean): Boolean = when {
            // Same climb, different outcome: Moon replaced it, so do we.
            sameClimb -> true
            // Gone from Moon entirely — only safe once the day is fully read …
            !complete -> false
            // … and only while nobody has put their own notes on the row.
            else -> untouched
        }

        prepared.map { it.entry.climbedAt }.distinct().forEach { climbedAt ->
            secureDb.ascentsQueries.selectImportedAscentsForDate(climbedAt, SCREEN_PREFIX)
                .executeAsList()
                .forEach { row ->
                    val id = row.external_id ?: return@forEach
                    if (id in wantedIds) return@forEach
                    val untouched = row.comment.isNullOrBlank() && row.quality == null
                    if (shouldRetire(row.climb_uuid in wantedClimbs, untouched)) {
                        secureDb.ascentsQueries.deleteAscentByExternalId(id)
                        removed++
                    } else {
                        kept++
                    }
                }
            secureDb.bidsQueries.selectImportedBidsForDate(climbedAt, SCREEN_PREFIX)
                .executeAsList()
                .forEach { row ->
                    val id = row.external_id ?: return@forEach
                    if (id in wantedIds) return@forEach
                    val untouched = row.comment.isNullOrBlank()
                    if (shouldRetire(row.climb_uuid in wantedClimbs, untouched)) {
                        secureDb.bidsQueries.deleteBidByExternalId(id)
                        removed++
                    } else {
                        kept++
                    }
                }
        }
        if (removed > 0 || kept > 0) {
            Log.i(TAG, "retired $removed superseded MoonBoard row(s), kept $kept")
        }
        return removed to kept
    }

    private data class Resolved(
        val uuid: String,
        val layoutId: Long?,
        val name: String,
        val frames: String,
        val framesCount: Long,
        val angle: Long,
        val difficulty: Double?,
    )

    /** Candidates for just the names of one training day. */
    private fun lookupByNames(
        entries: List<MoonBoardScreenEntry>,
        angles: List<Long>,
    ): Map<String, List<ScreenIndexRow>> {
        val names = entries.map { it.name.lowercase(Locale.ROOT) }.distinct()
        val startedAt = SystemClock.elapsedRealtime()
        val rows = boardDb.boardQueries.lookupMoonScreenByNames(angles, names).executeAsList()
        Log.i(
            TAG,
            "catalogue lookup: ${names.size} name(s) -> ${rows.size} row(s) in " +
                "${SystemClock.elapsedRealtime() - startedAt} ms",
        )
        return rows.groupBy({ "${it.name.lowercase(Locale.ROOT)}|${it.angle}" }) {
            ScreenIndexRow(it.uuid, it.setter_username)
        }
    }

    /**
     * Name -> candidate(s) for one board angle. A name with a single candidate
     * stores that row directly instead of a one-element list: at six figures of
     * names the per-list object overhead alone is tens of megabytes.
     */
    private fun indexForAngle(angle: Long, onCatalogueScan: () -> Unit): Map<String, Any> =
        screenIndex.getOrPut(angle) {
        onCatalogueScan()
        val startedAt = SystemClock.elapsedRealtime()
        val index = HashMap<String, Any>()
        boardDb.boardQueries.listMoonScreenIndex(listOf(angle)).executeAsList().forEach { row ->
            val name = row.name.lowercase(Locale.ROOT)
            val entry = ScreenIndexRow(row.uuid, row.setter_username)
            when (val existing = index[name]) {
                null -> index[name] = entry
                is ScreenIndexRow -> index[name] = arrayListOf(existing, entry)
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    (existing as ArrayList<ScreenIndexRow>).add(entry)
                }
            }
        }
        Log.i(
            TAG,
            "catalogue index @${angle}°: ${index.size} names in ${SystemClock.elapsedRealtime() - startedAt} ms",
        )
        index
    }

    private fun screenKey(entry: MoonBoardScreenEntry): String =
        "${entry.name.lowercase(Locale.ROOT)}|${entry.setter.lowercase(Locale.ROOT)}|${entry.angle}"

    /**
     * Matches a whole scanned logbook against the local MoonBoard catalogue in
     * two bounded queries instead of one full table scan per entry.
     *
     * The ambiguity rule is unchanged: a unique setter match wins, a single
     * name/angle candidate is accepted, and anything else stays unresolved and
     * is imported as a logbook-only snapshot.
     */
    private fun resolveScreenEntries(
        entries: List<MoonBoardScreenEntry>,
        onCatalogueScan: () -> Unit,
    ): Map<String, Resolved> {
        val wanted = entries.associateBy(::screenKey)
        if (wanted.isEmpty()) return emptyMap()

        val angles = entries.map { it.angle.toLong() }.distinct()
        val targeted = if (screenIndex.keys.containsAll(angles) ||
            targetedLookups >= TARGETED_LOOKUP_LIMIT
        ) {
            null
        } else {
            targetedLookups++
            lookupByNames(entries, angles)
        }

        val chosen = LinkedHashMap<String, String>()
        wanted.forEach { (key, entry) ->
            val name = entry.name.lowercase(Locale.ROOT)
            val rows = if (targeted != null) {
                targeted["$name|${entry.angle}"].orEmpty()
            } else {
                when (val hit = indexForAngle(entry.angle.toLong(), onCatalogueScan)[name]) {
                    null -> emptyList()
                    is ScreenIndexRow -> listOf(hit)
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        hit as List<ScreenIndexRow>
                    }
                }
            }
            val exact = rows.filter { it.setter?.equals(entry.setter, ignoreCase = true) == true }
            val row = when {
                exact.size == 1 -> exact.single()
                exact.isEmpty() && rows.size == 1 -> rows.single()
                else -> null
            }
            if (row != null) chosen[key] = row.uuid
        }
        if (chosen.isEmpty()) return emptyMap()

        val full = chosen.values.distinct().chunked(300).flatMap { uuids ->
            boardDb.boardQueries.lookupMoonCsvCandidates(uuids).executeAsList()
        }.associateBy { "${it.uuid}|${it.angle}" }

        val resolved = LinkedHashMap<String, Resolved>()
        chosen.forEach { (key, uuid) ->
            val entry = wanted.getValue(key)
            val row = full["$uuid|${entry.angle.toLong()}"] ?: return@forEach
            resolved[key] = Resolved(
                uuid = row.uuid,
                layoutId = row.layout_id,
                name = row.name,
                frames = row.frames,
                framesCount = row.frames_count,
                angle = row.angle,
                difficulty = row.difficulty_average,
            )
        }
        return resolved
    }

    /**
     * Called after Moon catalogue Imported/AlreadyCurrent, and opportunistically
     * at every import start. The board marker makes readiness survive restart;
     * staged rows remain until an idempotent external-id insert has succeeded.
     */
    suspend fun finalizePendingIfCatalogueReady(catalogueReady: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            if (catalogueReady) {
                boardDb.boardQueries.upsertSyncState(CATALOGUE_READY_MARKER, "complete")
                // A preceding scan may have cached a partial/empty catalogue.
                // Never resolve newly staged rows against that stale view after
                // the catalogue sync has just completed.
                screenIndex.clear()
                targetedLookups = 0
            }
            if (!catalogueIsComplete()) return@withContext 0
            val pending = secureDb.moonImportStagingQueries.selectStagedMoonImports().executeAsList()
            if (pending.isEmpty()) return@withContext 0
            val csvIds = pending.mapNotNull { it.problem_id }.distinct()
            val csvResolved = resolve(csvIds)
            val screenEntries = pending.filter { it.source_type == "screen" }.mapNotNull { row ->
                val name = row.problem_name ?: return@mapNotNull null
                val setter = row.setter_name ?: return@mapNotNull null
                val angle = row.angle?.toInt() ?: return@mapNotNull null
                MoonBoardScreenEntry(name, setter, angle, row.climbed_at, "", row.attempts.toInt(), row.is_send != 0L)
            }
            val screenResolved = resolveScreenEntries(screenEntries) {}
            var finalized = 0
            secureDb.transaction {
                pending.forEach { row ->
                    val climb = if (row.source_type == "csv") {
                        row.problem_id?.let(csvResolved::get)
                    } else {
                        val key = "${row.problem_name.orEmpty().lowercase(Locale.ROOT)}|" +
                            "${row.setter_name.orEmpty().lowercase(Locale.ROOT)}|${row.angle}"
                        screenResolved[key]
                    }
                    if (climb == null) {
                        if (row.source_type == "screen") {
                            val name = row.problem_name
                            val setter = row.setter_name
                            val angle = row.angle?.toInt()
                            if (name != null && setter != null && angle != null) {
                                // Once completeness is confirmed, keep this
                                // visible as history while retaining its raw
                                // identity for a future catalogue update.
                                insertResolved(
                                    screenSnapshot(
                                        MoonBoardScreenEntry(
                                            name, setter, angle, row.climbed_at, "",
                                            row.attempts.toInt(), row.is_send != 0L,
                                        ),
                                    ),
                                    row.climbed_at,
                                    row.attempts.toInt(),
                                    row.rating?.toInt(),
                                    row.is_send != 0L,
                                    row.external_id,
                                )
                            }
                        }
                        secureDb.moonImportStagingQueries.markStagedMoonImportUnresolved(row.external_id)
                    } else {
                        insertResolved(
                            climb, row.climbed_at, row.attempts.toInt(), row.rating?.toInt(),
                            row.is_send != 0L, row.external_id,
                        )
                        reconcileExistingSnapshot(climb, row.is_send != 0L, row.external_id)
                        // INSERT OR IGNORE returning 0 means an earlier attempt
                        // already finalized this external id: deletion is still safe.
                        secureDb.moonImportStagingQueries.deleteStagedMoonImport(row.external_id)
                        finalized++
                    }
                }
            }
            finalized
        }

    private fun catalogueIsComplete(): Boolean =
        boardDb.boardQueries.getSyncState(CATALOGUE_READY_MARKER).executeAsOneOrNull() == "complete"

    private fun reconcileExistingSnapshot(climb: Resolved, isSend: Boolean, externalId: String) {
        if (isSend) {
            secureDb.ascentsQueries.resolveStagedMoonAscent(
                climb_uuid = climb.uuid, angle = climb.angle, climb_name = climb.name,
                difficulty_average = climb.difficulty, climb_frames = climb.frames,
                frames_count = climb.framesCount, layout_id = climb.layoutId,
                external_id = externalId,
            )
        } else {
            secureDb.bidsQueries.resolveStagedMoonBid(
                climb_uuid = climb.uuid, angle = climb.angle, climb_name = climb.name,
                difficulty_average = climb.difficulty, layout_id = climb.layoutId,
                external_id = externalId,
            )
        }
    }

    private fun stageCsv(entry: MoonBoardCsvEntry, externalId: String, complete: Boolean) {
        secureDb.moonImportStagingQueries.stageMoonImport(
            external_id = externalId, source_type = "csv", problem_id = entry.problemId,
            problem_name = null, setter_name = null, angle = null,
            climbed_at = entry.climbedAt, attempts = entry.attempts.toLong(),
            rating = entry.rating?.toLong(), is_send = if (entry.isSend) 1 else 0,
            resolution_state = if (complete) "unresolved" else "pending",
        )
    }

    private fun stageScreen(entry: MoonBoardScreenEntry, externalId: String, complete: Boolean) {
        secureDb.moonImportStagingQueries.stageMoonImport(
            external_id = externalId, source_type = "screen", problem_id = null,
            problem_name = entry.name, setter_name = entry.setter, angle = entry.angle.toLong(),
            climbed_at = entry.climbedAt, attempts = entry.attempts.toLong(), rating = null,
            is_send = if (entry.isSend) 1 else 0,
            resolution_state = if (complete) "unresolved" else "pending",
        )
    }

    private fun screenSnapshot(entry: MoonBoardScreenEntry): Resolved = Resolved(
        uuid = UUID.nameUUIDFromBytes(
            "moon-screen-climb:${entry.name}:${entry.setter}:${entry.angle}"
                .toByteArray(Charsets.UTF_8),
        ).toString(),
        layoutId = null,
        name = entry.name,
        frames = "",
        framesCount = 1,
        angle = entry.angle.toLong(),
        difficulty = null,
    )

    private fun insertResolved(
        climb: Resolved,
        climbedAt: String,
        attempts: Int,
        rating: Int?,
        isSend: Boolean,
        externalId: String,
    ): Boolean {
        if (isSend) {
            secureDb.ascentsQueries.insertMoonCsvAscent(
                uuid = UUID.randomUUID().toString(), climb_uuid = climb.uuid,
                angle = climb.angle, is_mirror = 0,
                attempt_id = if (attempts <= 1) 1 else 2,
                bid_count = attempts.toLong(), quality = rating?.toLong(), difficulty = null,
                is_benchmark = 0, comment = null, climbed_at = climbedAt, synced = 0,
                gym_uuid = null, wall_uuid = null, product_layout_uuid = null,
                climb_name = climb.name, difficulty_average = climb.difficulty,
                climb_frames = climb.frames, frames_count = climb.framesCount,
                external_id = externalId, layout_id = climb.layoutId,
            )
            return secureDb.ascentsQueries.lastAscentChangeCount().executeAsOne() > 0
        }
        secureDb.bidsQueries.insertMoonCsvBid(
            uuid = UUID.randomUUID().toString(), climb_uuid = climb.uuid,
            angle = climb.angle, is_mirror = 0, bid_count = attempts.toLong(),
            comment = null, climbed_at = climbedAt, synced = 0,
            gym_uuid = null, wall_uuid = null, product_layout_uuid = null,
            climb_name = climb.name, difficulty_average = climb.difficulty,
            external_id = externalId, layout_id = climb.layoutId,
        )
        return secureDb.bidsQueries.lastBidChangeCount().executeAsOne() > 0
    }

    private fun resolve(problemIds: List<Long>): Map<Long, Resolved> {
        val uuidToProblem = HashMap<String, Pair<Long, Int?>>()
        problemIds.forEach { id ->
            MoonBoardUuid.candidates(id).forEach { uuidToProblem[it.uuid] = id to it.encodedAngle }
        }
        val rows = uuidToProblem.keys.chunked(300).flatMap { candidates ->
            boardDb.boardQueries.lookupMoonCsvCandidates(candidates).executeAsList()
        }
        return rows.groupBy { uuidToProblem[it.uuid]!!.first }.mapValues { (_, matches) ->
            val best = matches.maxBy { row ->
                val encoded = uuidToProblem[row.uuid]?.second
                when {
                    encoded != null && encoded.toLong() == row.angle -> 3
                    encoded == null && row.angle == 40L -> 2
                    else -> 1
                }
            }
            Resolved(
                uuid = best.uuid,
                layoutId = best.layout_id,
                name = best.name,
                frames = best.frames,
                framesCount = best.frames_count,
                angle = best.angle,
                difficulty = best.difficulty_average,
            )
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "MoonBoardCsvImporter"
        const val SCREEN_PREFIX = "moon-screen:%"
        const val TARGETED_LOOKUP_LIMIT = 4
        const val CATALOGUE_READY_MARKER = "moonboard_catalogue_complete_v1"
    }
}
