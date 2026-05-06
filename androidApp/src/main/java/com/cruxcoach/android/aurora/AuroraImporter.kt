package com.cruxcoach.android.aurora

import android.util.Log
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.KilterGradeMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main entry point for FEAT-005 Aurora-JSON-import.
 *
 * Pipeline (mirrors `boardsesh/packages/web/app/lib/data-sync/aurora/json-import.ts`,
 * Apache 2.0):
 *
 *   1. Parse JSON → [AuroraExportData] via [AuroraExportParser].
 *   2. Collect every distinct climb name from ascents/attempts/circuits.
 *   3. Resolve names → board-DB UUIDs in batches (≤500/IN-clause). Apply
 *      the boardsesh tiebreaker: public Kilter > user-local draft, then
 *      higher `total_ascensionist_count` wins on ties within a tier.
 *   4. Insert ascents idempotently keyed on
 *      `external_id = aurora-json:ascent:<sha256(climbUuid:angle:climbedAt)[..32]>`.
 *   5. Same for bids.
 *   6. Upsert circuits as `climb_lists` rows; replace-and-rewrite their
 *      `climb_list_entries` so re-imports re-resolve names that became
 *      resolvable since last run.
 *   7. (Deferred to 0.1.5) Import user-authored draft climbs from the
 *      `climbs[]` array — coordinate→placement_id lookup is non-trivial
 *      and not blocking for the ascents/bids/circuits data path.
 *
 * Each step runs inside its own SQLCipher transaction (per FEAT-005
 * §5.2 — one transaction per step, not one big one, so a 50k-row import
 * doesn't lock the secure DB for minutes).
 */
@Singleton
class AuroraImporter @Inject constructor(
    private val secureDb: SecureDatabase,
    private val boardDb: BoardDatabase,
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

        val names = collectDistinctNames(data)
        progress(AuroraImportProgress.ResolvingClimbNames(names.size))
        val resolution = resolveNames(names, ownPubkey)

        val ascents = importAscents(data.ascents, resolution, progress)
        val bids = importBids(data.attempts, resolution, progress)
        val circuits = importCircuits(data.circuits, resolution, progress)

        // climbs[] section deferred — see status note in §4.3 of the spec.
        val climbs = ImportCounts(skipped = data.climbs.size)

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

    private fun collectDistinctNames(data: AuroraExportData): Set<String> {
        val names = HashSet<String>()
        data.ascents.forEach { names.add(it.climb) }
        data.attempts.forEach { names.add(it.climb) }
        data.circuits.forEach { c -> c.climbs.forEach { names.add(it) } }
        return names
    }

    private fun resolveNames(names: Set<String>, ownPubkey: String): NameResolution {
        if (names.isEmpty()) return NameResolution(emptyMap(), emptySet())

        val resolved = HashMap<String, String>(names.size)
        // Per-name tiebreaker state — pick public over draft, then by
        // total_ascensionist_count.
        val sourceWeight = HashMap<String, Int>()  // 2 = public, 1 = draft
        val popularity = HashMap<String, Long>()

        names.chunked(NAME_BATCH_SIZE).forEach { batch ->
            val rows = boardDb.boardQueries
                .lookupClimbsByNamesForAuroraImport(batch, ownPubkey)
                .executeAsList()
            for (row in rows) {
                val weight = if (row.source == "kilter") 2 else 1
                val existingWeight = sourceWeight[row.name] ?: 0
                val existingPop = popularity[row.name] ?: -1L
                val take = when {
                    weight > existingWeight -> true
                    weight < existingWeight -> false
                    row.total_ascensionist_count > existingPop -> true
                    else -> false
                }
                if (take) {
                    resolved[row.name] = row.uuid
                    sourceWeight[row.name] = weight
                    popularity[row.name] = row.total_ascensionist_count
                }
            }
        }

        val unresolved = names - resolved.keys
        return NameResolution(resolved, unresolved)
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
        secureDb.transaction {
            ascents.forEachIndexed { idx, a ->
                if (idx % 50 == 0) {
                    progress(AuroraImportProgress.ImportingAscents(idx, ascents.size))
                }
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
        secureDb.transaction {
            attempts.forEachIndexed { idx, b ->
                if (idx % 50 == 0) {
                    progress(AuroraImportProgress.ImportingBids(idx, attempts.size))
                }
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
                // boardsesh handles this by leaving an existing entry alone
                // rather than wiping a previously-resolved import.
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
                Log.w(TAG, "Circuit '${circuit.name}' import failed", e)
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

        /** Inverse of [KilterGradeMapper.DIFFICULTY_TO_FONT]. Built lazily
         *  from the KilterGradeMapper public surface — the source map is
         *  private so we re-derive via the public lookup function. */
        private val FONT_TO_DIFFICULTY: Map<String, Int> = (10..33).associate { id ->
            KilterGradeMapper.difficultyToFont(id.toDouble()).lowercase() to id
        }
    }
}
