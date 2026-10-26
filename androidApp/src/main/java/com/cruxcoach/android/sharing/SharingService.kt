package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.Audience
import com.cruxcoach.domain.sharing.AudienceResolver
import com.cruxcoach.domain.sharing.Bech32
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.RecordRuleKey
import com.cruxcoach.domain.sharing.ShareCategory
import com.cruxcoach.domain.sharing.ShareDecisionSource
import com.cruxcoach.domain.sharing.SharePeriod
import com.cruxcoach.domain.sharing.ShareRule
import com.cruxcoach.domain.sharing.ShareTemplate
import com.cruxcoach.domain.sharing.TemplateId
import java.time.LocalDate
import java.time.ZoneOffset

/** The four states every screen shows for a person. */
enum class VisibleState { PENDING, ACTIVE, STOPPED, ENDED }

/** How complete what a friend shares with us is (VS7). */
sealed interface Completeness {
    /** Count and digest matched for the friend's state as of [asOf] (the friend's clock). */
    data class Complete(val asOf: Long) : Completeness

    /** A gap is known since [since] (our clock; 0: nothing arrived yet). */
    data class Filling(val since: Long) : Completeness
}

/** Whether what we share reached a friend (VS8): their last confirmation and,
 * when that is overdue, since when they wait (null: they are up to date). */
data class Delivery(val confirmedAt: Long, val waitingSince: Long?)

/** One person as the UI sees them: local choices plus the native state. */
data class SharingFriend(
    val person: SharingPerson,
    val native: MarmotPeer?,
    val visible: VisibleState,
    /** Received profile name, if the friend shares it. */
    val profileName: String?,
    val incomingScope: ShareScope,
    val received: Int,
    val confirmedAt: Long,
    val completeness: Completeness = Completeness.Filling(0),
    /** Null while we share nothing with them. */
    val delivery: Delivery? = null,
) {
    val peer get() = person.peer
    val displayName: String get() = SharingNames.display(person.label, profileName, person.peer)
}

object SharingNames {
    /** A person's own label, else the name they share, else a short npub. */
    fun display(label: String?, profileName: String?, peer: String): String =
        label?.takeIf { it.isNotBlank() } ?: profileName?.takeIf { it.isNotBlank() } ?: shortId(peer)

    fun shortId(peer: String): String = Bech32.npub(peer)?.let { it.take(12) + "…" + it.takeLast(6) } ?: (peer.take(12) + "…")
}

/** A friend's record about one climb, with the friend's display name. */
data class ReceivedOnClimb(val peer: String, val name: String, val record: SharingRecord)

/** One own record about a climb and who receives it now ("who sees this?"). */
data class OwnOnClimb(val record: SharingRecord, val audience: List<String>, val never: Boolean)

/** Who receives one category now, and why (template, exception, single records; until when). */
data class CategoryAudience(val category: ShareCategory, val entries: List<AudienceEntry>)

data class AudienceEntry(val peer: String, val name: String, val source: ShareDecisionSource?, val expiresAt: Long?)

/** A friendship request from someone without a local person entry yet. */
data class SharingInvitation(val peer: String, val receivedAt: Long)

/** One category for one person: the outcome, why, and whether it follows the template. */
data class CategoryChoice(
    val shared: Boolean,
    val byTemplate: Boolean,
    val exception: ShareRule?,
    val source: ShareDecisionSource,
)

/** One own record, whether it goes to the person while their switch is on, and
 * whether it is never shared with anyone. */
data class PreviewItem(val record: SharingRecord, val included: Boolean, val exception: ShareRule?, val never: Boolean = false)

/** Everything the person page shows. [items] ignores the switch; [SharingFriend.visible] says whether it is on. */
data class SharingPersonDetail(
    val friend: SharingFriend,
    val template: ShareTemplate,
    val categories: Map<ShareCategory, CategoryChoice>,
    val periodDays: Int,
    val items: List<PreviewItem>,
    val received: List<SharingRecord>,
    /** What would be sent is larger than one generation may carry: nothing goes out. */
    val overLimit: Boolean = false,
    /** Own records that can never be sent (too long or incomplete); left out one by one. */
    val skipped: Int = 0,
)

