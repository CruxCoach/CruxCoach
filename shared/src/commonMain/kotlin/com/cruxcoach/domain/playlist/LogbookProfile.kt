package com.cruxcoach.domain.playlist

import kotlin.math.roundToInt

/**
 * One logged send, as the profile needs it.
 *
 * [climbedAt] is an ISO-8601 date or timestamp; only the leading `yyyy-MM-dd`
 * is ever compared, so plain string ordering is enough and no date library is
 * needed down here. Cutoffs come in ready-made from the platform layer.
 */
data class LoggedSend(
    val climbUuid: String,
    val difficulty: Double,
    val climbedAt: String,
)

/**
 * The logbook-derived performance profile the planner works from. Pure value
 * object — the platform layer picks the angle pool and hands in plain records.
 *
 * All difficulties are Aurora-scale (10–34) community averages, the same unit
 * `climb_stats.difficulty_average` and the denormalized ascent rows carry.
 * Every integer step is one Font grade; the V-scale is a naming convention
 * laid over it and is not used for arithmetic anywhere in this package.
 */
data class LogbookProfile(
    /** Hardest send ever, or null with an empty logbook. The ceiling. */
    val maxDifficulty: Double?,
    /** Hardest flash (send with bid_count ≤ 1) ever, or null. */
    val flashDifficulty: Double?,
    /** Send count backing the profile — below [MIN_SAMPLE] the UI should flag
     *  recommendations as defaults, not personal. */
    val sampleSize: Int,
    /** Open projects: attempted-but-unsent climb uuids, most recent first.
     *  PROJECTING slots prefer these over fresh candidates. */
    val openProjectUuids: List<String> = emptyList(),
    /**
     * The work anchor: the mean of the climber's hardest recent sends.
     *
     * Replaces the old "second-hardest send". That was an order statistic at a
     * fixed position, which means something different at every logbook size —
     * the 83rd percentile over six sends, the 99.7th over six hundred. So the
     * more a climber logged, the harder they were anchored, which is backwards:
     * more evidence should make the estimate steadier, not more extreme.
     *
     * See [anchorOf] for how many sends go in and why.
     */
    val anchorDifficulty: Double? = null,
    /** Same estimator over flashes — the volume anchor. */
    val flashAnchorDifficulty: Double? = null,
    /** SECOND-hardest send. Kept only as the fallback when there is not enough
     *  history for [anchorDifficulty]. */
    val secondMaxDifficulty: Double? = null,
    /** Second-hardest true flash; fallback for [flashAnchorDifficulty]. */
    val secondFlashDifficulty: Double? = null,
) {
    /** Effective max for planning: logbook max or the ~V5 default. The PEAK —
     *  used as the hard ceiling, never as the work anchor. */
    val effectiveMax: Double
        get() = maxDifficulty ?: TrainingRanges.DEFAULT_MAX_DIFFICULTY

    /** The work anchor. All working bands derive from THIS; the peak only caps
     *  them. */
    val effectiveRepeatableMax: Double
        get() = anchorDifficulty ?: secondMaxDifficulty ?: effectiveMax

    /** Effective flash: logbook flash, else derived from the max. */
    val effectiveFlash: Double
        get() = flashDifficulty ?: (effectiveMax - TrainingRanges.FLASH_FALLBACK_STEPS)

    /**
     * Volume anchor: the robust flash.
     *
     * With enough history [flashAnchorDifficulty] already averages the hardest
     * recent flashes and needs no further guard. Without it the old bounded
     * demotion still applies — flashes are sparse, so a single lucky one may
     * pull the band at most one Font step past the second-best rather than
     * setting it outright. Failing both, it derives from the work anchor, not
     * from the peak, which would inflate the volume band as well.
     */
    val effectiveRepeatableFlash: Double
        get() {
            flashAnchorDifficulty?.let { return it }
            val top = flashDifficulty
                ?: return effectiveRepeatableMax - TrainingRanges.FLASH_FALLBACK_STEPS
            val floor = top - TrainingRanges.DIFF_PER_FONT_STEP
            return maxOf(secondFlashDifficulty ?: floor, floor)
        }

    /** True when the profile rests on real logbook data. */
    val isPersonalized: Boolean
        get() = maxDifficulty != null && sampleSize >= MIN_SAMPLE

    companion object {
        const val MIN_SAMPLE = 5

        /** Below this many sends in a window, widen to the next one. */
        const val MIN_WINDOW_SENDS = 5

        /** Share of the recent sends that define the anchor. */
        const val ANCHOR_TOP_FRACTION = 0.10

        /**
         * Never fewer than this — a handful of sends still has to produce an
         * anchor, and a single one would be a coin flip.
         */
        const val ANCHOR_MIN_COUNT = 3

        /**
         * Never more than this. Past roughly two dozen, each further climb only
         * pulls the mean down toward everyday terrain; the cap keeps a large
         * logbook from anchoring its owner below their own working level, while
         * still diluting any single fluke to a twenty-fifth of its deviation.
         */
        const val ANCHOR_MAX_COUNT = 25

        /**
         * Mean of the hardest [ANCHOR_TOP_FRACTION] of [sends], within the
         * first [cutoffs] window that holds enough of them.
         *
         * Two things make this robust where the old second-hardest was not.
         * The count scales with the sample, so a lucky send is one voice among
         * many exactly when there have been many chances to get lucky. And the
         * sends are deduplicated per climb first: a 4x4 session logs sixteen
         * sends on four problems, and without that, training volume would count
         * as evidence of ability and a climber doing lots of intervals would
         * quietly lower their own anchor.
         *
         * @param cutoffs ISO dates, newest window first. Each is tried in turn;
         *   if none holds [MIN_WINDOW_SENDS], the whole logbook is used.
         */
        fun anchorOf(sends: List<LoggedSend>, cutoffs: List<String>): Double? {
            if (sends.isEmpty()) return null
            val windows = cutoffs.map { cutoff -> sends.filter { it.climbedAt >= cutoff } } + listOf(sends)
            val pool = windows.firstOrNull { bestPerClimb(it).size >= MIN_WINDOW_SENDS }
                ?: sends
            val best = bestPerClimb(pool)
            if (best.isEmpty()) return null
            val k = (best.size * ANCHOR_TOP_FRACTION).roundToInt()
                .coerceIn(ANCHOR_MIN_COUNT, ANCHOR_MAX_COUNT)
                .coerceAtMost(best.size)
            return best.sortedDescending().take(k).average().roundToInt().toDouble()
        }

        /** One value per climb — the hardest send of it. */
        private fun bestPerClimb(sends: List<LoggedSend>): List<Double> =
            sends.groupBy { it.climbUuid }
                .map { (_, group) -> group.maxOf { it.difficulty } }

        /**
         * Builds the profile from raw logbook records.
         *
         * @param sends every send in the angle pool, with dates and climb ids.
         * @param flashes the subset with bid_count ≤ 1.
         * @param openProjectUuids attempted-not-sent uuids, recent first.
         * @param recencyCutoffs ISO dates, newest window first — see [anchorOf].
         */
        fun fromLogbook(
            sends: List<LoggedSend>,
            flashes: List<LoggedSend>,
            openProjectUuids: List<String> = emptyList(),
            recencyCutoffs: List<String> = emptyList(),
        ): LogbookProfile = LogbookProfile(
            maxDifficulty = sends.maxOfOrNull { it.difficulty },
            flashDifficulty = flashes.maxOfOrNull { it.difficulty },
            sampleSize = sends.size,
            openProjectUuids = openProjectUuids,
            anchorDifficulty = anchorOf(sends, recencyCutoffs),
            flashAnchorDifficulty = anchorOf(flashes, recencyCutoffs),
            secondMaxDifficulty = sends.map { it.difficulty }.sortedDescending().getOrNull(1),
            secondFlashDifficulty = flashes.map { it.difficulty }.sortedDescending().getOrNull(1),
        )
    }
}
