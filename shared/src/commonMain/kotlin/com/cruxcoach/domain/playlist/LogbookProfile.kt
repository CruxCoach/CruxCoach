package com.cruxcoach.domain.playlist

/**
 * The logbook-derived performance profile the planner works from. Pure
 * value object — the Android layer aggregates the raw rows (per target
 * angle when enough data exists there, else across all angles) and hands
 * in plain difficulty lists.
 *
 * All difficulties are Aurora-scale (10–34) community averages, the same
 * unit `climb_stats.difficulty_average` / denormalized ascent rows carry.
 */
data class LogbookProfile(
    /** Hardest send, or null with an empty logbook. */
    val maxDifficulty: Double?,
    /** Hardest flash (send with bid_count ≤ 1), or null. */
    val flashDifficulty: Double?,
    /** Send count backing the profile — below [MIN_SAMPLE] the UI should
     *  flag recommendations as defaults, not personal. */
    val sampleSize: Int,
    /** Open projects: attempted-but-unsent climb uuids, most recent first.
     *  PROJECTING slots prefer these over fresh candidates. */
    val openProjectUuids: List<String> = emptyList(),
) {
    /** Effective max for planning: logbook max or the ~V5 default. */
    val effectiveMax: Double
        get() = maxDifficulty ?: TrainingRanges.DEFAULT_MAX_DIFFICULTY

    /** Effective flash: logbook flash, else max − 2 V-grades. */
    val effectiveFlash: Double
        get() = flashDifficulty ?: (effectiveMax - TrainingRanges.FLASH_FALLBACK_OFFSET)

    /** True when the profile rests on real logbook data. */
    val isPersonalized: Boolean
        get() = maxDifficulty != null && sampleSize >= MIN_SAMPLE

    companion object {
        const val MIN_SAMPLE = 5

        /**
         * Builds the profile from raw logbook aggregates.
         *
         * @param sendDifficulties community difficulty of every send.
         * @param flashDifficulties subset of sends with bid_count ≤ 1.
         * @param openProjectUuids attempted-not-sent uuids, recent first.
         */
        fun fromLogbook(
            sendDifficulties: List<Double>,
            flashDifficulties: List<Double>,
            openProjectUuids: List<String> = emptyList(),
        ): LogbookProfile = LogbookProfile(
            maxDifficulty = sendDifficulties.maxOrNull(),
            flashDifficulty = flashDifficulties.maxOrNull(),
            sampleSize = sendDifficulties.size,
            openProjectUuids = openProjectUuids,
        )
    }
}
