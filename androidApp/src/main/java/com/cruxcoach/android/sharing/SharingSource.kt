package com.cruxcoach.android.sharing

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.SharingCategory
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
        var truncated = false
        fun <T : Any> rows(query: app.cash.sqldelight.Query<T>): List<T> =
            query.executeAsList().also { if (it.size > SharingRecords.MAX_RECORDS) truncated = true }
        val records = mutableListOf<SharingRecord>()
        fun add(id: String, category: SharingCategory, fields: Map<String, String>) {
            records += SharingRecord(id, category, SharingRecords.revision(id, category, fields), fields)
        }
        if (SharingCategory.PROFILE_AND_GOALS in scope.categories) {
            database.userProfilesQueries.getActive().executeAsOneOrNull()?.let { row ->
                fun list(raw: String) = Json.parseToJsonElement(raw).jsonArray.joinToString("\n") { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }
                add("profile:active", SharingCategory.PROFILE_AND_GOALS, linkedMapOf(
                    "name" to row.name, "boulderGrade" to row.max_boulder_grade, "sportGrade" to row.max_sport_grade.orEmpty(),
                    "climbingYears" to row.climbing_years.toString(), "sessionsPerWeek" to row.sessions_per_week.toString(),
                    "equipment" to list(row.available_equipment), "goals" to list(row.goals)))
            }
        }
        if (SharingCategory.TRAINING_HISTORY in scope.categories) {
            rows(q.sourceAscents(scope.trainingSince)).forEach { row ->
                add("ascent:${row.uuid}", SharingCategory.TRAINING_HISTORY, linkedMapOf("climbId" to row.climb_uuid,
                    "name" to row.climb_name, "board" to row.board_brand, "angle" to row.angle.toString(),
                    "attempts" to (row.bid_count ?: 0).coerceAtLeast(1).toString(), "grade" to (row.difficulty?.toString() ?: ""),
                    "date" to row.climbed_at.take(10), "outcome" to "SEND"))
            }
            rows(q.sourceBids(scope.trainingSince)).forEach { row ->
                add("bid:${row.uuid}", SharingCategory.TRAINING_HISTORY, linkedMapOf("climbId" to row.climb_uuid,
                    "name" to row.climb_name, "board" to row.board_brand, "angle" to row.angle.toString(),
                    "attempts" to (row.bid_count ?: 0).coerceAtLeast(1).toString(), "grade" to "", "date" to row.climbed_at.take(10), "outcome" to "ATTEMPT"))
            }
        }
        if (SharingCategory.PRIVATE_NOTES in scope.categories) {
            rows(q.sourceNotes()).forEach { row ->
                add("note:${row.climb_uuid}", SharingCategory.PRIVATE_NOTES, linkedMapOf("climbId" to row.climb_uuid, "note" to row.note))
            }
        }
        val (sendable, skipped) = records.sortedBy { it.id }.partition { SharingRecords.valid(it, scope) }
        SharingProjection(sendable, skipped.map { it.id }, truncated)
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
