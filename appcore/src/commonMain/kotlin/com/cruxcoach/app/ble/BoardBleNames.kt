package com.cruxcoach.app.ble

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.relay.RelayBoardName

/**
 * A board seen in a scan. [identifier] is the CBPeripheral identifier UUID
 * string: iOS never exposes the MAC address, and the identifier is only stable
 * on this device.
 */
data class DiscoveredBoard(
    val displayName: String,
    val serial: String,
    val apiLevel: Int,
    val identifier: String,
    val rssi: Int,
    val boardBrand: BoardBrand = BoardBrand.KILTER,
    /** True when the endpoint is another CruxCoach user's connectable relay. */
    val isCruxRelay: Boolean = false,
    /** Runtime-only capacity observation; never persisted as a firmware fact. */
    val advertisesWhileConnected: Boolean? = null,
)

/** Pure advertised-name classifiers; port of Android's BoardBleScanner. */
object BoardBleNames {

    /** eWalls 2.0.14 prefixes; the last `_` segment must be the 12-hex controller identity. */
    fun quantumSerialOrNull(name: String): String? {
        if (!(name.startsWith("eWalls_") || name.startsWith("QB_") || name.startsWith("QBB_"))) return null
        val last = name.substringAfterLast('_')
        return last.takeIf { it.length == 12 && it.all(::isHexDigit) }
    }

    fun isMoonBoardName(name: String): Boolean =
        name.startsWith("MoonBoard") || name.startsWith("Moonboard")

    fun auroraBrandFromName(displayName: String): BoardBrand {
        val n = displayName.lowercase().replace(" ", "").replace("-", "")
        return when {
            n.startsWith("tension") -> BoardBrand.TENSION
            n.startsWith("grasshopper") -> BoardBrand.GRASSHOPPER
            n.startsWith("decoy") -> BoardBrand.DECOY
            n.startsWith("soill") -> BoardBrand.SOILL
            n.startsWith("touchstone") -> BoardBrand.TOUCHSTONE
            else -> BoardBrand.KILTER
        }
    }

    /** `Name#serial@api`, `Name#serial` (api 2), `Name@api` (serial ""), else null. */
    fun parseBoardName(name: String): Triple<String, String, Int>? {
        val hashIdx = name.indexOf('#')
        val atIdx = name.lastIndexOf('@')
        return when {
            hashIdx >= 0 && atIdx > hashIdx -> Triple(
                name.substring(0, hashIdx),
                name.substring(hashIdx + 1, atIdx),
                name.substring(atIdx + 1).toIntOrNull() ?: 2,
            )
            hashIdx >= 0 -> Triple(name.substring(0, hashIdx), name.substring(hashIdx + 1), 2)
            atIdx >= 0 -> {
                val api = name.substring(atIdx + 1).toIntOrNull() ?: return null
                Triple(name.substring(0, atIdx), "", api)
            }
            else -> null
        }
    }

    /** Classifies one advertisement exactly as Android's scan callback does; null = not a board. */
    fun classify(advertisedName: String, identifier: String, rssi: Int): DiscoveredBoard? {
        val isRelay = RelayBoardName.isRelayName(advertisedName)
        val boardName = RelayBoardName.unwrap(advertisedName)
        val quantumSerial = quantumSerialOrNull(boardName)
        return when {
            quantumSerial != null -> DiscoveredBoard(
                boardName, quantumSerial, 1, identifier, rssi, BoardBrand.QUANTUM, isRelay,
            )
            isMoonBoardName(boardName) -> DiscoveredBoard(
                boardName, "", 0, identifier, rssi, BoardBrand.MOONBOARD, isRelay,
            )
            else -> {
                val parsed = parseBoardName(boardName) ?: return null
                DiscoveredBoard(
                    parsed.first, parsed.second, parsed.third, identifier, rssi,
                    auroraBrandFromName(parsed.first), isRelay,
                )
            }
        }
    }

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
