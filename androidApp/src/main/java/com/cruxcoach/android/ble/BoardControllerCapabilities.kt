package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand

/** Number of apps a physical board controller can serve at the same time. */
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
    /** A relay only adds value for a verified single-connection Aurora controller. */
    val relaySupported: Boolean,
)

/**
 * One capability registry for connection, queue and relay UX.
 *
 * The advertised Aurora API level is the only machine-readable generation
 * signal currently available: API 2 is treated as the legacy exclusive
 * generation and API 3+ as the newer multi-client generation. The API level is
 * still a wire-protocol version, not an explicit connection-count bit, so every
 * newly supported controller generation must be verified on hardware. Unknown
 * controllers are treated conservatively: CruxCoach neither disconnects them
 * automatically nor offers a relay until their behaviour is verified.
 */
internal object BoardControllerProfiles {
    fun forBoard(board: DiscoveredBoard?): BoardControllerProfile = resolve(
        brand = board?.boardBrand,
        apiLevel = board?.apiLevel ?: 0,
        isCruxRelay = board?.isCruxRelay == true,
    )

    fun resolve(
        brand: BoardBrand?,
        apiLevel: Int,
        isCruxRelay: Boolean = false,
    ): BoardControllerProfile {
        if (isCruxRelay) {
            return BoardControllerProfile(
                connectionCapacity = BoardConnectionCapacity.MULTIPLE,
                projectionLifetime = BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT,
                relaySupported = false,
            )
        }

        if (brand == BoardBrand.MOONBOARD) {
            return BoardControllerProfile(
                connectionCapacity = BoardConnectionCapacity.MULTIPLE,
                projectionLifetime = BoardProjectionLifetime.UNTIL_LAST_CONNECTION,
                relaySupported = false,
            )
        }

        if (brand?.usesAuroraProtocol == true) {
            val capacity = when {
                apiLevel in 1..2 -> BoardConnectionCapacity.SINGLE
                apiLevel >= 3 -> BoardConnectionCapacity.MULTIPLE
                else -> BoardConnectionCapacity.UNKNOWN
            }
            return BoardControllerProfile(
                connectionCapacity = capacity,
                projectionLifetime = BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT,
                relaySupported = capacity == BoardConnectionCapacity.SINGLE,
            )
        }

        return BoardControllerProfile(
            connectionCapacity = BoardConnectionCapacity.UNKNOWN,
            projectionLifetime = BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT,
            relaySupported = false,
        )
    }
}
