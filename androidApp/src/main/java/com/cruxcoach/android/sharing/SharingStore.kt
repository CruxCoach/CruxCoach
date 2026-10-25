package com.cruxcoach.android.sharing

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.Audience
import com.cruxcoach.domain.sharing.AudienceResolver
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.PersonPolicy
import com.cruxcoach.domain.sharing.RecordRuleKey
import com.cruxcoach.domain.sharing.ShareCategory
import com.cruxcoach.domain.sharing.SharePeriod
import com.cruxcoach.domain.sharing.SharePolicy
import com.cruxcoach.domain.sharing.ShareRule
import com.cruxcoach.domain.sharing.ShareTemplate
import com.cruxcoach.domain.sharing.ShareTemplates
import com.cruxcoach.domain.sharing.TemplateId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneOffset

enum class PersonState { REQUESTED, CONNECTED, ENDING, ENDED }

/** What the local history records (FEAT-073 U2). Kept only here, never sent. */
enum class HistoryEvent { REQUESTED, ACCEPTED, TEMPLATE_CHOSEN, CATEGORY_RULE, RECORD_RULE, NEVER_SHARE, OUTGOING, TEMPLATE_EDITED, AGE, EXPIRED, ENDED, REMOVED }

/** One history line. [detail] is event-specific: a template id, `CATEGORY:EFFECT`, `on`/`off`. */
data class HistoryEntry(val seq: Long, val at: Long, val peer: String?, val event: HistoryEvent, val detail: String?)

