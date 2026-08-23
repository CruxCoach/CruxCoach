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

    private data class ScreenIndexRow(val uuid: String, val setter: String?)

    /**
     * Opens an on-device scan and reports how many Moon entries each training
     * day already has in the logbook, so the scan can skip days that are
     * complete instead of re-reading them.
     */
    suspend fun beginScreenImport(): Map<String, MoonBoardImportedDay> = withContext(Dispatchers.IO) {
        screenIndex.clear()
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
    suspend fun importScreenSession(entries: List<MoonBoardScreenEntry>): MoonBoardCsvImportResult =
        withContext(Dispatchers.IO) {
            if (entries.isEmpty()) return@withContext MoonBoardCsvImportResult()
            var ascents = 0
            var projects = 0
            var duplicates = 0
            var notImported = 0
            var snapshotOnly = 0
            var replaced = 0
            val unresolved = linkedSetOf<String>()
            val occurrence = HashMap<String, Int>()

            // Resolve against the catalogue *before* opening the write
            // transaction. Catalogue lookups inside it held the secure database
            // for the entire import and froze every other screen with it.
            val catalogue = resolveScreenEntries(entries)

            val prepared = entries.map { entry ->
                val resolved = catalogue[screenKey(entry)]
                val climb = resolved ?: screenSnapshot(entry).also { snapshotOnly++ }
                val signature = listOf(
                    entry.name, entry.setter, entry.angle, entry.climbedAt,
                    entry.tries, entry.attempts,
                ).joinToString(":")
                val ordinal = occurrence.merge(signature, 1, Int::plus) ?: 1
                val kind = if (entry.isSend) "ascent" else "bid"
                PreparedEntry(
                    entry = entry,
                    climb = climb,
                    externalId = "moon-screen:$kind:${sha256("$signature:$ordinal").take(32)}",
                )
            }

            secureDb.transaction {
                replaced = retireSupersededRows(prepared)
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
                unresolvedLabels = unresolved.take(100),
            )
        }

    suspend fun import(csv: String): MoonBoardCsvImportResult = withContext(Dispatchers.IO) {
        val export = MoonBoardCsvParser.parse(csv).getOrElse {
            return@withContext MoonBoardCsvImportResult(error = it.message ?: "Invalid MoonBoard CSV")
        }
        val resolved = resolve(export.entries.map { it.problemId }.distinct())
        var ascents = 0
        var projects = 0
        var duplicates = 0
        var notImported = 0
        val unresolved = linkedSetOf<Long>()
        val occurrence = HashMap<String, Int>()

        secureDb.transaction {
            export.entries.forEach { entry ->
                val climb = resolved[entry.problemId] ?: run {
                    notImported++
                    unresolved += entry.problemId
                    return@forEach
                }
                try {
                    val signature = listOf(
                        entry.problemId, entry.climbedAt, entry.tries,
                        entry.attempts, entry.rating ?: "",
                    ).joinToString(":")
                    val ordinal = occurrence.merge(signature, 1, Int::plus) ?: 1
                    val kind = if (entry.isSend) "ascent" else "bid"
                    val externalId = "moon-csv:$kind:${sha256("$signature:$ordinal").take(32)}"
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
     * prefix), only on this training day, and only for climbs the current
     * reading actually covers. A climb Moon no longer lists is left alone and
     * surfaces as a reported deviation instead — deleting it is the user's call.
     */
    private fun retireSupersededRows(prepared: List<PreparedEntry>): Int {
        if (prepared.isEmpty()) return 0
        val wantedIds = prepared.mapTo(HashSet()) { it.externalId }
        val wantedClimbs = prepared.mapTo(HashSet()) { it.climb.uuid }
        var removed = 0
        prepared.map { it.entry.climbedAt }.distinct().forEach { climbedAt ->
            secureDb.ascentsQueries.selectImportedAscentsForDate(climbedAt, SCREEN_PREFIX)
                .executeAsList()
                .forEach { row ->
                    val id = row.external_id ?: return@forEach
                    if (row.climb_uuid !in wantedClimbs || id in wantedIds) return@forEach
                    secureDb.ascentsQueries.deleteAscentByExternalId(id)
                    removed++
                }
            secureDb.bidsQueries.selectImportedBidsForDate(climbedAt, SCREEN_PREFIX)
                .executeAsList()
                .forEach { row ->
                    val id = row.external_id ?: return@forEach
                    if (row.climb_uuid !in wantedClimbs || id in wantedIds) return@forEach
                    secureDb.bidsQueries.deleteBidByExternalId(id)
                    removed++
                }
        }
        if (removed > 0) Log.i(TAG, "retired $removed superseded MoonBoard row(s)")
        return removed
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

    /**
     * Name -> candidate(s) for one board angle. A name with a single candidate
     * stores that row directly instead of a one-element list: at six figures of
     * names the per-list object overhead alone is tens of megabytes.
     */
    private fun indexForAngle(angle: Long): Map<String, Any> = screenIndex.getOrPut(angle) {
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
    ): Map<String, Resolved> {
        val wanted = entries.associateBy(::screenKey)
        if (wanted.isEmpty()) return emptyMap()

        val chosen = LinkedHashMap<String, String>()
        wanted.forEach { (key, entry) ->
            val index = indexForAngle(entry.angle.toLong())
            val rows = when (val hit = index[entry.name.lowercase(Locale.ROOT)]) {
                null -> emptyList()
                is ScreenIndexRow -> listOf(hit)
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    hit as List<ScreenIndexRow>
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
     * A Moon log can be newer than the independently synced CruxCoach catalogue.
     * The secure logbook is deliberately denormalized, so preserve that account
     * history as a non-lightable snapshot instead of silently dropping it.
     */
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
    }
}
