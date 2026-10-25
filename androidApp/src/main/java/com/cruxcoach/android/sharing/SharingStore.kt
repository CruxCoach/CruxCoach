package com.cruxcoach.android.sharing

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.CircleBaselines
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.ObjectRuleKey
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.PeerPolicy
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingPolicy
import com.cruxcoach.domain.sharing.SharingPolicyResolver
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneOffset

/** How far back training history reaches; [ALL] means no cutoff. */
object TrainingPeriod {
    const val ALL = -1
    val choices = listOf(30, 90, 365, ALL)
    const val DEFAULT = 30
}

data class SharingPreset(val circle: SharingCircle, val categories: Set<SharingCategory>, val trainingDays: Int)

enum class PersonState { REQUESTED, CONNECTED, ENDING, ENDED }

data class SharingPerson(
    val peer: String,
    val circle: SharingCircle,
    val outgoing: Boolean,
    val trainingDays: Int?,
    val label: String?,
    val state: PersonState,
    val cancelPending: Boolean,
    val createdAt: Long,
    val endedAt: Long?,
)

data class OutgoingState(
    val generation: Long,
    val sequence: Long,
    val generationStartedAt: Long,
    val scope: ShareScope,
    val sent: Map<String, Long>,
    val digest: String,
    val count: Int,
    val manifestAt: Long,
    val fullRequired: Boolean,
    val ackedGeneration: Long,
    val ackedSequence: Long,
    val ackedAt: Long,
)

data class IncomingState(
    val generation: Long,
    /** -1 while the generation's full state is still being staged. */
    val sequence: Long,
    val scope: ShareScope,
    val expectedDigest: String,
    val expectedCount: Int,
    val staging: Map<Int, List<SharingRecord>>,
    val pending: List<ShareMessage>,
    val resyncGeneration: Long,
    val resyncAt: Long,
    val ackDue: Boolean,
    val receivedAt: Long,
) {
    val complete get() = sequence >= 0

    companion object {
        val NONE = IncomingState(0, -1, ShareScope.EMPTY, "", 0, emptyMap(), emptyList(), 0, 0, false, 0)
    }
}

/** A record a friend shared, as stored outside every canonical table. */
data class ReceivedRecord(
    val peer: String,
    val record: SharingRecord,
    val climbUuid: String?,
    val occurredOn: String?,
)

/** SQL access for the local policy, both directions and received records. */
class SharingStore(private val database: SecureDatabase) {
    private val q get() = database.shareQueries
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val sentSerializer = MapSerializer(String.serializer(), Long.serializer())
    private val stagingSerializer = MapSerializer(Int.serializer(), ListSerializer(SharingRecord.serializer()))
    private val pendingSerializer = ListSerializer(ShareMessage.serializer())

    fun <T> transaction(block: () -> T): T = database.transactionWithResult { block() }

    // ---- policy ---------------------------------------------------------------

    fun presets(): Map<SharingCircle, SharingPreset> {
        val stored = q.selectPresets().executeAsList().associate { row ->
            val circle = SharingCircle.valueOf(row.circle)
            circle to SharingPreset(circle, categories(row.categories), row.training_days.toInt())
        }
        // Absent rows mean "never configured": nothing is shared.
        return SharingCircle.entries.associateWith { stored[it] ?: SharingPreset(it, emptySet(), TrainingPeriod.DEFAULT) }
    }

    fun putPreset(preset: SharingPreset) =
        q.upsertPreset(preset.circle.name, preset.categories.sorted().joinToString(",") { it.name }, preset.trainingDays.toLong())

    private fun categories(raw: String): Set<SharingCategory> =
        raw.split(',').filter { it.isNotEmpty() }.mapNotNull { name -> SharingCategory.entries.firstOrNull { it.name == name } }.toSet()

    fun persons(): List<SharingPerson> = q.selectPersons().executeAsList().map { row ->
        SharingPerson(row.peer, SharingCircle.valueOf(row.circle), row.outgoing == 1L, row.training_days?.toInt(),
            row.label, PersonState.valueOf(row.state), row.cancel_pending == 1L, row.created_at, row.ended_at)
    }

