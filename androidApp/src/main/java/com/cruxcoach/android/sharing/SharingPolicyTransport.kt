package com.cruxcoach.android.sharing

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable internal data class LedgerWire(
    val id: String, val peer: String, val sequence: Long, val parent: String?,
    val authority: Long, val epoch: Long, val generation: Long, val signer: String,
    val signature: String, val kind: String, val body: String,
) {
    fun entry() = SharingLedgerEntry(LedgerEntryId(id), PeerId(peer), sequence, parent?.let(::LedgerEntryId),
        authority, epoch, generation, signer, signature, SharingLedgerCodec.decodeBody(kind, body))
    companion object {
        fun of(entry: SharingLedgerEntry): LedgerWire {
            val (kind, body) = SharingLedgerCodec.encodeBody(entry.body)
            return LedgerWire(entry.id.value, entry.peer.value, entry.policySequence, entry.parent?.value,
                entry.authorityGeneration, entry.resourceEpoch, entry.deviceGeneration, entry.signerNpub, entry.signature, kind, body)
        }
    }
}
@Serializable internal data class PolicyProposal(
    val version: Int = 1, val purpose: String = "cc.sharing.policy.v1", val action: String,
    val owner: String, val recipient: String, val binding: String,
    val head: LedgerWire, val categories: Set<SharingCategory>, val expiresAt: Long,
    val consent: LedgerWire? = null,
)
data class IncomingSharingPolicy(val peer: String, val categories: Set<SharingCategory>, val expiresAt: Long, val accepted: Boolean, val headId: String = "")

/** Transports a current owner proposal and a peer-signed ledger acceptance.
 * Both directions are MLS-authenticated and session-bound; the owner admits the
 * response through the existing signature/DAG/authority reducer at its real DB.
 * The recipient stores no owner's vault keys or unrelated policy history. */
