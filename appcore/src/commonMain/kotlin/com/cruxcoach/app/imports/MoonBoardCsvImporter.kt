package com.cruxcoach.app.imports

import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.util.toHex
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.secure.SecureDatabase

/**
 * Port of the file half of Android `MoonBoardCsvImporter`.
 *
 * What it does, and why it is shaped this way:
 *
 * * A row whose climb the local MoonBoard catalogue does not know yet is not
 *   dropped and not guessed at — it is written to `moon_import_staging` with
 *   its external id, and picked up by [finalizePendingIfCatalogueReady] once
 *   the catalogue lands. A user can therefore import their logbook before they
 *   have downloaded a single board.
 * * Idempotency is the external id, byte-identical to Android's:
 *   `moon-csv:<ascent|bid>:<sha256(signature:ordinal)[..32]>`. The ordinal
 *   counts repeats of the same signature *within one file*, so a logbook that
 *   genuinely holds the same climb twice on one day keeps both rows while a
 *   re-import of the same file adds none. Both writes are `INSERT OR IGNORE`
 *   on a UNIQUE external id, so a re-import is a skip, never a duplicate.
 *
 * NOT ported: the on-device accessibility scan (`moon-screen:` rows,
 * `importScreenSession`, `retireSupersededRows`). Staged rows with
 * `source_type = 'screen'` are therefore left untouched here instead of being
 * resolved against a scheme this port does not implement.
 */
