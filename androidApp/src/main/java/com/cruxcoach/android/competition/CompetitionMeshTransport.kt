package com.cruxcoach.android.competition

import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.fips.FipsRealmContext
import com.cruxcoach.android.fips.FipsRealmKind
import com.vitorpamplona.quartz.nip01Core.core.Event
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Signed local events in a competition realm; each participant may carry a distinct BoardCell. */
@Singleton
class CompetitionMeshTransport @Inject constructor(
    private val runtime: FipsMeshRuntime,
    private val client: CompetitionRelayClient,
    private val credentials: CompetitionLocalCredentialStore,
) {
    @Serializable private data class Wire(
        val type: String = "competition_event",
        val compId: String,
        val cellId: String,
        val physicalBoardId: String,
        val epoch: Long,
        val sequence: Long,
        val participantCredential: String,
        val eventJson: String = "",
    )
    private data class Membership(
        val cellId: String,
        val physicalBoardId: String,
        val epoch: Long,
        val credential: String,
        var sequence: Long = 0,
    )
    private data class RemoteProgress(
        val cellId: String,
        val physicalBoardId: String,
        var epoch: Long,
        var sequence: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val joined = mutableMapOf<String, Membership>()
    private val remotes = mutableMapOf<String, RemoteProgress>() // comp|credential
    private val history = mutableMapOf<String, LinkedHashMap<String, String>>()
    private val json = Json { ignoreUnknownKeys = false }

    init {
        current = this
        scope.launch { runtime.messages.collect(::receive) }
        // Initial requests can precede Noise/direct-join completion; retry whenever the
        // authenticated graph changes. Discovery itself remains inside FipsBleRadio.
        scope.launch { runtime.peers.collect { synchronized(joined) { joined.keys.toList() }.forEach(::requestHistory) } }
    }

    private fun receive(incoming: com.cruxcoach.android.fips.AuthenticatedFipsMessage) {
        val wire = runCatching { json.decodeFromString<Wire>(incoming.payload.decodeToString()) }.getOrNull() ?: return
        synchronized(joined) { joined[wire.compId] } ?: return
        if (wire.compId.isBlank() || wire.cellId.isBlank() || wire.physicalBoardId.isBlank() ||
            wire.participantCredential.length != 48 || wire.epoch <= 0 || wire.sequence < 0) return
        val remoteKey = "${wire.compId}|${wire.participantCredential}"
        val progress = synchronized(remotes) { remotes[remoteKey] }
        if (progress == null) {
            // The local user explicitly entered this competition realm. FIPS authenticates
            // the end sender even across transit; CCJ1 remains link-local and is never used
            // as (or exposed as) a relayable membership proof here.
            synchronized(remotes) {
                remotes[remoteKey] = RemoteProgress(wire.cellId, wire.physicalBoardId, wire.epoch, 0)
            }
        } else if (progress.cellId != wire.cellId ||
            progress.physicalBoardId != wire.physicalBoardId) return

        if (wire.type == "competition_request") {
            synchronized(history) { history[wire.compId]?.values?.toList().orEmpty() }
                .forEach { runtime.send(incoming.senderNpub, it.encodeToByteArray()) }
            return
        }
        val current = synchronized(remotes) { remotes.getValue(remoteKey) }
        if (wire.epoch < current.epoch || (wire.epoch == current.epoch && wire.sequence <= current.sequence)) return
        if (wire.epoch > current.epoch) current.apply { epoch = wire.epoch; sequence = 0 }
        if (wire.sequence != current.sequence + 1) {
            requestHistory(wire.compId)
            return // never skip a competition sequence gap
        }
        val event = runCatching { Event.fromJson(wire.eventJson) }.getOrNull() ?: return
        current.sequence = wire.sequence
        synchronized(history) { history.getOrPut(wire.compId) { linkedMapOf() }[event.id] = incoming.payload.decodeToString() }
        client.ingestMesh(event, System.currentTimeMillis() / 1_000)
    }

    /** Explicit competition open/create action switches to realm_id=competition_id. */
    fun joinLocal(compId: String): Boolean {
        if (isJoined(compId)) return true
        synchronized(joined) { joined.keys.filter { it != compId } }.forEach(::leave)
        val snapshot = BoardCellManager.current?.snapshot() ?: return false
        val membership = Membership(snapshot.cellId.value, snapshot.physicalBoardId.value,
            System.currentTimeMillis(), credentials.getOrCreate(compId))
        BoardCellManager.current?.freezeForTransportRealmSwitch()
        if (!runtime.activateRealm(FipsRealmContext(compId, membership.cellId, FipsRealmKind.COMPETITION))) return false
        synchronized(joined) { joined[compId] = membership }
        runtime.acquire()
        requestHistory(compId)
        return true
    }

    fun leave(compId: String) {
        if (synchronized(joined) { joined.remove(compId) } != null) {
            runtime.release()
            runtime.endRealm(compId)
            credentials.end(compId)
            synchronized(remotes) { remotes.keys.removeAll { it.startsWith("$compId|") } }
        }
    }
    fun isJoined(compId: String): Boolean = synchronized(joined) { compId in joined }

    fun publish(compId: String, event: Event): Int {
        val local = synchronized(joined) { joined[compId]?.also { it.sequence++ } } ?: return 0
        val wire = Wire(compId = compId, cellId = local.cellId, physicalBoardId = local.physicalBoardId,
            epoch = local.epoch, sequence = local.sequence, participantCredential = local.credential,
            eventJson = event.toJson())
        val encoded = json.encodeToString(wire)
        synchronized(history) { history.getOrPut(compId) { linkedMapOf() }[event.id] = encoded }
        return realmPeers().count { runtime.send(it, encoded.encodeToByteArray()) }
    }

    private fun requestHistory(compId: String) {
        val local = synchronized(joined) { joined[compId] } ?: return
        val request = json.encodeToString(Wire("competition_request", compId, local.cellId,
            local.physicalBoardId, local.epoch, 0, local.credential)).encodeToByteArray()
        realmPeers().forEach { runtime.send(it, request) }
    }

    private fun realmPeers(): Sequence<String> = runtime.peers.value.asSequence()
        .filter { it.connected && it.npub != runtime.localNpub }.map { it.npub }.distinct()

    companion object { @Volatile internal var current: CompetitionMeshTransport? = null }
}
