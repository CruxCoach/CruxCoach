package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.SharingCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** What one direction currently covers: categories and the training cutoff
 * (an ISO date; history before it is outside the scope). */
@Serializable data class SharingScope(
    val categories: Set<SharingCategory>,
    val trainingSince: String,
)

/** One current record of the owner's own data. `revision` is the global source
 * revision of its last change; `fields` follow a fixed per-category whitelist. */
@Serializable data class SharingRecord(
    val id: String,
    val category: SharingCategory,
    val revision: Long,
    val fields: Map<String, String>,
)

internal object SharingRecords {
    const val MAX_RECORDS = 1000
    const val MAX_BYTES = 1_048_576
    const val MAX_RECORD_BYTES = 8192
    val supported = setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.TRAINING_HISTORY, SharingCategory.PRIVATE_NOTES)
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    private val allowed = mapOf(
        SharingCategory.PROFILE_AND_GOALS to setOf("name", "boulderGrade", "sportGrade", "climbingYears", "sessionsPerWeek", "equipment", "goals"),
        SharingCategory.TRAINING_HISTORY to setOf("climbId", "name", "board", "angle", "attempts", "grade", "date", "outcome"),
        SharingCategory.PRIVATE_NOTES to setOf("climbId", "note"),
    )

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    /** Content version of one record: the first 56 bits of SHA-256 over its id,
     * category and fields in key order. It changes exactly when what is sent
     * changes; equal content always gives the same value. The sender compares it
     * with what it sent; the receiver applies deltas in sequence order and does
     * not order by it. */
    fun revision(id: String, category: SharingCategory, fields: Map<String, String>): Long {
        val canonical = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(id))
            put("category", kotlinx.serialization.json.JsonPrimitive(category.name))
            put("fields", kotlinx.serialization.json.buildJsonObject {
                fields.toSortedMap().forEach { (key, value) -> put(key, kotlinx.serialization.json.JsonPrimitive(value)) }
            })
        }.toString()
        return hash(canonical).take(14).toLong(16)
    }

    /** Order-independent digest of a record set; both sides compute it. */
    fun digest(records: Collection<SharingRecord>): String = hash(json.encodeToString(records.sortedBy { it.id }))

    fun valid(scope: SharingScope): Boolean = runCatching {
        supported.containsAll(scope.categories) &&
            Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(scope.trainingSince) &&
            java.time.LocalDate.parse(scope.trainingSince).toString() == scope.trainingSince
    }.getOrDefault(false)

    fun valid(record: SharingRecord, scope: SharingScope): Boolean = runCatching {
        record.revision >= 0 && record.category in scope.categories && record.id.length in 1..256 &&
            record.fields.keys == allowed[record.category] &&
            json.encodeToString(record).encodeToByteArray().size <= MAX_RECORD_BYTES &&
            when (record.category) {
                SharingCategory.PROFILE_AND_GOALS -> record.id == "profile:active"
                SharingCategory.TRAINING_HISTORY -> {
                    val date = record.fields.getValue("date")
                    (record.id.startsWith("ascent:") && record.fields["outcome"] == "SEND" ||
                        record.id.startsWith("bid:") && record.fields["outcome"] == "ATTEMPT") &&
                        date.length in 10..40 && date.take(10) >= scope.trainingSince &&
                        java.time.LocalDate.parse(date.take(10)).toString() == date.take(10)
                }
                SharingCategory.PRIVATE_NOTES -> record.id == "note:${record.fields["climbId"]}"
                else -> false
            }
    }.getOrDefault(false)

    /** One direction's whole set fits a generation (count and size). */
    fun withinLimits(records: List<SharingRecord>): Boolean =
        records.size <= MAX_RECORDS && json.encodeToString(records).encodeToByteArray().size <= MAX_BYTES

    fun check(records: List<SharingRecord>, scope: SharingScope) {
        require(withinLimits(records) && records.map { it.id }.distinct().size == records.size)
        require(records.all { valid(it, scope) })
    }
}
