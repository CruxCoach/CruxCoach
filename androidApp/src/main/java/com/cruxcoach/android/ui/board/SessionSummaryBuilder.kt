package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.board.IntensityZone
import com.cruxcoach.domain.board.IntensityZoneEngine
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.domain.board.KilterGradeMapper

/**
 * Pure builder for the end-of-playlist summary — extracted from
 * BoardBrowserViewModel.endSession() so the playlist player and the
 * browser produce the identical summary from one implementation.
 */
object SessionSummaryBuilder {

    fun build(
        ascents: List<AscentWithClimb>,
        zones: IntensityZones,
        gradeScale: GradeScale,
    ): EnhancedSessionSummary {
        val sends = ascents.filter { it.isSend }
        val diffs = sends.mapNotNull { it.difficultyAverage }
        val counts = diffs.groupBy { zones.classify(it) }

        val hardestSend = sends.maxByOrNull { it.difficultyAverage ?: 0.0 }
        val flashCount = sends.count { it.bidCount <= 1L }
        val uniqueClimbs = ascents.map { it.climbUuid }.distinct().size

        val gradePyramid = sends
            .filter { it.difficultyAverage != null }
            .groupBy { KilterGradeMapper.difficultyToVScale(it.difficultyAverage!!) }
            .map { (vGrade, list) ->
                BoardGradePyramidEntry(
                    grade = GradeDisplayHelper.formatGrade(vGrade, gradeScale),
                    count = list.size,
                    difficultyInt = list.first().difficultyAverage!!.toInt()
                )
            }
            .sortedBy { it.difficultyInt }

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
