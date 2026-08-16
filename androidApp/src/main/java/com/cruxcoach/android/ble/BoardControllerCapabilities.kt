package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand

/**
 * Operational connection capacity inferred for the current controller session.
 *
 * [UNKNOWN] is no longer produced by [BoardControllerProfiles] — an
 * unclassified controller is treated as [SINGLE], which is what real hardware
 * does. It remains for call sites that reason about "no board at all".
 */
internal enum class BoardConnectionCapacity {
    SINGLE,
    MULTIPLE,
    UNKNOWN,
}

internal object BoardConnectionOwner {
    const val SESSION = "session"
    const val RELAY = "relay"
    const val BOARD_MESH = "board-mesh"
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
 * **Every physical controller counts as exclusive.**
 * That is not a guess about firmware, it is how the boards behave and how their
 * own apps treat them: the Kilter app tries each of a wall's endpoints in turn
 * and, when none accepts, can only say "both signals are busy or out of range"
 * — it cannot tell an occupied controller from an absent one either. It ships
 * an inactivity auto-disconnect for the same reason: the board has to be handed
 * back before the next climber can use it.
 *
 * Advertising while connected is not proof of spare connection capacity. Some
 * controllers keep publishing connectable advertisements but reject a second
 * GATT central. A single phone cannot distinguish that from genuine multi-client
 * support without another device actually connecting, so using the advertisement
 * as proof produced a dangerous false positive. Physical boards therefore stay
 * conservative regardless of that legacy observation.
 *
 * CruxRelay is our own endpoint, so its multi-client capacity is known outright.
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

        // A continued advertisement does not prove that a second central will
        // be accepted. Only CruxRelay's capacity is known by construction.
        val capacity = BoardConnectionCapacity.SINGLE
        return BoardControllerProfile(
            connectionCapacity = capacity,
            projectionLifetime = if (brand == BoardBrand.MOONBOARD) {
                BoardProjectionLifetime.UNTIL_LAST_CONNECTION
            } else {
                BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT
            },
            relaySupported = brand?.isInteractive == true,
        )
    }
}
