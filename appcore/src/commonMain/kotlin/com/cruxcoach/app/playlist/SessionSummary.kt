package com.cruxcoach.app.playlist

import com.cruxcoach.app.logbook.BoardGradePyramidEntry
import com.cruxcoach.app.logbook.BoardStatsComputer
import com.cruxcoach.app.settings.GradeScale
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.IntensityZone
import com.cruxcoach.domain.board.IntensityZoneEngine
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.domain.board.SessionType
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn

/** Android `EnhancedSessionSummary`: what the end-of-session sheet shows beyond the clock. */
data class EnhancedSessionSummary(
    val warmupCount: Int = 0,
    val optimalCount: Int = 0,
    val limitCount: Int = 0,
    val sessionType: SessionType = SessionType.PYRAMID_SESSION,
    val hardestSendGrade: String? = null,
    val hardestSendName: String? = null,
    val flashCount: Int = 0,
    val totalSends: Int = 0,
    val totalAttempts: Int = 0,
    val uniqueClimbs: Int = 0,
    val gradeDistribution: List<BoardGradePyramidEntry> = emptyList(),
) {
    val zoneTotal: Int get() = warmupCount + optimalCount + limitCount
}

/**
 * Pure builder for the end-of-playlist summary — port of Android
 * `SessionSummaryBuilder`, so the player produces the identical summary.
 */
object SessionSummaryBuilder {

    /**
     * [trueFlashUuids] are send uuids that are TRUE flashes over the FULL
     * logbook history ([BoardStatsComputer.trueFlashUuids]): a first-try repeat
     * of an old project is a redpoint, not a flash.
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

        // Same displayed-grade grouping as the stats sheet.
        val gradePyramid = BoardStatsComputer.computeGradePyramid(sends, gradeScale)

        return EnhancedSessionSummary(
            warmupCount = counts[IntensityZone.WARMUP]?.size ?: 0,
            optimalCount = counts[IntensityZone.OPTIMAL]?.size ?: 0,
            limitCount = counts[IntensityZone.LIMIT]?.size ?: 0,
            sessionType = IntensityZoneEngine.classifySession(diffs, zones),
            hardestSendGrade = hardestSend?.difficultyAverage?.let { BoardStatsComputer.formatDifficulty(it, gradeScale) },
            hardestSendName = hardestSend?.climbName,
            flashCount = flashCount,
            totalSends = sends.size,
            totalAttempts = ascents.sumOf { it.bidCount.coerceAtLeast(1L) }.toInt(),
            uniqueClimbs = uniqueClimbs,
            gradeDistribution = gradePyramid,
        )
    }

    /** All log rows of a session window: sends AND attempts. */
    fun sessionRows(all: List<AscentWithClimb>, from: String, to: String): List<AscentWithClimb> =
        all.filter { it.climbedAt >= from && it.climbedAt <= to }.sortedBy { it.climbedAt }
}

/** An ended session and, when the logbook holds rows of its window, their summary. */
data class FinishedSession(val session: RecordedSession, val summary: EnhancedSessionSummary?)

/**
 * Builds the sheet's data for an ended session the way Android's player does:
 * personal intensity zones from the last 90 days (Android
 * `IntensityZoneManager.recompute`), true flashes over the full history.
 */
class BoardSessionSummarizer(
    private val repository: PersonalBoardRepository,
    private val gradeScale: () -> GradeScale,
    private val cutoffDate: () -> String = {
        Clock.System.todayIn(TimeZone.currentSystemDefault()).minus(90, DateTimeUnit.DAY).toString()
    },
) {
    fun summarize(session: RecordedSession): FinishedSession {
        val summary = try {
            val all = repository.getUserLogbookAllLight()
            val rows = SessionSummaryBuilder.sessionRows(all, session.startedAt, session.endedAt)
            if (rows.isEmpty()) {
                // Android then falls back to the session's own counters.
                null
            } else {
                val cutoff = cutoffDate()
                val zones = IntensityZoneEngine.computeZones(
                    repository.getUserSendDifficulties(cutoff) + repository.getUserBidDifficulties(cutoff),
                )
                SessionSummaryBuilder.build(rows, zones, gradeScale(), BoardStatsComputer.trueFlashUuids(all))
            }
        } catch (e: Exception) {
            null
        }
        return FinishedSession(session, summary)
    }
}