    fun person(peer: String): SharingPerson? = persons().firstOrNull { it.peer == peer }

    fun insertPerson(peer: String, circle: SharingCircle, outgoing: Boolean, label: String?, state: PersonState, now: Long) =
        q.insertPerson(peer, circle.name, if (outgoing) 1 else 0, null, label?.take(64), state.name, now)

    fun setCircle(peer: String, circle: SharingCircle) = q.updatePersonCircle(circle.name, peer)
    fun setOutgoing(peer: String, outgoing: Boolean) = q.updatePersonOutgoing(if (outgoing) 1 else 0, peer)
    fun setTrainingDays(peer: String, days: Int?) = q.updatePersonTrainingDays(days?.toLong(), peer)
    fun setLabel(peer: String, label: String?) = q.updatePersonLabel(label?.take(64), peer)
    fun setState(peer: String, state: PersonState, now: Long) =
        q.updatePersonState(state.name, if (state == PersonState.ENDED || state == PersonState.ENDING) now else null, peer)
    fun setCancelPending(peer: String, pending: Boolean) = q.setCancelPending(if (pending) 1 else 0, peer)
    fun deletePerson(peer: String) = q.deletePerson(peer)

    fun setPersonRule(peer: String, category: SharingCategory, effect: AccessEffect?) =
        if (effect == null) q.deletePersonRule(peer, category.name) else q.upsertPersonRule(peer, category.name, effect.name)

    fun setObjectRule(peer: String, recordId: String, category: SharingCategory, effect: AccessEffect?) =
        if (effect == null) q.deleteObjectRule(peer, recordId) else q.upsertObjectRule(peer, recordId, category.name, effect.name)

    /** The policy the FEAT-062 resolver evaluates; ended persons are absent. */
    fun policy(): SharingPolicy {
        val persons = persons().filter { it.state != PersonState.ENDED && it.state != PersonState.ENDING }
        val personRules = q.selectAllPersonRules().executeAsList().groupBy { it.peer }
        val objectRules = q.selectAllObjectRules().executeAsList().groupBy { it.peer }
        return SharingPolicy(
            baselines = CircleBaselines(presets().mapValues { it.value.categories }),
            peers = persons.associate { person ->
                PeerId(person.peer) to PeerPolicy(
                    circle = person.circle,
                    categoryRules = personRules[person.peer].orEmpty().associate { SharingCategory.valueOf(it.category) to AccessEffect.valueOf(it.effect) },
                    objectRules = objectRules[person.peer].orEmpty().associate {
                        ObjectRuleKey(ObjectId(it.record_id), SharingCategory.valueOf(it.category)) to AccessEffect.valueOf(it.effect)
                    },
                )
            },
        )
    }

    fun trainingDays(person: SharingPerson): Int = person.trainingDays ?: presets().getValue(person.circle).trainingDays

    /**
     * What goes to [person] today: nothing when the switch is off; otherwise the
     * categories the resolver allows plus categories reached only by an object
     * ALLOW (then only those objects), and a rolling UTC training cutoff.
     */
    fun scope(person: SharingPerson, policy: SharingPolicy, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): ShareScope {
        if (!person.outgoing || person.state != PersonState.CONNECTED) return ShareScope.EMPTY
        val peer = PeerId(person.peer)
        val categories = SharingCategory.entries.filter { category ->
            SharingPolicyResolver.resolve(policy, peer, category).isAllowed ||
                policy.peers[peer]?.objectRules?.any { (key, effect) -> key.category == category && effect == AccessEffect.ALLOW } == true
        }
        val days = trainingDays(person)
        val cutoff = if (SharingCategory.TRAINING_HISTORY in categories && days != TrainingPeriod.ALL) today.minusDays(days.toLong()).toString() else null
        return ShareScope(categories, cutoff)
    }

