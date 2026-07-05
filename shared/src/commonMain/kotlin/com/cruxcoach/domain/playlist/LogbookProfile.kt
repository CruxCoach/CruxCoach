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
    /** SECOND-hardest send — the outlier-robust "repeatable max". One
     *  lucky 7b against a 7a/7a+ background must not anchor the session
     *  a grade past reality; the second-best send is the classic robust
     *  estimator of what the climber can actually reproduce. */
    val secondMaxDifficulty: Double? = null,
) {
    /** Effective max for planning: logbook max or the ~V5 default. The
     *  PEAK — used as the hard ceiling, not as the work anchor. */
    val effectiveMax: Double
        get() = maxDifficulty ?: TrainingRanges.DEFAULT_MAX_DIFFICULTY

    /** The work anchor: repeatable (second-best) max, falling back to the
     *  peak when the logbook carries fewer than two sends. All working
     *  bands derive from THIS; the peak only caps them. */
    val effectiveRepeatableMax: Double
        get() = secondMaxDifficulty ?: effectiveMax

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
            secondMaxDifficulty = sendDifficulties.sortedDescending().getOrNull(1),
        )
    }
}
