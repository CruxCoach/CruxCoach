package com.cruxcoach.android.ui.fips

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.IncomingControllerRequest
import com.cruxcoach.android.boardcell.BoardCellPlatformPolicy
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.fips.FipsConnectionStage
import com.cruxcoach.android.fips.FipsPeer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FipsMeshPeerUi(
    val npub: String,
    val transport: String,
    val lastSeenMs: Long,
    val directAuthenticated: Boolean,
    val member: Boolean,
    val controller: Boolean,
    val displayName: String? = null,
)

data class NearbyFipsMeshUi(
    val address: String,
    val realmTag: String,
    val cellTag: String,
    val rssi: Int,
    val lastSeenMs: Long,
    val currentMesh: Boolean,
    val joinableBoardCellId: String?,
    val boardName: String?,
    val psm: Int,
)

data class FipsMeshUiState(
    val running: Boolean = false,
    val bluetoothAvailable: Boolean = true,
    val boardName: String? = null,
    val boardBrand: String? = null,
    val physicalBoardId: String? = null,
    val cellId: String? = null,
    val availability: String? = null,
    val localNpub: String? = null,
    val controllerNpub: String? = null,
    val memberCount: Int = 0,
    val joinStage: FipsConnectionStage = FipsConnectionStage.IDLE,
    val peers: List<FipsMeshPeerUi> = emptyList(),
    val nearbyMeshes: List<NearbyFipsMeshUi> = emptyList(),
    /** The BoardCell's one shared playlist, if there is one. */
    val playlist: MeshPlaylistUi? = null,
)

/** The canonical joinable playlist as the mesh status strip shows it. */
data class MeshPlaylistUi(
    val itemCount: Int,
    val memberCount: Int,
    val localIsMember: Boolean,
    val localIsHost: Boolean,
) {
    /** Board membership is playlist membership; there is never a second join. */
    val offersJoin: Boolean get() = false
}

/** True exactly when the join button should be offered and would work. */
val FipsMeshUiState.canJoinPlaylist: Boolean
    get() = availability == "ACTIVE" && playlist?.offersJoin == true

internal fun visibleNearbyMeshes(
    nearby: List<com.cruxcoach.android.fips.FipsNearbyMesh>,
    activeCellId: String?,
): List<com.cruxcoach.android.fips.FipsNearbyMesh> = nearby.filterNot {
    // The active radio observes advertisements from its own realm. During a
    // short coordinator restore window the canonical snapshot (and therefore
    // activeCellId) can still be null. Never offer that advertisement as a
    // fresh join: doing so would tear down the healthy controller transport.
    it.matchesActiveRealm || (activeCellId != null && it.joinableBoardCellId == activeCellId)
}

/**
 * Native FIPS deliberately retains a disconnected peer for a bounded repair
 * window. Once BoardCell has canonically removed that npub, keeping it in the
 * "connected peers" UI is misleading. This is display-only: the native cache
 * and admission state remain untouched, so a later MemberJoined makes the
 * peer visible again immediately.
 */
internal fun visibleCanonicalPeers(
    peers: List<FipsPeer>,
    canonicalMembers: Set<String>?,
): List<FipsPeer> = peers.filter { peer ->
    peer.connected && (canonicalMembers == null || peer.npub in canonicalMembers)
}

