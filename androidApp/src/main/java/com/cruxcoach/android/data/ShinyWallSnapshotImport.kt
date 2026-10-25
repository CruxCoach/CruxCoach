package com.cruxcoach.android.data

import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.domain.board.ShinyWallCatalogue
import com.cruxcoach.domain.board.ShinyWallVariant
import java.util.UUID

/**
 * Reading and planning the Shiny Wall catalogue snapshot
 * (`shinywall_snapshot_v1`, user_version 1: `meta`, `routes`, `route_holds`,
 * `route_stats`).
 *
 * The snapshot is the publisher's cleaned copy of the vendor catalogue: no
 * drafts, no user ids, no free text. One climb row is written per (route,
 * variant) the route has holds for, with a stable uuid so logbook and list
 * entries survive every re-import. Grades, votes and benchmark flags map onto
 * the shared scales through [ShinyWallCatalogue].
 */
internal object ShinyWallSnapshot {
    const val SCHEMA_VERSION = 1L
    val REQUIRED_TABLES = setOf("meta", "routes", "route_holds", "route_stats")

    data class Route(
        val id: String,
        val name: String,
        val grade: String?,
        val angle: Int?,
        val noMatching: Boolean,
        val onlyHands: Boolean,
        val updatedAt: String?,
    )

    data class Stat(
        val routeId: String,
        val angle: Int,
        val assessNum: Long?,
        val assessSent: Long?,
        val avgGrade: Double?,
        val avgPunct: Double?,
        val benchmark: Boolean,
    )

    data class Contents(
        val routes: List<Route>,
        /** routeId → variant code → (hold number, role) */
        val holds: Map<String, Map<String, List<Pair<Int, String>>>>,
        val stats: List<Stat>,
    )

    data class ClimbRow(
        val uuid: String,
        val layoutId: Long,
        val name: String,
        val frames: String,
        val createdAt: String?,
        val isNomatch: Boolean,
        val method: String?,
    )

    data class StatRow(
        val climbUuid: String,
        val angle: Int,
        val displayDifficulty: Double?,
        val difficultyAverage: Double?,
        val qualityAverage: Double?,
        val ascensionistCount: Long,
        val benchmarkDifficulty: Double?,
        val layoutId: Long,
    )

    data class Plan(val climbs: List<ClimbRow>, val stats: List<StatRow>, val skippedHoldLists: Int)

    /** Stable climb uuid for one route on one variant. */
    fun climbUuid(routeId: String, variant: ShinyWallVariant): String =
        UUID.nameUUIDFromBytes("shinywall:${variant.code}:$routeId".encodeToByteArray()).toString()

    /**
     * Turns snapshot contents into climb and stat rows.
     *
     * Stats: the route's own angle always gets a row with its published grade
     * (also when nobody voted yet). Other angles carry the mean grade vote.
     * Votes, sends and benchmark flags come from the vendor statistics.
     */
    fun plan(contents: Contents): Plan {
        val statsByRoute = contents.stats.groupBy { it.routeId }
        val climbs = mutableListOf<ClimbRow>()
        val stats = mutableListOf<StatRow>()
        var skipped = 0
        for (route in contents.routes.sortedBy { it.id }) {
            val perVariant = contents.holds[route.id].orEmpty()
            for ((code, holds) in perVariant.toSortedMap()) {
                val variant = ShinyWallVariant.fromCode(code)?.takeIf { it.supported }
                val frames = variant?.let { ShinyWallCatalogue.frames(holds, it) }
                if (variant == null || frames.isNullOrEmpty()) {
                    skipped++
                    continue
                }
                val uuid = climbUuid(route.id, variant)
                climbs += ClimbRow(
                    uuid = uuid,
                    layoutId = variant.layoutId,
                    name = route.name,
                    frames = frames,
                    createdAt = route.updatedAt,
                    isNomatch = route.noMatching,
                    method = if (route.onlyHands) "method_footless" else null,
                )
                val published = ShinyWallCatalogue.difficultyForGrade(route.grade)?.toDouble()
                val routeStats = statsByRoute[route.id].orEmpty().associateBy { it.angle }
                val angles = (routeStats.keys + listOfNotNull(route.angle))
                    .filter { it in ShinyWallVariant.ANGLES }
                    .toSortedSet()
                for (angle in angles) {
                    val stat = routeStats[angle]
                    val voted = stat?.takeIf { (it.assessNum ?: 0) > 0 }
                        ?.let { ShinyWallCatalogue.difficultyForAverageVote(it.avgGrade) }
                    val display = if (angle == route.angle) published ?: voted else voted
                    stats += StatRow(
                        climbUuid = uuid,
                        angle = angle,
                        displayDifficulty = display,
                        difficultyAverage = voted ?: display,
                        qualityAverage = ShinyWallCatalogue.qualityForAverageVote(stat?.avgPunct),
                        ascensionistCount = stat?.assessSent ?: 0L,
                        benchmarkDifficulty = if (stat?.benchmark == true) display else null,
                        layoutId = variant.layoutId,
                    )
                }
            }
        }
        return Plan(climbs, stats, skipped)
    }

