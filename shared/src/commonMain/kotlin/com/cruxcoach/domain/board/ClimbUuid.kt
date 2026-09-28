package com.cruxcoach.domain.board

/**
 * Climb-uuid spellings, in one place.
 *
 * The board catalogue does not agree with itself. Measured on the published
 * Kilter catalogue of 2026-09-20 (240 836 climbs):
 *
 * | spelling          | climbs  | origin                                |
 * |-------------------|---------|---------------------------------------|
 * | nodash-UPPERCASE  | 131 058 | `/climbs/curated` (legacy)            |
 * | nodash-lowercase  |  68 950 | `/climbs` API rows                    |
 * | dashed-lowercase  |  40 828 | new-world / BoardSesh sweep           |
 *
 * A logbook carries whatever its source produced: one 11-entry Kilter
 * account held all three forms at once, and a backup restore lowercases
 * every `climb_uuid` on the way in, changing the spelling again.
 *
 * So a climb must be addressable whatever the two sides happen to spell.
 * [normKey] is the comparison key; [spellings] is the candidate list for a
 * lookup that wants to stay on the uuid index instead of scanning.
 */
object ClimbUuid {

    /** Spelling-agnostic comparison key: hyphens stripped, lowercased. */
    fun normKey(uuid: String): String = uuid.replace("-", "").lowercase()

    /**
     * Every spelling [uuid] may be stored under, [uuid] itself first so an
     * exact hit costs a single indexed point lookup. Deduplicated, so an
     * already-canonical uuid does not widen the query.
     *
     * Callers that can afford a scan should still fall back to a normalized
     * comparison (`BoardRepository.findClimbCanonicalUuid`,
     * `getClimbByUuidNormalized`) for spellings nobody has seen yet.
     */
    fun spellings(uuid: String): List<String> {
        val nodash = uuid.replace("-", "")
        val out = LinkedHashSet<String>(6)
        out.add(uuid)
        out.add(nodash.uppercase())
        out.add(nodash.lowercase())
        // Canonical 8-4-4-4-12 grouping. Guarded on length: a non-uuid key
        // (community rows carry free-form ids) must not be re-cut into a
        // string that could collide with an unrelated climb.
        if (nodash.length == UUID_HEX_LENGTH) {
            val dashed = buildString(36) {
                append(nodash, 0, 8); append('-')
                append(nodash, 8, 12); append('-')
                append(nodash, 12, 16); append('-')
                append(nodash, 16, 20); append('-')
                append(nodash, 20, 32)
            }
            out.add(dashed.lowercase())
            out.add(dashed.uppercase())
        }
        return out.toList()
    }

    private const val UUID_HEX_LENGTH = 32
}
