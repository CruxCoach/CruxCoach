package com.cruxcoach.android.data

import com.cruxcoach.data.repository.AuroraClimbWithStats
import com.cruxcoach.data.repository.BoardRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Resolved climb display info: name + raw difficulty for formatting. */
data class ClimbDisplayInfo(
    val name: String,
    val difficultyAverage: Double?
)

/**
 * Centralized climb name resolution that handles UUID format differences
 * between BLE protocol (uppercase-no-hyphens) and database (lowercase-with-hyphens).
 *
 * Previously duplicated in NearbyPresenceManager, BoardStateManager,
 * SessionQueueManager — each with subtly different resolution logic,
 * causing some paths to fail on UUID formats that others handled fine.
 */
@Singleton
class ClimbNameResolver @Inject constructor(
    private val boardRepository: BoardRepository
) {
    /**
     * Resolves a climb name by UUID, tolerating format differences:
     * 1. Raw UUID as-is
     * 2. Lowercase
     * 3. Uppercase
     * 4. Hyphenated lowercase (e.g. "305ecf35-4ab5-4c9c-afd5-91af0848004b")
     * 5. Hyphenated uppercase
     *
     * @param angle used to select the correct climb_stats entry.
     *              Pass 0 or any value when only the name is needed — the name
     *              is the same across all angles for a given UUID.
     */
    fun resolveName(uuid: String, angle: Int = 0): String? {
        return resolveClimb(uuid, angle)?.name
    }

    /** Resolves name + difficulty for display in BLE chips/banners. */
    fun resolveInfo(uuid: String, angle: Int = 0): ClimbDisplayInfo? {
        val climb = resolveClimb(uuid, angle) ?: return null
        return ClimbDisplayInfo(climb.name, climb.difficultyAverage)
    }

    private fun resolveClimb(uuid: String, angle: Int): AuroraClimbWithStats? {
        boardRepository.getClimbByUuid(uuid, angle)?.let { return it }
        boardRepository.getClimbByUuid(uuid.lowercase(), angle)?.let { return it }
        boardRepository.getClimbByUuid(uuid.uppercase(), angle)?.let { return it }
        // BLE protocol decodes as uppercase-no-hyphens; DB may store lowercase-with-hyphens
        val bare = uuid.replace("-", "")
        if (bare.length == 32) {
            val hyphenated = "${bare.substring(0, 8)}-${bare.substring(8, 12)}-" +
                "${bare.substring(12, 16)}-${bare.substring(16, 20)}-${bare.substring(20)}"
            boardRepository.getClimbByUuid(hyphenated.lowercase(), angle)?.let { return it }
            boardRepository.getClimbByUuid(hyphenated.uppercase(), angle)?.let { return it }
        }
        return null
    }
}