    /** Reads an ATTACHed snapshot. Validation that must pass before any
     *  catalogue row is deleted happens here. */
    fun read(db: SQLiteDatabase, alias: String): Contents {
        val schema = db.rawQuery("PRAGMA $alias.user_version", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        require(schema == SCHEMA_VERSION) { "Unsupported Shiny Wall snapshot schema: $schema" }
        val present = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM $alias.sqlite_master WHERE type='table'", null).use { c ->
            while (c.moveToNext()) present += c.getString(0)
        }
        require(present.containsAll(REQUIRED_TABLES)) { "Shiny Wall snapshot is missing required tables" }

        val routes = mutableListOf<Route>()
        db.rawQuery(
            "SELECT id, name, grade, angle, no_matching, only_hands, updated_at FROM $alias.routes",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                routes += Route(
                    id = c.getString(0),
                    name = c.getString(1),
                    grade = if (c.isNull(2)) null else c.getString(2),
                    angle = if (c.isNull(3)) null else c.getInt(3),
                    noMatching = !c.isNull(4) && c.getInt(4) != 0,
                    onlyHands = !c.isNull(5) && c.getInt(5) != 0,
                    updatedAt = if (c.isNull(6)) null else c.getString(6),
                )
            }
        }
        val holds = mutableMapOf<String, MutableMap<String, MutableList<Pair<Int, String>>>>()
        db.rawQuery(
            "SELECT route_id, variant, hold_index, role FROM $alias.route_holds ORDER BY route_id, variant, hold_index",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                holds.getOrPut(c.getString(0)) { mutableMapOf() }
                    .getOrPut(c.getString(1)) { mutableListOf() }
                    .add(c.getInt(2) to c.getString(3))
            }
        }
        val hasBenchmark = db.rawQuery("PRAGMA $alias.table_info(route_stats)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == "benchmark") found = true
            found
        }
        val stats = mutableListOf<Stat>()
        db.rawQuery(
            "SELECT route_id, angle, assess_num, assess_sent, avg_grade, avg_punct, " +
                (if (hasBenchmark) "benchmark" else "NULL") + " FROM $alias.route_stats",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                stats += Stat(
                    routeId = c.getString(0),
                    angle = c.getInt(1),
                    assessNum = if (c.isNull(2)) null else c.getLong(2),
                    assessSent = if (c.isNull(3)) null else c.getLong(3),
                    avgGrade = if (c.isNull(4)) null else c.getDouble(4),
                    avgPunct = if (c.isNull(5)) null else c.getDouble(5),
                    benchmark = !c.isNull(6) && c.getInt(6) != 0,
                )
            }
        }
        return Contents(routes, holds, stats)
    }
}
