package com.cruxcoach.android.sharing

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.SharingCategory
import kotlinx.serialization.json.*

/** The same bounded projection serves preview, first sync and every delta.
 * Never joins received rows or returns body/injury data, log comments or media. */
class ContinuousSourceAdapter(private val database: SecureDatabase) {
    fun version(): Pair<String, Long> = database.continuousSharingQueries.sourceVersion().executeAsOne().let { it.generation to it.revision }
    fun read(scope: ContinuousScope): List<ContinuousRecord> = database.transactionWithResult {
        require(ContinuousCodec.valid(scope))
        val records = mutableListOf<ContinuousRecord>()
        fun add(id: String, category: SharingCategory, fields: Map<String, String>, revision: Long? = null) { records += ContinuousRecord(id, category, revision ?: database.continuousSharingQueries.resourceVersion(id).executeAsOneOrNull() ?: 0, fields) }
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
            database.continuousSharingQueries.sourceAscents(scope.trainingSince).executeAsList().forEach { row ->
                add("ascent:${row.uuid}", SharingCategory.TRAINING_HISTORY, linkedMapOf("climbId" to row.climb_uuid,
                    "name" to row.climb_name, "board" to row.board_brand, "angle" to row.angle.toString(),
                    "attempts" to (row.bid_count ?: 0).coerceAtLeast(1).toString(), "grade" to (row.difficulty?.toString() ?: ""),
                    "date" to row.climbed_at, "outcome" to "SEND"), row.source_revision)
            }
            database.continuousSharingQueries.sourceBids(scope.trainingSince).executeAsList().forEach { row ->
                add("bid:${row.uuid}", SharingCategory.TRAINING_HISTORY, linkedMapOf("climbId" to row.climb_uuid,
                    "name" to row.climb_name, "board" to row.board_brand, "angle" to row.angle.toString(),
                    "attempts" to (row.bid_count ?: 0).coerceAtLeast(1).toString(), "grade" to "", "date" to row.climbed_at, "outcome" to "ATTEMPT"), row.source_revision)
            }
        }
        if (SharingCategory.PRIVATE_NOTES in scope.categories) {
            database.continuousSharingQueries.sourceNotes().executeAsList().forEach { row ->
                add("note:${row.climb_uuid}", SharingCategory.PRIVATE_NOTES, linkedMapOf("climbId" to row.climb_uuid, "note" to row.note), row.source_revision)
            }
        }
        records.sortedBy { it.id }.also { ContinuousCodec.checkRecords(it, scope) }
    }
}