    /** The exact records of [scope] that pass the per-record resolver. */
    fun selected(person: SharingPerson, scope: ShareScope, policy: SharingPolicy, source: SharingSource): List<SharingRecord> {
        if (scope.categories.isEmpty()) return emptyList()
        val peer = PeerId(person.peer)
        return source.read(scope.asSourceScope()).filter {
            SharingPolicyResolver.resolve(policy, peer, it.category, ObjectId(it.id)).isAllowed
        }
    }

    // ---- directions -------------------------------------------------------------

    fun outgoing(peer: String): OutgoingState? = q.selectOutgoing(peer).executeAsOneOrNull()?.let { row ->
        OutgoingState(row.generation, row.sequence, row.generation_started_at, json.decodeFromString(row.scope),
            json.decodeFromString(sentSerializer, row.sent), row.digest, row.record_count.toInt(), row.manifest_at,
            row.full_required == 1L, row.acked_generation, row.acked_sequence, row.acked_at)
    }

    fun putOutgoing(peer: String, s: OutgoingState) = q.upsertOutgoing(peer, s.generation, s.sequence, s.generationStartedAt,
        json.encodeToString(s.scope), json.encodeToString(sentSerializer, s.sent), s.digest, s.count.toLong(), s.manifestAt,
        if (s.fullRequired) 1 else 0, s.ackedGeneration, s.ackedSequence, s.ackedAt)

    fun requireFull(peer: String) = q.requireFull(peer)
    fun recordAck(peer: String, generation: Long, sequence: Long, at: Long) = q.recordAck(generation, sequence, at, peer)

    fun incoming(peer: String): IncomingState = q.selectIncoming(peer).executeAsOneOrNull()?.let { row ->
        IncomingState(row.generation, row.sequence, json.decodeFromString(row.scope), row.expected_digest,
            row.expected_count.toInt(), json.decodeFromString(stagingSerializer, row.staging),
            json.decodeFromString(pendingSerializer, row.pending), row.resync_generation, row.resync_at,
            row.ack_due == 1L, row.received_at)
    } ?: IncomingState.NONE

    fun incomingStates(): Map<String, IncomingState> = q.selectAllIncoming().executeAsList().associate { it.peer to incoming(it.peer) }

    fun putIncoming(peer: String, s: IncomingState) = q.upsertIncoming(peer, s.generation, s.sequence, json.encodeToString(s.scope),
        s.expectedDigest, s.expectedCount.toLong(), json.encodeToString(stagingSerializer, s.staging),
        json.encodeToString(pendingSerializer, s.pending), s.resyncGeneration, s.resyncAt, if (s.ackDue) 1 else 0, s.receivedAt)

    /** Forget both directions and everything received from [peer]. */
    fun clearExchange(peer: String) {
        q.deleteRecords(peer)
        q.deleteIncoming(peer)
        q.deleteOutgoing(peer)
    }

    // ---- received records ---------------------------------------------------------

    fun records(peer: String): List<SharingRecord> = q.selectRecords(peer).executeAsList().map(::toRecord)

    fun received(): List<ReceivedRecord> = q.selectAllRecords().executeAsList().map { ReceivedRecord(it.peer, toRecord(it), it.climb_uuid, it.occurred_on) }

    fun receivedForClimb(climbUuid: String): List<ReceivedRecord> =
        q.selectRecordsForClimb(climbUuid).executeAsList().map { ReceivedRecord(it.peer, toRecord(it), it.climb_uuid, it.occurred_on) }

    private fun toRecord(row: com.cruxcoach.db.secure.Share_record) = SharingRecord(row.record_id,
        SharingCategory.valueOf(row.category), row.revision, json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), row.fields))

    fun putRecord(peer: String, record: SharingRecord) = q.upsertRecord(peer, record.id, record.category.name, record.revision,
        record.fields["climbId"]?.takeIf { record.category != SharingCategory.PROFILE_AND_GOALS },
        record.fields["date"]?.take(10), json.encodeToString(MapSerializer(String.serializer(), String.serializer()), record.fields))

    fun deleteRecord(peer: String, id: String) = q.deleteRecord(peer, id)
    fun deleteRecords(peer: String) = q.deleteRecords(peer)
}
