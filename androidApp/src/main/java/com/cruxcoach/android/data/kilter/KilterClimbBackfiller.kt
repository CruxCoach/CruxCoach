package com.cruxcoach.android.data.kilter

import android.util.Log
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardClimbParser

/**
 * Backfills the local board DB with the user's OWN Kilter climbs that the
 * curated mirror lacks — both the LOGGED climbs (new-world / PowerSync-only
 * rows a logbook ascent can reference yet fail to resolve, the "Climb nicht
 * gefunden" bug) and the AUTHORED climbs (climbs the user set in the
 * official app that never reached the mirror and may never have been
 * logged).
 *
 * Shared contract of both backfills:
 *  - Best-effort and NON-fatal: any fetch/parse/write failure is logged and
 *    swallowed so the surrounding log sync continues.
 *  - Only climbs the board DB does NOT already have are upserted — curated
 *    rows are never clobbered ([BoardRepository.climbExistsByUuid] gate).
 *  - Compliance: each backfill is a SINGLE GET of the user's own data with
 *    their own Bearer token; no bulk/all-climbs fetch, no loop-crawl, no
 *    separate schedule — it rides the existing logbook-sync triggers.
 *  - Author identity: every upserted row records the climb author's Kilter
 *    userUuid (`kilter_author_uuid`) when the endpoint carries one, so the
 *    own-climb publish gate can later compare it against the CONNECTED
 *    account's userUuid — authorship by identity, never by display name.
 */
internal class KilterClimbBackfiller(
    private val apiClient: KilterApiClient,
    private val boardRepository: BoardRepository,
) {
    private companion object {
        const val TAG = "KilterClimbBackfiller"
    }

    /**
     * Backfill the user's own LOGGED climbs from `GET /climbs/logged`. Runs
     * BEFORE the engine's `insertLogs` so the denormalization there picks up
     * the freshly-upserted name/frames and the detail screen can resolve the
     * climb.
     */
    suspend fun backfillLoggedClimbs() {
        val response = apiClient.fetchLoggedClimbs().getOrElse {
            Log.w(TAG, "Logged-climb backfill skipped (fetch failed): ${it.message}")
            return
        }
        if (response.climbs.isEmpty()) return

        // Only consider climbs the board DB is missing — never overwrite
        // curated rows. climbExistsByUuid is an indexed point-lookup.
        val missing = response.climbs.filter { climb ->
            climb.climbUuid.isNotBlank() && !boardRepository.climbExistsByUuid(climb.climbUuid)
        }
        if (missing.isEmpty()) return

        // Stats keyed by (uuid, angle) so each climb-stat row pairs with its
        // climb. The detail screen resolves a climb via the (uuid, angle)
        // LEFT JOIN, and insertLogs' denormalization needs the climb_stats
        // row to exist for the logged angle — so we upsert a stat at each
        // climb's own angle, falling back to the API stats list.
        val statsByKey = response.climbStats.associateBy { it.climbUuid to it.angle }

        var upserted = 0
        try {
            boardRepository.runInTransaction {
                for (climb in missing) {
                    upsertBackfilledClimb(climb)
                    // Stat row at the climb's own angle so the (uuid, angle)
                    // lookup resolves. Use the API stat if present; otherwise
                    // write a bare row carrying only the angle key.
                    val stat = statsByKey[climb.climbUuid to climb.angle]
                    boardRepository.upsertClimbStat(
                        climbUuid = climb.climbUuid,
                        angle = climb.angle.toLong(),
                        displayDifficulty = stat?.difficultyAverage,
                        difficultyAverage = stat?.difficultyAverage,
                        qualityAverage = stat?.qualityAverage,
                        ascensionistCount = stat?.ascentCount?.toLong(),
                        benchmarkDifficulty = null,
                        faUsername = stat?.faUsername,
                        faAt = stat?.faAt,
                        officialKilterDifficulty = stat?.currentDifficultyId?.toLong(),
                    )
                    upserted++
                }
            }
            if (upserted > 0) {
                Log.i(TAG, "Backfilled $upserted logged climb(s) missing from board DB")
            }
        } catch (e: Exception) {
            // Backfill is an enhancement, never a gate — keep the log sync alive.
            Log.w(TAG, "Logged-climb backfill failed mid-write — continuing log sync", e)
        }
    }

    /**
     * Backfill the user's own AUTHORED climbs from
     * `GET /climbs/climbdetails/user`. Same gap-filling as
     * [backfillLoggedClimbs], plus these rows are the authoritative source
     * of the author identity the own-climb publish gate needs.
     *
     * The endpoint has no stats array, so each upserted climb gets a bare
     * climb_stats row at its own setter angle (NULL difficulty/quality) —
     * enough for the (uuid, angle) detail lookup to resolve it there.
     */
    suspend fun backfillAuthoredClimbs() {
        val climbs = apiClient.fetchOwnAuthoredClimbs().getOrElse {
            Log.w(TAG, "Authored-climb backfill skipped (fetch failed): ${it.message}")
            return
        }
        if (climbs.isEmpty()) return

        val missing = climbs.filter { climb ->
            climb.climbUuid.isNotBlank() && !boardRepository.climbExistsByUuid(climb.climbUuid)
        }
        if (missing.isEmpty()) return

        var upserted = 0
        try {
            boardRepository.runInTransaction {
                for (climb in missing) {
                    upsertBackfilledClimb(climb)
                    // No stats on this endpoint — write a bare row at the
                    // climb's own (setter) angle so it resolves + renders
                    // there. NULL difficulty/quality = "ungraded".
                    boardRepository.upsertClimbStat(
                        climbUuid = climb.climbUuid,
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
                }
            }
            if (upserted > 0) {
                Log.i(TAG, "Backfilled $upserted authored climb(s) missing from board DB")
            }
        } catch (e: Exception) {
            // Backfill is an enhancement, never a gate — keep the sync alive.
            Log.w(TAG, "Authored-climb backfill failed mid-write — continuing sync", e)
        }
    }

    /**
     * Shared climb-row mapping for both backfills. Only ever called for
     * uuids [BoardRepository.climbExistsByUuid] reported missing, inside the
     * caller's transaction. Also records the climb author's Kilter userUuid
     * (`kilter_author_uuid`) when present — `userUuid` is the AUTHOR'S
     * identity on both endpoints (on `/climbs/climbdetails/user` it is by
     * definition the authenticated account).
     */
    private fun upsertBackfilledClimb(climb: KilterLoggedClimb) {
        val frames = climb.climbConcat
        val moveCount = if (frames.isNotBlank()) {
            BoardClimbParser.estimateMoveCount(BoardClimbParser.parseFrames(frames)).toLong()
        } else 0L
        // productLayoutUuid is a numeric string in Kilter's API
        // ("10", "27", …) — the same value the board DB stores as
        // layout_id. Unparseable → 0 (still resolvable by uuid).
        val layoutId = climb.productLayoutUuid.toLongOrNull() ?: 0L
        boardRepository.upsertClimb(
            uuid = climb.climbUuid,
            layoutId = layoutId,
            setter = climb.username.ifBlank { null },
            name = climb.name,
            frames = frames,
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
            boardRepository.setClimbKilterAuthorUuid(
                uuid = climb.climbUuid,
                authorUuid = climb.userUuid,
            )
        }
    }
}
