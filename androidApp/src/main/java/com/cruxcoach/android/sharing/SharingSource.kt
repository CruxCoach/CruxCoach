package com.cruxcoach.android.sharing

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.ShareCategory
import kotlinx.serialization.json.*

/** The owner's current, bounded projection: the same code serves preview, full
 * snapshot and every delta. Never joins received rows or returns body or
 * injury data, log comments or media. Training records carry the day only,
 * never the time of day (no movement profile). A record's revision is a hash
 * of its sent content ([SharingRecords.revision]); nothing is stored for it. */
/** The owner's records for one scope. [skipped] are records that can never be
 * sent (longer than 8 KiB or malformed); they are left out one by one instead
 * of blocking everything. [truncated]: a source has more rows than one
 * generation may carry, so the set is incomplete and must not be sent. */
data class SharingProjection(val records: List<SharingRecord>, val skipped: List<String>, val truncated: Boolean)

class SharingSource(private val database: SecureDatabase) {
    /** Sendable records; refuses an incomplete or oversized set instead of truncating it. */
    fun read(scope: SharingScope): List<SharingRecord> = project(scope).let { projection ->
        require(!projection.truncated) { "over_limit" }
        projection.records.also { SharingRecords.check(it, scope) }
    }

    fun project(scope: SharingScope): SharingProjection = database.transactionWithResult {
        require(SharingRecords.valid(scope))
        val q = database.shareSourceQueries
        build(scope,
            ascents = { q.sourceAscents(scope.since).executeAsList().map { Ascent(it.uuid, it.climb_uuid, it.climb_name, it.board_brand, it.angle, it.bid_count, it.difficulty, it.climbed_at) } },
            bids = { q.sourceBids(scope.since).executeAsList().map { Bid(it.uuid, it.climb_uuid, it.climb_name, it.board_brand, it.angle, it.bid_count, it.climbed_at) } },
            notes = { q.sourceNotes().executeAsList().map { it.climb_uuid to it.note } },
        )
    }

    /** The owner's records about these climbs only, every period (the climb sheet asks
     * who sees them). Same fields and checks as [project]. */
    fun projectClimbs(climbUuids: Collection<String>): SharingProjection = database.transactionWithResult {
        val q = database.shareSourceQueries
        val ids = climbUuids.distinct()
        if (ids.isEmpty()) return@transactionWithResult SharingProjection(emptyList(), emptyList(), false)
        build(SharingScope(SharingRecords.supported, ShareScope.ALL_HISTORY),
            ascents = { q.sourceAscentsForClimbs(ids).executeAsList().map { Ascent(it.uuid, it.climb_uuid, it.climb_name, it.board_brand, it.angle, it.bid_count, it.difficulty, it.climbed_at) } },
            bids = { q.sourceBidsForClimbs(ids).executeAsList().map { Bid(it.uuid, it.climb_uuid, it.climb_name, it.board_brand, it.angle, it.bid_count, it.climbed_at) } },
            notes = { q.sourceNotesForClimbs(ids).executeAsList().map { it.climb_uuid to it.note } },
        )
    }

    private data class Ascent(val uuid: String, val climb: String, val name: String, val board: String, val angle: Long, val bids: Long?, val grade: Long?, val at: String)
    private data class Bid(val uuid: String, val climb: String, val name: String, val board: String, val angle: Long, val bids: Long?, val at: String)

    private fun build(
        scope: SharingScope,
        ascents: () -> List<Ascent>,
        bids: () -> List<Bid>,
        notes: () -> List<Pair<String, String>>,
    ): SharingProjection {
        var truncated = false
        fun <T> rows(read: () -> List<T>): List<T> = read().also { if (it.size > SharingRecords.MAX_RECORDS) truncated = true }
        val records = mutableListOf<SharingRecord>()
        fun add(id: String, category: ShareCategory, fields: Map<String, String>) {
            records += SharingRecord(id, category, SharingRecords.revision(id, category, fields), fields)
        }
        if (ShareCategory.CLIMBING_PROFILE in scope.categories) {
            database.userProfilesQueries.getActive().executeAsOneOrNull()?.let { row ->
                fun list(raw: String) = Json.parseToJsonElement(raw).jsonArray.joinToString("\n") { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }
                add("profile:active", ShareCategory.CLIMBING_PROFILE, linkedMapOf(
                    "name" to row.name, "boulderGrade" to row.max_boulder_grade, "sportGrade" to row.max_sport_grade.orEmpty(),
                    "climbingYears" to row.climbing_years.toString(), "sessionsPerWeek" to row.sessions_per_week.toString(),
                    "equipment" to list(row.available_equipment), "goals" to list(row.goals)))
            }
        }
        if (ShareCategory.SENDS in scope.categories) {
            rows(ascents).forEach { row ->
                add("ascent:${row.uuid}", ShareCategory.SENDS, linkedMapOf("climbId" to row.climb,
                    "name" to row.name, "board" to row.board, "angle" to row.angle.toString(),
                    "attempts" to (row.bids ?: 0).coerceAtLeast(1).toString(), "grade" to (row.grade?.toString() ?: ""),
                    "date" to row.at.take(10)))
            }
        }
        if (ShareCategory.ATTEMPTS in scope.categories) {
            rows(bids).forEach { row ->
                add("bid:${row.uuid}", ShareCategory.ATTEMPTS, linkedMapOf("climbId" to row.climb,
                    "name" to row.name, "board" to row.board, "angle" to row.angle.toString(),
                    "attempts" to (row.bids ?: 0).coerceAtLeast(1).toString(), "date" to row.at.take(10)))
            }
        }
        if (ShareCategory.CLIMB_NOTES in scope.categories) {
            rows(notes).forEach { (climb, note) ->
                add("note:$climb", ShareCategory.CLIMB_NOTES, linkedMapOf("climbId" to climb, "note" to note))
            }
        }
        val (sendable, skipped) = records.sortedBy { it.id }.partition { SharingRecords.valid(it, scope) }
        return SharingProjection(sendable, skipped.map { it.id }, truncated)
    }
}

/** SQLDelight notifies this listener after a write to a source table commits
 * (profiles, ascents, bids, notes); the next pass compares content. */
class SharingSourceChanges(database: SecureDatabase, onChange: () -> Unit) : AutoCloseable {
    private val query = database.shareSourceQueries.sourceTouched()
    private val listener = app.cash.sqldelight.Query.Listener(onChange)
    init { query.addListener(listener) }
    override fun close() { query.removeListener(listener) }
}
