package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand

/** Board-family differences that affect BLE projection lifecycle. */
internal object BoardProjectionPolicy {

    /**
     * Stock MoonBoard controllers clear their LEDs when the final GATT client
     * disconnects. Aurora-family controllers retain the last projection.
     */
    fun projectionSurvivesDisconnect(brand: BoardBrand?): Boolean =
        brand != BoardBrand.MOONBOARD

    /**
     * A session host must release the controller for an actual successor.
     * Without a successor, disconnect only when the controller itself keeps
     * the projection; otherwise a solo MoonBoard session would end by
     * needlessly turning off the final climb.
     */
    fun shouldReleaseBoardAfterHosting(
        hasSuccessor: Boolean,
        projectionSurvivesDisconnect: Boolean,
        connectionCapacity: BoardConnectionCapacity,
        pinnedByAnotherFeature: Boolean = false,
    ): Boolean = connectionCapacity == BoardConnectionCapacity.SINGLE &&
        !pinnedByAnotherFeature &&
        (hasSuccessor || projectionSurvivesDisconnect)

    /** Keep a successfully projected MoonBoard climb alive by retaining GATT. */
    fun shouldArmIdleDisconnect(
        seconds: Int,
        connectionState: ConnectionState,
        explicitlySuppressed: Boolean,
        connectionCapacity: BoardConnectionCapacity,
        projectionSurvivesDisconnect: Boolean,
    ): Boolean =
        seconds > 0 &&
            connectionState == ConnectionState.CONNECTED &&
            !explicitlySuppressed &&
            connectionCapacity == BoardConnectionCapacity.SINGLE &&
            projectionSurvivesDisconnect

    /** MoonBoard climbs carry wire-ready frames instead of Aurora hold rows. */
    fun hasSendablePayload(
        brand: BoardBrand?,
        holdCount: Int,
        frames: String?,
    ): Boolean = when (brand) {
        BoardBrand.MOONBOARD -> !frames.isNullOrBlank()
        null -> false
        else -> holdCount > 0
    }
}