data class SharingPerson(
    val peer: String,
    val template: TemplateId,
    val outgoing: Boolean,
    /** Overrides the template's period for this person; null follows the template. */
    val periodDays: Int?,
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
    companion object {
        /** A friend's clock may run a little behind; their next generation usually arrives first. */
        const val RECEIVED_GRACE_MS = 10 * 60_000L
        const val HISTORY_DAYS = 365L
        const val HISTORY_KEEP = 1_000L
        private const val AGE_KEY = "age"
        private const val AGE_CONFIRMED = "16_or_older"
        private const val AGE_UNDER = "under_16"
    }

    private val q get() = database.shareQueries
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val sentSerializer = MapSerializer(String.serializer(), Long.serializer())
    private val stagingSerializer = MapSerializer(Int.serializer(), ListSerializer(SharingRecord.serializer()))
    private val pendingSerializer = ListSerializer(ShareMessage.serializer())

    fun <T> transaction(block: () -> T): T = database.transactionWithResult { block() }

    // ---- policy ---------------------------------------------------------------

    /** Every template, shipped ones first; a missing shipped row falls back to its default. */
    fun templates(): List<ShareTemplate> {
        val stored = q.selectTemplates().executeAsList().mapNotNull { row ->
            runCatching {
                ShareTemplate(TemplateId(row.id), categories(row.categories), row.period_days.toInt(), row.hidden == 1L)
            }.getOrNull()
        }.associateBy { it.id }
        val shipped = ShareTemplates.SHIPPED.map { stored[it.id] ?: it }
        return shipped + stored.values.filter { t -> shipped.none { it.id == t.id } }
    }

    fun template(id: TemplateId): ShareTemplate? = templates().firstOrNull { it.id == id }

    fun putTemplate(template: ShareTemplate) =
        q.upsertTemplate(template.id.value, template.categories.sortedBy { it.ordinal }.joinToString(",") { it.name },
            template.periodDays.toLong(), if (template.hidden) 1 else 0)

    private fun categories(raw: String): Set<ShareCategory> =
        raw.split(',').filter { it.isNotEmpty() }.mapNotNull { name -> category(name) }.toSet()

    private fun category(name: String): ShareCategory? = ShareCategory.entries.firstOrNull { it.name == name }

    /** Self-declared age of 16 or more (FEAT-073 §2.7); stored with the moment of the answer. */
    fun ageConfirmed(): Boolean = q.selectSetting(AGE_KEY).executeAsOneOrNull() == AGE_CONFIRMED

    fun ageAnswered(): Boolean = q.selectSetting(AGE_KEY).executeAsOneOrNull() != null

    fun setAge(atLeastSixteen: Boolean) = q.putSetting(AGE_KEY, if (atLeastSixteen) AGE_CONFIRMED else AGE_UNDER)

    fun persons(): List<SharingPerson> = q.selectPersons().executeAsList().map { row ->
        SharingPerson(row.peer, TemplateId(row.template), row.outgoing == 1L, row.period_days?.toInt(),
            row.label, PersonState.valueOf(row.state), row.cancel_pending == 1L, row.created_at, row.ended_at)
    }

    fun person(peer: String): SharingPerson? = persons().firstOrNull { it.peer == peer }

    fun insertPerson(peer: String, template: TemplateId, outgoing: Boolean, label: String?, state: PersonState, now: Long) =
        q.insertPerson(peer, template.value, if (outgoing) 1 else 0, null, label?.take(64), state.name, now)

    fun setTemplate(peer: String, template: TemplateId) = q.updatePersonTemplate(template.value, peer)
    fun setOutgoing(peer: String, outgoing: Boolean) = q.updatePersonOutgoing(if (outgoing) 1 else 0, peer)
    fun setPeriod(peer: String, days: Int?) = q.updatePersonPeriod(days?.toLong(), peer)
    fun setLabel(peer: String, label: String?) = q.updatePersonLabel(label?.take(64), peer)
    fun setState(peer: String, state: PersonState, now: Long) =
        q.updatePersonState(state.name, if (state == PersonState.ENDED || state == PersonState.ENDING) now else null, peer)
    fun setCancelPending(peer: String, pending: Boolean) = q.setCancelPending(if (pending) 1 else 0, peer)
    /** Removes the person and every row that depends on them. Foreign keys are
     * off on the secure database, so nothing cascades by itself. */
    fun deletePerson(peer: String) = database.transaction {
        q.deletePersonRules(peer)
        q.deletePersonObjectRules(peer)
        clearExchange(peer)
        q.deletePerson(peer)
    }

    fun setPersonRule(peer: String, category: ShareCategory, rule: ShareRule?) =
        if (rule == null) q.deletePersonRule(peer, category.name)
        else q.upsertPersonRule(peer, category.name, rule.effect.name, rule.expiresAt)

    fun setObjectRule(peer: String, recordId: String, category: ShareCategory, rule: ShareRule?) =
        if (rule == null) q.deleteObjectRule(peer, recordId)
        else q.upsertObjectRule(peer, recordId, category.name, rule.effect.name, rule.expiresAt)

    fun neverShared(): Set<String> = q.selectNever().executeAsList().toSet()

    fun setNeverShare(recordId: String, never: Boolean, now: Long) =
        if (never) q.insertNever(recordId, now) else q.deleteNever(recordId)

    /** The policy the resolver evaluates; ended persons are absent. Unknown
     * category names in stored rules are ignored (they cannot widen anything). */
    fun policy(): SharePolicy {
        val persons = persons().filter { it.state != PersonState.ENDED && it.state != PersonState.ENDING }
        val personRules = q.selectAllPersonRules().executeAsList().groupBy { it.peer }
        val objectRules = q.selectAllObjectRules().executeAsList().groupBy { it.peer }
        return SharePolicy(
            templates = templates().associateBy { it.id },
            persons = persons.associate { person ->
                PeerId(person.peer) to PersonPolicy(
                    template = person.template,
                    periodDays = person.periodDays,
                    categoryRules = personRules[person.peer].orEmpty().mapNotNull { row ->
                        category(row.category)?.let { it to ShareRule(AccessEffect.valueOf(row.effect), row.expires_at) }
                    }.toMap(),
                    recordRules = objectRules[person.peer].orEmpty().mapNotNull { row ->
                        category(row.category)?.let { RecordRuleKey(ObjectId(row.record_id), it) to ShareRule(AccessEffect.valueOf(row.effect), row.expires_at) }
                    }.toMap(),
                )
            },
            neverShare = neverShared().map(::ObjectId).toSet(),
            ageConfirmed = ageConfirmed(),
        )
    }

    fun periodDays(person: SharingPerson, policy: SharePolicy = policy()): Int =
        AudienceResolver.periodDays(policy, PeerId(person.peer)) ?: person.periodDays ?: template(person.template)?.periodDays ?: SharePeriod.DEFAULT

    /**
     * What goes to [person] now: nothing when the switch is off; otherwise the
     * categories the resolver allows plus categories reached only by a record
     * ALLOW (then only those records), and a rolling UTC cutoff for dated ones.
     */
    fun scope(person: SharingPerson, policy: SharePolicy, now: Long, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): ShareScope {
        if (!person.outgoing || person.state != PersonState.CONNECTED) return ShareScope.EMPTY
        val peer = PeerId(person.peer)
        val audience = Audience.Person(peer)
        val rules = policy.persons[peer]?.recordRules.orEmpty()
        val categories = SharingRecords.supported.filter { category ->
            AudienceResolver.resolve(policy, audience, category, null, now).isAllowed ||
                rules.any { (key, rule) ->
                    key.category == category && rule.effect == AccessEffect.ALLOW && rule.activeAt(now) &&
                        AudienceResolver.resolve(policy, audience, category, key.record, now).isAllowed
                }
        }.sortedBy { it.ordinal }
        val days = periodDays(person, policy)
        val cutoff = if (categories.any { it.dated } && days != SharePeriod.ALL) today.minusDays(days.toLong()).toString() else null
        return ShareScope(categories, cutoff)
    }

    /** The exact records of [scope] that pass the per-record resolver, each
     * carrying the expiry of the rule that lets it out. Throws when that set is
     * incomplete or larger than one generation may carry: then nothing is sent,
     * and the person page says so ([SharingPersonDetail.overLimit]). */
    fun selected(person: SharingPerson, scope: ShareScope, policy: SharePolicy, source: SharingSource, now: Long): List<SharingRecord> =
        selected(person, scope, policy, now, source::project)

    /** As above with the projection supplied (several people with one scope read it once). */
    fun selected(
        person: SharingPerson,
        scope: ShareScope,
        policy: SharePolicy,
        now: Long,
        project: (SharingScope) -> SharingProjection,
    ): List<SharingRecord> {
        if (scope.categories.isEmpty()) return emptyList()
        val audience = Audience.Person(PeerId(person.peer))
        val sourceScope = scope.asSourceScope()
        val projection = project(sourceScope)
        require(!projection.truncated) { "over_limit" }
        return projection.records.mapNotNull { record ->
            val decision = AudienceResolver.resolve(policy, audience, record.category, ObjectId(record.id), now)
            if (decision.isAllowed) SharingRecords.withUntil(record, decision.expiresAt) else null
        }.also { SharingRecords.check(it, sourceScope) }
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
        ShareCategory.valueOf(row.category), row.revision,
        json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), row.fields), row.expires_at)

    fun putRecord(peer: String, record: SharingRecord) = q.upsertRecord(peer, record.id, record.category.name, record.revision,
        record.fields["climbId"]?.takeIf { record.category != ShareCategory.CLIMBING_PROFILE },
        record.fields["date"]?.take(10), json.encodeToString(MapSerializer(String.serializer(), String.serializer()), record.fields),
        record.until)

    fun deleteRecord(peer: String, id: String) = q.deleteRecord(peer, id)
    fun deleteRecords(peer: String) = q.deleteRecords(peer)

    // ---- history --------------------------------------------------------------------

    /** Record one own decision; older entries beyond a year or 1,000 lines go. */
    fun addHistory(at: Long, peer: String?, event: HistoryEvent, detail: String? = null) {
        q.insertHistory(at, peer, event.name, detail?.take(256))
        q.pruneHistory(at - HISTORY_DAYS * 86_400_000L, HISTORY_KEEP)
    }

    fun history(limit: Long = 200): List<HistoryEntry> = q.selectHistory(limit).executeAsList().mapNotNull { row ->
        HistoryEvent.entries.firstOrNull { it.name == row.event }?.let { HistoryEntry(row.seq, row.at, row.peer, it, row.detail) }
    }

    // ---- expiry ---------------------------------------------------------------------

    /** A received record whose grant ended, allowing [RECEIVED_GRACE_MS] for clock skew. */
    fun receivedExpired(record: SharingRecord, now: Long): Boolean =
        record.until != null && record.until + RECEIVED_GRACE_MS <= now

    /** Delete every received record whose grant ended; works without the friend in reach. */
    fun purgeExpiredRecords(now: Long) { q.deleteExpiredRecords(now - RECEIVED_GRACE_MS) }

    /** Own rules that ended: removed, and the people they concerned returned. Their
     * resolution already ignores them; the caller withdraws what is still queued. */
    fun takeExpiredRules(now: Long): Set<String> = transaction {
        val peers = q.selectExpiredRulePeers(now).executeAsList().toSet()
        if (peers.isNotEmpty()) {
            q.deleteExpiredPersonRules(now)
            q.deleteExpiredObjectRules(now)
        }
        peers
    }

    /** The next moment something here expires (an own rule, or a received record after grace). */
    fun nextExpiry(now: Long): Long? {
        val rules = q.selectRuleExpiries().executeAsList().filterNotNull().filter { it > now }
        val records = q.selectRecordExpiries().executeAsList().filterNotNull().map { it + RECEIVED_GRACE_MS }.filter { it > now }
        return (rules + records).minOrNull()
    }
}
