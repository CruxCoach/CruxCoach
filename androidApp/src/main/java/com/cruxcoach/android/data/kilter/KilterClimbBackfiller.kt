package com.cruxcoach.android.data.kilter

import android.util.Log
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold

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
 *    rows are never clobbered. The exists gate is FORMAT-BLIND
 *    ([BoardRepository.findClimbCanonicalUuid]): the DB mixes uuid
 *    spellings (legacy nodash-UPPERCASE curated rows vs new-world
 *    dashed-lowercase API uuids), and an exact-match gate would re-insert
 *    an already-mirrored climb as a logical duplicate under the other
 *    spelling, splitting its identity (publish keeps the Kilter uuid, and
 *    cross-device reconciliation matches by uuid).
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
    suspend fun backfillLoggedClimbs(): Int {
        val response = apiClient.fetchLoggedClimbs().getOrElse {
            Log.w(TAG, "Logged-climb backfill skipped (fetch failed): ${it.message}")
            return 0
        }
        if (response.climbs.isEmpty()) return 0

        // Only consider climbs the board DB is missing — never overwrite
        // curated rows. Format-blind: a climb already mirrored under the
        // legacy nodash-UPPERCASE spelling counts as present.
        val missing = response.climbs.filter { climb ->
            climb.climbUuid.isNotBlank() &&
                boardRepository.findClimbCanonicalUuid(climb.climbUuid) == null
        }
        if (missing.isEmpty()) return 0

        // Every stat the endpoint sends for a climb, by uuid. It used to be
        // keyed by (uuid, climb.angle) and read at the climb's own angle —
        // but `climb.angle` is the SETTER's angle while the stats, like the
        // logbook, come at the angle the climb was CLIMBED at. Measured on a
        // real account: every stat arrived at 40° while the climb records
        // said 40°, 55° and 60°, so a third of them found nothing, wrote a
        // bare ungraded row at an angle nobody logged, and left the logged
        // angle without a stat — the entry resolved but showed no grade.
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
                    // A climb the endpoint sent no stats for still needs a row
                    // at its own angle, or the (uuid, angle) lookup has
                    // nothing to resolve. NULL difficulty = "ungraded".
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
            if (upserted > 0) {
                Log.i(TAG, "Backfilled $upserted logged climb(s) missing from board DB")
            }
        } catch (e: Exception) {
            // Backfill is an enhancement, never a gate — keep the log sync alive.
            Log.w(TAG, "Logged-climb backfill failed mid-write — continuing log sync", e)
        }
        return upserted
    }

    /**
     * Backfill the user's own AUTHORED climbs from
     * `GET /climbs/climbdetails/user`. Same gap-filling as
     * [backfillLoggedClimbs], plus this endpoint is the authoritative source
     * of the author identity the own-climb publish gate needs — so it
     * records `kilter_author_uuid` for EVERY fetched climb: on the freshly
     * inserted row for missing climbs, and on the canonical existing row
     * (curated mirror, any uuid spelling) for climbs the DB already has.
     *
     * The endpoint has no stats array, so each upserted climb gets a bare
     * climb_stats row at its own setter angle (NULL difficulty/quality) —
     * enough for the (uuid, angle) detail lookup to resolve it there.
     */
    suspend fun backfillAuthoredClimbs(): Int {
        val climbs = apiClient.fetchOwnAuthoredClimbs().getOrElse {
            Log.w(TAG, "Authored-climb backfill skipped (fetch failed): ${it.message}")
            return 0
        }
        if (climbs.isEmpty()) return 0

        // Resolve every fetched climb to its canonical stored uuid (null =
        // truly missing). Format-blind: the user's own LISTED climbs are
        // typically already in the curated mirror under the legacy
        // nodash-UPPERCASE spelling — those must NOT be re-inserted, but
        // they MUST still get the author identity recorded (this endpoint
        // just authoritatively attested the connected account authored
        // them — otherwise the publish gate stays closed for exactly the
        // most publish-worthy climbs).
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
                        // No stats on this endpoint — write a bare row at the
                        // climb's own (setter) angle so it resolves + renders
                        // there. NULL difficulty/quality = "ungraded".
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
                        // Existing row (curated or earlier backfill): record
                        // the author identity on the CANONICAL row. Fill-only
                        // SQL — never clobbers an already-recorded author.
                        boardRepository.setClimbKilterAuthorUuid(
                            uuid = canonicalUuid,
                            authorUuid = climb.userUuid,
                        )
                        marked++
                    }
                }
            }
            if (upserted > 0 || marked > 0) {
                Log.i(
                    TAG,
                    "Authored-climb backfill: $upserted upserted, " +
                        "$marked existing row(s) author-marked"
                )
            }
        } catch (e: Exception) {
            // Backfill is an enhancement, never a gate — keep the sync alive.
            Log.w(TAG, "Authored-climb backfill failed mid-write — continuing sync", e)
        }
        // upserted + marked = how many of the user's OWN authored climbs the
        // import recognized (newly inserted plus existing rows author-stamped),
        // surfaced in the import summary.
        return upserted + marked
    }

    /**
     * The layout a backfilled climb belongs to.
     *
     * `product_layout_uuid` was read as a layout id. It is a product SIZE.
     * Checked against the published catalogue on a real account: the values
     * that arrive are 7, 8, 10 and 14 — Kilter sizes — and every one of them
     * belongs to layout 1, Original, which is also what `productName` says.
     * Written as layouts they became 7, 10 and 14, which have no row in
     * `layouts` and no hold geometry at all, and 8, which is Homewall: a
     * different Kilter board. Every render lookup then missed and the detail
     * screen fell back to the user's own configured board.
     *
     * The size is the authority, because it is what the field actually
     * carries. The holes are the fallback — a hole carries a placement per
     * hold set and a set is published for one layout — and both are served by
     * the geometry bundled in the APK, so either answers before a download.
     */
    private fun resolveLayoutId(climb: KilterLoggedClimb, frames: String): Long {
        climb.productLayoutUuid.toIntOrNull()
            ?.let { boardRepository.getLayoutForProductSize(it) }
            ?.let { return it }
        val holeIds = BoardClimbParser.parseFrames(frames).map { it.placementId }
        boardRepository.getLayoutForHoles(holeIds)?.let { return it }
        return boardRepository.getDefaultLayoutForBrand("kilter")?.toLong()
            ?: BoardConstants.KILTER_ORIGINAL_LAYOUT.toLong()
    }

    /**
     * A Kilter `climbConcat` in the placement-id form every other climbs row
     * and every renderer uses.
     *
     * `climbConcat` names HOLES ("h1174p12"); the catalogue names PLACEMENTS
     * ("p1174r12"). The two id spaces overlap numerically — 80 of 85 sampled
     * hole ids also exist as placement ids — so storing the string verbatim
     * did not fail loudly, it silently drew a different hold. Verified on the
     * published catalogue: for every sampled climb the API's hole ids are
     * exactly the hole_ids of the catalogue frames' placements, and the
     * rewrite below reproduces those frames hold for hold and role for role.
     *
     * [BoardClimbParser.parseFrames] reports the leading number as
     * `placementId` for both formats, so for a climbConcat that field is the
     * hole id. An incomplete mapping returns the input untouched rather than
     * a half-rewritten frame that would render part of the climb in the wrong
     * place.
     */
    private fun toPlacementFrames(frames: String, layoutId: Long): String {
        if (frames.isBlank() || !BoardClimbParser.isClimbConcat(frames)) return frames
        val holds = BoardClimbParser.parseFrames(frames)
        if (holds.isEmpty()) return frames
        val converted = holds.map { hold ->
            val placementId = boardRepository
                .getPlacementForHoleInLayout(hold.placementId, layoutId) ?: return frames
            BoardHold(placementId.toInt(), hold.roleId)
        }
        return BoardClimbParser.encodeFrames(converted)
    }

    /**
     * Shared climb-row mapping for both backfills. Only ever called for
     * uuids [BoardRepository.findClimbCanonicalUuid] reported missing under
     * ANY spelling, inside the caller's transaction. Also records the climb author's Kilter userUuid
     * (`kilter_author_uuid`) when present — `userUuid` is the AUTHOR'S
     * identity on both endpoints (on `/climbs/climbdetails/user` it is by
     * definition the authenticated account).
     */
    private fun upsertBackfilledClimb(climb: KilterLoggedClimb) {
        val frames = climb.climbConcat
        val moveCount = if (frames.isNotBlank()) {
            BoardClimbParser.estimateMoveCount(BoardClimbParser.parseFrames(frames)).toLong()
        } else 0L
        val layoutId = resolveLayoutId(climb, frames)
        boardRepository.upsertClimb(
            // 7.sqm settled LOWER(uuid) as the canonical persisted form, and
            // the catalogue importer lowercases at its boundary for exactly
            // that reason. Writing the API spelling through made these the
            // only mixed-case rows in the board DB: the importer's exists-gate
            // missed them and inserted a SECOND row for the same climb, while
            // a logbook lookup tries the API spelling first and kept landing
            // on this one. Downloading the catalogue then repaired nothing.
            uuid = climb.climbUuid.lowercase(),
            layoutId = layoutId,
            setter = climb.username.ifBlank { null },
            name = climb.name,
            frames = toPlacementFrames(frames, layoutId),
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
                uuid = climb.climbUuid.lowercase(),
                authorUuid = climb.userUuid,
            )
        }
    }
}
