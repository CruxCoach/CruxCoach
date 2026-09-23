package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.Bech32
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.ObjectRuleKey
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingPolicyResolver
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

/** One category for one person: the outcome and whether it follows the preset. */
data class CategoryChoice(val shared: Boolean, val byPreset: Boolean, val exception: AccessEffect?)

/** One own record and whether it goes to the person while their switch is on. */
data class PreviewItem(val record: SharingRecord, val included: Boolean, val exception: AccessEffect?)

/** Everything the person page shows. [items] ignores the switch; [SharingFriend.visible] says whether it is on. */
data class SharingPersonDetail(
    val friend: SharingFriend,
    val preset: SharingPreset,
    val categories: Map<SharingCategory, CategoryChoice>,
    val trainingDays: Int,
    val items: List<PreviewItem>,
    val received: List<SharingRecord>,
)

data class SharingOverview(
    val status: MarmotStatus,
    val presets: Map<SharingCircle, SharingPreset>,
    val friends: List<SharingFriend>,
    val invitations: List<SharingInvitation>,
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
) {
    private val host get() = access.host

    suspend fun overview(): SharingOverview {
        val status = host.status()
        val incoming = store.incomingStates()
        val friends = store.persons().map { person ->
            val native = status.peers.firstOrNull { it.account == person.peer }
            val received = store.records(person.peer)
            SharingFriend(
                person = person,
                native = native,
                visible = visible(person, native),
                profileName = received.firstOrNull { it.category == SharingCategory.PROFILE_AND_GOALS }?.fields?.get("name"),
                incomingScope = incoming[person.peer]?.scope ?: ShareScope.EMPTY,
                received = received.size,
                confirmedAt = store.outgoing(person.peer)?.ackedAt ?: 0,
            )
        }
        // An ended friendship does not hide a new request from the same person.
        val known = friends.filter { it.person.state != PersonState.ENDED }.map { it.peer }.toSet()
        val invitations = status.peers.filter { it.state == "invited" && it.account !in known }
            .map { SharingInvitation(it.account, it.createdAt) }
        return SharingOverview(status, store.presets(), friends, invitations)
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
    suspend fun request(peer: String, circle: SharingCircle, label: String?) {
        sync.exclusive {
            val existing = store.person(peer)
            store.transaction {
                if (existing != null) {
                    check(existing.state == PersonState.ENDED) { "person_exists" }
                    store.clearExchange(peer)
                    store.deletePerson(peer)
                }
                store.insertPerson(peer, circle, outgoing = true, label = label, state = PersonState.REQUESTED, now = clock())
            }
        }
        sync.retryInvitationNow(peer)
        runCatching { access.interactive { sync.run(host) } }
        onChanged()
    }

    suspend fun accept(peer: String, circle: SharingCircle, outgoing: Boolean) {
        sync.exclusive {
            store.transaction {
                store.person(peer)?.let {
                    check(it.state == PersonState.ENDED) { "person_exists" }
                    store.clearExchange(peer)
                    store.deletePerson(peer)
                }
                store.insertPerson(peer, circle, outgoing, label = null, state = PersonState.CONNECTED, now = clock())
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
    suspend fun setCircle(peer: String, circle: SharingCircle) = narrowing(listOf(peer)) { store.setCircle(peer, circle) }
    suspend fun setTrainingDays(peer: String, days: Int?) = narrowing(listOf(peer)) { store.setTrainingDays(peer, days) }
    suspend fun setPersonRule(peer: String, category: SharingCategory, effect: AccessEffect?) =
        narrowing(listOf(peer)) { store.setPersonRule(peer, category, effect) }
    suspend fun setObjectRule(peer: String, recordId: String, category: SharingCategory, effect: AccessEffect?) =
        narrowing(listOf(peer)) { store.setObjectRule(peer, recordId, category, effect) }

    suspend fun setLabel(peer: String, label: String?) {
        store.setLabel(peer, label?.trim()?.takeIf { it.isNotEmpty() })
        onChanged()
    }

    suspend fun setPreset(preset: SharingPreset) {
        val affected = store.persons().filter { it.state == PersonState.CONNECTED }.map { it.peer }
        narrowing(affected) { store.putPreset(preset) }
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
        val scope = store.scope(connected, policy)
        return runCatching { store.selected(connected, scope, policy, source) }.getOrDefault(emptyList())
    }

    fun previewScope(peer: String): ShareScope {
        val person = store.person(peer) ?: return ShareScope.EMPTY
        return store.scope(person.copy(state = PersonState.CONNECTED), store.policy())
    }

    suspend fun detail(peer: String, overview: SharingOverview? = null): SharingPersonDetail? {
        val friend = (overview ?: overview()).friends.firstOrNull { it.peer == peer } ?: return null
        val person = friend.person
        val policy = store.policy()
        val id = PeerId(peer)
        val rules = policy.peers[id]
        val inPreset = policy.baselines.effectiveFor(person.circle)
        val categories = SharingCategory.entries.associateWith { category ->
            CategoryChoice(SharingPolicyResolver.resolve(policy, id, category).isAllowed, category in inPreset, rules?.categoryRules?.get(category))
        }
        val days = store.trainingDays(person)
        val cutoff = if (days == TrainingPeriod.ALL) null else LocalDate.now(ZoneOffset.UTC).minusDays(days.toLong()).toString()
        val candidates = runCatching { source.read(ShareScope(SharingCategory.entries, cutoff).asSourceScope()) }.getOrDefault(emptyList())
        val items = candidates.map { record ->
            PreviewItem(record, rules != null && SharingPolicyResolver.resolve(policy, id, record.category, ObjectId(record.id)).isAllowed,
                rules?.objectRules?.get(ObjectRuleKey(ObjectId(record.id), record.category)))
        }
        return SharingPersonDetail(friend, store.presets().getValue(person.circle), categories, days, items, store.records(peer))
    }

    fun received(peer: String): List<SharingRecord> = store.records(peer)
    fun receivedForClimb(climbUuid: String): List<ReceivedRecord> = store.receivedForClimb(climbUuid)

    /** Records current friends shared about any of [climbUuids] (a climb and
     * its equivalent identities). Reads only the app database. */
    fun receivedOnClimb(climbUuids: Set<String>): List<ReceivedOnClimb> {
        val persons = store.persons().filter { it.state == PersonState.CONNECTED }.associateBy { it.peer }
        val names = mutableMapOf<String, String>()
        fun name(person: SharingPerson) = names.getOrPut(person.peer) {
            val profile = store.records(person.peer).firstOrNull { it.category == SharingCategory.PROFILE_AND_GOALS }?.fields?.get("name")
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
