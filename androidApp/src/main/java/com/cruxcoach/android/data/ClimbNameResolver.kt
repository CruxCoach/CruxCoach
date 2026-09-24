package com.cruxcoach.android.data

import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.getClimbByUuidAnySpelling
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

    fun resolveClimb(uuid: String, angle: Int): ClimbWithStats? {
        // The BLE protocol decodes uppercase-no-hyphens while the catalogue
        // stores three spellings; the ladder that used to stand here tried
        // only the dashed forms once the input was dashed, so a dashed uuid
        // could never reach the 200 008 nodash rows. One spelling-blind
        // lookup instead — the per-variant "via=" labels went with it, since
        // there is no longer a variant that can be the reason for a miss.
        boardRepository.getClimbByUuidAnySpelling(uuid, angle)?.let {
            android.util.Log.d(TAG, "resolved uuid=${uuid.take(8)}… angle=$angle")
            return it
        }
        // Final-fallback diagnose: differentiate "uuid not in climbs at
        // all" (= bundle-coverage gap, e.g. Homewall climb on an Original
        // user's bundle) vs "uuid in climbs but no climb_stats row at the
        // requested angle" (= cross-angle JOIN miss but the UI's detail
        // page would still find it via the LEFT JOIN). The probe asks with
        // angle=0, which never matches a real stats row → if THAT also
        // returns null, the row is genuinely missing from the climbs table.
        // Spelling-blind too, so a miss here can only mean absence.
        val anyAngleHit = boardRepository.getClimbByUuidAnySpelling(uuid, 0)
        val reason = if (anyAngleHit != null) "stats-missing-for-angle=$angle"
        else "uuid-not-in-climbs (bundle-coverage gap)"
        android.util.Log.w(TAG, "UNRESOLVED uuid=$uuid angle=$angle reason=$reason")
        return null
    }

    private companion object {
        const val TAG = "ClimbNameResolver"
    }
}