data class SharingOverview(
    val status: MarmotStatus,
    /** Templates a person can be given now (hidden ones come with their categories). */
    val templates: List<ShareTemplate>,
    val friends: List<SharingFriend>,
    val invitations: List<SharingInvitation>,
    /** The self-declared age was asked; [ageConfirmed]: 16 or older. */
    val ageAnswered: Boolean = true,
    val ageConfirmed: Boolean = true,
    /** Every template, hidden ones too: a person may already have one. */
    val allTemplates: List<ShareTemplate> = templates,
    /** The local history of own decisions, newest first. */
    val history: List<HistoryEntry> = emptyList(),
    /** People are invited by link (Olm); otherwise by npub. */
    val linkInvites: Boolean = false,
    /** Own invitation links still open, newest first. */
    val links: List<MarmotLink> = emptyList(),
    /** Who receives which category now. */
    val whoSees: List<CategoryAudience> = emptyList(),
)

/**
 * Person-facing operations. Every policy change is treated as a possible
 * narrowing: the friend's not-yet-replicated messages are withdrawn natively
 * first, then the change commits, all under the sync mutex (target doc §7, O6).
 * If the withdrawal cannot run now, a flag guarantees it runs before the host
 * next goes online.
 */
class SharingService(
    private val access: SharingHost,
    private val store: SharingStore,
    private val sync: SharingSync,
    private val source: SharingSource,
    private val onChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** The same day source as [SharingSync], so preview and sending share one cutoff. */
    private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) },
) {
    private val host get() = access.host

    suspend fun overview(): SharingOverview {
        // What a friend shared with an end date is gone at that date, open app or not.
        val now = clock()
        store.purgeExpiredRecords(now)
        val status = host.status()
        val incoming = store.incomingStates()
        val friends = store.persons().map { person ->
            val native = status.peers.firstOrNull { it.account == person.peer }
            val received = store.records(person.peer)
            SharingFriend(
                person = person,
                native = native,
                visible = visible(person, native),
                profileName = received.firstOrNull { it.category == ShareCategory.CLIMBING_PROFILE }?.fields?.get("name"),
                incomingScope = incoming[person.peer]?.scope ?: ShareScope.EMPTY,
                received = received.size,
                confirmedAt = store.member(person.peer)?.ackedAt ?: 0,
                completeness = completeness(incoming[person.peer]),
                delivery = if (person.state == PersonState.CONNECTED && person.outgoing) delivery(person, now) else null,
            )
        }
        // An ended friendship does not hide a new request from the same person.
        val known = friends.filter { it.person.state != PersonState.ENDED }.map { it.peer }.toSet()
        val invitations = status.peers.filter { it.state == "invited" && it.account !in known }
            .map { SharingInvitation(it.account, it.createdAt) }
        val templates = store.templates()
        val links = if (access.linkInvites) runCatching { host.links() }.getOrDefault(emptyList()) else emptyList()
        return SharingOverview(status, templates.filterNot { it.hidden }, friends, invitations,
            ageAnswered = store.ageAnswered(), ageConfirmed = store.ageConfirmed(), allTemplates = templates, history = store.history(), whoSees = whoSeesWhat(),
            linkInvites = access.linkInvites, links = links)
    }

    private fun completeness(state: IncomingState?): Completeness = when {
        state == null || state.stream.isEmpty() -> Completeness.Filling(state?.incompleteSince ?: 0)
        state.incompleteSince > 0 -> Completeness.Filling(state.incompleteSince)
        else -> Completeness.Complete(state.completeAt)
    }

    /** Up to date: confirmed the stream's current state, or confirmed recently
     * (confirmations come about once a day). Otherwise waiting since the last one. */
    private fun delivery(person: SharingPerson, now: Long): Delivery {
        val member = store.member(person.peer) ?: return Delivery(0, person.createdAt)
        val stream = store.stream(member.stream)
        val current = stream != null && member.ackedGeneration == stream.generation && member.ackedSequence == stream.sequence
        val recent = member.ackedAt > 0 && now - member.ackedAt <= CURRENT_MS
        val upToDate = !member.snapshotDue && (current || recent)
        return Delivery(member.ackedAt, if (upToDate) null else member.ackedAt.takeIf { it > 0 } ?: member.joinedAt)
    }

    private fun visible(person: SharingPerson, native: MarmotPeer?): VisibleState = when {
        person.state == PersonState.ENDED || person.state == PersonState.ENDING || native?.ended == true -> VisibleState.ENDED
        person.state == PersonState.REQUESTED || native == null || !native.active -> VisibleState.PENDING
        person.outgoing -> VisibleState.ACTIVE
        else -> VisibleState.STOPPED
    }

    suspend fun setReachable(enabled: Boolean) {
        // Pending withdrawals run before the host may go online (§7, O6).
        access.interactive { sync.beforeOnline(host); host.setOnline(true); host.setDiscovery(enabled) }
        onChanged()
    }

    /** One foreground pass now: withdrawals, online, native sync, app step. */
    suspend fun syncNow(): Boolean = access.interactive { SharingPass(host, sync).run(stayOnline = true) }

    /** Ask [peer] for a friendship; the invitation is retried until it can be delivered. */
    suspend fun request(peer: String, template: TemplateId, label: String?) {
        check(store.ageConfirmed()) { "age_not_confirmed" }
        sync.exclusive {
            val existing = store.person(peer)
            store.transaction {
                if (existing != null) {
                    check(existing.state == PersonState.ENDED) { "person_exists" }
                    store.clearExchange(peer)
                    store.deletePerson(peer)
                }
                store.insertPerson(peer, template, outgoing = true, label = label, state = PersonState.REQUESTED, now = clock())
                store.addHistory(clock(), peer, HistoryEvent.REQUESTED, template.value)
            }
        }
        sync.retryInvitationNow(peer)
        runCatching { access.interactive { sync.run(host) } }
        onChanged()
    }

    /** A new invitation link to send; whoever uses it asks, and this side confirms. */
    suspend fun createLink(): String {
        check(store.ageConfirmed()) { "age_not_confirmed" }
        val link = host.linkCode()
        onChanged()
        return ShareLinks.url(link.payload)
    }

    /** A fresh contact code to show as QR (valid an hour, taken once). */
    suspend fun contactCode(): String {
        check(store.ageConfirmed()) { "age_not_confirmed" }
        return host.contactCode()
    }

    suspend fun revokeLink(id: String) {
        host.revokeLink(id)
        onChanged()
    }

    /** An opened link: ask its issuer for a friendship with [template]. */
    suspend fun openLink(payload: String, template: TemplateId, label: String?) {
        check(store.ageConfirmed()) { "age_not_confirmed" }
        val peer = host.importContact(payload)
        request(peer, template, label)
    }

    suspend fun accept(peer: String, template: TemplateId, outgoing: Boolean) {
        check(store.ageConfirmed()) { "age_not_confirmed" }
        sync.exclusive {
            store.transaction {
                store.person(peer)?.let {
                    check(it.state == PersonState.ENDED) { "person_exists" }
                    store.clearExchange(peer)
                    store.deletePerson(peer)
                }
                store.insertPerson(peer, template, outgoing, label = null, state = PersonState.CONNECTED, now = clock())
                store.addHistory(clock(), peer, HistoryEvent.ACCEPTED, "${template.value}:${if (outgoing) "on" else "off"}")
            }
            runCatching { host.accept(peer) }.onFailure {
                store.deletePerson(peer)
                throw it
            }
        }
        onChanged()
    }

    suspend fun decline(peer: String) {
        host.decline(peer)
        onChanged()
    }

    suspend fun setOutgoing(peer: String, outgoing: Boolean) = narrowing(listOf(peer)) {
        store.setOutgoing(peer, outgoing)
        store.addHistory(clock(), peer, HistoryEvent.OUTGOING, if (outgoing) "on" else "off")
    }
    suspend fun setTemplate(peer: String, template: TemplateId) = narrowing(listOf(peer)) {
        store.setTemplate(peer, template)
        store.addHistory(clock(), peer, HistoryEvent.TEMPLATE_CHOSEN, template.value)
    }
    suspend fun setPeriod(peer: String, days: Int?) = narrowing(listOf(peer)) { store.setPeriod(peer, days) }
    /** [expiresAt] (epoch ms) ends the rule by itself; health categories need one to be allowed. */
    suspend fun setPersonRule(peer: String, category: ShareCategory, effect: AccessEffect?, expiresAt: Long? = null) =
        narrowing(listOf(peer)) {
            store.setPersonRule(peer, category, effect?.let { ShareRule(it, expiresAt) })
            store.addHistory(clock(), peer, HistoryEvent.CATEGORY_RULE, ruleDetail(category, effect, expiresAt))
        }
    suspend fun setObjectRule(peer: String, recordId: String, category: ShareCategory, effect: AccessEffect?, expiresAt: Long? = null) =
        narrowing(listOf(peer)) {
            store.setObjectRule(peer, recordId, category, effect?.let { ShareRule(it, expiresAt) })
            store.addHistory(clock(), peer, HistoryEvent.RECORD_RULE, ruleDetail(category, effect, expiresAt))
        }

    /** `CATEGORY:EFFECT[:until]`; an empty effect means "follows the template again". */
    private fun ruleDetail(category: ShareCategory, effect: AccessEffect?, expiresAt: Long?) =
        listOfNotNull(category.name, effect?.name.orEmpty(), expiresAt?.toString()).joinToString(":")

    /** "Never share this record" applies to everyone at once. */
    suspend fun setNeverShare(recordId: String, never: Boolean) = narrowing(connected()) {
        store.setNeverShare(recordId, never, clock())
        store.addHistory(clock(), null, HistoryEvent.NEVER_SHARE, if (never) "on" else "off")
    }

    /** The self-declared age (FEAT-073 §2.7). Under 16 nothing goes to anyone. */
    suspend fun setAge(atLeastSixteen: Boolean) = narrowing(connected()) {
        store.setAge(atLeastSixteen)
        store.addHistory(clock(), null, HistoryEvent.AGE, if (atLeastSixteen) "on" else "off")
    }

    /** The local history, newest first. */
    fun history(): List<HistoryEntry> = store.history()

    private fun connected() = store.persons().filter { it.state == PersonState.CONNECTED }.map { it.peer }

    suspend fun setLabel(peer: String, label: String?) {
        store.setLabel(peer, label?.trim()?.takeIf { it.isNotEmpty() })
        onChanged()
    }

    /** Edit a template; every connected person on it may receive less from now on. */
    suspend fun putTemplate(template: ShareTemplate) {
        val affected = store.persons().filter { it.state == PersonState.CONNECTED && it.template == template.id }.map { it.peer }
        narrowing(affected) {
            store.putTemplate(template)
            store.addHistory(clock(), null, HistoryEvent.TEMPLATE_EDITED, template.id.value)
        }
    }

    /** End the friendship: both received directions are deleted locally now;
     * the friend's app deletes its copies when the end message arrives. */
    suspend fun end(peer: String) {
        sync.exclusive {
            runCatching { host.cancel(peer) }
            store.transaction {
                store.clearExchange(peer)
                store.setState(peer, PersonState.ENDING, clock())
                store.addHistory(clock(), peer, HistoryEvent.ENDED)
            }
        }
        onChanged()
    }

    /** Forget an ended person entirely. */
    suspend fun remove(peer: String) {
        sync.exclusive {
            val person = store.person(peer) ?: return@exclusive
            check(person.state == PersonState.ENDED || person.state == PersonState.REQUESTED) { "person_active" }
            store.transaction {
                store.clearExchange(peer)
                store.deletePerson(peer)
                store.addHistory(clock(), peer, HistoryEvent.REMOVED)
            }
        }
        onChanged()
    }

    /** Exactly what [peer] receives now (or would receive once connected). */
    fun preview(peer: String): List<SharingRecord> {
        val person = store.person(peer) ?: return emptyList()
        val policy = store.policy()
        val connected = person.copy(state = PersonState.CONNECTED)
        val scope = store.scope(connected, policy, clock(), today())
        return runCatching { store.selected(connected, scope, policy, source, clock()) }.getOrDefault(emptyList())
    }

    fun previewScope(peer: String): ShareScope {
        val person = store.person(peer) ?: return ShareScope.EMPTY
        return store.scope(person.copy(state = PersonState.CONNECTED), store.policy(), clock(), today())
    }

    suspend fun detail(peer: String, overview: SharingOverview? = null): SharingPersonDetail? {
        val friend = (overview ?: overview()).friends.firstOrNull { it.peer == peer } ?: return null
        val person = friend.person
        val policy = store.policy()
        val now = clock()
        val audience = Audience.Person(PeerId(peer))
        val rules = policy.persons[audience.peer]
        val template = store.template(person.template) ?: ShareTemplate(person.template, emptySet())
        val categories = SharingRecords.supported.sortedBy { it.ordinal }.associateWith { category ->
            val decision = AudienceResolver.resolve(policy, audience, category, null, now)
            CategoryChoice(decision.isAllowed, category in template.categories, rules?.categoryRules?.get(category), decision.source)
        }
        val days = store.periodDays(person, policy)
        val cutoff = if (days == SharePeriod.ALL) null else today().minusDays(days.toLong()).toString()
        val projection = runCatching { source.project(ShareScope(SharingRecords.supported.sortedBy { it.ordinal }, cutoff).asSourceScope()) }.getOrNull()
        val candidates = projection?.records.orEmpty()
        // Exactly the selection the sync sends; when it refuses, nothing goes out.
        val connected = person.copy(state = PersonState.CONNECTED)
        val overLimit = person.outgoing &&
            runCatching { store.selected(connected, store.scope(connected, policy, now, today()), policy, source, now) }.isFailure
        val items = candidates.map { record ->
            PreviewItem(
                record,
                rules != null && AudienceResolver.resolve(policy, audience, record.category, ObjectId(record.id), now).isAllowed,
                rules?.recordRules?.get(RecordRuleKey(ObjectId(record.id), record.category)),
                never = ObjectId(record.id) in policy.neverShare,
            )
        }
        return SharingPersonDetail(friend, template, categories, days, items, store.records(peer),
            overLimit = overLimit, skipped = projection?.skipped?.size ?: 0)
    }

    /**
     * "Who sees this?" for the own records about these climbs (a climb and its
     * equivalent identities): for each, the people it goes to now, exactly as the
     * sync selects (template, exceptions, period, limits), and whether it is never
     * shared. Empty while nobody is connected with sharing on.
     */
    fun whoSeesClimb(climbUuids: Set<String>): List<OwnOnClimb> {
        val persons = store.persons().filter { it.state == PersonState.CONNECTED && it.outgoing }
        if (persons.isEmpty()) return emptyList()
        val own = runCatching { source.projectClimbs(climbUuids).records }.getOrDefault(emptyList())
        if (own.isEmpty()) return emptyList()
        val policy = store.policy()
        val now = clock()
        val projections = mutableMapOf<SharingScope, SharingProjection>()
        val receiving = persons.associateWith { person ->
            val scope = store.scope(person, policy, now, today())
            runCatching { store.selected(person, scope, policy, now) { s -> projections.getOrPut(s) { source.project(s) } } }
                .getOrDefault(emptyList()).map { it.id }.toSet()
        }
        return own.map { record ->
            OwnOnClimb(record, persons.filter { record.id in receiving.getValue(it) }.map { name(it) },
                ObjectId(record.id) in policy.neverShare)
        }
    }

    /** The overview of who receives which category now, with the reason. */
    fun whoSeesWhat(): List<CategoryAudience> {
        val persons = store.persons().filter { it.state == PersonState.CONNECTED && it.outgoing }
        val policy = store.policy()
        val now = clock()
        return SharingRecords.supported.sortedBy { it.ordinal }.map { category ->
            CategoryAudience(category, persons.mapNotNull { person ->
                val audience = Audience.Person(PeerId(person.peer))
                val decision = AudienceResolver.resolve(policy, audience, category, null, now)
                val bySingleRecords = !decision.isAllowed && policy.persons[audience.peer]?.recordRules.orEmpty().any { (key, rule) ->
                    key.category == category && rule.effect == AccessEffect.ALLOW && rule.activeAt(now) &&
                        AudienceResolver.resolve(policy, audience, category, key.record, now).isAllowed
                }
                when {
                    decision.isAllowed -> AudienceEntry(person.peer, name(person), decision.source, decision.expiresAt)
                    bySingleRecords -> AudienceEntry(person.peer, name(person), null, null)
                    else -> null
                }
            })
        }
    }

    private fun name(person: SharingPerson): String {
        val profile = store.records(person.peer).firstOrNull { it.category == ShareCategory.CLIMBING_PROFILE }?.fields?.get("name")
        return SharingNames.display(person.label, profile, person.peer)
    }

    fun received(peer: String): List<SharingRecord> = store.records(peer)
    fun receivedForClimb(climbUuid: String): List<ReceivedRecord> = store.receivedForClimb(climbUuid)

    /** Records current friends shared about any of [climbUuids] (a climb and
     * its equivalent identities). Reads only the app database. */
    fun receivedOnClimb(climbUuids: Set<String>): List<ReceivedOnClimb> {
        store.purgeExpiredRecords(clock())
        val persons = store.persons().filter { it.state == PersonState.CONNECTED }.associateBy { it.peer }
        val names = mutableMapOf<String, String>()
        fun name(person: SharingPerson) = names.getOrPut(person.peer) {
            val profile = store.records(person.peer).firstOrNull { it.category == ShareCategory.CLIMBING_PROFILE }?.fields?.get("name")
            SharingNames.display(person.label, profile, person.peer)
        }
        return climbUuids.flatMap { store.receivedForClimb(it) }.mapNotNull { received ->
            val person = persons[received.peer] ?: return@mapNotNull null
            ReceivedOnClimb(received.peer, name(person), received.record)
        }.distinctBy { it.peer to it.record.id }
            .sortedWith(compareBy<ReceivedOnClimb> { it.name.lowercase() }.thenByDescending { it.record.fields["date"].orEmpty() })
    }

    private suspend fun narrowing(peers: List<String>, change: () -> Unit) {
        sync.exclusive {
            val withdrawn = peers.associateWith { peer -> runCatching { host.cancel(peer) } }
            store.transaction {
                change()
                for ((peer, result) in withdrawn) {
                    // A withdrawn message leaves a gap the friend cannot fill:
                    // the friend gets a snapshot of their own next.
                    if ((result.getOrNull() ?: 0) > 0) store.requireSnapshot(peer)
                    if (result.isFailure) store.setCancelPending(peer, true)
                }
            }
        }
        onChanged()
    }

    companion object {
        /** A complete state this recent counts as current; a confirmation this recent as up to date (VS7, VS8). */
        const val CURRENT_MS = 36 * 60 * 60_000L
        /** A gap older than this is shown as missing data, not as data on its way (VS7). */
        const val LONG_GAP_MS = 24 * 60 * 60_000L
    }
}
