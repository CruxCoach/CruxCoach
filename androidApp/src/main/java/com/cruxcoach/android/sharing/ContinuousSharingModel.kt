package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.SharingCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** A new purpose/version, never an interpretation of snapshot consent. */
@Serializable data class ContinuousScope(
    val categories: Set<SharingCategory>,
    val trainingSince: String,
)
@Serializable data class ContinuousOffer(
    val version: Int = 2,
    val purpose: String = "cc.friendship.selection.v1",
    val id: String,
    val owner: String, val recipient: String,
    val ownerDevice: String, val recipientDevice: String,
    val ownerRole: SnapshotEndpointRole, val recipientRole: SnapshotEndpointRole,
    val sourceGeneration: String, val authorityGeneration: Long,
    val binding: String, val policyHead: String, val scope: ContinuousScope,
    val createdAt: Long, val expiresAt: Long,
    val friendship: String = id, val selection: Long = 1,
    val requestHash: String = "", val signature: String = "",

)
@Serializable internal data class ContinuousRequest(
    val purpose: String = "cc.friendship.request-intent.v1", val id: String,
    val account: String, val peer: String, val role: SnapshotEndpointRole,
    val scope: ContinuousScope, val source: String, val authority: Long,
    val createdAt: Long, val signature: String = "",
)
data class PendingFriendshipView(val id: String, val peer: String, val scope: ContinuousScope)
@Serializable data class ContinuousRecord(
    val id: String, val category: SharingCategory, val revision: Long, val fields: Map<String, String>,
)
@Serializable internal data class ContinuousChange(val id: String, val record: ContinuousRecord? = null)
@Serializable internal data class ContinuousTransfer(
    val version: Long, val base: Long, val full: Boolean,
    val sourceRevision: Long, val asOf: Long, val wireExpiry: Long,
    val categories: Set<SharingCategory>, val digest: String,
    val target: List<ContinuousRecord>, val pages: List<List<ContinuousChange>>,
)
@Serializable internal data class ContinuousWire(
    val version: Int = 2, val purpose: String = "cc.friendship.sync.v1",
    val action: String, val offer: ContinuousOffer, val expiresAt: Long,
    val counterOffer: ContinuousOffer? = null,
    val initialSelection: ContinuousOffer? = null,
    val end: ContinuousEnd? = null,
    val sequence: Long = 0, val base: Long = 0, val full: Boolean = false,
    val sourceRevision: Long = 0, val asOf: Long = 0,
    val categories: Set<SharingCategory> = emptySet(), val digest: String = "",
    val page: Int = 0, val pages: Int = 0, val changes: List<ContinuousChange> = emptyList(),
)
@Serializable internal data class ContinuousEnd(
    val purpose: String = "cc.friendship.end.v1", val friendship: String,
    val requester: String, val acceptor: String, val signer: String,
    val createdAt: Long, val signature: String = "",
)
@Serializable internal data class ContinuousState(
    val offer: ContinuousOffer, val status: String,
    val categories: Set<SharingCategory> = offer.scope.categories,
    val sequence: Long = 0, val acknowledged: Long = 0,
    val sourceRevision: Long = -1, val asOf: Long = 0, val confirmedAt: Long = 0,
    val records: List<ContinuousRecord> = emptyList(),
    val sending: ContinuousTransfer? = null,
    val receiving: Map<Int, ContinuousWire> = emptyMap(),
    val control: ContinuousWire? = null,
    val error: String? = null,
    val sentPages: Int = 0,
    val scheduledAt: Long = 0,
    val initialOffer: ContinuousOffer = offer,
    val cleanupConfirmed: Boolean = false,

)
data class ContinuousView(
    val offer: ContinuousOffer, val outgoing: Boolean, val status: String,
    val categories: Set<SharingCategory>, val asOf: Long, val confirmedAt: Long,
    val pending: Boolean, val records: List<ContinuousRecord>, val error: String?, val cleanupConfirmed: Boolean = false,
)

