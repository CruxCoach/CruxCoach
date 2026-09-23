package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle

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
    val displayName: String get() = person.label?.takeIf { it.isNotBlank() } ?: profileName?.takeIf { it.isNotBlank() } ?: (person.peer.take(12) + "…")
}

/** A friendship request from someone without a local person entry yet. */
data class SharingInvitation(val peer: String, val receivedAt: Long)

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

    fun received(peer: String): List<SharingRecord> = store.records(peer)
    fun receivedForClimb(climbUuid: String): List<ReceivedRecord> = store.receivedForClimb(climbUuid)

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
