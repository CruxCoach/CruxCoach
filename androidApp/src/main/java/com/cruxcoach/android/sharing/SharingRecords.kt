package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.ShareCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest

/** What one direction currently covers: categories and the cutoff for dated
 * categories (an ISO date; records before it are outside the scope). */
@Serializable data class SharingScope(
    val categories: Set<ShareCategory>,
    val since: String,
)

/**
 * One current record of the owner's own data. `revision` is a hash of what is
 * sent; `fields` follow a fixed per-category whitelist. [until] (epoch ms) is
 * set when the grant that lets the record out expires: the recipient's app
 * deletes it then, even without hearing from the owner again.
 */
@Serializable data class SharingRecord(
    val id: String,
    val category: ShareCategory,
    val revision: Long,
    val fields: Map<String, String>,
    val until: Long? = null,
)

internal object SharingRecords {
    const val MAX_RECORDS = 1000
    const val MAX_BYTES = 1_048_576
    const val MAX_RECORD_BYTES = 8192
    /** Categories this version can project and accept. */
    val supported: Set<ShareCategory> = setOf(ShareCategory.CLIMBING_PROFILE, ShareCategory.SENDS, ShareCategory.ATTEMPTS, ShareCategory.CLIMB_NOTES)
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = false }

    private val allowed = mapOf(
        ShareCategory.CLIMBING_PROFILE to setOf("name", "boulderGrade", "sportGrade", "climbingYears", "sessionsPerWeek", "equipment", "goals"),
        ShareCategory.SENDS to setOf("climbId", "name", "board", "angle", "attempts", "grade", "date"),
        ShareCategory.ATTEMPTS to setOf("climbId", "name", "board", "angle", "attempts", "date"),
        ShareCategory.CLIMB_NOTES to setOf("climbId", "note"),
    )

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    /** Content version of one record: the first 56 bits of SHA-256 over its id,
     * category, fields in key order and expiry. It changes exactly when what is
     * sent changes; equal content always gives the same value. The sender
     * compares it with what it sent; the receiver applies deltas in sequence
     * order and does not order by it. */
    fun revision(id: String, category: ShareCategory, fields: Map<String, String>, until: Long? = null): Long {
        val canonical = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("category", JsonPrimitive(category.name))
            put("fields", buildJsonObject { fields.toSortedMap().forEach { (key, value) -> put(key, JsonPrimitive(value)) } })
            until?.let { put("until", JsonPrimitive(it)) }
        }.toString()
        return hash(canonical).take(14).toLong(16)
    }

    /** The same record under another expiry, with its revision recomputed. */
    fun withUntil(record: SharingRecord, until: Long?): SharingRecord =
        if (record.until == until) record
        else record.copy(until = until, revision = revision(record.id, record.category, record.fields, until))

    /** Order-independent digest of a record set; both sides compute it. */
    fun digest(records: Collection<SharingRecord>): String = hash(json.encodeToString(records.sortedBy { it.id }))

    private fun validDate(value: String) = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value) &&
        java.time.LocalDate.parse(value).toString() == value

    fun valid(scope: SharingScope): Boolean = runCatching {
        supported.containsAll(scope.categories) && validDate(scope.since)
    }.getOrDefault(false)

    fun valid(record: SharingRecord, scope: SharingScope): Boolean = runCatching {
        record.revision >= 0 && record.category in scope.categories && record.id.length in 1..256 &&
            record.fields.keys == allowed[record.category] &&
            (record.until == null || record.until > 0) &&
            json.encodeToString(record).encodeToByteArray().size <= MAX_RECORD_BYTES &&
            when (record.category) {
                ShareCategory.CLIMBING_PROFILE -> record.id == "profile:active"
                ShareCategory.SENDS, ShareCategory.ATTEMPTS -> {
                    val date = record.fields.getValue("date")
                    record.id.startsWith(if (record.category == ShareCategory.SENDS) "ascent:" else "bid:") &&
                        validDate(date) && date >= scope.since
                }
                ShareCategory.CLIMB_NOTES -> record.id == "note:${record.fields["climbId"]}"
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