class SharingPolicyTransport(
    private val database: SecureDatabase,
    private val repository: SecureDbSharingRepository,
    private val account: String,
    private val port: MarmotSnapshotPort,
    private val signer: AsyncSharingLedgerSigner,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = SharingSessionCoordination.forDatabase(database)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val q get() = database.sharingTransportQueries
    private val tags = listOf(listOf("l", "cc.sharing.policy.v1", "cruxcoach.private"))
    private fun ready() = repository.canUseSnapshots(DeviceCapability.READ) && !repository.administrativeWritesLocked()
    private fun decode(raw: String): PolicyProposal? = if (raw.length > 65_536) null else
        runCatching { json.decodeFromString<PolicyProposal>(raw).takeIf {
            json.encodeToString(it) == raw && it.version == 1 && it.purpose == "cc.sharing.policy.v1" &&
                it.action in setOf("PROPOSE", "CONSENT") && it.categories.isNotEmpty() &&
                it.head.peer == it.recipient && it.head.entry().body !is SharingLedgerBody.Unknown &&
                it.head.signer in setOf(it.owner, it.recipient) && signer.verifier().verify(it.head.entry())
        } }.getOrNull()

    fun synchronize() = synchronized(lock) {
        if (!ready()) return@synchronized
        port.drain(1222, tags) { session, raw ->
            val proposal = decode(raw) ?: return@drain
            if (proposal.binding != session.binding || proposal.expiresAt <= now() || proposal.expiresAt > now() + 7 * 86_400_000L) return@drain
            if (proposal.action == "PROPOSE" && proposal.recipient == account && proposal.owner == session.peer.account && proposal.consent == null) {
                database.transaction {
                    val previous = q.incomingPolicy(account, proposal.owner).executeAsOneOrNull()
                    val prior = previous?.proposal?.let(::decode)
                    if (prior != null && prior.binding == proposal.binding && prior.head.sequence > proposal.head.sequence) return@transaction
                    if (prior != null && prior.binding == proposal.binding && prior.head.sequence == proposal.head.sequence) {
                        if (prior != proposal) {
                            // An expired request can be re-offered with the same
                            // signed head; this never reuses its old acceptance.
                            val renewal = prior.expiresAt <= now() && proposal.expiresAt > prior.expiresAt && prior.copy(expiresAt = proposal.expiresAt) == proposal
                            q.putIncomingPolicy(account, proposal.owner, session.binding, raw, null, if (renewal) "PENDING" else "CONFLICT")
                        }
                        return@transaction
                    }
                    q.putIncomingPolicy(account, proposal.owner, session.binding, raw, null, "PENDING")
                }
            } else if (proposal.action == "CONSENT" && proposal.owner == account && proposal.recipient == session.peer.account) {
                val response = proposal.consent?.entry() ?: return@drain
                val original = q.outgoingPolicy(account, proposal.recipient).executeAsOneOrNull()?.let(::decode) ?: return@drain
                if (proposal.copy(action = "PROPOSE", consent = null) != original) return@drain
                val acceptance = response.body as? SharingLedgerBody.RecipientAccepted ?: return@drain
                if (response.signerNpub != session.peer.account || response.peer.value != session.peer.account ||
                    acceptance.deviceId.value != session.peer.device || !proposal.categories.containsAll(acceptance.categories)) return@drain
                database.transaction {
                    if (ready() && repository.authorisedHead(response.peer)?.id == response.parent && proposal.expiresAt > now()) repository.tryAppendEntry(response)
                }
            }
        }
        repository.loadProjection().relationships.values.filter { it.pendingConsentCategories.isNotEmpty() && it.failClosedReason == null }.forEach { state ->
            port.withSession(state.peer.value) { session ->
                val head = repository.authorisedHead(state.peer) ?: return@withSession
                val old = q.outgoingPolicy(account, state.peer.value).executeAsOneOrNull()?.let(::decode)
                val proposal = if (old != null && old.head == LedgerWire.of(head) && old.binding == session.binding && old.expiresAt > now()) old else
                    PolicyProposal(action = "PROPOSE", owner = account, recipient = state.peer.value, binding = session.binding,
                        head = LedgerWire.of(head), categories = state.offeredCategories,
                        expiresAt = minOf(now() + 86_400_000, state.expiresAt ?: Long.MAX_VALUE))
                if (proposal.categories.isEmpty() || proposal.expiresAt <= now()) return@withSession
                val wire = json.encodeToString(proposal)
                q.putOutgoingPolicy(account, state.peer.value, wire)
                port.handoff(session, 1222, tags, wire)
            }
        }
        q.incomingPolicies(account).executeAsList().filter { it.state == "ACCEPTED" }.forEach { row ->
            val consent = row.consent ?: return@forEach
            val proposal = decode(consent) ?: return@forEach
            port.withSession(row.peer) { session ->
                if (session.binding == row.binding && proposal.expiresAt > now()) port.handoff(session, 1222, tags, consent)
            }
        }
        flushPending()
    }

    private fun flushPending() {
        port.flush(1222, tags) { session, wire, publish ->
            val proposal = decode(wire) ?: return@flush
            database.transaction {
                if (!ready() || proposal.binding != session.binding || proposal.expiresAt <= now()) return@transaction
                if (proposal.action == "PROPOSE" && proposal.owner == account && proposal.recipient == session.peer.account &&
                    repository.authorisedHead(PeerId(proposal.recipient)) == proposal.head.entry()) publish()
                else if (proposal.action == "CONSENT" && proposal.recipient == account && proposal.owner == session.peer.account) {
                    val row = q.incomingPolicy(account, proposal.owner).executeAsOneOrNull()
                    if (row?.state == "ACCEPTED" && row.consent == wire && row.binding == session.binding) publish()
                }
            }
        }
    }

    fun incoming(peer: String): IncomingSharingPolicy? = synchronized(lock) {
        if (!ready()) return@synchronized null
        val row = q.incomingPolicy(account, peer).executeAsOneOrNull() ?: return@synchronized null
        val proposal = decode(row.proposal) ?: return@synchronized null
        if (row.state !in setOf("PENDING", "ACCEPTED") || proposal.expiresAt <= now()) return@synchronized null
        port.withSession(peer) { session ->
            if (session.binding != row.binding) null else IncomingSharingPolicy(peer, proposal.categories, proposal.expiresAt, row.state == "ACCEPTED", proposal.head.id)
        }
    }

    /** Only a user action invokes this; receiving PROPOSE never signs consent. */
    fun accept(peer: String, categories: Set<SharingCategory>): Boolean = synchronized(lock) {
        if (!ready() || !repository.canUseSnapshots(DeviceCapability.MUTATE_PERMISSIONS) || categories.isEmpty()) return@synchronized false
        val row = q.incomingPolicy(account, peer).executeAsOneOrNull() ?: return@synchronized false
        val proposal = decode(row.proposal) ?: return@synchronized false
        if (row.state != "PENDING" || !proposal.categories.containsAll(categories) || proposal.expiresAt <= now()) return@synchronized false
        port.withSession(peer) { session ->
            if (session.binding != row.binding) return@withSession false
            val parent = proposal.head.entry()
            if (parent.policySequence !in 1 until Long.MAX_VALUE || parent.resourceEpoch <= 0) return@withSession false
            val candidate = SharingLedgerEntry(LedgerEntryId(UUID.randomUUID().toString()), PeerId(account), parent.policySequence + 1,
                parent.id, parent.authorityGeneration, parent.resourceEpoch, parent.deviceGeneration, account, "",
                SharingLedgerBody.RecipientAccepted(categories, DeviceId(session.local.device)))
            val signed = runBlocking { signer.sign(candidate) } ?: return@withSession false
            if (!ready() || proposal.expiresAt <= now() || !signer.verifier().verify(signed)) return@withSession false
            val wire = json.encodeToString(proposal.copy(action = "CONSENT", consent = LedgerWire.of(signed)))
            q.putIncomingPolicy(account, peer, row.binding, row.proposal, wire, "ACCEPTED")
            true
        } ?: false
    }
}
