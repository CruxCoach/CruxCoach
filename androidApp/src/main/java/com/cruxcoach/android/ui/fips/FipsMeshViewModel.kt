package com.cruxcoach.android.ui.fips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.IncomingControllerRequest
import com.cruxcoach.android.boardcell.IncomingJoinRequest
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.fips.FipsConnectionStage
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
    /**
     * The BoardCell's one joinable playlist, if there is one.
     *
     * Being in the mesh makes this visible and nothing more: joining the
     * playlist is a separate, explicit act, which is why the state carries
     * both "a playlist exists" and "am I in it" rather than collapsing them.
     */
    val playlist: MeshPlaylistUi? = null,
)

/** One entry in the climber lineup. */
data class MeshLineupEntryUi(
    val ownerNpub: String,
    val climbUuid: String,
    val angle: Int,
    val isCurrent: Boolean,
    val isDone: Boolean,
)

/** The canonical joinable playlist as the mesh status strip shows it. */
data class MeshPlaylistUi(
    val itemCount: Int,
    val memberCount: Int,
    val localIsMember: Boolean,
    val localIsHost: Boolean,
    val closed: Boolean = false,
    val lineup: List<MeshLineupEntryUi> = emptyList(),
) {
    /**
     * Whether to offer the join button.
     *
     * Only a cell member that is not already in the playlist can join, and
     * only when there is something to join. The caller additionally gates on
     * the cell being ACTIVE, which is what [FipsMeshUiState.canJoinPlaylist]
     * folds in.
     */
    val offersJoin: Boolean get() = !localIsMember && itemCount > 0
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

@HiltViewModel
class FipsMeshViewModel @Inject constructor(
    private val runtime: FipsMeshRuntime,
    private val boardCellManager: BoardCellManager,
    boardConnection: BoardBleConnection,
) : ViewModel() {
    private val _joiningBoardCellId = MutableStateFlow<String?>(null)
    val joiningBoardCellId = _joiningBoardCellId.asStateFlow()
    private val _joinFailed = MutableStateFlow(false)
    val joinFailed = _joinFailed.asStateFlow()
    private val _leaveFailed = MutableStateFlow(false)
    val leaveFailed = _leaveFailed.asStateFlow()
    /** Stable display metadata; nearby advertisements are intentionally TTL-bound. */
    private val _activeBoardName = MutableStateFlow<String?>(null)
    val membershipTransition = boardCellManager.membershipTransition
    val incomingControllerRequest = boardCellManager.incomingControllerRequest
    val incomingJoinRequests = boardCellManager.incomingJoinRequests

    init {
        runtime.startNearbyDiscovery()
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
        boardConnection.connectedBoardDescriptor,
    ) { transport, peers, nearby, active, board ->
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
            peers = peers.filter { it.connected }.map { peer ->
                FipsMeshPeerUi(
                    npub = peer.npub,
                    transport = peer.transport,
                    lastSeenMs = peer.lastSeenMs,
                    directAuthenticated = peer.npub in direct,
                    member = peer.npub in snapshot?.members.orEmpty(),
                    controller = peer.npub == snapshot?.controllerId,
                )
            },
            playlist = snapshot?.playlist?.takeIf { it.isJoinable }?.let { pl ->
                MeshPlaylistUi(
                    itemCount = pl.items.size,
                    memberCount = pl.members.size,
                    localIsMember = runtime.localNpub in pl.members,
                    localIsHost = pl.hostId == runtime.localNpub,
                    closed = pl.closed,
                    lineup = pl.items.mapIndexed { index, entry ->
                        MeshLineupEntryUi(
                            ownerNpub = entry.ownerId,
                            climbUuid = entry.climbUuid,
                            angle = entry.angle,
                            isCurrent = index == pl.currentIndex,
                            isDone = pl.currentIndex >= 0 && index < pl.currentIndex,
                        )
                    },
                )
            },
            nearbyMeshes = visibleNearbyMeshes(nearby, snapshot?.cellId?.value).map {
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
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FipsMeshUiState())

    fun join(mesh: NearbyFipsMeshUi) {
        val cellId = mesh.joinableBoardCellId ?: return
        _joinFailed.value = false
        _joiningBoardCellId.value = cellId
        viewModelScope.launch {
            try {
                val joined = try {
                    boardCellManager.joinNearbyMesh(cellId, mesh.boardName)
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

    fun ensureDiscovery() = runtime.startNearbyDiscovery()

    fun leave() {
        _leaveFailed.value = false
        viewModelScope.launch {
            _leaveFailed.value = !boardCellManager.leaveCurrentMesh()
        }
    }

    fun dismissJoinError() { _joinFailed.value = false }

    fun approveControllerTransfer(request: IncomingControllerRequest) =
        boardCellManager.approveControllerTransfer(request.requestId)

    fun denyControllerTransfer(request: IncomingControllerRequest) =
        boardCellManager.denyControllerTransfer(request.requestId)

    fun approveJoinRequest(request: IncomingJoinRequest) =
        boardCellManager.approveJoinRequest(request.requestId)

    fun denyJoinRequest(request: IncomingJoinRequest) =
        boardCellManager.denyJoinRequest(request.requestId)
}
