package com.cruxcoach.app.ble

import com.cruxcoach.domain.board.BoardBrand

/**
 * Quantum-specific part of the link. Until the ordered setup is ported, a
 * Quantum controller is refused outright: it never reaches CONNECTED, so no
 * write can be attempted against it.
 */
internal class QuantumLinkSupport(private val controller: BoardConnectionController) {
    fun onConnectStarting(board: DiscoveredBoard) = Unit
    fun cancelOperations() = Unit
    fun markStale() = Unit
    fun onReady(link: BoardConnectionController.Link) = Unit
    fun onRead(characteristicUuid: String, value: ByteArray?) = Unit
    fun onNotifyState(characteristicUuid: String, enabled: Boolean, success: Boolean) = Unit
    fun onNotification(characteristicUuid: String, value: ByteArray) = Unit

    fun beginSetup(link: BoardConnectionController.Link) {
        check(link.board.boardBrand == BoardBrand.QUANTUM)
        controller.failAndDisconnect(BoardConnectFailure.CONNECT_FAILED)
    }
}