internal object ContinuousCodec {
    const val DAY = 86_400_000L
    const val MAX_GRANTS = 512
    const val MAX_ACTIVE = 16
    const val MAX_RECORDS = 1000
    const val MAX_BYTES = 1_048_576
    const val MAX_WIRE = 65_536
    val supported = setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.TRAINING_HISTORY, SharingCategory.PRIVATE_NOTES)
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val hex = Regex("[a-f0-9]{64}")
    private val id = Regex("[a-f0-9-]{32,36}")
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    fun digest(records: List<ContinuousRecord>): String = hash(json.encodeToString(records.sortedBy { it.id }))
    fun valid(offer: ContinuousOffer): Boolean = runCatching {
        offer.version == 2 && offer.purpose == "cc.friendship.selection.v1" && id.matches(offer.id) &&
            listOf(offer.owner, offer.recipient, offer.ownerDevice, offer.recipientDevice).all(hex::matches) &&
            offer.owner != offer.recipient && id.matches(offer.sourceGeneration) && offer.authorityGeneration > 0 &&
            offer.binding.length in 1..512 && id.matches(offer.policyHead) && offer.createdAt > 0 && offer.expiresAt > offer.createdAt &&
            offer.expiresAt == Long.MAX_VALUE && id.matches(offer.friendship) && offer.selection in 1 until Long.MAX_VALUE &&
            (offer.requestHash.isEmpty() || hex.matches(offer.requestHash)) && Regex("[a-f0-9]{128}").matches(offer.signature) && valid(offer.scope)
    }.getOrDefault(false)
    fun valid(scope: ContinuousScope): Boolean = runCatching {
        supported.containsAll(scope.categories) &&
            Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(scope.trainingSince) &&
            java.time.LocalDate.parse(scope.trainingSince).toString() == scope.trainingSince
    }.getOrDefault(false)
    fun valid(record: ContinuousRecord, scope: ContinuousScope): Boolean = runCatching {
        val allowed = when (record.category) {
            SharingCategory.PROFILE_AND_GOALS -> setOf("name", "boulderGrade", "sportGrade", "climbingYears", "sessionsPerWeek", "equipment", "goals")
            SharingCategory.TRAINING_HISTORY -> setOf("climbId", "name", "board", "angle", "attempts", "grade", "date", "outcome")
            SharingCategory.PRIVATE_NOTES -> setOf("climbId", "note")
            else -> emptySet()
        }
        record.revision >= 0 && record.category in scope.categories && record.id.length in 1..256 &&
            record.fields.keys == allowed && json.encodeToString(record).encodeToByteArray().size <= 8192 &&
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
    fun checkRecords(records: List<ContinuousRecord>, scope: ContinuousScope) {
        require(records.size <= MAX_RECORDS && records.map { it.id }.distinct().size == records.size)
        require(records.all { valid(it, scope) } && json.encodeToString(records).encodeToByteArray().size <= MAX_BYTES)
    }
    fun decode(raw: String): ContinuousWire? = if (raw.length > MAX_WIRE) null else runCatching {
        json.decodeFromString<ContinuousWire>(raw).takeIf { wire ->
            json.encodeToString(wire) == raw && raw.encodeToByteArray().size <= MAX_WIRE &&
                wire.version == 2 && wire.purpose == "cc.friendship.sync.v1" && valid(wire.offer) &&
                wire.action in setOf("OFFER", "ACCEPT", "SELECT", "DECLINE", "END", "END_ACK", "PAGE", "ACK", "RESYNC") &&
                wire.expiresAt <= wire.offer.expiresAt && wire.expiresAt > 0 && wire.sequence >= 0 && wire.base >= 0 &&
                wire.sourceRevision >= 0 && wire.asOf >= 0 && wire.offer.scope.categories.containsAll(wire.categories) &&
                wire.changes.size <= 16 && (wire.action != "PAGE" ||
                    wire.sequence > wire.base && wire.page in 0 until wire.pages && wire.pages in 1..128 &&
                    hex.matches(wire.digest) && wire.changes.all { change ->
                        change.id.length in 1..256 && (change.record == null || change.record.id == change.id && valid(change.record, wire.offer.scope))
                    })
        }
    }.getOrNull()
    fun pages(changes: List<ContinuousChange>): List<List<ContinuousChange>> {
        val pages = mutableListOf<List<ContinuousChange>>()
        var current = mutableListOf<ContinuousChange>()
        for (change in changes) {
            if (current.size == 16 || json.encodeToString(current + change).encodeToByteArray().size > 48_000) {
                pages += current; current = mutableListOf()
            }
            current += change
        }
        if (current.isNotEmpty() || pages.isEmpty()) pages += current
        require(pages.size <= 128)
        return pages
    }
}
