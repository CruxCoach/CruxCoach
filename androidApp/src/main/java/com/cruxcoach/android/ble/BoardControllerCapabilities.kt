package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand

/** Operational connection capacity inferred for the current controller session. */
internal enum class BoardConnectionCapacity {
    SINGLE,
    MULTIPLE,
    UNKNOWN,
}

internal object BoardConnectionOwner {
    const val SESSION = "session"
    const val RELAY = "relay"
}

/** What happens to the LEDs after the final controller connection closes. */
internal enum class BoardProjectionLifetime {
    RETAINED_AFTER_DISCONNECT,
    UNTIL_LAST_CONNECTION,
}

internal data class BoardControllerProfile(
    val connectionCapacity: BoardConnectionCapacity,
    val projectionLifetime: BoardProjectionLifetime,
    /** A relay only adds value when an Aurora controller appears exclusive. */
    val relaySupported: Boolean,
)

/**
 * One capability registry for connection, queue and relay UX.
 *
 * Neither the board family nor Aurora's advertised API level says how many
 * centrals the firmware accepts. CruxCoach therefore observes whether the exact
 * controller keeps sending connectable advertisements after GATT is ready:
 * visible means another direct client can at least attempt to connect; not
 * observed means the controller is treated as operationally exclusive for this
 * connection. The negative observation is deliberately not persisted because
 * Android or radio conditions can hide advertisements.
 *
 * Unknown controllers are treated conservatively until that short probe has
 * completed. CruxRelay itself is known to be a multi-client endpoint.
 */
internal object BoardControllerProfiles {
    fun forBoard(board: DiscoveredBoard?): BoardControllerProfile = resolve(
        brand = board?.boardBrand,
        isCruxRelay = board?.isCruxRelay == true,
        advertisesWhileConnected = board?.advertisesWhileConnected,
    )

    fun resolve(
        brand: BoardBrand?,
        isCruxRelay: Boolean = false,
        advertisesWhileConnected: Boolean? = null,
    ): BoardControllerProfile {
        if (isCruxRelay) {
            return BoardControllerProfile(
                connectionCapacity = BoardConnectionCapacity.MULTIPLE,
                projectionLifetime = BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT,
                relaySupported = false,
            )
        }

        val capacity = when (advertisesWhileConnected) {
            true -> BoardConnectionCapacity.MULTIPLE
            false -> BoardConnectionCapacity.SINGLE
            null -> BoardConnectionCapacity.UNKNOWN
        }
        return BoardControllerProfile(
            connectionCapacity = capacity,
            projectionLifetime = if (brand == BoardBrand.MOONBOARD) {
                BoardProjectionLifetime.UNTIL_LAST_CONNECTION
            } else {
                BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT
            },
            relaySupported = brand?.usesAuroraProtocol == true &&
                capacity == BoardConnectionCapacity.SINGLE,
        )
    }
}
