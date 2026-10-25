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
        store.purgeExpiredRecords(clock())
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
                confirmedAt = store.outgoing(person.peer)?.ackedAt ?: 0,
            )
        }
        // An ended friendship does not hide a new request from the same person.
        val known = friends.filter { it.person.state != PersonState.ENDED }.map { it.peer }.toSet()
        val invitations = status.peers.filter { it.state == "invited" && it.account !in known }
            .map { SharingInvitation(it.account, it.createdAt) }
        val templates = store.templates()
        return SharingOverview(status, templates.filterNot { it.hidden }, friends, invitations,
            ageAnswered = store.ageAnswered(), ageConfirmed = store.ageConfirmed(), allTemplates = templates)
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
            }
        }
        sync.retryInvitationNow(peer)
        runCatching { access.interactive { sync.run(host) } }
        onChanged()
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

    suspend fun setOutgoing(peer: String, outgoing: Boolean) = narrowing(listOf(peer)) { store.setOutgoing(peer, outgoing) }
    suspend fun setTemplate(peer: String, template: TemplateId) = narrowing(listOf(peer)) { store.setTemplate(peer, template) }
    suspend fun setPeriod(peer: String, days: Int?) = narrowing(listOf(peer)) { store.setPeriod(peer, days) }
    /** [expiresAt] (epoch ms) ends the rule by itself; health categories need one to be allowed. */
    suspend fun setPersonRule(peer: String, category: ShareCategory, effect: AccessEffect?, expiresAt: Long? = null) =
        narrowing(listOf(peer)) { store.setPersonRule(peer, category, effect?.let { ShareRule(it, expiresAt) }) }
    suspend fun setObjectRule(peer: String, recordId: String, category: ShareCategory, effect: AccessEffect?, expiresAt: Long? = null) =
        narrowing(listOf(peer)) { store.setObjectRule(peer, recordId, category, effect?.let { ShareRule(it, expiresAt) }) }

    /** "Never share this record" applies to everyone at once. */
    suspend fun setNeverShare(recordId: String, never: Boolean) =
        narrowing(connected()) { store.setNeverShare(recordId, never, clock()) }

    /** The self-declared age (FEAT-073 §2.7). Under 16 nothing goes to anyone. */
    suspend fun setAge(atLeastSixteen: Boolean) = narrowing(connected()) { store.setAge(atLeastSixteen) }

    private fun connected() = store.persons().filter { it.state == PersonState.CONNECTED }.map { it.peer }

    suspend fun setLabel(peer: String, label: String?) {
        store.setLabel(peer, label?.trim()?.takeIf { it.isNotEmpty() })
        onChanged()
    }

    /** Edit a template; every connected person on it may receive less from now on. */
    suspend fun putTemplate(template: ShareTemplate) {
        val affected = store.persons().filter { it.state == PersonState.CONNECTED && it.template == template.id }.map { it.peer }
        narrowing(affected) { store.putTemplate(template) }
    }

    /** End the friendship: both received directions are deleted locally now;
     * the friend's app deletes its copies when the end message arrives. */
    suspend fun end(peer: String) {
        sync.exclusive {
            runCatching { host.cancel(peer) }
            store.transaction {
                store.clearExchange(peer)
                store.setState(peer, PersonState.ENDING, clock())
            }
        }
        onChanged()
    }

    /** Forget an ended person entirely. */
    suspend fun remove(peer: String) {
        sync.exclusive {
            val person = store.person(peer) ?: return@exclusive
            check(person.state == PersonState.ENDED || person.state == PersonState.REQUESTED) { "person_active" }
            store.transaction { store.clearExchange(peer); store.deletePerson(peer) }
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
                    // the next send starts a new generation.
                    if ((result.getOrNull() ?: 0) > 0) store.requireFull(peer)
                    if (result.isFailure) store.setCancelPending(peer, true)
                }
            }
        }
        onChanged()
    }
}