@HiltViewModel
class FipsMeshViewModel @Inject constructor(
    private val runtime: FipsMeshRuntime,
    private val boardCellManager: BoardCellManager,
    boardConnection: BoardBleConnection,
) : ViewModel() {
    private val meshAvailable = BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)
    private val _joiningBoardCellId = MutableStateFlow<String?>(null)
    val joiningBoardCellId = _joiningBoardCellId.asStateFlow()
    private val _joinFailed = MutableStateFlow(false)
    val joinFailed = _joinFailed.asStateFlow()
    private val _leaveFailed = MutableStateFlow(false)
    val leaveFailed = _leaveFailed.asStateFlow()
    /** Stable display metadata; nearby advertisements are intentionally TTL-bound. */
    private val _activeBoardName = MutableStateFlow<String?>(null)
    val membershipTransition = boardCellManager.membershipTransition
    val incomingJoinRequests = boardCellManager.incomingJoinRequests
    val joinRetryAfterEpochMs = boardCellManager.joinRetryAfterEpochMs
    val incomingControllerRequest = boardCellManager.incomingControllerRequest

    init {
        if (meshAvailable) runtime.startNearbyDiscovery()
        viewModelScope.launch {
            boardCellManager.snapshots.collect { snapshot ->
                if (snapshot == null) {
                    _activeBoardName.value = null
                } else {
                    runtime.nearbyMeshes.value.firstOrNull {
                        it.joinableBoardCellId == snapshot.cellId.value || it.matchesActiveRealm
                    }?.boardName?.let { _activeBoardName.value = it }
                    _joiningBoardCellId.value = null
                    _joinFailed.value = false
                }
            }
        }
        viewModelScope.launch {
            runtime.nearbyMeshes.collect { nearby ->
                val cellId = boardCellManager.snapshots.value?.cellId?.value ?: return@collect
                nearby.firstOrNull {
                    it.joinableBoardCellId == cellId || it.matchesActiveRealm
                }?.boardName?.let { _activeBoardName.value = it }
            }
        }
    }

    val state = combine(
        combine(runtime.running, runtime.bluetoothAvailable, runtime.connectionProgress) { running, bluetooth, progress ->
            Triple(running, bluetooth, progress.stage)
        },
        runtime.peers,
        runtime.nearbyMeshes,
        combine(boardCellManager.snapshots, _activeBoardName) { snapshot, name -> snapshot to name },
        combine(boardConnection.connectedBoardDescriptor, boardCellManager.peerDiagnostics) { board, diagnostics ->
            board to diagnostics
        },
    ) { transport, peers, nearby, active, boardState ->
        val (board, diagnostics) = boardState
        val (snapshot, retainedBoardName) = active
        val direct = runtime.directAuthenticatedPeers.value
        FipsMeshUiState(
            running = transport.first,
            bluetoothAvailable = transport.second,
            joinStage = transport.third,
            boardName = board?.displayName ?: retainedBoardName
                ?: nearby.firstOrNull { it.matchesActiveRealm }?.boardName,
            boardBrand = board?.boardBrand?.name,
            physicalBoardId = snapshot?.physicalBoardId?.value,
            cellId = snapshot?.cellId?.value,
            availability = snapshot?.availability?.name,
            localNpub = runtime.localNpub.takeIf { it.isNotBlank() },
            controllerNpub = snapshot?.controllerId,
            memberCount = snapshot?.members?.size ?: 0,
            peers = visibleCanonicalPeers(peers, snapshot?.members).map { peer ->
                FipsMeshPeerUi(
                    npub = peer.npub,
                    transport = peer.transport,
                    lastSeenMs = peer.lastSeenMs,
                    directAuthenticated = peer.npub in direct,
                    member = peer.npub in snapshot?.members.orEmpty(),
                    controller = peer.npub == snapshot?.controllerId,
                    displayName = diagnostics[peer.npub]?.displayName,
                )
            },
            playlist = snapshot?.playlist?.takeIf { it.isJoinable }?.let {
                MeshPlaylistUi(
                    itemCount = it.items.size,
                    memberCount = it.members.size,
                    localIsMember = runtime.localNpub in it.members,
                    localIsHost = it.hostId == runtime.localNpub,
                )
            },
            nearbyMeshes = if (meshAvailable) visibleNearbyMeshes(nearby, snapshot?.cellId?.value).map {
                NearbyFipsMeshUi(
                    address = it.address,
                    realmTag = it.realmTag,
                    cellTag = it.cellTag,
                    rssi = it.rssi,
                    lastSeenMs = it.lastSeenMs,
                    // Realm-tag equality only says the radio is currently
                    // trying that realm; it does not mean membership/ownership.
                    currentMesh = false,
                    joinableBoardCellId = it.joinableBoardCellId,
                    boardName = it.boardName,
                    psm = it.psm,
                )
            } else emptyList(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FipsMeshUiState())

    fun join(mesh: NearbyFipsMeshUi) {
        if (!meshAvailable) return
        val cellId = mesh.joinableBoardCellId ?: return
        _joinFailed.value = false
        _joiningBoardCellId.value = cellId
        viewModelScope.launch {
            try {
                val joined = try {
                    boardCellManager.joinNearbyMesh(
                        cellId,
                        mesh.boardName,
                        mesh.address,
                        mesh.psm,
                        mesh.rssi,
                    )
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    false
                }
                if (joined) _activeBoardName.value = mesh.boardName
                _joinFailed.value = !joined
            } finally {
                _joiningBoardCellId.value = null
            }
        }
    }

    fun ensureDiscovery() {
        if (meshAvailable) runtime.startNearbyDiscovery()
    }

    fun leave() {
        _leaveFailed.value = false
        viewModelScope.launch {
            _leaveFailed.value = !boardCellManager.leaveMeshForBoardDisconnect()
        }
    }

    fun dismissJoinError() { _joinFailed.value = false }

    fun approveControllerTransfer(request: IncomingControllerRequest) =
        boardCellManager.approveControllerTransfer(request.requestId)

    fun denyControllerTransfer(request: IncomingControllerRequest) =
        boardCellManager.denyControllerTransfer(request.requestId)

    fun allowBoardJoin(request: com.cruxcoach.android.boardcell.IncomingBoardJoinRequest) =
        boardCellManager.decideBoardJoin(request.requestId, request.candidateId, approved = true)

    fun denyBoardJoin(request: com.cruxcoach.android.boardcell.IncomingBoardJoinRequest) =
        boardCellManager.decideBoardJoin(request.requestId, request.candidateId, approved = false)
}
