package com.cruxcoach.app.imports

import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.LocalClimbDraft
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.community.ClimbBounds
import com.cruxcoach.domain.community.FramesHash
import kotlinx.serialization.json.Json

/**
 * Port of Android `AuroraImporter` (FEAT-005): the file-based Aurora account
 * export. No network, no account — the user picks the JSON their board app
 * emailed them and the rows land locally.
 *
 * Pipeline, unchanged from Android:
 *
 *  1. Parse to [AuroraExportData].
 *  2. Resolve the names ascents/attempts/circuits reference against the local
 *     board DB: case-sensitive batch first, case-insensitive fallback after.
 *  3. Import `climbs[]` as local drafts, but only for names a public catalogue
 *     climb does not already cover.
 *  4. Insert ascents, keyed idempotently on
 *     `aurora-json:ascent:<sha256(climbUuid:angle:climbedAt)[..32]>`.
 *  5. The same for bids.
 *  6. Upsert circuits as `climb_lists`, rewriting their membership so a
 *     re-import picks up names that became resolvable since the last run.
 *
 * Each step runs in its own transaction, so a 50k-row import never holds the
 * personal database for minutes.
 */
class AuroraImporter(
    private val secureDb: SecureDatabase,
    private val boardDb: BoardDatabase,
    private val boardRepository: BoardRepository,
    private val hashing: Hashing,
    private val ownPubkey: () -> String,
    private val newUuid: () -> String = ImportUuids::random,
) {
    private val ids = AuroraExternalId(hashing)

    private class NameResolution(val byName: Map<String, String>, val unresolved: Set<String>)

    private class ResolverPick(val uuid: String, val weight: Int, val popularity: Long)

    private class Counts(var imported: Int = 0, var skipped: Int = 0, var rejected: Int = 0) {
        fun toEntity(code: String) = ImportEntityCount(code, imported, skipped, rejected)
    }

    fun import(
        json: String,
        progress: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): FileImportResult {
        val format = ImportCodes.FORMAT_AURORA_JSON
        if (json.isBlank()) return FileImportResult.failed(format, ImportCodes.EMPTY)
        if (json.length > MAX_INPUT_CHARS) return FileImportResult.failed(format, ImportCodes.TOO_LARGE)

        progress(ImportCodes.PHASE_PARSING, 0, 0)
        val data = try {
            JSON.decodeFromString(AuroraExportData.serializer(), json)
        } catch (e: Exception) {
            return FileImportResult.failed(format, ImportCodes.MALFORMED)
        }
        if (
            data.ascents.size > MAX_COLLECTION || data.attempts.size > MAX_COLLECTION ||
            data.circuits.size > MAX_COLLECTION || data.climbs.size > MAX_COLLECTION
        ) {
            return FileImportResult.failed(format, ImportCodes.TOO_MANY_ROWS)
        }

        val pubkey = try {
            ownPubkey()
        } catch (e: Exception) {
            ""
        }
        val rejections = ArrayList<ImportRejection>()

        // climbs[] are imported only after this, so a name that collides with
        // the public catalogue never becomes a phantom local draft.
        val referenced = collectReferencedNames(data)
        val initial = resolveNames(referenced, pubkey)

        val climbs = Counts()
        val newlyImported = importLocalClimbs(data, pubkey, initial, climbs, rejections, progress)
        val resolution = NameResolution(
            byName = initial.byName + newlyImported,
            unresolved = initial.unresolved - newlyImported.keys,
        )

        val ascents = importAscents(data, resolution, rejections, progress)
        val bids = importBids(data, resolution, rejections, progress)
        val lists = importCircuits(data, resolution, rejections, progress)
        progress(ImportCodes.PHASE_DONE, 0, 0)

        val all = listOf(ascents, bids, lists, climbs)
        return FileImportResult(
            formatCode = format,
            rowsSeen = data.ascents.size + data.attempts.size + data.circuits.size + data.climbs.size,
            imported = all.sumOf { it.imported },
            skippedDuplicates = all.sumOf { it.skipped },
            rejected = all.sumOf { it.rejected },
            rejections = rejections,
            unresolvedLabels = resolution.unresolved.sorted().take(UNRESOLVED_CAP),
            entities = listOf(
                ascents.toEntity(ImportCodes.ENTITY_ASCENTS),
                bids.toEntity(ImportCodes.ENTITY_BIDS),
                lists.toEntity(ImportCodes.ENTITY_LISTS),
                climbs.toEntity(ImportCodes.ENTITY_CLIMBS),
            ),
        )
    }

    // ── Name resolution ────────────────────────────────────────────────

    private fun collectReferencedNames(data: AuroraExportData): Set<String> {
        val names = LinkedHashSet<String>()
        data.ascents.forEach { names.add(it.climb) }
        data.attempts.forEach { names.add(it.climb) }
        data.circuits.forEach { circuit -> circuit.climbs.forEach { names.add(it) } }
        return names
    }

    private fun resolveNames(names: Set<String>, ownPubkey: String): NameResolution {
        if (names.isEmpty()) return NameResolution(emptyMap(), emptySet())
        val picks = HashMap<String, ResolverPick>(names.size)

        // Brand-scoped to Kilter: the shared climbs table also carries the
        // MoonBoard and Aurora catalogues, and without the gate a same-named
        // foreign-board climb could win the popularity tiebreaker.
        names.chunked(NAME_BATCH_SIZE).forEach { batch ->
            boardDb.boardQueries.lookupClimbsByNamesForAuroraImport(
                names = batch,
                boardBrand = EXPORT_BOARD_BRAND,
                ownPubkey = ownPubkey,
            ).executeAsList().forEach { row ->
                applyPick(picks, row.name, row.uuid, row.source, row.total_ascensionist_count)
            }
        }

        val stillUnresolved = names - picks.keys
        if (stillUnresolved.isNotEmpty()) {
            val lowerToOriginal = HashMap<String, String>(stillUnresolved.size)
            stillUnresolved.forEach { lowerToOriginal[it.lowercase()] = it }
            lowerToOriginal.keys.toList().chunked(NAME_BATCH_SIZE).forEach { batch ->
                boardDb.boardQueries.lookupClimbsByLowerNamesForAuroraImport(
                    lowerNames = batch,
                    boardBrand = EXPORT_BOARD_BRAND,
                    ownPubkey = ownPubkey,
                ).executeAsList().forEach { row ->
                    val original = lowerToOriginal[row.name.lowercase()] ?: return@forEach
                    applyPick(picks, original, row.uuid, row.source, row.total_ascensionist_count)
                }
            }
        }

        val resolved = HashMap<String, String>(picks.size)
        picks.forEach { (name, pick) -> resolved[name] = pick.uuid }
        return NameResolution(resolved, names - resolved.keys)
    }

    private fun applyPick(
        picks: HashMap<String, ResolverPick>,
        name: String,
        uuid: String,
        source: String?,
        popularity: Long,
    ) {
        val weight = if (source == "kilter") 2 else 1
        val existing = picks[name]
        val take = when {
            existing == null -> true
            weight > existing.weight -> true
            weight < existing.weight -> false
            else -> popularity > existing.popularity
        }
        if (take) picks[name] = ResolverPick(uuid, weight, popularity)
    }

    // ── climbs[] as local drafts ───────────────────────────────────────

    /**
     * Skip rules, in Android's order: unknown layout, empty holds, no hold
     * resolvable to a placement, and a name a public Kilter climb already
     * covers. An existing row with the same deterministic uuid is a skip, not
     * an overwrite — it may carry edits from the climb creator.
     */
    private fun importLocalClimbs(
        data: AuroraExportData,
        ownPubkey: String,
        existingResolution: NameResolution,
        counts: Counts,
        rejections: MutableList<ImportRejection>,
        progress: (String, Int, Int) -> Unit,
    ): Map<String, String> {
        val climbs = data.climbs
        if (climbs.isEmpty()) return emptyMap()
        val newUuids = HashMap<String, String>()

        val placementCoordIndex = HashMap<Long, Long>(4096)
        boardRepository.getAllPlacements().forEach { placement ->
            placementCoordIndex[coordKey(placement.x.toInt(), placement.y.toInt())] = placement.placementId
        }
        // Two equally-named climbs in one export must not collapse onto one uuid.
        val seenUuids = HashSet<String>()

        climbs.forEachIndexed { index, climb ->
            progress(ImportCodes.PHASE_CLIMBS, index + 1, climbs.size)
            try {
                if (climb.name.length > MAX_NAME_LEN) {
                    reject(counts, rejections, ImportCodes.ROW_NAME_TOO_LONG, index, "")
                    return@forEachIndexed
                }
                if (climb.holds.size > MAX_HOLDS) {
                    reject(counts, rejections, ImportCodes.ROW_TOO_MANY_HOLDS, index, climb.name)
                    return@forEachIndexed
                }
                val layoutId = resolveLayoutId(climb.layout)
                if (layoutId == null) {
                    reject(counts, rejections, ImportCodes.ROW_UNKNOWN_LAYOUT, index, climb.name)
                    return@forEachIndexed
                }
                if (climb.holds.isEmpty()) {
                    reject(counts, rejections, ImportCodes.ROW_NO_HOLDS, index, climb.name)
                    return@forEachIndexed
                }
                if (existingResolution.byName[climb.name] != null) {
                    counts.skipped++
                    return@forEachIndexed
                }

                val holds = ArrayList<BoardHold>(climb.holds.size)
                climb.holds.forEach { hold ->
                    val placementId = placementCoordIndex[coordKey(hold.x, hold.y)]
                    val role = roleStringToId(hold.role)
                    if (placementId != null && role != null) {
                        holds += BoardHold(placementId = placementId.toInt(), roleId = role)
                    }
                }
                if (holds.isEmpty()) {
                    // Off-scale coordinates mean a hand-crafted export or a
                    // layout whose placement data this device does not have.
                    reject(counts, rejections, ImportCodes.ROW_NO_HOLDS, index, climb.name)
                    return@forEachIndexed
                }

                val createdAtIso = AuroraTimestamp.normalize(climb.created_at) ?: ""
                val frames = BoardClimbParser.encodeFrames(holds)
                var draftUuid = AuroraExternalId.climbUuid(layoutId, climb.name, createdAtIso)
                if (!seenUuids.add(draftUuid)) draftUuid = newUuid()

                if (boardRepository.climbExistsByUuid(draftUuid)) {
                    counts.skipped++
                    return@forEachIndexed
                }

                boardRepository.insertLocalDraft(
                    draft = LocalClimbDraft(
                        uuid = draftUuid,
                        name = climb.name,
                        description = climb.description.orEmpty(),
                        framesText = frames,
                        framesHash = FramesHash.of(frames, layoutId),
                        createdAt = createdAtIso,
                        createdByPubkey = ownPubkey.takeIf { it.isNotEmpty() },
                        moveCount = holds.count { it.roleId in MOVE_ROLES }.toLong(),
                        setterUsername = data.user.username,
                    ),
                    layoutId = layoutId,
                    angle = DEFAULT_DRAFT_ANGLE,
                    setterGradeId = KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
                    bounds = ClimbBounds.fromCoords(climb.holds.map { it.x to it.y }),
                )
                counts.imported++
                newUuids[climb.name] = draftUuid
            } catch (e: Exception) {
                reject(counts, rejections, ImportCodes.ROW_WRITE_FAILED, index, climb.name)
            }
        }
        return newUuids
    }

    // ── Ascents / bids / circuits ──────────────────────────────────────

    private fun importAscents(
        data: AuroraExportData,
        resolution: NameResolution,
        rejections: MutableList<ImportRejection>,
        progress: (String, Int, Int) -> Unit,
    ): Counts {
        val counts = Counts()
        val ascents = data.ascents
        if (ascents.isEmpty()) return counts
        val queries = secureDb.ascentsQueries
        // Per-row catch inside the transaction: one bad row counts as rejected
        // instead of rolling back the whole batch.
        secureDb.transaction {
            ascents.forEachIndexed { index, ascent ->
                if (index % PROGRESS_STRIDE == 0) {
                    progress(ImportCodes.PHASE_ASCENTS, index, ascents.size)
                }
                try {
                    val climbUuid = resolution.byName[ascent.climb]
                    if (climbUuid == null) {
                        reject(counts, rejections, ImportCodes.ROW_UNKNOWN_CLIMB, index, ascent.climb)
                        return@forEachIndexed
                    }
                    val climbedAt = AuroraTimestamp.normalize(ascent.climbed_at)
                    if (climbedAt == null) {
                        reject(counts, rejections, ImportCodes.ROW_BAD_TIMESTAMP, index, ascent.climb)
                        return@forEachIndexed
                    }
                    queries.insertAuroraAscent(
                        uuid = newUuid(),
                        climb_uuid = climbUuid,
                        angle = ascent.angle.toLong(),
                        is_mirror = 0L,
                        attempt_id = if (ascent.count == 1) 1L else 2L,
                        bid_count = ascent.count.toLong(),
                        quality = ascent.stars.toLong(),
                        difficulty = parseGradeToDifficultyId(ascent.grade)?.toLong(),
                        is_benchmark = 0L,
                        comment = null,
                        climbed_at = climbedAt,
                        synced = 0L,
                        gym_uuid = null,
                        wall_uuid = null,
                        product_layout_uuid = null,
                        climb_name = ascent.climb,
                        difficulty_average = null,
                        climb_frames = "",
                        frames_count = 1L,
                        external_id = ids.ascent(climbUuid, ascent.angle, climbedAt),
                    )
                    if (queries.lastAscentChangeCount().executeAsOne() > 0) {
                        counts.imported++
                    } else {
                        counts.skipped++
                    }
                } catch (e: Exception) {
                    reject(counts, rejections, ImportCodes.ROW_WRITE_FAILED, index, ascent.climb)
                }
            }
        }
        progress(ImportCodes.PHASE_ASCENTS, ascents.size, ascents.size)
        return counts
    }

    private fun importBids(
        data: AuroraExportData,
        resolution: NameResolution,
        rejections: MutableList<ImportRejection>,
        progress: (String, Int, Int) -> Unit,
    ): Counts {
        val counts = Counts()
        val attempts = data.attempts
        if (attempts.isEmpty()) return counts
        val queries = secureDb.bidsQueries
        secureDb.transaction {
            attempts.forEachIndexed { index, attempt ->
                if (index % PROGRESS_STRIDE == 0) {
                    progress(ImportCodes.PHASE_BIDS, index, attempts.size)
                }
                try {
                    val climbUuid = resolution.byName[attempt.climb]
                    if (climbUuid == null) {
                        reject(counts, rejections, ImportCodes.ROW_UNKNOWN_CLIMB, index, attempt.climb)
                        return@forEachIndexed
                    }
                    val climbedAt = AuroraTimestamp.normalize(attempt.climbed_at)
                    if (climbedAt == null) {
                        reject(counts, rejections, ImportCodes.ROW_BAD_TIMESTAMP, index, attempt.climb)
                        return@forEachIndexed
                    }
                    queries.insertAuroraBid(
                        uuid = newUuid(),
                        climb_uuid = climbUuid,
                        angle = attempt.angle.toLong(),
                        is_mirror = 0L,
                        bid_count = attempt.count.toLong(),
                        comment = null,
                        climbed_at = climbedAt,
                        synced = 0L,
                        gym_uuid = null,
                        wall_uuid = null,
                        product_layout_uuid = null,
                        climb_name = attempt.climb,
                        difficulty_average = null,
                        external_id = ids.bid(climbUuid, attempt.angle, climbedAt),
                    )
                    if (queries.lastBidChangeCount().executeAsOne() > 0) {
                        counts.imported++
                    } else {
                        counts.skipped++
                    }
                } catch (e: Exception) {
                    reject(counts, rejections, ImportCodes.ROW_WRITE_FAILED, index, attempt.climb)
                }
            }
        }
        progress(ImportCodes.PHASE_BIDS, attempts.size, attempts.size)
        return counts
    }

    private fun importCircuits(
        data: AuroraExportData,
        resolution: NameResolution,
        rejections: MutableList<ImportRejection>,
        progress: (String, Int, Int) -> Unit,
    ): Counts {
        val counts = Counts()
        val circuits = data.circuits
        if (circuits.isEmpty()) return counts
        val queries = secureDb.climbListsQueries
        circuits.forEachIndexed { index, circuit ->
            progress(ImportCodes.PHASE_LISTS, index + 1, circuits.size)
            try {
                if (circuit.name.length > MAX_NAME_LEN) {
                    reject(counts, rejections, ImportCodes.ROW_NAME_TOO_LONG, index, "")
                    return@forEachIndexed
                }
                val createdAtIso = AuroraTimestamp.normalize(circuit.created_at)
                if (createdAtIso == null) {
                    reject(counts, rejections, ImportCodes.ROW_BAD_TIMESTAMP, index, circuit.name)
                    return@forEachIndexed
                }
                val external = ids.circuit(circuit.name, createdAtIso)
                val resolvedClimbs = circuit.climbs.mapNotNull { resolution.byName[it] }
                // A circuit whose every member is unknown is left alone rather
                // than wiping what a previous, better-resolved import wrote.
                if (resolvedClimbs.isEmpty() && circuit.climbs.isNotEmpty()) {
                    reject(counts, rejections, ImportCodes.ROW_UNKNOWN_CLIMB, index, circuit.name)
                    return@forEachIndexed
                }
                secureDb.transaction {
                    val existingId = queries.findClimbListByExternalId(external).executeAsOneOrNull()
                    val listId = if (existingId != null) {
                        queries.updateAuroraClimbListMeta(
                            name = circuit.name,
                            description = circuit.description,
                            color = circuit.color,
                            id = existingId,
                        )
                        queries.deleteClimbListEntries(existingId)
                        counts.skipped++
                        existingId
                    } else {
                        queries.insertAuroraClimbList(
                            name = circuit.name,
                            created_at = createdAtIso,
                            description = circuit.description,
                            color = circuit.color,
                            external_id = external,
                        )
                        counts.imported++
                        queries.getLastInsertedListId().executeAsOne()
                    }
                    resolvedClimbs.forEach { climbUuid ->
                        queries.insertClimbListEntry(
                            list_id = listId,
                            climb_uuid = climbUuid,
                            added_at = createdAtIso,
                        )
                    }
                    queries.prunePlaybackStepsOutsideMembership(listId)
                }
            } catch (e: Exception) {
                reject(counts, rejections, ImportCodes.ROW_WRITE_FAILED, index, circuit.name)
            }
        }
        return counts
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun reject(
        counts: Counts,
        rejections: MutableList<ImportRejection>,
        code: String,
        index: Int,
        label: String,
    ) {
        counts.rejected++
        if (rejections.size < FileImportResult.MAX_REPORTED_REJECTIONS) {
            rejections += ImportRejection(code, index, label.take(MAX_LABEL_LEN))
        }
    }

    /** Two 16-bit coordinates packed into one key; placements stay far inside that. */
    private fun coordKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun resolveLayoutId(layoutName: String): Long? {
        val canon = layoutName.trim()
        return KILTER_LAYOUT_NAMES[canon] ?: KILTER_LAYOUT_NAMES[canon.lowercase()]
    }

    private fun roleStringToId(role: String): Int? = when (role.lowercase()) {
        "start" -> HoldRole.START
        "middle", "hand" -> HoldRole.HAND
        "finish" -> HoldRole.FINISH
        "foot" -> HoldRole.FOOT
        else -> null
    }

    /**
     * Aurora's font-style grade text (`6A`, `6A/V3`, `6a+`) to a CruxCoach
     * difficulty id. Unrecognised input stores a null difficulty rather than
     * failing the row.
     */
    private fun parseGradeToDifficultyId(grade: String): Int? {
        val cleaned = grade.lowercase().substringBefore('/').trim()
        FONT_TO_DIFFICULTY[cleaned]?.let { return it }
        val asVScale = cleaned.uppercase()
        return KilterGradeMapper.vScaleToDifficulty(asVScale).takeIf { it != 11 || asVScale == "V0" }
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            // Aurora sometimes emits null where a non-nullable string belongs.
            coerceInputValues = true
            explicitNulls = false
        }

        const val NAME_BATCH_SIZE = 500
        const val PROGRESS_STRIDE = 50
        const val UNRESOLVED_CAP = 50
        const val MAX_LABEL_LEN = 200
        /** Aurora's own exports top out around a few MB even for power users. */
        const val MAX_INPUT_CHARS = 32 * 1024 * 1024
        const val MAX_COLLECTION = 50_000
        const val MAX_NAME_LEN = 200
        const val MAX_HOLDS = 1_000

        /** FEAT-005 imports are Kilter-app exports; name resolution is scoped to it. */
        const val EXPORT_BOARD_BRAND = "kilter"
        /** The export carries no authoring angle, so a draft gets the popular one. */
        const val DEFAULT_DRAFT_ANGLE = 40L

        val MOVE_ROLES = setOf(HoldRole.START, HoldRole.HAND, HoldRole.FINISH)

        val KILTER_LAYOUT_NAMES: Map<String, Long> = mapOf(
            "Kilter Board" to 1L,
            "Kilter Board Original" to 1L,
            "Kilter Board Homewall" to 8L,
            "kilter board" to 1L,
            "kilter board original" to 1L,
            "kilter board homewall" to 8L,
        )

        /** Inverse of the grade mapper's font table, re-derived from its public lookup. */
        val FONT_TO_DIFFICULTY: Map<String, Int> = (10..33).associate { id ->
            KilterGradeMapper.difficultyToFont(id.toDouble()).lowercase() to id
        }
    }
}
