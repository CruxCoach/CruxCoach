package com.cruxcoach.android.ui.fips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.IncomingControllerRequest
import com.cruxcoach.android.fips.FipsMeshRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
    val boardName: String? = null,
    val boardBrand: String? = null,
    val physicalBoardId: String? = null,
    val cellId: String? = null,
    val availability: String? = null,
    val localNpub: String? = null,
    val controllerNpub: String? = null,
    val memberCount: Int = 0,
    val peers: List<FipsMeshPeerUi> = emptyList(),
    val nearbyMeshes: List<NearbyFipsMeshUi> = emptyList(),
)

@HiltViewModel
class FipsMeshViewModel @Inject constructor(
    private val runtime: FipsMeshRuntime,
    private val boardCellManager: BoardCellManager,
    boardConnection: BoardBleConnection,
) : ViewModel() {
    private var autoJoinAttemptedCell: String? = null
    private val _joiningMeshName = MutableStateFlow<String?>(null)
    val joiningMeshName = _joiningMeshName.asStateFlow()
    val incomingControllerRequest = boardCellManager.incomingControllerRequest

    init {
        runtime.startNearbyDiscovery()
        viewModelScope.launch {
            boardCellManager.snapshots.filterNotNull().collect { _joiningMeshName.value = null }
        }
        viewModelScope.launch {
            combine(runtime.nearbyMeshes, boardCellManager.snapshots) { nearby, snapshot -> nearby to snapshot }
                .collect { (nearby, snapshot) ->
                    if (snapshot != null) return@collect
                    val only = nearby.filter { !it.matchesActiveRealm && it.joinableBoardCellId != null }
                        .singleOrNull() ?: return@collect
                    if (autoJoinAttemptedCell == only.joinableBoardCellId) return@collect
                    delay(1_500)
                    val stable = runtime.nearbyMeshes.value
                        .filter { !it.matchesActiveRealm && it.joinableBoardCellId != null }.singleOrNull()
                    if (stable?.joinableBoardCellId == only.joinableBoardCellId &&
                        boardCellManager.snapshots.value == null) {
                        autoJoinAttemptedCell = only.joinableBoardCellId
                        _joiningMeshName.value = only.boardName ?: "Board-Mesh"
                        if (!boardCellManager.joinNearbyMesh(only.joinableBoardCellId!!, only.boardName)) {
                            _joiningMeshName.value = null
                        }
                    }
                }
        }
    }

    val state = combine(
        runtime.running,
        runtime.peers,
        runtime.nearbyMeshes,
        boardCellManager.snapshots,
        boardConnection.connectedBoardDescriptor,
    ) { running, peers, nearby, snapshot, board ->
        val direct = runtime.directAuthenticatedPeers()
        FipsMeshUiState(
            running = running,
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
            nearbyMeshes = nearby.map {
                NearbyFipsMeshUi(
                    address = it.address,
                    realmTag = it.realmTag,
                    cellTag = it.cellTag,
                    rssi = it.rssi,
                    lastSeenMs = it.lastSeenMs,
                    currentMesh = it.matchesActiveRealm,
                    joinableBoardCellId = it.joinableBoardCellId,
                    boardName = it.boardName,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FipsMeshUiState())

    fun join(mesh: NearbyFipsMeshUi) {
        val cellId = mesh.joinableBoardCellId ?: return
        _joiningMeshName.value = mesh.boardName ?: "Board-Mesh"
        viewModelScope.launch {
            if (!boardCellManager.joinNearbyMesh(cellId, mesh.boardName)) _joiningMeshName.value = null
        }
    }

    fun ensureDiscovery() = runtime.startNearbyDiscovery()

    fun approveControllerTransfer(request: IncomingControllerRequest) =
        boardCellManager.approveControllerTransfer(request.requestId)

    fun denyControllerTransfer(request: IncomingControllerRequest) =
        boardCellManager.denyControllerTransfer(request.requestId)
}
