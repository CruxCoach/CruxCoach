package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.boardcell.BoardCellId
import com.cruxcoach.android.boardcell.PhysicalBoardIdentity
import com.cruxcoach.android.fips.FipsNearbyMesh

/** CruxRelay is a compatibility transport for Android 9. On mesh-capable
 * devices CruxCoach always presents the canonical board group instead. */
internal fun visibleBoardsForPlatform(
    boards: List<DiscoveredBoard>,
    meshAvailable: Boolean,
): List<DiscoveredBoard> = if (meshAvailable) boards.filterNot(DiscoveredBoard::isCruxRelay) else boards

internal fun visibleMeshesForPlatform(
    meshes: List<FipsNearbyMesh>,
    meshAvailable: Boolean,
): List<FipsNearbyMesh> = if (meshAvailable) meshes else emptyList()

/** One board group gets one choice in discovery: its mesh, physical board, or
 * relay must never be listed as competing ways to join the same board. */
internal fun visibleStandaloneBoards(
    boards: List<DiscoveredBoard>,
    nearbyMeshes: List<FipsNearbyMesh>,
    activeBoardCellId: String?,
    activeMeshBoardName: String?,
): List<DiscoveredBoard> {
    val meshCells = nearbyMeshes.mapNotNull { it.joinableBoardCellId }.toSet()
    return boards.filter { board ->
        val cell = runCatching {
            BoardCellId.forPhysical(PhysicalBoardIdentity.resolve(board)).value
        }.getOrNull()
        val relayForActiveMesh = activeBoardCellId != null && board.isCruxRelay &&
            sameBoardLabel(board.displayName, activeMeshBoardName)
        !relayForActiveMesh && cell != activeBoardCellId && cell !in meshCells
    }
}

/** Relay names may be byte-trimmed for BLE; accept that one normalized label
 * can be the prefix of the other, but never associate an absent label. */
private fun sameBoardLabel(discovered: String, active: String?): Boolean {
    val left = discovered.trim().lowercase()
    val right = active?.trim()?.lowercase().orEmpty()
    return left.isNotEmpty() && right.isNotEmpty() &&
        (left == right || left.startsWith(right) || right.startsWith(left))
}
