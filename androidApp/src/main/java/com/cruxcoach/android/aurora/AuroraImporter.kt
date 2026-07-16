package com.cruxcoach.android.aurora

import android.util.Log
import com.cruxcoach.android.nostr.NostrSigner
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main entry point for FEAT-005 Aurora-JSON-import.
 *
 * Pipeline:
 *
 *   1. Parse JSON → [AuroraExportData] via [AuroraExportParser].
 *   2. Resolve names from ascents/attempts/circuits → board-DB UUIDs:
 *      case-sensitive batch first (index-friendly), case-insensitive
 *      fallback for any residual.
 *   3. Import `climbs[]` as local drafts — only when the climb's name
 *      doesn't already resolve to a public Kilter UUID (avoids cloning
 *      catalog climbs the user happens to have logged).
 *   4. Insert ascents idempotently keyed on
 *      `external_id = aurora-json:ascent:<sha256(climbUuid:angle:climbedAt)[..32]>`.
 *   5. Same for bids.
 *   6. Upsert circuits as `climb_lists` rows; replace-and-rewrite their
 *      `climb_list_entries` so re-imports re-resolve names that became
 *      resolvable since last run.
 *
 * Each step runs inside its own SQLCipher transaction (per FEAT-005
 * §5.2 — one transaction per step, not one big one, so a 50k-row import
 * doesn't lock the secure DB for minutes).
 */
@Singleton
class AuroraImporter @Inject constructor(
    private val secureDb: SecureDatabase,
    private val boardDb: BoardDatabase,
    private val boardRepository: BoardRepository,
    private val nostrSigner: NostrSigner,
    private val parser: AuroraExportParser,
) {

    suspend fun import(
        json: String,
        progress: (AuroraImportProgress) -> Unit = {},
    ): AuroraImportResult = withContext(Dispatchers.IO) {
        progress(AuroraImportProgress.Parsing)
        val data = parser.parse(json).getOrElse {
            Log.w(TAG, "Aurora export parse failed", it)
            return@withContext AuroraImportResult.parseError(
                it.message ?: it.javaClass.simpleName,
            )
        }

        val ownPubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull().orEmpty()

        // Pass 1: resolve names referenced by ascents/attempts/circuits.
        // climbs[] are imported AFTER this so we can detect collisions
        // with the public catalog before inserting a phantom local draft.
        val refNames = collectReferencedNames(data)
        progress(AuroraImportProgress.ResolvingClimbNames(refNames.size))
        val initial = resolveNames(refNames, ownPubkey)

        val (climbs, newlyImported) = importLocalClimbs(
            climbs = data.climbs,
            username = data.user.username,
            ownPubkey = ownPubkey,
            existingResolution = initial,
            progress = progress,
        )

        // Merge in the newly-imported draft UUIDs so ascents/circuits
        // referencing them resolve. (Any climb name that was already
        // resolved to a public Kilter UUID stays that way — the importer
        // skipped the climbs[] insert in that case.)
        val resolution = NameResolution(
            byName = initial.byName + newlyImported,
            unresolved = initial.unresolved - newlyImported.keys,
        )

        val ascents = importAscents(data.ascents, resolution, progress)
        val bids = importBids(data.attempts, resolution, progress)
        val circuits = importCircuits(data.circuits, resolution, progress)

        progress(AuroraImportProgress.Done)

        AuroraImportResult(
            ascents = ascents,
            bids = bids,
            circuits = circuits,
            climbs = climbs,
            unresolvedClimbNames = resolution.unresolved
                .toList()
                .sorted()
                .take(AuroraImportResult.UNRESOLVED_CAP),
        )
    }

    // ── Name resolution ───────────────────────────────────────────────

    private data class NameResolution(
        val byName: Map<String, String>,
        val unresolved: Set<String>,
    )

    private data class ResolverPick(
        val uuid: String,
        val source: String,
        val weight: Int,         // 2 = public, 1 = draft
        val popularity: Long,
    )

    private fun collectReferencedNames(data: AuroraExportData): Set<String> {
        val names = HashSet<String>()
        data.ascents.forEach { names.add(it.climb) }
        data.attempts.forEach { names.add(it.climb) }
        data.circuits.forEach { c -> c.climbs.forEach { names.add(it) } }
        return names
    }

    private fun resolveNames(names: Set<String>, ownPubkey: String): NameResolution {
        if (names.isEmpty()) return NameResolution(emptyMap(), emptySet())

        val picks = HashMap<String, ResolverPick>(names.size)

        // Pass 1: case-sensitive (uses idx_climbs_name). Brand-scoped to
        // Kilter: the FEAT-005 export comes from the Kilter app, and the
        // shared climbs table also carries the MoonBoard/Aurora catalogues
        // (all source='kilter') — without the brand gate a same-named
        // foreign-board climb could win the popularity tiebreaker.
        names.chunked(NAME_BATCH_SIZE).forEach { batch ->
            val rows = boardDb.boardQueries
                .lookupClimbsByNamesForAuroraImport(
                    names = batch,
                    boardBrand = EXPORT_BOARD_BRAND,
                    ownPubkey = ownPubkey,
                )
                .executeAsList()
            for (row in rows) {
                applyPick(picks, row.name, row.uuid, row.source, row.total_ascensionist_count)
            }
        }

        // Pass 2: case-insensitive fallback for whatever didn't match.
        val stillUnresolved = names - picks.keys
        if (stillUnresolved.isNotEmpty()) {
            val lowerToOriginal = HashMap<String, String>(stillUnresolved.size)
            stillUnresolved.forEach { lowerToOriginal[it.lowercase()] = it }
            lowerToOriginal.keys.toList().chunked(NAME_BATCH_SIZE).forEach { batch ->
                val rows = boardDb.boardQueries
                    .lookupClimbsByLowerNamesForAuroraImport(
                        lowerNames = batch,
                        boardBrand = EXPORT_BOARD_BRAND,
                        ownPubkey = ownPubkey,
                    )
                    .executeAsList()
                for (row in rows) {
                    val original = lowerToOriginal[row.name.lowercase()] ?: continue
                    applyPick(picks, original, row.uuid, row.source, row.total_ascensionist_count)
                }
            }
        }

        val resolved = HashMap<String, String>(picks.size)
        picks.forEach { (name, pick) -> resolved[name] = pick.uuid }
        val unresolved = names - resolved.keys
        Log.i(
            TAG,
            "name-resolution: total=${names.size} resolved=${resolved.size} unresolved=${unresolved.size}",
        )
        return NameResolution(resolved, unresolved)
    }

    private fun applyPick(
        picks: HashMap<String, ResolverPick>,
        name: String,
        uuid: String,
        source: String,
        popularity: Long,
    ) {
        val weight = if (source == "kilter") 2 else 1
        val existing = picks[name]
        val take = when {
            existing == null -> true
            weight > existing.weight -> true
            weight < existing.weight -> false
            popularity > existing.popularity -> true
            else -> false
        }
        if (take) picks[name] = ResolverPick(uuid, source, weight, popularity)
    }

    // ── climbs[] (local drafts) ───────────────────────────────────────

    /**
     * Returns import counts plus a `name → newly-inserted-uuid` map for
     * the caller to fold into the main name-resolution table.
     *
     * Skip rules (in order):
     *   1. Layout name not recognised → `failed++`.
     *   2. Empty `holds` (no draft body) → `failed++`.
     *   3. None of the holds resolve to a placement_id (board.db doesn't
     *      know any of those (x,y) coords for the layout) → `failed++`.
     *   4. Name already resolves to a *public* Kilter UUID → `skipped++`.
     *      (We don't want to clone a catalog climb just because the user
     *      has it in their personal climbs[] list.)
     *   5. Otherwise: `INSERT OR IGNORE` via [BoardRepository.insertLocalDraft].
     *      Re-imports of the same export hit the IGNORE path and produce
     *      `skipped++` even though the deterministic UUID matches. We
     *      treat "row already exists" as a skip rather than re-running
     *      the UPDATE pass — the pre-existing row could carry user edits
     *      from the climb-creator that we mustn't clobber.
     */
    private fun importLocalClimbs(
        climbs: List<AuroraClimb>,
        username: String,
        ownPubkey: String,
        existingResolution: NameResolution,
        progress: (AuroraImportProgress) -> Unit,
    ): Pair<ImportCounts, Map<String, String>> {
        if (climbs.isEmpty()) return ImportCounts() to emptyMap()

        var imported = 0
        var skipped = 0
        var failed = 0
        val newUuids = HashMap<String, String>()

        // Pre-load placements once. ~3k rows on a Kilter catalog — fine.
        val placementCoordIndex = HashMap<Long, Long>(4096)  // (x*1_000_000 + y) → placementId
        for (p in boardRepository.getAllPlacements()) {
            placementCoordIndex[coordKey(p.x.toInt(), p.y.toInt())] = p.placementId
        }

        // Keys we already saw in this batch so two equally-named
        // climbs in the same export get distinct UUIDs (the deterministic
        // UUID input includes created_at, so realistically they collide
        // only on hand-crafted JSON — but cheap to be safe).
        val seenUuids = HashSet<String>()

        climbs.forEachIndexed { idx, climb ->
            progress(AuroraImportProgress.ImportingClimbs(idx + 1, climbs.size))
            try {
                val layoutId = resolveLayoutId(climb.layout)
                if (layoutId == null) {
                    Log.w(TAG, "climbs[$idx] — unknown layout")
                    failed++; return@forEachIndexed
                }
                if (climb.holds.isEmpty()) { failed++; return@forEachIndexed }

                // Skip if a public Kilter climb already covers this name.
                val existing = existingResolution.byName[climb.name]
                if (existing != null) {
                    skipped++; return@forEachIndexed
                }

                val holds = mutableListOf<BoardHold>()
                var unmappedCoords = 0
                var unknownRoles = 0
                for (h in climb.holds) {
                    val pid = placementCoordIndex[coordKey(h.x, h.y)]
                    if (pid == null) { unmappedCoords++; continue }
                    val role = roleStringToId(h.role) ?: run { unknownRoles++; null }
                    if (role != null) holds += BoardHold(placementId = pid.toInt(), roleId = role)
                }
                if (holds.isEmpty()) {
                    Log.w(
                        TAG,
                        "climbs[$idx] — no holds resolvable " +
                            "(in=${climb.holds.size}, unmappedCoords=$unmappedCoords, " +
                            "unknownRoles=$unknownRoles, layout=$layoutId). " +
                            "Aurora hold coords are expected on the same scale as " +
                            "placements.x/y (~ -50..170); off-scale coords usually " +
                            "mean the export was hand-crafted or for a layout we " +
                            "don't have placement data for.",
                    )
                    failed++; return@forEachIndexed
                }

                val createdAtIso = AuroraTimestamp.normalize(climb.created_at) ?: ""
                val frames = BoardClimbParser.encodeFrames(holds)
                val framesHash = FramesHash.of(frames, layoutId)
                val bounds = ClimbBounds.fromCoords(climb.holds.map { it.x to it.y })
                val moveCount = holds.count { it.roleId in MOVE_ROLES }.toLong()

                var draftUuid = AuroraExternalId.climbUuid(layoutId, climb.name, createdAtIso)
                // Hand-crafted JSON edge case: two climbs same (layout,
                // name, createdAt) → fall back to a random UUID for the
                // duplicate so both rows land. Real exports never collide.
                if (!seenUuids.add(draftUuid)) {
                    draftUuid = UUID.randomUUID().toString()
                }

                // Skip re-imports — the existing insertLocalDraft is INSERT
                // OR IGNORE + UPDATE, so calling it on an already-existing
                // uuid clobbers user edits made via the climb-creator UI.
                // We need "import once, ignore on re-runs" semantics here,
                // which is exactly what an existence check buys us.
                if (boardRepository.climbExistsByUuid(draftUuid)) {
                    skipped++; return@forEachIndexed
                }

                boardRepository.insertLocalDraft(
                    draft = LocalClimbDraft(
                        uuid = draftUuid,
                        name = climb.name,
                        description = climb.description.orEmpty(),
                        framesText = frames,
                        framesHash = framesHash,
                        createdAt = createdAtIso,
                        createdByPubkey = ownPubkey.takeIf { it.isNotEmpty() },
                        moveCount = moveCount,
                        setterUsername = username,
                    ),
                    layoutId = layoutId,
                    angle = DEFAULT_DRAFT_ANGLE,
                    setterGradeId = KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
                    bounds = bounds,
                )
                imported++
                newUuids[climb.name] = draftUuid
            } catch (e: Exception) {
                Log.w(TAG, "climbs[$idx] import failed (${e.javaClass.simpleName})")
                failed++
            }
        }

        return ImportCounts(imported, skipped, failed) to newUuids
    }

    private fun coordKey(x: Int, y: Int): Long {
        // Pack two 16-bit coords (placement coords are well under that)
        // into one Long key. Avoids the cost of a Pair<Int,Int> hash.
        return (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    }

    private fun resolveLayoutId(layoutName: String): Long? {
        val canon = layoutName.trim()
        return KILTER_LAYOUT_NAMES[canon]
            ?: KILTER_LAYOUT_NAMES[canon.lowercase()]
    }

    private fun roleStringToId(role: String): Int? = when (role.lowercase()) {
        "start" -> HoldRole.START
        "middle", "hand" -> HoldRole.HAND
        "finish" -> HoldRole.FINISH
        "foot" -> HoldRole.FOOT
        else -> null
    }

    // ── Ascents / Bids / Circuits ─────────────────────────────────────

    private fun importAscents(
        ascents: List<AuroraAscent>,
        resolution: NameResolution,
        progress: (AuroraImportProgress) -> Unit,
    ): ImportCounts {
        if (ascents.isEmpty()) return ImportCounts()
        var imported = 0
        var skipped = 0
        var failed = 0
        val q = secureDb.ascentsQueries
        // Per-row try/catch inside the transaction: a single bad row
        // (malformed grade, missing climb name, SQL constraint violation)
        // counts as `failed` instead of rolling back the entire batch.
        // CancellationException must rethrow so the transaction aborts
        // cleanly on coroutine cancellation.
        secureDb.transaction {
            ascents.forEachIndexed { idx, a ->
                if (idx % 50 == 0) {
                    progress(AuroraImportProgress.ImportingAscents(idx, ascents.size))
                }
                try {
                    val climbUuid = resolution.byName[a.climb] ?: run { failed++; return@forEachIndexed }
                    val climbedAt = AuroraTimestamp.normalize(a.climbed_at)
                        ?: run { failed++; return@forEachIndexed }
                    val external = AuroraExternalId.ascent(climbUuid, a.angle, climbedAt)
                    val attemptId = if (a.count == 1) 1L else 2L  // 1 = flash, 2 = redpoint
                    val difficulty = parseGradeToDifficultyId(a.grade)
                    val uuid = UUID.randomUUID().toString()
                    q.insertAuroraAscent(
                        uuid = uuid,
                        climb_uuid = climbUuid,
                        angle = a.angle.toLong(),
                        is_mirror = 0L,
                        attempt_id = attemptId,
                        bid_count = a.count.toLong(),
                        quality = a.stars.toLong(),
                        difficulty = difficulty?.toLong(),
                        is_benchmark = 0L,
                        comment = null,
                        climbed_at = climbedAt,
                        synced = 0L,
                        gym_uuid = null,
                        wall_uuid = null,
                        product_layout_uuid = null,
                        climb_name = a.climb,
                        difficulty_average = null,
                        climb_frames = "",
                        frames_count = 1L,
                        external_id = external,
                    )
                    if (q.lastAscentChangeCount().executeAsOne() > 0) imported++ else skipped++
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "ascents[$idx] insert failed (${t.javaClass.simpleName})")
                    failed++
                }
            }
        }
        progress(AuroraImportProgress.ImportingAscents(ascents.size, ascents.size))
        return ImportCounts(imported, skipped, failed)
    }

    private fun importBids(
        attempts: List<AuroraAttempt>,
        resolution: NameResolution,
        progress: (AuroraImportProgress) -> Unit,
    ): ImportCounts {
        if (attempts.isEmpty()) return ImportCounts()
        var imported = 0
        var skipped = 0
        var failed = 0
        val q = secureDb.bidsQueries
        // Per-row try/catch inside the transaction: see importAscents.
        secureDb.transaction {
            attempts.forEachIndexed { idx, b ->
                if (idx % 50 == 0) {
                    progress(AuroraImportProgress.ImportingBids(idx, attempts.size))
                }
                try {
                    val climbUuid = resolution.byName[b.climb] ?: run { failed++; return@forEachIndexed }
                    val climbedAt = AuroraTimestamp.normalize(b.climbed_at)
                        ?: run { failed++; return@forEachIndexed }
                    val external = AuroraExternalId.bid(climbUuid, b.angle, climbedAt)
                    val uuid = UUID.randomUUID().toString()
                    q.insertAuroraBid(
                        uuid = uuid,
                        climb_uuid = climbUuid,
                        angle = b.angle.toLong(),
                        is_mirror = 0L,
                        bid_count = b.count.toLong(),
                        comment = null,
                        climbed_at = climbedAt,
                        synced = 0L,
                        gym_uuid = null,
                        wall_uuid = null,
                        product_layout_uuid = null,
                        climb_name = b.climb,
                        difficulty_average = null,
                        external_id = external,
                    )
                    if (q.lastBidChangeCount().executeAsOne() > 0) imported++ else skipped++
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "bids[$idx] insert failed (${t.javaClass.simpleName})")
                    failed++
                }
            }
        }
        progress(AuroraImportProgress.ImportingBids(attempts.size, attempts.size))
        return ImportCounts(imported, skipped, failed)
    }

    private fun importCircuits(
        circuits: List<AuroraCircuit>,
        resolution: NameResolution,
        progress: (AuroraImportProgress) -> Unit,
    ): ImportCounts {
        if (circuits.isEmpty()) return ImportCounts()
        var imported = 0
        var skipped = 0
        var failed = 0
        val q = secureDb.climbListsQueries
        circuits.forEachIndexed { idx, circuit ->
            progress(AuroraImportProgress.ImportingCircuits(idx + 1, circuits.size))
            try {
                val createdAtIso = AuroraTimestamp.normalize(circuit.created_at)
                    ?: run { failed++; return@forEachIndexed }
                val external = AuroraExternalId.circuit(circuit.name, createdAtIso)
                val resolvedClimbs = circuit.climbs.mapNotNull { resolution.byName[it] }

                // Skip writing the circuit if every climb resolves to nothing —
                // leave an existing entry alone rather than wiping a
                // previously-resolved import.
                if (resolvedClimbs.isEmpty() && circuit.climbs.isNotEmpty()) {
                    failed++
                    return@forEachIndexed
                }

                secureDb.transaction {
                    val existingId = q.findClimbListByExternalId(external).executeAsOneOrNull()
                    val listId = if (existingId != null) {
                        q.updateAuroraClimbListMeta(
                            name = circuit.name,
                            description = circuit.description,
                            color = circuit.color,
                            id = existingId,
                        )
                        q.deleteClimbListEntries(existingId)
                        skipped++
                        existingId
                    } else {
                        q.insertAuroraClimbList(
                            name = circuit.name,
                            created_at = createdAtIso,
                            description = circuit.description,
                            color = circuit.color,
                            external_id = external,
                        )
                        imported++
                        q.getLastInsertedListId().executeAsOne()
                    }
                    for (climbUuid in resolvedClimbs) {
                        q.insertClimbListEntry(
                            list_id = listId,
                            climb_uuid = climbUuid,
                            added_at = createdAtIso,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "circuits[$idx] import failed (${e.javaClass.simpleName})")
                failed++
            }
        }
        return ImportCounts(imported, skipped, failed)
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Parse Aurora's font-style grade text (`"6A"`, `"6A/V3"`,
     *  `"6a+"`) to a CruxCoach difficulty ID using the existing
     *  KilterGradeMapper tables. Returns null on inputs we don't
     *  recognise — caller stores `difficulty = null` rather than
     *  failing the row. */
    private fun parseGradeToDifficultyId(grade: String): Int? {
        val cleaned = grade.lowercase().substringBefore('/').trim()
        FONT_TO_DIFFICULTY[cleaned]?.let { return it }
        // Fallback: try treating the input as already V-scale.
        val asVScale = cleaned.uppercase()
        return KilterGradeMapper.vScaleToDifficulty(asVScale).takeIf { it != 11 || asVScale == "V0" }
    }

    companion object {
        private const val TAG = "AuroraImporter"
        private const val NAME_BATCH_SIZE = 500
        /** The board whose official app produced the export. FEAT-005
         *  imports are Kilter-app JSON exports only; name resolution is
         *  scoped to this brand so ascents never attach to a same-named
         *  climb from another board's catalogue. */
        private const val EXPORT_BOARD_BRAND = "kilter"
        /** Default angle for an imported draft. The Aurora export
         *  format doesn't carry per-climb authoring angle (it's a
         *  property of the ascent / circuit, not the climb), so we
         *  pick the most popular setting for a community-mode draft.
         *  The user can re-angle the climb in the editor later. */
        private const val DEFAULT_DRAFT_ANGLE = 40L

        private val MOVE_ROLES = setOf(HoldRole.START, HoldRole.HAND, HoldRole.FINISH)

        /** Layout-name → layout_id table. 0.1.4 supports both Kilter
         *  layouts (Original = 1, Homewall = 8). Aurora-exported
         *  layout names empirically seen: "Kilter Board", "Kilter
         *  Board Original", "Kilter Board Homewall". The lower-case
         *  duplicates are tolerance for Aurora's older exports that
         *  occasionally lower-case the layout label. */
        private val KILTER_LAYOUT_NAMES: Map<String, Long> = mapOf(
            "Kilter Board" to 1L,
            "Kilter Board Original" to 1L,
            "Kilter Board Homewall" to 8L,
            "kilter board" to 1L,
            "kilter board original" to 1L,
            "kilter board homewall" to 8L,
        )

        /** Inverse of [KilterGradeMapper.DIFFICULTY_TO_FONT]. Built lazily
         *  from the KilterGradeMapper public surface — the source map is
         *  private so we re-derive via the public lookup function. */
        private val FONT_TO_DIFFICULTY: Map<String, Int> = (10..33).associate { id ->
            KilterGradeMapper.difficultyToFont(id.toDouble()).lowercase() to id
        }
    }
}