class MoonBoardCsvImporter(
    private val secureDb: SecureDatabase,
    private val boardDb: BoardDatabase,
    private val hashing: Hashing,
    private val newUuid: () -> String = ImportUuids::random,
) {
    private class Resolved(
        val uuid: String,
        val layoutId: Long?,
        val name: String,
        val frames: String,
        val framesCount: Long,
        val angle: Long,
        val difficulty: Double?,
    )

    /**
     * Imports one MoonBoard account export.
     *
     * [progress] is called with `(phase, done, total)` while rows are written;
     * it must not touch the database.
     */
    fun import(
        csv: String,
        progress: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): FileImportResult {
        val format = ImportCodes.FORMAT_MOONBOARD_CSV
        if (csv.isBlank()) return FileImportResult.failed(format, ImportCodes.EMPTY)
        finalizePendingIfCatalogueReady()
        val export = when (val parsed = MoonBoardCsvParser.parse(csv)) {
            is MoonBoardCsvParseResult.Failed ->
                return FileImportResult.failed(format, parsed.code, parsed.row)
            is MoonBoardCsvParseResult.Ok -> parsed.export
        }

        val resolved = resolve(export.entries.map { it.problemId }.distinct())
        var ascents = 0
        var projects = 0
        var duplicates = 0
        var rejected = 0
        var staged = 0
        val rejections = ArrayList<ImportRejection>()
        val unresolved = LinkedHashSet<String>()
        val occurrence = HashMap<String, Int>()
        val catalogueComplete = catalogueIsComplete()
        val total = export.entries.size

        secureDb.transaction {
            export.entries.forEachIndexed { index, entry ->
                progress(ImportCodes.PHASE_ROWS, index, total)
                val signature = listOf(
                    entry.problemId, entry.climbedAt, entry.tries,
                    entry.attempts, entry.rating ?: "",
                ).joinToString(":")
                // Android uses Map.merge; that is a JVM-only default method.
                val ordinal = (occurrence[signature] ?: 0) + 1
                occurrence[signature] = ordinal
                val kind = if (entry.isSend) "ascent" else "bid"
                val externalId = "moon-csv:$kind:${sha256(signature + ":" + ordinal)}"
                val climb = resolved[entry.problemId]
                if (climb == null) {
                    stageCsv(entry, externalId, catalogueComplete)
                    if (catalogueComplete) {
                        rejected++
                        unresolved += entry.problemId.toString()
                        if (rejections.size < FileImportResult.MAX_REPORTED_REJECTIONS) {
                            rejections += ImportRejection(
                                ImportCodes.ROW_UNKNOWN_CLIMB, entry.sourceRow, entry.problemId.toString(),
                            )
                        }
                    } else {
                        staged++
                    }
                    return@forEachIndexed
                }
                val inserted = insertResolved(
                    climb, entry.climbedAt, entry.attempts, entry.rating, entry.isSend, externalId,
                )
                if (inserted) {
                    if (entry.isSend) ascents++ else projects++
                } else {
                    duplicates++
                }
            }
        }
        progress(ImportCodes.PHASE_DONE, total, total)
        return FileImportResult(
            formatCode = format,
            rowsSeen = total,
            imported = ascents + projects,
            skippedDuplicates = duplicates,
            staged = staged,
            rejected = rejected,
            rejections = rejections,
            unresolvedLabels = unresolved.take(FileImportResult.MAX_REPORTED_LABELS),
        )
    }

    /**
     * Resolves what an earlier import parked. Called at every import start and
     * after a MoonBoard catalogue install; the board marker makes readiness
     * survive a restart. Returns how many staged rows became real logbook rows.
     */
    fun finalizePendingIfCatalogueReady(catalogueReady: Boolean = false): Int {
        if (catalogueReady) {
            boardDb.boardQueries.upsertSyncState(CATALOGUE_READY_MARKER, "complete")
        }
        if (!catalogueIsComplete()) return 0
        val pending = secureDb.moonImportStagingQueries.selectStagedMoonImports().executeAsList()
            .filter { it.source_type == "csv" }
        if (pending.isEmpty()) return 0
        val resolvedByProblem = resolve(pending.mapNotNull { it.problem_id }.distinct())
        var finalized = 0
        secureDb.transaction {
            pending.forEach { row ->
                val climb = row.problem_id?.let(resolvedByProblem::get)
                if (climb == null) {
                    secureDb.moonImportStagingQueries
                        .markStagedMoonImportState("unresolved", row.external_id)
                    return@forEach
                }
                insertResolved(
                    climb, row.climbed_at, row.attempts.toInt(), row.rating?.toInt(),
                    row.is_send != 0L, row.external_id,
                )
                reconcileExistingSnapshot(climb, row.is_send != 0L, row.external_id)
                // INSERT OR IGNORE reporting 0 means an earlier attempt already
                // finalized this external id; dropping the staged row stays safe.
                secureDb.moonImportStagingQueries.deleteStagedMoonImport(row.external_id)
                finalized++
            }
        }
        return finalized
    }

    /** Rows still waiting for a catalogue. Drives the "… pending" line on the screen. */
    fun stagedRowCount(): Long =
        secureDb.moonImportStagingQueries.countStagedMoonImports().executeAsOne()

    private fun catalogueIsComplete(): Boolean =
        boardDb.boardQueries.getSyncState(CATALOGUE_READY_MARKER).executeAsOneOrNull()
            ?.let { it == "complete" } ?: false

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
            rating = entry.rating?.toLong(), is_send = if (entry.isSend) 1L else 0L,
            resolution_state = if (complete) "unresolved" else "pending",
        )
    }

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
                uuid = newUuid(), climb_uuid = climb.uuid,
                angle = climb.angle, is_mirror = 0L,
                attempt_id = if (attempts <= 1) 1L else 2L,
                bid_count = attempts.toLong(), quality = rating?.toLong(), difficulty = null,
                is_benchmark = 0L, comment = null, climbed_at = climbedAt, synced = 0L,
                gym_uuid = null, wall_uuid = null, product_layout_uuid = null,
                climb_name = climb.name, difficulty_average = climb.difficulty,
                climb_frames = climb.frames, frames_count = climb.framesCount,
                external_id = externalId, layout_id = climb.layoutId,
            )
            return secureDb.ascentsQueries.lastAscentChangeCount().executeAsOne() > 0
        }
        secureDb.bidsQueries.insertMoonCsvBid(
            uuid = newUuid(), climb_uuid = climb.uuid,
            angle = climb.angle, is_mirror = 0L, bid_count = attempts.toLong(),
            comment = null, climbed_at = climbedAt, synced = 0L,
            gym_uuid = null, wall_uuid = null, product_layout_uuid = null,
            climb_name = climb.name, difficulty_average = climb.difficulty,
            external_id = externalId, layout_id = climb.layoutId,
        )
        return secureDb.bidsQueries.lastBidChangeCount().executeAsOne() > 0
    }

    private fun resolve(problemIds: List<Long>): Map<Long, Resolved> {
        if (problemIds.isEmpty()) return emptyMap()
        val uuidToProblem = HashMap<String, Pair<Long, Int?>>()
        problemIds.forEach { id ->
            MoonBoardUuid.candidates(id).forEach { uuidToProblem[it.uuid] = id to it.encodedAngle }
        }
        val rows = uuidToProblem.keys.chunked(CANDIDATE_BATCH).flatMap { candidates ->
            boardDb.boardQueries.lookupMoonCsvCandidates(candidates).executeAsList()
        }
        return rows.groupBy { uuidToProblem.getValue(it.uuid).first }.mapValues { (_, matches) ->
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

    private fun sha256(value: String): String =
        hashing.sha256(value.encodeToByteArray()).toHex().take(32)

    private companion object {
        const val CATALOGUE_READY_MARKER = "moonboard_catalogue_complete_v1"
        const val CANDIDATE_BATCH = 300
    }
}
