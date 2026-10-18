package com.cruxcoach.app.kilter

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One climb of `GET /climbs/logged` or `GET /climbs/climbdetails/user`
 * (Android's `KilterLoggedClimb`, camelCase like the wire). On the authored
 * endpoint `userUuid`/`username` are by definition the signed-in account.
 */
@Serializable
internal class KilterLoggedClimb(
    val climbUuid: String = "",
    val climbConcat: String = "",
    val name: String = "",
    val angle: Int = 0,
    val description: String = "",
    val edgeLeft: Int? = null,
    val edgeRight: Int? = null,
    val edgeBottom: Int? = null,
    val edgeTop: Int? = null,
    val frameCount: Int = 1,
    val framesPace: Int = 0,
    val productLayoutUuid: String = "",
    val productName: String = "",
    val isListed: Boolean = true,
    val isDraft: Boolean = false,
    /** Kilter soft-deletes: `/climbs/logged` keeps sending deleted climbs. */
    val isDeleted: Boolean = false,
    val origin: String = "",
    val userUuid: String = "",
    val username: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

/** One stat row of `/climbs/logged`, keyed (climbUuid, angle) like `climb_stats`. */
@Serializable
internal class KilterLoggedClimbStat(
    val climbUuid: String = "",
    val angle: Int = 0,
    val difficultyAverage: Double? = null,
    val qualityAverage: Double? = null,
    val ascentCount: Int? = null,
    val currentDifficultyId: Int? = null,
    val faUsername: String? = null,
    val faAt: String? = null,
)

@Serializable
internal class KilterLoggedClimbsResponse(
    val climbs: List<KilterLoggedClimb> = emptyList(),
    val climbStats: List<KilterLoggedClimbStat> = emptyList(),
)

/**
 * Port of Android's `KilterClimbBackfiller`: puts the user's OWN Kilter climbs
 * the board DB lacks into it — the LOGGED ones (so a logbook entry resolves
 * instead of "climb not found") and the AUTHORED ones (which also record the
 * author's Kilter uuid, the identity the own-climb publish gate compares).
 *
 * Shared contract, as on Android: best effort and never fatal for the log
 * import it runs before; only climbs missing under ANY uuid spelling are
 * inserted, curated rows are never touched; each backfill is one GET of the
 * user's own data with their own token. Runs before the logs are written so
 * their names and frames denormalize from the fresh rows.
 *
 * Android defers the backfill while the Kilter catalogue is still loading
 * (before it every climb looks missing). iOS has no deferral queue, so
 * [catalogueReady] false skips the pass; the next import runs it.
 */
class KilterClimbBackfiller(
    private val api: KilterApi,
    private val boardRepository: BoardRepository,
    private val catalogueReady: () -> Boolean = { boardRepository.hasClimbsForBrand("kilter") },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Logged + authored backfill, as Android's `backfillOwnClimbs`; (logged upserted, own recognised). */
    suspend fun backfillOwnClimbs(): KilterBackfillCounts {
        if (!catalogueReady()) return KilterBackfillCounts(0, 0, deferred = true)
        return KilterBackfillCounts(backfillLoggedClimbs(), backfillAuthoredClimbs(), deferred = false)
    }

    /** `GET /climbs/logged`: climbs the user logged that the board DB is missing. */
    suspend fun backfillLoggedClimbs(): Int {
        val response = fetch("climbs/logged") { text ->
            json.decodeFromString(KilterLoggedClimbsResponse.serializer(), text)
        } ?: return 0
        if (response.climbs.isEmpty()) return 0
        val missing = response.climbs.filter { climb ->
            climb.climbUuid.isNotBlank() && !climb.isDeleted &&
                boardRepository.findClimbCanonicalUuid(climb.climbUuid) == null
        }
        if (missing.isEmpty()) return 0
        // Stats by climb, all angles: they come at the angle the climb was CLIMBED
        // at, which is not the setter's angle the climb record carries.
        val statsByClimb = response.climbStats.groupBy { it.climbUuid }
        var upserted = 0
        try {
            boardRepository.runInTransaction {
                for (climb in missing) {
                    upsertBackfilledClimb(climb)
                    val stats = statsByClimb[climb.climbUuid].orEmpty()
                    for (stat in stats) {
                        boardRepository.upsertClimbStat(
                            climbUuid = climb.climbUuid.lowercase(),
                            angle = stat.angle.toLong(),
                            displayDifficulty = stat.difficultyAverage,
                            difficultyAverage = stat.difficultyAverage,
                            qualityAverage = stat.qualityAverage,
                            ascensionistCount = stat.ascentCount?.toLong(),
                            benchmarkDifficulty = null,
                            faUsername = stat.faUsername,
                            faAt = stat.faAt,
                            officialKilterDifficulty = stat.currentDifficultyId?.toLong(),
                        )
                    }
                    // A row at the climb's own angle, or the (uuid, angle) lookup finds nothing.
                    if (stats.none { it.angle == climb.angle }) {
                        boardRepository.upsertClimbStat(
                            climbUuid = climb.climbUuid.lowercase(), angle = climb.angle.toLong(),
                            displayDifficulty = null, difficultyAverage = null,
                            qualityAverage = null, ascensionistCount = null,
                            benchmarkDifficulty = null,
                        )
                    }
                    upserted++
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An enhancement, never a gate: the transaction rolled back, the import goes on.
            return 0
        }
        return upserted
    }

    /**
     * `GET /climbs/climbdetails/user`: climbs the user set. Missing ones are
     * inserted with a bare stat row at the setter angle; existing ones (any uuid
     * spelling) get the author uuid recorded on their canonical row, fill-only.
     * Returns inserted + author-marked, the import summary's "own climbs".
     */
    suspend fun backfillAuthoredClimbs(): Int {
        val climbs = fetch("climbs/climbdetails/user") { text ->
            // Live shape is a bare array; tolerate an envelope like /climbs/logged.
            if (text.trimStart().startsWith("[")) {
                json.decodeFromString(ListSerializer(KilterLoggedClimb.serializer()), text)
            } else {
                json.decodeFromString(KilterLoggedClimbsResponse.serializer(), text).climbs
            }
        } ?: return 0
        val resolved = climbs
            .filter { it.climbUuid.isNotBlank() }
            .map { climb -> climb to boardRepository.findClimbCanonicalUuid(climb.climbUuid) }
        if (resolved.isEmpty()) return 0
        var upserted = 0
        var marked = 0
        try {
            boardRepository.runInTransaction {
                for ((climb, canonicalUuid) in resolved) {
                    if (canonicalUuid == null) {
                        upsertBackfilledClimb(climb)
                        boardRepository.upsertClimbStat(
                            climbUuid = climb.climbUuid.lowercase(),
                            angle = climb.angle.toLong(),
                            displayDifficulty = null,
                            difficultyAverage = null,
                            qualityAverage = null,
                            ascensionistCount = null,
                            benchmarkDifficulty = null,
                            faUsername = null,
                            faAt = null,
                            officialKilterDifficulty = null,
                        )
                        upserted++
                    } else if (climb.userUuid.isNotBlank()) {
                        boardRepository.setClimbKilterAuthorUuid(uuid = canonicalUuid, authorUuid = climb.userUuid)
                        marked++
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return 0
        }
        return upserted + marked
    }

    private suspend fun <T> fetch(path: String, decode: (String) -> T): T? {
        val call = api.climbCall("GET", path, null, KilterApi.MAX_LOGS_BYTES, KilterApi.UPLOAD_TIMEOUT_SECONDS)
        if (call.failure != KilterFailure.NONE || call.status !in 200..299) return null
        return try {
            decode(call.body)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The layout a backfilled climb belongs to. `productLayoutUuid` carries a
     * product SIZE (7, 8, 10, 14 …), so the size is the authority; the holes are
     * the fallback; Kilter's default layout the last resort.
     */
    private fun resolveLayoutId(climb: KilterLoggedClimb, frames: String): Long {
        climb.productLayoutUuid.toIntOrNull()
            ?.let { boardRepository.getLayoutForProductSize(it) }
            ?.let { return it }
        val holeIds = BoardClimbParser.parseFrames(frames).map { it.placementId }
        boardRepository.getLayoutForHoles(holeIds)?.let { return it }
        return boardRepository.getDefaultLayoutForBrand("kilter")?.toLong() ?: KILTER_ORIGINAL_LAYOUT_ID
    }

    /**
     * A `climbConcat` (hole ids, "h1174p12") rewritten into the placement-id
     * frames every renderer reads ("p…r12"). The two id spaces overlap
     * numerically, so a verbatim copy silently draws different holds. An
     * incomplete mapping keeps the input untouched rather than half-rewriting it.
     */
    private fun toPlacementFrames(frames: String, layoutId: Long, frameCount: Int): String {
        if (frames.isBlank() || !BoardClimbParser.isClimbConcat(frames)) return frames
        val holds = BoardClimbParser.parseClimbConcat(frames)
        if (holds.isEmpty()) return frames
        val resolved = holds.map { hold ->
            val placementId = boardRepository.getPlacementForHoleInLayout(hold.holeId, layoutId) ?: return frames
            placementId.toInt() to hold
        }
        val lastFrame = maxOf(
            frameCount.coerceAtLeast(1),
            holds.maxOf { maxOf(it.startFrame, it.endFrame ?: 1) },
        )
        if (lastFrame <= 1) {
            return BoardClimbParser.encodeFrames(resolved.map { (pid, hold) -> BoardHold(pid, hold.roleId) })
        }
        // Multi-frame climb: delta frames, comma-separated — each frame first the
        // holds leaving it (`x{pid}`), then the ones arriving.
        return (1..lastFrame).joinToString(",") { frame ->
            buildString {
                if (frame > 1) {
                    resolved.filter { (_, h) -> (h.endFrame ?: lastFrame) == frame - 1 }
                        .forEach { (pid, _) -> append("x").append(pid) }
                }
                resolved.filter { (_, h) -> h.startFrame == frame }
                    .forEach { (pid, h) -> append("p").append(pid).append("r").append(h.roleId) }
            }
        }
    }

    /** Only ever called for climbs missing under every spelling, inside the caller's transaction. */
    private fun upsertBackfilledClimb(climb: KilterLoggedClimb) {
        val frames = climb.climbConcat
        val moveCount = if (frames.isNotBlank()) {
            BoardClimbParser.estimateMoveCount(BoardClimbParser.parseFrames(frames)).toLong()
        } else {
            0L
        }
        val layoutId = resolveLayoutId(climb, frames)
        boardRepository.upsertClimb(
            // LOWER(uuid) is the canonical persisted form (the catalogue importer
            // lowercases too); a mixed-case row would be inserted a second time.
            uuid = climb.climbUuid.lowercase(),
            layoutId = layoutId,
            setter = climb.username.ifBlank { null },
            name = climb.name,
            frames = toPlacementFrames(frames, layoutId, climb.frameCount),
            framesCount = climb.frameCount.toLong().coerceAtLeast(1L),
            isListed = if (climb.isListed) 1L else 0L,
            edgeLeft = climb.edgeLeft?.toLong(),
            edgeRight = climb.edgeRight?.toLong(),
            edgeBottom = climb.edgeBottom?.toLong(),
            edgeTop = climb.edgeTop?.toLong(),
            createdAt = climb.createdAt.ifBlank { null },
            description = climb.description,
            framesPace = climb.framesPace.toLong(),
            moveCount = moveCount,
        )
        if (climb.userUuid.isNotBlank()) {
            boardRepository.setClimbKilterAuthorUuid(uuid = climb.climbUuid.lowercase(), authorUuid = climb.userUuid)
        }
    }

    private companion object {
        /** Android `BoardConstants.KILTER_ORIGINAL_LAYOUT`. */
        const val KILTER_ORIGINAL_LAYOUT_ID = 1L
    }
}

/** What [KilterClimbBackfiller.backfillOwnClimbs] did; [deferred] when the catalogue was not in yet. */
class KilterBackfillCounts(val loggedUpserted: Int, val ownRecognised: Int, val deferred: Boolean)
