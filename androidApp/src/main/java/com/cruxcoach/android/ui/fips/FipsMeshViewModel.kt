package com.cruxcoach.android.ui.fips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.IncomingControllerRequest
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.fips.FipsConnectionStage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
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

/** The canonical joinable playlist as the mesh status strip shows it. */
data class MeshPlaylistUi(
    val itemCount: Int,
    val memberCount: Int,
    val localIsMember: Boolean,
    val localIsHost: Boolean,
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
    activeCellId != null && it.joinableBoardCellId == activeCellId
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
    val membershipTransition = boardCellManager.membershipTransition
    val incomingControllerRequest = boardCellManager.incomingControllerRequest

    init {
        runtime.startNearbyDiscovery()
        viewModelScope.launch {
            boardCellManager.snapshots.filterNotNull().collect {
                _joiningBoardCellId.value = null
                _joinFailed.value = false
            }
        }
    }

    val state = combine(
        combine(runtime.running, runtime.bluetoothAvailable, runtime.connectionProgress) { running, bluetooth, progress ->
            Triple(running, bluetooth, progress.stage)
        },
        runtime.peers,
        runtime.nearbyMeshes,
        boardCellManager.snapshots,
        boardConnection.connectedBoardDescriptor,
    ) { transport, peers, nearby, snapshot, board ->
        val direct = runtime.directAuthenticatedPeers()
        FipsMeshUiState(
            running = transport.first,
            bluetoothAvailable = transport.second,
            joinStage = transport.third,
            boardName = board?.displayName ?: nearby.firstOrNull { it.matchesActiveRealm }?.boardName,
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
            playlist = snapshot?.playlist?.takeIf { it.isJoinable }?.let {
                MeshPlaylistUi(
                    itemCount = it.items.size,
                    memberCount = it.members.size,
                    localIsMember = runtime.localNpub in it.members,
                    localIsHost = it.hostId == runtime.localNpub,
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
}
