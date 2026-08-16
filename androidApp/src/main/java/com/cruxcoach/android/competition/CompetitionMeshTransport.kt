package com.cruxcoach.android.competition

import android.util.Log
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.fips.FipsMeshRuntime
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

/** Signed competition streams scoped to the currently active physical BoardCell. */
@Singleton
class CompetitionMeshTransport @Inject constructor(
    private val runtime: FipsMeshRuntime,
    private val client: CompetitionRelayClient,
    private val credentials: CompetitionLocalCredentialStore,
    private val eventStore: CompetitionLocalEventStore,
    private val boardConnection: BoardBleConnection,
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
        val local = synchronized(joined) { joined[wire.compId] } ?: return
        if (wire.compId.isBlank() || wire.cellId.isBlank() || wire.physicalBoardId.isBlank() ||
            wire.participantCredential.length != 48 || wire.epoch <= 0 || wire.sequence < 0) return
        // A competition realm is tied to one concrete board cell. Merely
        // knowing the comp id must not let a peer from another board inject or
        // request its private history.
        if (wire.cellId != local.cellId || wire.physicalBoardId != local.physicalBoardId) return
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
        runCatching { eventStore.put(wire.compId, event.id, event.toJson()) }
            .onFailure { Log.w(TAG, "could not persist received competition event", it) }
        client.ingestMesh(event, System.currentTimeMillis() / 1_000)
    }

    /**
     * Join this competition as a scoped stream on the active board realm.
     *
     * Competition used to replace the BoardCell realm here. That froze the
     * board controller at exactly the moment the host needed to send climbs.
     * Keeping both protocols on the authenticated board realm makes the
     * concrete board the physical scope; compId still separates logical logs.
     */
    suspend fun joinLocal(compId: String): Boolean {
        if (isJoined(compId)) return true
        synchronized(joined) { joined.keys.filter { it != compId } }.forEach(::leave)
        val snapshot = BoardCellManager.current?.snapshot() ?: return false
        val membership = Membership(snapshot.cellId.value, snapshot.physicalBoardId.value,
            System.currentTimeMillis(), credentials.getOrCreate(compId))
        val owner = FipsMeshRuntime.competitionOwner(compId)
        runtime.acquire(owner)
        if (!runtime.running.value) {
            runtime.release(owner)
            return false
        }
        boardConnection.acquireKeepAlive(KEEP_ALIVE_OWNER)
        synchronized(joined) { joined[compId] = membership }
        restoreLocalHistory(compId, membership)
        requestHistory(compId)
        return true
    }

    fun leave(compId: String) {
        if (synchronized(joined) { joined.remove(compId) } != null) {
            runtime.release(FipsMeshRuntime.competitionOwner(compId))
            boardConnection.releaseKeepAlive(KEEP_ALIVE_OWNER)
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
        eventStore.put(compId, event.id, event.toJson())
        return realmPeers().count { runtime.send(it, encoded.encodeToByteArray()) }
    }

    /** Restore definition first; the reducer can then accept chained entries in any order. */
    private fun restoreLocalHistory(compId: String, membership: Membership) {
        val events = runCatching { eventStore.load(compId) }
            .onFailure { Log.w(TAG, "could not restore local competition history", it) }
            .getOrDefault(emptyList()).mapNotNull { raw ->
            runCatching { Event.fromJson(raw) }.getOrNull()
        }.sortedWith(
            compareBy<Event> {
                if (it.tags.any { tag -> tag.size >= 2 && tag[0] == "l" && tag[1] == "competition" }) 0 else 1
            }.thenBy { it.createdAt }.thenBy { it.id },
        )
        events.forEach { event ->
            membership.sequence++
            val wire = Wire(
                compId = compId,
                cellId = membership.cellId,
                physicalBoardId = membership.physicalBoardId,
                epoch = membership.epoch,
                sequence = membership.sequence,
                participantCredential = membership.credential,
                eventJson = event.toJson(),
            )
            synchronized(history) {
                history.getOrPut(compId) { linkedMapOf() }[event.id] = json.encodeToString(wire)
            }
            client.ingestMesh(event, System.currentTimeMillis() / 1_000)
        }
    }

    private fun requestHistory(compId: String) {
        val local = synchronized(joined) { joined[compId] } ?: return
        val request = json.encodeToString(Wire("competition_request", compId, local.cellId,
            local.physicalBoardId, local.epoch, 0, local.credential)).encodeToByteArray()
        realmPeers().forEach { runtime.send(it, request) }
    }

    private fun realmPeers(): Sequence<String> = runtime.peers.value.asSequence()
        .filter { it.connected && it.npub != runtime.localNpub }.map { it.npub }.distinct()

    companion object {
        private const val TAG = "CompetitionMesh"
        private const val KEEP_ALIVE_OWNER = "competition-mesh"
        @Volatile internal var current: CompetitionMeshTransport? = null
    }
}
