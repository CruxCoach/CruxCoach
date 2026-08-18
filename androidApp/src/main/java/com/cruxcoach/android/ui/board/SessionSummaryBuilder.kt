package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.board.IntensityZone
import com.cruxcoach.domain.board.IntensityZoneEngine
import com.cruxcoach.domain.board.IntensityZones

/**
 * Pure builder for the end-of-playlist summary — extracted from
 * BoardBrowserViewModel.endSession() so the playlist player and the
 * browser produce the identical summary from one implementation.
 */
object SessionSummaryBuilder {

    /**
     * @param trueFlashUuids Send uuids that are TRUE flashes over the FULL
     *   logbook history ([BoardStatsComputer.trueFlashUuids]) — a first-try
     *   repeat of an old project is a redpoint, not a flash, so the naive
     *   `bidCount <= 1` per-session detection must not be used here.
     */
    fun build(
        ascents: List<AscentWithClimb>,
        zones: IntensityZones,
        gradeScale: GradeScale,
        trueFlashUuids: Set<String>,
    ): EnhancedSessionSummary {
        val sends = ascents.filter { it.isSend }
        val diffs = sends.mapNotNull { it.difficultyAverage }
        val counts = diffs.groupBy { zones.classify(it) }

        val hardestSend = sends.maxByOrNull { it.difficultyAverage ?: 0.0 }
        val flashCount = sends.count { it.uuid in trueFlashUuids }
        val uniqueClimbs = ascents.map { it.climbUuid }.distinct().size

        // Same displayed-grade grouping as the stats sheet — the old V-scale
        // detour merged Font grades sharing a V bucket (7b and 7b+).
        val gradePyramid = BoardStatsComputer.computeGradePyramid(sends, gradeScale)

        return EnhancedSessionSummary(
            warmupCount = counts[IntensityZone.WARMUP]?.size ?: 0,
            optimalCount = counts[IntensityZone.OPTIMAL]?.size ?: 0,
            limitCount = counts[IntensityZone.LIMIT]?.size ?: 0,
            sessionType = IntensityZoneEngine.classifySession(diffs, zones),
            hardestSendGrade = hardestSend?.difficultyAverage?.let {
                GradeDisplayHelper.formatDifficulty(it, gradeScale)
            },
            hardestSendName = hardestSend?.climbName,
            flashCount = flashCount,
            totalSends = sends.size,
            totalAttempts = ascents.count { !it.isSend },
            uniqueClimbs = uniqueClimbs,
            gradeDistribution = gradePyramid
        )
    }
}
